import Foundation
import CryptoKit

public struct Playlist: Codable, Identifiable, Equatable, Sendable {
    public var id: String
    public var name: String
    public var kind: String
    public var address: String
    public var username: String
    public var password: String
    public var epgURL: String
    public var allowHTTP: Bool
    public init(id: String = UUID().uuidString, name: String = "", kind: String = "xtream", address: String = "",
                username: String = "", password: String = "", epgURL: String = "", allowHTTP: Bool = false) {
        self.id=id; self.name=name; self.kind=kind; self.address=address; self.username=username
        self.password=password; self.epgURL=epgURL; self.allowHTTP=allowHTTP
    }
}
public struct Channel: Codable, Identifiable, Hashable, Sendable {
    public var id: String
    public var name: String
    public var group: String
    public var url: String
    public var logo: String
    public var guideID: String
    public var streamID: String
    public var height: Int
    public var fps: Double
    public var catchupDays: Int
    public init(id: String, name: String, group: String = "Other", url: String, logo: String = "", guideID: String = "",
                streamID: String = "", height: Int = 0, fps: Double = 0, catchupDays: Int = 0) {
        self.id=id; self.name=name; self.group=group; self.url=url; self.logo=logo; self.guideID=guideID
        self.streamID=streamID; self.height=height; self.fps=fps; self.catchupDays=catchupDays
    }
    public var quality: String { height >= 2000 ? "4K" : height >= 1000 ? "FHD" : height >= 700 ? "HD" : height > 0 ? "\(height)p" : "" }
    public var searchKey: String { TextMatch.normalize(name) }
}
public struct Programme: Codable, Hashable, Sendable {
    public var channelID: String
    public var title: String
    public var detail: String
    public var start: Date
    public var end: Date
    public init(channelID: String, title: String, detail: String = "", start: Date, end: Date) {
        self.channelID=channelID; self.title=title; self.detail=detail; self.start=start; self.end=end
    }
}
public struct Lineup: Codable, Sendable {
    public var channels: [Channel]
    public var epgURL: String
    public var savedAt: Date
    public init(channels: [Channel], epgURL: String = "", savedAt: Date = Date()) {
        self.channels=channels; self.epgURL=epgURL; self.savedAt=savedAt
    }
    public var groups: [String] {
        var seen=Set<String>()
        return channels.compactMap { seen.insert($0.group).inserted ? $0.group : nil }
    }
}
public enum SpareError: Error, LocalizedError, Equatable {
    case address, httpConsent, limit, playlist, authentication, network, status(Int), unavailable, storage
    public var errorDescription: String? {
        switch self {
        case .address: return "Enter a valid http:// or https:// address."
        case .httpConsent: return "This provider uses unencrypted HTTP. Enable HTTP for this playlist to continue."
        case .limit: return "The provider response is larger than this preview can safely read."
        case .playlist: return "No live channels were found. Check the playlist address and account details."
        case .authentication: return "The provider did not accept this account. Check the username and password."
        case .network: return "Could not reach the provider. Check your connection and try again."
        case .status(let code): return "The provider returned HTTP \(code). Try another channel or check the playlist."
        case .unavailable: return "This channel is not responding. Try another stream or reconnect."
        case .storage: return "Could not securely save the playlist. Check Keychain access and try again."
        }
    }
    public static func safe(_ error: Error) -> String { (error as? SpareError)?.errorDescription ?? SpareError.network.errorDescription! }
}
public enum TextMatch {
    public static func normalize(_ text: String) -> String {
        text.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "en_US_POSIX"))
            .replacingOccurrences(of: "[^a-z0-9]+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
    }
    public static func contains(_ text: String, phrase: String) -> Bool {
        let phrase=normalize(phrase)
        return !phrase.isEmpty && (" " + normalize(text) + " ").contains(" " + phrase + " ")
    }
    public static func stableID(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }
    public static func height(_ name: String) -> Int {
        let s=" " + normalize(name) + " "
        if s.range(of: #"\b(4k|uhd|2160p?|2169p?)\b"#, options: .regularExpression) != nil { return 2160 }
        if s.range(of: #"\b(fhd|1080[pi]?)\b"#, options: .regularExpression) != nil { return 1080 }
        if s.range(of: #"\b(hd|720p?)\b"#, options: .regularExpression) != nil { return 720 }
        return 0
    }
}
