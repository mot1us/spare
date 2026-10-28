import Foundation
import CSpare

public enum GuideService {
    public static func key(_ channel: Channel) -> String { channel.guideID.isEmpty ? channel.id : channel.guideID }
    public static func load(_ address: String, allowHTTP: Bool) async throws -> [String:[Programme]] {
        let data=try await HTTP.get(address, allowHTTP: allowHTTP, limit: 64*1024*1024, timeout: 40)
        return try parse(data)
    }
    public static func parse(_ original: Data) throws -> [String:[Programme]] {
        var data=original
        if data.starts(with: [0x1f,0x8b]) {
            var expanded=Data(count: 96*1024*1024)
            let count=original.withUnsafeBytes { input in expanded.withUnsafeMutableBytes { output in
                spare_gunzip(input.baseAddress, input.count, output.baseAddress, output.count)
            }}
            guard count>0 else { throw SpareError.limit }
            expanded.count=count; data=expanded
        }
        guard data.count <= 96*1024*1024,
              data.range(of: Data("<!DOCTYPE".utf8)) == nil,
              data.range(of: Data("<!ENTITY".utf8)) == nil else { throw SpareError.playlist }
        let delegate=XMLGuide()
        let parser=XMLParser(data: data)
        parser.shouldResolveExternalEntities=false; parser.externalEntityResolvingPolicy = .never
        parser.delegate=delegate
        guard parser.parse(), !delegate.exceeded else { throw SpareError.playlist }
        return delegate.entries.mapValues { $0.sorted { $0.start < $1.start } }
    }
}
private final class XMLGuide: NSObject, XMLParserDelegate {
    var entries=[String:[Programme]](), exceeded=false
    private var channel="", title="", detail="", field="", buffer=""
    private var start: Date?, end: Date?, count=0, inProgramme=false
    private let formatter: DateFormatter = {
        let f=DateFormatter(); f.locale=Locale(identifier:"en_US_POSIX"); f.dateFormat="yyyyMMddHHmmss Z"; f.timeZone=TimeZone(secondsFromGMT:0); return f
    }()
    private func date(_ value: String?) -> Date? {
        guard let value else { return nil }
        let compact=value.trimmingCharacters(in:.whitespaces)
        return formatter.date(from:compact.count==14 ? compact+" +0000" : compact)
    }
    func parser(_ parser: XMLParser, foundInternalEntityDeclarationWithName name: String, value: String?) { exceeded=true; parser.abortParsing() }
    func parser(_ parser: XMLParser, foundExternalEntityDeclarationWithName name: String, publicID: String?, systemID: String?) { exceeded=true; parser.abortParsing() }
    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?, attributes a: [String:String]) {
        if name == "programme" {
            channel=String((a["channel"] ?? "").prefix(256)); start=date(a["start"]); end=date(a["stop"])
            title=""; detail=""; inProgramme=true
        } else if inProgramme && (name == "title" || name == "desc") { field=name; buffer="" }
    }
    func parser(_ parser: XMLParser, foundCharacters value: String) {
        if !field.isEmpty && buffer.count < 4096 { buffer += value.prefix(4096-buffer.count) }
    }
    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
        if name == field {
            if name == "title" && title.isEmpty { title=String(buffer.prefix(512)) }
            if name == "desc" && detail.isEmpty { detail=buffer }
            field=""; buffer=""
        } else if name == "programme" {
            inProgramme=false
            guard let start, let end, end>start, !channel.isEmpty, !title.isEmpty else { return }
            let now=Date()
            if end < now.addingTimeInterval(-3*86400) || start > now.addingTimeInterval(8*86400) { return }
            count += 1
            if count > 500000 { exceeded=true; parser.abortParsing(); return }
            if entries[channel,default:[]].count < 512 {
                entries[channel,default:[]].append(Programme(channelID:channel,title:title,detail:detail,start:start,end:end))
            }
        }
    }
}
