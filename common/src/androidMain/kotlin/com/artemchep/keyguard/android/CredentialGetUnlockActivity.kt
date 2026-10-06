package com.artemchep.keyguard.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.PendingIntentHandler
import androidx.lifecycle.lifecycleScope
import com.artemchep.keyguard.common.exception.credential.CallingAppNotPrivilegedException
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.di.KeyguardKoinOwner
import com.artemchep.keyguard.feature.keyguard.ManualAppScreen
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnCreate
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnLoading
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnUnlock
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.platform.recordLog
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.theme.Dimens
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
import org.jetbrains.compose.resources.stringResource

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CredentialGetUnlockActivity : BaseActivity(), KeyguardKoinOwner {
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

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordLog("Opened credential get-unlock activity")
        // The request should never be null, we can not proceed
        // immediately exit the screen.
        val request = PendingIntentHandler.retrieveBeginGetCredentialRequest(intent)
        if (request == null) {
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

        // We always have a valid result to return, therefore
        // Activity.RESULT_OK
        val status = Activity.RESULT_OK
        val intent = Intent().apply {
            PendingIntentHandler.setBeginGetCredentialResponse(
                intent = this,
                response = response,
            )
        }
        setResult(status, intent)
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
                session = session,
                callingAppInfo = callingAppInfo,
                title = title,
                exception = exception,
                beforeRetry = {
                    uiStateSink.value = UiState.Loading
                },
                onRetry = onRetry,
            )
            uiStateSink.value = UiState.Error(data)
            return
        }

        recordException(exception)
        // Something went wrong, finish with the
        // exception.
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
        val uiState = uiStateSink.value
        ExtensionScaffold(
            header = {
                Header(
                    uiState = uiState,
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
                        when (uiState) {
                            is UiState.Loading -> {
                                val fakeLoadingState = VaultState.Loading
                                ManualAppScreenOnLoading(fakeLoadingState)
                            }

                            is UiState.Error -> {
                                val data = uiState.data
                                CredentialError(
                                    title = data.title,
                                    message = data.message,
                                    advanced = data.advanced,
                                    onFinish = data.onFinish,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Header(
        uiState: UiState,
    ) {
        Row(
            modifier = Modifier
                .padding(
                    start = Dimens.horizontalPadding,
                    end = 8.dp,
                    top = 8.dp,
                    bottom = 8.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val titleRes = when (uiState) {
                is UiState.Loading -> Res.string.autofill_unlock_keyguard
                is UiState.Error -> Res.string.autofill_authorize_app
            }
            Text(
                modifier = Modifier
                    .weight(1f)
                    .align(Alignment.CenterVertically),
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )

            val context by rememberUpdatedState(newValue = LocalContext.current)
            TextButton(
                onClick = {
                    context.closestActivityOrNull?.finish()
                },
            ) {
                Icon(Icons.Outlined.Close, null)
                Spacer(
                    modifier = Modifier
                        .width(Dimens.buttonIconPadding),
                )
                Text(
                    text = stringResource(Res.string.cancel),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
