package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.feature.fido2.asFido2AppException
import com.artemchep.keyguard.common.exception.Readable
import com.artemchep.keyguard.common.exception.YubiKeyAuthCanceledException
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.BiometricAuthException
import com.artemchep.keyguard.common.model.BiometricAuthPrompt
import com.artemchep.keyguard.common.model.BiometricAuthPromptSimple
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.model.YubiKeyAuthPrompt
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.usecase.ClearData
import com.artemchep.keyguard.common.usecase.DisableYubiKeyUnlock
import com.artemchep.keyguard.common.usecase.EnableYubiKeyUnlock
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.feature.keyguard.setup.SetupState
import com.artemchep.keyguard.feature.keyguard.setup.setupStateProducer
import com.artemchep.keyguard.feature.keyguard.unlock.UnlockState
import com.artemchep.keyguard.feature.keyguard.unlock.unlockStateProducer
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.apple.core.evaluateBiometrics
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.model.invokeAction
import com.artemchep.keyguard.apple.throttleLatest
import com.artemchep.keyguard.platform.LeBiometricCipherApple
import com.artemchep.keyguard.platform.LeContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

private const val YUBIKEY_CHALLENGE_LENGTH = 32
private const val YUBIKEY_SECRET_LENGTH = 20

/**
 * The vault create / unlock flow: the Setup and Unlock screens plus the
 * create/unlock one-shots and the native biometric (Touch ID) + YubiKey prompt
 * handling. Runs the shared [setupStateProducer] / [unlockStateProducer]
 * headlessly; biometric prompts resolve through the shared [evaluateBiometrics],
 * YubiKey prompts through the shared native YubiKey client.
 */
internal class AuthController(
    private val ctx: CoreContext,
    private val authPromptHost: AuthPromptHost,
    private val fido2PromptHost: Fido2PromptController,
) {
    private val clearData: ClearData by lazy { ctx.koin.get() }
    private val enableYubiKeyUnlock: EnableYubiKeyUnlock by lazy { ctx.koin.get() }
    private val disableYubiKeyUnlock: DisableYubiKeyUnlock by lazy { ctx.koin.get() }
    private val showMessage: ShowMessage by lazy { ctx.koin.get() }

    private var latestUnlockState: UnlockState? = null
    private var latestUnlockActionHandlers: Map<String, () -> Unit> = emptyMap()
    private var latestSetupState: SetupState? = null

    /**
     * Creates a brand-new vault protected by the given master [password]. When
     * [biometric] is true and supported, enrolls Touch ID as part of creation
     * (shows the system sheet, materializes the keychain cipher). A cancelled
     * prompt aborts silently.
     */
    suspend fun createVault(password: String, biometric: Boolean) {
        val state = ctx.currentState() as? VaultState.Create
            ?: error("Can not create a vault in the current state: ${ctx.currentState()::class.simpleName}")
        val withBiometric = state.createWithMasterPasswordAndBiometric
        if (biometric && withBiometric != null) {
            val cipher = withBiometric.getCipher().fold(
                ifLeft = { throw it },
                ifRight = { it },
            )
            val exception = evaluateBiometrics(
                reason = org.jetbrains.compose.resources.getString(Res.string.setup_biometric_auth_confirm_title),
            ) { context ->
                (cipher as LeBiometricCipherApple).materialize(context)
            }
            if (exception != null) {
                when (exception.code) {
                    BiometricAuthException.ERROR_USER_CANCELED,
                    BiometricAuthException.ERROR_CANCELED,
                    BiometricAuthException.ERROR_NEGATIVE_BUTTON -> return
                    else -> throw exception
                }
            }
            withBiometric.getCreateIo(password).invoke()
        } else {
            state.createWithMasterPassword.getCreateIo(password).invoke()
        }
    }

    /** True when a vault can be created with Touch ID enrollment. */
    suspend fun createVaultSupportsBiometric(): Boolean =
        (ctx.currentState() as? VaultState.Create)?.createWithMasterPasswordAndBiometric != null

    /** Unlocks an existing vault using the given master [password]. */
    suspend fun unlockVault(password: String) {
        when (val state = ctx.currentState()) {
            is VaultState.Unlock ->
                state.unlockWithMasterPassword.getCreateIo(password).invoke()

            else -> error("Can not unlock a vault in the current state: ${state::class.simpleName}")
        }
    }

    fun observeUnlock(
        onChange: (UnlockSnapshot) -> Unit,
    ): KeyguardCancellable {
        val leContext = ctx.koin.get<LeContext>()
        return ctx.launchObserver {
            ctx.unlockUseCase().collectLatest { state ->
                if (state is VaultState.Unlock) {
                    coroutineScope {
                        val stateFlow = ctx.koin.newHeadlessStateFlowScope("unlock", this)
                            .unlockStateProducer(
                                clearData = clearData,
                                unlockVaultByMasterPassword = state.unlockWithMasterPassword,
                                unlockVaultByBiometric = state.unlockWithBiometric,
                                unlockVaultByYubiKey = state.unlockWithYubiKey,
                                unlockVaultByFido2 = state.unlockWithFido2,
                                lockInfo = state.lockInfo,
                            )
                            .throttleLatest()
                            .shareIn(this, SharingStarted.Eagerly, replay = 1)

                        launch {
                            val sideEffects = stateFlow
                                .mapNotNull { it.getOrNull()?.sideEffects }
                                .first()
                            unlockPromptHostActive.collectLatest { active ->
                                if (active) {
                                    sideEffects.showBiometricPromptFlow.collect { prompt ->
                                        when (prompt) {
                                            is BiometricAuthPrompt ->
                                                authPromptHost.handleBiometricPrompt(
                                                    prompt,
                                                    reason = org.jetbrains.compose.resources.getString(
                                                        Res.string.unlock_biometric_auth_confirm_title,
                                                    ),
                                                )
                                            is BiometricAuthPromptSimple ->
                                                authPromptHost.handleBiometricPromptSimple(
                                                    prompt,
                                                    reason = org.jetbrains.compose.resources.getString(
                                                        Res.string.unlock_biometric_auth_confirm_title,
                                                    ),
                                                )
                                        }
                                    }
                                }
                            }
                        }

                        launch {
                            val sideEffects = stateFlow
                                .mapNotNull { it.getOrNull()?.sideEffects }
                                .first()
                            unlockPromptHostActive.collectLatest { active ->
                                if (active) {
                                    sideEffects.showYubiKeyPromptFlow.collect { prompt ->
                                        when (prompt) {
                                            is YubiKeyAuthPrompt -> authPromptHost.handleYubiKeyPrompt(prompt)
                                        }
                                    }
                                }
                            }
                        }

                        launch {
                            val effects = stateFlow.mapNotNull { it.getOrNull()?.sideEffects }.first()
                            unlockPromptHostActive.collectLatest { active ->
                                if (active) effects.showFido2PromptFlow.collectLatest(fido2PromptHost::handle)
                            }
                        }

                        stateFlow
                            .map { loadable ->
                                val unlockState = loadable.getOrNull()
                                val handlers = LinkedHashMap<String, () -> Unit>()
                                val actions = unlockState.toUnlockActionSnapshots(leContext, handlers)
                                Triple(unlockState, unlockState.toUnlockSnapshot(actions), handlers)
                            }
                            .collectOnMain { (unlockState, snapshot, handlers) ->
                                latestUnlockState = unlockState
                                latestUnlockActionHandlers = handlers
                                onChange(snapshot)
                            }
                    }
                } else {
                    ctx.publishOnMain {
                        latestUnlockState = null
                        latestUnlockActionHandlers = emptyMap()
                        onChange(UnlockSnapshot.empty)
                    }
                }
            }
        }
    }

    /**
     * True while the SwiftUI unlock screen is visible. Gates the prompt hosts in
     * [observeUnlock] so the system Touch ID sheet can never appear without the
     * unlock screen behind it.
     */
    private val unlockPromptHostActive = MutableStateFlow(false)

    fun setUnlockScreenVisible(visible: Boolean) {
        unlockPromptHostActive.value = visible
    }

    /** Writes [text] into the unlock password field. */
    fun setUnlockPassword(text: String) {
        latestUnlockState?.password?.onChange?.invoke(text)
    }

    /** Submits the current unlock password. */
    fun submitUnlock() {
        latestUnlockState?.unlockVaultByMasterPassword?.invoke()
    }

    /** Triggers the biometric unlock prompt, if the device supports it. */
    fun triggerUnlockBiometric() {
        latestUnlockState?.biometric?.onClick?.invoke()
    }

    /** Invokes an unlock-screen escape-hatch action (e.g. "Erase data") by id. */
    fun invokeUnlockAction(id: String) {
        latestUnlockActionHandlers.invokeAction(id)
    }

    /** Triggers the YubiKey unlock prompt, if the vault has a YubiKey factor. */
    fun triggerUnlockYubiKey() {
        latestUnlockState?.yubiKey?.onClick?.invoke()
    }

    fun triggerUnlockFido2() { latestUnlockState?.fido2?.onClick?.invoke() }

    @Suppress("TooGenericExceptionCaught") // Report enrollment failures through the application message bus.
    fun setFido2Unlock(value: Boolean) {
        ctx.scope.launch {
            try {
                val service = ctx.koin.get<com.artemchep.keyguard.common.usecase.impl.Fido2UnlockService>()
                if (value) {
                    fido2PromptHost.run { execute -> service.enroll(execute).bind() }
                } else {
                    service.disable().bind()
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                val readable = error.asFido2AppException() as Readable
                val leContext = ctx.koin.get<LeContext>()
                showMessage.copy(ToastMessage(type = ToastMessage.Type.ERROR,
                    title = textResource(readable.title, leContext), text = textResource(readable.text, leContext)))
            }
        }
    }

    /** Enables / disables YubiKey unlock, the create-side twin of setBiometricUnlock. */
    fun setYubiKeyUnlock(value: Boolean, slot: Int, provision: Boolean, overwrite: Boolean) {
        ctx.scope.launch(Dispatchers.Default) {
            val outcome = runCatching {
                if (value) {
                    require(slot == 1 || slot == 2) { "YubiKey slot must be 1 or 2." }
                    val cryptoGenerator = ctx.koin.get<CryptoGenerator>()
                    val challenge = cryptoGenerator.seed(YUBIKEY_CHALLENGE_LENGTH)
                    val response = if (provision) {
                        val secret = cryptoGenerator.seed(YUBIKEY_SECRET_LENGTH)
                        try {
                            authPromptHost.runYubiKeyProvision(slot, secret, challenge, overwrite)
                        } finally {
                            secret.fill(0)
                        }
                    } else {
                        authPromptHost.runYubiKeyChallengeResponse(slot, challenge)
                    }
                    try {
                        enableYubiKeyUnlock(slot, challenge, response).bind()
                    } finally {
                        response.fill(0)
                    }
                } else {
                    disableYubiKeyUnlock().bind()
                }
            }
            outcome.exceptionOrNull()?.let { postYubiKeyEnrollError(it) }
        }
    }

    private suspend fun postYubiKeyEnrollError(e: Throwable) {
        if (e is YubiKeyAuthCanceledException) return
        val leContext = ctx.koin.get<LeContext>()
        val title: String
        val text: String?
        if (e is Readable) {
            title = textResource(e.title, leContext)
            text = textResource(e.text, leContext)
        } else {
            title = "Failed to enable YubiKey unlock"
            text = e.message
        }
        showMessage.copy(
            ToastMessage(
                type = ToastMessage.Type.ERROR,
                title = title,
                text = text,
            ),
        )
    }

    fun observeSetup(
        onChange: (SetupSnapshot) -> Unit,
    ): KeyguardCancellable {
        return ctx.launchObserver {
            ctx.unlockUseCase().collectLatest { state ->
                if (state is VaultState.Create) {
                    coroutineScope {
                        val stateFlow = ctx.koin.newHeadlessStateFlowScope("setup", this)
                            .setupStateProducer(
                                createVaultWithMasterPassword = state.createWithMasterPassword,
                                createVaultWithMasterPasswordAndBiometric = state.createWithMasterPasswordAndBiometric,
                            )
                            .throttleLatest()
                            .shareIn(this, SharingStarted.Eagerly, replay = 1)

                        launch {
                            val sideEffects = stateFlow
                                .mapNotNull { it.getOrNull()?.sideEffects }
                                .first()
                            setupPromptHostActive.collectLatest { active ->
                                if (active) {
                                    sideEffects.showBiometricPromptFlow.collect { prompt ->
                                        authPromptHost.handleBiometricPrompt(
                                            prompt,
                                            reason = org.jetbrains.compose.resources.getString(
                                                Res.string.setup_biometric_auth_confirm_title,
                                            ),
                                        )
                                    }
                                }
                            }
                        }

                        stateFlow
                            .map { loadable ->
                                val setupState = loadable.getOrNull()
                                setupState to setupState.toSetupSnapshot()
                            }
                            .collectOnMain { (setupState, snapshot) ->
                                latestSetupState = setupState
                                onChange(snapshot)
                            }
                    }
                } else {
                    ctx.publishOnMain {
                        latestSetupState = null
                        onChange(SetupSnapshot.empty)
                    }
                }
            }
        }
    }

    private val setupPromptHostActive = MutableStateFlow(false)

    fun setSetupScreenVisible(visible: Boolean) {
        setupPromptHostActive.value = visible
    }

    /** Writes [text] into the create-vault password field. */
    fun setSetupPassword(text: String) {
        latestSetupState?.password?.onChange?.invoke(text)
    }

    /** Toggles the "send crash reports" opt-in on the create screen. */
    fun setSetupCrashlytics(enabled: Boolean) {
        latestSetupState?.crashlytics?.onChange?.invoke(enabled)
    }

    /** Toggles Touch ID enrollment for the vault being created. */
    fun setSetupBiometric(enabled: Boolean) {
        latestSetupState?.biometric?.onChange?.invoke(enabled)
    }

    /** Submits the create-vault form. */
    fun submitSetup() {
        latestSetupState?.onCreateVault?.invoke()
    }
}
