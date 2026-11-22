package com.artemchep.keyguard.feature.agent

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.confirmation.elevatedaccess.ElevatedAccessForm
import com.artemchep.keyguard.feature.confirmation.elevatedaccess.ElevatedAccessPromptEffects
import com.artemchep.keyguard.feature.confirmation.elevatedaccess.ElevatedAccessState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.back
import com.artemchep.keyguard.res.confirm
import com.artemchep.keyguard.res.elevatedaccess_header_text
import com.artemchep.keyguard.res.elevatedaccess_header_title
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource

/**
 * The second step of an approval prompt whose request requires the user's
 * identity: confirms it in place of the request, and then approves it.
 *
 * @param subject What approving releases, such as the selected passkey;
 *   `null` if the request names no single item.
 * @param busy `true` while the approval is being verified.
 * @param onBack Returns to the request.
 */
@Composable
fun AgentApprovalConfirmAccess(
    state: ElevatedAccessState,
    subject: AgentApprovalChoiceItem?,
    expiresAt: Instant?,
    busy: Boolean,
    onBack: () -> Unit,
) {
    ElevatedAccessPromptEffects(state)

    val message = stringResource(Res.string.elevatedaccess_header_text)
    AgentApprovalDialog(
        icon = {
            Icon(
                imageVector = Icons.Outlined.Security,
                contentDescription = null,
            )
        },
        iconCompact = true,
        title = stringResource(Res.string.elevatedaccess_header_title),
        message = { AnnotatedString(message) },
        callerInfo = null,
        expiresAt = expiresAt,
        cancelText = stringResource(Res.string.back),
        confirmText = stringResource(Res.string.confirm),
        confirmEnabled = !busy && state.onConfirm != null,
        onCancel = onBack,
        onConfirm = {
            state.onConfirm?.invoke()
        },
        // The user got here with the armed Approve button, and
        // the password field takes the focus.
        nextStep = true,
    ) {
        if (subject != null) {
            AgentApprovalChoiceList(
                items = listOf(subject),
                selected = 0,
                onSelect = null,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        // The Go key of the password field answers the prompt, so
        // it waits for the input guard as the buttons do.
        val armed = LocalAgentApprovalInputGuard.current.armed
        ElevatedAccessForm(
            state = state,
            onConfirm = state.onConfirm?.takeIf { armed && !busy },
            showText = false,
            alwaysFocusPassword = true,
        )
    }
}
