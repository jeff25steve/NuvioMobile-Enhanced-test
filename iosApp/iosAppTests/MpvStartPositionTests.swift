import XCTest
@testable import Nuvio

final class MpvStartPositionTests: XCTestCase {
    func testFormatsRetryPositionWithStableDecimalSeparator() {
        XCTAssertEqual(mpvStartPositionSeconds(12.3456), "12.346")
        XCTAssertEqual(mpvStartPositionSeconds(0.001), "0.001")
    }

    func testOmitsInvalidOrBeginningPositions() {
        XCTAssertNil(mpvStartPositionSeconds(0))
        XCTAssertNil(mpvStartPositionSeconds(-1))
        XCTAssertNil(mpvStartPositionSeconds(.nan))
        XCTAssertNil(mpvStartPositionSeconds(.infinity))
    }
}
