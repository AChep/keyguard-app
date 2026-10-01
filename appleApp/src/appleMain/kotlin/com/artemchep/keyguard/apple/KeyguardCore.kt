package com.artemchep.keyguard.apple

import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.feature.generator.GeneratorRoute
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.navigation.NavigationIntent
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
import com.artemchep.keyguard.apple.model.TotpFieldSnapshot
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
import com.artemchep.keyguard.apple.watchtower.WatchtowerController
import com.artemchep.keyguard.apple.watchtower.WatchtowerSettingsSnapshot
import com.artemchep.keyguard.apple.watchtower.WatchtowerSnapshot
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.koin.core.scope.Scope
import platform.Foundation.create

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

/** Facade exposing shared Keyguard logic to native SwiftUI clients. */
class KeyguardCore(runtime: KeyguardRuntime) {
    constructor() : this(KeyguardRuntime.APP)
    private val context = CoreContext(runtime)

    private val authPromptHost = AuthPromptHost(context, com.artemchep.keyguard.util.yubikey.NativeYubiKeyClient())

    private val dialogController by lazy { DialogController(context, authPromptHost) }

    private val generatorController by lazy { GeneratorController(context) }

    /** The add form's in-form generator; separate from the generator screen so the two never share routing tables. */
    private val autofillGeneratorController by lazy { GeneratorController(context) }

    private val gpgToolsController by lazy { GpgToolsController(context) }

    private val appLifecycleController = AppLifecycleController(context)

    /** Report every SwiftUI `scenePhase` change; the sync worker and the auto-lock timer follow it. */
    fun setScenePhase(phase: KeyguardScenePhase) = appLifecycleController.setScenePhase(phase)

    /** Main app only; a no-op in the AutoFill runtime and on repeat calls. */
    fun startAutomaticBackups() = appLifecycleController.startAutomaticBackups()

    /** Main app only; a no-op in the AutoFill runtime and on repeat calls. */
    fun startWatchtower() = appLifecycleController.startWatchtower()

    /** [onChange] runs on the main thread on every status change. */
    fun observeStatus(
        onChange: (KeyguardVaultStatus) -> Unit,
    ): KeyguardCancellable = appLifecycleController.observeStatus(onChange)

    fun lockVault() = appLifecycleController.lockVault()

    /** Closes this extension core permanently. Await before completing or cancelling AutoFill. */
    @Throws(Exception::class)
    suspend fun closeAutofillSession() = appLifecycleController.closeAutofillSession()

    fun syncVault() = appLifecycleController.syncVault()

    private val quickCopyController by lazy { QuickCopyController(context) }

    /**
     * [field] is "username", "password" or "otp"; passwords are copied concealed. No-op while locked or when the
     * field is absent. Named `quick…`, not `copy…`: Kotlin/Native exports `copy`-family names as `doCopy…`.
     */
    suspend fun quickCopyCipherField(secretId: String, accountId: String, field: String) =
        quickCopyController.quickCopyCipherField(secretId, accountId, field)

    suspend fun generateAndCopyPassword() = quickCopyController.generateAndCopyPassword()

    // Launch at login (macOS). Delegates to the Swift bridge set via [registerLaunchAtLoginBridge].

    private val launchAtLoginController by lazy { LaunchAtLoginController(context) }

    /** Emits on every registration change; `requiresApproval` and `available` are sampled only then. */
    fun observeLaunchAtLogin(
        onChange: (LaunchAtLoginSnapshot) -> Unit,
    ): KeyguardCancellable = launchAtLoginController.observeLaunchAtLogin(onChange)

    fun setLaunchAtLogin(enabled: Boolean) = launchAtLoginController.setLaunchAtLogin(enabled)

    fun openLoginItemsSettings() = launchAtLoginController.openLoginItemsSettings()

    // First-run onboarding

    private val onboardingController by lazy { OnboardingController(context) }

    /** Emits `true` once [markOnboarded] has run, `false` before: the inverse of the Compose banner's visibility. */
    fun observeOnboarding(
        onChange: (Boolean) -> Unit,
    ): KeyguardCancellable = onboardingController.observeOnboarding(onChange)

    fun markOnboarded() = onboardingController.markOnboarded()

    // Quick Search (global hotkey overlay)

    private val quickSearchController by lazy { QuickSearchController(context) }

    /** Emits [QuickSearchSnapshot.empty] while the vault is locked. */
    fun observeQuickSearch(
        onChange: (QuickSearchSnapshot) -> Unit,
    ): KeyguardCancellable = quickSearchController.observeQuickSearch(onChange)

    /** Live TOTP codes keyed by item id, pushed on Main once per second; empty while locked. */
    fun observeQuickSearchTotp(
        onChange: (Map<String, TotpFieldSnapshot>) -> Unit,
    ): KeyguardCancellable = quickSearchController.observeQuickSearchTotp(onChange)

    /**
     * The Quick Search result rows as state-anchored [VaultListDelta]s — the SAME
     * background-delivered contract as the vault list's `observeListDelta` and
     * Recents' `observeRecentsListDelta`.
     */
    fun observeQuickSearchListDelta(
        onChange: (com.artemchep.keyguard.apple.vault.VaultListDelta) -> Unit,
    ): KeyguardCancellable = quickSearchController.observeQuickSearchListDelta(onChange)

    fun setQuickSearchQuery(text: String) = quickSearchController.setQuickSearchQuery(text)

    /** Bumps the field revision, so the SwiftUI buffer adopts the empty text (unlike [setQuickSearchQuery]). */
    fun clearQuickSearchQuery() = quickSearchController.clearQuickSearchQuery()

    /** Moves the highlighted result by [direction]: +1 down, -1 up. */
    fun moveQuickSearchSelection(direction: Int) = quickSearchController.moveQuickSearchSelection(direction)

    fun moveQuickSearchActionSelection(direction: Int) =
        quickSearchController.moveQuickSearchActionSelection(direction)

    fun selectQuickSearchItem(id: String) = quickSearchController.selectQuickSearchItem(id)

    fun invokeQuickSearchDefaultAction() = quickSearchController.invokeQuickSearchDefaultAction()

    fun invokeQuickSearchAction(typeName: String) = quickSearchController.invokeQuickSearchAction(typeName)

    fun setQuickSearchOpenUrlHandler(handler: ((String) -> Unit)?) =
        quickSearchController.setQuickSearchOpenUrlHandler(handler)

    /** Sets the handler that opens external URLs for producer-emitted [NavigationIntent.NavigateToBrowser] intents. */
    fun setOpenUrlHandler(handler: ((String) -> Unit)?) =
        navigationStackController.setOpenUrlHandler(handler)

    /** Opens system actions (Maps, Mail, Phone, Messages) independently of browser preferences. */
    fun setOpenSystemUrlHandler(handler: ((String) -> Unit)?) =
        navigationStackController.setOpenSystemUrlHandler(handler)

    /**
     * Sets the handler that presents the native share sheet for a producer-emitted
     * [NavigationIntent.NavigateToShare] intent; the argument is the text to share (the public Send link).
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
     * producer-emitted `AddRoute`; the arguments are (type, name, username, password).
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

    /** Sets the handler that reveals a local file natively; the argument is the file uri. */
    fun setRevealFileHandler(handler: ((String) -> Unit)?) =
        navigationStackController.setRevealFileHandler(handler)

    // SSH agent

    private val sshAgentController by lazy { SshAgentController(context) }

    /** Starts and stops the agent with its setting. Main app only: the AutoFill extension must never spawn it. */
    fun startSshAgentApplier() = sshAgentController.startSshAgentApplier()

    fun setSshAgentEnabled(value: Boolean) = sshAgentController.setSshAgentEnabled(value)

    fun observeSshAgentRequests(
        onChange: (List<SshAgentRequestSnapshot>) -> Unit,
    ): KeyguardCancellable = sshAgentController.observeSshAgentRequests(onChange)

    fun resolveSshAgentRequest(id: String, approved: Boolean) = sshAgentController.resolveSshAgentRequest(id, approved)

    fun observeSshAgentStatus(
        onChange: (SshAgentStatusSnapshot) -> Unit,
    ): KeyguardCancellable = sshAgentController.observeSshAgentStatus(onChange)

    // GPG agent
    private val gpgAgentController by lazy { GpgAgentController(context) }

    /** Starts and stops the agent with its setting. Main app only: the AutoFill extension must never spawn it. */
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

    /**
     * Denies every pending signing request, then saves the pending filters; on success the `onClose` of
     * [observeGpgAgentFilters] fires.
     */
    fun saveGpgAgentFilters() = gpgAgentController.saveGpgAgentFilters()

    fun resetGpgAgentFilters() = gpgAgentController.resetGpgAgentFilters()

    fun observeGpgAgentHistory(
        onChange: (GpgAgentHistorySnapshot) -> Unit,
    ): KeyguardCancellable = gpgAgentController.observeGpgAgentHistory(onChange)

    /** Clears without asking; confirm with the user first. */
    fun clearGpgAgentHistory() = gpgAgentController.clearGpgAgentHistory()

    // AutoFill (credential provider)

    private val autofillController by lazy { AutofillController(context) }

    fun observeAutofillChanges(onChange: (Boolean) -> Unit, onFailure: () -> Unit): KeyguardCancellable =
        autofillController.observeChanges(onChange, onFailure)

    @Throws(Exception::class)
    suspend fun loadAutofillIndex(): com.artemchep.keyguard.apple.lists.AutofillIndexSnapshot? =
        autofillController.loadIndex()

    /** [recordId] is `accountId|cipherId` (an index or suggestion record id); null while locked or when gone. */
    @Throws(Exception::class)
    suspend fun loadAutofillCredential(recordId: String): AutofillCredentialSnapshot? =
        autofillController.loadAutofillCredential(recordId)

    /**
     * [serviceIdentifiers] are URLs / domains from `ASCredentialServiceIdentifier`. Matches come first (ranked like
     * the Android provider, `suggested` set), then every other login with a password. Empty while locked.
     */
    @Throws(Exception::class)
    suspend fun loadAutofillSuggestions(
        serviceIdentifiers: List<String>,
    ): List<AutofillSuggestionSnapshot> =
        autofillController.loadAutofillSuggestions(serviceIdentifiers)

    /** Like [loadAutofillSuggestions], but only logins with a valid TOTP secret. */
    @Throws(Exception::class)
    suspend fun loadOneTimeCodeSuggestions(
        serviceIdentifiers: List<String>,
    ): List<AutofillSuggestionSnapshot> =
        autofillController.loadOneTimeCodeSuggestions(serviceIdentifiers)

    /** TOTP code of the login [recordId] (`accountId|cipherId`); null while locked, when gone or without TOTP. */
    @Throws(Exception::class)
    suspend fun loadOneTimeCode(recordId: String): String? =
        autofillController.loadOneTimeCode(recordId)

    @Throws(Exception::class)
    suspend fun loadPasskeyRegistrationSuggestions(serviceIdentifiers: List<String>): List<AutofillSuggestionSnapshot> =
        autofillController.loadPasskeyRegistrationSuggestions(serviceIdentifiers)

    private val passkeyController by lazy { PasskeyController(context) }

    @Throws(Exception::class)
    suspend fun loadMatchingPasskeyIdentities(
        rpId: String,
        allowedCredentialIds: List<ByteArray>,
    ): List<PasskeyIdentitySnapshot> =
        passkeyController.loadMatchingPasskeyIdentities(rpId, allowedCredentialIds)

    /** Call after consent, inside the provider's store lock and before the write ([createPasskey]). */
    @Throws(Exception::class)
    suspend fun hasExcludedPasskeyCredential(rpId: String, credentialIds: List<ByteArray>): Boolean =
        passkeyController.hasExcludedPasskeyCredential(rpId, credentialIds)

    /**
     * [recordId] is a [PasskeyIdentitySnapshot.recordId]; the system supplies [clientDataHash]. Null while locked, or
     * when the credential is gone or does not match [expectedRpId] / [expectedCredentialId]. Invalid requests and
     * signing errors throw.
     */
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

    /**
     * Attaches a new passkey ("none" attestation) to the login [cipherRecordId] (`accountId|cipherId`). Null when
     * the login is gone or read-only, or saving fails; crypto errors throw.
     */
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

    private val recentsController by lazy { RecentsController(context) }

    /**
     * The Recents item rows as state-anchored [VaultListDelta]s — the SAME
     * background-delivered contract as [makeVaultListSession]'s
     * `observeListDelta`.
     */
    fun observeRecentsListDelta(
        onChange: (com.artemchep.keyguard.apple.vault.VaultListDelta) -> Unit,
    ): KeyguardCancellable = recentsController.observeRecentsListDelta(onChange)

    /** The Recents tab bar + current selection (on Main). */
    fun observeRecentsTabs(
        onChange: (RecentsTabsSnapshot) -> Unit,
    ): KeyguardCancellable = recentsController.observeRecentsTabs(onChange)

    /** Live TOTP codes keyed by item id, pushed on Main once per second; empty while locked. */
    fun observeRecentsTotp(
        onChange: (Map<String, TotpFieldSnapshot>) -> Unit,
    ): KeyguardCancellable = recentsController.observeRecentsTotp(onChange)

    fun setRecentsTab(key: String) = recentsController.setRecentsTab(key)

    fun observeUnlock(
        onChange: (UnlockSnapshot) -> Unit,
    ): KeyguardCancellable = authController.observeUnlock(onChange)

    /** Report the unlock screen's visibility; biometric, YubiKey and FIDO2 prompts only appear while it is visible. */
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

    /**
     * [slot] is 1 or 2. With [provision], enabling first writes a fresh secret to the slot; [overwrite] allows
     * replacing an already configured one.
     */
    fun setYubiKeyUnlock(value: Boolean, slot: Int, provision: Boolean, overwrite: Boolean) =
        authController.setYubiKeyUnlock(value, slot, provision, overwrite)

    fun observeSetup(
        onChange: (SetupSnapshot) -> Unit,
    ): KeyguardCancellable = authController.observeSetup(onChange)

    /** Report the setup screen's visibility; its biometric prompt only appears while it is visible. */
    fun setSetupScreenVisible(visible: Boolean) = authController.setSetupScreenVisible(visible)

    fun setSetupPassword(text: String) = authController.setSetupPassword(text)

    fun setSetupCrashlytics(enabled: Boolean) = authController.setSetupCrashlytics(enabled)

    fun setSetupBiometric(enabled: Boolean) = authController.setSetupBiometric(enabled)

    fun submitSetup() = authController.submitSetup()

    private val messagesController by lazy { MessagesController(context) }

    /** [onMessage] runs on the main thread. */
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

    /**
     * Call on the main thread after [observeBitwardenLogin]'s `onTwofaRequired` fires; before that it only emits
     * [TwofaSnapshot.empty].
     */
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

    /** For a WebDAV location this re-opens the WebDAV sheet instead of a file picker. */
    fun pickKeePassDbFile() = keePassLoginController.pickKeePassDbFile()

    fun clearKeePassDbFile() = keePassLoginController.clearKeePassDbFile()

    fun pickKeePassKeyFile() = keePassLoginController.pickKeePassKeyFile()

    fun clearKeePassKeyFile() = keePassLoginController.clearKeePassKeyFile()

    fun setKeePassPassword(text: String) = keePassLoginController.setKeePassPassword(text)

    fun submitKeePassLogin() = keePassLoginController.submitKeePassLogin()

    fun setKeePassFilePickerRequestHandler(handler: ((KeePassFilePickerRequest) -> Unit)?) =
        keePassLoginController.setKeePassFilePickerRequestHandler(handler)

    /**
     * [uri] must be the original picked URL (not a copy) and [accessToken] the Base64 of its security-scoped bookmark,
     * created while access was active. The bookmark keeps the account's access to the file across relaunches.
     */
    fun resolveKeePassFilePicker(requestId: String, uri: String, name: String?, size: Long, accessToken: String?) =
        keePassLoginController.resolveKeePassFilePicker(requestId, uri, name, size, accessToken)

    fun cancelKeePassFilePicker(requestId: String) = keePassLoginController.cancelKeePassFilePicker(requestId)

    /** [id] is "url", "username" or "password". */
    fun setWebDavField(id: String, text: String) = keePassLoginController.setWebDavField(id, text)

    fun submitWebDavSettings() = keePassLoginController.submitWebDavSettings()

    /** The result arrives through [observeMessages], not in a snapshot. */
    fun testWebDavConnection() = keePassLoginController.testWebDavConnection()

    /** Call when the user closes the WebDAV sheet: nothing is applied, and the form keeps its previous location. */
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

    /** A null [itemId] or [accountId] shows the empty pane; the observer keeps running. */
    fun setDetailTarget(itemId: String?, accountId: String?) =
        cipherDetailController.setDetailTarget(itemId, accountId)

    fun invokeVaultAction(id: String) = cipherDetailController.invokeVaultAction(id)

    /** No-op when the shown cipher has no favourite action (e.g. read-only ciphers). */
    fun toggleVaultFavorite() = cipherDetailController.toggleVaultFavorite()

    // Navigation stack. Screens layered above the selection-driven root detail, one stack per scope.

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

    /** Live TOTP badges of the stacked cipher details, keyed by cipher id. Nothing arrives after cancellation. */
    fun observeNavStackTotp(
        onChange: (Map<String, VaultDetailTotpSnapshot>) -> Unit,
    ): KeyguardCancellable = navigationStackController.observeNavStackTotp(onChange)

    /** Pops the top screen of [scope]. Safe from any thread. */
    fun popScreen(scope: String) = navigationStackController.popScreen(scope)

    /** Pops every screen of [scope], back to its root. Safe from any thread. */
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

    // Dialogs

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

    /** Report the effective SwiftUI color scheme on every change; an open preview re-highlights its code with it. */
    fun setInterfaceDarkMode(isDark: Boolean) = dialogController.setInterfaceDarkMode(isDark)

    /**
     * Copies the previewed text through the app clipboard, so auto-clear applies. Named `invoke…`, not `copy…`:
     * Kotlin/Native exports `copy`-family names as `doCopy…`.
     */
    fun invokeAttachmentPreviewCopy() = dialogController.invokeAttachmentPreviewCopy()

    fun closeAttachmentPreview() = dialogController.closeAttachmentPreview()

    // Confirmation dialog

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

    fun closeConfirmation() = dialogController.closeConfirmation()

    fun setConfirmationFilePickerRequestHandler(handler: ((AddFilePickerRequest) -> Unit)?) =
        dialogController.setConfirmationFilePickerRequestHandler(handler)

    fun resolveConfirmationFilePicker(requestId: String, uri: String, name: String?, size: Long) =
        dialogController.resolveConfirmationFilePicker(requestId, uri, name, size)

    fun cancelConfirmationFilePicker(requestId: String) =
        dialogController.cancelConfirmationFilePicker(requestId)

    // Master-password re-prompt ("elevated access") dialog

    fun observeElevatedAccess(
        onChange: (ElevatedAccessSnapshot?) -> Unit,
    ): KeyguardCancellable = dialogController.observeElevatedAccess(onChange)

    fun setElevatedAccessPassword(text: String) =
        dialogController.setElevatedAccessPassword(text)

    fun triggerElevatedAccessBiometric() = dialogController.triggerElevatedAccessBiometric()

    fun triggerElevatedAccessYubiKey() = dialogController.triggerElevatedAccessYubiKey()

    fun confirmElevatedAccess() = dialogController.confirmElevatedAccess()

    fun closeElevatedAccess() = dialogController.closeElevatedAccess()

    // Service info dialog

    fun observeServiceInfo(
        onChange: (ServiceDirectoryDetailSnapshot?) -> Unit,
    ): KeyguardCancellable = dialogController.observeServiceInfo(onChange)

    fun closeServiceInfo() = dialogController.closeServiceInfo()

    // Data breach (HIBP) dialogs

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

    // Color picker dialog

    fun observeColorPicker(
        onChange: (ColorPickerSnapshot?) -> Unit,
    ): KeyguardCancellable = dialogController.observeColorPicker(onChange)

    fun selectColorPickerSwatch(id: String) = dialogController.selectColorPickerSwatch(id)

    fun confirmColorPicker() = dialogController.confirmColorPicker()

    fun closeColorPicker() = dialogController.closeColorPicker()

    // Collection / organization info dialog

    fun observeInfoDialog(
        onChange: (InfoDialogSnapshot?) -> Unit,
    ): KeyguardCancellable = dialogController.observeInfoDialog(onChange)

    fun closeInfoDialog() = dialogController.closeInfoDialog()

    // The add form's "Link to an item" cipher picker.

    fun observeCipherLinkPicker(
        onChange: (com.artemchep.keyguard.apple.dialog.CipherLinkPickerSnapshot?) -> Unit,
    ): KeyguardCancellable = dialogController.observeCipherLinkPicker(onChange)

    fun setCipherLinkPickerQuery(text: String) = dialogController.setCipherLinkPickerQuery(text)

    fun selectCipherLinkPickerItem(id: String) = dialogController.selectCipherLinkPickerItem(id)

    fun closeCipherLinkPicker() = dialogController.closeCipherLinkPicker()

    // Account picker ("Save to", "Copy to…") and folder picker ("Move to folder")

    fun observeAccountPicker(
        onChange: (AccountPickerSnapshot?) -> Unit,
    ): KeyguardCancellable = dialogController.observeAccountPicker(onChange)

    fun setAccountPickerNewFolderName(text: String) = dialogController.setAccountPickerNewFolderName(text)

    fun selectAccountPickerItem(key: String) = dialogController.selectAccountPickerItem(key)

    fun confirmAccountPicker() = dialogController.confirmAccountPicker()

    fun closeAccountPicker() = dialogController.closeAccountPicker()

    // Send list / detail

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

    /** Copies the Send's share link. */
    fun sendCopy() = sendDetailController.sendCopy()

    fun sendShare() = sendDetailController.sendShare()

    fun sendEdit() = sendDetailController.sendEdit()

    /** Emits [GeneratorSnapshot.empty] while the vault is locked; [onChange] runs on the main thread. */
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

    // GPG Tools (encrypt / decrypt / sign / verify)

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

    // In-form generators (add / edit form)

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

    /** Emits [GeneratorSnapshot.empty] while the vault is locked; [onChange] runs on the main thread. */
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
     * Only fields with an `AddTextFieldSnapshot.autofill` accept it; a no-op for others.
     */
    fun setAddFieldText(id: String, text: String) = addItemController.setAddFieldText(id, text)

    // Add / edit form (cipher and Send)

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
    // initializer doesn't forward-reference an uninitialized controller.
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
            dialogController.openUrl = navigationStackController::openUrl
            navigationStackController.bitwardenLoginArgsHandler = loginController::prepareBitwardenLogin
            // Late-bind the producers' interceptor to the stack so their full-screen
            // routes push instead of being dropped. Done here to break the
            // controller <-> stack reference cycle.
            val provider: (Scope) -> ((NavigationIntent) -> Boolean) = { sessionKoin ->
                navigationStackController.interceptor(sessionKoin)
            }
            quickCopyController.navigationInterceptorProvider = provider
            cipherDetailController.navigationInterceptorProvider = provider
            watchtowerController.navigationInterceptorProvider = provider
            generatorController.navigationInterceptorProvider = { sessionKoin ->
                val stackInterceptor = provider(sessionKoin)
                val interceptor: (NavigationIntent) -> Boolean = { intent ->
                    addItemController.interceptDateTimePicker(intent) || stackInterceptor(intent)
                }
                interceptor
            }
            autofillGeneratorController.navigationInterceptorProvider = provider
            gpgToolsController.navigationInterceptorProvider = provider
            sendDetailController.navigationInterceptorProvider = provider
            // Add / Send forms emit dialog routes; date / time pickers are caught by the controller itself.
            addItemController.navigationInterceptorProvider = { sessionKoin ->
                dialogController.navigationInterceptor(sessionKoin = sessionKoin)
            }
            sendListController.navigationInterceptorProvider = provider
        }
    }

    /** Creates an independent vault-list session per call; tear it down with [VaultListSession.close]. */
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
        navigationInterceptorProvider = { sessionKoin ->
            navigationStackController.interceptor(sessionKoin)
        },
        cipherFilterId = cipherFilterId,
    )

    fun pushSendDetail(sendId: String, accountId: String) =
        navigationStackController.pushSendDetail(sendId, accountId)

    /** Not restored on relaunch: account details have no route descriptor. */
    fun pushAccountDetail(accountId: String) =
        navigationStackController.pushAccountDetail(accountId)

    fun setEntryFeedbackMessage(instanceId: Long, text: String) =
        navigationStackController.setEntryFeedbackMessage(instanceId, text)

    fun submitEntryFeedback(instanceId: Long) =
        navigationStackController.submitEntryFeedback(instanceId)

    /** The standalone "Contact us" sheet; it never closes itself. Its `mailto:` opens via [setOpenSystemUrlHandler]. */
    fun observeFeedback(onChange: (FeedbackSnapshot) -> Unit): KeyguardCancellable =
        feedbackController.observeFeedback(
            onChange = onChange,
            openUrl = { url -> navigationStackController.openSystemUrl(url) },
        )

    fun setFeedbackMessage(text: String) = feedbackController.setFeedbackMessage(text)

    fun submitFeedback() = feedbackController.submitFeedback()

    fun setExportPassword(instanceId: Long, text: String) =
        navigationStackController.setExportPassword(instanceId, text)

    fun pushCollectionsList(accountId: String, organizationId: String?, title: String) =
        navigationStackController.pushCollectionsList(accountId, organizationId, title)

    @Throws(Exception::class)
    suspend fun addFolder(accountId: String, name: String) =
        foldersController.addFolder(accountId, name)

    // Account list / detail

    private val accountsController by lazy { AccountsController(context, dialogController) }

    init {
        if (context.runtime == KeyguardRuntime.APP) {
            // Late-bind here (a second init block) because accountsController is declared
            // after the navigation stack.
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

    private val emailRelayController by lazy { EmailRelayController(context) }

    private val generatorHistoryController by lazy { GeneratorHistoryController(context, dialogController) }

    fun observeGeneratorHistory(
        onChange: (GeneratorHistorySnapshot) -> Unit,
    ): KeyguardCancellable = generatorHistoryController.observeGeneratorHistory(onChange)

    fun invokeGeneratorHistoryItemAction(id: String) =
        generatorHistoryController.invokeGeneratorHistoryItemAction(id)

    fun invokeGeneratorHistoryOption(id: String) =
        generatorHistoryController.invokeGeneratorHistoryOption(id)

    fun invokeGeneratorHistorySelectionAction(id: String) =
        generatorHistoryController.invokeGeneratorHistorySelectionAction(id)

    fun toggleGeneratorHistorySelection(itemId: String) =
        generatorHistoryController.toggleGeneratorHistorySelection(itemId)

    fun clearGeneratorHistorySelection() =
        generatorHistoryController.clearGeneratorHistorySelection()

    private val readOnlyListsController by lazy { ReadOnlyListsController(context, dialogController) }

    /** History of the cipher [cipherId], or of every cipher when `null`. */
    fun observeSshAgentHistory(
        cipherId: String?,
        onChange: (SshAgentHistorySnapshot) -> Unit,
    ): KeyguardCancellable = readOnlyListsController.observeSshAgentHistory(cipherId, onChange)

    fun observePasswordHistory(
        itemId: String,
        onChange: (PasswordHistorySnapshot) -> Unit,
    ): KeyguardCancellable = readOnlyListsController.observePasswordHistory(itemId, onChange)

    fun invokePasswordHistoryItemAction(id: String) =
        readOnlyListsController.invokePasswordHistoryItemAction(id)

    fun invokePasswordHistorySelectionAction(id: String) =
        readOnlyListsController.invokePasswordHistorySelectionAction(id)

    fun invokePasswordHistoryAction(id: String) =
        readOnlyListsController.invokePasswordHistoryAction(id)

    fun togglePasswordHistorySelection(itemId: String) =
        readOnlyListsController.togglePasswordHistorySelection(itemId)

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

    fun invokeUrlBlockListItemAction(id: String) =
        readOnlyListsController.invokeUrlBlockListItemAction(id)

    fun invokeUrlBlockListSelectionAction(id: String) =
        readOnlyListsController.invokeUrlBlockListSelectionAction(id)

    fun invokeUrlBlockListPrimaryAction() =
        readOnlyListsController.invokeUrlBlockListPrimaryAction()

    fun toggleUrlBlockListSelection(itemId: String) =
        readOnlyListsController.toggleUrlBlockListSelection(itemId)

    fun clearUrlBlockListSelection() =
        readOnlyListsController.clearUrlBlockListSelection()

    fun observeUrlOverrideList(
        onChange: (UrlRuleListSnapshot) -> Unit,
    ): KeyguardCancellable = readOnlyListsController.observeUrlOverrideList(onChange)

    fun invokeUrlOverrideListItemAction(id: String) =
        readOnlyListsController.invokeUrlOverrideListItemAction(id)

    fun invokeUrlOverrideListSelectionAction(id: String) =
        readOnlyListsController.invokeUrlOverrideListSelectionAction(id)

    fun invokeUrlOverrideListPrimaryAction() =
        readOnlyListsController.invokeUrlOverrideListPrimaryAction()

    fun toggleUrlOverrideListSelection(itemId: String) =
        readOnlyListsController.toggleUrlOverrideListSelection(itemId)

    fun clearUrlOverrideListSelection() =
        readOnlyListsController.clearUrlOverrideListSelection()

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

    /** Null if [id] is unknown or its service is no longer registered. */
    suspend fun loadEmailRelay(id: String): EmailRelayFormSnapshot? =
        emailRelayController.loadEmailRelay(id)

    /** Creates a forwarder when [id] is null, otherwise updates it. [values] is keyed by schema field key. */
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

    // Watchtower settings

    fun observeWatchtowerSettings(
        onChange: (WatchtowerSettingsSnapshot) -> Unit,
    ): KeyguardCancellable = watchtowerController.observeWatchtowerSettings(onChange)

    fun setCheckPwnedPasswords(value: Boolean) = watchtowerController.setCheckPwnedPasswords(value)

    fun setCheckPwnedServices(value: Boolean) = watchtowerController.setCheckPwnedServices(value)

    fun setCheckTwoFa(value: Boolean) = watchtowerController.setCheckTwoFa(value)

    fun setCheckPasskeys(value: Boolean) = watchtowerController.setCheckPasskeys(value)

    /** A blank [token] clears the saved one; input that fails [isValidHibpApiToken] is ignored. */
    fun setHibpApiToken(token: String) = watchtowerController.setHibpApiToken(token)

    /** True for a blank [token] (clears it) or 32 hex characters, ignoring surrounding whitespace. */
    fun isValidHibpApiToken(token: String): Boolean = watchtowerController.isValidHibpApiToken(token)

    // Security settings. Items the common providers hide on Apple — screen-off lock, clipboard
    // auto-refresh, clipboard notifications, screenshots, clear vault (Wear-only) — are intentionally
    // not surfaced here.

    private val securityController by lazy { SecurityController(context) }

    fun observeSecuritySettings(
        onChange: (SecuritySettingsSnapshot) -> Unit,
    ): KeyguardCancellable = securityController.observeSecuritySettings(onChange)

    /** Enabling first shows the system biometric prompt; a cancelled or failed prompt leaves the setting off. */
    fun setBiometricUnlock(value: Boolean) = securityController.setBiometricUnlock(value)

    fun setVaultPersist(value: Boolean) = securityController.setVaultPersist(value)

    fun setVaultLockAfterReboot(value: Boolean) = securityController.setVaultLockAfterReboot(value)

    fun setVaultLockTimeout(optionId: String) = securityController.setVaultLockTimeout(optionId)

    fun setBiometricTimeout(optionId: String) = securityController.setBiometricTimeout(optionId)

    fun setClipboardAutoClear(optionId: String) = securityController.setClipboardAutoClear(optionId)

    fun setConcealFields(value: Boolean) = securityController.setConcealFields(value)

    fun setWebsiteIcons(value: Boolean) = securityController.setWebsiteIcons(value)

    fun setGravatar(value: Boolean) = securityController.setGravatar(value)

    // AutoFill settings

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

    // Subscriptions / in-app purchases

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


    // SSH agent settings

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

    /** Saves the pending filters; on success the `onClose` of [observeSshAgentFilters] fires. */
    fun saveSshAgentFilters() = sshAgentController.saveSshAgentFilters()

    fun resetSshAgentFilters() = sshAgentController.resetSshAgentFilters()

    // Change master password

    private val changePasswordController by lazy { ChangePasswordController(context, authPromptHost) }

    /** [onClose] fires on the main thread once the password has changed; dismiss the sheet then. */
    fun observeChangePassword(
        onChange: (ChangePasswordSnapshot) -> Unit,
        onClose: () -> Unit,
    ): KeyguardCancellable = changePasswordController.observeChangePassword(onChange, onClose)

    fun setChangePasswordCurrent(text: String) = changePasswordController.setChangePasswordCurrent(text)

    fun setChangePasswordNew(text: String) = changePasswordController.setChangePasswordNew(text)

    fun setChangePasswordBiometric(enabled: Boolean) = changePasswordController.setChangePasswordBiometric(enabled)

    fun submitChangePassword() = changePasswordController.submitChangePassword()

    // Development settings. Debug overrides are unavailable in Release builds.

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

    // Appearance settings. Option ids are variant names ("system" for the default, locale tag for
    // locales). Keep-screen-on is applied by iOS; Android app icons are intentionally not surfaced.

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

    /** [onChange] runs on the main thread. */
    fun observeAppPreferences(
        onChange: (AppPreferencesSnapshot) -> Unit,
    ): KeyguardCancellable = appearanceController.observeAppPreferences(onChange)

    /** Invokes [onMinimize] after every clipboard copy made while "Minimize after copying" is on. */
    fun observeMinimizeOnCopy(
        onMinimize: () -> Unit,
    ): KeyguardCancellable = appearanceController.observeMinimizeOnCopy(onMinimize)

    // Navigation items

    private val navItemsController by lazy { NavItemsController(context, dialogController) }

    /**
     * Observes the resolved top-level navigation sections the SwiftUI shell
     * renders (macOS sidebar / iOS tab bar). Emits the empty snapshot while
     * locked; Swift falls back to the default built-in sections.
     */
    fun observeNavItems(
        onChange: (NavItemsSnapshot) -> Unit,
    ): KeyguardCancellable = navItemsController.observeNavItems(onChange)

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

    // Automatic backups

    private val backupsController by lazy { BackupsController(context, addItemController) }

    fun observeBackupSettings(
        onChange: (BackupSettingsSnapshot) -> Unit,
    ): KeyguardCancellable = backupsController.observeBackupSettings(onChange)

    fun setBackupIncludeAttachments(value: Boolean) = backupsController.setBackupIncludeAttachments(value)

    /**
     * Starts a setup draft from the saved configuration, secrets included. Draft edits are saved only when
     * [enableBackup] verifies the destination.
     */
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

    /** Verifies and saves the draft. Only a `setupSaveRevision` bump signals success; status updates never do. */
    fun enableBackup() = backupsController.enableBackup()

    fun triggerBackupNow() = backupsController.triggerBackupNow()

    fun setBackupRetention(maxSnapshots: Int) = backupsController.setBackupRetention(maxSnapshots)

    fun disableBackup() = backupsController.disableBackup()
}
