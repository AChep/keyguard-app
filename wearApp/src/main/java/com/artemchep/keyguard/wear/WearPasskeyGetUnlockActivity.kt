package com.artemchep.keyguard.wear

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.PendingIntentHandler
import androidx.lifecycle.lifecycleScope
import com.artemchep.keyguard.android.PasskeyBeginGetUnlockFlow
import com.artemchep.keyguard.android.UiStateError
import com.artemchep.keyguard.android.getCredentialErrorUiState
import com.artemchep.keyguard.common.exception.credential.CallingAppNotPrivilegedException
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
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString as getComposeString

@RequiresApi(34)
class WearPasskeyGetUnlockActivity : WearCredentialProviderActivity() {
    companion object {
        fun getIntent(
            context: Context,
        ): Intent = Intent(context, WearPasskeyGetUnlockActivity::class.java)
    }

    private val getVaultSession by lazy { koin.get<GetVaultSession>() }

    private val passkeyBeginGetUnlockFlow by lazy { koin.get<PasskeyBeginGetUnlockFlow>() }

    private val uiStateSink = mutableStateOf<UiState>(UiState.Loading)

    private sealed interface UiState {
        data object Loading : UiState

        /**
         * A screen that shows an error to a user and
         * offers a button to close the app.
         */
        class Error(
            val data: UiStateError,
        ) : UiState
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordLog("Opened wear passkey get-unlock activity")
        // The request should never be null, we can not proceed
        // immediately exit the screen.
        val request = PendingIntentHandler.retrieveBeginGetCredentialRequest(intent)
        if (request == null) {
            finish()
            return
        }

        val startedAt = Clock.System.now()
        lifecycleScope.launch {
            val session = getVaultSession()
                .mapNotNull { it as? MasterSession.Key }
                .first()
            val userVerified = session.createdAt > startedAt &&
                    session.origin is MasterSession.Key.Authenticated

            // An attempt of handling the request. A user may grant
            // the privilege to the calling app and retry.
            val retrySink = MutableStateFlow(0)
            retrySink
                .onEach { attempt ->
                    handleBeginGetCredentialRequest(
                        session = session,
                        request = request,
                        userVerified = userVerified,
                        onRetry = {
                            retrySink.value = attempt + 1
                        },
                    )
                }
                .collect()
        }
    }

    private suspend fun handleBeginGetCredentialRequest(
        session: MasterSession.Key,
        request: BeginGetCredentialRequest,
        userVerified: Boolean,
        onRetry: () -> Unit,
    ) {
        val response = runCatching {
            withContext(Dispatchers.Default) {
                passkeyBeginGetUnlockFlow.processUnlockedVault(
                    session = session,
                    request = request,
                    userVerified = userVerified,
                )
            }
        }.getOrElse {
            handleBeginGetCredentialFailure(
                session = session,
                request = request,
                exception = it,
                onRetry = onRetry,
            )
            return // end
        }

        val intent = Intent().apply {
            PendingIntentHandler.setBeginGetCredentialResponse(
                intent = this,
                response = response,
            )
        }
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    private suspend fun handleBeginGetCredentialFailure(
        session: MasterSession.Key,
        request: BeginGetCredentialRequest,
        exception: Throwable,
        onRetry: () -> Unit,
    ) {
        val callingAppInfo = request.callingAppInfo
        if (
            exception is CallingAppNotPrivilegedException &&
            callingAppInfo != null
        ) {
            recordLog("Calling app is not privileged, offering to grant the privilege")
            // We intentionally do not set the result here: closing the
            // screen yields RESULT_CANCELED, so the system re-surfaces
            // the selector instead of failing the request.
            val title = getComposeString(Res.string.error_failed_use_passkey)
            val data = getCredentialErrorUiState(
                translatorScope = translatorScope,
                session = session,
                callingAppInfo = callingAppInfo,
                title = title,
                exception = exception,
                beforeRetry = {
                    uiStateSink.value = UiState.Loading
                },
                onRetry = onRetry,
                onFinish = ::finish,
            )
            uiStateSink.value = UiState.Error(data)
            return
        }

        recordException(exception)
        val intent = Intent().apply {
            val e = exception as? GetCredentialException
                ?: GetCredentialUnknownException()
            PendingIntentHandler.setGetCredentialException(
                intent = this,
                exception = e,
            )
        }
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    @Composable
    override fun Content() {
        ManualAppScreen { vaultState ->
            when (vaultState) {
                is VaultState.Create -> WearCreateVaultScreen(vaultState)
                is VaultState.Unlock -> WearUnlockVaultScreen(vaultState)
                is VaultState.Loading -> WearLoadingScreen()
                is VaultState.Main -> when (val state = uiStateSink.value) {
                    is UiState.Loading -> WearLoadingScreen()
                    is UiState.Error -> WearCredentialErrorScreen(state.data)
                }
            }
        }
    }
}
