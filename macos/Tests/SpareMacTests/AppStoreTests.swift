import XCTest
@testable import SpareCore
@testable import SpareMac

@MainActor final class AppStoreTests:XCTestCase {
    func testSwitchingPlaylistClearsOldChannelsAndStreamChoices() async throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let suite="Spare-tests-"+UUID().uuidString,preferences=UserDefaults(suiteName:suite)!
        defer { try? FileManager.default.removeItem(at:root);preferences.removePersistentDomain(forName:suite) }
        let vault=Vault(directory:root,testMode:true)
        let store=AppStore(vault:vault,preferences:preferences,startAutomatically:false)
        let old=Playlist(id:"old",kind:"m3u",address:"https://example.test/old")
        let next=Playlist(id:"next",kind:"m3u",address:"https://example.test/next")
        let oldChannel=Channel(id:"old-channel",name:"Old channel",url:"https://example.test/old-stream")
        let nextChannel=Channel(id:"next-channel",name:"Next channel",url:"https://example.test/next-stream")
        try await vault.save(Lineup(channels:[oldChannel]),name:"lineup",source:old)
        try await vault.save(Lineup(channels:[nextChannel]),name:"lineup",source:next)
        store.playlists=[old,next]
        store.activate(old)
        try await waitUntil { !store.loading && store.visibleChannels==[oldChannel] }
        store.streamResults=[StreamMatch(channel:oldChannel,confidence:100,reason:"Test match")]
        store.showStreams=true
        store.query="Old" // Leave an asynchronous filter pending while changing sources.
        store.activate(next)
        XCTAssertTrue(store.channels.isEmpty)
        XCTAssertTrue(store.visibleChannels.isEmpty)
        XCTAssertTrue(store.streamResults.isEmpty)
        XCTAssertFalse(store.showStreams)
        try await waitUntil { !store.loading && store.visibleChannels==[nextChannel] }
        XCTAssertEqual(store.channels,[nextChannel])
    }

    func testDeletingActivePlaylistCancelsPendingFilter() async throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let suite="Spare-tests-"+UUID().uuidString,preferences=UserDefaults(suiteName:suite)!
        defer { try? FileManager.default.removeItem(at:root);preferences.removePersistentDomain(forName:suite) }
        let vault=Vault(directory:root,testMode:true)
        let store=AppStore(vault:vault,preferences:preferences,startAutomatically:false)
        let source=Playlist(id:"old",kind:"m3u",address:"https://example.test/old")
        let channel=Channel(id:"old-channel",name:"Old channel",url:"https://example.test/old-stream")
        try await vault.save(Lineup(channels:[channel]),name:"lineup",source:source)
        store.playlists=[source]
        store.activate(source)
        try await waitUntil { !store.loading && store.visibleChannels==[channel] }
        store.query="Old"
        await store.deletePlaylist(source)
        // The old delayed search used to repopulate visibleChannels after deletion completed.
        try await Task.sleep(nanoseconds:180_000_000)
        XCTAssertTrue(store.channels.isEmpty)
        XCTAssertTrue(store.visibleChannels.isEmpty)
        XCTAssertNil(store.source)
    }

    func testFocusedSportsHeroUsesLatestScoresAndCurrentFilters() async {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let suite="Spare-tests-"+UUID().uuidString,preferences=UserDefaults(suiteName:suite)!
        defer { try? FileManager.default.removeItem(at:root);preferences.removePersistentDomain(forName:suite) }
        let store=AppStore(vault:Vault(directory:root,testMode:true),preferences:preferences,startAutomatically:false)
        let home=Team(id:"home",name:"Home team",nickname:"Home",abbreviation:"H")
        let away=Team(id:"away",name:"Away team",nickname:"Away",abbreviation:"A")
        var game=Game(id:"selected",league:"MLB",name:"Away at Home",start:Date(),state:"in",status:"Live",home:home,away:away,homeScore:"1",awayScore:"0")
        store.games=[game]
        XCTAssertEqual(store.featuredGame(focusedID:game.id)?.homeScore,"1")
        game.staleSince=Date();store.games=[game];store.sportsLiveOnly=true
        XCTAssertTrue(store.displayedGames.isEmpty)
        store.sportsLiveOnly=false
        XCTAssertEqual(store.featuredGame(focusedID:game.id)?.stateLabel,"SAVED")
        game.staleSince=nil
        game.homeScore="3";game.state="post";game.status="Final"
        store.games=[game]
        XCTAssertEqual(store.featuredGame(focusedID:game.id),game)
        store.sportsLiveOnly=true
        XCTAssertNil(store.featuredGame(focusedID:game.id))
        store.sportsLiveOnly=false;store.sportsLeague="NBA"
        XCTAssertNil(store.featuredGame(focusedID:game.id))
    }

    func testRenamingPlaylistPreservesCurrentPlaybackAndCachedChannels() async throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let suite="Spare-tests-"+UUID().uuidString,preferences=UserDefaults(suiteName:suite)!
        defer { try? FileManager.default.removeItem(at:root);preferences.removePersistentDomain(forName:suite) }
        let vault=Vault(directory:root,testMode:true)
        let store=AppStore(vault:vault,preferences:preferences,startAutomatically:false)
        let source=Playlist(id:"source",name:"Original",kind:"m3u",address:"https://example.test/playlist")
        let channel=Channel(id:"one",name:"Fixture channel",url:"https://example.test/stream")
        try await vault.save(Lineup(channels:[channel]),name:"lineup",source:source)
        store.playlists=[source];store.activate(source)
        try await waitUntil { !store.loading && store.visibleChannels==[channel] }
        store.current=channel
        var renamed=source;renamed.name="Renamed"
        store.playlists=[renamed];store.activate(renamed)
        XCTAssertEqual(store.current,channel)
        XCTAssertEqual(store.channels,[channel])
        try await waitUntil { !store.loading && store.visibleChannels==[channel] }
        XCTAssertNil(store.notice)
    }

    private func waitUntil(_ ready:()->Bool) async throws {
        for _ in 0..<200 {
            if ready() { return }
            try await Task.sleep(nanoseconds:10_000_000)
        }
        XCTFail("Timed out waiting for isolated cached playlist")
    }
}
