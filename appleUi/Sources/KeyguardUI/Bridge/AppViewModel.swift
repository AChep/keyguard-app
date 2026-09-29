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
    public static let shared: AppViewModel = {
        let app = AppViewModel(core: KeyguardCore())
        app.start()
        return app
    }()

    public typealias Status = VaultStatus

    @ObservationIgnored private let core: KeyguardCore
    @ObservationIgnored private var started = false
    let auth: VaultSessionModel
    let login: BitwardenLoginModel
    let keepass: KeePassLoginModel
    let navigation: NavigationModel
    let dialogs: DialogsModel
    let filePicker: FilePickerModel
    let notifications: NotificationsModel
    let preferences: AppPreferencesModel
    let accounts: AccountsModel
    let cipherDetail: CipherDetailModel
    let vaultActions: VaultActionsModel
    let generator: GeneratorModel
    let generatorHistory: GeneratorHistoryModel
    let autofillGenerator: AutofillGeneratorModel
    let gpgTools: GpgToolsModel
    let addItem: AddItemModel
    let send: SendModel
    let watchtower: WatchtowerModel
    let directories: ServiceDirectoryModel
    let emailRelay: EmailRelayModel
    let wordlists: WordlistsModel
    let gpgAgent: GpgAgentModel
    let sshAgent: SshAgentModel
    let quickSearch: QuickSearchModel
    let appInformation: AppInformationModel
    let urlRules: UrlRulesModel
    let settings: SettingsModel
    let launchAtLogin: LaunchAtLoginModel
    let security: SecuritySettingsModel
    let autofillSettings: AutofillSettingsModel
    let subscriptions: SubscriptionsModel
    let changePassword: ChangePasswordModel
    let feedback: FeedbackModel
    let navigationSettings: NavigationSettingsModel
    let backups: BackupSettingsModel
    let external: ExternalActions
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
        let auth = VaultSessionModel(core: core, notifications: notifications)
        self.auth = auth
        let login = BitwardenLoginModel(core: core)
        self.login = login
        let keepass = KeePassLoginModel(core: core)
        self.keepass = keepass
        let navigation = NavigationModel(core: core)
        self.navigation = navigation
        let dialogs = DialogsModel(core: core)
        self.dialogs = dialogs
        let filePicker = FilePickerModel(core: core)
        self.filePicker = filePicker
        let preferences = AppPreferencesModel(core: core)
        self.preferences = preferences
        let accounts = AccountsModel(core: core)
        self.accounts = accounts
        let cipherDetail = CipherDetailModel(core: core)
        self.cipherDetail = cipherDetail
        let vaultActions = VaultActionsModel(core: core, notifications: notifications)
        self.vaultActions = vaultActions
        let generator = GeneratorModel(core: core)
        self.generator = generator
        let generatorHistory = GeneratorHistoryModel(core: core)
        self.generatorHistory = generatorHistory
        let autofillGenerator = AutofillGeneratorModel(core: core)
        self.autofillGenerator = autofillGenerator
        let gpgTools = GpgToolsModel(core: core)
        self.gpgTools = gpgTools
        let addItem = AddItemModel(core: core)
        self.addItem = addItem
        let send = SendModel(core: core)
        self.send = send
        let watchtower = WatchtowerModel(core: core)
        self.watchtower = watchtower
        let directories = ServiceDirectoryModel(core: core)
        self.directories = directories
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
        let urlRules = UrlRulesModel(core: core)
        self.urlRules = urlRules
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
        let changePassword = ChangePasswordModel(core: core)
        self.changePassword = changePassword
        let feedback = FeedbackModel(core: core)
        self.feedback = feedback
        let navigationSettings = NavigationSettingsModel(core: core)
        self.navigationSettings = navigationSettings
        let backups = BackupSettingsModel(core: core)
        self.backups = backups
        let external = ExternalActions(core: core)
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
        // Make SMAppService launch-at-login available to the shared module.
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
            core.setScenePhase(phase: KeyguardScenePhase.background)
        @unknown default:
            core.setScenePhase(phase: KeyguardScenePhase.inactive)
        }
    }

    /// Routes a `keyguard://` deep link to the shared navigation stack. Wired from
    /// each app's SwiftUI `onOpenURL`; unrecognized URLs are ignored by the core.
    public func handleDeepLink(_ url: URL) {
        core.handleDeepLink(url: url.absoluteString)
    }

}
