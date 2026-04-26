package com.artemchep.keyguard.wear

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import androidx.lifecycle.lifecycleScope
import com.artemchep.keyguard.android.PasskeyProviderGetActivityArgs
import com.artemchep.keyguard.android.PasskeyProviderGetFlow
import com.artemchep.keyguard.android.UiStateError
import com.artemchep.keyguard.android.getCredentialErrorUiState
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.feature.keyguard.ManualAppScreen
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.platform.recordLog
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.error_failed_use_passkey
import com.artemchep.keyguard.wear.feature.WearCreateVaultScreen
import com.artemchep.keyguard.wear.feature.WearLoadingScreen
import com.artemchep.keyguard.wear.feature.WearUnlockVaultScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString as getComposeString
import org.kodein.di.instance
import kotlin.time.Clock

@RequiresApi(34)
class WearPasskeyGetActivity : WearCredentialProviderActivity() {
    companion object {
        fun getIntent(
            context: Context,
            args: PasskeyProviderGetActivityArgs,
        ): Intent = Intent(context, WearPasskeyGetActivity::class.java).apply {
            putExtra(
                com.artemchep.keyguard.android.PasskeyGetActivity.KEY_ARGUMENTS,
                args,
            )
        }
    }

    private val _args by lazy {
        intent.extras?.getParcelable(
            com.artemchep.keyguard.android.PasskeyGetActivity.KEY_ARGUMENTS,
            PasskeyProviderGetActivityArgs::class.java,
        )
    }

    private val args: PasskeyProviderGetActivityArgs get() = requireNotNull(_args)

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

        class RequiresAuthentication(
            val onAuthenticated: () -> Unit,
        ) : UiState

        class Error(
            val data: UiStateError,
        ) : UiState
    }

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordLog("Opened wear passkey get activity")
        if (_args == null) {
            finish()
            return
        }

        val startedAt = Clock.System.now()
        lifecycleScope.launch {
            val session = getVaultSession()
                .mapNotNull { it as? MasterSession.Key }
                .first()

            val userVerifiedState = MutableStateFlow(
                args.userVerified ||
                    (session.createdAt > startedAt &&
                        session.origin is MasterSession.Key.Authenticated),
            )
            if (!userVerifiedState.value && args.requiresUserVerification) {
                uiStateSink.value = UiState.RequiresAuthentication {
                    userVerifiedState.value = true
                }
                userVerifiedState.first { it }
            }
            uiStateSink.value = UiState.Loading

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
            com.artemchep.keyguard.android.PasskeyUtils.withProcessingMinTime {
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
            return
        }

        passkeyProviderGetFlow.recordUsage(
            session = session,
            args = args,
        )

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
            val error = exception as? GetCredentialException
                ?: GetCredentialUnknownException()
            PendingIntentHandler.setGetCredentialException(
                intent = this,
                exception = error,
            )
        }
        setResult(RESULT_OK, intent)

        val title = getComposeString(Res.string.error_failed_use_passkey)
        val data = getCredentialErrorUiState(
            translatorScope = translatorScope,
            session = session,
            callingAppInfo = getCredentialRequest.callingAppInfo,
            title = title,
            exception = exception,
            beforeRetry = {
                uiStateSink.value = UiState.Loading
            },
            onRetry = onRetry,
            onFinish = ::finish,
        )
        return UiState.Error(data)
    }

    @Composable
    override fun Content() {
        ManualAppScreen { vaultState ->
            when (vaultState) {
                is VaultState.Create -> WearCreateVaultScreen(vaultState)
                is VaultState.Unlock -> WearUnlockVaultScreen(vaultState)
                is VaultState.Loading -> WearLoadingScreen()
                is VaultState.Main -> {
                    when (val state = uiStateSink.value) {
                        is UiState.Loading -> WearLoadingScreen()
                        is UiState.Error -> WearCredentialErrorScreen(state.data)
                        is UiState.RequiresAuthentication -> {
                            val updatedOnAuthenticated = rememberUpdatedState(state.onAuthenticated)
                            WearUserVerificationScreen(
                                args = args,
                                onAuthenticated = {
                                    updatedOnAuthenticated.value.invoke()
                                },
                            )
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
