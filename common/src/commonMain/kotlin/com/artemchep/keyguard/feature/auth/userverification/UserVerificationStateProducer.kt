package com.artemchep.keyguard.feature.auth.userverification

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.exception.YubiKeyAuthCanceledException
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.BiometricAuthException
import com.artemchep.keyguard.common.model.BiometricAuthPromptSimple
import com.artemchep.keyguard.common.model.BiometricStatus
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.PureBiometricAuthPrompt
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.model.YubiKeyAuthPrompt
import com.artemchep.keyguard.common.usecase.BiometricStatusUseCase
import com.artemchep.keyguard.common.usecase.ConfirmAccessByPasswordUseCase
import com.artemchep.keyguard.common.usecase.ConfirmAccessByYubiKeyRequest
import com.artemchep.keyguard.common.usecase.ConfirmAccessByYubiKeyUseCase
import com.artemchep.keyguard.common.usecase.GetBiometricRequireConfirmation
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.auth.common.textFieldHandle
import com.artemchep.keyguard.feature.auth.common.util.validatedPassword
import com.artemchep.keyguard.feature.keyguard.unlock.UnlockState
import com.artemchep.keyguard.feature.loading.LoadingTask
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

private const val DEFAULT_PASSWORD = ""

/**
 * How long the prompt streams stay alive after the last collector goes away, so a
 * brief recomposition does not drop a prompt that is already on screen.
 */
private const val PROMPT_SHARE_TIMEOUT_MS = 5000L

@Composable
fun produceUserVerificationState(
    onAuthenticated: () -> Unit,
): UserVerificationState = with(localDI().direct) {
    produceUserVerificationState(
        onAuthenticated = onAuthenticated,
        biometricStatusUseCase = instance(),
        getBiometricRequireConfirmation = instance(),
        confirmAccessByPasswordUseCase = instance(),
        confirmAccessByYubiKeyUseCase = instance(),
        windowCoroutineScope = instance(),
    )
}

@Composable
fun produceUserVerificationState(
    onAuthenticated: () -> Unit,
    biometricStatusUseCase: BiometricStatusUseCase,
    getBiometricRequireConfirmation: GetBiometricRequireConfirmation,
    confirmAccessByPasswordUseCase: ConfirmAccessByPasswordUseCase,
    confirmAccessByYubiKeyUseCase: ConfirmAccessByYubiKeyUseCase,
    windowCoroutineScope: WindowCoroutineScope,
): UserVerificationState = produceScreenState(
    key = "user_verification",
    initial = UserVerificationState(),
    args = arrayOf(
        windowCoroutineScope,
    ),
) {
    userVerificationStateProducer(
        onAuthenticated = onAuthenticated,
        biometricStatusUseCase = biometricStatusUseCase,
        getBiometricRequireConfirmation = getBiometricRequireConfirmation,
        confirmAccessByPasswordUseCase = confirmAccessByPasswordUseCase,
        confirmAccessByYubiKeyUseCase = confirmAccessByYubiKeyUseCase,
    )
}

suspend fun RememberStateFlowScope.userVerificationStateProducer(
    onAuthenticated: () -> Unit,
    biometricStatusUseCase: BiometricStatusUseCase,
    getBiometricRequireConfirmation: GetBiometricRequireConfirmation,
    confirmAccessByPasswordUseCase: ConfirmAccessByPasswordUseCase,
    confirmAccessByYubiKeyUseCase: ConfirmAccessByYubiKeyUseCase,
): Flow<UserVerificationState> {
    val executor = screenExecutor()
    val passwordHandle = textFieldHandle("password", initial = DEFAULT_PASSWORD)

    val biometric = buildBiometricArm(
        executor = executor,
        biometricStatusUseCase = biometricStatusUseCase,
        getBiometricRequireConfirmation = getBiometricRequireConfirmation,
        onAuthenticated = onAuthenticated,
    )
    val yubiKey = buildYubiKeyArm(
        executor = executor,
        confirmAccessByYubiKeyUseCase = confirmAccessByYubiKeyUseCase,
        onAuthenticated = onAuthenticated,
    )
    val sideEffects = UnlockState.SideEffects(
        showBiometricPromptFlow = biometric.promptFlow,
        showYubiKeyPromptFlow = yubiKey.promptFlow,
    )

    return combine(
        passwordHandle.sink
            .map { cell -> cell to validatedPassword(cell.text) },
        executor.isExecutingFlow,
    ) { (passwordCell, validatedPassword), taskExecuting ->
        val content = UserVerificationState.Content(
            biometric = biometric.state(taskExecuting),
            yubiKey = yubiKey.state(taskExecuting),
            sideEffects = sideEffects,
            password = TextFieldModel.of(
                cell = passwordCell,
                handle = passwordHandle,
                validated = validatedPassword,
            ),
            isLoading = taskExecuting,
            onVerify = createVerifyOrNull(
                validatedPassword = validatedPassword,
                taskExecuting = taskExecuting,
                executor = executor,
                confirmAccessByPasswordUseCase = confirmAccessByPasswordUseCase,
                onAuthenticated = onAuthenticated,
            ),
        )
        UserVerificationState(
            content = Loadable.Ok(content),
        )
    }
}

/**
 * One hardware verification method: the prompt stream the screen subscribes to, and
 * the button state for it.
 *
 * `null` [enabled] means the method is unavailable on this device, which is the only
 * case where the button is absent rather than merely greyed out.
 */
private class BiometricArm(
    val promptFlow: Flow<PureBiometricAuthPrompt>,
    private val enabled: UserVerificationState.Biometric?,
) {
    fun state(taskExecuting: Boolean): UserVerificationState.Biometric? = when {
        enabled == null -> null
        taskExecuting -> DISABLED
        else -> enabled
    }

    private companion object {
        val DISABLED = UserVerificationState.Biometric(onClick = null)
    }
}

/** The YubiKey twin of [BiometricArm]. */
private class YubiKeyArm(
    val promptFlow: Flow<YubiKeyAuthPrompt>,
    private val enabled: UserVerificationState.YubiKey?,
) {
    fun state(taskExecuting: Boolean): UserVerificationState.YubiKey? = when {
        enabled == null -> null
        taskExecuting -> DISABLED
        else -> enabled
    }

    private companion object {
        val DISABLED = UserVerificationState.YubiKey(onClick = null)
    }
}

private suspend fun RememberStateFlowScope.buildBiometricArm(
    executor: LoadingTask,
    biometricStatusUseCase: BiometricStatusUseCase,
    getBiometricRequireConfirmation: GetBiometricRequireConfirmation,
    onAuthenticated: () -> Unit,
): BiometricArm {
    val prompt = createBiometricPromptOrNull(
        executor = executor,
        biometricStatusUseCase = biometricStatusUseCase,
        getBiometricRequireConfirmation = getBiometricRequireConfirmation,
        onAuthenticated = onAuthenticated,
    )
    val sink = EventFlow<PureBiometricAuthPrompt>()
    val promptFlow = sink
        // Automatically emit the prompt on first show
        // of the user interface.
        .onStart {
            if (prompt != null) {
                emit(prompt)
            }
        }
        .shareIn(screenScope, SharingStarted.WhileSubscribed(PROMPT_SHARE_TIMEOUT_MS))
    return BiometricArm(
        promptFlow = promptFlow,
        enabled = prompt?.let { available ->
            UserVerificationState.Biometric(
                onClick = {
                    sink.emit(available)
                },
            )
        },
    )
}

private suspend fun RememberStateFlowScope.buildYubiKeyArm(
    executor: LoadingTask,
    confirmAccessByYubiKeyUseCase: ConfirmAccessByYubiKeyUseCase,
    onAuthenticated: () -> Unit,
): YubiKeyArm {
    val request = confirmAccessByYubiKeyUseCase()
        .attempt()
        .bind()
        .getOrNull()
    val sink = EventFlow<YubiKeyAuthPrompt>()
    val promptFlow = sink
        .shareIn(screenScope, SharingStarted.WhileSubscribed(PROMPT_SHARE_TIMEOUT_MS))
    return YubiKeyArm(
        promptFlow = promptFlow,
        enabled = request?.let { available ->
            UserVerificationState.YubiKey(
                onClick = {
                    val prompt = createYubiKeyPrompt(
                        request = available,
                        executor = executor,
                        onAuthenticated = onAuthenticated,
                    )
                    sink.emit(prompt)
                },
            )
        },
    )
}

/**
 * The password path: `null` while the password is invalid or another attempt is
 * already running, which is what keeps the button disabled.
 */
private fun RememberStateFlowScope.createVerifyOrNull(
    validatedPassword: Validated<String>,
    taskExecuting: Boolean,
    executor: LoadingTask,
    confirmAccessByPasswordUseCase: ConfirmAccessByPasswordUseCase,
    onAuthenticated: () -> Unit,
): (() -> Unit)? {
    val error = (validatedPassword as? Validated.Failure)?.error
    if (error != null || taskExecuting) {
        return null
    }
    return {
        val io = confirmAccessByPasswordUseCase(validatedPassword.model)
            .effectTap { success ->
                if (success) {
                    onAuthenticated()
                } else {
                    val message = ToastMessage(
                        title = translate(Res.string.error_incorrect_password),
                        type = ToastMessage.Type.ERROR,
                    )
                    message(message)
                }
            }
        executor.execute(io)
    }
}

private suspend fun createBiometricPromptOrNull(
    executor: LoadingTask,
    biometricStatusUseCase: BiometricStatusUseCase,
    getBiometricRequireConfirmation: GetBiometricRequireConfirmation,
    onAuthenticated: () -> Unit,
): PureBiometricAuthPrompt? {
    val biometricStatus = biometricStatusUseCase()
        .toIO()
        .attempt()
        .bind()
        .getOrNull()
    if (biometricStatus !is BiometricStatus.Available) {
        return null
    }
    val requireConfirmation = getBiometricRequireConfirmation()
        .first()
    return createPrompt(
        executor = executor,
        requireConfirmation = requireConfirmation,
        fn = onAuthenticated,
    )
}

private fun createYubiKeyPrompt(
    request: ConfirmAccessByYubiKeyRequest,
    executor: LoadingTask,
    onAuthenticated: () -> Unit,
): YubiKeyAuthPrompt = YubiKeyAuthPrompt(
    slot = request.slot,
    challenge = request.challenge,
    onComplete = { result ->
        result.fold(
            ifLeft = { exception ->
                if (exception is YubiKeyAuthCanceledException) {
                    return@fold
                }

                val io = ioRaise<Unit>(exception)
                executor.execute(io)
            },
            ifRight = { response ->
                val io = request.confirm(response)
                    .effectTap {
                        onAuthenticated()
                    }
                executor.execute(io)
            },
        )
    },
)

private fun createPrompt(
    executor: LoadingTask,
    requireConfirmation: Boolean,
    fn: () -> Unit,
): PureBiometricAuthPrompt = BiometricAuthPromptSimple(
    title = TextHolder.Res(Res.string.elevatedaccess_biometric_auth_confirm_title),
    requireConfirmation = requireConfirmation,
    onComplete = { result ->
        result.fold(
            ifLeft = { exception ->
                when (exception.code) {
                    BiometricAuthException.ERROR_CANCELED,
                    BiometricAuthException.ERROR_USER_CANCELED,
                    BiometricAuthException.ERROR_NEGATIVE_BUTTON,
                    -> return@fold
                }

                val io = ioRaise<Unit>(exception)
                executor.execute(io)
            },
            ifRight = {
                fn.invoke()
            },
        )
    },
)
