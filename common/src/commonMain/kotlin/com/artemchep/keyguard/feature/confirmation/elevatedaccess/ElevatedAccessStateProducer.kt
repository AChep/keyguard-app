package com.artemchep.keyguard.feature.confirmation.elevatedaccess

import androidx.compose.runtime.Composable
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
import com.artemchep.keyguard.common.usecase.BiometricStatusUseCase
import com.artemchep.keyguard.common.usecase.ConfirmAccessByPasswordUseCase
import com.artemchep.keyguard.common.usecase.GetBiometricRequireConfirmation
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.auth.common.util.validatedPassword
import com.artemchep.keyguard.feature.keyguard.unlock.UnlockState
import com.artemchep.keyguard.feature.loading.LoadingTask
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

private const val DEFAULT_PASSWORD = ""

@Composable
fun produceElevatedAccessState(
    transmitter: RouteResultTransmitter<ElevatedAccessResult>,
): ElevatedAccessState = with(localDI().direct) {
    produceElevatedAccessState(
        transmitter = transmitter,
        biometricStatusUseCase = instance(),
        getBiometricRequireConfirmation = instance(),
        confirmAccessByPasswordUseCase = instance(),
        windowCoroutineScope = instance(),
    )
}

@Composable
fun produceElevatedAccessState(
    transmitter: RouteResultTransmitter<ElevatedAccessResult>,
    biometricStatusUseCase: BiometricStatusUseCase,
    getBiometricRequireConfirmation: GetBiometricRequireConfirmation,
    confirmAccessByPasswordUseCase: ConfirmAccessByPasswordUseCase,
    windowCoroutineScope: WindowCoroutineScope,
): ElevatedAccessState = produceScreenState(
    key = "elevated_access",
    initial = ElevatedAccessState(),
    args = arrayOf(
        windowCoroutineScope,
    ),
) {
    val executor = screenExecutor()

    val passwordSink = mutablePersistedFlow("password") { DEFAULT_PASSWORD }
    val passwordState = mutableComposeState(passwordSink)

    val biometricPrompt = kotlin.run {
        val biometricStatus = biometricStatusUseCase()
            .toIO()
            .attempt()
            .bind()
            .getOrNull()
        when (biometricStatus) {
            is BiometricStatus.Available -> {
                val requireConfirmation = getBiometricRequireConfirmation()
                    .first()
                createPromptOrNull(
                    executor = executor,
                    requireConfirmation = requireConfirmation,
                    fn = {
                        navigatePopSelf()
                        transmitter(ElevatedAccessResult.Allow)
                    },
                )
            }

            else -> null
        }
    }

    val biometricPromptSink = EventFlow<PureBiometricAuthPrompt>()
    val biometricPromptFlow = biometricPromptSink
        // Automatically emit the prompt on first show
        // of the user interface.
        .onStart {
            if (biometricPrompt != null) {
                emit(biometricPrompt)
            }
        }
        .shareIn(screenScope, SharingStarted.WhileSubscribed(5000L))
    val biometricStateEnabled = biometricPrompt?.let { prompt ->
        ElevatedAccessState.Biometric(
            onClick = {
                biometricPromptSink.emit(prompt)
            },
        )
    }
    val biometricStateDisabled = biometricPrompt?.let { prompt ->
        ElevatedAccessState.Biometric(
            onClick = null,
        )
    }

    combine(
        passwordSink
            .validatedPassword(this),
        executor.isExecutingFlow,
    ) { validatedPassword, taskExecuting ->
        val error = (validatedPassword as? Validated.Failure)?.error
        val canCreateVault = error == null && !taskExecuting
        val content = ElevatedAccessState.Content(
            biometric = if (taskExecuting) {
                biometricStateDisabled
            } else {
                biometricStateEnabled
            },
            sideEffects = UnlockState.SideEffects(
                showBiometricPromptFlow = biometricPromptFlow,
            ),
            password = TextFieldModel2.of(
                state = passwordState,
                validated = validatedPassword,
            ),
            isLoading = taskExecuting,
        )
        ElevatedAccessState(
            content = Loadable.Ok(content),
            onDeny = {
                navigatePopSelf()
                transmitter(ElevatedAccessResult.Deny)
            },
            onConfirm = if (canCreateVault) {
                // lambda
                {
                    val io = confirmAccessByPasswordUseCase(validatedPassword.model)
                        .effectTap { success ->
                            if (success) {
                                navigatePopSelf()
                                transmitter(ElevatedAccessResult.Allow)
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
            } else {
                null
            },
        )
    }
}

private fun createPromptOrNull(
    executor: LoadingTask,
    requireConfirmation: Boolean,
    fn: () -> Unit,
): PureBiometricAuthPrompt = run {
    BiometricAuthPromptSimple(
        title = TextHolder.Res(Res.string.elevatedaccess_biometric_auth_confirm_title),
        text = TextHolder.Res(Res.string.elevatedaccess_biometric_auth_confirm_text),
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
}
