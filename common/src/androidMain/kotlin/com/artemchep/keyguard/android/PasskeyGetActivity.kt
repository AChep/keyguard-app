package com.artemchep.keyguard.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import androidx.lifecycle.lifecycleScope
import arrow.optics.optics
import com.artemchep.keyguard.android.util.getParcelableCompat
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.AddCipherUsedPasskeyHistoryRequest
import com.artemchep.keyguard.common.model.BiometricAuthException
import com.artemchep.keyguard.common.model.BiometricAuthPrompt
import com.artemchep.keyguard.common.model.BiometricAuthPromptSimple
import com.artemchep.keyguard.common.model.BiometricStatus
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.PureBiometricAuthPrompt
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.usecase.AddCipherUsedPasskeyHistory
import com.artemchep.keyguard.common.usecase.BiometricStatusUseCase
import com.artemchep.keyguard.common.usecase.ConfirmAccessByPasswordUseCase
import com.artemchep.keyguard.common.usecase.GetBiometricRequireConfirmation
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetPrivilegedApps
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.auth.common.util.validatedPassword
import com.artemchep.keyguard.feature.biometric.BiometricPromptEffect
import com.artemchep.keyguard.feature.keyguard.ManualAppScreen
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnCreate
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnLoading
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnUnlock
import com.artemchep.keyguard.feature.keyguard.unlock.UnlockScreenContainer
import com.artemchep.keyguard.feature.keyguard.unlock.UnlockState
import com.artemchep.keyguard.feature.loading.LoadingTask
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.NavigationNode
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.platform.recordLog
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.OtherScaffold
import com.artemchep.keyguard.ui.PasswordFlatTextField
import org.jetbrains.compose.resources.getString as getComposeString
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlinx.parcelize.Parcelize
import org.kodein.di.*
import org.kodein.di.compose.localDI

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasskeyGetActivity : BaseActivity(), DIAware {
    companion object {
        private const val KEY_ARGUMENTS = "arguments"

        fun getIntent(
            context: Context,
            args: Args,
        ): Intent = Intent(context, PasskeyGetActivity::class.java).apply {
            putExtra(KEY_ARGUMENTS, args)
        }
    }

    @Parcelize
    data class Args(
        val accountId: String,
        val cipherId: String,
        val credId: String,
        val cipherName: String,
        val credRpId: String,
        val credUserDisplayName: String,
        val requiresUserVerification: Boolean,
        val userVerified: Boolean,
    ) : Parcelable

    private val _args by lazy {
        intent.extras?.getParcelableCompat<Args>(KEY_ARGUMENTS)
    }

    private val args: Args get() = requireNotNull(_args)

    private val getVaultSession by instance<GetVaultSession>()

    private val getCredentialRequestUtils by instance<PasskeyProviderGetRequest>()

    private val getCredentialRequest by lazy {
        val request = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
        requireNotNull(request) {
            "Get request from framework is empty."
        }
    }

    private val uiStateSink = mutableStateOf<UiState>(UiState.Loading)

    private sealed interface UiState {
        data object Loading : UiState

        /**
         * A screen that asks a user to
         * authenticate himself.
         */
        class RequiresAuthentication(
            val onAuthenticated: () -> Unit,
        ) : UiState

        /**
         * A screen that shows an error to a user and
         * offers a button to close the app.
         */
        class Error(
            val data: UiStateError,
        ) : UiState
    }

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordLog("Opened passkey get activity")
        // Arguments should never be null, we can not proceed
        // immediately exit the screen.
        if (_args == null) {
            finish()
            return
        }

        val startedAt = Clock.System.now()
        // Observe the vault session to detect when a user
        // unlocks the vault.
        lifecycleScope.launch {
            val session = getVaultSession()
                .mapNotNull { it as? MasterSession.Key }
                .first()

            val userVerifiedState = kotlin.run {
                val initialValue = args.userVerified ||
                        session.createdAt > startedAt &&
                        session.origin is MasterSession.Key.Authenticated
                MutableStateFlow(initialValue)
            }
            if (!userVerifiedState.value && args.requiresUserVerification) {
                uiStateSink.value = UiState.RequiresAuthentication {
                    userVerifiedState.value = true
                }
                // Wait till the user passes verification process.
                userVerifiedState.first { it }
            }
            uiStateSink.value = UiState.Loading

            // An attempt of handling the cipher.
            val retrySink = MutableStateFlow(0)
            retrySink
                .onEach { attempt ->
                    handleCredentialRequest(
                        session = session,
                        onRetry = {
                            retrySink.value = attempt + 1
                        },
                    )
                }
                .collect()
        }
    }

    private suspend fun handleCredentialRequest(
        session: MasterSession.Key,
        onRetry: () -> Unit,
    ) {
        val response = runCatching {
            PasskeyUtils.withProcessingMinTime {
                processUnlockedVault(
                    session = session,
                    userVerified = true,
                )
            }
        }.getOrElse {
            recordException(it)

            val uiState = getCredentialErrorUiState(
                session = session,
                exception = it,
                onRetry = onRetry,
            )
            uiStateSink.value = uiState
            return // end
        }

        // Log that a used has used the passkey. We only do
        // it after a successful attempt.
        val addCipherUsedPasskey = session.di.direct.instance<AddCipherUsedPasskeyHistory>()
        val addCipherUsedPasskeyRequest = AddCipherUsedPasskeyHistoryRequest(
            accountId = args.accountId,
            cipherId = args.cipherId,
            credentialId = args.credId,
        )
        addCipherUsedPasskey(addCipherUsedPasskeyRequest)
            .attempt()
            .bind()

        val intent = Intent().apply {
            PendingIntentHandler.setGetCredentialResponse(
                intent = this,
                response = response,
            )
        }
        setResult(RESULT_OK, intent)
        finish()
    }

    private suspend fun getCredentialErrorUiState(
        session: MasterSession.Key,
        exception: Throwable,
        onRetry: () -> Unit,
    ): UiState.Error {
        val intent = Intent().apply {
            val e = exception as? GetCredentialException
                ?: GetCredentialUnknownException()
            PendingIntentHandler.setGetCredentialException(
                intent = this,
                exception = e,
            )
        }
        setResult(RESULT_OK, intent)

        // Get the UI model of an error

        val title = getComposeString(Res.string.error_failed_use_passkey)
        val data = getCredentialErrorUiState(
            session = session,
            callingAppInfo = getCredentialRequest.callingAppInfo,
            title = title,
            exception = exception,
            beforeRetry = {
                uiStateSink.value = UiState.Loading
            },
            onRetry = onRetry,
        )
        return UiState.Error(data)
    }

    @Composable
    override fun Content() {
        val title = stringResource(Res.string.passkey_auth_via_header)
        val context by rememberUpdatedState(newValue = LocalContext.current)
        CredentialScaffold(
            onCancel = {
                context.closestActivityOrNull?.finish()
            },
            titleText = title,
            // Render the subtitle basing on the create
            // credential type.
            subtitle = {
                CredentialSubtitlePublicKey(
                    username = args.credUserDisplayName,
                    rpId = args.credRpId,
                )
            },
        ) {
            // Instead of showing a vault to a user, we continue showing the
            // loading screen until we automatically form a response and close
            // the activity.
            ManualAppScreen { vaultState ->
                when (vaultState) {
                    is VaultState.Create -> ManualAppScreenOnCreate(vaultState)
                    is VaultState.Unlock -> ManualAppScreenOnUnlock(vaultState)
                    is VaultState.Loading -> ManualAppScreenOnLoading(vaultState)
                    is VaultState.Main -> {
                        when (val state = uiStateSink.value) {
                            is UiState.Loading -> {
                                val fakeLoadingState = VaultState.Loading
                                ManualAppScreenOnLoading(fakeLoadingState)
                            }

                            is UiState.Error -> {
                                val data = state.data
                                CredentialError(
                                    title = data.title,
                                    message = data.message,
                                    advanced = data.advanced,
                                    onFinish = data.onFinish,
                                )
                            }

                            is UiState.RequiresAuthentication -> {
                                val updatedOnAuthenticated by rememberUpdatedState(state.onAuthenticated)
                                val route = remember {
                                    UserVerificationRoute(
                                        onAuthenticated = {
                                            updatedOnAuthenticated.invoke()
                                        },
                                    )
                                }
                                NavigationNode(
                                    id = "user_verification",
                                    route = route,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun processUnlockedVault(
        session: MasterSession.Key,
        userVerified: Boolean,
    ): GetCredentialResponse {
        val ciphers = kotlin.run {
            val getCiphers = session.di.direct.instance<GetCiphers>()
            getCiphers()
                .first()
        }
        val credential = ciphers
            .firstNotNullOfOrNull { cipher ->
                if (
                    args.accountId != cipher.accountId &&
                    args.cipherId != cipher.id
                ) {
                    return@firstNotNullOfOrNull null
                }

                cipher.login?.fido2Credentials
                    ?.firstOrNull { credential ->
                        args.credId == credential.credentialId
                    }
            }
        requireNotNull(credential)

        val privilegedApps = kotlin.run {
            val getPrivilegedApps = session.di.direct.instance<GetPrivilegedApps>()
            getPrivilegedApps()
                .first()
        }
        return getCredentialRequestUtils.processGetCredentialsRequest(
            request = getCredentialRequest,
            credential = credential,
            userVerified = userVerified,
            privilegedApps = privilegedApps,
        )
    }
}

class UserVerificationRoute(
    private val onAuthenticated: () -> Unit,
) : Route {
    @Composable
    override fun Content(
    ) {
        UserVerificationScreen(
            onAuthenticated = onAuthenticated,
        )
    }
}

@Composable
fun UserVerificationScreen(
    onAuthenticated: () -> Unit,
) {
    val state = produceUserVerificationState(
        onAuthenticated = onAuthenticated,
    )
    val content = state.content.getOrNull()
        ?: return

    BiometricPromptEffect(content.sideEffects.showBiometricPromptFlow)
    OtherScaffold {
        UnlockScreenContainer(
            top = {
                Text(
                    textAlign = TextAlign.Center,
                    text = stringResource(Res.string.userverification_header_text),
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            center = {
                val keyboardOnGo: (KeyboardActionScope.() -> Unit)? =
                    if (content.onVerify != null) {
                        // lambda
                        {
                            content.onVerify.invoke()
                        }
                    } else {
                        null
                    }
                PasswordFlatTextField(
                    modifier = Modifier,
                    testTag = "field:password",
                    value = content.password,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = keyboardOnGo,
                    ),
                )
            },
            bottom = {
                val onUnlockButtonClick by rememberUpdatedState(
                    content.onVerify,
                )
                Button(
                    modifier = Modifier
                        .testTag("btn:go")
                        .fillMaxWidth(),
                    enabled = content.onVerify != null,
                    onClick = {
                        onUnlockButtonClick?.invoke()
                    },
                ) {
                    Text(
                        text = stringResource(Res.string.userverification_button_go),
                    )
                }
                val onBiometricButtonClick by rememberUpdatedState(
                    content.biometric?.onClick,
                )
                ExpandedIfNotEmpty(
                    valueOrNull = content.biometric,
                ) { b ->
                    ElevatedButton(
                        modifier = Modifier
                            .padding(top = 32.dp),
                        enabled = b.onClick != null,
                        onClick = {
                            onBiometricButtonClick?.invoke()
                        },
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Fingerprint,
                            contentDescription = null,
                        )
                    }
                }
            },
        )
    }
}

@Immutable
data class UserVerificationState(
    val content: Loadable<Content> = Loadable.Loading,
) {
    @Immutable
    data class Content(
        val sideEffects: UnlockState.SideEffects,
        val password: TextFieldModel2,
        val biometric: Biometric? = null,
        val isLoading: Boolean = false,
        val onVerify: (() -> Unit)? = null,
    )

    @Immutable
    @optics
    data class Biometric(
        val onClick: (() -> Unit)? = null,
    ) {
        companion object
    }

    @Immutable
    @optics
    data class SideEffects(
        val showBiometricPromptFlow: Flow<BiometricAuthPrompt>,
    ) {
        companion object
    }
}

private const val DEFAULT_PASSWORD = ""

@Composable
fun produceUserVerificationState(
    onAuthenticated: () -> Unit,
): UserVerificationState = with(localDI().direct) {
    produceUserVerificationState(
        onAuthenticated = onAuthenticated,
        biometricStatusUseCase = instance(),
        getBiometricRequireConfirmation = instance(),
        confirmAccessByPasswordUseCase = instance(),
        windowCoroutineScope = instance(),
    )
}

@Composable
fun produceUserVerificationState(
    onAuthenticated: () -> Unit,
    biometricStatusUseCase: BiometricStatusUseCase,
    getBiometricRequireConfirmation: GetBiometricRequireConfirmation,
    confirmAccessByPasswordUseCase: ConfirmAccessByPasswordUseCase,
    windowCoroutineScope: WindowCoroutineScope,
): UserVerificationState = produceScreenState(
    key = "user_verification",
    initial = UserVerificationState(),
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
                        onAuthenticated()
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
        UserVerificationState.Biometric(
            onClick = {
                biometricPromptSink.emit(prompt)
            },
        )
    }
    val biometricStateDisabled = biometricPrompt?.let { prompt ->
        UserVerificationState.Biometric(
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
        val content = UserVerificationState.Content(
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
            onVerify = if (canCreateVault) {
                // lambda
                {
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
            } else {
                null
            },
        )
        UserVerificationState(
            content = Loadable.Ok(content),
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
