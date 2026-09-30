import XCTest
@testable import KeyguardUI

@MainActor
final class LinkOpeningCoordinatorTests: XCTestCase {
    func testWebLinksTryUniversalLinkBeforePresentingBrowser() async {
        let fixture = Fixture()
        fixture.coordinator.updatePreference(useExternalBrowser: false)

        fixture.coordinator.open("https://example.com/path?q=hello#section")
        await fixture.coordinator.openingTask?.value

        XCTAssertEqual(fixture.systemCalls.map(\.universalOnly), [true])
        XCTAssertEqual(fixture.browserURLs.map(\.absoluteString), ["https://example.com/path?q=hello#section"])
        XCTAssertEqual(fixture.failures, 0)
    }

    func testInstalledAppHandoffDoesNotAlsoOpenBrowser() async {
        let fixture = Fixture()
        fixture.systemResult = true
        fixture.coordinator.updatePreference(useExternalBrowser: false)

        fixture.coordinator.open("https://example.com")
        await fixture.coordinator.openingTask?.value

        XCTAssertEqual(fixture.systemCalls.count, 1)
        XCTAssertTrue(fixture.browserURLs.isEmpty)
    }

    func testWebLinksPreserveUnicodeAndEncodeSpaces() async {
        let fixture = Fixture()
        fixture.coordinator.updatePreference(useExternalBrowser: false)

        fixture.coordinator.open("https://example.com/Café/a b?q=hello%20world")
        await fixture.coordinator.openingTask?.value

        XCTAssertEqual(
            fixture.browserURLs.map(\.absoluteString),
            ["https://example.com/Caf%C3%A9/a%20b?q=hello%20world"]
        )
        XCTAssertEqual(fixture.failures, 0)
    }

    func testExternalPreferenceAndLiveChanges() async {
        let fixture = Fixture()
        fixture.systemResult = true
        fixture.coordinator.updatePreference(useExternalBrowser: true)
        fixture.coordinator.open("http://example.com")
        await fixture.coordinator.openingTask?.value
        XCTAssertEqual(fixture.systemCalls.map(\.universalOnly), [false])
        XCTAssertTrue(fixture.browserURLs.isEmpty)

        fixture.systemResult = false
        fixture.coordinator.updatePreference(useExternalBrowser: false)
        fixture.coordinator.open("https://example.com")
        await fixture.coordinator.openingTask?.value
        XCTAssertEqual(fixture.browserURLs.count, 1)
    }

    func testMapsAndExplicitExternalAuthenticationBypassBrowserPreference() async {
        let fixture = Fixture()
        fixture.systemResult = true
        fixture.coordinator.updatePreference(useExternalBrowser: false)

        for raw in ["https://maps.apple.com/?q=Kyiv", "https://vault.example.com"] {
            fixture.coordinator.open(raw, forceSystem: true)
            await fixture.coordinator.openingTask?.value
        }

        XCTAssertEqual(fixture.systemCalls.map(\.universalOnly), [false, false])
        XCTAssertTrue(fixture.browserURLs.isEmpty)
    }

    func testNonWebSchemesUseSystemWithoutWaitingForPreference() async {
        let fixture = Fixture()
        fixture.systemResult = true
        for raw in ["mailto:test@example.com", "tel:+123", "sms:+123", "app-settings:", "custom-app://open"] {
            fixture.coordinator.open(raw)
            await fixture.coordinator.openingTask?.value
        }
        XCTAssertEqual(fixture.systemCalls.count, 5)
        XCTAssertTrue(fixture.systemCalls.allSatisfy { !$0.universalOnly })
        XCTAssertTrue(fixture.browserURLs.isEmpty)
    }

    func testFirstTapWaitsForPersistedPreference() async {
        let fixture = Fixture()
        fixture.systemResult = true
        fixture.coordinator.open("https://example.com")
        XCTAssertNil(fixture.coordinator.openingTask)
        XCTAssertTrue(fixture.systemCalls.isEmpty)

        fixture.coordinator.updatePreference(useExternalBrowser: true)
        await fixture.coordinator.openingTask?.value
        XCTAssertEqual(fixture.systemCalls.map(\.universalOnly), [false])
    }

    func testBackgroundDiscardsTapWaitingForPreferences() async {
        let fixture = Fixture()
        fixture.coordinator.open("https://example.com")
        fixture.coordinator.cancelPendingRequests()
        fixture.coordinator.updatePreference(useExternalBrowser: false)
        await fixture.coordinator.openingTask?.value
        XCTAssertTrue(fixture.systemCalls.isEmpty)
        XCTAssertTrue(fixture.browserURLs.isEmpty)
    }

    func testBackgroundDuringUniversalLinkAttemptDoesNotPresentBrowser() async {
        let fixture = Fixture()
        fixture.onSystemCall = { fixture.coordinator.cancelPendingRequests() }
        fixture.coordinator.updatePreference(useExternalBrowser: false)
        fixture.coordinator.open("https://example.com")
        await fixture.coordinator.openingTask?.value
        XCTAssertTrue(fixture.browserURLs.isEmpty)
        XCTAssertEqual(fixture.failures, 0)
    }

    func testBackgroundBeforeOpeningStartsDoesNotLaunchAnotherApp() async {
        let fixture = Fixture()
        fixture.coordinator.updatePreference(useExternalBrowser: true)
        fixture.coordinator.open("https://example.com")
        fixture.coordinator.cancelPendingRequests()
        await fixture.coordinator.openingTask?.value
        XCTAssertTrue(fixture.systemCalls.isEmpty)
        XCTAssertEqual(fixture.failures, 0)
    }

    func testSystemActionSupersedesLinkWaitingForPreferences() async {
        let fixture = Fixture()
        fixture.systemResult = true
        fixture.coordinator.open("https://example.com")
        fixture.coordinator.open("tel:+123")
        await fixture.coordinator.openingTask?.value
        fixture.coordinator.updatePreference(useExternalBrowser: false)
        await fixture.coordinator.openingTask?.value
        XCTAssertEqual(fixture.systemCalls.map { $0.url.absoluteString }, ["tel:+123"])
    }

    func testRepeatedTapsDoNotStackPresentations() async {
        let fixture = Fixture()
        fixture.coordinator.updatePreference(useExternalBrowser: false)
        fixture.coordinator.open("https://example.com")
        fixture.coordinator.open("https://example.org")
        await fixture.coordinator.openingTask?.value
        XCTAssertEqual(fixture.systemCalls.count, 1)
        XCTAssertEqual(fixture.browserURLs.count, 1)
    }

    func testMalformedAndNonNavigableURLsShowFeedbackWithoutOpeningAnything() {
        let fixture = Fixture()
        for raw in [
            "", "example.com", "https:///path", "https://bad host", "javascript:alert(1)", "data:text/plain,secret",
        ] {
            fixture.coordinator.open(raw)
        }
        XCTAssertEqual(fixture.failures, 6)
        XCTAssertTrue(fixture.systemCalls.isEmpty)
        XCTAssertTrue(fixture.browserURLs.isEmpty)
    }

    func testOpeningFailuresProduceFeedbackWithoutExternalBrowserRetry() async {
        let fixture = Fixture()
        fixture.browserResult = false
        fixture.coordinator.updatePreference(useExternalBrowser: false)
        fixture.coordinator.open("https://example.com")
        await fixture.coordinator.openingTask?.value
        XCTAssertEqual(fixture.failures, 1)
        XCTAssertEqual(fixture.systemCalls.map(\.universalOnly), [true])

        fixture.coordinator.open("custom-app://missing")
        await fixture.coordinator.openingTask?.value
        XCTAssertEqual(fixture.failures, 2)
    }

    func testMacOSAlwaysUsesSystemOpening() async {
        let fixture = Fixture(supportsInAppBrowser: false)
        fixture.systemResult = true
        fixture.coordinator.open("https://example.com")
        await fixture.coordinator.openingTask?.value
        XCTAssertEqual(fixture.systemCalls.map(\.universalOnly), [false])
        XCTAssertTrue(fixture.browserURLs.isEmpty)
    }

    @MainActor
    private final class Fixture {
        let supportsInAppBrowser: Bool
        var systemCalls: [(url: URL, universalOnly: Bool)] = []
        var browserURLs: [URL] = []
        var failures = 0
        var systemResult = false
        var browserResult = true
        var onSystemCall: (() -> Void)?

        init(supportsInAppBrowser: Bool = true) {
            self.supportsInAppBrowser = supportsInAppBrowser
        }

        lazy var coordinator: LinkOpeningCoordinator = {
            let value = LinkOpeningCoordinator(
                supportsInAppBrowser: supportsInAppBrowser,
                openSystem: { [unowned self] url, universalOnly in
                    systemCalls.append((url, universalOnly))
                    onSystemCall?()
                    return systemResult
                },
                showFailure: { [unowned self] in failures += 1 }
            )
            value.setBrowserHandler { [unowned self] url in
                browserURLs.append(url)
                return browserResult
            }
            return value
        }()
    }
}
