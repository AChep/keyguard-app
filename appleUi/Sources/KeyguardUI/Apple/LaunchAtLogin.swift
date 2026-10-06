#if os(macOS)
import Foundation
import ServiceManagement
import KeyguardShared

/// `SMAppService.mainApp`-backed implementation of the shared `LaunchAtLoginBridge`.
///
/// `ServiceManagement` is Swift/ObjC-only and unreachable from Kotlin/Native, so
/// the registration is done here and surfaced to the shared module through the
/// bridge registry, like the Keychain bridge. macOS 13+ only (we target 14),
/// so no legacy `SMLoginItemSetEnabled` / `LSSharedFileList` fallback is needed.
final class SMAppServiceLaunchAtLoginBridge: LaunchAtLoginBridge {
    func status() -> LaunchAtLoginStatus {
        switch SMAppService.mainApp.status {
        case .enabled:
            return .enabled
        case .notRegistered:
            return .disabled
        case .requiresApproval:
            return .requiresApproval
        case .notFound:
            return .unavailable
        @unknown default:
            return .unavailable
        }
    }

    func setEnabled(enabled: Bool) {
        do {
            if enabled {
                // Idempotent: registering an already-registered service throws,
                // so only register when not already enabled.
                if SMAppService.mainApp.status != .enabled {
                    try SMAppService.mainApp.register()
                }
            } else {
                try SMAppService.mainApp.unregister()
            }
        } catch {
            NSLog("Keyguard: launch-at-login \(enabled ? "register" : "unregister") failed: \(error)")
        }
    }

    func openLoginItemsSettings() {
        SMAppService.openSystemSettingsLoginItems()
    }
}

/// Safe to call more than once; the last registration wins.
func installLaunchAtLoginBridge() {
    LaunchAtLoginBridgeKt.registerLaunchAtLoginBridge(bridge: SMAppServiceLaunchAtLoginBridge())
}

#endif
