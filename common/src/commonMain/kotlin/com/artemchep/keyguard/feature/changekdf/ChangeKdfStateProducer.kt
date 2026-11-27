package com.artemchep.keyguard.feature.changekdf

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.BiometricAuthPrompt
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.usecase.GetBiometricRequireConfirmation
import com.artemchep.keyguard.common.usecase.MeasureMasterKdfUseCase
import com.artemchep.keyguard.common.usecase.UnlockUseCase
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.auth.common.TextFieldHandle
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.common.modelFlow
import com.artemchep.keyguard.feature.auth.common.textFieldHandle
import com.artemchep.keyguard.feature.auth.common.util.ValidationPassword
import com.artemchep.keyguard.feature.auth.common.util.validatePassword
import com.artemchep.keyguard.feature.auth.common.util.validatedPassword
import com.artemchep.keyguard.feature.changepassword.ChangePasswordAction
import com.artemchep.keyguard.feature.changepassword.ChangePasswordActions
import com.artemchep.keyguard.feature.changepassword.ChangePasswordState
import com.artemchep.keyguard.feature.changepassword.changePasswordBiometricFlow
import com.artemchep.keyguard.feature.changepassword.createChangePasswordActions
import com.artemchep.keyguard.feature.masterkdf.MasterKdfFormHandle
import com.artemchep.keyguard.feature.masterkdf.MasterKdfFormState
import com.artemchep.keyguard.feature.masterkdf.masterKdfFormHandle
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import org.koin.compose.currentKoinScope

private const val KEY_PASSWORD = "password"
private const val KEY_KDF = "kdf"

@Composable
fun changeKdfState(): Loadable<ChangeKdfState> = with(currentKoinScope()) {
    changeKdfState(
        unlockUseCase = get(),
        getBiometricRequireConfirmation = get(),
        measureMasterKdf = get(),
        // The change replaces the vault scope before recording success and closing this screen.
        windowCoroutineScope = getKoin().get<WindowCoroutineScope>(),
    )
}

@Composable
fun changeKdfState(
    unlockUseCase: UnlockUseCase,
    getBiometricRequireConfirmation: GetBiometricRequireConfirmation,
    measureMasterKdf: MeasureMasterKdfUseCase,
    windowCoroutineScope: WindowCoroutineScope,
): Loadable<ChangeKdfState> = produceScreenState(
    key = "change_kdf",
    initial = Loadable.Loading,
    args = arrayOf(
        unlockUseCase,
    ),
) {
    changeKdfStateProducer(
        unlockUseCase = unlockUseCase,
        getBiometricRequireConfirmation = getBiometricRequireConfirmation,
        measureMasterKdf = measureMasterKdf,
        windowCoroutineScope = windowCoroutineScope,
    )
}

/**
 * Produces the state of the screen that changes the key derivation of the
 * vault. Suspends until the vault is open: the form starts from its key
 * derivation.
 */
suspend fun RememberStateFlowScope.changeKdfStateProducer(
    unlockUseCase: UnlockUseCase,
    getBiometricRequireConfirmation: GetBiometricRequireConfirmation,
    measureMasterKdf: MeasureMasterKdfUseCase,
    windowCoroutineScope: WindowCoroutineScope,
): Flow<Loadable<ChangeKdfState>> {
    val biometricPromptSink = EventFlow<BiometricAuthPrompt>()

    val passwordHandle = textFieldHandle(KEY_PASSWORD)

    // A change runs one at a time: a second one would race the
    // re-key of the vault with the first one.
    val loadingSink = MutableStateFlow(false)

    val vaultFlow = unlockUseCase()
        .map { vaultState ->
            (vaultState as? VaultState.Main)?.let { state ->
                val requireConfirmation = getBiometricRequireConfirmation()
                    .first()
                val actions = createChangePasswordActions(
                    state = state,
                    requireConfirmation = requireConfirmation,
                    biometricPromptSink = biometricPromptSink,
                    windowCoroutineScope = windowCoroutineScope,
                    loadingSink = loadingSink,
                    successMessage = Res.string.changekdf_changed_successfully,
                    biometricPromptTitle = Res.string.changekdf_biometric_auth_confirm_title,
                )
                ChangeKdfVault(
                    kdf = state.changePassword.kdf,
                    actions = actions,
                )
            }
        }
        .shareIn(screenScope, SharingStarted.Lazily, replay = 1)

    // The persisted fields keep the edits, so the form starts
    // from the key derivation of the vault only once.
    val initialKdf = vaultFlow
        .filterNotNull()
        .first()
        .kdf
    // Only one key derivation runs at a time: a test does not start
    // while the key derivation changes, and the other way round.
    val kdfForm = masterKdfFormHandle(
        prefix = KEY_KDF,
        // A legacy key derivation moves to the default one.
        initial = initialKdf as? MasterKdf.Argon2id
            ?: MasterKdf.Argon2id.DEFAULT,
        measureMasterKdf = measureMasterKdf,
        isBusyFlow = loadingSink,
    )

    val biometricFlow = changePasswordBiometricFlow(
        actionsFlow = vaultFlow.map { it?.actions },
    )
    val passwordFlow = passwordHandle.modelFlow { password ->
        validatedPassword(password)
    }

    val stateFactory = ChangeKdfStateFactory(
        passwordHandle = passwordHandle,
        kdfForm = kdfForm,
        sideEffects = ChangePasswordState.SideEffects(
            showBiometricPromptFlow = biometricPromptSink,
        ),
    )
    return combine(
        passwordFlow,
        kdfForm.stateFlow,
        biometricFlow,
        vaultFlow,
        loadingSink,
        stateFactory::create,
    )
}

private class ChangeKdfVault(
    /** The current key derivation of the vault. */
    val kdf: MasterKdf,
    val actions: ChangePasswordActions,
)

private class ChangeKdfStateFactory(
    private val passwordHandle: TextFieldHandle,
    private val kdfForm: MasterKdfFormHandle,
    private val sideEffects: ChangePasswordState.SideEffects,
) {
    fun create(
        password: TextFieldModel,
        kdf: MasterKdfFormState,
        biometric: ChangePasswordState.Biometric?,
        vault: ChangeKdfVault?,
        isLoading: Boolean,
    ): Loadable<ChangeKdfState> {
        val newKdf = kdf.kdf
        val canConfirm = password.error == null &&
                password.text.isNotEmpty() &&
                newKdf != null &&
                // A legacy key derivation always differs.
                newKdf != vault?.kdf &&
                !kdf.test.isRunning &&
                !isLoading
        val change = if (biometric?.checked == true) {
            vault?.actions?.changeWithBiometric
        } else {
            vault?.actions?.change
        }
        val onConfirm = if (canConfirm && vault != null && change != null) {
            changeKdfAction(
                change = change,
                currentKdf = vault.kdf,
                passwordHandle = passwordHandle,
                kdfForm = kdfForm,
            )
        } else {
            null
        }
        val state = ChangeKdfState(
            sideEffects = sideEffects,
            password = password,
            kdf = kdf,
            biometric = biometric,
            isLoading = isLoading,
            onConfirm = onConfirm,
        )
        return Loadable.Ok(state)
    }
}

/**
 * Changes the key derivation, reading the input when the user
 * submits: the UI may still hold a callback of an older state.
 */
private fun changeKdfAction(
    change: ChangePasswordAction,
    currentKdf: MasterKdf,
    passwordHandle: TextFieldHandle,
    kdfForm: MasterKdfFormHandle,
): () -> Unit = {
    val password = passwordHandle.sink.value.text
    val kdf = kdfForm.currentKdf()
        ?.takeIf { it != currentKdf }
    val canChange = validatePassword(password) == ValidationPassword.OK &&
            // Only one key derivation runs at a time.
            !kdfForm.isTestingFlow.value
    if (kdf != null && canChange) {
        change(password, VaultState.Main.ChangePassword.Change.Kdf(kdf))
    }
}
