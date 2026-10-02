package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.feature.fido2.asFido2AppException
import com.artemchep.keyguard.common.exception.Readable
import com.artemchep.keyguard.common.exception.YubiKeyAuthCanceledException
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.BiometricAuthPrompt
import com.artemchep.keyguard.common.model.BiometricAuthPromptSimple
import com.artemchep.keyguard.common.model.PureBiometricAuthPrompt
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.model.YubiKeyAuthPrompt
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.usecase.ClearData
import com.artemchep.keyguard.common.usecase.DisableYubiKeyUnlock
import com.artemchep.keyguard.common.usecase.EnableYubiKeyUnlock
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.feature.keyguard.setup.setupStateProducer
import com.artemchep.keyguard.feature.keyguard.unlock.UnlockState
import com.artemchep.keyguard.feature.keyguard.unlock.unlockStateProducer
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.throttleLatest
import com.artemchep.keyguard.platform.LeContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
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
    // Shared executor rejects simultaneous unlock/create operations across form owners.
    private val executor by lazy {
        ctx.koin.newHeadlessStateFlowScope("authentication", ctx.backgroundScope).screenExecutor()
    }
    private val prompts = AuthPromptCoordinator()

    private fun formScope(key: String, scope: CoroutineScope): RememberStateFlowScope {
        val delegate = ctx.koin.newHeadlessStateFlowScope(key, scope)
        return object : RememberStateFlowScope by delegate {
            override fun screenExecutor() = executor
        }
    }

    private fun perform(action: (() -> Unit)?) {
        if (!executor.isExecutingFlow.value && !prompts.isActive.value) action?.invoke()
    }

    fun makeUnlockSession(): MasterPasswordSession = MasterPasswordSession { publish ->
        ctx.launchObserver {
            ctx.unlockUseCase().collectLatest { state ->
                // Invalidate old callbacks before starting the next vault-state producer.
                ctx.publishOnMain { publish(MasterPasswordSnapshot.empty, MasterPasswordActions()) }
                if (state is VaultState.Unlock) coroutineScope {
                    formScope("unlock", this).unlockStateProducer(
                        clearData = clearData,
                        unlockVaultByMasterPassword = state.unlockWithMasterPassword,
                        unlockVaultByBiometric = null,
                        unlockVaultByYubiKey = null,
                    ).throttleLatest().combine(prompts.isActive) { loadable, busy ->
                        val form = loadable.getOrNull()
                        form.toMasterPasswordSnapshot(busy) to MasterPasswordActions(
                            setPassword = form?.password?.onChange,
                            submit = { perform(form?.unlockVaultByMasterPassword) },
                        )
                    }.collectOnMain { (snapshot, actions) -> publish(snapshot, actions) }
                }
            }
        }
    }

    fun observeUnlockOptions(
        onChange: (UnlockOptionsSnapshot) -> Unit,
    ): KeyguardCancellable {
        val leContext = ctx.koin.get<LeContext>()
        return ctx.launchObserver {
            ctx.unlockUseCase().collectLatest { state ->
                if (state is VaultState.Unlock) {
                    coroutineScope {
                        val stateFlow = formScope("unlock.options", this)
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
                                        handleBiometricPrompt(
                                            prompt,
                                            org.jetbrains.compose.resources.getString(
                                                Res.string.unlock_biometric_auth_confirm_title,
                                            ),
                                        )
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
                                        prompts.run {
                                            if (!executor.isExecutingFlow.value) {
                                                when (prompt) {
                                                    is YubiKeyAuthPrompt -> authPromptHost.handleYubiKeyPrompt(prompt)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        launch {
                            val effects = stateFlow.mapNotNull { it.getOrNull()?.sideEffects }.first()
                            unlockPromptHostActive.collectLatest { active ->
                                if (active) effects.showFido2PromptFlow.collectLatest { prompt ->
                                    prompts.run(onRejected = prompt::cancel) { fido2PromptHost.handle(prompt) }
                                }
                            }
                        }

                        stateFlow
                            .combine(prompts.isActive) { loadable, busy ->
                                val unlockState = loadable.getOrNull()
                                val handlers = LinkedHashMap<String, () -> Unit>()
                                val actions = unlockState.toUnlockActionSnapshots(leContext, handlers)
                                val snapshot = unlockState.toUnlockOptionsSnapshot(actions)
                                Triple(unlockState, snapshot.copy(isLoading = snapshot.isLoading || busy), handlers)
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
                        onChange(UnlockOptionsSnapshot.empty)
                    }
                }
            }
        }
    }

    private suspend fun handleBiometricPrompt(prompt: PureBiometricAuthPrompt, reason: String) {
        prompts.run {
            if (!executor.isExecutingFlow.value) {
                when (prompt) {
                    is BiometricAuthPrompt -> authPromptHost.handleBiometricPrompt(prompt, reason)
                    is BiometricAuthPromptSimple -> authPromptHost.handleBiometricPromptSimple(prompt, reason)
                }
            }
        }
    }

    private val unlockPromptHostActive = MutableStateFlow(false)

    fun setUnlockScreenVisible(visible: Boolean) {
        unlockPromptHostActive.value = visible
    }

    fun triggerUnlockBiometric() {
        perform(latestUnlockState?.biometric?.onClick)
    }

    fun invokeUnlockAction(id: String) {
        perform(latestUnlockActionHandlers[id])
    }

    fun triggerUnlockYubiKey() {
        perform(latestUnlockState?.yubiKey?.onClick)
    }

    fun triggerUnlockFido2() { perform(latestUnlockState?.fido2?.onClick) }

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

    fun makeSetupSession(): MasterPasswordSession = MasterPasswordSession { publish ->
        ctx.launchObserver {
            ctx.unlockUseCase().collectLatest { state ->
                ctx.publishOnMain { publish(MasterPasswordSnapshot.empty, MasterPasswordActions()) }
                if (state is VaultState.Create) coroutineScope {
                    val stateFlow = formScope("setup", this)
                        .setupStateProducer(
                            createVaultWithMasterPassword = state.createWithMasterPassword,
                            createVaultWithMasterPasswordAndBiometric = state.createWithMasterPasswordAndBiometric,
                        )
                        .throttleLatest()
                        .shareIn(this, SharingStarted.Eagerly, replay = 1)
                    launch {
                        val effects = stateFlow.mapNotNull { it.getOrNull()?.sideEffects }.first()
                        effects.showBiometricPromptFlow.collect { prompt ->
                            handleBiometricPrompt(
                                prompt,
                                org.jetbrains.compose.resources.getString(
                                    Res.string.setup_biometric_auth_confirm_title,
                                ),
                            )
                        }
                    }
                    stateFlow.combine(prompts.isActive) { loadable, busy ->
                        val form = loadable.getOrNull()
                        form.toMasterPasswordSnapshot(busy) to MasterPasswordActions(
                            setPassword = form?.password?.onChange,
                            setBiometric = form?.biometric?.onChange,
                            setCrashlytics = form?.crashlytics?.onChange,
                            submit = { perform(form?.onCreateVault) },
                        )
                    }.collectOnMain { (snapshot, actions) -> publish(snapshot, actions) }
                }
            }
        }
    }
}
