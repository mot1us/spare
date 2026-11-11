import XCTest
@testable import SpareCore

final class SportsServiceTests:XCTestCase {
    func testFailedRefreshRetainsOnlyRequestedDatesCachedGames() async throws {
        let now=Date(),fixture=ScoreFixture(try scoreboard(state:"post",date:Date()))
        let service=SportsService(fetch:{ _ in try await fixture.read() })
        let first=await service.load(date:now,leagues:["MLB"],force:true)
        XCTAssertEqual(first.games.count,1)
        XCTAssertTrue(first.failed.isEmpty)
        await fixture.fail()
        let stale=await service.load(date:now,leagues:["MLB"],force:true)
        XCTAssertEqual(stale.games,first.games)
        XCTAssertEqual(stale.failed,["MLB"])
        let otherDate=Calendar.current.date(byAdding:.day,value:3,to:now)!
        let missing=await service.load(date:otherDate,leagues:["MLB"],force:true)
        XCTAssertTrue(missing.games.isEmpty,"A failed date lookup must not reuse another date's games")
        XCTAssertEqual(missing.failed,["MLB"])
    }

    func testFailedLiveRefreshKeepsSavedStatusUntilSuccessfulRefresh() async throws {
        let now=Date(),fixture=ScoreFixture(try scoreboard(state:"in",date:Date()))
        let service=SportsService(fetch:{ _ in try await fixture.read() })
        let first=await service.load(date:now,leagues:["MLB"],force:true)
        XCTAssertEqual(first.games.first?.live,true)
        await fixture.fail()
        let stale=await service.load(date:now,leagues:["MLB"],force:true)
        XCTAssertEqual(stale.games.first?.state,"in")
        XCTAssertEqual(stale.games.first?.homeScore,"3")
        XCTAssertEqual(stale.games.first?.live,false)
        XCTAssertEqual(stale.games.first?.stateLabel,"SAVED")
        XCTAssertEqual(stale.failed,["MLB"])
        // A cache hit cannot turn a failed refresh back into a fresh Live badge.
        let reused=await service.load(date:now,leagues:["MLB"])
        XCTAssertEqual(reused.games.first?.stale,true)
        XCTAssertEqual(reused.games.first?.live,false)
        XCTAssertEqual(reused.failed,["MLB"])
        await fixture.recover()
        let fresh=await service.load(date:now,leagues:["MLB"],force:true)
        XCTAssertEqual(fresh.games.first?.live,true)
        XCTAssertEqual(fresh.games.first?.stale,false)
        XCTAssertTrue(fresh.failed.isEmpty)
    }

    private func scoreboard(state:String,date:Date) throws -> Data {
        try JSONSerialization.data(withJSONObject:["events":[[
            "id":"game","name":"Away at Home","date":ISO8601DateFormatter().string(from:date),
            "status":["type":["state":state,"shortDetail":state=="post" ? "Final":"Live"]],
            "competitions":[["competitors":[
                ["homeAway":"home","score":"3","team":["id":"one","displayName":"Home team"]],
                ["homeAway":"away","score":"2","team":["id":"two","displayName":"Away team"]]
            ]]]
        ]]])
    }
}

private actor ScoreFixture {
    let payload:Data
    private var failing=false
    init(_ payload:Data) { self.payload=payload }
    func fail() { failing=true }
    func recover() { failing=false }
    func read() throws -> Data {
        if failing { throw SpareError.status(503) }
        return payload
    }
}
