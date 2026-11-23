import AppKit
import Foundation
import KeyguardUI

/// Compiled only into the isolated startup test application, never into Keyguard.
@MainActor
enum StartupTestSupport {
    static weak var delegate: AppDelegate?
    static let instance = StartupTestInstance()
    static var preferences = StartupPreferences(
        loaded: ProcessInfo.processInfo.environment["KEYGUARD_TEST_DELAY_PREFERENCES"] != "1",
        startInBackground: ProcessInfo.processInfo.environment["KEYGUARD_TEST_SHOW_LOGIN"] != "1",
        closeToTray: ProcessInfo.processInfo.environment["KEYGUARD_TEST_CLOSE_TO_TRAY"] == "1")

    /// The delegate's production preference observer, fired by the test command.
    static var preferencesChanged: (@MainActor () -> Void)?

    static var services: AppDelegateServices {
        var services = AppDelegateServices()
        // A login launch is selected by the flag; never by the ambient launch AppleEvent.
        services.loginActivation = false
        if ProcessInfo.processInfo.environment["KEYGUARD_TEST_USER_LAUNCH"] != "1",
            !services.arguments.contains("--launch-at-login")
        {
            services.arguments.append("--background")
        }
        services.prepareStorage = { nil }
        services.acquireInstance = { _ in
            record("ownership-acquired")
            return instance
        }
        services.startCore = { _ in record("core-started") }
        services.observePreferences = { preferencesChanged = $0 }
        services.preferences = { preferences }
        services.updateLifecycle = { record("lifecycle-\($0)") }
        services.showMainWindow = { open in
            record("window-requested")
            DockIconMode.showMainWindow {
                record("window-open-action")
                open()
            }
        }
        services.menuBarOnly = { ProcessInfo.processInfo.environment["KEYGUARD_TEST_MENU_BAR_ONLY"] == "1" }
        return services
    }

    static func record(_ event: String) {
        guard let path = ProcessInfo.processInfo.environment["KEYGUARD_TEST_EVENTS"] else { return }
        let url = URL(fileURLWithPath: path)
        let data = Data("\(event)\n".utf8)
        if !FileManager.default.fileExists(atPath: path) { FileManager.default.createFile(atPath: path, contents: nil) }
        guard let handle = try? FileHandle(forWritingTo: url) else { return }
        defer { try? handle.close() }
        handle.seekToEndOfFile()
        handle.write(data)
    }
}
