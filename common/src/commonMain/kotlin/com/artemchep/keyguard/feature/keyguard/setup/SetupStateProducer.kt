package com.artemchep.keyguard.feature.keyguard.setup

import androidx.compose.runtime.Composable
import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.getOrElse
import arrow.core.identity
import arrow.core.partially1
import arrow.core.some
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.model.BiometricAuthPrompt
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.auth.common.SwitchFieldModel
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.auth.common.util.validatedPassword
import com.artemchep.keyguard.feature.loading.LoadingTask
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.platform.LeBiometricCipher
import com.artemchep.keyguard.platform.crashlyticsIsEnabled
import com.artemchep.keyguard.platform.crashlyticsSetEnabled
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val DEFAULT_PASSWORD = ""
private const val DEFAULT_BIOMETRIC = false
private const val DEFAULT_CRASHLYTICS = false

@Composable
fun setupScreenState(
    createVaultWithMasterPassword: VaultState.Create.WithPassword,
    createVaultWithMasterPasswordAndBiometric: VaultState.Create.WithBiometric?,
): Loadable<SetupState> = produceScreenState<Loadable<SetupState>>(
    key = "setup",
    initial = Loadable.Loading,
    args = arrayOf(
        createVaultWithMasterPassword,
        createVaultWithMasterPasswordAndBiometric,
    ),
) {
    val executor = screenExecutor()

    val biometricPromptSink = EventFlow<BiometricAuthPrompt>()

    val biometricSink = mutablePersistedFlow("biometric") { DEFAULT_BIOMETRIC }
    val crashlyticsSink = mutablePersistedFlow("crashlytics") {
        crashlyticsIsEnabled()
            ?: DEFAULT_CRASHLYTICS
    }

    val passwordSink = mutablePersistedFlow("password") { DEFAULT_PASSWORD }
    val passwordState = mutableComposeState(passwordSink)

    val createVaultByMasterPasswordFn = createVaultWithMasterPassword
        .let {
            CreateVaultWithPassword(executor, it)
        }
    val createVaultWithMasterPasswordAndBiometricFn = createVaultWithMasterPasswordAndBiometric
        ?.let {
            CreateVaultWithBiometric(executor, it)
        }

    val biometricStateFlow = biometricStateFlow(
        biometricSink = biometricSink,
        // True if the device is capable of performing a
        // biometric authentication.
        hasBiometric = createVaultWithMasterPasswordAndBiometric != null,
    )
    combine(
        passwordSink.validatedPassword(this),
        crashlyticsSink,
        biometricStateFlow,
        executor.isExecutingFlow,
    ) { validatedPassword, crashlytics, biometric, taskIsExecuting ->
        val validationError = (validatedPassword as? Validated.Failure)?.error
        val canCreateVault = validationError == null && !taskIsExecuting
        val state = SetupState(
            biometric = biometric,
            password = TextFieldModel2.of(
                state = passwordState,
                validated = validatedPassword,
            ),
            crashlytics = SwitchFieldModel(
                checked = crashlytics,
                onChange = crashlyticsSink::value::set,
            ),
            sideEffects = SetupState.SideEffects(
                showBiometricPromptFlow = biometricPromptSink,
            ),
            isLoading = taskIsExecuting,
            onCreateVault = if (canCreateVault) {
                crashlyticsSetEnabled(crashlytics)
                // If the biometric is checked & possible, then ask user to
                // confirm his identity.
                if (createVaultWithMasterPasswordAndBiometricFn != null && biometric?.checked == true) {
                    var promptWrapper: Option<BiometricAuthPrompt?> = None
                    val promptMutex by lazy {
                        Mutex()
                    }

                    // An action should trigger the biometric prompt upon
                    // execution.
                    fun() {
                        screenScope.launch {
                            // Get or create the prompt and remember
                            // it for the future invocations.
                            val prompt = promptMutex.withLock {
                                promptWrapper.getOrElse {
                                    createPromptOrNull(
                                        executor = executor,
                                        createVaultWithMasterPasswordAndBiometricFn = createVaultWithMasterPasswordAndBiometricFn,
                                        password = validatedPassword.model,
                                    )
                                }.also {
                                    promptWrapper = it.some()
                                }
                            }
                            // Send it down the sink
                            prompt?.let(biometricPromptSink::emit)
                        }
                    }
                } else {
                    createVaultByMasterPasswordFn.partially1(validatedPassword.model)
                }
            } else {
                null
            },
        )
        Loadable.Ok(state)
    }
}

private suspend fun createPromptOrNull(
    executor: LoadingTask,
    createVaultWithMasterPasswordAndBiometricFn: CreateVaultWithBiometric,
    password: String,
): BiometricAuthPrompt? = run {
    val createVault = createVaultWithMasterPasswordAndBiometricFn
        .partially1(password)
    // Creating a cipher may fail with:
    // Fatal Exception:
    //     java.security.ProviderException
    //   Keystore key generation failed
    val cipher = createVaultWithMasterPasswordAndBiometricFn.getCipher()
        .fold(
            ifLeft = { e ->
                val fakeIo = ioRaise<Unit>(e)
                executor.execute(fakeIo)
                return@run null
            },
            ifRight = ::identity,
        )
    BiometricAuthPrompt(
        title = TextHolder.Res(Res.string.setup_biometric_auth_confirm_title),
        cipher = cipher,
        requireConfirmation = createVaultWithMasterPasswordAndBiometricFn.requireConfirmation,
        onComplete = { result ->
            result.fold(
                ifLeft = { exception ->
                    val io = ioRaise<Unit>(exception)
                    executor.execute(io)
                },
                ifRight = {
                    createVault()
                },
            )
        },
    )
}

private open class CreateVaultWithPassword(
    private val executor: LoadingTask,
    private val getCreateIo: (String) -> IO<Unit>,
) : (String) -> Unit {
    // Create from vault state options
    constructor(
        executor: LoadingTask,
        options: VaultState.Create.WithPassword,
    ) : this(
        executor = executor,
        getCreateIo = options.getCreateIo,
    )

    override fun invoke(password: String) {
        val io = getCreateIo(password)
        executor.execute(io, password)
    }
}

private class CreateVaultWithBiometric(
    executor: LoadingTask,
    /**
     * A getter for the cipher to pass to the biometric
     * authentication.
     */
    val getCipher: suspend () -> Either<Throwable, LeBiometricCipher>,
    getCreateIo: (String) -> IO<Unit>,
    val requireConfirmation: Boolean,
) : CreateVaultWithPassword(executor, getCreateIo) {
    // Create from vault state options
    constructor(
        executor: LoadingTask,
        options: VaultState.Create.WithBiometric,
    ) : this(
        executor = executor,
        getCipher = options.getCipher,
        getCreateIo = options.getCreateIo,
        requireConfirmation = options.requireConfirmation,
    )
}

private fun biometricStateFlow(
    biometricSink: MutableStateFlow<Boolean>,
    hasBiometric: Boolean,
) = if (hasBiometric) {
    val initialState = SetupState.Biometric(
        onChange = biometricSink::value::set,
    )
    biometricSink.map { isChecked ->
        initialState.copy(checked = isChecked)
    }
} else {
    val disabledState = null
    flowOf(disabledState)
}
