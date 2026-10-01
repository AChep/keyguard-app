package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.feature.fido2.asFido2AppException
import com.artemchep.keyguard.common.exception.Readable
import com.artemchep.keyguard.common.exception.YubiKeyAuthCanceledException
import com.artemchep.keyguard.common.io.bind
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
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.model.invokeAction
import com.artemchep.keyguard.apple.throttleLatest
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

/**
 * Byte length of the random challenge generated when enrolling YubiKey unlock
 * (matches the shared Android `settingYubiKeyUnlockProvider`). The challenge is
 * stored and replayed verbatim at unlock, so the length only matters at enroll.
 */
private const val YUBIKEY_CHALLENGE_LENGTH = 32

/** Byte length of the HMAC-SHA1 secret written when provisioning a slot. */
private const val YUBIKEY_SECRET_LENGTH = 20

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

    private val unlockPromptHostActive = MutableStateFlow(false)

    fun setUnlockScreenVisible(visible: Boolean) {
        unlockPromptHostActive.value = visible
    }

    fun setUnlockPassword(text: String) {
        latestUnlockState?.password?.onChange?.invoke(text)
    }

    fun submitUnlock() {
        latestUnlockState?.unlockVaultByMasterPassword?.invoke()
    }

    fun triggerUnlockBiometric() {
        latestUnlockState?.biometric?.onClick?.invoke()
    }

    fun invokeUnlockAction(id: String) {
        latestUnlockActionHandlers.invokeAction(id)
    }

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
            title = textResource(Res.string.yubikey_error_title, leContext)
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

    fun setSetupPassword(text: String) {
        latestSetupState?.password?.onChange?.invoke(text)
    }

    fun setSetupCrashlytics(enabled: Boolean) {
        latestSetupState?.crashlytics?.onChange?.invoke(enabled)
    }

    fun setSetupBiometric(enabled: Boolean) {
        latestSetupState?.biometric?.onChange?.invoke(enabled)
    }

    fun submitSetup() {
        latestSetupState?.onCreateVault?.invoke()
    }
}
