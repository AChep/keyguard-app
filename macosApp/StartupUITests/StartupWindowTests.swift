import XCTest

@MainActor
final class StartupWindowTests: XCTestCase {
    private var app: XCUIApplication!
    private var events: URL!

    override func setUp() async throws {
        await MainActor.run {
            continueAfterFailure = false
            events = FileManager.default.temporaryDirectory.appendingPathComponent("keyguard-startup-\(UUID()).events")
            app = XCUIApplication()
            app.launchEnvironment["KEYGUARD_TEST_EVENTS"] = events.path
        }
    }

    override func tearDown() async throws {
        await MainActor.run {
            let attachment = XCTAttachment(string: recordedEvents().joined(separator: "\n"))
            attachment.name = "Startup events"
            attachment.lifetime = .keepAlways
            add(attachment)
            app.terminate()
            try? FileManager.default.removeItem(at: events)
        }
    }

    func testBackgroundDoesNotConstructMainRootAndReopenCreatesOneWindow() {
        app.launch()
        waitForEvent("core-started")
        // The launch decision follows the core start; give a window time to appear.
        XCTAssertFalse(app.windows.firstMatch.waitForExistence(timeout: 1))
        XCTAssertFalse(recordedEvents().contains("main-root-created"))
        command("Reveal main window")
        XCTAssertTrue(app.windows.firstMatch.waitForExistence(timeout: 5))
        XCTAssertTrue(recordedEvents().contains("main-root-created"))
        command("Reveal main window")
        // The window opens after a delay; give a second one time to appear.
        Thread.sleep(forTimeInterval: 1)
        XCTAssertEqual(app.windows.count, 1)
        XCTAssertEqual(recordedEvents().filter { $0 == "core-started" }.count, 1)
    }

    func testLoginDoesNotConstructWindowUntilPreferencesLoaded() {
        app.launchArguments = ["--launch-at-login"]
        app.launchEnvironment["KEYGUARD_TEST_SHOW_LOGIN"] = "1"
        app.launchEnvironment["KEYGUARD_TEST_DELAY_PREFERENCES"] = "1"
        app.launch()
        waitForEvent("core-started")
        XCTAssertFalse(app.windows.firstMatch.waitForExistence(timeout: 1))
        XCTAssertFalse(recordedEvents().contains("main-root-created"))
        command("Load preferences")
        XCTAssertTrue(app.windows.firstMatch.waitForExistence(timeout: 5))
    }

    func testCloseToTrayRetainsProcessAndReopenWorks() {
        app.launchEnvironment["KEYGUARD_TEST_USER_LAUNCH"] = "1"
        app.launchEnvironment["KEYGUARD_TEST_CLOSE_TO_TRAY"] = "1"
        app.launch()
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 5))
        // DockIconMode.isMainWindow relies on SwiftUI's naming of WindowGroup(id: "main") windows.
        XCTAssertTrue(window.identifier.hasPrefix("main-"), "Main window identifier: \(window.identifier)")
        window.buttons[XCUIIdentifierCloseWindow].click()
        XCTAssertTrue(window.waitForNonExistence(timeout: 5))
        XCTAssertFalse(app.wait(for: .notRunning, timeout: 2))
        command("Reveal main window")
        XCTAssertTrue(window.waitForExistence(timeout: 5))
        XCTAssertEqual(app.windows.count, 1)
    }

    func testMainWindowCloseWithoutTrayTerminates() {
        app.launchEnvironment["KEYGUARD_TEST_USER_LAUNCH"] = "1"
        app.launch()
        XCTAssertTrue(app.windows.firstMatch.waitForExistence(timeout: 5))
        app.windows.firstMatch.buttons[XCUIIdentifierCloseWindow].click()
        XCTAssertTrue(app.wait(for: .notRunning, timeout: 5))
    }

    private func command(_ title: String) {
        app.activate()
        app.menuBars.menuBarItems["Startup Test"].click()
        app.menuBars.menuItems[title].click()
    }

    private func recordedEvents() -> [String] {
        ((try? String(contentsOf: events, encoding: .utf8)) ?? "").split(separator: "\n").map(String.init)
    }

    private func waitForEvent(_ event: String) {
        let ready = XCTNSPredicateExpectation(
            predicate: NSPredicate { [weak self] _, _ in
                self?.recordedEvents().contains(event) == true
            }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 5), .completed)
    }
}
