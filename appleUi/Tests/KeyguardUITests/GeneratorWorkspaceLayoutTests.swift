import XCTest
@testable import KeyguardUI

final class GeneratorWorkspaceLayoutTests: XCTestCase {
    func testOnlySufficientlyWideRegularIPadsUseColumns() {
        XCTAssertFalse(GeneratorWorkspaceLayout(width: 839, isPad: true, isRegular: true).isWide)
        let wide = GeneratorWorkspaceLayout(width: 840, isPad: true, isRegular: true)
        XCTAssertEqual(wide.settingsWidth, 360)
        XCTAssertFalse(GeneratorWorkspaceLayout(width: 1_024, isPad: false, isRegular: true).isWide)
        XCTAssertFalse(GeneratorWorkspaceLayout(width: 1_024, isPad: true, isRegular: false).isWide)
    }

    func testTextSizeReservesEnoughRoomAndAccessibilityUsesOneColumn() {
        XCTAssertFalse(GeneratorWorkspaceLayout(width: 1_000, isPad: true, isRegular: true, textScale: 1.25).isWide)
        XCTAssertTrue(GeneratorWorkspaceLayout(width: 1_050, isPad: true, isRegular: true, textScale: 1.25).isWide)
        XCTAssertFalse(
            GeneratorWorkspaceLayout(width: 1_366, isPad: true, isRegular: true, isAccessibilitySize: true).isWide)
    }

    func testLargeWindowsCapTheWorkspaceAndSettingsWidth() {
        XCTAssertEqual(GeneratorWorkspaceLayout(width: 1_200, isPad: true, isRegular: true).settingsWidth, 440)
        XCTAssertEqual(GeneratorWorkspaceLayout(width: 1_920, isPad: true, isRegular: true).settingsWidth, 440)
    }
}
