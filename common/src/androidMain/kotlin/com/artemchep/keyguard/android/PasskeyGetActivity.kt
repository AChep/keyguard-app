package com.artemchep.keyguard.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import androidx.lifecycle.lifecycleScope
import com.artemchep.keyguard.android.util.getParcelableCompat
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.model.AddCipherUsedPasskeyHistoryRequest
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.usecase.AddCipherUsedPasskeyHistory
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetPrivilegedApps
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.feature.auth.userverification.UserVerificationRoute
import com.artemchep.keyguard.feature.keyguard.ManualAppScreen
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnCreate
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnLoading
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnUnlock
import com.artemchep.keyguard.feature.navigation.NavigationNode
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.platform.recordLog
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.getString as getComposeString
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlinx.parcelize.Parcelize
import org.kodein.di.*

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasskeyGetActivity : BaseActivity(), DIAware {
    companion object {
        const val KEY_ARGUMENTS = "arguments"
    }

    private val _args by lazy {
        intent.extras?.getParcelableCompat<PasskeyProviderGetActivityArgs>(KEY_ARGUMENTS)
    }

    protected val args: PasskeyProviderGetActivityArgs get() = requireNotNull(_args)

    private val getVaultSession by instance<GetVaultSession>()

    private val passkeyProviderGetFlow by instance<PasskeyProviderGetFlow>()

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
            withContext(Dispatchers.Default) {
                PasskeyUtils.withProcessingMinTime {
                    processUnlockedVault(
                        session = session,
                        userVerified = true,
                    )
                }
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
        withContext(Dispatchers.Default) {
            passkeyProviderGetFlow.recordUsage(
                session = session,
                args = args,
            )
        }

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
    ): GetCredentialResponse = passkeyProviderGetFlow.processUnlockedVault(
        session = session,
        request = getCredentialRequest,
        args = args,
        userVerified = userVerified,
    )
}
