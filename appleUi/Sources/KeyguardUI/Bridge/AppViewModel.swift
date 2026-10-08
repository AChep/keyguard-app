import SwiftUI
import Observation
@preconcurrency import KeyguardShared
#if os(iOS)
import UIKit
#endif

/// Creates the process-wide core and shared feature models for every Apple surface.
/// Feature state and observation lifetimes live in the models below.
@MainActor
@Observable
public final class AppViewModel {
    static func create() -> AppViewModel {
        let app = AppViewModel(core: KeyguardCore())
        app.start()
        return app
    }

    public typealias Status = VaultStatus

    @ObservationIgnored private let core: KeyguardCore
    @ObservationIgnored private var started = false
    let auth: VaultSessionModel
    let accountLogin: AccountLoginModel
    let navigation: NavigationModel
    let dialogs: DialogsModel
    let filePicker: FilePickerModel
    let notifications: NotificationsModel
    let preferences: AppPreferencesModel
    let accounts: AccountsModel
    let vaultActions: VaultActionsModel
    let sessions: SessionFactory
    let watchtower: WatchtowerModel
    let emailRelay: EmailRelayModel
    let wordlists: WordlistsModel
    let gpgAgent: GpgAgentModel
    let sshAgent: SshAgentModel
    let quickSearch: QuickSearchModel
    let appInformation: AppInformationModel
    let settings: SettingsModel
    let launchAtLogin: LaunchAtLoginModel
    let security: SecuritySettingsModel
    let autofillSettings: AutofillSettingsModel
    let subscriptions: SubscriptionsModel
    let navigationSettings: NavigationSettingsModel
    let backups: BackupSettingsModel
    let external: ExternalActions
    let links: LinkOpeningCoordinator
    let autofillIndex: AutofillIndexService
    #if os(iOS)
    let screenAwake = ScreenAwakeCoordinator { UIApplication.shared.isIdleTimerDisabled = $0 }
    #endif

    /// Construction wires dependencies; only the shared app starts workers.
    init(core: KeyguardCore) {
        self.core = core
        let autofillIndex = AutofillIndexService(core: core)
        self.autofillIndex = autofillIndex
        let notifications = NotificationsModel(core: core)
        self.notifications = notifications
        let links = LinkOpeningCoordinator(
            supportsInAppBrowser: {
                #if os(iOS)
                true
                #else
                false
                #endif
            }(),
            openSystem: { url, universalLinksOnly in
                #if os(iOS)
                await UIApplication.shared.open(url, options: universalLinksOnly ? [.universalLinksOnly: true] : [:])
                #else
                NSWorkspace.shared.open(url)
                #endif
            },
            showFailure: { notifications.showLinkOpeningError() }
        )
        self.links = links
        let auth = VaultSessionModel(core: core)
        self.auth = auth
        self.accountLogin = AccountLoginModel(core: core, links: links)
        let navigation = NavigationModel(core: core)
        self.navigation = navigation
        let dialogs = DialogsModel(core: core)
        self.dialogs = dialogs
        let filePicker = FilePickerModel(core: core)
        self.filePicker = filePicker
        let preferences = AppPreferencesModel(core: core, links: links)
        self.preferences = preferences
        let accounts = AccountsModel(core: core)
        self.accounts = accounts
        let vaultActions = VaultActionsModel(core: core, notifications: notifications)
        self.vaultActions = vaultActions
        self.sessions = SessionFactory(core: core)
        let watchtower = WatchtowerModel(core: core)
        self.watchtower = watchtower
        let emailRelay = EmailRelayModel(core: core, notifications: notifications)
        self.emailRelay = emailRelay
        let wordlists = WordlistsModel(core: core, notifications: notifications)
        self.wordlists = wordlists
        self.gpgAgent = GpgAgentModel(core: core)
        let sshAgent = SshAgentModel(core: core)
        self.sshAgent = sshAgent
        let quickSearch = QuickSearchModel(core: core, vaultActions: vaultActions)
        self.quickSearch = quickSearch
        let appInformation = AppInformationModel(core: core)
        self.appInformation = appInformation
        let settings = SettingsModel(core: core)
        self.settings = settings
        let launchAtLogin = LaunchAtLoginModel(core: core)
        self.launchAtLogin = launchAtLogin
        let security = SecuritySettingsModel(core: core)
        self.security = security
        let autofillSettings = AutofillSettingsModel(core: core)
        self.autofillSettings = autofillSettings
        let subscriptions = SubscriptionsModel(core: core)
        self.subscriptions = subscriptions
        let navigationSettings = NavigationSettingsModel(core: core)
        self.navigationSettings = navigationSettings
        let backups = BackupSettingsModel(core: core)
        self.backups = backups
        let external = ExternalActions(core: core, links: links, notifications: notifications)
        self.external = external
    }

    private func start() {
        guard !started else { return }
        started = true
        // Make Keychain Services available (vault / unlock key storage; shared with
        // the AutoFill extension via the keychain access group). Both iOS and macOS
        // use the same `SecItem*` bridge, so this is not macOS-gated.
        installKeychainBridge()
        // Make StoreKit 2 in-app purchases available to the shared module before
        // premium status is read. Main app only — the AutoFill extension never
        // registers it, so premium there falls back to the cached value.
        installBillingBridge()
        core.startBilling()
        #if os(macOS)
        installLaunchAtLoginBridge()
        #endif
        // Auto-start the SSH agent whenever the persisted "SSH agent" preference
        // is on (including right now, on launch). Main app only — the AutoFill
        // extension constructs its own KeyguardCore and must never spawn the agent.
        core.startSshAgentApplier()
        core.startGpgAgentApplier()
        // AutoFill shares KeyguardCore, so app-only workers start here.
        core.startAutomaticBackups()
        core.startWatchtower()
        external.start()
        navigation.start()
        auth.start()
        autofillIndex.start()
        notifications.start()
        dialogs.start()
        filePicker.start()
        preferences.start()
    }

    // The app shells keep a small public API; feature views use focused models.
    public var status: Status { auth.status }
    public var appPreferences: AppPreferencesSnapshot { preferences.appPreferences }
    public var accentColor: Color? { preferences.accentColor }
    public var pendingUnlockAction: UnlockActionSnapshot? {
        get { auth.pendingUnlockAction }
        set { auth.pendingUnlockAction = newValue }
    }

    public func invokeUnlockAction(_ id: String) { auth.invokeUnlockAction(id) }

    /// Mirrors the app's `scenePhase` into the shared sync worker so the
    /// live-sync notifications websocket stays connected in the foreground and
    /// pauses when the app is backgrounded (re-syncing on return).
    public func updateScenePhase(_ phase: ScenePhase) {
        switch phase {
        case .active:
            autofillIndex.setForeground(true)
            core.setScenePhase(phase: KeyguardScenePhase.active)
            core.refreshBilling()
        case .inactive:
            core.setScenePhase(phase: KeyguardScenePhase.inactive)
        case .background:
            autofillIndex.setForeground(false)
            links.cancelPendingRequests()
            core.setScenePhase(phase: KeyguardScenePhase.background)
        @unknown default:
            core.setScenePhase(phase: KeyguardScenePhase.inactive)
        }
    }

    /// Routes a `keyguard://` deep link to the shared navigation stack; unrecognized
    /// URLs are ignored by the core.
    public func handleDeepLink(_ url: URL) {
        core.handleDeepLink(url: url.absoluteString)
    }

}
