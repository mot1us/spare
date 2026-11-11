import XCTest
@testable import SpareMac

final class PlaybackWatchdogTests:XCTestCase {
    func testBriefRebufferingDoesNotUseOriginalTuneTime() {
        let start=Date(timeIntervalSince1970:1000)
        var watchdog=PlaybackWatchdog(now:start)
        XCTAssertFalse(watchdog.stalled(state:3,width:1920,time:0,now:start))
        XCTAssertFalse(watchdog.stalled(state:3,width:1920,time:60000,now:start.addingTimeInterval(60)))
        XCTAssertFalse(watchdog.stalled(state:2,width:1920,time:60000,now:start.addingTimeInterval(61)))
        XCTAssertFalse(watchdog.stalled(state:2,width:1920,time:60000,now:start.addingTimeInterval(64)))
        XCTAssertFalse(watchdog.stalled(state:3,width:1920,time:61000,now:start.addingTimeInterval(65)))
        XCTAssertFalse(watchdog.stalled(state:2,width:1920,time:61000,now:start.addingTimeInterval(66)))
        XCTAssertTrue(watchdog.stalled(state:2,width:1920,time:61000,now:start.addingTimeInterval(87)))
    }

    func testStartupAndFrozenPlayingStateRemainBounded() {
        let start=Date(timeIntervalSince1970:1000)
        var watchdog=PlaybackWatchdog(now:start)
        XCTAssertFalse(watchdog.stalled(state:1,width:0,time:-1,now:start.addingTimeInterval(19)))
        XCTAssertTrue(watchdog.stalled(state:1,width:0,time:-1,now:start.addingTimeInterval(21)))
        watchdog=PlaybackWatchdog(now:start)
        XCTAssertFalse(watchdog.stalled(state:3,width:1920,time:1000,now:start))
        XCTAssertTrue(watchdog.stalled(state:3,width:1920,time:1000,now:start.addingTimeInterval(26)))
    }
}
