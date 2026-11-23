import AppKit
import Observation
import SwiftUI
import KeyguardShared
import KeyguardUI

/// The process boundary: tests replace storage and ownership, while exercising the real delegate.
@MainActor
struct AppDelegateServices {
    var arguments: [String] = ProcessInfo.processInfo.arguments
    var loginActivation: Bool?
    var prepareStorage: () -> String? = { KeyguardStorage.shared.prepare() }
    /// The App Group both app flavors share; its instance lock arbitrates between them.
    var appGroup = Bundle.main.object(forInfoDictionaryKey: "KeyguardAppGroupIdentifier") as? String
    /// The other app flavor, which may own the instance lock.
    var siblingBundleIdentifier = Bundle.main.object(forInfoDictionaryKey: "KeyguardSiblingBundleIdentifier") as? String
    var acquireInstance: @MainActor (MacosStartup) async throws -> (any AppProcessInstance)? = {
        try await $0.acquireInstance()
    }
    /// Starts the core; the flag tells it whether this launch opens its main window.
    var startCore: (Bool) -> Void = { AppStartup.shared.startOwnedInstance(showsMainWindow: $0) }
    /// Calls back after each change of the started core's preferences, until they have loaded.
    var observePreferences: (@escaping @MainActor @Sendable () -> Void) -> Void = { onChange in
        guard let model = AppStartup.shared.model else { return }
        observeLoadedPreferences(of: model, onChange: onChange)
    }
    var preferences: () -> StartupPreferences = {
        let preferences = AppStartup.shared.model?.appPreferences
        return StartupPreferences(
            loaded: preferences?.loaded == true,
            startInBackground: preferences?.startInBackground ?? true,
            closeToTray: preferences?.closeToTray ?? false)
    }
    var menuBarOnly: () -> Bool = { UserDefaults.standard.bool(forKey: DockIconMode.menuBarOnlyKey) }
    var applyDockMode: (Bool) -> Void = { DockIconMode.apply(menuBarOnly: $0) }
    var showMainWindow: (@escaping () -> Void) -> Void = { DockIconMode.showMainWindow(open: $0) }
    /// Waits briefly for the other flavor to take focus before this process exits.
    var awaitSiblingActivation: @MainActor (String) async -> Void = { await awaitActivation(ofBundleIdentifier: $0) }
    var terminate: () -> Void = { NSApp.terminate(nil) }
    var updateLifecycle: (ScenePhase) -> Void = { AppStartup.shared.model?.updateScenePhase($0) }
}

@MainActor
private func observeLoadedPreferences(of model: AppViewModel, onChange: @escaping @MainActor @Sendable () -> Void) {
    withObservationTracking {
        _ = model.appPreferences.loaded
    } onChange: {
        // Runs before the change is applied; read the new value afterwards.
        Task { @MainActor in
            onChange()
            if !model.appPreferences.loaded { observeLoadedPreferences(of: model, onChange: onChange) }
        }
    }
}

/// Returns once the application activates, or after a second.
private func awaitActivation(ofBundleIdentifier bundleIdentifier: String) async {
    await withTaskGroup(of: Void.self) { group in
        group.addTask {
            let activations = NSWorkspace.shared.notificationCenter.notifications(
                named: NSWorkspace.didActivateApplicationNotification)
            for await notification in activations {
                let app = notification.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication
                if app?.bundleIdentifier == bundleIdentifier { return }
            }
        }
        group.addTask { try? await Task.sleep(for: .seconds(1)) }
        await group.next()
        group.cancelAll()
    }
}
