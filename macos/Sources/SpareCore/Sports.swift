import Foundation

public struct League: Identifiable, Hashable, Sendable {
    public let id: String, name: String, path: String
    public static let all: [League] = [
        .init(id:"NFL",name:"NFL",path:"football/nfl"),.init(id:"MLB",name:"MLB",path:"baseball/mlb"),
        .init(id:"NBA",name:"NBA",path:"basketball/nba"),.init(id:"NHL",name:"NHL",path:"hockey/nhl"),
        .init(id:"EPL",name:"Premier League",path:"soccer/eng.1"),.init(id:"MLS",name:"MLS",path:"soccer/usa.1"),
        .init(id:"UCL",name:"Champions League",path:"soccer/uefa.champions")]
}
public struct Team: Codable, Identifiable, Hashable, Sendable {
    public var id:String, name:String, nickname:String, abbreviation:String, logo:String
    public init(id:String,name:String,nickname:String,abbreviation:String,logo:String="") {
        self.id=id;self.name=name;self.nickname=nickname;self.abbreviation=abbreviation;self.logo=logo
    }
    public func mentioned(in text:String) -> Bool {
        [name,nickname].filter { TextMatch.normalize($0).count >= 4 }.contains { TextMatch.contains(text,phrase:$0) }
    }
}
public struct Game: Codable, Identifiable, Hashable, Sendable {
    public var id:String, league:String, name:String, start:Date, state:String, status:String
    public var home:Team, away:Team, homeScore:String, awayScore:String, networks:[String]
    public init(id:String,league:String,name:String,start:Date,state:String,status:String,home:Team,away:Team,
                homeScore:String="",awayScore:String="",networks:[String]=[]) {
        self.id=id;self.league=league;self.name=name;self.start=start;self.state=state;self.status=status
        self.home=home;self.away=away;self.homeScore=homeScore;self.awayScore=awayScore;self.networks=networks
    }
    public var live:Bool { state == "in" && start <= Date().addingTimeInterval(60) && start > Date().addingTimeInterval(-18*3600) }
    public var completed:Bool { state == "post" }
}
public struct StreamMatch:Identifiable,Sendable {
    public var channel:Channel, confidence:Int, reason:String
    public var id:String { channel.id }
}
public enum SportsMatcher {
    public static func find(_ game:Game, channels:[Channel], guide:[String:[Programme]], now:Date=Date()) -> [StreamMatch] {
        let noise=#"\b(replay|rerun|classic|highlights|pregame|postgame|preview|news|encore|condensed)\b"#
        var result=[StreamMatch]()
        for channel in channels {
            let listings=guide[GuideService.key(channel),default:[]]
            let programme=listings.first { $0.start <= now && $0.end>now }
            let title=programme?.title ?? ""
            let normalized=TextMatch.normalize(title)
            let excluded=normalized.range(of:noise,options:.regularExpression) != nil
            let bothGuide = !excluded && game.home.mentioned(in:title) && game.away.mentioned(in:title)
            let bothName = game.home.mentioned(in:channel.name) && game.away.mentioned(in:channel.name)
            let oneName = game.home.mentioned(in:channel.name) || game.away.mentioned(in:channel.name)
            let network=game.networks.contains { network in
                let key=TextMatch.normalize(network)
                return key.count >= 3 && TextMatch.contains(channel.name,phrase:key)
            }
            let confidence=bothGuide ? 100 : bothName ? 85 : oneName ? 65 : network ? 25 : 0
            guard confidence>0 else { continue }
            result.append(StreamMatch(channel:channel,confidence:confidence,
                reason:bothGuide ? "Game listed in guide" : bothName ? "Game in channel title" : oneName ? "Team channel · possible coverage" : "Broadcast network · possible coverage"))
        }
        // Coverage first, resolution next; unknown FPS never prevents playback or opens another connection.
        return result.sorted { a,b in
            let coverageA=a.confidence >= 80, coverageB=b.confidence >= 80
            if coverageA != coverageB { return coverageA }
            if a.channel.height != b.channel.height { return a.channel.height>b.channel.height }
            if a.confidence != b.confidence { return a.confidence>b.confidence }
            if a.channel.fps != b.channel.fps { return a.channel.fps>b.channel.fps }
            return a.channel.name.localizedStandardCompare(b.channel.name) == .orderedAscending
        }.prefix(100).map { $0 }
    }
}
public struct SportsResult:Sendable { public let games:[Game], failed:[String] }
public actor SportsService {
    private var cache=[String:(Date,[Game])]()
    public init() {}
    public func load(date:Date, leagues:Set<String>, force:Bool=false) async -> SportsResult {
        let formatter=DateFormatter();formatter.locale=Locale(identifier:"en_US_POSIX")
        formatter.dateFormat="yyyyMMdd";formatter.timeZone=TimeZone(identifier:"America/New_York")
        let calendar=Calendar.current
        let days=Set([formatter.string(from:calendar.startOfDay(for:date)),formatter.string(from:calendar.startOfDay(for:date).addingTimeInterval(86399))])
        var requests=[(League,String)](), games=[Game](), failed=[String]()
        for league in League.all where leagues.contains(league.id) {
            for day in days {
                let key=league.id+":"+day
                if !force,let cached=cache[key],Date().timeIntervalSince(cached.0)<60 { games += cached.1 }
                else { requests.append((league,day)) }
            }
        }
        await withTaskGroup(of:(String,[Game]?).self) { group in
            for (league,day) in requests {
                group.addTask {
                    let url="https://site.api.espn.com/apis/site/v2/sports/\(league.path)/scoreboard?dates=\(day)&limit=100"
                    do { return (league.id+":"+day,try Self.parse(try await HTTP.get(url,limit:3*1024*1024,timeout:8),league:league.id)) }
                    catch { return (league.id+":"+day,nil) }
                }
            }
            for await (key,entries) in group {
                if let entries { cache[key]=(Date(),entries); games += entries }
                else { failed.append(String(key.prefix(while: { $0 != ":" }))) }
            }
        }
        var seen=Set<String>()
        games=games.filter { seen.insert($0.id).inserted && (calendar.isDate($0.start,inSameDayAs:date) || $0.live && calendar.isDateInToday(date)) }
        return SportsResult(games:games.sorted { a,b in a.live != b.live ? a.live : a.start<b.start },failed:Array(Set(failed)).sorted())
    }
    public static func parse(_ data:Data,league:String) throws -> [Game] {
        guard let root=try JSONSerialization.jsonObject(with:data) as? [String:Any],
              let events=root["events"] as? [[String:Any]],events.count<=500 else { throw SpareError.playlist }
        let iso=ISO8601DateFormatter()
        let shortISO=DateFormatter()
        shortISO.locale=Locale(identifier:"en_US_POSIX");shortISO.dateFormat="yyyy-MM-dd'T'HH:mmXXXXX"
        return events.compactMap { event in
            guard let comp=(event["competitions"] as? [[String:Any]])?.first,
                  let sides=comp["competitors"] as? [[String:Any]],sides.count==2,
                  let start=iso.date(from:PlaylistService.string(comp["date"] ?? event["date"]))
                    ?? shortISO.date(from:PlaylistService.string(comp["date"] ?? event["date"])) else { return nil }
            func team(_ side:[String:Any]) -> Team {
                let t=side["team"] as? [String:Any] ?? [:]
                let rawLogo=PlaylistService.string(t["logo"])
                return Team(id:league+":"+PlaylistService.string(t["id"]),
                    name:PlaylistService.string(t["displayName"]),nickname:PlaylistService.string(t["name"]),
                    abbreviation:PlaylistService.string(t["abbreviation"]),
                    logo:PlaylistService.safeArtwork(rawLogo.replacingOccurrences(of:"/500/scoreboard/",with:"/500/")))
            }
            let home=sides.first { PlaylistService.string($0["homeAway"])=="home" } ?? sides[0]
            let away=sides.first { PlaylistService.string($0["homeAway"])=="away" } ?? sides[1]
            let status=(comp["status"] ?? event["status"]) as? [String:Any] ?? [:]
            let type=status["type"] as? [String:Any] ?? [:]
            var networks=(comp["broadcasts"] as? [[String:Any]] ?? []).flatMap { $0["names"] as? [String] ?? [] }
            networks += (comp["geoBroadcasts"] as? [[String:Any]] ?? []).compactMap { ($0["media"] as? [String:Any])?["shortName"] as? String }
            return Game(id:league+":"+PlaylistService.string(event["id"]),league:league,name:PlaylistService.string(event["name"]),
                start:start,state:PlaylistService.string(type["state"]),status:PlaylistService.string(type["shortDetail"] ?? type["description"]),
                home:team(home),away:team(away),homeScore:PlaylistService.string(home["score"]),awayScore:PlaylistService.string(away["score"]),
                networks:Array(Set(networks)).sorted())
        }
    }
}
