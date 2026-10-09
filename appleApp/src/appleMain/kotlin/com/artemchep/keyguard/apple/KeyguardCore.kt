package com.artemchep.keyguard.apple

import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.apple.account.AccountDetailSession
import com.artemchep.keyguard.apple.account.AccountListSnapshot
import com.artemchep.keyguard.apple.account.AccountsController
import com.artemchep.keyguard.apple.account.SyncStatusSnapshot
import com.artemchep.keyguard.apple.add.AddDatePickerRequest
import com.artemchep.keyguard.apple.add.AddEditFormRequest
import com.artemchep.keyguard.apple.add.AddFilePickerRequest
import com.artemchep.keyguard.apple.add.AddFormActions
import com.artemchep.keyguard.apple.add.AddFormSession
import com.artemchep.keyguard.apple.add.AddItemController
import com.artemchep.keyguard.apple.add.AddItemFormSnapshot
import com.artemchep.keyguard.apple.auth.AuthController
import com.artemchep.keyguard.apple.auth.AuthPromptHost
import com.artemchep.keyguard.apple.auth.KeePassLoginSession
import com.artemchep.keyguard.apple.auth.BitwardenLoginSession
import com.artemchep.keyguard.apple.auth.MessageSnapshot
import com.artemchep.keyguard.apple.auth.MessagesController
import com.artemchep.keyguard.apple.auth.MasterPasswordSession
import com.artemchep.keyguard.apple.auth.UnlockOptionsSnapshot
import com.artemchep.keyguard.apple.billing.SubscriptionsController
import com.artemchep.keyguard.apple.billing.SubscriptionsSnapshot
import com.artemchep.keyguard.apple.core.AppLifecycleController
import com.artemchep.keyguard.apple.core.KeyguardRuntime
import com.artemchep.keyguard.apple.core.AgentFiltersSession
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.ListSession
import com.artemchep.keyguard.apple.generator.GeneratorHistorySnapshot
import com.artemchep.keyguard.apple.lists.PasswordHistorySnapshot
import com.artemchep.keyguard.apple.lists.UrlRuleListSnapshot
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.KeyguardScenePhase
import com.artemchep.keyguard.apple.core.KeyguardVaultStatus
import com.artemchep.keyguard.apple.core.NavigationStackController
import com.artemchep.keyguard.apple.core.ScreenEntrySnapshot
import com.artemchep.keyguard.apple.core.ListNavigationOrigin
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
import com.artemchep.keyguard.apple.generator.GeneratorSession
import com.artemchep.keyguard.apple.generator.GeneratorHistoryController
import com.artemchep.keyguard.apple.generator.WordlistController
import com.artemchep.keyguard.apple.generator.WordlistActionRequestSnapshot
import com.artemchep.keyguard.apple.gpgtools.GpgToolsSession
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
import com.artemchep.keyguard.apple.lists.ReadOnlyListsController
import com.artemchep.keyguard.apple.lists.SshAgentHistorySession
import com.artemchep.keyguard.apple.onboarding.OnboardingController
import com.artemchep.keyguard.apple.model.TotpFieldSnapshot
import com.artemchep.keyguard.apple.send.SendDetailController
import com.artemchep.keyguard.apple.send.SendDetailSession
import com.artemchep.keyguard.apple.send.SendListController
import com.artemchep.keyguard.apple.send.SendListSession
import com.artemchep.keyguard.apple.settings.AboutTeamSnapshot
import com.artemchep.keyguard.apple.settings.AppPreferencesSnapshot
import com.artemchep.keyguard.apple.settings.DebugSettingsController
import com.artemchep.keyguard.apple.settings.DebugSettingsSnapshot
import com.artemchep.keyguard.apple.settings.AppearanceController
import com.artemchep.keyguard.apple.settings.AppearanceSettingsSnapshot
import com.artemchep.keyguard.apple.settings.BackupSettingsSnapshot
import com.artemchep.keyguard.apple.settings.BackupSetupSession
import com.artemchep.keyguard.apple.settings.BackupsController
import com.artemchep.keyguard.apple.settings.ChangePasswordController
import com.artemchep.keyguard.apple.settings.ChangePasswordSession
import com.artemchep.keyguard.apple.settings.DataSafetyItemSnapshot
import com.artemchep.keyguard.apple.settings.NavItemsController
import com.artemchep.keyguard.apple.settings.NavItemsSettingsSnapshot
import com.artemchep.keyguard.apple.settings.NavItemsSnapshot
import com.artemchep.keyguard.apple.settings.AutofillSettingsController
import com.artemchep.keyguard.apple.settings.AutofillSettingsSnapshot
import com.artemchep.keyguard.apple.settings.FeedbackController
import com.artemchep.keyguard.apple.settings.FeedbackSession
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
import com.artemchep.keyguard.apple.gpgagent.GpgAgentHistorySnapshot
import com.artemchep.keyguard.apple.gpgagent.GpgAgentRequestSnapshot
import com.artemchep.keyguard.apple.gpgagent.GpgAgentSettingsSnapshot
import com.artemchep.keyguard.apple.gpgagent.GpgAgentStatusSnapshot
import com.artemchep.keyguard.apple.sshagent.SshAgentController
import com.artemchep.keyguard.apple.sshagent.SshAgentRequestSnapshot
import com.artemchep.keyguard.apple.sshagent.SshAgentSettingsSnapshot
import com.artemchep.keyguard.apple.sshagent.SshAgentStatusSnapshot
import com.artemchep.keyguard.apple.vault.CipherDetailController
import com.artemchep.keyguard.apple.vault.CipherDetailSession
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
import com.artemchep.keyguard.apple.vault.RecentsSession
import com.artemchep.keyguard.apple.vault.VaultDetailTotpSnapshot
import com.artemchep.keyguard.apple.vault.VaultListSession
import com.artemchep.keyguard.apple.vault.VaultListSessionConfig
import com.artemchep.keyguard.apple.vault.toArgs
import com.artemchep.keyguard.apple.watchtower.WatchtowerController
import com.artemchep.keyguard.apple.watchtower.WatchtowerSession
import com.artemchep.keyguard.apple.watchtower.WatchtowerSettingsSnapshot
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
     * [RecentsSession.observe].
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
    fun setAddAccountHandler(handler: ((String, String?) -> Unit)?) =
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

    fun makeGpgAgentFiltersSession(): AgentFiltersSession =
        gpgAgentController.makeGpgAgentFiltersSession()

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

    fun makeRecentsSession(): RecentsSession = recentsController.makeSession()

    fun observeUnlockOptions(
        onChange: (UnlockOptionsSnapshot) -> Unit,
    ): KeyguardCancellable = authController.observeUnlockOptions(onChange)

    /** Report the unlock screen's visibility; biometric, YubiKey and FIDO2 prompts only appear while it is visible. */
    fun setUnlockScreenVisible(visible: Boolean) = authController.setUnlockScreenVisible(visible)

    fun makeUnlockSession(): MasterPasswordSession = authController.makeUnlockSession()

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

    fun makeSetupSession(): MasterPasswordSession = authController.makeSetupSession()

    private val messagesController by lazy { MessagesController(context) }

    /** [onMessage] runs on the main thread. */
    fun observeMessages(
        onMessage: (MessageSnapshot) -> Unit,
    ): KeyguardCancellable = messagesController.observeMessages(onMessage)

    private val loginRequests = com.artemchep.keyguard.apple.auth.LoginRequests()

    /** A dialog host owned by one form presentation, so form dialogs never outlive or cross forms. */
    private fun newFormDialogController() = DialogController(context, authPromptHost).apply {
        openUrl = navigationStackController::openUrl
    }

    /** Form dialogs first; everything else goes to the navigation stack. */
    private fun DialogController.formNavigationInterceptor(sessionKoin: Scope): (NavigationIntent) -> Boolean {
        val local = navigationInterceptor(sessionKoin, formDialogsOnly = true)
        val stack = navigationStackController.interceptor(sessionKoin)
        return { local(it) || stack(it) }
    }

    fun makeBitwardenLoginSession(requestId: String? = null): BitwardenLoginSession {
        val dialogs = newFormDialogController()
        return BitwardenLoginSession(
            ctx = context,
            fido2PromptHost = fido2PromptHost,
            args = loginRequests.args(requestId),
            dialogs = com.artemchep.keyguard.apple.dialog.FormDialogsSession(dialogs),
            onDispose = { loginRequests.remove(requestId) },
        ).apply {
            navigationInterceptorProvider = { sessionKoin -> dialogs.formNavigationInterceptor(sessionKoin) }
        }
    }

    fun makeKeePassLoginSession(): KeePassLoginSession = KeePassLoginSession(context)

    private val cipherDetailController by lazy { CipherDetailController(context, dialogController) }

    /** Creates an independent detail presentation; cancel its observation or call [CipherDetailSession.close]. */
    fun makeCipherDetailSession(itemId: String, accountId: String): CipherDetailSession =
        cipherDetailController.makeSession(itemId, accountId)

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

    fun makeSendListSession(): SendListSession = sendListController.makeSession()

    fun makeSendDetailSession(itemId: String, accountId: String): SendDetailSession =
        sendDetailController.makeSession(itemId, accountId)

    fun makeGeneratorSession(): GeneratorSession = generatorController.makeSession()

    fun makeGpgToolsSession(): GpgToolsSession = GpgToolsSession(context).apply {
        navigationInterceptorProvider = { navigationStackController.interceptor(it) }
    }

    // Add / edit form (cipher and Send)

    private val addItemController by lazy { AddItemController(context) }

    fun makeAddCipherSession(
        type: String,
        name: String? = null,
        username: String? = null,
        password: String? = null,
    ): AddFormSession = makeAddFormSession { controller, publish, onClose ->
        controller.observeAddCipher(type, name, username, password, onClose, publish)
    }

    fun makeAddSendSession(type: String): AddFormSession = makeAddFormSession { controller, publish, onClose ->
        controller.observeAddSend(type, onClose, publish)
    }

    fun makeEditCipherSession(requestId: String): AddFormSession =
        makeAddFormSession(requestId) { controller, publish, onClose ->
            controller.observeEditCipher(requestId, onClose, publish)
        }

    fun makeEditSendSession(requestId: String): AddFormSession =
        makeAddFormSession(requestId) { controller, publish, onClose ->
            controller.observeEditSend(requestId, onClose, publish)
        }

    private fun makeAddFormSession(
        requestId: String? = null,
        observe: (
            AddItemController,
            (AddItemFormSnapshot, AddFormActions) -> Unit,
            () -> Unit,
        ) -> KeyguardCancellable,
    ): AddFormSession {
        val dialogs = newFormDialogController()
        val controller = AddItemController(context).apply {
            navigationInterceptorProvider = { sessionKoin -> dialogs.formNavigationInterceptor(sessionKoin) }
        }
        if (requestId != null) addItemController.copyEditRequest(requestId, controller)
        return AddFormSession(
            ctx = context,
            controller = controller,
            dialogController = dialogs,
            subscribe = { publish, onClose -> observe(controller, publish, onClose) },
            onDispose = { if (requestId != null) addItemController.clearEditForm(requestId) },
        )
    }

    fun setEditFormRequestHandler(handler: ((AddEditFormRequest) -> Unit)?) =
        addItemController.setEditFormRequestHandler(handler)

    fun setAddDatePickerRequestHandler(handler: ((AddDatePickerRequest) -> Unit)?) =
        addItemController.setAddDatePickerRequestHandler(handler)

    fun resolveAddDatePicker(requestId: String, year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        addItemController.resolveAddDatePicker(requestId, year, month, day, hour, minute)

    fun cancelAddDatePicker(requestId: String) = addItemController.cancelAddDatePicker(requestId)

    private val watchtowerController by lazy { WatchtowerController(context) }

    fun makeWatchtowerSession(): WatchtowerSession = watchtowerController.makeSession()

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
            navigationStackController.bitwardenLoginArgsHandler = loginRequests::put
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
            sendDetailController.navigationInterceptorProvider = provider
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
        openListCipher = navigationStackController::openListCipher,
    )

    fun openListSend(origin: ListNavigationOrigin, sendId: String, accountId: String) =
        navigationStackController.openListSend(origin, sendId, accountId)

    fun clearListDetail(origin: ListNavigationOrigin, detailInstanceId: Long) =
        navigationStackController.clearListDetail(origin, detailInstanceId)

    fun popToScreen(scope: String, instanceId: Long?) =
        navigationStackController.popToScreen(scope, instanceId)

    fun pushSendDetail(sendId: String, accountId: String) =
        navigationStackController.pushSendDetail(sendId, accountId)

    /** Not restored on relaunch: account details have no route descriptor. */
    fun pushAccountDetail(accountId: String) =
        navigationStackController.pushAccountDetail(accountId)

    fun setEntryFeedbackMessage(instanceId: Long, text: String) =
        navigationStackController.setEntryFeedbackMessage(instanceId, text)

    fun submitEntryFeedback(instanceId: Long) =
        navigationStackController.submitEntryFeedback(instanceId)

    /** Each native feedback presentation owns its draft. Sending opens the system mail composer. */
    fun makeFeedbackSession(): FeedbackSession = feedbackController.makeFeedbackSession(
        openUrl = { url -> navigationStackController.openSystemUrl(url) },
    )

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

    fun makeAccountDetailSession(accountId: String): AccountDetailSession =
        accountsController.makeDetailSession(accountId)

    fun invokeAccountListAction(id: String) = accountsController.invokeAccountListAction(id)

    private val emailRelayController by lazy { EmailRelayController(context) }

    private val generatorHistoryController by lazy { GeneratorHistoryController(context, dialogController) }

    fun makeGeneratorHistorySession(): ListSession<GeneratorHistorySnapshot> = generatorHistoryController.makeSession()

    private val readOnlyListsController by lazy { ReadOnlyListsController(context, dialogController) }

    /** History of the cipher [cipherId], or of every cipher when `null`. */
    fun makeSshAgentHistorySession(cipherId: String?): SshAgentHistorySession =
        readOnlyListsController.makeSshAgentHistorySession(cipherId)

    fun makePasswordHistorySession(itemId: String): ListSession<PasswordHistorySnapshot> =
        readOnlyListsController.makePasswordHistorySession(itemId)

    fun observeLicense(
        onChange: (LicenseListSnapshot) -> Unit,
    ): KeyguardCancellable = readOnlyListsController.observeLicense(onChange)

    fun observeLocalizationContributors(
        onChange: (LocalizationContributorsSnapshot) -> Unit,
    ): KeyguardCancellable = readOnlyListsController.observeLocalizationContributors(onChange)

    fun observeLogs(
        onChange: (LogsSnapshot) -> Unit,
    ): KeyguardCancellable = readOnlyListsController.observeLogs(onChange)

    fun makeUrlBlockListSession(): ListSession<UrlRuleListSnapshot> =
        readOnlyListsController.makeUrlBlockListSession()

    fun makeUrlOverrideListSession(): ListSession<UrlRuleListSnapshot> =
        readOnlyListsController.makeUrlOverrideListSession()

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

    /** True for a blank [token] (clears it) or a well-formed HIBP API token, ignoring surrounding whitespace. */
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

    fun makeSshAgentFiltersSession(): AgentFiltersSession =
        sshAgentController.makeSshAgentFiltersSession()

    // Change master password

    private val changePasswordController by lazy { ChangePasswordController(context, authPromptHost) }

    fun makeChangePasswordSession(): ChangePasswordSession = changePasswordController.makeChangePasswordSession()

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
    fun setWebDavTransactions(value: Boolean) = appearanceController.setWebDavTransactions(value)
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

    private val backupsController by lazy { BackupsController(context) }

    fun observeBackupSettings(
        onChange: (BackupSettingsSnapshot) -> Unit,
    ): KeyguardCancellable = backupsController.observeBackupSettings(onChange)

    /** Creates a presentation-owned draft. Changes persist only after successful destination verification. */
    fun makeBackupSetupSession(): BackupSetupSession = backupsController.makeBackupSetupSession()

    fun isValidBackupWebDavUrl(url: String): Boolean = backupsController.isValidBackupWebDavUrl(url)

    fun triggerBackupNow() = backupsController.triggerBackupNow()

    fun setBackupRetention(maxSnapshots: Int) = backupsController.setBackupRetention(maxSnapshots)

    fun disableBackup() = backupsController.disableBackup()
}
