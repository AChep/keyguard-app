import XCTest
@testable import KeyguardUI

final class AppDialogRoutingTests: XCTestCase {
    func testDialogPriorityIsStableAcrossAllPendingRoutes() {
        var available = Set(RootAppSheetRoute.allCases)
        for expected in RootAppSheetRoute.allCases {
            XCTAssertEqual(
                RootAppSheetRoute.preferred(
                    available: available, isAddFormActive: false, hasLocalAuthenticationHost: false),
                expected
            )
            available.remove(expected)
        }
        XCTAssertNil(
            RootAppSheetRoute.preferred(available: [], isAddFormActive: false, hasLocalAuthenticationHost: false))
    }

    func testActiveFormKeepsOwnershipOfItsConfirmationAndAccountPicker() {
        let available: Set<RootAppSheetRoute> = [.confirmation, .accountPicker, .infoDialog]
        XCTAssertEqual(
            RootAppSheetRoute.preferred(available: available, isAddFormActive: true, hasLocalAuthenticationHost: false),
            .infoDialog)
        XCTAssertEqual(
            RootAppSheetRoute.preferred(
                available: available, isAddFormActive: false, hasLocalAuthenticationHost: false), .confirmation)
    }

    func testLocalAuthenticationHostSuppressesOnlyRootAuthentication() {
        XCTAssertNil(
            RootAppSheetRoute.preferred(
                available: [.elevatedAccess], isAddFormActive: false, hasLocalAuthenticationHost: true))
        XCTAssertEqual(
            RootAppSheetRoute.preferred(
                available: [.elevatedAccess, .websiteLeak], isAddFormActive: false, hasLocalAuthenticationHost: true),
            .websiteLeak)
        XCTAssertEqual(
            RootAppSheetRoute.preferred(
                available: [.elevatedAccess], isAddFormActive: false, hasLocalAuthenticationHost: false),
            .elevatedAccess)
    }
}
