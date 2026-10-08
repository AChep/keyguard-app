import SwiftUI
import UIKit
import SafariServices
import XCTest
@testable import KeyguardUI

@MainActor
final class ScenePrivacyCoverTests: XCTestCase {
    // Keep simulated scene transitions out of UIKit's own lifecycle observers.
    private let notifications = NotificationCenter()
    private var scene: UIWindowScene!
    private var window: UIWindow!
    private var previousKeyWindow: UIWindow?
    private var probe: PrivacyScreen.SceneObserverView!
    private var extraWindows: [UIWindow] = []

    override func setUp() async throws {
        scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        previousKeyWindow = scene.keyWindow
        window = makeWindow()
        window.makeKeyAndVisible()
        probe = PrivacyScreen.SceneObserverView(privacyCover: ScenePrivacyCover(notifications: notifications))
        window.rootViewController?.view.addSubview(probe)
        post(UIScene.didActivateNotification)
    }

    override func tearDown() async throws {
        probe?.privacyCover.stop()
        for extra in extraWindows {
            extra.isHidden = true
            extra.rootViewController = nil
        }
        extraWindows.removeAll()
        window?.isHidden = true
        window?.rootViewController = nil
        window = nil
        probe = nil
        previousKeyWindow?.makeKey()
        previousKeyWindow = nil
        scene = nil
    }

    func testInactiveSnapshotCoversPresentedSheetSynchronouslyWithoutDismissingIt() async throws {
        let sheet = UIViewController()
        sheet.view.backgroundColor = .red
        sheet.modalPresentationStyle = .pageSheet
        await withCheckedContinuation { continuation in
            window.rootViewController?.present(sheet, animated: false) { continuation.resume() }
        }

        post(UIScene.willDeactivateNotification)

        try assertCovered(window)
        XCTAssertTrue(window.rootViewController?.presentedViewController === sheet)
        XCTAssertTrue(scene.keyWindow === window, "The cover must not steal keyboard focus")
        XCTAssertEqual(snapshotPixel(window), [255, 255, 255, 255], "The snapshot must contain only the opaque cover")

        post(UIScene.didActivateNotification)

        XCTAssertNil(privacyCover(in: window))
        XCTAssertTrue(window.rootViewController?.presentedViewController === sheet)
        XCTAssertFalse(window.accessibilityElementsHidden)
    }

    func testSafariPresentsAboveSheetAndStaysCoveredWhileInactive() async throws {
        let sheet = UIViewController()
        sheet.modalPresentationStyle = .pageSheet
        await withCheckedContinuation { continuation in
            window.rootViewController?.present(sheet, animated: false) { continuation.resume() }
        }
        let presenter = SafariBrowserPresenter()
        presenter.window = window
        let url = try XCTUnwrap(URL(string: "https://example.invalid"))

        let opened = await presenter.open(url, systemBrowser: nil)
        XCTAssertTrue(opened)
        let browser = try XCTUnwrap(sheet.presentedViewController as? SFSafariViewController)
        XCTAssertNil(browser.parent, "Safari must be presented modally, not embedded")

        let reopened = await presenter.open(url, systemBrowser: nil)
        XCTAssertTrue(reopened)
        XCTAssertNil(browser.presentedViewController, "Repeated requests must not stack Safari controllers")

        post(UIScene.willDeactivateNotification)
        try assertCovered(window)
        XCTAssertEqual(snapshotPixel(window), [255, 255, 255, 255])
        post(UIScene.didActivateNotification)
        XCTAssertTrue(sheet.presentedViewController === browser)

        await withCheckedContinuation { continuation in
            sheet.dismiss(animated: false) { continuation.resume() }
        }
        XCTAssertTrue(window.rootViewController?.presentedViewController === sheet)
    }

    func testSystemBrowserUsesAttachedPresenterWithoutCreatingSafariFallback() async throws {
        let presenter = SafariBrowserPresenter()
        presenter.window = window
        let url = try XCTUnwrap(URL(string: "https://example.invalid"))
        var openedURLs: [URL] = []

        let opened = await presenter.open(url) { openedURLs.append($0) }

        XCTAssertTrue(opened)
        XCTAssertEqual(openedURLs, [url])
        XCTAssertNil(window.rootViewController?.presentedViewController)
    }

    func testBrowserWithoutAttachedWindowDoesNotOpenElsewhere() async throws {
        let presenter = SafariBrowserPresenter()
        let url = try XCTUnwrap(URL(string: "https://example.invalid"))
        var didOpen = false

        let opened = await presenter.open(url) { _ in didOpen = true }

        XCTAssertFalse(opened)
        XCTAssertFalse(didOpen)
    }

    func testTransitionCompletionAfterFalseReturnDoesNotResumeTwice() async throws {
        var completion: (@MainActor () -> Void)?

        await SafariBrowserPresenter.waitForTransition {
            completion = $0
            return false
        }

        let finish = try XCTUnwrap(completion)
        finish()
    }

    func testTransitionCompletionBeforeFalseReturnDoesNotResumeTwice() async {
        await SafariBrowserPresenter.waitForTransition { completion in
            completion()
            return false
        }
    }

    func testScheduledTransitionWaitsForCompletion() async throws {
        let scheduled = expectation(description: "Transition completion is scheduled")
        var completion: (@MainActor () -> Void)?
        var finished = false
        let waiting = Task { @MainActor in
            await SafariBrowserPresenter.waitForTransition {
                completion = $0
                scheduled.fulfill()
                return true
            }
            finished = true
        }
        await fulfillment(of: [scheduled], timeout: 5)
        XCTAssertFalse(finished)

        let finish = try XCTUnwrap(completion)
        finish()
        await waiting.value
        XCTAssertTrue(finished)
    }

    func testSwiftUISystemBrowserPresentsFromRoot() async throws {
        guard #available(iOS 26.0, *) else { throw XCTSkip("The SwiftUI browser API requires iOS 26") }
        let ready = expectation(description: "System URL action is attached to the window")
        var openURL: OpenURLAction?
        let host = UIHostingController(
            rootView: SystemBrowserProbe { action in
                guard openURL == nil else { return }
                openURL = action
                ready.fulfill()
            }
        )
        window.rootViewController = host
        await fulfillment(of: [ready], timeout: 5)
        let action = try XCTUnwrap(openURL)
        let presenter = SafariBrowserPresenter()
        presenter.window = window
        let url = try XCTUnwrap(URL(string: "https://example.invalid"))
        var usedSystemAction = false

        let opened = await presenter.open(url) {
            usedSystemAction = true
            action($0, prefersInApp: true)
        }
        XCTAssertTrue(opened)
        XCTAssertTrue(usedSystemAction)
        let presented = expectation(
            for: NSPredicate { _, _ in
                MainActor.assumeIsolated { host.presentedViewController != nil }
            }, evaluatedWith: nil
        )
        await fulfillment(of: [presented], timeout: 5)
        XCTAssertTrue(host.presentedViewController is SFSafariViewController)
        let reopened = await presenter.open(url, systemBrowser: nil)
        XCTAssertTrue(reopened)
        await withCheckedContinuation { continuation in
            host.dismiss(animated: false) { continuation.resume() }
        }
    }

    func testBrowserPresentsAboveSheetWithoutUsingRootSwiftUIAction() async throws {
        guard #available(iOS 26.0, *) else { throw XCTSkip("The SwiftUI browser API requires iOS 26") }
        let ready = expectation(description: "System URL action is attached to the window")
        var openURL: OpenURLAction?
        let host = UIHostingController(
            rootView: SystemBrowserProbe { action in
                guard openURL == nil else { return }
                openURL = action
                ready.fulfill()
            }
        )
        window.rootViewController = host
        await fulfillment(of: [ready], timeout: 5)
        let action = try XCTUnwrap(openURL)
        let sheet = UIViewController()
        sheet.modalPresentationStyle = .pageSheet
        await withCheckedContinuation { continuation in
            host.present(sheet, animated: false) { continuation.resume() }
        }
        let presenter = SafariBrowserPresenter()
        presenter.window = window
        let url = try XCTUnwrap(URL(string: "https://example.invalid"))

        var usedRootAction = false
        let opened = await presenter.open(url) {
            usedRootAction = true
            action($0, prefersInApp: true)
        }
        XCTAssertTrue(opened)
        XCTAssertFalse(usedRootAction, "The root action cannot present over an existing sheet")
        let presented = expectation(
            for: NSPredicate { _, _ in
                MainActor.assumeIsolated { sheet.presentedViewController != nil }
            }, evaluatedWith: nil
        )
        await fulfillment(of: [presented], timeout: 5)
        XCTAssertTrue(host.presentedViewController === sheet)
        XCTAssertTrue(sheet.presentedViewController is SFSafariViewController)
        await withCheckedContinuation { continuation in
            sheet.dismiss(animated: false) { continuation.resume() }
        }
    }

    private struct SystemBrowserProbe: View {
        @Environment(\.openURL) private var openURL
        let onReady: (OpenURLAction) -> Void

        var body: some View {
            Color.clear.onAppear { onReady(openURL) }
        }
    }

    func testBackgroundReassertsCoverAndForegroundWaitsUntilActive() throws {
        post(UIScene.willDeactivateNotification)
        let latePresentation = UIView(frame: window.bounds)
        latePresentation.backgroundColor = .red
        window.addSubview(latePresentation)

        post(UIScene.didEnterBackgroundNotification)
        post(UIScene.willEnterForegroundNotification)

        try assertCovered(window)
        XCTAssertEqual(snapshotPixel(window), [255, 255, 255, 255])

        post(UIScene.didActivateNotification)

        XCTAssertNil(privacyCover(in: window))
        XCTAssertTrue(latePresentation.superview === window)
    }

    func testWindowsShownWhileInactiveAreCoveredAndResizeWithTheirWindow() throws {
        post(UIScene.willDeactivateNotification)
        let extra = makeWindow()
        extraWindows.append(extra)
        extra.accessibilityElementsHidden = true
        extra.isHidden = false
        notifications.post(name: UIWindow.didBecomeVisibleNotification, object: extra)
        try assertCovered(extra)

        extra.frame.size = CGSize(width: 240, height: 320)
        extra.layoutIfNeeded()
        try assertCovered(extra)

        post(UIScene.didActivateNotification)

        XCTAssertNil(privacyCover(in: extra))
        XCTAssertTrue(extra.accessibilityElementsHidden, "Restore the original accessibility setting")
    }

    func testDetachedRootRemainsProtectedUntilDismantled() throws {
        probe.removeFromSuperview()
        post(UIScene.willDeactivateNotification)
        try assertCovered(window)

        PrivacyScreen.dismantleUIView(probe, coordinator: ())

        XCTAssertNil(privacyCover(in: window))
        XCTAssertFalse(window.accessibilityElementsHidden)
        post(UIScene.didEnterBackgroundNotification)
        XCTAssertNil(privacyCover(in: window), "A dismantled root must stop observing the scene")
    }

    func testOtherScenesCannotUncoverThisSceneAndDisconnectCleansUp() throws {
        post(UIScene.willDeactivateNotification)
        notifications.post(name: UIScene.didActivateNotification, object: NSObject())
        try assertCovered(window)

        post(UIScene.didDisconnectNotification)

        XCTAssertNil(privacyCover(in: window))
        XCTAssertFalse(window.accessibilityElementsHidden)
        post(UIScene.willDeactivateNotification)
        XCTAssertNil(privacyCover(in: window))
    }

    func testDeallocationRestoresCoveredWindowsWithoutExplicitStop() throws {
        probe.privacyCover.stop()
        var cover: ScenePrivacyCover? = ScenePrivacyCover(notifications: notifications)
        cover?.attach(to: scene)
        post(UIScene.willDeactivateNotification)
        try assertCovered(window)

        cover = nil

        XCTAssertNil(privacyCover(in: window))
        XCTAssertFalse(window.accessibilityElementsHidden)
        post(UIScene.didEnterBackgroundNotification)
        XCTAssertNil(privacyCover(in: window))
    }

    private func makeWindow() -> UIWindow {
        let window = UIWindow(windowScene: scene)
        window.overrideUserInterfaceStyle = .light
        let controller = UIViewController()
        controller.view.backgroundColor = .blue
        window.rootViewController = controller
        return window
    }

    private func post(_ name: Notification.Name) {
        notifications.post(name: name, object: scene)
    }

    private func privacyCover(in window: UIWindow) -> UIView? {
        window.subviews.first { $0.accessibilityIdentifier == "keyguard.privacy-cover" }
    }

    private func assertCovered(_ window: UIWindow, file: StaticString = #filePath, line: UInt = #line) throws {
        let cover = try XCTUnwrap(privacyCover(in: window), file: file, line: line)
        XCTAssertTrue(window.subviews.last === cover, file: file, line: line)
        XCTAssertEqual(cover.frame, window.bounds, file: file, line: line)
        XCTAssertFalse(cover.isHidden, file: file, line: line)
        XCTAssertEqual(cover.alpha, 1, file: file, line: line)
        XCTAssertTrue(cover.isOpaque, file: file, line: line)
        XCTAssertTrue(window.accessibilityElementsHidden, file: file, line: line)
    }

    private func snapshotPixel(_ window: UIWindow) -> [UInt8] {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        format.preferredRange = .standard
        let image = UIGraphicsImageRenderer(size: CGSize(width: 1, height: 1), format: format).image { context in
            context.cgContext.translateBy(x: -window.bounds.midX, y: -window.bounds.midY)
            window.layer.render(in: context.cgContext)
        }
        guard let data = image.cgImage?.dataProvider?.data else { return [] }
        return Array((data as Data).prefix(4))
    }
}
