import XCTest
@testable import Nuvio

final class MpvStartPositionTests: XCTestCase {
    func testFormatsRetryPositionWithStableDecimalSeparator() {
        XCTAssertEqual(mpvRetryStartPositionSeconds(12.3456), "12.346")
        XCTAssertEqual(mpvRetryStartPositionSeconds(0.001), "0.001")
    }

    func testOmitsInvalidOrBeginningPositions() {
        XCTAssertNil(mpvRetryStartPositionSeconds(0))
        XCTAssertNil(mpvRetryStartPositionSeconds(-1))
        XCTAssertNil(mpvRetryStartPositionSeconds(.nan))
        XCTAssertNil(mpvRetryStartPositionSeconds(.infinity))
    }
}
