package com.artemchep.keyguard.android.ipc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.artemchep.keyguard.android.BaseActivity
import com.artemchep.keyguard.android.ui.DialogActivityWindow
import com.artemchep.keyguard.android.ui.dialogActivityContainerColor
import com.artemchep.keyguard.android.ui.dialogActivityContentColor
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.feature.auth.userverification.UserVerificationRoute
import com.artemchep.keyguard.feature.keyguard.AuthScreen
import com.artemchep.keyguard.feature.keyguard.LocalAuthScreen
import com.artemchep.keyguard.feature.keyguard.ManualAppScreen
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnCreate
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnLoading
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnUnlock
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.ipc_approval_auth_reason
import com.artemchep.keyguard.feature.navigation.NavigationNode
import kotlinx.coroutines.launch
import org.kodein.di.instance

internal class AndroidIpcApprovalActivity : BaseActivity() {
    private val getVaultSession by instance<GetVaultSession>()

    companion object {
        private const val EXTRA_REQUEST_ID =
            "com.artemchep.keyguard.android.ipc.extra.APPROVAL_REQUEST_ID"

        fun getIntent(
            context: Context,
            requestId: String,
        ): Intent = Intent(context, AndroidIpcApprovalActivity::class.java).apply {
            putExtra(EXTRA_REQUEST_ID, requestId)
        }
    }

    // Approving here hands another app the use of a private key, so the
    // screen must not be coverable by an overlay.
    override val isConsentSurface: Boolean
        get() = true

    private var completed = false
    private var approving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (requestId() == null) {
            finishDenied()
        }
    }

    override fun onDestroy() {
        if (!completed && isFinishing && !isChangingConfigurations) {
            requestId()?.let(AndroidIpcApprovalCoordinator::deny)
        }
        super.onDestroy()
    }

    @Composable
    override fun activityContainerColor(): Color = dialogActivityContainerColor()

    @Composable
    override fun activityContentColor(
        containerColor: Color,
    ): Color = dialogActivityContentColor()

    @Composable
    override fun Content() {
        val requestId = requestId()
            ?: return
        BackHandler {
            finishDenied()
        }
        DialogActivityWindow(
            onDismiss = ::finishDenied,
        ) {
            val state = produceAndroidIpcApprovalState(
                requestId = requestId,
                onApprove = ::finishApproved,
                onDeny = ::finishDenied,
            )
            if (state !is AndroidIpcApprovalState.Ready || !state.requiresAuthentication) {
                AndroidIpcApprovalScreen(state)
                return@DialogActivityWindow
            }
            AuthenticatedApprovalContent(
                requestId = requestId,
                state = state,
            )
        }
    }

    @Composable
    private fun AuthenticatedApprovalContent(
        requestId: String,
        state: AndroidIpcApprovalState.Ready,
    ) {
        val session by getVaultSession()
            .collectAsStateWithLifecycle(getVaultSession.valueOrNull)
        var userVerified by remember(requestId) {
            mutableStateOf(false)
        }
        when (val currentSession = session) {
            null -> AndroidIpcApprovalScreen(AndroidIpcApprovalState.Loading)

            is MasterSession.Empty -> {
                val authScreen = AuthScreen(
                    reason = TextHolder.Res(Res.string.ipc_approval_auth_reason),
                    style = AuthScreen.Style.DIALOG,
                    onCancel = ::finishDenied,
                )
                CompositionLocalProvider(
                    LocalAuthScreen provides authScreen,
                ) {
                    ManualAppScreen { vaultState ->
                        when (vaultState) {
                            is VaultState.Create -> ManualAppScreenOnCreate(vaultState)
                            is VaultState.Unlock -> ManualAppScreenOnUnlock(vaultState)
                            is VaultState.Loading -> ManualAppScreenOnLoading(vaultState)
                            is VaultState.Main -> AndroidIpcApprovalScreen(state)
                        }
                    }
                }
            }

            is MasterSession.Key -> {
                if (
                    currentSession.origin is MasterSession.Key.Persisted &&
                    !userVerified
                ) {
                    val route = remember(requestId) {
                        UserVerificationRoute(
                            onAuthenticated = {
                                userVerified = true
                            },
                        )
                    }
                    NavigationNode(
                        id = "android_ipc_user_verification",
                        route = route,
                    )
                } else {
                    AndroidIpcApprovalScreen(state)
                }
            }
        }
    }

    private fun requestId(): String? = intent
        ?.getStringExtra(EXTRA_REQUEST_ID)
        ?.takeIf { it.isNotBlank() }

    private fun finishApproved(selectedKeyIds: Set<String>) {
        val requestId = requestId()
            ?: return finishDenied()
        if (approving) {
            return
        }
        approving = true
        // The approval commits the registration, so the result may only be
        // returned once that write settled; a client that retries earlier
        // would be told it is still unregistered.
        lifecycleScope.launch {
            val retryIntent = AndroidIpcApprovalCoordinator.approve(
                requestId = requestId,
                selectedKeyIds = selectedKeyIds,
            )
            if (retryIntent == null) {
                approving = false
                finishDenied()
                return@launch
            }
            completed = true
            setResult(Activity.RESULT_OK, retryIntent)
            finish()
        }
    }

    private fun finishDenied() {
        // The approval is committed asynchronously, so a tap on the scrim may
        // land while we are waiting for the write to settle. Denying then
        // would revoke a grant that we are about to hand back to the client.
        if (completed || approving) {
            return
        }
        requestId()?.let(AndroidIpcApprovalCoordinator::deny)
        completed = true
        setResult(Activity.RESULT_CANCELED)
        finish()
    }
}
