import SwiftUI
import UIKit
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
