package com.artemchep.keyguard.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.artemchep.keyguard.feature.agent.AgentApprovalChoiceItem
import com.artemchep.keyguard.feature.agent.AgentApprovalConfirmAccess
import com.artemchep.keyguard.feature.confirmation.elevatedaccess.ElevatedAccessResult
import com.artemchep.keyguard.feature.confirmation.elevatedaccess.produceElevatedAccessState
import com.artemchep.keyguard.feature.navigation.NavigationNode
import com.artemchep.keyguard.feature.navigation.NavigationRouter
import com.artemchep.keyguard.feature.navigation.Route
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The body of an approval prompt, inside the shared agent window. If the
 * request [requires the user's identity][requiresIdentity], approving it
 * goes to a second step that confirms the identity in place of the
 * request; [confirm] then gets a `verify` that returns the result.
 *
 * @param expiresAt When the request expires; the second step counts down to it.
 * @param subject What the second step names as approved, such as the
 *   selected passkey; `null` to name nothing.
 */
@Composable
internal fun ApprovalPrompt(
    requestId: String,
    requiresIdentity: Boolean,
    expiresAt: Instant,
    confirm: suspend (verify: suspend () -> Boolean) -> Boolean,
    onDecision: (approved: Boolean, reason: String) -> Unit,
    subject: AgentApprovalChoiceItem? = null,
    content: @Composable (controls: ApprovalPromptControls) -> Unit,
) {
    val currentRequiresIdentity by rememberUpdatedState(requiresIdentity)
    val currentExpiresAt by rememberUpdatedState(expiresAt)
    val currentConfirm by rememberUpdatedState(confirm)
    val currentOnDecision by rememberUpdatedState(onDecision)
    val currentSubject by rememberUpdatedState(subject)
    val currentContent by rememberUpdatedState(content)
    val route = remember(requestId) {
        object : Route {
            @Composable
            override fun Content() {
                ApprovalPromptScreen(
                    requestId = requestId,
                    requiresIdentity = currentRequiresIdentity,
                    expiresAt = currentExpiresAt,
                    confirm = currentConfirm,
                    onDecision = currentOnDecision,
                    subject = currentSubject,
                    content = currentContent,
                )
            }
        }
    }
    ApprovalPromptNavigation(requestId = requestId, route = route)
}

@Composable
private fun ApprovalPromptScreen(
    requestId: String,
    requiresIdentity: Boolean,
    expiresAt: Instant,
    confirm: suspend (verify: suspend () -> Boolean) -> Boolean,
    onDecision: (approved: Boolean, reason: String) -> Unit,
    subject: AgentApprovalChoiceItem?,
    content: @Composable (controls: ApprovalPromptControls) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(requestId) { mutableStateOf(false) }
    // The second step: the confirmation of the user's identity, in
    // place of the request. The request may also come to require it
    // after it showed, for example once the vault unlocks.
    var confirming by remember(requestId) { mutableStateOf(false) }

    // Approves the request; the identity is confirmed if [identityConfirmed].
    val approve: (identityConfirmed: Boolean) -> Unit = approve@{ identityConfirmed ->
        if (busy) return@approve
        busy = true
        scope.launch {
            try {
                var identityMissing = false
                val approved = confirm {
                    identityMissing = !identityConfirmed
                    identityConfirmed
                }
                if (identityMissing) {
                    confirming = true
                } else {
                    onDecision(approved, "approval_dialog_confirm")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                onDecision(false, "approval_dialog_error")
            } finally {
                busy = false
            }
        }
    }

    if (confirming) {
        // Composed only on this step, so that the biometric
        // prompt starts once the user gets here.
        val state = produceElevatedAccessState { result ->
            // A confirmation that ends after the user went back
            // doesn't approve the request.
            if (result is ElevatedAccessResult.Allow && confirming) approve(true)
        }
        AgentApprovalConfirmAccess(
            state = state,
            subject = subject,
            expiresAt = expiresAt,
            busy = busy,
            onBack = {
                // The approval is on its way already.
                if (!busy) confirming = false
            },
        )
    } else {
        content(
            ApprovalPromptControls(
                busy = busy,
                onDeny = {
                    onDecision(false, "approval_dialog_cancel")
                },
                onAllow = {
                    if (requiresIdentity) {
                        // Nothing is sent before the identity is confirmed.
                        confirming = true
                    } else {
                        approve(false)
                    }
                },
            ),
        )
    }
}

/**
 * The body of an unlock prompt. The window shows the unlock screen while
 * the vault is locked; once it shows this, the vault is unlocked.
 */
@Composable
internal fun UnlockApprovalPrompt(
    requestId: String,
    onDecision: (approved: Boolean, reason: String) -> Unit,
) {
    val currentOnDecision by rememberUpdatedState(onDecision)
    LaunchedEffect(requestId) {
        currentOnDecision(true, "vault_unlocked")
    }
}

/**
 * Hosts the [route] of one approval prompt in its own navigation stack, so
 * its screen state, such as the confirmation of the user's identity, ends
 * with the prompt.
 */
@Composable
internal fun ApprovalPromptNavigation(
    requestId: String,
    route: Route,
) {
    val root = remember(requestId, route) {
        object : Route {
            @Composable
            override fun Content() {
                NavigationRouter(id = "approval_prompt.$requestId", initial = route) { entries ->
                    NavigationNode(entries = entries)
                }
            }
        }
    }
    NavigationNode(id = "App:ApprovalPrompt:$requestId", route = root)
}
