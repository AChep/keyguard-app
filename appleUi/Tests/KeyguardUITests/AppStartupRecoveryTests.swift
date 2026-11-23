#if os(macOS)
import XCTest
@testable import KeyguardUI

@MainActor
final class AppStartupRecoveryTests: XCTestCase {
    func testRecoverableFailureRoutesRetryThroughOwnership() {
        let startup = AppStartup()
        var requests = 0
        startup.onStartRequested = { requests += 1 }
        startup.fail(messageKey: "instance_service_error_text")
        startup.start()
        XCTAssertEqual(requests, 1)
        XCTAssertNil(startup.model)
    }
}
#endif
