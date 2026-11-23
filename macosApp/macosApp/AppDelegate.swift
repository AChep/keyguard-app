import AppKit
import KeyguardShared
import KeyguardUI

/// Owns startup and process services independently of SwiftUI window appearances.
///
/// | Phase | Meaning | Window decisions |
/// |---|---|---|
/// | `starting` | Arbitrating, or a secondary about to exit | Hold requests and the launch decision |
/// | `owner` | Holds the instance lock until process exit | Open for requests; decide the launch once preferences load |
/// | `failed` | Storage or arbitration failed; stays during a retry | Hold requests; decide the launch; a Dock reopen shows the error |
@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private enum Phase {
        case starting
        /// The instance keeps the lock until process exit.
        case owner(any AppProcessInstance)
        case failed
    }

    private let startup: AppStartup
    private let services: AppDelegateServices
    private var quickSearch: QuickSearchController?
    private var approvals: [AgentApprovalPanelController] = []
    private var openMainWindow: (() -> Void)?
    private var launch: MacosStartup?
    private var phase = Phase.starting
    private var launchDecided = false
    private var pendingOpen = false
    private var hasOpenedMainWindow = false
    /// A launch that shows its main window keeps the vault alive until that window is visible.
    private var holdsLifecycleForMainWindow = false
    private var windowObservers: [NSObjectProtocol] = []
    private var startupTask: Task<Void, Never>?
    private var pendingURLs: [URL] = []
    private var nativeActivation: NativeWindowActivation?

    override convenience init() {
        #if KEYGUARD_STARTUP_TESTING
        self.init(startup: .shared, services: StartupTestSupport.services)
        StartupTestSupport.delegate = self
        #else
        self.init(startup: .shared, services: AppDelegateServices())
        #endif
    }

    init(startup: AppStartup, services: AppDelegateServices) {
        self.startup = startup
        self.services = services
        super.init()
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Icons download through the shared core's URLSession clients, and Gravatar
        // avatars through AsyncImage; both use URLCache.shared. The default shared
        // cache is too small to retain them, so enlarge it once at startup; the bytes
        // then stay in memory (and on disk across launches). Clients that present a
        // client certificate keep no cache.
        URLCache.shared = URLCache(
            memoryCapacity: 32 * 1024 * 1024,
            diskCapacity: 256 * 1024 * 1024
        )
        // The launch AppleEvent is only current while this handler runs.
        let launch = MacosStartup(
            arguments: services.arguments,
            loginActivation: services.loginActivation ?? MacosStartup.companion.launchedAtLogin())
        self.launch = launch
        let menuBarOnly = services.menuBarOnly()
        services.applyDockMode(menuBarOnly)
        // Only a user launch shows its window without waiting for preferences.
        holdsLifecycleForMainWindow =
            !menuBarOnly && !launch.needsStartupPreferences && launch.showsMainWindow(startInBackground: true)

        startup.onStarted = { [weak self] model in self?.startControllers(model: model) }
        startup.onStartRequested = { [weak self] in self?.beginStartup() }
        beginStartup()
        observeWindowActivity()
        updateLifecycle()
        resolveInitialLaunch()
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        let menuBarOnly = services.menuBarOnly()
        let closeToTray = services.preferences().closeToTray
        // Closing an approval or quick-search panel in a background session is
        // not the same action as closing the main application window.
        return hasOpenedMainWindow && !menuBarOnly && !closeToTray
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        // A Dock reopen targets this process; a request from the other flavor is meant for the owner.
        if case .failed = phase, let openMainWindow {
            // Show this process's startup error; a held request stays held.
            services.showMainWindow(openMainWindow)
        } else {
            requestMainWindow()
        }
        return false
    }

    func application(_ application: NSApplication, open urls: [URL]) {
        pendingURLs.append(contentsOf: urls)
        requestMainWindow()
        deliverURLs()
    }

    func applicationDidBecomeActive(_ notification: Notification) { updateLifecycle() }
    func applicationDidResignActive(_ notification: Notification) { updateLifecycle() }

    func applicationWillTerminate(_ notification: Notification) {
        // Ownership stays held until process exit; the OS then releases the lock.
        for observer in windowObservers { NotificationCenter.default.removeObserver(observer) }
    }

    private func beginStartup() {
        guard startupTask == nil else { return }
        if case .owner = phase { return }
        startupTask = Task {
            defer { startupTask = nil }
            if let errorKey = services.prepareStorage() {
                fail(messageKey: errorKey)
                return
            }
            do {
                installNativeActivation()
                guard let launch, let owner = try await services.acquireInstance(launch) else {
                    // Another process, usually the other app flavor, owns the App Group data.
                    if launch?.activatesExistingInstance == true {
                        let sibling = services.siblingBundleIdentifier
                        nativeActivation?.activateExisting(yieldingTo: sibling)
                        // The owner activates after a delay; exiting first can leave
                        // its window behind another app.
                        if let sibling { await services.awaitSiblingActivation(sibling) }
                    }
                    services.terminate()
                    return
                }
                phase = .owner(owner)
                services.startCore(holdsLifecycleForMainWindow)
                observePreferences()
                resolveInitialLaunch()
                deliverURLs()
            } catch {
                fail(
                    messageKey: "instance_service_error_text", titleKey: "instance_service_error_title",
                    details: error.localizedDescription)
            }
        }
    }

    /// The phase stays failed during a retry, until ownership is acquired.
    private func fail(messageKey: String, titleKey: String? = nil, details: String? = nil) {
        if let titleKey {
            startup.fail(messageKey: messageKey, titleKey: titleKey, details: details)
        } else {
            // The storage error keeps the model's default title.
            startup.fail(messageKey: messageKey, details: details)
        }
        phase = .failed
        resolveInitialLaunch()
    }

    /// A login launch decides on its window once the preferences load.
    private func observePreferences() {
        services.observePreferences { [weak self] in self?.resolveInitialLaunch() }
    }

    private func installNativeActivation() {
        guard nativeActivation == nil, let appGroup = services.appGroup else { return }
        // Install before arbitration: a user activation can arrive while the
        // primary is still acquiring its lock. Only the owner consumes it.
        nativeActivation = NativeWindowActivation(appGroup: appGroup) { [weak self] in
            self?.requestMainWindow()
        }
    }

    private func deliverURLs() {
        guard let model = startup.model else { return }
        let urls = pendingURLs
        pendingURLs.removeAll()
        for url in urls { model.handleDeepLink(url) }
    }

    func installMainWindowOpener(_ open: @escaping () -> Void) {
        openMainWindow = open
        resolveInitialLaunch()
    }

    /// From then on, closing the last window may quit the app.
    func mainWindowDidOpen() {
        hasOpenedMainWindow = true
    }

    /// Held until this process owns the instance and the window opener is known.
    private func requestMainWindow() {
        pendingOpen = true
        resolveInitialLaunch()
    }

    private func resolveInitialLaunch() {
        guard let launch, let openMainWindow else { return }
        // A process without ownership never consumes a request: it may be a
        // secondary that received the owner's activation and then failed.
        if case .owner = phase, pendingOpen {
            pendingOpen = false
            launchDecided = true
            services.showMainWindow(openMainWindow)
            return
        }
        guard !launchDecided else { return }
        switch phase {
        case .starting:
            return
        case .owner:
            if launch.needsStartupPreferences, !services.preferences().loaded { return }
        case .failed:
            // No core means no preferences: startInBackground defaults to true,
            // so a failed LOGIN launch stays quiet.
            break
        }
        launchDecided = true
        let menuBarOnly = services.menuBarOnly()
        let background = services.preferences().startInBackground
        if !menuBarOnly, launch.showsMainWindow(startInBackground: background) {
            services.showMainWindow(openMainWindow)
        }
    }

    private func startControllers(model: AppViewModel) {
        guard quickSearch == nil else { return }
        quickSearch = QuickSearchController(model: model)
        approvals = [
            .sshAgent(model: model),
            .gpgAgent(model: model),
            .environments(model: model),
            .cli(model: model),
        ]
        updateLifecycle()
    }

    private func observeWindowActivity() {
        // Occlusion changes cover windows ordered out or minimized without
        // closing, such as Quick Search hiding after it resigns key.
        for name in [
            NSWindow.didBecomeKeyNotification, NSWindow.didResignKeyNotification, NSWindow.willCloseNotification,
            NSWindow.didChangeOcclusionStateNotification,
        ] {
            windowObservers.append(
                NotificationCenter.default.addObserver(
                    forName: name, object: nil, queue: .main
                ) { [weak self] notification in
                    if let window = notification.object as? NSWindow {
                        MainActor.assumeIsolated { Self.disableRestoration(of: window) }
                    }
                    // Defer past the notification: a closing window is still visible
                    // while willClose is posted.
                    Task { @MainActor in self?.updateLifecycle() }
                }
            )
        }
    }

    /// Disables restoration so a quiet launch never reopens a restored vault window.
    static func disableRestoration(of window: NSWindow) {
        if DockIconMode.isMainWindow(window), window.isRestorable {
            window.isRestorable = false
        }
    }

    /// Non-activating panels (Quick Search, approvals) take key focus while another app stays active, but still use the vault.
    static func isInUse(keyWindow: NSWindow?, appIsActive: Bool) -> Bool {
        guard let keyWindow else { return false }
        return appIsActive || keyWindow is NSPanel
    }

    private func updateLifecycle() {
        var hasVisibleWindow = false
        var hasVisibleMainWindow = false
        var keyWindow: NSWindow?
        for window in NSApp.windows where window.isVisible && window.canBecomeKey {
            hasVisibleWindow = true
            hasVisibleMainWindow = hasVisibleMainWindow || DockIconMode.isMainWindow(window)
            if window.isKeyWindow { keyWindow = window }
        }
        if holdsLifecycleForMainWindow {
            // The core starts active; window state before the first window would release the vault.
            guard hasVisibleMainWindow else { return }
            holdsLifecycleForMainWindow = false
        }
        let hasActiveWindow = Self.isInUse(keyWindow: keyWindow, appIsActive: NSApp.isActive)
        // A biometric prompt can temporarily deactivate the app; keep that
        // distinct from backgrounding so dismissal cannot restart unlocking.
        services.updateLifecycle(
            hasActiveWindow ? .active : hasVisibleWindow ? .inactive : .background)
    }
}
