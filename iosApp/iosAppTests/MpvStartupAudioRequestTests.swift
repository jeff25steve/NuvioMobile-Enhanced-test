import XCTest
@testable import Nuvio

final class MpvStartupAudioRequestTests: XCTestCase {
    func testExternalAudioRemainsPendingUntilFileLoadedAndIsConsumedOnce() {
        var request = MpvStartupAudioRequest()
        request.replace(with: "  https://media.example/audio.m4a  ")

        XCTAssertEqual(request.url, "https://media.example/audio.m4a")
        XCTAssertEqual(request.takeAfterFileLoaded(), "https://media.example/audio.m4a")
        XCTAssertNil(request.url)
        XCTAssertNil(request.takeAfterFileLoaded())
    }

    func testEmptyExternalAudioIsNotQueued() {
        var request = MpvStartupAudioRequest()
        request.replace(with: " \n\t ")

        XCTAssertNil(request.url)
        XCTAssertNil(request.takeAfterFileLoaded())
    }

    func testPendingExternalAudioCanBeClearedWhenPlayerIsDestroyed() {
        var request = MpvStartupAudioRequest()
        request.replace(with: "https://media.example/audio.m4a")
        request.clear()

        XCTAssertNil(request.url)
        XCTAssertNil(request.takeAfterFileLoaded())
    }
}
