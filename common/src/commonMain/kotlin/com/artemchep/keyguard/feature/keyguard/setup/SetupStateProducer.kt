package com.artemchep.keyguard.feature.keyguard.setup

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.getOrElse
import arrow.core.identity
import arrow.core.some
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.model.BiometricAuthPrompt
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.usecase.MeasureMasterKdfUseCase
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.auth.common.SwitchFieldModel
import com.artemchep.keyguard.feature.auth.common.TextFieldHandle
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.common.textFieldHandle
import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.auth.common.util.validatedPassword as validateMasterPassword
import com.artemchep.keyguard.feature.loading.LoadingTask
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.masterkdf.MasterKdfFormHandle
import com.artemchep.keyguard.feature.masterkdf.masterKdfFormHandle
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.platform.LeBiometricCipher
import com.artemchep.keyguard.platform.crashlyticsIsEnabled
import com.artemchep.keyguard.platform.crashlyticsSetEnabled
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.compose.currentKoinScope

private const val DEFAULT_PASSWORD = ""
private const val DEFAULT_BIOMETRIC = false
private const val DEFAULT_CRASHLYTICS = false
private const val DEFAULT_KDF_EXPANDED = false

private const val KEY_KDF = "kdf"
private const val KEY_KDF_EXPANDED = "kdf.expanded"

@Composable
fun setupScreenState(
    createVaultWithMasterPassword: VaultState.Create.WithPassword,
    createVaultWithMasterPasswordAndBiometric: VaultState.Create.WithBiometric?,
): Loadable<SetupState> = with(currentKoinScope()) {
    setupScreenState(
        createVaultWithMasterPassword = createVaultWithMasterPassword,
        createVaultWithMasterPasswordAndBiometric = createVaultWithMasterPasswordAndBiometric,
        measureMasterKdf = get(),
    )
}

@Composable
fun setupScreenState(
    createVaultWithMasterPassword: VaultState.Create.WithPassword,
    createVaultWithMasterPasswordAndBiometric: VaultState.Create.WithBiometric?,
    measureMasterKdf: MeasureMasterKdfUseCase,
): Loadable<SetupState> = produceScreenState<Loadable<SetupState>>(
    key = "setup",
    initial = Loadable.Loading,
    args = arrayOf(
        createVaultWithMasterPassword,
        createVaultWithMasterPasswordAndBiometric,
    ),
) {
    setupStateProducer(
        createVaultWithMasterPassword = createVaultWithMasterPassword,
        createVaultWithMasterPasswordAndBiometric = createVaultWithMasterPasswordAndBiometric,
        measureMasterKdf = measureMasterKdf,
    )
}

suspend fun RememberStateFlowScope.setupStateProducer(
    createVaultWithMasterPassword: VaultState.Create.WithPassword,
    createVaultWithMasterPasswordAndBiometric: VaultState.Create.WithBiometric?,
    measureMasterKdf: MeasureMasterKdfUseCase,
): Flow<Loadable<SetupState>> {
    val executor = screenExecutor()

    val biometricPromptSink = EventFlow<BiometricAuthPrompt>()

    val biometricSink = mutablePersistedFlow("biometric") { DEFAULT_BIOMETRIC }
    val crashlyticsSink = mutablePersistedFlow("crashlytics") {
        crashlyticsIsEnabled()
            ?: DEFAULT_CRASHLYTICS
    }

    val passwordHandle = textFieldHandle("password", initial = DEFAULT_PASSWORD)

    // Only one key derivation runs at a time: a test does not start
    // while the vault is being created, and the other way round.
    val kdfForm = masterKdfFormHandle(
        prefix = KEY_KDF,
        initial = MasterKdf.Argon2id.DEFAULT,
        measureMasterKdf = measureMasterKdf,
        isBusyFlow = executor.isExecutingFlow,
    )
    val kdfExpandedSink = mutablePersistedFlow(KEY_KDF_EXPANDED) { DEFAULT_KDF_EXPANDED }
    val onKdfExpandedChange: (Boolean) -> Unit = kdfExpandedSink::value::set
    // The error does not depend on the entered
    // values, so translate it once.
    val kdfInvalidText = translate(Res.string.setup_kdf_invalid_text)

    val actions = persistentListOf(
        FlatItemAction(
            id = "setup.advanced",
            icon = Icons.Outlined.Tune,
            title = Res.string.advanced_options.wrap(),
            onClick = {
                onKdfExpandedChange(true)
            },
        ),
    )

    // Clear the password field when the screen
    // moves into background. Goes through the command path so the UI
    // edit buffer adopts the cleared text unconditionally.
    launchUi {
        try {
            awaitCancellation()
        } finally {
            passwordHandle.setText(DEFAULT_PASSWORD)
        }
    }

    val createVaultByMasterPasswordFn = createVaultWithMasterPassword
        .let {
            CreateVaultWithPassword(executor, it)
        }
    val createVaultWithMasterPasswordAndBiometricFn = createVaultWithMasterPasswordAndBiometric
        ?.let {
            CreateVaultWithBiometric(executor, it)
        }

    val createVaultWithPasswordAction = createVaultWithPasswordAction(
        createVaultFn = createVaultByMasterPasswordFn,
        passwordHandle = passwordHandle,
        kdfForm = kdfForm,
        crashlyticsSink = crashlyticsSink,
    )

    val biometricStateFlow = biometricStateFlow(
        biometricSink = biometricSink,
        // True if the device is capable of performing a
        // biometric authentication.
        hasBiometric = createVaultWithMasterPasswordAndBiometric != null,
    )
    return combine(
        passwordHandle.sink
            .map { cell -> cell to validateMasterPassword(cell.text) },
        crashlyticsSink,
        biometricStateFlow,
        executor.isExecutingFlow,
        combine(kdfForm.stateFlow, kdfExpandedSink, ::Pair),
    ) { (passwordCell, validatedPassword), crashlytics, biometric, taskIsExecuting, (kdf, kdfExpanded) ->
        val validationError = (validatedPassword as? Validated.Failure)?.error
        val canCreateVault = validationError == null &&
                kdf.kdf != null &&
                !kdf.test.isRunning &&
                !taskIsExecuting
        val state = SetupState(
            biometric = biometric,
            kdf = kdf,
            kdfExpanded = kdfExpanded,
            onKdfExpandedChange = onKdfExpandedChange,
            kdfError = kdfInvalidText.takeIf { kdf.kdf == null },
            password = TextFieldModel.of(
                cell = passwordCell,
                handle = passwordHandle,
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
            actions = actions,
            onCreateVault = if (canCreateVault) {
                // If the biometric is checked & possible, then ask user to
                // confirm his identity.
                if (createVaultWithMasterPasswordAndBiometricFn != null && biometric?.checked == true) {
                    createVaultWithBiometricAction(
                        createVaultFn = createVaultWithMasterPasswordAndBiometricFn,
                        passwordHandle = passwordHandle,
                        kdfForm = kdfForm,
                        crashlyticsSink = crashlyticsSink,
                        biometricPromptSink = biometricPromptSink,
                    )
                } else {
                    createVaultWithPasswordAction
                }
            } else {
                null
            },
        )
        Loadable.Ok(state)
    }
}

/**
 * Creates the vault with the password, reading the input when the user
 * submits: the UI may still hold a callback of an older state.
 */
private fun RememberStateFlowScope.createVaultWithPasswordAction(
    createVaultFn: CreateVaultWithPassword,
    passwordHandle: TextFieldHandle,
    kdfForm: MasterKdfFormHandle,
    crashlyticsSink: MutableStateFlow<Boolean>,
): () -> Unit = fun() {
    val password = passwordHandle.sink.value.text
    val kdf = kdfForm.currentKdf()
        ?: return
    val crashlytics = crashlyticsSink.value
    screenScope.launch {
        val currentPassword = validateMasterPassword(password)
        if (currentPassword !is Validated.Success) return@launch
        // Only one key derivation runs at a time.
        if (kdfForm.isTestingFlow.value) return@launch
        createVaultFn(currentPassword.model, kdf, crashlytics)
    }
}

/**
 * Asks the user to confirm their identity, then creates the vault
 * with the password and the biometric unlock.
 */
private fun RememberStateFlowScope.createVaultWithBiometricAction(
    createVaultFn: CreateVaultWithBiometric,
    passwordHandle: TextFieldHandle,
    kdfForm: MasterKdfFormHandle,
    crashlyticsSink: MutableStateFlow<Boolean>,
    biometricPromptSink: EventFlow<BiometricAuthPrompt>,
): () -> Unit {
    var promptWrapper: Option<BiometricAuthPrompt?> = None
    var promptKey: Pair<String, MasterKdf.Argon2id>? = null
    val promptMutex by lazy {
        Mutex()
    }

    // An action should trigger the biometric prompt upon
    // execution.
    return fun() {
        val password = passwordHandle.sink.value.text
        val kdf = kdfForm.currentKdf()
            ?: return
        screenScope.launch {
            val currentPassword = validateMasterPassword(password)
            if (currentPassword !is Validated.Success) return@launch
            // Only one key derivation runs at a time.
            if (kdfForm.isTestingFlow.value) return@launch
            // Get or create the prompt and remember
            // it for the future invocations.
            val prompt = promptMutex.withLock {
                val key = currentPassword.model to kdf
                if (promptKey != key) {
                    promptWrapper = None
                    promptKey = key
                }
                promptWrapper.getOrElse {
                    createPromptOrNull(
                        createVaultWithMasterPasswordAndBiometricFn = createVaultFn,
                        password = currentPassword.model,
                        kdf = kdf,
                        // The vault is created after the prompt, so
                        // read the latest choice then.
                        getCrashlytics = crashlyticsSink::value,
                    )
                }.also {
                    promptWrapper = it.some()
                }
            }
            // Send it down the sink
            prompt?.let(biometricPromptSink::emit)
        }
    }
}

private suspend fun createPromptOrNull(
    createVaultWithMasterPasswordAndBiometricFn: CreateVaultWithBiometric,
    password: String,
    kdf: MasterKdf.Argon2id,
    getCrashlytics: () -> Boolean,
): BiometricAuthPrompt? = run {
    val executor = createVaultWithMasterPasswordAndBiometricFn.executor
    val createVault = {
        createVaultWithMasterPasswordAndBiometricFn(password, kdf, getCrashlytics())
    }
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
    val executor: LoadingTask,
    private val getCreateIo: (String, MasterKdf.Argon2id) -> IO<Unit>,
) {
    // Create from vault state options
    constructor(
        executor: LoadingTask,
        options: VaultState.Create.WithPassword,
    ) : this(
        executor = executor,
        getCreateIo = options.getCreateIo,
    )

    operator fun invoke(
        password: String,
        kdf: MasterKdf.Argon2id,
        crashlytics: Boolean,
    ) {
        val io = ioEffect {
            crashlyticsSetEnabled(crashlytics)
            getCreateIo(password, kdf).bind()
        }
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
    getCreateIo: (String, MasterKdf.Argon2id) -> IO<Unit>,
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
