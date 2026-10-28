import Foundation

public enum PlaylistService {
    public static func endpoint(_ source: Playlist, file: String = "player_api.php", action: String = "",
                                extra: [URLQueryItem] = []) throws -> String {
        var base=source.address.trimmingCharacters(in: .whitespacesAndNewlines)
        while base.hasSuffix("/") { base.removeLast() }
        if ["player_api.php","get.php","xmltv.php"].contains(URL(string: base)?.lastPathComponent ?? "") {
            base=String(base.prefix(upTo: base.lastIndex(of: "/")!))
        }
        let url=try HTTP.validate(base, allowHTTP: source.allowHTTP)
        guard url.query == nil, url.fragment == nil else { throw SpareError.address }
        var components=URLComponents(url: url.appendingPathComponent(file), resolvingAgainstBaseURL: false)!
        components.queryItems=[URLQueryItem(name: "username", value: source.username), URLQueryItem(name: "password", value: source.password)]
        if !action.isEmpty { components.queryItems!.append(URLQueryItem(name: "action", value: action)) }
        components.queryItems! += extra
        return components.url!.absoluteString
    }
    public static func load(_ source: Playlist, progress: @Sendable (String) async -> Void = { _ in }) async throws -> Lineup {
        _=try HTTP.validate(source.address, allowHTTP: source.allowHTTP)
        if source.kind == "m3u" {
            await progress("Downloading playlist…")
            let data=try await HTTP.get(source.address, allowHTTP: source.allowHTTP)
            await progress("Organizing channels…")
            var result=try parseM3U(data, allowHTTP: source.allowHTTP)
            if !source.epgURL.isEmpty { result.epgURL=source.epgURL }
            return result
        }
        await progress("Connecting to your provider…")
        let accountData=try await HTTP.get(try endpoint(source), allowHTTP: source.allowHTTP, limit: 2*1024*1024)
        if let account=try? JSONSerialization.jsonObject(with: accountData) as? [String: Any],
           let info=account["user_info"] as? [String: Any] {
            if String(describing: info["auth"] ?? "1") == "0" { throw SpareError.authentication }
        }
        await progress("Downloading channels and categories…")
        async let categoryData=HTTP.get(try endpoint(source, action: "get_live_categories"), allowHTTP: source.allowHTTP, limit: 4*1024*1024)
        async let streamData=HTTP.get(try endpoint(source, action: "get_live_streams"), allowHTTP: source.allowHTTP)
        let (categories, streams)=try await (categoryData, streamData)
        await progress("Organizing channels…")
        let channels=try parseXtream(streams, categories: categories, source: source)
        let epg=source.epgURL.isEmpty ? try endpoint(source, file: "xmltv.php") : source.epgURL
        return Lineup(channels: channels, epgURL: epg)
    }
    public static func parseXtream(_ data: Data, categories: Data, source: Playlist) throws -> [Channel] {
        guard let rows=try JSONSerialization.jsonObject(with: data) as? [[String: Any]], rows.count <= 100000 else { throw SpareError.playlist }
        let groups=(try? JSONSerialization.jsonObject(with: categories) as? [[String: Any]]) ?? []
        var names=[String: String]()
        for row in groups { names[string(row["category_id"])]=string(row["category_name"]) }
        let endpoint=try self.endpoint(source)
        let base=String(endpoint.prefix(upTo: endpoint.range(of: "/player_api.php")!.lowerBound))
        func encoded(_ text: String) -> String { text.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "" }
        var seen=Set<String>()
        let result=rows.compactMap { row -> Channel? in
            let id=string(row["stream_id"])
            guard !id.isEmpty, id.range(of: #"^[0-9]+$"#, options: .regularExpression) != nil, seen.insert(id).inserted else { return nil }
            let name=String(string(row["name"]).prefix(256))
            let rawHeight=(row["height"] as? NSNumber)?.intValue ?? 0
            let fps=Double(string(row["fps"] ?? row["frame_rate"])) ?? 0
            let direct=string(row["direct_source"])
            let url=(try? HTTP.validate(direct, allowHTTP: source.allowHTTP))?.absoluteString
                ?? "\(base)/live/\(encoded(source.username))/\(encoded(source.password))/\(id).m3u8"
            return Channel(id: "\(source.id):\(id)", name: name.isEmpty ? "Channel \(id)" : name,
                group: names[string(row["category_id"])] ?? "Other", url: url,
                logo: safeArtwork(string(row["stream_icon"])), guideID: string(row["epg_channel_id"]), streamID: id,
                height: (1...4320).contains(rawHeight) ? rawHeight : TextMatch.height(name),
                fps: (10...120).contains(fps) ? fps : 0,
                catchupDays: min(30, (row["tv_archive_duration"] as? NSNumber)?.intValue ?? Int(string(row["tv_archive_duration"])) ?? 0))
        }
        guard !result.isEmpty else { throw SpareError.playlist }
        return result
    }
    public static func parseM3U(_ data: Data, allowHTTP: Bool) throws -> Lineup {
        guard let text=String(data: data, encoding: .utf8), text.trimmingCharacters(in: .whitespacesAndNewlines.union(CharacterSet(charactersIn: "\u{feff}"))).hasPrefix("#EXTM3U") else { throw SpareError.playlist }
        let attributes=try! NSRegularExpression(pattern: #"([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')"#)
        func attrs(_ line: String) -> [String: String] {
            var result=[String:String]()
            for match in attributes.matches(in: line, range: NSRange(line.startIndex..., in: line)) {
                let key=String(line[Range(match.range(at: 1), in: line)!]).lowercased()
                let value=match.range(at: 2).location != NSNotFound ? match.range(at: 2) : match.range(at: 3)
                result[key]=String(line[Range(value, in: line)!])
            }
            return result
        }
        var channels=[Channel](), metadata=[String:String](), title="", group="", epg="", seen=Set<String>()
        for raw in text.components(separatedBy: .newlines) {
            let line=raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard line.count <= 32768 else { throw SpareError.limit }
            if line.hasPrefix("#EXTM3U") {
                let a=attrs(line); epg=a["url-tvg"] ?? a["x-tvg-url"] ?? ""
            } else if line.hasPrefix("#EXTINF:") {
                metadata=attrs(line); group=metadata["group-title"] ?? "Other"
                var quote: Character?=nil, comma: String.Index?
                for i in line.indices {
                    let c=line[i]
                    if c == quote { quote=nil } else if quote == nil && (c == "\"" || c == "'") { quote=c }
                    else if c == "," && quote == nil { comma=i; break }
                }
                title=comma.map { String(line[line.index(after: $0)...]) } ?? metadata["tvg-name"] ?? ""
            } else if line.hasPrefix("#EXTGRP:") { group=String(line.dropFirst(8)) }
            else if !line.isEmpty && !line.hasPrefix("#") {
                defer { metadata=[:]; title=""; group="" }
                guard (try? HTTP.validate(line, allowHTTP: allowHTTP)) != nil else { continue }
                let id=TextMatch.stableID(line)
                guard seen.insert(id).inserted else { continue }
                channels.append(Channel(id: id, name: title.isEmpty ? "Channel \(channels.count+1)" : String(title.prefix(256)),
                    group: group.isEmpty ? "Other" : String(group.prefix(128)), url: line, logo: safeArtwork(metadata["tvg-logo"] ?? ""),
                    guideID: metadata["tvg-id"] ?? "", height: TextMatch.height(title)))
                if channels.count > 100000 { throw SpareError.limit }
            }
        }
        guard !channels.isEmpty else { throw SpareError.playlist }
        return Lineup(channels: channels, epgURL: epg.components(separatedBy: ",").first ?? "")
    }
    public static func shortEPG(_ source: Playlist, channel: Channel) async throws -> [Programme] {
        guard source.kind == "xtream", !channel.streamID.isEmpty else { return [] }
        let url=try endpoint(source, action: "get_short_epg", extra: [.init(name: "stream_id", value: channel.streamID), .init(name: "limit", value: "12")])
        let data=try await HTTP.get(url, allowHTTP: source.allowHTTP, limit: 2*1024*1024, timeout: 8)
        let rows=(try JSONSerialization.jsonObject(with: data) as? [String:Any])?["epg_listings"] as? [[String:Any]] ?? []
        return rows.prefix(72).compactMap { row in
            guard let start=Double(string(row["start_timestamp"])), let end=Double(string(row["stop_timestamp"] ?? row["end_timestamp"])), end>start else { return nil }
            func decode(_ key: String) -> String {
                let raw=string(row[key])
                return Data(base64Encoded: raw).flatMap { String(data:$0,encoding:.utf8) } ?? raw
            }
            return Programme(channelID: channel.guideID.isEmpty ? channel.id : channel.guideID, title: decode("title"), detail: decode("description"),
                             start: Date(timeIntervalSince1970: start), end: Date(timeIntervalSince1970: end))
        }
    }
    public static func safeArtwork(_ value: String) -> String { (try? HTTP.validate(value, allowHTTP: false))?.absoluteString ?? "" }
    public static func playbackURLs(_ channel:Channel,source:Playlist)->[String] {
        var result=[channel.url]
        if source.kind=="xtream",!channel.streamID.isEmpty,
           var parts=URLComponents(string:channel.url),parts.percentEncodedPath.contains("/live/"),
           parts.percentEncodedPath.hasSuffix("/"+channel.streamID+".m3u8") {
            parts.percentEncodedPath=String(parts.percentEncodedPath.dropLast(5))+".ts"
            if let url=parts.url?.absoluteString { result.append(url) }
        }
        return result
    }
    static func string(_ value: Any?) -> String { value.map { String(describing: $0) } ?? "" }
}
