#if os(macOS)
import AppKit

/// Focus-only activation across app flavors that share one App Group. LaunchServices
/// reopens the same bundle; this reaches the other flavor's instance-lock owner.
/// No payload, process identity, signing request, or unlock action crosses it.
@MainActor
public final class NativeWindowActivation {
    private let name: Notification.Name
    private var observer: NSObjectProtocol?

    /// `onRequest` runs for every request; the caller ignores it until it owns the instance.
    public init(appGroup: String, onRequest: @escaping @MainActor () -> Void) {
        name = Notification.Name("\(appGroup).keyguard.open-main-window")
        observer = DistributedNotificationCenter.default().addObserver(
            forName: name, object: nil, queue: .main
        ) { notification in
            // Sandboxed senders must supply no userInfo. Do not interpret any
            // sender-controlled data even if an unsandboxed process supplies it.
            guard notification.object == nil, notification.userInfo == nil else { return }
            Task { @MainActor in onRequest() }
        }
    }

    isolated deinit {
        if let observer { DistributedNotificationCenter.default().removeObserver(observer) }
    }

    /// Asks the owner to show its main window. Cooperative activation keeps this
    /// process in front unless it first yields to the owner's bundle.
    public func activateExisting(yieldingTo bundleIdentifier: String?) {
        if let bundleIdentifier {
            NSApplication.shared.yieldActivation(toApplicationWithBundleIdentifier: bundleIdentifier)
        }
        // Apple explicitly permits App Sandbox notifications without userInfo.
        DistributedNotificationCenter.default().postNotificationName(
            name, object: nil, userInfo: nil, deliverImmediately: true)
    }
}
#endif
