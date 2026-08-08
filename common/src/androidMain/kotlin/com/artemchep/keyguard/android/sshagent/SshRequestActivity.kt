package com.artemchep.keyguard.android.sshagent

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.android.BaseActivity
import com.artemchep.keyguard.android.ui.DialogActivityWindow
import com.artemchep.keyguard.android.ui.dialogActivityContainerColor
import com.artemchep.keyguard.android.ui.dialogActivityContentColor
import com.artemchep.keyguard.android.util.getParcelableCompat
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.service.sshagent.SshAgentApprovalRequest
import com.artemchep.keyguard.common.service.sshagent.SshAgentGetListRequest
import com.artemchep.keyguard.common.service.sshagent.SshAgentRequestQueue
import com.artemchep.keyguard.common.service.agent.completeWithLog
import com.artemchep.keyguard.feature.keyguard.AuthScreen
import com.artemchep.keyguard.feature.keyguard.LocalAuthScreen
import com.artemchep.keyguard.feature.keyguard.ManualAppScreen
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnCreate
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnLoading
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnUnlock
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.sshagent.SshAgentApprovalContent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.ssh_client_request
import kotlinx.coroutines.delay
import kotlin.time.Clock

internal class SshRequestActivity : BaseActivity() {
    companion object {
        private const val KEY_SERVICE_INTENT = "service_intent"
        private const val KEY_LAUNCH_ID = "launch_id"

        fun getIntent(
            context: Context,
            serviceIntent: Intent? = null,
            launchId: Long? = null,
        ): Intent = Intent(context, SshRequestActivity::class.java).apply {
            putExtra(KEY_SERVICE_INTENT, serviceIntent)
            putExtra(KEY_LAUNCH_ID, launchId ?: 0L)
        }

        fun getLaunchId(intent: Intent?): Long? = intent
            ?.getLongExtra(KEY_LAUNCH_ID, 0L)
            ?.takeIf { it > 0L }
    }

    // Approving here lets an SSH client sign with a private key, so the
    // screen must not be coverable by an overlay.
    override val isConsentSurface: Boolean
        get() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        SshRequestCoordinator.confirmLaunch(intent)
        super.onCreate(savedInstanceState)
        startServiceIfNeeded(intent)
    }

    override fun onNewIntent(intent: Intent) {
        SshRequestCoordinator.confirmLaunch(intent)
        super.onNewIntent(intent)
        setIntent(intent)
        startServiceIfNeeded(intent)
    }

    @Composable
    override fun activityContainerColor(): Color = dialogActivityContainerColor()

    @Composable
    override fun activityContentColor(
        containerColor: Color,
    ): Color = dialogActivityContentColor()

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) {
            SshRequestCoordinator.dismissCurrentRequest()
        }
        super.onDestroy()
    }

    private fun startServiceIfNeeded(intent: Intent?) {
        // Start the actual processing service
        // if this activity was launched as a recovery path.
        val serviceIntent = intent?.extras?.getParcelableCompat<Intent?>(KEY_SERVICE_INTENT)
        if (serviceIntent != null) {
            runOnUiThread {
                startForegroundService(serviceIntent)
            }
        }
    }

    @Composable
    override fun Content() {
        val activeRequestState by SshRequestCoordinator.state.collectAsState()
        // Remember whether we have had an active request before
        // or not. We need it because we want to try to  wait for
        // a request to appear, if we have opened an activity too
        // early.
        var cancelDelay by remember {
            mutableLongStateOf(5000L)
        }
        LaunchedEffect(activeRequestState) {
            val hasActiveRequest = activeRequestState != null
            if (hasActiveRequest) {
                val r = activeRequestState?.request
                cancelDelay = when (r) {
                    is SshAgentGetListRequest -> 3000L // high chance of getting an another event afterwards
                    is SshAgentApprovalRequest -> 1000L
                    else -> cancelDelay
                }
                return@LaunchedEffect
            }

            // Maybe there's another request waiting?
            delay(cancelDelay)
            finish()
        }

        BackHandler(
            enabled = activeRequestState != null,
        ) {
            dismissCurrentRequestAndFinish()
        }

        DialogActivityWindow(
            onDismiss = ::dismissCurrentRequestAndFinish,
        ) {
            val authScreen = AuthScreen(
                reason = TextHolder.Res(Res.string.ssh_client_request),
                style = AuthScreen.Style.DIALOG,
            )
            CompositionLocalProvider(
                LocalAuthScreen provides authScreen,
            ) {
                ActiveRequestContent(
                    activeRequestState = activeRequestState,
                    modifier = Modifier
                        .fillMaxSize(),
                )
            }
            TimeoutIndicator(
                activeRequestState = activeRequestState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(),
            )
        }
    }

    @Composable
    private fun ActiveRequestContent(
        activeRequestState: SshAgentRequestQueue.ActiveRequestState?,
        modifier: Modifier = Modifier,
    ) {
        val activeRequest = activeRequestState?.request

        Box(
            modifier = modifier,
        ) {
            ManualAppScreen { vaultState ->
                val isUnlocked = vaultState is VaultState.Main
                LaunchedEffect(isUnlocked, activeRequest) {
                    if (
                        shouldAutoCompleteAndroidSshRequest(
                            request = activeRequest,
                            isVaultUnlocked = isUnlocked,
                        )
                    ) {
                        activeRequest.completeWithLog(
                            value = true,
                            reason = "android_vault_unlocked_auto_complete",
                        )
                    }
                }

                when (vaultState) {
                    is VaultState.Create -> ManualAppScreenOnCreate(vaultState)
                    is VaultState.Unlock -> ManualAppScreenOnUnlock(vaultState)
                    is VaultState.Loading -> ManualAppScreenOnLoading(vaultState)
                    is VaultState.Main -> when (activeRequest) {
                        null -> ManualAppScreenOnLoading()
                        is SshAgentApprovalRequest -> {
                            SshAgentApprovalContent(
                                request = activeRequest,
                                onDismiss = {},
                            )
                        }

                        is SshAgentGetListRequest -> {
                            ManualAppScreenOnLoading()
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TimeoutIndicator(
        activeRequestState: SshAgentRequestQueue.ActiveRequestState?,
        modifier: Modifier = Modifier,
    ) {
        val progress = rememberTimeoutProgress(activeRequestState)
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = modifier,
            )
        }
    }

    @Composable
    private fun rememberTimeoutProgress(
        activeRequestState: SshAgentRequestQueue.ActiveRequestState?,
    ): Float? {
        val progress by produceState<Float?>(null, activeRequestState) {
            val state = activeRequestState ?: run {
                value = null
                return@produceState
            }
            val expiresAt = state.request.expiresAt
            val totalMs = (expiresAt - Clock.System.now()).inWholeMilliseconds
                .coerceAtLeast(1L)
            while (true) {
                val remainingMs = (expiresAt - Clock.System.now()).inWholeMilliseconds
                    .coerceAtLeast(0L)
                value = remainingMs.toFloat() / totalMs.toFloat()
                if (remainingMs == 0L) {
                    break
                }
                delay(100L)
            }
        }
        return progress
    }

    private fun dismissCurrentRequestAndFinish() {
        SshRequestCoordinator.dismissCurrentRequest()
        finish()
    }
}
