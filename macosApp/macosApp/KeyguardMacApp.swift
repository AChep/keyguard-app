import SwiftUI
import AppKit
import KeyguardUI

/// Owns process-wide AppKit surfaces that live outside the SwiftUI scene graph:
/// the global Quick Search hotkey + panel and the agent approval panels. All
/// driven by the one shared `AppViewModel`.
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var quickSearch: QuickSearchController?
    private var sshApproval: SshAgentApprovalController?
    private var gpgApproval: GpgAgentApprovalController?

    /// "Close to the menu bar": when enabled, closing the last window keeps
    /// Keyguard running in the menu bar. Menu-bar-only mode also requires the
    /// process to stay alive, independently of the normal window-close preference.
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        let menuBarOnly = UserDefaults.standard.bool(forKey: DockIconMode.menuBarOnlyKey)
        let closeToTray = AppViewModel.shared.appPreferences.closeToTray
        return !menuBarOnly && !closeToTray
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Favicons load via SwiftUI AsyncImage -> URLSession.shared -> URLCache.shared.
        // The default shared cache is too small to retain them, so enlarge it once at
        // startup; favicon bytes then stay in memory (and on disk across launches).
        URLCache.shared = URLCache(
            memoryCapacity: 32 * 1024 * 1024,
            diskCapacity: 256 * 1024 * 1024
        )

        quickSearch = QuickSearchController(model: .shared)
        sshApproval = SshAgentApprovalController(model: .shared)
        gpgApproval = GpgAgentApprovalController(model: .shared)
    }
}

@main
struct KeyguardMacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var model = AppViewModel.shared
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage(DockIconMode.menuBarOnlyKey) private var menuBarOnly = false

    var body: some Scene {
        // The main window carries a stable id so the menu-bar popover can raise
        // it via `openWindow(id: "main")`.
        WindowGroup(id: "main") {
            ContentView()
                .keyguardAppConfiguration(model: model, scenePhase: scenePhase)
                .frame(minWidth: 460, minHeight: 560)
                .onAppear { DockIconMode.applyInitialMode(menuBarOnly: menuBarOnly) }
                .onChange(of: menuBarOnly) { _, newValue in
                    DockIconMode.apply(menuBarOnly: newValue)
                }
        }
        .windowResizability(.contentSize)
        // Standard titlebar so each screen's `navigationTitle` (Vault, Send,
        // Watchtower, Settings, …) shows in the unified titlebar/toolbar.
        .windowStyle(.titleBar)
        // A top-level "Vault" menu: the Unlock screen's escape-hatch actions while
        // locked, and "Lock" while unlocked.
        .commands {
            VaultCommands()
            ToolbarCommands()
        }

        // Persistent status-bar item. Shares the one `AppViewModel` (and therefore
        // the one `KeyguardCore` / DI graph) with the main window — a second view
        // model would spin up a second session.
        MenuBarExtra("Keyguard", systemImage: "lock.shield") {
            MenuBarPopover()
                .keyguardEnvironment(model: model)
                .tint(model.accentColor)
        }
        .menuBarExtraStyle(.window)
    }
}
