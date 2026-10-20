package com.artemchep.keyguard.android.agentbridge

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
import androidx.compose.runtime.SideEffect
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
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.di.VaultSessionContent
import com.artemchep.keyguard.feature.agent.AgentRequestUiState
import com.artemchep.keyguard.feature.agent.rememberAgentRequestUiState
import com.artemchep.keyguard.feature.gpgagent.GpgAgentApprovalContent
import com.artemchep.keyguard.feature.keyguard.AuthScreen
import com.artemchep.keyguard.feature.keyguard.LocalAuthScreen
import com.artemchep.keyguard.feature.keyguard.ManualAppScreen
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnCreate
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnLoading
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnUnlock
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.sshagent.SshAgentApprovalContent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_client_request
import com.artemchep.keyguard.res.ssh_client_request
import java.util.UUID
import kotlin.time.Clock
import kotlinx.coroutines.delay
import org.koin.core.scope.Scope

internal class AgentRequestActivity : BaseActivity() {
    companion object {
        private const val KEY_SERVICE_INTENT = "service_intent"
        private const val KEY_LAUNCH_ID = "launch_id"
        private const val KEY_PROMPT_TOKEN = "prompt_token"

        fun getIntent(
            context: Context,
            serviceIntent: Intent? = null,
            launchId: Long? = null,
        ): Intent = Intent(context, AgentRequestActivity::class.java).apply {
            putExtra(KEY_SERVICE_INTENT, serviceIntent)
            putExtra(KEY_LAUNCH_ID, launchId ?: 0L)
        }

        fun getLaunchId(intent: Intent?): Long? = intent
            ?.getLongExtra(KEY_LAUNCH_ID, 0L)
            ?.takeIf { it > 0L }

        private fun getServiceIntent(intent: Intent?): Intent? =
            intent?.extras?.getParcelableCompat<Intent?>(KEY_SERVICE_INTENT)
    }

    // Approving here lets an SSH or GPG client use a private key, so the
    // screen must not be coverable by an overlay.
    override val isConsentSurface: Boolean
        get() = true

    /** The request on screen, denied if the activity goes away. */
    private var shownRequest: AgentRequestUiState<AgentBridgePrompt>? = null

    /** Identifies this prompt across configuration changes. */
    private lateinit var promptToken: String

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
        promptToken = savedInstanceState?.getString(KEY_PROMPT_TOKEN)
            ?: UUID.randomUUID().toString()
        // Open before confirming the launch, so that a request
        // shown here falls back to its notification only once
        // this prompt closes.
        AgentRequestCoordinator.prompts.open(promptToken)
        AgentRequestCoordinator.confirmLaunch(intent)
        super.onCreate(savedInstanceState)
        startServiceIfNeeded(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PROMPT_TOKEN, promptToken)
    }

    override fun onNewIntent(intent: Intent) {
        AgentRequestCoordinator.confirmLaunch(intent)
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
            shownRequest?.onDecision?.invoke(false, "activity_destroyed")
        }
        // A configuration change recreates the prompt with the same token.
        if (isFinishing || !isChangingConfigurations) {
            AgentRequestCoordinator.prompts.close(promptToken)
        }
        super.onDestroy()
    }

    private fun startServiceIfNeeded(intent: Intent?) {
        // Start the actual processing service
        // if this activity was launched as a recovery path.
        val serviceIntent = getServiceIntent(intent)
        if (serviceIntent != null) {
            runOnUiThread {
                startForegroundService(serviceIntent)
            }
        }
    }

    @Composable
    override fun Content() {
        val requestUiState = rememberAgentRequestUiState(
            pending = AgentRequestCoordinator.pending,
            idOf = AgentBridgePrompt::id,
            expiresAtOf = AgentBridgePrompt::expiresAt,
            respond = AgentRequestCoordinator::respond,
            logType = "termux",
        )
        val activeRequestState = (requestUiState as? Loadable.Ok)?.value
        // Read directly, as the request state has none in its first
        // frames and in the pause between two requests.
        val pending by AgentRequestCoordinator.pending.collectAsState()
        SideEffect {
            shownRequest = activeRequestState
        }
        // Remember whether we have had an active request before
        // or not. We need it because we want to try to  wait for
        // a request to appear, if we have opened an activity too
        // early.
        var cancelDelay by remember {
            mutableLongStateOf(5000L)
        }
        val hasRequest = requestUiState != null
        LaunchedEffect(hasRequest) {
            if (hasRequest) {
                cancelDelay = 1000L
                return@LaunchedEffect
            }

            // Maybe there's another request waiting?
            delay(cancelDelay)
            finish()
        }

        BackHandler(
            enabled = hasRequest,
        ) {
            dismiss()
        }

        DialogActivityWindow(
            onDismiss = ::dismiss,
        ) {
            val bridge = agentRequestUnlockBridge(
                shown = activeRequestState?.request,
                pending = pending,
                recovery = AgentBridge.ofAction(getServiceIntent(intent)?.action),
            )
            val reason = when (bridge) {
                AgentBridge.SSH -> Res.string.ssh_client_request
                AgentBridge.GPG -> Res.string.gpg_client_request
                null -> null
            }
            val authScreen = AuthScreen(
                reason = reason?.let(TextHolder::Res),
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
        activeRequestState: AgentRequestUiState<AgentBridgePrompt>?,
        modifier: Modifier = Modifier,
    ) {
        Box(
            modifier = modifier,
        ) {
            ManualAppScreen { vaultState ->
                when (vaultState) {
                    is VaultState.Create -> ManualAppScreenOnCreate(vaultState)
                    is VaultState.Unlock -> ManualAppScreenOnUnlock(vaultState)
                    is VaultState.Loading -> ManualAppScreenOnLoading(vaultState)
                    is VaultState.Main -> when (activeRequestState) {
                        null -> ManualAppScreenOnLoading()
                        else -> {
                            // Provide the session Scope so that the content can
                            // read the vault, e.g. to resolve the key's title.
                            VaultSessionContent(vaultState.session) {
                                PromptContent(
                                    prompt = activeRequestState.request,
                                    onDecision = activeRequestState.onDecision,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TimeoutIndicator(
        activeRequestState: AgentRequestUiState<AgentBridgePrompt>?,
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

    /**
     * Denies the request on screen. The prompt stays open while other
     * requests wait, and shows the next one after a short pause.
     */
    private fun dismiss() {
        val request = shownRequest
        shownRequest = null
        if (AgentRequestCoordinator.dismissPrompt(request)) {
            finish()
        }
    }
}

@Composable
private fun PromptContent(
    prompt: AgentBridgePrompt,
    onDecision: (approved: Boolean, reason: String) -> Unit,
) {
    when (prompt) {
        is AgentBridgePrompt.Ssh -> SshAgentApprovalContent(
            request = prompt.request,
            onDecision = onDecision,
        )

        is AgentBridgePrompt.Gpg -> GpgAgentApprovalContent(
            request = prompt.request,
            onDecision = onDecision,
        )
    }
}

@Composable
private fun rememberTimeoutProgress(
    activeRequestState: AgentRequestUiState<AgentBridgePrompt>?,
): Float? {
    val progress by produceState<Float?>(null, activeRequestState) {
        val state = activeRequestState ?: run {
            value = null
            return@produceState
        }
        val expiresAt = state.expiresAt
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
