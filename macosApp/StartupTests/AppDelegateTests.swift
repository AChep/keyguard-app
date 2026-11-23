import AppKit
import KeyguardShared
import KeyguardUI
import XCTest
import SwiftUI
@testable import KeyguardStartupHost

@MainActor
final class AppDelegateTests: XCTestCase {
    func testEarlyReopenWaitsForOwnershipAndOnlyStartsOneCore() async {
        let instance = StartupTestInstance()
        var acquisition: CheckedContinuation<(any AppProcessInstance)?, any Error>?
        var acquisitions = 0
        var starts = 0
        var opens = 0
        let startup = AppStartup()
        var services = isolatedServices()
        services.arguments = ["--background"]
        services.acquireInstance = { _ in
            acquisitions += 1
            return try await withCheckedThrowingContinuation { acquisition = $0 }
        }
        services.startCore = { _ in starts += 1 }
        let delegate = AppDelegate(startup: startup, services: services)
        delegate.installMainWindowOpener { opens += 1 }
        delegate.applicationDidFinishLaunching(notification())
        _ = delegate.applicationShouldHandleReopen(NSApp, hasVisibleWindows: false)
        _ = delegate.applicationShouldHandleReopen(NSApp, hasVisibleWindows: false)
        startup.start()
        await waitUntil { acquisitions == 1 }
        XCTAssertEqual(acquisitions, 1)
        XCTAssertEqual(starts, 0)
        XCTAssertEqual(opens, 0)
        acquisition?.resume(returning: instance)
        await waitUntil { opens == 1 }
        XCTAssertEqual(starts, 1)
        XCTAssertEqual(opens, 1)
        startup.start()
        XCTAssertEqual(starts, 1)
        delegate.applicationWillTerminate(notification())
    }

    func testOtherFlavorRequestWaitsForOwnershipCoalescesAndThenReopens() async {
        let group = "keyguard.startup.test.\(UUID())"
        var acquisition: CheckedContinuation<(any AppProcessInstance)?, any Error>?
        var opens = 0
        var delivered = 0
        var services = isolatedServices()
        services.arguments = ["--background"]
        services.appGroup = group
        services.acquireInstance = { _ in try await withCheckedThrowingContinuation { acquisition = $0 } }
        let delegate = AppDelegate(startup: AppStartup(), services: services)
        delegate.installMainWindowOpener { opens += 1 }
        delegate.applicationDidFinishLaunching(notification())
        await waitUntil { acquisition != nil }
        let probe = NativeWindowActivation(appGroup: group) { delivered += 1 }
        let sender = NativeWindowActivation(appGroup: group) {}
        sender.activateExisting(yieldingTo: nil)
        sender.activateExisting(yieldingTo: nil)
        await waitUntil { delivered == 2 }
        await settle()
        XCTAssertEqual(opens, 0)
        acquisition?.resume(returning: StartupTestInstance())
        await waitUntil { opens == 1 }
        await settle()
        XCTAssertEqual(opens, 1)
        sender.activateExisting(yieldingTo: nil)
        await waitUntil { opens == 2 }
        delegate.applicationWillTerminate(notification())
        withExtendedLifetime((probe, sender)) {}
    }

    func testFailedProcessWithoutOwnershipNeverOpensForAnotherProcessActivation() async {
        let group = "keyguard.startup.test.\(UUID())"
        var acquisition: CheckedContinuation<(any AppProcessInstance)?, any Error>?
        var opens = 0
        var delivered = 0
        let startup = AppStartup()
        var services = isolatedServices()
        services.arguments = ["--background"]
        services.appGroup = group
        services.acquireInstance = { _ in try await withCheckedThrowingContinuation { acquisition = $0 } }
        let delegate = AppDelegate(startup: startup, services: services)
        delegate.installMainWindowOpener { opens += 1 }
        delegate.applicationDidFinishLaunching(notification())
        await waitUntil { acquisition != nil }
        let probe = NativeWindowActivation(appGroup: group) { delivered += 1 }
        let sender = NativeWindowActivation(appGroup: group) {}
        // The owner's activation arrives before this process fails arbitration.
        sender.activateExisting(yieldingTo: nil)
        await waitUntil { delivered == 1 }
        acquisition?.resume(throwing: CocoaError(.fileReadUnknown))
        await waitUntil { startup.hasError }
        sender.activateExisting(yieldingTo: nil)
        await waitUntil { delivered == 2 }
        await settle()
        XCTAssertEqual(opens, 0)
        // A Dock reopen is addressed to this process, so it shows the startup error.
        _ = delegate.applicationShouldHandleReopen(NSApp, hasVisibleWindows: false)
        XCTAssertEqual(opens, 1)
        delegate.applicationWillTerminate(notification())
        withExtendedLifetime((probe, sender)) {}
    }

    func testUserLaunchedSecondaryActivatesOtherFlavorAndBackgroundStaysQuiet() async {
        for (arguments, requests) in [([String](), 1), (["--background"], 0)] {
            let group = "keyguard.startup.test.\(UUID())"
            var received = 0
            var exits = 0
            var starts = 0
            var events: [String] = []
            let owner = NativeWindowActivation(appGroup: group) { received += 1 }
            var services = isolatedServices()
            services.arguments = arguments
            services.appGroup = group
            services.siblingBundleIdentifier = "com.artemchep.keyguard.startuptest.sibling"
            services.acquireInstance = { _ in nil }
            services.startCore = { _ in starts += 1 }
            services.awaitSiblingActivation = { events.append("await \($0)") }
            services.terminate = {
                exits += 1
                events.append("exit")
            }
            let delegate = AppDelegate(startup: AppStartup(), services: services)
            delegate.applicationDidFinishLaunching(notification())
            await waitUntil { exits == 1 }
            if requests > 0 { await waitUntil { received == requests } }
            await settle()
            XCTAssertEqual(received, requests)
            XCTAssertEqual(starts, 0)
            // Only a user launch waits for the owner to take focus before exiting.
            let waits = requests > 0 ? ["await com.artemchep.keyguard.startuptest.sibling"] : []
            XCTAssertEqual(events, waits + ["exit"])
            delegate.applicationWillTerminate(notification())
            withExtendedLifetime(owner) {}
        }
    }

    func testLoginWaitsForLoadedPreferencesAndBackgroundDoesNotOpen() async {
        await assertLoginLaunch(arguments: ["--launch-at-login"], loginActivation: false)
    }

    /// `SMAppService` launches the app at login with no arguments, only the launch AppleEvent.
    func testLoginAppleEventWithoutArgumentsWaitsForPreferencesAndBackgroundDoesNotOpen() async {
        await assertLoginLaunch(arguments: [], loginActivation: true)
    }

    private func assertLoginLaunch(
        arguments: [String], loginActivation: Bool, file: StaticString = #filePath, line: UInt = #line
    ) async {
        for background in [true, false] {
            var preferences = StartupPreferences(loaded: false, startInBackground: background)
            var opens = 0
            var started = false
            var preferencesChanged: (@MainActor () -> Void)?
            var services = isolatedServices()
            services.arguments = arguments
            services.loginActivation = loginActivation
            services.preferences = { preferences }
            services.startCore = { _ in started = true }
            services.observePreferences = { preferencesChanged = $0 }
            let delegate = AppDelegate(startup: AppStartup(), services: services)
            delegate.installMainWindowOpener { opens += 1 }
            delegate.applicationDidFinishLaunching(notification())
            await waitUntil({ started }, file: file, line: line)
            XCTAssertEqual(opens, 0, "Opened before preferences loaded", file: file, line: line)
            preferences.loaded = true
            preferencesChanged?()
            XCTAssertEqual(opens, background ? 0 : 1, "Start in background: \(background)", file: file, line: line)
            delegate.applicationWillTerminate(notification())
        }
    }

    func testSilentSecondaryTerminatesWithoutCreatingCore() async {
        var starts = 0
        var exits = 0
        var services = isolatedServices()
        services.arguments = ["--background"]
        services.acquireInstance = { _ in nil }
        services.startCore = { _ in starts += 1 }
        services.terminate = { exits += 1 }
        let delegate = AppDelegate(startup: AppStartup(), services: services)
        delegate.applicationDidFinishLaunching(notification())
        await waitUntil { exits == 1 }
        XCTAssertEqual(starts, 0)
        XCTAssertEqual(exits, 1)
        delegate.applicationWillTerminate(notification())
    }

    func testClosingBackgroundPanelsDoesNotQuitAndMainCloseHonorsPreferences() async {
        var preferences = StartupPreferences(loaded: true)
        var menuBarOnly = false
        var started = false
        var services = isolatedServices()
        services.arguments = ["--background"]
        services.preferences = { preferences }
        services.menuBarOnly = { menuBarOnly }
        services.startCore = { _ in started = true }
        let delegate = AppDelegate(startup: AppStartup(), services: services)
        delegate.applicationDidFinishLaunching(notification())
        await waitUntil { started }
        XCTAssertFalse(delegate.applicationShouldTerminateAfterLastWindowClosed(NSApp))
        delegate.mainWindowDidOpen()
        XCTAssertTrue(delegate.applicationShouldTerminateAfterLastWindowClosed(NSApp))
        preferences.closeToTray = true
        XCTAssertFalse(delegate.applicationShouldTerminateAfterLastWindowClosed(NSApp))
        preferences.closeToTray = false
        menuBarOnly = true
        XCTAssertFalse(delegate.applicationShouldTerminateAfterLastWindowClosed(NSApp))
        delegate.applicationWillTerminate(notification())
    }

    func testTransientMenuCloseCannotQuitWhileFirstMainWindowIsStillOpening() async {
        var started = false
        var pendingOpen: (() -> Void)?
        var services = isolatedServices()
        services.arguments = ["--background"]
        services.startCore = { _ in started = true }
        services.showMainWindow = { pendingOpen = $0 }
        let delegate = AppDelegate(startup: AppStartup(), services: services)
        delegate.installMainWindowOpener {}
        delegate.applicationDidFinishLaunching(notification())
        await waitUntil { started }
        _ = delegate.applicationShouldHandleReopen(NSApp, hasVisibleWindows: false)
        XCTAssertNotNil(pendingOpen)
        XCTAssertFalse(delegate.applicationShouldTerminateAfterLastWindowClosed(NSApp))
        delegate.mainWindowDidOpen()
        XCTAssertTrue(delegate.applicationShouldTerminateAfterLastWindowClosed(NSApp))
        delegate.applicationWillTerminate(notification())
    }

    func testRecoverableAcquisitionFailureRetriesBeforeStartingCore() async {
        let startup = AppStartup()
        var attempts = 0
        var starts = 0
        var services = isolatedServices()
        services.acquireInstance = { _ in
            attempts += 1
            if attempts == 1 { throw CocoaError(.fileReadUnknown) }
            return StartupTestInstance()
        }
        services.startCore = { _ in starts += 1 }
        let delegate = AppDelegate(startup: startup, services: services)
        delegate.applicationDidFinishLaunching(notification())
        await waitUntil { startup.hasError }
        XCTAssertEqual(starts, 0)
        startup.start()
        await waitUntil { starts == 1 }
        XCTAssertEqual(attempts, 2)
        delegate.applicationWillTerminate(notification())
    }

    func testPendingActivationOpensOnceAfterFailedAcquisitionIsRetried() async {
        let group = "keyguard.startup.test.\(UUID())"
        var acquisition: CheckedContinuation<(any AppProcessInstance)?, any Error>?
        var attempts = 0
        var starts = 0
        var opens = 0
        var delivered = 0
        let startup = AppStartup()
        var services = isolatedServices()
        services.arguments = ["--background"]
        services.appGroup = group
        services.acquireInstance = { _ in
            attempts += 1
            if attempts == 1 { return try await withCheckedThrowingContinuation { acquisition = $0 } }
            return StartupTestInstance()
        }
        services.startCore = { _ in starts += 1 }
        let delegate = AppDelegate(startup: startup, services: services)
        delegate.installMainWindowOpener { opens += 1 }
        delegate.applicationDidFinishLaunching(notification())
        await waitUntil { acquisition != nil }
        let probe = NativeWindowActivation(appGroup: group) { delivered += 1 }
        let sender = NativeWindowActivation(appGroup: group) {}
        sender.activateExisting(yieldingTo: nil)
        await waitUntil { delivered == 1 }
        acquisition?.resume(throwing: CocoaError(.fileReadUnknown))
        await waitUntil { startup.hasError }
        await settle()
        XCTAssertEqual(opens, 0)
        startup.start()
        await waitUntil { opens == 1 }
        await settle()
        XCTAssertEqual(attempts, 2)
        XCTAssertEqual(starts, 1)
        XCTAssertEqual(opens, 1)
        delegate.applicationWillTerminate(notification())
        withExtendedLifetime((probe, sender)) {}
    }

    func testOwnerOpensAgainForActivationAfterLaunchWasDecided() async {
        let group = "keyguard.startup.test.\(UUID())"
        var opens = 0
        var delivered = 0
        var services = isolatedServices()
        services.arguments = []
        services.appGroup = group
        let delegate = AppDelegate(startup: AppStartup(), services: services)
        delegate.installMainWindowOpener { opens += 1 }
        delegate.applicationDidFinishLaunching(notification())
        await waitUntil { opens == 1 }
        await settle()
        XCTAssertEqual(opens, 1)
        let probe = NativeWindowActivation(appGroup: group) { delivered += 1 }
        let sender = NativeWindowActivation(appGroup: group) {}
        sender.activateExisting(yieldingTo: nil)
        await waitUntil { delivered == 1 }
        await waitUntil { opens == 2 }
        await settle()
        XCTAssertEqual(opens, 2)
        delegate.applicationWillTerminate(notification())
        withExtendedLifetime((probe, sender)) {}
    }

    func testUserLaunchKeepsVaultActiveUntilFirstWindowAndBackgroundDoesNot() async {
        for (arguments, showsWindow) in [([String](), true), (["--background"], false)] {
            var phases: [ScenePhase] = []
            var startedShowingWindow: Bool?
            var services = isolatedServices()
            services.arguments = arguments
            services.startCore = { startedShowingWindow = $0 }
            services.showMainWindow = { _ in }
            services.updateLifecycle = { phases.append($0) }
            let delegate = AppDelegate(startup: AppStartup(), services: services)
            delegate.installMainWindowOpener {}
            delegate.applicationDidFinishLaunching(notification())
            await waitUntil { startedShowingWindow != nil }
            // A user launch seeds the core active, which keeps the lock timer off.
            XCTAssertEqual(startedShowingWindow, showsWindow)
            delegate.applicationDidResignActive(Notification(name: NSApplication.didResignActiveNotification))
            if showsWindow {
                XCTAssertEqual(phases, [])
                // SwiftUI's appearance can precede the window being onscreen.
                delegate.mainWindowDidOpen()
                let other = makeWindow()
                other.orderFront(nil)
                await settle()
                XCTAssertEqual(phases, [])
                let main = makeWindow()
                main.identifier = NSUserInterfaceItemIdentifier("main-AppWindow-1")
                main.orderFront(nil)
                await waitUntil { !phases.isEmpty }
                other.close()
                main.close()
                // A closed window can linger in NSApp.windows; keep it out of main-window lookups.
                main.identifier = nil
            } else {
                // AppKit window state reaches the core from the start.
                XCTAssertFalse(phases.isEmpty)
            }
            delegate.applicationWillTerminate(notification())
        }
    }

    func testUserLaunchInMenuBarOnlyModeStartsInBackground() async {
        var startedShowingWindow: Bool?
        var services = isolatedServices()
        services.arguments = []
        services.menuBarOnly = { true }
        services.startCore = { startedShowingWindow = $0 }
        let delegate = AppDelegate(startup: AppStartup(), services: services)
        delegate.applicationDidFinishLaunching(notification())
        await waitUntil { startedShowingWindow != nil }
        XCTAssertEqual(startedShowingWindow, false)
        delegate.applicationWillTerminate(notification())
    }

    func testShownMainWindowIsNeverSavedForRestoration() async {
        var services = isolatedServices()
        services.arguments = ["--background"]
        let delegate = AppDelegate(startup: AppStartup(), services: services)
        delegate.applicationDidFinishLaunching(notification())
        defer { delegate.applicationWillTerminate(notification()) }
        let main = NSWindow(
            contentRect: NSRect(x: 100, y: 100, width: 300, height: 200),
            styleMask: [.titled, .closable], backing: .buffered, defer: true)
        main.identifier = NSUserInterfaceItemIdentifier("main-AppWindow-1")
        let other = NSWindow(
            contentRect: NSRect(x: 100, y: 100, width: 300, height: 200),
            styleMask: [.titled, .closable], backing: .buffered, defer: true)
        XCTAssertTrue(main.isRestorable)
        XCTAssertTrue(other.isRestorable)
        // A window posts an occlusion change when it is first shown.
        for window in [main, other] {
            NotificationCenter.default.post(name: NSWindow.didChangeOcclusionStateNotification, object: window)
        }
        XCTAssertFalse(main.isRestorable)
        XCTAssertTrue(other.isRestorable)
    }

    func testRealAppKitWindowNotificationsDriveLifecycle() async {
        var phases: [ScenePhase] = []
        var services = isolatedServices()
        services.arguments = ["--background"]
        services.updateLifecycle = { phases.append($0) }
        let delegate = AppDelegate(startup: AppStartup(), services: services)
        delegate.applicationDidFinishLaunching(notification())
        let window = makeWindow()
        defer {
            window.close()
            delegate.applicationWillTerminate(notification())
        }
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        await waitUntil { NSApp.isActive }
        // This test delegate is not the host's installed NSApplication delegate.
        delegate.applicationDidBecomeActive(Notification(name: NSApplication.didBecomeActiveNotification))
        await waitUntil { phases.last == .active }
        window.resignKey()
        await waitUntil { phases.last == .inactive }
        // A panel that hides after resigning key posts no key or close notification.
        window.orderOut(nil)
        await waitUntil { phases.last == .background }
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        await waitUntil { phases.last == .active }
        window.close()
        await waitUntil { phases.last == .background }
    }

    /// The test host activates itself when a panel takes key, so this checks the decision only.
    func testKeyPanelCountsAsInUseWhileAnotherAppIsActive() {
        let window = makeWindow()
        let panel = makePanel()
        XCTAssertTrue(AppDelegate.isInUse(keyWindow: panel, appIsActive: false))
        XCTAssertFalse(AppDelegate.isInUse(keyWindow: window, appIsActive: false))
        XCTAssertTrue(AppDelegate.isInUse(keyWindow: window, appIsActive: true))
        XCTAssertFalse(AppDelegate.isInUse(keyWindow: nil, appIsActive: true))
    }

    func testMenuBarOnlyHidesMainWindowsButPreservesApprovalPanelsAndDockPreference() async {
        let defaults = UserDefaults.standard
        let previousPreference = defaults.object(forKey: DockIconMode.menuBarOnlyKey)
        let previousPolicy = NSApp.activationPolicy()
        let main = NSWindow(
            contentRect: NSRect(x: 100, y: 100, width: 300, height: 200),
            styleMask: [.titled, .closable], backing: .buffered, defer: false)
        let panel = NSPanel(
            contentRect: NSRect(x: 100, y: 100, width: 200, height: 100),
            styleMask: [.titled, .utilityWindow], backing: .buffered, defer: false)
        main.isReleasedWhenClosed = false
        panel.isReleasedWhenClosed = false
        defer {
            main.close()
            panel.close()
            defaults.set(previousPreference, forKey: DockIconMode.menuBarOnlyKey)
            NSApp.setActivationPolicy(previousPolicy)
        }
        main.orderFront(nil)
        panel.orderFront(nil)
        defaults.set(true, forKey: DockIconMode.menuBarOnlyKey)
        DockIconMode.apply(menuBarOnly: true)
        XCTAssertFalse(main.isVisible)
        XCTAssertTrue(panel.isVisible)
        XCTAssertEqual(NSApp.activationPolicy(), .accessory)
        var opens = 0
        DockIconMode.showMainWindow {
            opens += 1
            main.makeKeyAndOrderFront(nil)
        }
        await waitUntil { opens == 1 }
        XCTAssertTrue(main.isVisible)
        XCTAssertTrue(defaults.bool(forKey: DockIconMode.menuBarOnlyKey))
        XCTAssertEqual(NSApp.activationPolicy(), .accessory)
    }

    func testShowMainWindowActsAtOnceWhenRegularAndCoalescesWhileSettling() async {
        let defaults = UserDefaults.standard
        let previousPreference = defaults.object(forKey: DockIconMode.menuBarOnlyKey)
        let previousPolicy = NSApp.activationPolicy()
        defer {
            defaults.set(previousPreference, forKey: DockIconMode.menuBarOnlyKey)
            NSApp.setActivationPolicy(previousPolicy)
        }
        defaults.set(false, forKey: DockIconMode.menuBarOnlyKey)
        var opens = 0
        NSApp.setActivationPolicy(.regular)
        DockIconMode.showMainWindow { opens += 1 }
        XCTAssertEqual(opens, 1)
        // From menu-bar-only mode, requests wait for AppKit to settle and open one window.
        NSApp.setActivationPolicy(.accessory)
        DockIconMode.showMainWindow { opens += 1 }
        DockIconMode.showMainWindow { opens += 1 }
        XCTAssertEqual(opens, 1)
        await waitUntil { opens == 2 }
        await settle()
        XCTAssertEqual(opens, 2)
    }

    private func makeWindow() -> NSWindow {
        let window = NSWindow(
            contentRect: NSRect(x: 100, y: 100, width: 300, height: 200),
            styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        // The UI tests launch this bundle; a restored window would open their main scene.
        window.isRestorable = false
        return window
    }

    private func makePanel() -> NSPanel {
        let panel = NSPanel(
            contentRect: NSRect(x: 100, y: 100, width: 300, height: 200),
            styleMask: [.nonactivatingPanel, .titled], backing: .buffered, defer: false)
        panel.isReleasedWhenClosed = false
        panel.isRestorable = false
        return panel
    }

    private func isolatedServices() -> AppDelegateServices {
        var services = AppDelegateServices()
        // Otherwise the delegate reads the ambient launch AppleEvent.
        services.loginActivation = false
        services.prepareStorage = { nil }
        services.appGroup = nil
        services.siblingBundleIdentifier = nil
        services.acquireInstance = { _ in StartupTestInstance() }
        services.startCore = { _ in }
        services.preferences = { StartupPreferences(loaded: true) }
        services.applyDockMode = { _ in }
        services.menuBarOnly = { false }
        services.showMainWindow = { $0() }
        services.observePreferences = { _ in }
        services.awaitSiblingActivation = { _ in }
        services.terminate = { XCTFail("Unexpected process exit") }
        services.updateLifecycle = { _ in }
        return services
    }

    /// Lets queued main-actor work run before asserting that something did not happen.
    private func settle() async {
        try? await Task.sleep(for: .milliseconds(200))
    }

    private func notification() -> Notification { Notification(name: NSApplication.didFinishLaunchingNotification) }

    private func waitUntil(_ ready: () -> Bool, file: StaticString = #filePath, line: UInt = #line) async {
        let deadline = ContinuousClock.now + .seconds(3)
        while !ready(), ContinuousClock.now < deadline {
            try? await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertTrue(ready(), "Startup event did not arrive", file: file, line: line)
    }
}
