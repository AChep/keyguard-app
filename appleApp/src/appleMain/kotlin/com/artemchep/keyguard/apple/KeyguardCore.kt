package com.artemchep.keyguard.apple

import com.artemchep.keyguard.AppleBillingBridge
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.main
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.registerLaunchAtLoginBridge
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.usecase.PutLaunchAtLogin
import com.artemchep.keyguard.feature.auth.accountStateProducer
import com.artemchep.keyguard.feature.changepassword.changePasswordStateProducer
import com.artemchep.keyguard.feature.generator.GeneratorRoute
import com.artemchep.keyguard.feature.home.settings.accounts.accountListScreenStateProducer
import com.artemchep.keyguard.feature.home.settings.backups.AutomaticBackupsSettingsState
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.home.vault.quicksearch.QuickSearchHeadlessController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.send.sendListScreenStateProducer
import com.artemchep.keyguard.feature.send.view.sendViewScreenStateProducer
import com.artemchep.keyguard.apple.account.AccountDetailSnapshot
import com.artemchep.keyguard.apple.account.AccountListSnapshot
import com.artemchep.keyguard.apple.account.AccountsController
import com.artemchep.keyguard.apple.account.SyncStatusSnapshot
import com.artemchep.keyguard.apple.add.AddDatePickerRequest
import com.artemchep.keyguard.apple.add.AddEditFormRequest
import com.artemchep.keyguard.apple.add.AddFilePickerRequest
import com.artemchep.keyguard.apple.add.AddItemController
import com.artemchep.keyguard.apple.add.AddItemFormSnapshot
import com.artemchep.keyguard.apple.add.AddKeyGeneratorSnapshot
import com.artemchep.keyguard.apple.auth.AuthController
import com.artemchep.keyguard.apple.auth.AuthPromptHost
import com.artemchep.keyguard.apple.auth.KeePassFilePickerRequest
import com.artemchep.keyguard.apple.auth.KeePassLoginController
import com.artemchep.keyguard.apple.auth.KeePassLoginSnapshot
import com.artemchep.keyguard.apple.auth.LoginController
import com.artemchep.keyguard.apple.auth.LoginSnapshot
import com.artemchep.keyguard.apple.auth.MessageSnapshot
import com.artemchep.keyguard.apple.auth.MessagesController
import com.artemchep.keyguard.apple.auth.SetupSnapshot
import com.artemchep.keyguard.apple.auth.TwofaSnapshot
import com.artemchep.keyguard.apple.auth.UnlockSnapshot
import com.artemchep.keyguard.apple.auth.WebDavSettingsSnapshot
import com.artemchep.keyguard.apple.billing.SubscriptionsController
import com.artemchep.keyguard.apple.billing.SubscriptionsSnapshot
import com.artemchep.keyguard.apple.core.AppLifecycleController
import com.artemchep.keyguard.apple.core.KeyguardRuntime
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.KeyguardScenePhase
import com.artemchep.keyguard.apple.core.KeyguardVaultStatus
import com.artemchep.keyguard.apple.core.NavigationStackController
import com.artemchep.keyguard.apple.core.ScreenEntrySnapshot
import com.artemchep.keyguard.apple.dialog.AccountPickerSnapshot
import com.artemchep.keyguard.apple.dialog.AttachmentPreviewSnapshot
import com.artemchep.keyguard.apple.dialog.BarcodeSnapshot
import com.artemchep.keyguard.apple.dialog.ColorPickerSnapshot
import com.artemchep.keyguard.apple.dialog.ConfirmationSnapshot
import com.artemchep.keyguard.apple.dialog.DialogController
import com.artemchep.keyguard.apple.dialog.ElevatedAccessSnapshot
import com.artemchep.keyguard.apple.dialog.EmailLeakSnapshot
import com.artemchep.keyguard.apple.dialog.InfoDialogSnapshot
import com.artemchep.keyguard.apple.dialog.PasswordMemorySnapshot
import com.artemchep.keyguard.apple.dialog.LargeTypeSnapshot
import com.artemchep.keyguard.apple.dialog.PasskeyCredentialSnapshot
import com.artemchep.keyguard.apple.dialog.PasswordLeakSnapshot
import com.artemchep.keyguard.apple.dialog.WebsiteLeakSnapshot
import com.artemchep.keyguard.apple.directory.ServiceDirectoryController
import com.artemchep.keyguard.apple.filter.CipherFiltersController
import com.artemchep.keyguard.apple.directory.ServiceDirectoryDetailSnapshot
import com.artemchep.keyguard.apple.generator.EmailRelayController
import com.artemchep.keyguard.apple.generator.EmailRelayFormSnapshot
import com.artemchep.keyguard.apple.generator.EmailRelayActionRequestSnapshot
import com.artemchep.keyguard.apple.generator.GeneratorController
import com.artemchep.keyguard.apple.generator.GeneratorHistoryController
import com.artemchep.keyguard.apple.generator.GeneratorHistorySnapshot
import com.artemchep.keyguard.apple.generator.GeneratorSnapshot
import com.artemchep.keyguard.apple.generator.WordlistController
import com.artemchep.keyguard.apple.generator.WordlistActionRequestSnapshot
import com.artemchep.keyguard.apple.gpgtools.GpgToolsController
import com.artemchep.keyguard.apple.gpgtools.GpgToolsFilePickerRequest
import com.artemchep.keyguard.apple.gpgtools.GpgToolsPublicKeyRequest
import com.artemchep.keyguard.apple.gpgtools.GpgToolsPublicKeyValidationSnapshot
import com.artemchep.keyguard.apple.gpgtools.GpgToolsExportSnapshot
import com.artemchep.keyguard.apple.gpgtools.GpgToolsResultSnapshot
import com.artemchep.keyguard.apple.gpgtools.GpgToolsSnapshot
import com.artemchep.keyguard.apple.lists.AutofillController
import com.artemchep.keyguard.apple.lists.AutofillCredentialSnapshot
import com.artemchep.keyguard.apple.lists.AutofillIdentitySnapshot
import com.artemchep.keyguard.apple.lists.AutofillSuggestionSnapshot
import com.artemchep.keyguard.apple.lists.PasskeyAssertionSnapshot
import com.artemchep.keyguard.apple.lists.PasskeyController
import com.artemchep.keyguard.apple.lists.PasskeyIdentitySnapshot
import com.artemchep.keyguard.apple.lists.PasskeyRegistrationSnapshot
import com.artemchep.keyguard.apple.lists.LicenseListSnapshot
import com.artemchep.keyguard.apple.lists.LocalizationContributorsSnapshot
import com.artemchep.keyguard.apple.lists.LogsSnapshot
import com.artemchep.keyguard.apple.lists.PasswordHistorySnapshot
import com.artemchep.keyguard.apple.lists.ReadOnlyListsController
import com.artemchep.keyguard.apple.lists.SshAgentHistorySnapshot
import com.artemchep.keyguard.apple.lists.UrlRuleListSnapshot
import com.artemchep.keyguard.apple.onboarding.OnboardingController
import com.artemchep.keyguard.apple.model.SettingOptionSnapshot
import com.artemchep.keyguard.apple.model.TotpFieldSnapshot
import com.artemchep.keyguard.apple.model.VaultItemSnapshot
import com.artemchep.keyguard.apple.send.SendDetailController
import com.artemchep.keyguard.apple.send.SendDetailSnapshot
import com.artemchep.keyguard.apple.send.SendListController
import com.artemchep.keyguard.apple.send.SendListSnapshot
import com.artemchep.keyguard.apple.settings.AboutTeamSnapshot
import com.artemchep.keyguard.apple.settings.AppPreferencesSnapshot
import com.artemchep.keyguard.apple.settings.DebugSettingsController
import com.artemchep.keyguard.apple.settings.DebugSettingsSnapshot
import com.artemchep.keyguard.apple.settings.AppearanceController
import com.artemchep.keyguard.apple.settings.AppearanceSettingsSnapshot
import com.artemchep.keyguard.apple.settings.BackupSettingsSnapshot
import com.artemchep.keyguard.apple.settings.BackupsController
import com.artemchep.keyguard.apple.settings.ChangePasswordController
import com.artemchep.keyguard.apple.settings.ChangePasswordSnapshot
import com.artemchep.keyguard.apple.settings.DataSafetyItemSnapshot
import com.artemchep.keyguard.apple.settings.NavItemsController
import com.artemchep.keyguard.apple.settings.NavItemsSettingsSnapshot
import com.artemchep.keyguard.apple.settings.NavItemsSnapshot
import com.artemchep.keyguard.apple.settings.AutofillSettingsController
import com.artemchep.keyguard.apple.settings.AutofillSettingsSnapshot
import com.artemchep.keyguard.apple.settings.FeedbackController
import com.artemchep.keyguard.apple.settings.FeedbackSnapshot
import com.artemchep.keyguard.apple.settings.LaunchAtLoginController
import com.artemchep.keyguard.apple.settings.LaunchAtLoginSnapshot
import com.artemchep.keyguard.apple.settings.SecurityController
import com.artemchep.keyguard.apple.settings.SecuritySettingsSnapshot
import com.artemchep.keyguard.apple.settings.SettingsItemSnapshot
import com.artemchep.keyguard.apple.settings.SettingsListSnapshot
import com.artemchep.keyguard.apple.settings.SettingsSearchIndex
import com.artemchep.keyguard.apple.settings.StaticDataController
import com.artemchep.keyguard.apple.settings.AppInformationController
import com.artemchep.keyguard.apple.settings.AppInformationSnapshot
import com.artemchep.keyguard.apple.gpgagent.GpgAgentController
import com.artemchep.keyguard.apple.gpgagent.GpgAgentFiltersSnapshot
import com.artemchep.keyguard.apple.gpgagent.GpgAgentHistorySnapshot
import com.artemchep.keyguard.apple.gpgagent.GpgAgentRequestSnapshot
import com.artemchep.keyguard.apple.gpgagent.GpgAgentSettingsSnapshot
import com.artemchep.keyguard.apple.gpgagent.GpgAgentStatusSnapshot
import com.artemchep.keyguard.apple.sshagent.SshAgentController
import com.artemchep.keyguard.apple.sshagent.SshAgentFiltersSnapshot
import com.artemchep.keyguard.apple.sshagent.SshAgentRequestSnapshot
import com.artemchep.keyguard.apple.sshagent.SshAgentSettingsSnapshot
import com.artemchep.keyguard.apple.sshagent.SshAgentStatusSnapshot
import com.artemchep.keyguard.apple.vault.CipherDetailController
import com.artemchep.keyguard.apple.vault.CollectionsController
import com.artemchep.keyguard.apple.vault.DownloadsController
import com.artemchep.keyguard.apple.vault.EquivalentDomainsController
import com.artemchep.keyguard.apple.vault.ExportController
import com.artemchep.keyguard.apple.vault.FoldersController
import com.artemchep.keyguard.apple.vault.QuickCopyController
import com.artemchep.keyguard.apple.vault.OrganizationsController
import com.artemchep.keyguard.apple.vault.QuickSearchController
import com.artemchep.keyguard.apple.vault.QuickSearchSnapshot
import com.artemchep.keyguard.apple.vault.RecentsController
import com.artemchep.keyguard.apple.vault.RecentsTabsSnapshot
import com.artemchep.keyguard.apple.vault.VaultDetailSnapshot
import com.artemchep.keyguard.apple.vault.VaultDetailTotpSnapshot
import com.artemchep.keyguard.apple.vault.VaultListSession
import com.artemchep.keyguard.apple.vault.VaultListSessionConfig
import com.artemchep.keyguard.apple.vault.toArgs
import com.artemchep.keyguard.apple.watchtower.WatchtowerAlertsSnapshot
import com.artemchep.keyguard.apple.watchtower.WatchtowerController
import com.artemchep.keyguard.apple.watchtower.WatchtowerSettingsSnapshot
import com.artemchep.keyguard.apple.watchtower.WatchtowerSnapshot
import com.artemchep.keyguard.res.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.koin.core.scope.Scope
import platform.Foundation.create

/** Facade exposing shared Keyguard logic to native SwiftUI clients. */
/**
 * How long the bridge may coalesce successive snapshot emissions of one
 * observer. Echo latency is invisible to the user: the SwiftUI edit
 * buffers display text locally and only reconcile against snapshots on
 * revision changes, so bursts (fast typing) can be batched aggressively.
 */
internal val SNAPSHOT_THROTTLE = 48.milliseconds

/**
 * Emits the first value immediately, then at most one value per [period],
 * always ending with the latest upstream value. Bounds the K/N -> Swift
 * bridging and SwiftUI diffing work to ~20 snapshots/s per observer while
 * typing, without ever dropping the final state.
 */
internal fun <T> Flow<T>.throttleLatest(
    period: Duration = SNAPSHOT_THROTTLE,
): Flow<T> = conflate()
    .transform { value ->
        emit(value)
        delay(period)
    }

class KeyguardCore(runtime: KeyguardRuntime) {
    constructor() : this(KeyguardRuntime.APP)
    /**
     * Shared kernel: the DI graph, the coroutine scopes and the observer
     * plumbing that enforces the bridge's threading contract (see [CoreContext]).
     */
    private val context = CoreContext(runtime)

    /**
     * The SwiftUI-presented dialog subsystem (Large Type, Barcode, passkey
     * credential, attachment preview). All channel state + headless producers
     * live in [DialogController]; the vault / send / account / password-history
     * detail observers hand its [DialogController.navigationInterceptor] to their
     * producers to catch the dialog routes.
     */
    /**
     * The native biometric (Touch ID / Face ID) + YubiKey prompt host, shared by
     * the create / unlock flow ([AuthController]) and the master-password re-prompt
     * dialog ([DialogController]). YubiKey operations use the shared native client.
     */
    private val authPromptHost = AuthPromptHost(context, com.artemchep.keyguard.util.yubikey.NativeYubiKeyClient())

    private val dialogController by lazy { DialogController(context, authPromptHost) }

    /**
     * The password / passphrase / username / email generator. All producer
     * wiring + snapshot projection live in [GeneratorController].
     */
    private val generatorController by lazy { GeneratorController(context) }

    /**
     * A second generator instance dedicated to the in-form Autofill / generate
     * affordance of the add / edit item form (the username / password field's
     * generate button). Runs the same shared producer with field-specific args
     * (and a stable producer key per field), independent of the main generator
     * screen so the two never share routing tables.
     */
    private val autofillGeneratorController by lazy { GeneratorController(context) }

    /**
     * The native "GPG Tools" section (encrypt / decrypt / sign / verify). All
     * producer wiring + snapshot projection live in [GpgToolsController]; the run
     * outcome is surfaced via a separate result callback (the shared producer
     * delivers it through a result-dialog navigation intent the controller
     * intercepts).
     */
    private val gpgToolsController by lazy { GpgToolsController(context) }

    private val appLifecycleController = AppLifecycleController(context)

    fun setScenePhase(phase: KeyguardScenePhase) = appLifecycleController.setScenePhase(phase)

    fun startAutomaticBackups() = appLifecycleController.startAutomaticBackups()

    fun startWatchtower() = appLifecycleController.startWatchtower()

    fun observeStatus(
        onChange: (KeyguardVaultStatus) -> Unit,
    ): KeyguardCancellable = appLifecycleController.observeStatus(onChange)

    fun lockVault() = appLifecycleController.lockVault()

    /** Closes this extension core permanently. Await before completing or cancelling AutoFill. */
    @Throws(Exception::class)
    suspend fun closeAutofillSession() = appLifecycleController.closeAutofillSession()

    fun syncVault() = appLifecycleController.syncVault()

    private val quickCopyController by lazy { QuickCopyController(context) }

    suspend fun quickCopyCipherField(secretId: String, accountId: String, field: String) =
        quickCopyController.quickCopyCipherField(secretId, accountId, field)

    suspend fun generateAndCopyPassword() = quickCopyController.generateAndCopyPassword()

    // ---------------------------------------------------------------------------
    // Launch at login (macOS). Thin bridge over the shared Get/PutLaunchAtLogin
    // use cases, which delegate to the Swift SMAppService bridge registered at
    // startup via [registerLaunchAtLoginBridge].
    // ---------------------------------------------------------------------------

    private val launchAtLoginController by lazy { LaunchAtLoginController(context) }

    fun observeLaunchAtLogin(
        onChange: (LaunchAtLoginSnapshot) -> Unit,
    ): KeyguardCancellable = launchAtLoginController.observeLaunchAtLogin(onChange)

    fun setLaunchAtLogin(enabled: Boolean) = launchAtLoginController.setLaunchAtLogin(enabled)

    fun openLoginItemsSettings() = launchAtLoginController.openLoginItemsSettings()

    // ---------------------------------------------------------------------------
    // First-run onboarding. Thin bridge over the shared Get/PutOnboardingLastVisit
    // use cases that back the Compose OnboardingBanner / OnboardingScreen pair: the
    // banner is shown until the user has visited (or dismissed) the onboarding, and
    // [markOnboarded] stamps the instant so it never shows again. The feature cards
    // are static localized content rendered natively in Swift.
    // ---------------------------------------------------------------------------

    private val onboardingController by lazy { OnboardingController(context) }

    fun observeOnboarding(
        onChange: (Boolean) -> Unit,
    ): KeyguardCancellable = onboardingController.observeOnboarding(onChange)

    fun markOnboarded() = onboardingController.markOnboarded()

    // ---------------------------------------------------------------------------
    // Quick Search (global hotkey overlay). Reuses the shared quick-search
    // producer + copy/open logic headless via [QuickSearchHeadlessController];
    // the Swift panel maps key events to the semantic input methods below.
    // ---------------------------------------------------------------------------

    private val quickSearchController by lazy { QuickSearchController(context) }

    fun observeQuickSearch(
        onChange: (QuickSearchSnapshot) -> Unit,
    ): KeyguardCancellable = quickSearchController.observeQuickSearch(onChange)

    fun observeQuickSearchTotp(
        onChange: (Map<String, TotpFieldSnapshot>) -> Unit,
    ): KeyguardCancellable = quickSearchController.observeQuickSearchTotp(onChange)

    /**
     * The Quick Search result rows as state-anchored [VaultListDelta]s — the SAME
     * background-delivered contract as the vault list's `observeListDelta` and
     * Recents' `observeRecentsListDelta`. See
     * [QuickSearchController.observeQuickSearchListDelta].
     */
    fun observeQuickSearchListDelta(
        onChange: (com.artemchep.keyguard.apple.vault.VaultListDelta) -> Unit,
    ): KeyguardCancellable = quickSearchController.observeQuickSearchListDelta(onChange)

    fun setQuickSearchQuery(text: String) = quickSearchController.setQuickSearchQuery(text)

    fun clearQuickSearchQuery() = quickSearchController.clearQuickSearchQuery()

    fun moveQuickSearchSelection(direction: Int) = quickSearchController.moveQuickSearchSelection(direction)

    fun moveQuickSearchActionSelection(direction: Int) =
        quickSearchController.moveQuickSearchActionSelection(direction)

    fun selectQuickSearchItem(id: String) = quickSearchController.selectQuickSearchItem(id)

    fun invokeQuickSearchDefaultAction() = quickSearchController.invokeQuickSearchDefaultAction()

    fun invokeQuickSearchAction(typeName: String) = quickSearchController.invokeQuickSearchAction(typeName)

    fun setQuickSearchOpenUrlHandler(handler: ((String) -> Unit)?) =
        quickSearchController.setQuickSearchOpenUrlHandler(handler)

    /**
     * Sets the handler that opens external URLs for producer-emitted
     * [com.artemchep.keyguard.feature.navigation.NavigationIntent.NavigateToBrowser]
     * intents (account "premium", autofill help links, …), threaded through the
     * navigation interceptor.
     */
    fun setOpenUrlHandler(handler: ((String) -> Unit)?) =
        navigationStackController.setOpenUrlHandler(handler)

    /** Opens system actions (Maps, Mail, Phone, Messages) independently of browser preferences. */
    fun setOpenSystemUrlHandler(handler: ((String) -> Unit)?) =
        navigationStackController.setOpenSystemUrlHandler(handler)

    /**
     * Sets the handler that presents the native share sheet for a producer-emitted
     * [com.artemchep.keyguard.feature.navigation.NavigationIntent.NavigateToShare]
     * intent (the Send detail "share" action), threaded through the navigation
     * interceptor with the public Send link text.
     */
    fun setShareHandler(handler: ((String) -> Unit)?) =
        navigationStackController.setShareHandler(handler)

    fun setPreviewFileHandler(handler: ((String) -> Unit)?) =
        navigationStackController.setPreviewFileHandler(handler)

    fun setShareFileHandler(handler: ((String) -> Unit)?) =
        navigationStackController.setShareFileHandler(handler)

    /**
     * Sets the handler that switches the visible section (tab) when a deep link targets a
     * screen in another section. Scope keys: "vault" / "send" / "generator" / "watchtower" /
     * "settings". Set by the shell.
     */
    fun setSelectScopeHandler(handler: ((String) -> Unit)?) =
        navigationStackController.setSelectScopeHandler(handler)

    /**
     * Sets the handler that presents the native create-item sheet for a
     * producer-emitted `AddRoute` (the generator's "create login / SSH key"),
     * with the generated value flattened to (type, name, username, password).
     */
    fun setAddCipherHandler(handler: ((String, String?, String?, String?) -> Unit)?) =
        navigationStackController.setAddCipherHandler(handler)

    /**
     * Sets the handler that presents the native add-account flow for a
     * producer-emitted login route; the argument is the `AccountType` name
     * ("BITWARDEN" / "KEEPASS").
     */
    fun setAddAccountHandler(handler: ((String) -> Unit)?) =
        navigationStackController.setAddAccountHandler(handler)

    /**
     * Sets the handler that reveals a local file natively (the KeePass account
     * detail's "open local vault" action); the argument is the file uri.
     */
    fun setRevealFileHandler(handler: ((String) -> Unit)?) =
        navigationStackController.setRevealFileHandler(handler)

    // ---------------------------------------------------------------------------
    // SSH agent. Spawns the reused Rust binary (NSTask), serves list/sign over a
    // POSIX IPC socket, and gates each signature through a per-request approval
    // surfaced to Swift. Signing runs through the shared native (Rust) SSH engine.
    // The on/off state is the shared persisted "ssh_agent" preference; the
    // start/stop side effect lives in [startSshAgentApplier].
    // ---------------------------------------------------------------------------

    private val sshAgentController by lazy { SshAgentController(context) }

    fun startSshAgentApplier() = sshAgentController.startSshAgentApplier()

    fun setSshAgentEnabled(value: Boolean) = sshAgentController.setSshAgentEnabled(value)

    fun observeSshAgentRequests(
        onChange: (List<SshAgentRequestSnapshot>) -> Unit,
    ): KeyguardCancellable = sshAgentController.observeSshAgentRequests(onChange)

    fun resolveSshAgentRequest(id: String, approved: Boolean) = sshAgentController.resolveSshAgentRequest(id, approved)

    fun observeSshAgentStatus(
        onChange: (SshAgentStatusSnapshot) -> Unit,
    ): KeyguardCancellable = sshAgentController.observeSshAgentStatus(onChange)

    // GPG is also started explicitly by the main app; AutoFill remains inert.
    private val gpgAgentController by lazy { GpgAgentController(context) }

    fun startGpgAgentApplier() = gpgAgentController.startGpgAgentApplier()

    fun setGpgAgentEnabled(value: Boolean) = gpgAgentController.setGpgAgentEnabled(value)

    fun retryGpgAgent() = gpgAgentController.retryGpgAgent()

    fun observeGpgAgentRequests(
        onChange: (List<GpgAgentRequestSnapshot>) -> Unit,
    ): KeyguardCancellable = gpgAgentController.observeGpgAgentRequests(onChange)

    fun resolveGpgAgentRequest(id: String, approved: Boolean) = gpgAgentController.resolveGpgAgentRequest(id, approved)

    fun observeGpgAgentStatus(
        onChange: (GpgAgentStatusSnapshot) -> Unit,
    ): KeyguardCancellable = gpgAgentController.observeGpgAgentStatus(onChange)

    fun observeGpgAgentSettings(
        onChange: (GpgAgentSettingsSnapshot) -> Unit,
    ): KeyguardCancellable = gpgAgentController.observeGpgAgentSettings(onChange)

    fun setGpgAgentApprovalWindow(optionId: String) = gpgAgentController.setGpgAgentApprovalWindow(optionId)

    fun setGpgAgentApprovalCachePolicy(optionId: String) = gpgAgentController.setGpgAgentApprovalCachePolicy(optionId)

    fun setGpgAgentDisplayKeyNames(value: Boolean) = gpgAgentController.setGpgAgentDisplayKeyNames(value)

    fun observeGpgAgentFilters(
        onChange: (GpgAgentFiltersSnapshot) -> Unit,
        onClose: () -> Unit,
    ): KeyguardCancellable = gpgAgentController.observeGpgAgentFilters(onChange, onClose)

    fun invokeGpgAgentFilter(id: String) = gpgAgentController.invokeGpgAgentFilter(id)

    fun saveGpgAgentFilters() = gpgAgentController.saveGpgAgentFilters()

    fun resetGpgAgentFilters() = gpgAgentController.resetGpgAgentFilters()

    fun observeGpgAgentHistory(
        onChange: (GpgAgentHistorySnapshot) -> Unit,
    ): KeyguardCancellable = gpgAgentController.observeGpgAgentHistory(onChange)

    fun clearGpgAgentHistory() = gpgAgentController.clearGpgAgentHistory()

    // ---------------------------------------------------------------------------
    // AutoFill (credential provider). The app populates the QuickType index
    // (ASCredentialIdentityStore) from these; the appex resolves a selected
    // credential. Reuses the unlocked-session ciphers (login + uris).
    // ---------------------------------------------------------------------------

    private val autofillController by lazy { AutofillController(context) }

    fun observeAutofillChanges(onChange: (Boolean) -> Unit, onFailure: () -> Unit): KeyguardCancellable =
        autofillController.observeChanges(onChange, onFailure)

    @Throws(Exception::class)
    suspend fun loadAutofillIndex(): com.artemchep.keyguard.apple.lists.AutofillIndexSnapshot? =
        autofillController.loadIndex()

    @Throws(Exception::class)
    suspend fun loadAutofillIdentities(): List<AutofillIdentitySnapshot> =
        autofillController.loadAutofillIdentities()

    @Throws(Exception::class)
    suspend fun loadAutofillCredential(recordId: String): AutofillCredentialSnapshot? =
        autofillController.loadAutofillCredential(recordId)

    @Throws(Exception::class)
    suspend fun loadAutofillSuggestions(
        serviceIdentifiers: List<String>,
    ): List<AutofillSuggestionSnapshot> =
        autofillController.loadAutofillSuggestions(serviceIdentifiers)

    @Throws(Exception::class)
    suspend fun loadOneTimeCodeIdentities(): List<AutofillIdentitySnapshot> =
        autofillController.loadOneTimeCodeIdentities()

    @Throws(Exception::class)
    suspend fun loadOneTimeCodeSuggestions(
        serviceIdentifiers: List<String>,
    ): List<AutofillSuggestionSnapshot> =
        autofillController.loadOneTimeCodeSuggestions(serviceIdentifiers)

    @Throws(Exception::class)
    suspend fun loadOneTimeCode(recordId: String): String? =
        autofillController.loadOneTimeCode(recordId)

    @Throws(Exception::class)
    suspend fun loadPasskeyRegistrationSuggestions(serviceIdentifiers: List<String>): List<AutofillSuggestionSnapshot> =
        autofillController.loadPasskeyRegistrationSuggestions(serviceIdentifiers)

    private val passkeyController by lazy { PasskeyController(context) }

    @Throws(Exception::class)
    suspend fun loadPasskeyIdentities(): List<PasskeyIdentitySnapshot> =
        passkeyController.loadPasskeyIdentities()

    @Throws(Exception::class)
    suspend fun loadMatchingPasskeyIdentities(
        rpId: String,
        allowedCredentialIds: List<ByteArray>,
    ): List<PasskeyIdentitySnapshot> =
        passkeyController.loadMatchingPasskeyIdentities(rpId, allowedCredentialIds)

    @Throws(Exception::class)
    suspend fun hasExcludedPasskeyCredential(rpId: String, credentialIds: List<ByteArray>): Boolean =
        passkeyController.hasExcludedPasskeyCredential(rpId, credentialIds)

    @Throws(Exception::class)
    suspend fun assertPasskey(
        recordId: String,
        clientDataHash: ByteArray,
        expectedRpId: String,
        expectedCredentialId: ByteArray,
        allowedCredentialIds: List<ByteArray>,
        userVerification: String?,
        userVerified: Boolean,
    ): PasskeyAssertionSnapshot? =
        passkeyController.assertPasskey(
            recordId = recordId,
            clientDataHash = clientDataHash,
            expectedRpId = expectedRpId,
            expectedCredentialId = expectedCredentialId,
            allowedCredentialIds = allowedCredentialIds,
            userVerification = userVerification,
            userVerified = userVerified,
        )

    @Throws(Exception::class)
    suspend fun createPasskey(
        cipherRecordId: String,
        rpId: String,
        rpName: String?,
        userName: String?,
        userDisplayName: String?,
        userHandle: ByteArray,
        userVerification: String?,
        userVerified: Boolean,
    ): PasskeyRegistrationSnapshot? =
        passkeyController.createPasskey(
            cipherRecordId = cipherRecordId,
            rpId = rpId,
            rpName = rpName,
            userName = userName,
            userDisplayName = userDisplayName,
            userHandle = userHandle,
            userVerification = userVerification,
            userVerified = userVerified,
        )

    private val fido2PromptHost by lazy { com.artemchep.keyguard.apple.auth.Fido2PromptController(context) }
    private val authController by lazy { AuthController(context, authPromptHost, fido2PromptHost) }

    suspend fun createVault(password: String, biometric: Boolean) =
        authController.createVault(password, biometric)

    suspend fun createVaultSupportsBiometric(): Boolean =
        authController.createVaultSupportsBiometric()

    suspend fun unlockVault(password: String) = authController.unlockVault(password)

    private val recentsController by lazy { RecentsController(context) }

    /**
     * The Recents item rows as state-anchored [VaultListDelta]s — the SAME
     * background-delivered contract as [makeVaultListSession]'s
     * `observeListDelta`. See [RecentsController.observeRecentsListDelta].
     */
    fun observeRecentsListDelta(
        onChange: (com.artemchep.keyguard.apple.vault.VaultListDelta) -> Unit,
    ): KeyguardCancellable = recentsController.observeRecentsListDelta(onChange)

    /** The Recents tab bar + current selection (on Main). */
    fun observeRecentsTabs(
        onChange: (RecentsTabsSnapshot) -> Unit,
    ): KeyguardCancellable = recentsController.observeRecentsTabs(onChange)

    fun observeRecentsTotp(
        onChange: (Map<String, TotpFieldSnapshot>) -> Unit,
    ): KeyguardCancellable = recentsController.observeRecentsTotp(onChange)

    fun setRecentsTab(key: String) = recentsController.setRecentsTab(key)

    fun observeUnlock(
        onChange: (UnlockSnapshot) -> Unit,
    ): KeyguardCancellable = authController.observeUnlock(onChange)

    fun setUnlockScreenVisible(visible: Boolean) = authController.setUnlockScreenVisible(visible)

    fun setUnlockPassword(text: String) = authController.setUnlockPassword(text)

    fun submitUnlock() = authController.submitUnlock()

    fun triggerUnlockBiometric() = authController.triggerUnlockBiometric()

    fun invokeUnlockAction(id: String) = authController.invokeUnlockAction(id)

    fun observeFido2Prompt(
        onChange: (com.artemchep.keyguard.apple.auth.Fido2PromptPhase) -> Unit,
    ): KeyguardCancellable = fido2PromptHost.observe(onChange)
    fun submitFido2Pin(pin: String) = fido2PromptHost.submitPin(pin)
    fun cancelFido2Prompt() = fido2PromptHost.cancel()
    fun triggerUnlockFido2() = authController.triggerUnlockFido2()
    fun setFido2Unlock(value: Boolean) = authController.setFido2Unlock(value)

    fun triggerUnlockYubiKey() = authController.triggerUnlockYubiKey()

    fun inspectYubiKeySlot(slot: Int, onResult: (Boolean?) -> Unit) {
        context.scope.launch {
            val result = runCatching { authPromptHost.inspectYubiKeySlot(slot) }.getOrNull()
            context.publishOnMain { onResult(result) }
        }
    }

    fun setYubiKeyUnlock(value: Boolean, slot: Int, provision: Boolean, overwrite: Boolean) =
        authController.setYubiKeyUnlock(value, slot, provision, overwrite)

    fun observeSetup(
        onChange: (SetupSnapshot) -> Unit,
    ): KeyguardCancellable = authController.observeSetup(onChange)

    fun setSetupScreenVisible(visible: Boolean) = authController.setSetupScreenVisible(visible)

    fun setSetupPassword(text: String) = authController.setSetupPassword(text)

    fun setSetupCrashlytics(enabled: Boolean) = authController.setSetupCrashlytics(enabled)

    fun setSetupBiometric(enabled: Boolean) = authController.setSetupBiometric(enabled)

    fun submitSetup() = authController.submitSetup()

    private val messagesController by lazy { MessagesController(context) }

    fun observeMessages(
        onMessage: (MessageSnapshot) -> Unit,
    ): KeyguardCancellable = messagesController.observeMessages(onMessage)

    private val loginController by lazy { LoginController(context) }

    fun observeBitwardenLogin(
        onChange: (LoginSnapshot) -> Unit,
        onSuccess: () -> Unit,
        onTwofaRequired: () -> Unit,
    ): KeyguardCancellable = loginController.observeBitwardenLogin(onChange, onSuccess, onTwofaRequired)

    fun setLoginField(id: String, text: String) = loginController.setLoginField(id, text)

    fun selectLoginRegion(key: String) = loginController.selectLoginRegion(key)

    fun invokeLoginAction(id: String) = loginController.invokeLoginAction(id)

    fun clickLoginRegister() = loginController.clickLoginRegister()

    fun submitLogin() = loginController.submitLogin()

    fun observeBitwardenLoginTwofa(
        onChange: (TwofaSnapshot) -> Unit,
        onSuccess: () -> Unit,
    ): KeyguardCancellable = loginController.observeBitwardenLoginTwofa(onChange, onSuccess)

    fun setTwofaCode(text: String) = loginController.setTwofaCode(text)

    fun selectTwofaProvider(key: String) = loginController.selectTwofaProvider(key)

    fun toggleTwofaRememberMe(checked: Boolean) = loginController.toggleTwofaRememberMe(checked)

    fun resendTwofaCode() = loginController.resendTwofaCode()

    fun submitTwofa() = loginController.submitTwofa()

    fun submitTwofaYubiKey(token: String) = loginController.submitTwofaYubiKey(token)

    private val keePassLoginController by lazy { KeePassLoginController(context) }

    /**
     * Observes the add-KeePass-account form. [onWebDavChange] surfaces the WebDAV
     * server sub-form: non-null means present the sheet with that state, null
     * means dismiss it.
     */
    fun observeKeePassLogin(
        onChange: (KeePassLoginSnapshot) -> Unit,
        onSuccess: () -> Unit,
        onWebDavChange: (WebDavSettingsSnapshot?) -> Unit,
    ): KeyguardCancellable = keePassLoginController.observeKeePassLogin(onChange, onSuccess, onWebDavChange)

    fun selectKeePassTab(key: String) = keePassLoginController.selectKeePassTab(key)

    fun selectKeePassLocation(key: String) = keePassLoginController.selectKeePassLocation(key)

    fun pickKeePassDbFile() = keePassLoginController.pickKeePassDbFile()

    fun clearKeePassDbFile() = keePassLoginController.clearKeePassDbFile()

    fun pickKeePassKeyFile() = keePassLoginController.pickKeePassKeyFile()

    fun clearKeePassKeyFile() = keePassLoginController.clearKeePassKeyFile()

    fun setKeePassPassword(text: String) = keePassLoginController.setKeePassPassword(text)

    fun submitKeePassLogin() = keePassLoginController.submitKeePassLogin()

    fun setKeePassFilePickerRequestHandler(handler: ((KeePassFilePickerRequest) -> Unit)?) =
        keePassLoginController.setKeePassFilePickerRequestHandler(handler)

    fun resolveKeePassFilePicker(requestId: String, uri: String, name: String?, size: Long, accessToken: String?) =
        keePassLoginController.resolveKeePassFilePicker(requestId, uri, name, size, accessToken)

    fun cancelKeePassFilePicker(requestId: String) = keePassLoginController.cancelKeePassFilePicker(requestId)

    fun setWebDavField(id: String, text: String) = keePassLoginController.setWebDavField(id, text)

    fun submitWebDavSettings() = keePassLoginController.submitWebDavSettings()

    fun testWebDavConnection() = keePassLoginController.testWebDavConnection()

    fun cancelWebDavSettings() = keePassLoginController.cancelWebDavSettings()

    private val cipherDetailController by lazy { CipherDetailController(context, dialogController) }

    /**
     * Observes the root detail pane. The live TOTP badges of the shown cipher arrive
     * on [onTotpChange] instead of [onChange], so the countdown does not rebuild the
     * whole detail every second.
     */
    fun observeCipherDetail(
        onChange: (VaultDetailSnapshot) -> Unit,
        onTotpChange: (VaultDetailTotpSnapshot?) -> Unit = {},
    ): KeyguardCancellable = cipherDetailController.observeCipherDetail(onChange, onTotpChange)

    fun setDetailTarget(itemId: String?, accountId: String?) =
        cipherDetailController.setDetailTarget(itemId, accountId)

    fun invokeVaultAction(id: String) = cipherDetailController.invokeVaultAction(id)

    fun toggleVaultFavorite() = cipherDetailController.toggleVaultFavorite()

    // ---------------------------------------------------------------------------
    // Navigation stack. A stack of screen instances layered ABOVE the selection-
    // driven root detail; pushes are driven by the shared producers' full-screen
    // navigation intents (a folder chip in a detail, opening an item in a pushed
    // list) via [NavigationStackController.interceptor]. Lets multiple screens of
    // the same type (e.g. the root vault list + a folder-filtered vault list) be
    // alive at once. SwiftUI mirrors the stack with a NavigationStack(path:).
    // ---------------------------------------------------------------------------

    /** Starts the whole-stack session gate for the unlocked lifetime (shell-owned). */
    fun startNavStackSession(): KeyguardCancellable = navigationStackController.startSession()

    /**
     * Opens a `keyguard://` deep link (call from the SwiftUI `onOpenURL` / AppKit URL
     * handler). Routes it to the native navigation stack; applied after unlock if the
     * vault is still locked. Unrecognized URLs are ignored.
     */
    fun handleDeepLink(url: String) {
        navigationStackController.openDeepLink(url)
    }

    /** Sets the scope (section / tab) that subsequent producer pushes target. */
    fun setNavScope(scope: String) = navigationStackController.setScope(scope)

    /** Observes the navigation stack of one [scope]; each section observes its own. */
    fun observeNavStack(
        scope: String,
        onChange: (List<ScreenEntrySnapshot>) -> Unit,
    ): KeyguardCancellable = navigationStackController.observeNavStack(scope, onChange)

    /** Observes the live TOTP badges of the stacked cipher details, keyed by cipher id. */
    fun observeNavStackTotp(
        onChange: (Map<String, VaultDetailTotpSnapshot>) -> Unit,
    ): KeyguardCancellable = navigationStackController.observeNavStackTotp(onChange)

    fun popScreen(scope: String) = navigationStackController.popScreen(scope)

    fun popToScreen(instanceId: Long) = navigationStackController.popToScreen(instanceId)

    fun clearNavScope(scope: String) = navigationStackController.clearScope(scope)

    fun invokeEntryAction(instanceId: Long, actionId: String) =
        navigationStackController.invokeEntryAction(instanceId, actionId)

    fun toggleEntryFavorite(instanceId: Long) =
        navigationStackController.toggleEntryFavorite(instanceId)

    fun setEntryListQuery(instanceId: Long, text: String) =
        navigationStackController.setEntryListQuery(instanceId, text)

    fun retryEntryList(instanceId: Long) = navigationStackController.retryEntryList(instanceId)

    fun openEntryListItem(instanceId: Long, itemId: String) =
        navigationStackController.openEntryListItem(instanceId, itemId)

    fun toggleEntryListSelection(instanceId: Long, itemId: String) =
        navigationStackController.toggleEntryListSelection(instanceId, itemId)

    fun clearEntryListSelection(instanceId: Long) = navigationStackController.clearEntryListSelection(instanceId)

    fun selectAllEntryListItems(instanceId: Long) = navigationStackController.selectAllEntryListItems(instanceId)

    fun requestEntryWordlistAction(
        instanceId: Long,
        actionId: String,
        itemId: String?,
        onResult: (WordlistActionRequestSnapshot?) -> Unit,
    ) = navigationStackController.requestEntryWordlistAction(instanceId, actionId, itemId, onResult)

    fun requestEntryEmailRelayAction(
        instanceId: Long,
        actionId: String,
        itemId: String?,
        onResult: (EmailRelayActionRequestSnapshot?) -> Unit,
    ) = navigationStackController.requestEntryEmailRelayAction(instanceId, actionId, itemId, onResult)

    // ---------------------------------------------------------------------------
    // SwiftUI-presented dialogs (Large Type, Barcode, passkey credential,
    // attachment preview). The channel state, headless producers and snapshot
    // projection all live in [DialogController]; the methods below delegate.
    // ---------------------------------------------------------------------------

    fun observePasswordMemory(
        onChange: (PasswordMemorySnapshot?) -> Unit,
    ): KeyguardCancellable = dialogController.observePasswordMemory(
        onChange,
    )
    fun setPasswordMemoryText(text: String) = dialogController.setPasswordMemoryText(text)
    fun verifyPasswordMemory() = dialogController.verifyPasswordMemory()
    fun closePasswordMemory() = dialogController.closePasswordMemory()

    fun observeLargeType(
        onChange: (LargeTypeSnapshot?) -> Unit,
    ): KeyguardCancellable = dialogController.observeLargeType(onChange)

    fun selectLargeTypeSymbol(index: Int) = dialogController.selectLargeTypeSymbol(index)

    fun closeLargeType() = dialogController.closeLargeType()

    fun observeBarcode(
        onChange: (BarcodeSnapshot?) -> Unit,
    ): KeyguardCancellable = dialogController.observeBarcode(onChange)

    fun selectBarcodeFormat(id: String) = dialogController.selectBarcodeFormat(id)

    fun closeBarcode() = dialogController.closeBarcode()

    fun observePasskeyCredential(
        onChange: (PasskeyCredentialSnapshot?) -> Unit,
    ): KeyguardCancellable = dialogController.observePasskeyCredential(onChange)

    fun usePasskeyCredential() = dialogController.usePasskeyCredential()

    fun closePasskeyCredential() = dialogController.closePasskeyCredential()

    fun observeAttachmentPreview(
        onChange: (AttachmentPreviewSnapshot?) -> Unit,
    ): KeyguardCancellable = dialogController.observeAttachmentPreview(onChange)

    fun setInterfaceDarkMode(isDark: Boolean) = dialogController.setInterfaceDarkMode(isDark)

    fun invokeAttachmentPreviewCopy() = dialogController.invokeAttachmentPreviewCopy()

    fun closeAttachmentPreview() = dialogController.closeAttachmentPreview()

    // The generic confirmation dialog (rename, change password, trash / delete,
    // "Configure Watchtower alerts", pickers). Driven headless by the shared
    // confirmationStateProducer; its file-picker bridge reuses the create-form
    // AddFilePickerRequest type + Swift NSOpenPanel / .fileImporter presentation.

    fun observeConfirmation(
        onChange: (ConfirmationSnapshot?) -> Unit,
    ): KeyguardCancellable = dialogController.observeConfirmation(onChange)

    fun setConfirmationItemBoolean(key: String, value: Boolean) =
        dialogController.setConfirmationItemBoolean(key, value)

    fun setConfirmationItemString(key: String, text: String) =
        dialogController.setConfirmationItemString(key, text)

    fun selectConfirmationItemEnum(key: String, optionKey: String) =
        dialogController.selectConfirmationItemEnum(key, optionKey)

    fun addConfirmationItem() = dialogController.addConfirmationItem()

    fun removeConfirmationItem(key: String) = dialogController.removeConfirmationItem(key)

    fun selectConfirmationItemFile(key: String) =
        dialogController.selectConfirmationItemFile(key)

    fun clearConfirmationItemFile(key: String) =
        dialogController.clearConfirmationItemFile(key)

    /** Opens the "learn more" link of the selected option of a confirmation choice row. */
    fun openConfirmationItemDoc(key: String) = dialogController.openConfirmationItemDoc(key)

    fun confirmConfirmation() = dialogController.confirmConfirmation()

    fun denyConfirmation() = dialogController.denyConfirmation()

    fun closeConfirmation() = dialogController.closeConfirmation()

    fun setConfirmationFilePickerRequestHandler(handler: ((AddFilePickerRequest) -> Unit)?) =
        dialogController.setConfirmationFilePickerRequestHandler(handler)

    fun resolveConfirmationFilePicker(requestId: String, uri: String, name: String?, size: Long) =
        dialogController.resolveConfirmationFilePicker(requestId, uri, name, size)

    fun cancelConfirmationFilePicker(requestId: String) =
        dialogController.cancelConfirmationFilePicker(requestId)

    // The master-password re-prompt ("elevated access") dialog: a reprompt-protected
    // cipher's copy / reveal / edit action runs the shared elevatedAccessStateProducer
    // headlessly; password verification + Touch ID / Face ID / YubiKey go through the
    // shared AuthPromptHost.

    fun observeElevatedAccess(
        onChange: (ElevatedAccessSnapshot?) -> Unit,
    ): KeyguardCancellable = dialogController.observeElevatedAccess(onChange)

    fun setElevatedAccessPassword(text: String) =
        dialogController.setElevatedAccessPassword(text)

    fun triggerElevatedAccessBiometric() = dialogController.triggerElevatedAccessBiometric()

    fun triggerElevatedAccessYubiKey() = dialogController.triggerElevatedAccessYubiKey()

    fun confirmElevatedAccess() = dialogController.confirmElevatedAccess()

    fun denyElevatedAccess() = dialogController.denyElevatedAccess()

    fun closeElevatedAccess() = dialogController.closeElevatedAccess()

    // The service-info dialog (the cipher detail's "Inactive one-time password" /
    // "Inactive passkey" rows): a static snapshot describing the matched service's
    // 2FA / passkey support, presented as a sheet.

    fun observeServiceInfo(
        onChange: (ServiceDirectoryDetailSnapshot?) -> Unit,
    ): KeyguardCancellable = dialogController.observeServiceInfo(onChange)

    fun closeServiceInfo() = dialogController.closeServiceInfo()

    // The HIBP breach dialogs (a cipher / account field's "Check data breaches"
    // action): each runs its shared leak producer headlessly via the dialog
    // navigation interceptor. The email / website dialogs list per-breach details;
    // the password dialog shows an occurrence count.

    fun observeEmailLeak(
        onChange: (EmailLeakSnapshot?) -> Unit,
    ): KeyguardCancellable = dialogController.observeEmailLeak(onChange)

    fun closeEmailLeak() = dialogController.closeEmailLeak()

    fun observePasswordLeak(
        onChange: (PasswordLeakSnapshot?) -> Unit,
    ): KeyguardCancellable = dialogController.observePasswordLeak(onChange)

    fun closePasswordLeak() = dialogController.closePasswordLeak()

    fun observeWebsiteLeak(
        onChange: (WebsiteLeakSnapshot?) -> Unit,
    ): KeyguardCancellable = dialogController.observeWebsiteLeak(onChange)

    fun closeWebsiteLeak() = dialogController.closeWebsiteLeak()

    // The color picker dialog (the account detail's "Change color" action): runs the
    // shared colorPickerStateProducer headlessly via the dialog navigation interceptor;
    // selecting a swatch + confirming persists the account's accent color.

    fun observeColorPicker(
        onChange: (ColorPickerSnapshot?) -> Unit,
    ): KeyguardCancellable = dialogController.observeColorPicker(onChange)

    fun selectColorPickerSwatch(id: String) = dialogController.selectColorPickerSwatch(id)

    fun confirmColorPicker() = dialogController.confirmColorPicker()

    fun denyColorPicker() = dialogController.denyColorPicker()

    fun closeColorPicker() = dialogController.closeColorPicker()

    // The collection / organization read-only "info" dialog (a grouping row's "Info"
    // action): runs the shared collection / organization screen-state producer headlessly
    // via the dialog navigation interceptor, projecting the capability flags.

    fun observeInfoDialog(
        onChange: (InfoDialogSnapshot?) -> Unit,
    ): KeyguardCancellable = dialogController.observeInfoDialog(onChange)

    fun closeInfoDialog() = dialogController.closeInfoDialog()

    // The add / Send create form ownership "Save to" account picker: runs the shared
    // organization-confirmation producer headlessly via the dialog navigation
    // interceptor, projecting the selectable account (+ org / collection / folder)
    // sections.

    fun observeCipherLinkPicker(
        onChange: (com.artemchep.keyguard.apple.dialog.CipherLinkPickerSnapshot?) -> Unit,
    ): KeyguardCancellable = dialogController.observeCipherLinkPicker(onChange)

    fun setCipherLinkPickerQuery(text: String) = dialogController.setCipherLinkPickerQuery(text)

    fun selectCipherLinkPickerItem(id: String) = dialogController.selectCipherLinkPickerItem(id)

    fun closeCipherLinkPicker() = dialogController.closeCipherLinkPicker()

    fun observeAccountPicker(
        onChange: (AccountPickerSnapshot?) -> Unit,
    ): KeyguardCancellable = dialogController.observeAccountPicker(onChange)

    fun setAccountPickerNewFolderName(text: String) = dialogController.setAccountPickerNewFolderName(text)

    fun selectAccountPickerItem(key: String) = dialogController.selectAccountPickerItem(key)

    fun confirmAccountPicker() = dialogController.confirmAccountPicker()

    fun denyAccountPicker() = dialogController.denyAccountPicker()

    fun closeAccountPicker() = dialogController.closeAccountPicker()

    // ---------------------------------------------------------------------------
    // Send list / detail. Mirrors the vault list / detail bridge above, reusing
    // the shared sendListScreenStateProducer / sendViewScreenStateProducer and the
    // shared VaultItemSnapshot mapping (Send items are the same VaultViewItem type).
    // ---------------------------------------------------------------------------

    private val sendListController by lazy { SendListController(context) }
    private val sendDetailController by lazy { SendDetailController(context, dialogController) }

    fun observeSendList(
        onChange: (SendListSnapshot) -> Unit,
    ): KeyguardCancellable = sendListController.observeSendList(onChange)

    fun setSendListQuery(text: String) = sendListController.setSendListQuery(text)

    fun invokeSendListFilter(id: String) = sendListController.invokeSendListFilter(id)

    fun invokeSendListSort(id: String) = sendListController.invokeSendListSort(id)

    fun clearSendListFilters() = sendListController.clearSendListFilters()

    fun clearSendListSort() = sendListController.clearSendListSort()

    fun toggleSendListSelection(itemId: String) = sendListController.toggleSendListSelection(itemId)

    fun invokeSendListSelectionAction(id: String) = sendListController.invokeSendListSelectionAction(id)

    fun invokeSendListAction(id: String) = sendListController.invokeSendListAction(id)

    fun clearSendListSelection() = sendListController.clearSendListSelection()

    fun dropFileOnSendList(uri: String, name: String?, size: Long) =
        sendListController.dropFileOnSendList(uri, name, size)

    fun observeSendDetail(
        itemId: String,
        accountId: String,
        onChange: (SendDetailSnapshot) -> Unit,
    ): KeyguardCancellable = sendDetailController.observeSendDetail(itemId, accountId, onChange)

    fun invokeSendAction(id: String) = sendDetailController.invokeSendAction(id)

    fun sendCopy() = sendDetailController.sendCopy()

    fun sendShare() = sendDetailController.sendShare()

    fun sendEdit() = sendDetailController.sendEdit()

    fun observeGenerator(
        onChange: (GeneratorSnapshot) -> Unit,
    ): KeyguardCancellable = generatorController.observeGenerator(onChange = onChange)

    fun invokeGeneratorAction(id: String) = generatorController.invokeGeneratorAction(id)

    fun setGeneratorSwitch(key: String, value: Boolean) =
        generatorController.setGeneratorSwitch(key, value)

    fun setGeneratorText(key: String, text: String) =
        generatorController.setGeneratorText(key, text)

    fun setGeneratorCounter(key: String, value: Int) =
        generatorController.setGeneratorCounter(key, value)

    fun setGeneratorLength(value: Int) = generatorController.setGeneratorLength(value)

    // ----------------------------------------------------------------------
    // GPG Tools (encrypt / decrypt / sign / verify). One producer instance per
    // operation string; the run outcome arrives on [onResult] (the shared
    // producer delivers it through a result-dialog navigation intent the
    // controller intercepts). Mutate through the typed setters below.
    // ----------------------------------------------------------------------

    fun observeGpgTools(
        operation: String,
        onChange: (GpgToolsSnapshot) -> Unit,
        onResult: (GpgToolsResultSnapshot) -> Unit,
        onFilePicker: (GpgToolsFilePickerRequest?) -> Unit,
        onPublicKey: (GpgToolsPublicKeyRequest?) -> Unit,
    ): KeyguardCancellable = gpgToolsController.observeGpgTools(
        operation,
        onChange,
        onResult,
        onFilePicker,
        onPublicKey,
    )

    fun stopGpgTools() = gpgToolsController.stopGpgTools()

    fun resolveGpgToolsFilePicker(id: String, name: String?, size: Long) =
        gpgToolsController.resolveGpgToolsFilePicker(id, name, size)

    fun addGpgToolsPublicKey() = gpgToolsController.addGpgToolsPublicKey()

    fun removeGpgToolsPublicKey(id: String) = gpgToolsController.removeGpgToolsPublicKey(id)

    fun validateGpgToolsPublicKey(id: String, text: String, onResult: (GpgToolsPublicKeyValidationSnapshot) -> Unit) =
        gpgToolsController.validateGpgToolsPublicKey(id, text, onResult)

    fun finishGpgToolsPublicKey(id: String, confirm: Boolean) =
        gpgToolsController.finishGpgToolsPublicKey(id, confirm)

    fun prepareGpgToolsExport(resultId: String, onResult: (GpgToolsExportSnapshot?) -> Unit) =
        gpgToolsController.prepareGpgToolsExport(resultId, onResult)

    fun finishGpgToolsExport(id: String) = gpgToolsController.finishGpgToolsExport(id)

    fun dismissGpgToolsResult() = gpgToolsController.dismissGpgToolsResult()

    fun setGpgToolsScope(key: String) = gpgToolsController.setGpgToolsScope(key)

    fun setGpgToolsSignMode(key: String) = gpgToolsController.setGpgToolsSignMode(key)

    fun setGpgToolsVerifyMode(key: String) = gpgToolsController.setGpgToolsVerifyMode(key)

    fun setGpgToolsArmor(value: Boolean) = gpgToolsController.setGpgToolsArmor(value)

    fun setGpgToolsInputText(text: String) = gpgToolsController.setGpgToolsInputText(text)

    fun setGpgToolsSignatureText(text: String) = gpgToolsController.setGpgToolsSignatureText(text)

    fun selectGpgToolsPrivateKey(id: String) = gpgToolsController.selectGpgToolsPrivateKey(id)

    fun selectGpgToolsEncryptSigningKey(id: String?) =
        gpgToolsController.selectGpgToolsEncryptSigningKey(id)

    fun toggleGpgToolsRecipient(id: String) = gpgToolsController.toggleGpgToolsRecipient(id)

    fun selectGpgToolsInputFile() = gpgToolsController.selectGpgToolsInputFile()

    fun clearGpgToolsInputFile() = gpgToolsController.clearGpgToolsInputFile()

    fun selectGpgToolsSignatureFile() = gpgToolsController.selectGpgToolsSignatureFile()

    fun clearGpgToolsSignatureFile() = gpgToolsController.clearGpgToolsSignatureFile()

    fun runGpgTools() = gpgToolsController.runGpgTools()

    fun invokeGpgToolsResultCopy() = gpgToolsController.invokeGpgToolsResultCopy()

    fun invokeGpgToolsResultSave() = gpgToolsController.invokeGpgToolsResultSave()

    // ----------------------------------------------------------------------
    // In-form Autofill / generate (the add / edit item form's username /
    // password generate button). A separate generator instance, parameterised
    // with the field kind + the cipher's URI context, projecting the same
    // GeneratorSnapshot. The chosen value is written back through
    // [setAddFieldText] (the revision-bumping onSetText path), NOT setAddField.
    // ----------------------------------------------------------------------

    fun observeAddKeyGenerator(
        itemId: String,
        sessionId: String,
        onChange: (AddKeyGeneratorSnapshot) -> Unit,
    ): KeyguardCancellable = addItemController.observeKeyGenerator(itemId, sessionId, onChange)

    fun invokeAddKeyGeneratorAction(sessionId: String, id: String) =
        addItemController.invokeKeyGeneratorAction(sessionId, id)

    fun setAddKeyGeneratorText(sessionId: String, key: String, text: String) =
        addItemController.setKeyGeneratorText(sessionId, key, text)

    fun setAddKeyGeneratorSwitch(sessionId: String, key: String, value: Boolean) =
        addItemController.setKeyGeneratorSwitch(sessionId, key, value)

    fun setAddKeyGeneratorCounter(sessionId: String, key: String, value: Int) =
        addItemController.setKeyGeneratorCounter(sessionId, key, value)

    fun useAddGeneratedKey(sessionId: String): Boolean = addItemController.useGeneratedKey(sessionId)

    fun observeAutofillGenerator(
        username: Boolean,
        password: Boolean,
        uris: List<String>,
        onChange: (GeneratorSnapshot) -> Unit,
    ): KeyguardCancellable {
        val k = when {
            password -> "password"
            username -> "username"
            else -> "generator"
        }
        return autofillGeneratorController.observeGenerator(
            args = GeneratorRoute.Args(
                context = GeneratorRoute.Args.Context(uris = uris.toTypedArray()),
                username = username,
                password = password,
            ),
            scopeName = "autofill_$k",
            producerKey = k,
            onChange = onChange,
        )
    }

    fun invokeAutofillGeneratorAction(id: String) =
        autofillGeneratorController.invokeGeneratorAction(id)

    fun setAutofillGeneratorSwitch(key: String, value: Boolean) =
        autofillGeneratorController.setGeneratorSwitch(key, value)

    fun setAutofillGeneratorText(key: String, text: String) =
        autofillGeneratorController.setGeneratorText(key, text)

    fun setAutofillGeneratorCounter(key: String, value: Int) =
        autofillGeneratorController.setGeneratorCounter(key, value)

    fun setAutofillGeneratorLength(value: Int) =
        autofillGeneratorController.setGeneratorLength(value)

    /**
     * Writes the in-form generator's chosen value into the add-form field [id]
     * through its revision-bumping `onSetText` sink, so the SwiftUI buffer adopts
     * it (distinct from [setAddField], which uses `onChange` and would be ignored).
     */
    fun setAddFieldText(id: String, text: String) = addItemController.setAddFieldText(id, text)

    // ----------------------------------------------------------------------
    // Add (create) form — cipher & Send
    // ----------------------------------------------------------------------

    private val addItemController by lazy { AddItemController(context) }

    fun observeAddCipher(
        type: String,
        name: String? = null,
        username: String? = null,
        password: String? = null,
        onClose: () -> Unit = {},
        onChange: (AddItemFormSnapshot) -> Unit,
    ): KeyguardCancellable =
        addItemController.observeAddCipher(type, name, username, password, onClose, onChange)

    fun observeAddSend(
        type: String,
        onClose: () -> Unit = {},
        onChange: (AddItemFormSnapshot) -> Unit,
    ): KeyguardCancellable = addItemController.observeAddSend(type, onClose, onChange)

    fun observeEditCipher(
        requestId: String,
        onClose: () -> Unit = {},
        onChange: (AddItemFormSnapshot) -> Unit,
    ): KeyguardCancellable = addItemController.observeEditCipher(requestId, onClose, onChange)

    fun observeEditSend(
        requestId: String,
        onClose: () -> Unit = {},
        onChange: (AddItemFormSnapshot) -> Unit,
    ): KeyguardCancellable = addItemController.observeEditSend(requestId, onClose, onChange)

    fun setEditFormRequestHandler(handler: ((AddEditFormRequest) -> Unit)?) =
        addItemController.setEditFormRequestHandler(handler)

    fun clearEditForm(requestId: String) = addItemController.clearEditForm(requestId)

    fun setAddField(id: String, text: String) = addItemController.setAddField(id, text)

    fun setAddSwitch(id: String, value: Boolean) = addItemController.setAddSwitch(id, value)

    fun invokeAddAction(id: String) = addItemController.invokeAddAction(id)

    fun scanAddTotp(id: String, value: String) = addItemController.scanAddTotp(id, value)

    fun submitAddItem() = addItemController.submitAddItem()

    fun invokeAddOwnership() = addItemController.invokeAddOwnership()

    fun setAddFilePickerRequestHandler(handler: ((AddFilePickerRequest) -> Unit)?) =
        addItemController.setAddFilePickerRequestHandler(handler)

    fun resolveAddFilePicker(requestId: String, uri: String, name: String?, size: Long, accessToken: String? = null) =
        addItemController.resolveAddFilePicker(requestId, uri, name, size, accessToken)

    fun cancelAddFilePicker(requestId: String) = addItemController.cancelAddFilePicker(requestId)

    fun dropFileOnAddForm(uri: String, name: String?, size: Long) =
        addItemController.dropFileOnAddForm(uri, name, size)

    fun dropFileOnAddItem(itemId: String, uri: String, name: String?, size: Long) =
        addItemController.dropFileOnAddItem(itemId, uri, name, size)

    fun setAddDatePickerRequestHandler(handler: ((AddDatePickerRequest) -> Unit)?) =
        addItemController.setAddDatePickerRequestHandler(handler)

    fun resolveAddDatePicker(requestId: String, year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        addItemController.resolveAddDatePicker(requestId, year, month, day, hour, minute)

    fun cancelAddDatePicker(requestId: String) = addItemController.cancelAddDatePicker(requestId)

    private val watchtowerController by lazy { WatchtowerController(context) }

    fun observeWatchtower(
        onChange: (WatchtowerSnapshot) -> Unit,
    ): KeyguardCancellable = watchtowerController.observeWatchtower(onChange)

    fun invokeWatchtowerAction(id: String) = watchtowerController.invokeWatchtowerAction(id)

    fun invokeWatchtowerFilter(id: String) = watchtowerController.invokeWatchtowerFilter(id)

    fun clearWatchtowerFilters() = watchtowerController.clearWatchtowerFilters()

    private val serviceDirectoryController by lazy { ServiceDirectoryController(context) }

    private val organizationsController by lazy { OrganizationsController(context) }
    private val collectionsController by lazy { CollectionsController(context) }
    private val foldersController by lazy { FoldersController(context) }
    private val equivalentDomainsController by lazy { EquivalentDomainsController(context) }
    private val exportController by lazy { ExportController(context) }
    private val cipherFiltersController by lazy { CipherFiltersController(context) }
    private val downloadsController by lazy { DownloadsController(context) }
    private val feedbackController by lazy { FeedbackController(context) }

    // Declared here (after every controller it references) so its property
    // initializer doesn't forward-reference an uninitialized controller. The
    // Swift-facing forwarders live up next to the cipher-detail section.
    private val navigationStackController by lazy {
        NavigationStackController(
            context,
            dialogController,
            cipherDetailController,
            watchtowerController,
            serviceDirectoryController,
            organizationsController,
            collectionsController,
            foldersController,
            equivalentDomainsController,
            exportController,
            cipherFiltersController,
            downloadsController,
            feedbackController,
            addItemController,
        )
    }

    init {
        if (context.runtime == KeyguardRuntime.APP) {
            // Dialog producers' links open like every other external link.
            dialogController.openUrl = navigationStackController::openUrl
            // A producer's Bitwarden login (an account's re-login) opens the native
            // login sheet with the route's args.
            navigationStackController.bitwardenLoginArgsHandler = loginController::prepareBitwardenLogin
            // Late-bind the producers' interceptor to the stack so their full-screen
            // routes push instead of being dropped (cipher detail's folder chips,
            // watchtower's cards / directory shortcuts / alerts). Done here to break
            // the controller <-> stack reference cycle.
            val provider: (Scope) -> ((NavigationIntent) -> Boolean) = { sessionKoin ->
                navigationStackController.interceptor(sessionKoin)
            }
            quickCopyController.navigationInterceptorProvider = provider
            cipherDetailController.navigationInterceptorProvider = provider
            watchtowerController.navigationInterceptorProvider = provider
            // The generator's "create login / SSH key" actions emit an AddRoute; thread
            // the interceptor so they reach the stack (which presents the add sheet).
            generatorController.navigationInterceptorProvider = { sessionKoin ->
                val stackInterceptor = provider(sessionKoin)
                val interceptor: (NavigationIntent) -> Boolean = { intent ->
                    addItemController.interceptDateTimePicker(intent) || stackInterceptor(intent)
                }
                interceptor
            }
            // The in-form Autofill generator can emit the same routes; give it the same
            // interceptor so they are handled identically rather than dropped.
            autofillGeneratorController.navigationInterceptorProvider = provider
            // GPG Tools captures its own result-dialog + custom-key routes; everything
            // else it emits is delegated to the stack interceptor.
            gpgToolsController.navigationInterceptorProvider = provider
            // The Send detail's "edit" action emits a SendAddRoute; thread the
            // interceptor so it reaches the stack (which opens the native edit sheet)
            // instead of being dropped by the dialog-only interceptor.
            sendDetailController.navigationInterceptorProvider = provider
            // The add / Send create form's ownership "Save to" row emits an
            // OrganizationConfirmationRoute; hand it the dialog interceptor so the
            // account picker is presented instead of being dropped (the date / time
            // pickers are caught by the controller's own interceptor regardless).
            addItemController.navigationInterceptorProvider = { sessionKoin ->
                dialogController.navigationInterceptor(sessionKoin = sessionKoin)
            }
            // The Send list's file drop emits a SendAddRoute (a new File send pre-filled
            // with the dropped file); thread the stack interceptor so it opens the native
            // create sheet instead of being dropped.
            sendListController.navigationInterceptorProvider = provider
        }
    }

    /**
     * Creates a vault-list session (the data-only delta/snapshot pipeline
     * wrapping the commonMain `AppleVaultListSource`). This is the live path for the
     * main vault list. Each call returns an independent instance; tear it down
     * with [VaultListSession.close].
     */
    fun makeVaultListSession(config: VaultListSessionConfig): VaultListSession =
        makeVaultListSession(
            args = config.toArgs(),
            persistenceScope = config.persistenceScope,
            cipherFilterId = config.cipherFilterId.takeIf { it.isNotEmpty() },
        )

    internal fun makeVaultListSession(
        args: VaultRoute.Args,
        persistenceScope: String,
        cipherFilterId: String? = null,
    ): VaultListSession = VaultListSession(
        ctx = context,
        args = args,
        persistenceScope = persistenceScope,
        // Route row opens and toolbar actions through the current stack scope.
        navigationInterceptorProvider = { sessionKoin ->
            navigationStackController.interceptor(sessionKoin)
        },
        cipherFilterId = cipherFilterId,
    )

    /** Pushes a cipher detail onto the stack (an iPhone vault row tap). */
    fun pushCipherDetail(itemId: String, accountId: String) =
        navigationStackController.pushCipherDetail(itemId, accountId)

    /** Pushes a service-directory list (a watchtower "Tools" row, Swift-initiated). */
    fun pushServiceDirectoryList(kind: String, title: String) =
        navigationStackController.pushServiceDirectoryList(kind, title)

    /** Pushes a service-directory service detail (list item tap, Swift-initiated). */
    fun pushServiceDirectoryDetail(kind: String, itemId: String, title: String) =
        navigationStackController.pushServiceDirectoryDetail(kind, itemId, title)

    /** Pushes the generator history (a generator "Tools" row, Swift-initiated). */
    fun pushGeneratorHistory() = navigationStackController.pushGeneratorHistory()

    /** Pushes the email-relay list (a generator "Tools" row, Swift-initiated). */
    fun pushEmailRelayList() = navigationStackController.pushEmailRelayList()

    /** Pushes the wordlists list (a generator "Tools" row, Swift-initiated). */
    fun pushWordlistList() = navigationStackController.pushWordlistList()

    /** Pushes a single wordlist's detail (a wordlists list-item tap, Swift-initiated). */
    fun pushWordlistDetail(wordlistId: Long, title: String) =
        navigationStackController.pushWordlistDetail(wordlistId, title)

    /** Pushes a cipher's password history (the cipher detail header button). */
    fun pushPasswordHistory(itemId: String) =
        navigationStackController.pushPasswordHistory(itemId)

    /** Pushes a Send detail (an iPhone Send row tap, Swift-initiated). */
    fun pushSendDetail(sendId: String, accountId: String) =
        navigationStackController.pushSendDetail(sendId, accountId)

    /** Pushes an account detail (an iPhone Settings account-row tap, Swift-initiated). */
    fun pushAccountDetail(accountId: String) =
        navigationStackController.pushAccountDetail(accountId)

    /** Pushes the "Contact us" feedback screen (a Settings → About row, Swift-initiated). */
    fun pushFeedback() = navigationStackController.pushFeedback()

    /** Writes [text] into a feedback entry's message field. */
    fun setEntryFeedbackMessage(instanceId: Long, text: String) =
        navigationStackController.setEntryFeedbackMessage(instanceId, text)

    /** Submits a feedback entry (sends the message via a native mailto:). */
    fun submitEntryFeedback(instanceId: Long) =
        navigationStackController.submitEntryFeedback(instanceId)

    /**
     * Standalone "Contact us" observation for the native modal sheet (macOS / iOS
     * Settings → About row). Mirrors [observeChangePassword]: runs the shared feedback
     * producer headlessly and opens the send `mailto:` via the host's open-url handler.
     */
    fun observeFeedback(onChange: (FeedbackSnapshot) -> Unit): KeyguardCancellable =
        feedbackController.observeFeedback(
            onChange = onChange,
            openUrl = { url -> navigationStackController.openSystemUrl(url) },
        )

    /** Writes [text] into the standalone feedback sheet's message field. */
    fun setFeedbackMessage(text: String) = feedbackController.setFeedbackMessage(text)

    /** Submits the standalone feedback sheet (sends the message via a native mailto:). */
    fun submitFeedback() = feedbackController.submitFeedback()

    /** Writes [text] into an export entry's password field. */
    fun setExportPassword(instanceId: Long, text: String) =
        navigationStackController.setExportPassword(instanceId, text)

    /** Pushes an organization's collections (an organizations list-item tap). */
    fun pushCollectionsList(accountId: String, organizationId: String?, title: String) =
        navigationStackController.pushCollectionsList(accountId, organizationId, title)

    /** Creates a folder in [accountId] (the folders screen's native add). */
    @Throws(Exception::class)
    suspend fun addFolder(accountId: String, name: String) =
        foldersController.addFolder(accountId, name)

    /** Renames the folder [id] (the folders screen's native rename). */
    @Throws(Exception::class)
    suspend fun renameFolder(id: String, name: String) =
        foldersController.renameFolder(id, name)

    /** Deletes the folder [id], optionally trashing its ciphers (native delete). */
    @Throws(Exception::class)
    suspend fun deleteFolder(id: String, trashCiphers: Boolean) =
        foldersController.deleteFolder(id, trashCiphers)

    fun observeServiceDirectoryDetail(
        kind: String,
        itemId: String,
        onChange: (ServiceDirectoryDetailSnapshot) -> Unit,
    ): KeyguardCancellable = serviceDirectoryController.observeServiceDirectoryDetail(kind, itemId, onChange)

    // ---------------------------------------------------------------------------
    // Account list / detail. Renders the list of accounts on top of the Settings
    // screen and a per-account detail pane, reusing the shared
    // accountListScreenStateProducer / accountStateProducer and the shared
    // VaultItemSnapshot mapping (account detail rows are the same VaultViewItem type).
    // ---------------------------------------------------------------------------

    private val accountsController by lazy { AccountsController(context, dialogController) }

    init {
        if (context.runtime == KeyguardRuntime.APP) {
            // Late-bind here (a second init block) because accountsController is declared
            // after the navigation stack; its detail "view items" (a VaultRoute) then
            // pushes onto the Settings-scope stack instead of being dropped.
            accountsController.navigationInterceptorProvider = { sessionKoin ->
                navigationStackController.interceptor(sessionKoin)
            }
        }
    }

    fun observeAccountList(
        onChange: (AccountListSnapshot) -> Unit,
    ): KeyguardCancellable = accountsController.observeAccountList(onChange)

    fun observeSyncStatus(
        onChange: (SyncStatusSnapshot) -> Unit,
    ): KeyguardCancellable = accountsController.observeSyncStatus(onChange)

    fun observeAccountDetail(
        accountId: String,
        onChange: (AccountDetailSnapshot) -> Unit,
    ): KeyguardCancellable = accountsController.observeAccountDetail(accountId, onChange)

    fun invokeAccountAction(id: String) = accountsController.invokeAccountAction(id)

    fun invokeAccountListAction(id: String) = accountsController.invokeAccountListAction(id)

    // Email forwarders and wordlists.

    /** Suspends until the vault is unlocked, returning its session [VaultState.Main]. */

    private val emailRelayController by lazy { EmailRelayController(context) }

    private val generatorHistoryController by lazy { GeneratorHistoryController(context, dialogController) }

    fun observeGeneratorHistory(
        onChange: (GeneratorHistorySnapshot) -> Unit,
    ): KeyguardCancellable = generatorHistoryController.observeGeneratorHistory(onChange)

    /** Runs a per-item dropdown action of a generator history row by its id. */
    fun invokeGeneratorHistoryItemAction(id: String) =
        generatorHistoryController.invokeGeneratorHistoryItemAction(id)

    /** Runs a top-level overflow option (Clear history) by its id. */
    fun invokeGeneratorHistoryOption(id: String) =
        generatorHistoryController.invokeGeneratorHistoryOption(id)

    /** Runs a bulk action of the active generator history multi-selection by its id. */
    fun invokeGeneratorHistorySelectionAction(id: String) =
        generatorHistoryController.invokeGeneratorHistorySelectionAction(id)

    /** Toggles whether a generator history row is part of the multi-selection. */
    fun toggleGeneratorHistorySelection(itemId: String) =
        generatorHistoryController.toggleGeneratorHistorySelection(itemId)

    /** Clears the active generator history multi-selection. */
    fun clearGeneratorHistorySelection() =
        generatorHistoryController.clearGeneratorHistorySelection()

    /** Selects every generator history value row. */
    fun selectAllGeneratorHistory() =
        generatorHistoryController.selectAllGeneratorHistory()

    private val readOnlyListsController by lazy { ReadOnlyListsController(context, dialogController) }

    fun observeSshAgentHistory(
        cipherId: String?,
        onChange: (SshAgentHistorySnapshot) -> Unit,
    ): KeyguardCancellable = readOnlyListsController.observeSshAgentHistory(cipherId, onChange)

    fun observePasswordHistory(
        itemId: String,
        onChange: (PasswordHistorySnapshot) -> Unit,
    ): KeyguardCancellable = readOnlyListsController.observePasswordHistory(itemId, onChange)

    /**
     * Runs a per-entry dropdown action of a password-history row (copy password /
     * remove from history / show in large type / show-and-lock / check data
     * breaches) by its id.
     */
    fun invokePasswordHistoryItemAction(id: String) =
        readOnlyListsController.invokePasswordHistoryItemAction(id)

    /** Runs a bulk action of the active password-history multi-selection (Delete) by its id. */
    fun invokePasswordHistorySelectionAction(id: String) =
        readOnlyListsController.invokePasswordHistorySelectionAction(id)

    /** Runs a top-level password-history action (the "Clear history" action) by its id. */
    fun invokePasswordHistoryAction(id: String) =
        readOnlyListsController.invokePasswordHistoryAction(id)

    /** Toggles whether a password-history row is part of the multi-selection. */
    fun togglePasswordHistorySelection(itemId: String) =
        readOnlyListsController.togglePasswordHistorySelection(itemId)

    /** Clears the active password-history multi-selection. */
    fun clearPasswordHistorySelection() =
        readOnlyListsController.clearPasswordHistorySelection()

    fun observeLicense(
        onChange: (LicenseListSnapshot) -> Unit,
    ): KeyguardCancellable = readOnlyListsController.observeLicense(onChange)

    fun observeLocalizationContributors(
        onChange: (LocalizationContributorsSnapshot) -> Unit,
    ): KeyguardCancellable = readOnlyListsController.observeLocalizationContributors(onChange)

    fun observeLogs(
        onChange: (LogsSnapshot) -> Unit,
    ): KeyguardCancellable = readOnlyListsController.observeLogs(onChange)

    fun observeUrlBlockList(
        onChange: (UrlRuleListSnapshot) -> Unit,
    ): KeyguardCancellable = readOnlyListsController.observeUrlBlockList(onChange)

    /** Runs a per-row dropdown action of a blocked-URL row (edit / duplicate / delete) by its id. */
    fun invokeUrlBlockListItemAction(id: String) =
        readOnlyListsController.invokeUrlBlockListItemAction(id)

    /** Runs a bulk action of the active blocked-URL multi-selection (Delete) by its id. */
    fun invokeUrlBlockListSelectionAction(id: String) =
        readOnlyListsController.invokeUrlBlockListSelectionAction(id)

    /** Opens the create-new blocked-URL form. */
    fun invokeUrlBlockListPrimaryAction() =
        readOnlyListsController.invokeUrlBlockListPrimaryAction()

    /** Toggles whether a blocked-URL row is part of the multi-selection. */
    fun toggleUrlBlockListSelection(itemId: String) =
        readOnlyListsController.toggleUrlBlockListSelection(itemId)

    /** Clears the active blocked-URL multi-selection. */
    fun clearUrlBlockListSelection() =
        readOnlyListsController.clearUrlBlockListSelection()

    fun observeUrlOverrideList(
        onChange: (UrlRuleListSnapshot) -> Unit,
    ): KeyguardCancellable = readOnlyListsController.observeUrlOverrideList(onChange)

    /** Runs a per-row dropdown action of a URL-override row (edit / duplicate / delete) by its id. */
    fun invokeUrlOverrideListItemAction(id: String) =
        readOnlyListsController.invokeUrlOverrideListItemAction(id)

    /** Runs a bulk action of the active URL-override multi-selection (Delete) by its id. */
    fun invokeUrlOverrideListSelectionAction(id: String) =
        readOnlyListsController.invokeUrlOverrideListSelectionAction(id)

    /** Opens the create-new URL-override form. */
    fun invokeUrlOverrideListPrimaryAction() =
        readOnlyListsController.invokeUrlOverrideListPrimaryAction()

    /** Toggles whether a URL-override row is part of the multi-selection. */
    fun toggleUrlOverrideListSelection(itemId: String) =
        readOnlyListsController.toggleUrlOverrideListSelection(itemId)

    /** Clears the active URL-override multi-selection. */
    fun clearUrlOverrideListSelection() =
        readOnlyListsController.clearUrlOverrideListSelection()

    fun observeWatchtowerNewAlerts(
        onChange: (WatchtowerAlertsSnapshot) -> Unit,
    ): KeyguardCancellable = watchtowerController.observeWatchtowerNewAlerts(onChange = onChange)

    fun invokeWatchtowerAlertItem(id: String) = watchtowerController.invokeWatchtowerAlertItem(id)

    fun markAllWatchtowerAlertsRead() = watchtowerController.markAllWatchtowerAlertsRead()

    private val appInformationController by lazy { AppInformationController(context) }

    fun observeAppInformation(
        onChange: (AppInformationSnapshot) -> Unit,
    ): KeyguardCancellable = appInformationController.observeAppInformation(onChange)

    private val staticDataController by lazy { StaticDataController(context) }
    private val wordlistController by lazy { WordlistController(context) }

    suspend fun loadAboutTeam(): AboutTeamSnapshot = staticDataController.loadAboutTeam()

    suspend fun loadDataSafety(): List<DataSafetyItemSnapshot> = staticDataController.loadDataSafety()

    suspend fun loadEmailRelayServices(): List<EmailRelayFormSnapshot> =
        emailRelayController.loadEmailRelayServices()

    suspend fun loadEmailRelay(id: String): EmailRelayFormSnapshot? =
        emailRelayController.loadEmailRelay(id)

    suspend fun saveEmailRelay(
        id: String?,
        type: String,
        name: String,
        values: Map<String, String>,
    ) = emailRelayController.saveEmailRelay(id, type, name, values)

    suspend fun duplicateEmailRelay(id: String) = emailRelayController.duplicateEmailRelay(id)

    suspend fun deleteEmailRelays(ids: List<String>) = emailRelayController.deleteEmailRelays(ids)

    @Throws(Exception::class)
    suspend fun addWordlistFromFile(name: String, uri: String) =
        wordlistController.addWordlistFromFile(name, uri)

    @Throws(Exception::class)
    suspend fun addWordlistFromUrl(name: String, url: String) =
        wordlistController.addWordlistFromUrl(name, url)

    @Throws(Exception::class)
    suspend fun renameWordlist(id: Long, name: String) = wordlistController.renameWordlist(id, name)

    @Throws(Exception::class)
    suspend fun deleteWordlists(ids: List<Long>) = wordlistController.deleteWordlists(ids)

    suspend fun loadSettingsList(): SettingsListSnapshot = staticDataController.loadSettingsList()

    @Throws(Exception::class)
    suspend fun loadSettingsSearch(
        categories: List<SettingsItemSnapshot>,
        biometricTitle: String,
        localeIdentifier: String,
    ): SettingsSearchIndex = staticDataController.loadSettingsSearch(categories, biometricTitle, localeIdentifier)

    // ---------------------------------------------------------------------------
    // Watchtower settings. Thin bridge over the shared Get/Put use cases that back
    // the common WatchtowerSettingsScreen items (check pwned passwords / services,
    // inactive 2FA, inactive passkeys, HIBP API token). Mirrors the per-item
    // providers in feature/home/settings/component/ — no UI logic re-derived here.
    // ---------------------------------------------------------------------------

    fun observeWatchtowerSettings(
        onChange: (WatchtowerSettingsSnapshot) -> Unit,
    ): KeyguardCancellable = watchtowerController.observeWatchtowerSettings(onChange)

    fun setCheckPwnedPasswords(value: Boolean) = watchtowerController.setCheckPwnedPasswords(value)

    fun setCheckPwnedServices(value: Boolean) = watchtowerController.setCheckPwnedServices(value)

    fun setCheckTwoFa(value: Boolean) = watchtowerController.setCheckTwoFa(value)

    fun setCheckPasskeys(value: Boolean) = watchtowerController.setCheckPasskeys(value)

    fun setHibpApiToken(token: String) = watchtowerController.setHibpApiToken(token)

    fun isValidHibpApiToken(token: String): Boolean = watchtowerController.isValidHibpApiToken(token)

    // ---------------------------------------------------------------------------
    // Security settings. Thin bridge over the shared Get/Put use cases that back
    // the macOS-relevant items of the common SecuritySettingsScreen (vault persist,
    // auto-lock timeout, lock-after-reboot, lock now, clipboard auto-clear, conceal
    // fields, website icons, Gravatar, Touch ID unlock). Items the common providers
    // hide on Apple — screen-off lock, clipboard auto-refresh, YubiKey, clipboard
    // notifications, screenshots, clear vault (Wear-only) — are intentionally not
    // surfaced here. Change-master-password is a separate sheet (see
    // observeChangePassword).
    // ---------------------------------------------------------------------------

    private val securityController by lazy { SecurityController(context) }

    fun observeSecuritySettings(
        onChange: (SecuritySettingsSnapshot) -> Unit,
    ): KeyguardCancellable = securityController.observeSecuritySettings(onChange)

    fun setBiometricUnlock(value: Boolean) = securityController.setBiometricUnlock(value)

    fun setVaultPersist(value: Boolean) = securityController.setVaultPersist(value)

    fun setVaultLockAfterReboot(value: Boolean) = securityController.setVaultLockAfterReboot(value)

    fun setVaultLockTimeout(optionId: String) = securityController.setVaultLockTimeout(optionId)

    fun setBiometricTimeout(optionId: String) = securityController.setBiometricTimeout(optionId)

    fun setClipboardAutoClear(optionId: String) = securityController.setClipboardAutoClear(optionId)

    fun setConcealFields(value: Boolean) = securityController.setConcealFields(value)

    fun setWebsiteIcons(value: Boolean) = securityController.setWebsiteIcons(value)

    fun setGravatar(value: Boolean) = securityController.setGravatar(value)

    // ---------------------------------------------------------------------------
    // AutoFill settings (default URI matching, copy-TOTP-to-clipboard,
    // save-credential prompts, save-URI prompts). Thin bridge over the shared
    // Get/Put preference use cases.
    // ---------------------------------------------------------------------------

    private val autofillSettingsController by lazy {
        val leContext = context.koin.get<LeContext>()
        AutofillSettingsController(
            getAutofillCopyTotp = context.koin.get(),
            putAutofillCopyTotp = context.koin.get(),
            getAutofillSaveRequest = context.koin.get(),
            putAutofillSaveRequest = context.koin.get(),
            getAutofillSaveUri = context.koin.get(),
            putAutofillSaveUri = context.koin.get(),
            getAutofillDefaultMatchDetection = context.koin.get(),
            putAutofillDefaultMatchDetection = context.koin.get(),
            scope = context.backgroundScope,
            text = { textResource(it, leContext) },
        )
    }

    fun observeAutofillSettings(
        onChange: (AutofillSettingsSnapshot) -> Unit,
    ): KeyguardCancellable = autofillSettingsController.observeAutofillSettings(onChange)

    fun setAutofillCopyTotp(value: Boolean) = autofillSettingsController.setCopyTotp(value)

    fun setAutofillSaveRequest(value: Boolean) = autofillSettingsController.setSaveRequest(value)

    fun setAutofillSaveUri(value: Boolean) = autofillSettingsController.setSaveUri(value)

    fun setAutofillDefaultMatchDetection(optionId: String) =
        autofillSettingsController.setDefaultMatchDetection(optionId)

    // ---------------------------------------------------------------------------
    // Subscriptions / in-app purchases (the paywall). The shared billing use
    // cases are driven headlessly; purchase / restore / manage route to the
    // Swift StoreKit bridge (see SubscriptionsController + AppleBillingBridge).
    // ---------------------------------------------------------------------------

    private val subscriptionsController by lazy { SubscriptionsController(context) }

    fun observeSubscriptions(
        onChange: (SubscriptionsSnapshot) -> Unit,
    ): KeyguardCancellable = subscriptionsController.observeSubscriptions(onChange)

    fun purchaseSubscription(id: String) = subscriptionsController.purchase(id)

    fun restorePurchases() = subscriptionsController.restorePurchases()

    fun manageSubscriptions() = subscriptionsController.manageSubscriptions()

    fun startBilling() = subscriptionsController.start()
    fun refreshBilling() = subscriptionsController.refresh()
    fun syncAppleLicense() = subscriptionsController.syncLicense()
    fun linkAppleLicense(value: String) = subscriptionsController.linkLicense(value)
    fun removeAppleLicense() = subscriptionsController.removeLicense()
    fun refreshAppleLicense() = subscriptionsController.refreshLicense()


    // ---------------------------------------------------------------------------
    // SSH agent settings (the Developer pane). Built from the same shared
    // Get/Put use cases the desktop settings use; the enabled toggle goes
    // through setSshAgentEnabled (see the SSH agent section above).
    // ---------------------------------------------------------------------------

    fun observeSshAgentSettings(
        onChange: (SshAgentSettingsSnapshot) -> Unit,
    ): KeyguardCancellable = sshAgentController.observeSshAgentSettings(onChange)

    fun setSshAgentApprovalWindow(optionId: String) = sshAgentController.setSshAgentApprovalWindow(optionId)

    fun setSshAgentDisplayKeyNames(value: Boolean) = sshAgentController.setSshAgentDisplayKeyNames(value)

    fun observeSshAgentFilters(
        onChange: (SshAgentFiltersSnapshot) -> Unit,
        onClose: () -> Unit,
    ): KeyguardCancellable = sshAgentController.observeSshAgentFilters(onChange, onClose)

    fun invokeSshAgentFilter(id: String) = sshAgentController.invokeSshAgentFilter(id)

    fun saveSshAgentFilters() = sshAgentController.saveSshAgentFilters()

    fun resetSshAgentFilters() = sshAgentController.resetSshAgentFilters()

    // ---------------------------------------------------------------------------
    // Change master password sheet. Runs the shared change-password producer
    // headlessly (see changePasswordStateProducer). On success the producer pops
    // its own screen via navigatePopSelf -> PopById, which the interceptor turns
    // into the [onClose] callback so SwiftUI can dismiss the sheet.
    // ---------------------------------------------------------------------------

    private val changePasswordController by lazy { ChangePasswordController(context, authPromptHost) }

    fun observeChangePassword(
        onChange: (ChangePasswordSnapshot) -> Unit,
        onClose: () -> Unit,
    ): KeyguardCancellable = changePasswordController.observeChangePassword(onChange, onClose)

    fun setChangePasswordCurrent(text: String) = changePasswordController.setChangePasswordCurrent(text)

    fun setChangePasswordNew(text: String) = changePasswordController.setChangePasswordNew(text)

    fun setChangePasswordBiometric(enabled: Boolean) = changePasswordController.setChangePasswordBiometric(enabled)

    fun submitChangePassword() = changePasswordController.submitChangePassword()

    // ---------------------------------------------------------------------------
    // Development settings. Debug overrides are unavailable in Release builds.
    // ---------------------------------------------------------------------------

    private val debugSettingsController by lazy {
        DebugSettingsController(
            getDebugPremium = context.koin.get(),
            putDebugPremium = context.koin.get(),
            scope = context.scope,
        )
    }

    fun observeDebugSettings(
        onChange: (DebugSettingsSnapshot) -> Unit,
    ): KeyguardCancellable = debugSettingsController.observe(onChange)

    fun setDebugPremium(enabled: Boolean) = debugSettingsController.setPremiumOverride(enabled)

    // ---------------------------------------------------------------------------
    // Appearance settings. Thin bridge over the shared Get/Put use cases that back
    // the macOS-relevant items of the common UiSettingsScreen. Enum pickers (theme,
    // accent, font, nav animation, locale) are surfaced as SettingOptionSnapshot
    // lists keyed by the variant's list index. Keep-screen-on is applied by iOS;
    // Android app icons are intentionally not surfaced.
    // ---------------------------------------------------------------------------

    private val appearanceController by lazy { AppearanceController(context) }

    fun observeAppearanceSettings(
        onChange: (AppearanceSettingsSnapshot) -> Unit,
    ): KeyguardCancellable = appearanceController.observeAppearanceSettings(onChange)

    fun setAmoledDark(value: Boolean) = appearanceController.setAmoledDark(value)
    fun setExpressive(value: Boolean) = appearanceController.setExpressive(value)
    fun setMarkdown(value: Boolean) = appearanceController.setMarkdown(value)
    fun setNavLabel(value: Boolean) = appearanceController.setNavLabel(value)
    fun setUseExternalBrowser(value: Boolean) = appearanceController.setUseExternalBrowser(value)
    fun setKeepScreenOn(value: Boolean) = appearanceController.setKeepScreenOn(value)
    fun setMinimizeOnCopy(value: Boolean) = appearanceController.setMinimizeOnCopy(value)
    fun setCloseToTray(value: Boolean) = appearanceController.setCloseToTray(value)
    fun setTwoPanelPortrait(value: Boolean) = appearanceController.setTwoPanelPortrait(value)
    fun setTwoPanelLandscape(value: Boolean) = appearanceController.setTwoPanelLandscape(value)
    fun setTheme(optionId: String) = appearanceController.setTheme(optionId)
    fun setFont(optionId: String) = appearanceController.setFont(optionId)
    fun setColors(optionId: String) = appearanceController.setColors(optionId)
    fun setLocale(optionId: String) = appearanceController.setLocale(optionId)
    fun setNavAnimation(optionId: String) = appearanceController.setNavAnimation(optionId)

    fun observeAppPreferences(
        onChange: (AppPreferencesSnapshot) -> Unit,
    ): KeyguardCancellable = appearanceController.observeAppPreferences(onChange)

    fun observeMinimizeOnCopy(
        onMinimize: () -> Unit,
    ): KeyguardCancellable = appearanceController.observeMinimizeOnCopy(onMinimize)

    // ---------------------------------------------------------------------------
    // Navigation items. The resolved top-level section list (order, visibility and
    // custom cipher-filter tabs from the shared NavItemsConfig) plus the
    // "Navigation items" settings screen, which runs the shared producer headlessly.

    private val navItemsController by lazy { NavItemsController(context, dialogController) }

    /**
     * Observes the resolved top-level navigation sections the SwiftUI shell
     * renders (macOS sidebar / iOS tab bar). Emits the empty snapshot while
     * locked; Swift falls back to the default built-in sections.
     */
    fun observeNavItems(
        onChange: (NavItemsSnapshot) -> Unit,
    ): KeyguardCancellable = navItemsController.observeNavItems(onChange)

    /** Observes the "Navigation items" settings screen state. */
    fun observeNavItemsSettings(
        onChange: (NavItemsSettingsSnapshot) -> Unit,
    ): KeyguardCancellable = navItemsController.observeNavItemsSettings(onChange)

    fun toggleNavItemVisibility(key: String) = navItemsController.toggleNavItemVisibility(key)
    fun moveNavItemUp(key: String) = navItemsController.moveNavItemUp(key)
    fun moveNavItemDown(key: String) = navItemsController.moveNavItemDown(key)
    fun removeNavItem(key: String) = navItemsController.removeNavItem(key)
    fun addNavItem(key: String) = navItemsController.addNavItem(key)

    /** Commits a drag-reorder; [keys] is the full row order the user dropped. */
    fun reorderNavItems(keys: List<String>) = navItemsController.reorderNavItems(keys)

    /** Fires the reset flow (native confirmation dialog, then defaults). */
    fun resetNavItems() = navItemsController.resetNavItems()

    // ---------------------------------------------------------------------------
    // Automatic Backups. Runs the shared AutomaticBackupsSettingsState producer
    // headlessly for saved configuration and status. Native setup keeps a complete
    // in-memory draft until destination verification succeeds; the folder picker
    // reuses the existing file-picker bridge ([handleAddFilePickerIntent]).
    // ---------------------------------------------------------------------------

    private val backupsController by lazy { BackupsController(context, addItemController) }

    fun observeBackupSettings(
        onChange: (BackupSettingsSnapshot) -> Unit,
    ): KeyguardCancellable = backupsController.observeBackupSettings(onChange)

    fun setBackupIncludeAttachments(value: Boolean) = backupsController.setBackupIncludeAttachments(value)

    fun beginBackupSetup() = backupsController.beginBackupSetup()

    fun cancelBackupSetup() = backupsController.cancelBackupSetup()

    fun setBackupSetupRetention(maxSnapshots: Int) = backupsController.setBackupSetupRetention(maxSnapshots)

    fun setBackupPassword(text: String) = backupsController.setBackupPassword(text)

    fun restoreBackupSetupPassword() = backupsController.restoreBackupSetupPassword()

    fun setBackupStoreKind(kind: String) = backupsController.setBackupStoreKind(kind)

    fun setBackupStoreLocalPath(path: String) = backupsController.setBackupStoreLocalPath(path)

    fun setBackupStoreWebDav(url: String, username: String, password: String) =
        backupsController.setBackupStoreWebDav(url, username, password)

    fun isValidBackupWebDavUrl(url: String): Boolean = backupsController.isValidBackupWebDavUrl(url)

    fun pickBackupLocation() = backupsController.pickBackupLocation()

    fun enableBackup() = backupsController.enableBackup()

    fun triggerBackupNow() = backupsController.triggerBackupNow()

    fun setBackupRetention(maxSnapshots: Int) = backupsController.setBackupRetention(maxSnapshots)

    fun disableBackup() = backupsController.disableBackup()
}
