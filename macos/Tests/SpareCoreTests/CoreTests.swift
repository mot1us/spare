import XCTest
@testable import SpareCore

final class CoreTests:XCTestCase {
    func testXtreamKeepsHTTPSAndEncodesCredentials() throws {
        let source=Playlist(id:"one",name:"Test",address:"https://example.test/portal/",username:"a&b",password:"x/y?",allowHTTP:true)
        let endpoint=try PlaylistService.endpoint(source,action:"get_live_streams")
        XCTAssertTrue(endpoint.hasPrefix("https://example.test/portal/player_api.php?"))
        let query=URLComponents(string:endpoint)!.queryItems!
        XCTAssertEqual(query.first(where:{$0.name=="username"})?.value,"a&b")
        XCTAssertEqual(query.first(where:{$0.name=="password"})?.value,"x/y?")
        let channels=try PlaylistService.parseXtream(
            Data(#"[{"stream_id":12,"name":"ESPN FHD","category_id":"2","epg_channel_id":"espn"}]"#.utf8),
            categories:Data(#"[{"category_id":"2","category_name":"USA Sports"}]"#.utf8),source:source)
        XCTAssertEqual(channels[0].group,"USA Sports")
        XCTAssertEqual(channels[0].height,1080)
        XCTAssertTrue(channels[0].url.hasPrefix("https://"))
        XCTAssertEqual(channels[0].fps,0)
        let formats=PlaylistService.playbackURLs(channels[0],source:source)
        XCTAssertEqual(formats.count,2)
        XCTAssertTrue(formats[0].hasSuffix(".m3u8"))
        XCTAssertTrue(formats[1].hasSuffix(".ts"))
        XCTAssertTrue(formats[1].contains("x%2Fy%3F"))
    }
    func testHTTPRequiresConsentAndRejectsOtherSchemes() throws {
        XCTAssertThrowsError(try HTTP.validate("http://example.test/list",allowHTTP:false)) { XCTAssertEqual($0 as? SpareError,.httpConsent) }
        XCTAssertNoThrow(try HTTP.validate("http://example.test/list",allowHTTP:true))
        XCTAssertThrowsError(try HTTP.validate("file:///etc/passwd",allowHTTP:true))
        XCTAssertThrowsError(try HTTP.validate("https://user:secret@example.test/",allowHTTP:true))
    }
    func testM3UQuotedCommaGroupsAndDuplicateStreams() throws {
        let text="""
        #EXTM3U url-tvg="https://example.test/epg.xml"
        #EXTINF:-1 tvg-id="KING" group-title="Seattle, WA",KING 5 FHD
        https://example.test/stream
        #EXTINF:-1 group-title="Duplicate",Ignored
        https://example.test/stream
        #EXTINF:-1 group-title="Unsafe",Local file
        file:///tmp/secret
        """
        let lineup=try PlaylistService.parseM3U(Data(text.utf8),allowHTTP:false)
        XCTAssertEqual(lineup.channels.count,1)
        XCTAssertEqual(lineup.channels[0].group,"Seattle, WA")
        XCTAssertTrue(lineup.channels[0].searchKey.contains("king 5"))
        XCTAssertEqual(lineup.epgURL,"https://example.test/epg.xml")
    }
    func testXMLGuideRejectsEntitiesAndUsesTimeZones() throws {
        let now=Date(),f=DateFormatter();f.dateFormat="yyyyMMddHHmmss Z";f.timeZone=TimeZone(secondsFromGMT:0)
        let text="<tv><programme channel=\"one\" start=\"\(f.string(from:now))\" stop=\"\(f.string(from:now.addingTimeInterval(3600)))\"><title>A &amp; B</title></programme></tv>"
        let guide=try GuideService.parse(Data(text.utf8))
        XCTAssertEqual(guide["one"]?.first?.title,"A & B")
        XCTAssertThrowsError(try GuideService.parse(Data("<!DOCTYPE tv [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><tv/>".utf8)))
    }
    func testSportsCoverageBeforeResolutionAndUnknownFPSPlayable() throws {
        let home=Team(id:"SEA",name:"Seattle Mariners",nickname:"Mariners",abbreviation:"SEA")
        let away=Team(id:"NYY",name:"New York Yankees",nickname:"Yankees",abbreviation:"NYY")
        let game=Game(id:"game",league:"MLB",name:"Yankees at Mariners",start:Date(),state:"in",status:"Live",home:home,away:away,networks:["ESPN"])
        let channels=[
            Channel(id:"network",name:"ESPN 4K",url:"https://example.test/1",height:2160,fps:60),
            Channel(id:"game",name:"Mariners vs Yankees FHD",url:"https://example.test/2",height:1080),
            Channel(id:"team",name:"Seattle Mariners HD",url:"https://example.test/3",height:720)]
        let matches=SportsMatcher.find(game,channels:channels,guide:[:])
        XCTAssertEqual(matches.first?.id,"game")
        XCTAssertEqual(matches.count,3)
        XCTAssertEqual(matches[0].channel.fps,0)
    }
    func testSportsPreservesFinalScoresAndHighResolutionLogos() throws {
        let data=Data(#"{"events":[{"id":"42","name":"Away at Home","date":"2026-10-28T22:00Z","status":{"type":{"state":"post","shortDetail":"Final"}},"competitions":[{"competitors":[{"homeAway":"home","score":"3","team":{"id":"1","displayName":"Seattle Mariners","name":"Mariners","abbreviation":"SEA","logo":"https://a.espncdn.com/i/teamlogos/mlb/500/scoreboard/sea.png"}},{"homeAway":"away","score":"2","team":{"id":"2","displayName":"New York Yankees","name":"Yankees","abbreviation":"NYY"}}]}]}]}"#.utf8)
        let games=try SportsService.parse(data,league:"MLB")
        XCTAssertEqual(games.count,1)
        XCTAssertEqual(games.first?.homeScore,"3")
        XCTAssertEqual(games.first?.completed,true)
        XCTAssertFalse(games.first!.home.logo.contains("scoreboard"))
    }
    func testEncryptedCacheDoesNotContainCredentials() async throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:root) }
        let vault=Vault(directory:root,testMode:true)
        let source=Playlist(name:"Test",address:"https://example.test",password:"private-test-value")
        try await vault.save(source,name:"source")
        let file=try FileManager.default.contentsOfDirectory(at:root,includingPropertiesForKeys:nil)[0]
        XCTAssertNil(try Data(contentsOf:file).range(of:Data("private-test-value".utf8)))
        let restored=try await vault.load(Playlist.self,name:"source")
        XCTAssertEqual(restored,source)
    }
}
