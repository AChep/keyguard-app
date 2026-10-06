#if os(macOS)
import AppKit
import SwiftUI

/// Controls whether the app shows a Dock icon (regular) or runs as a menu-bar-only
/// agent (accessory). Backed by an `@AppStorage` flag so the choice persists, and
/// applied at runtime via `NSApp.setActivationPolicy` — no `LSUIElement` Info.plist
/// key, so the first launch always shows the normal app.
///
/// Bringing the main window to the front from an accessory app needs the
/// documented recipe: flip to `.regular`, let AppKit settle, activate, then open.
/// `makeKeyAndOrderFront` alone is unreliable from an agent app.
public enum DockIconMode {
    public static let menuBarOnlyKey = "menuBarOnly"

    @MainActor private static var appliedInitialMode = false

    /// A newly opened scene must not hide itself again after the user selects Open.
    @MainActor
    public static func applyInitialMode(menuBarOnly: Bool) {
        guard !appliedInitialMode else { return }
        appliedInitialMode = true
        // SwiftUI's onAppear can precede ordering the initial window onscreen.
        Task { @MainActor in
            await Task.yield()
            apply(menuBarOnly: menuBarOnly)
        }
    }

    /// Applies a preference change, preserving hidden windows and their state.
    @MainActor
    public static func apply(menuBarOnly: Bool) {
        NSApp.setActivationPolicy(menuBarOnly ? .accessory : .regular)
        if menuBarOnly {
            for window in NSApp.windows where window.canBecomeMain && !(window is NSPanel) {
                window.orderOut(nil)
            }
        }
    }

    /// Brings the main window forward, restoring the Dock icon first if we are in
    /// menu-bar-only mode. `open` should call `openWindow(id: "main")`.
    @MainActor
    public static func showMainWindow(open: @escaping () -> Void) {
        NSApp.setActivationPolicy(.regular)
        Task {
            try? await Task.sleep(for: .milliseconds(100))
            NSApp.activate(ignoringOtherApps: true)
            open()
            // If an existing main window is hidden behind others, raise it too.
            NSApp.windows
                .first { $0.identifier?.rawValue.contains("main") == true }?
                .makeKeyAndOrderFront(nil)
            // Opening the window does not turn off the user's Dock preference.
            if UserDefaults.standard.bool(forKey: menuBarOnlyKey) {
                NSApp.setActivationPolicy(.accessory)
            }
        }
    }
}
#endif
