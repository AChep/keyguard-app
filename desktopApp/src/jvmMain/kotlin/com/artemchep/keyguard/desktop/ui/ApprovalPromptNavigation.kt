package com.artemchep.keyguard.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.artemchep.keyguard.feature.environments.ui.verifyEnvironmentAccess
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationNode
import com.artemchep.keyguard.feature.navigation.NavigationRouter
import com.artemchep.keyguard.feature.navigation.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The body of an approval prompt, inside the shared agent window. Allowing
 * the request first lets [confirm] verify the user's identity, if the
 * request requires it.
 */
@Composable
internal fun ApprovalPrompt(
    requestId: String,
    confirm: suspend (verify: suspend () -> Boolean) -> Boolean,
    onDecision: (approved: Boolean, reason: String) -> Unit,
    content: @Composable (busy: Boolean, onDeny: () -> Unit, onAllow: () -> Unit) -> Unit,
) {
    val currentConfirm by rememberUpdatedState(confirm)
    val currentOnDecision by rememberUpdatedState(onDecision)
    val currentContent by rememberUpdatedState(content)
    val route = remember(requestId) {
        object : Route {
            @Composable
            override fun Content() {
                ApprovalPromptScreen(requestId, currentConfirm, currentOnDecision, currentContent)
            }
        }
    }
    ApprovalPromptNavigation(requestId = requestId, route = route)
}

@Composable
private fun ApprovalPromptScreen(
    requestId: String,
    confirm: suspend (verify: suspend () -> Boolean) -> Boolean,
    onDecision: (approved: Boolean, reason: String) -> Unit,
    content: @Composable (busy: Boolean, onDeny: () -> Unit, onAllow: () -> Unit) -> Unit,
) {
    val navigation = LocalNavigationController.current
    val scope = rememberCoroutineScope()
    var busy by remember(requestId) { mutableStateOf(false) }
    content(
        busy,
        {
            onDecision(false, "approval_dialog_cancel")
        },
        {
            busy = true
            scope.launch {
                try {
                    val approved = confirm {
                        verifyEnvironmentAccess(navigation)
                    }
                    onDecision(approved, "approval_dialog_confirm")
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    onDecision(false, "approval_dialog_error")
                } finally {
                    busy = false
                }
            }
        },
    )
}

/**
 * Hosts the [route] of one approval prompt in its own navigation stack, so
 * a dialog it opens, such as an access verification, ends with the prompt.
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
