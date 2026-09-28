import XCTest
@testable import KeyguardUI

final class AppInformationTests: XCTestCase {
    @MainActor
    func testFormatsAppleMarketingVersionAndBundleBuild() {
        XCTAssertEqual(AppInformationModel.formatAppVersion(version: "3.2.1", build: "2"), "3.2.1 (2)")
    }

    @MainActor
    func testMissingBundleFieldsDoNotProduceEmptyParentheses() {
        XCTAssertEqual(AppInformationModel.formatAppVersion(version: "3.2.1", build: nil), "3.2.1")
        XCTAssertEqual(AppInformationModel.formatAppVersion(version: "3.2.1", build: ""), "3.2.1")
        XCTAssertEqual(AppInformationModel.formatAppVersion(version: nil, build: nil), L10n.unknown)
    }
}
