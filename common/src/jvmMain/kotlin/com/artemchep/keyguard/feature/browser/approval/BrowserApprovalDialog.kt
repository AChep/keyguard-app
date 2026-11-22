package com.artemchep.keyguard.feature.browser.approval

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_NAME_LENGTH
import com.artemchep.keyguard.common.service.agent.sanitizedAgentDisplayValue
import com.artemchep.keyguard.common.service.browser.BrowserApprovalRequest
import com.artemchep.keyguard.common.service.browser.toCallerIdentity
import com.artemchep.keyguard.feature.agent.AgentApprovalDialog
import com.artemchep.keyguard.feature.agent.rememberAgentApprovalCallerInfo
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.browser_extension_approval_origin
import com.artemchep.keyguard.res.browser_extension_approval_unverified
import com.artemchep.keyguard.res.ipc_approval_approve
import com.artemchep.keyguard.res.ipc_approval_deny
import org.jetbrains.compose.resources.stringResource

/**
 * The approval window of a browser extension's [request]: names the browser
 * and its extension, and denies or approves it.
 *
 * @param iconCompact See [AgentApprovalDialog].
 * @param message The message, given the name of the browser if known: the
 *   name of its pairing, or else of the verified browser.
 */
@Composable
internal fun BrowserApprovalDialog(
    request: BrowserApprovalRequest,
    icon: ImageVector,
    title: String,
    message: @Composable (who: String?) -> AnnotatedString,
    confirmEnabled: Boolean,
    onDeny: () -> Unit,
    onAllow: () -> Unit,
    iconCompact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val session = request.session
    val callerIdentity = remember(session.caller) {
        session.caller.toCallerIdentity()
    }
    val callerInfo = rememberAgentApprovalCallerInfo(callerIdentity)
    val pairingName = remember(request.pairingName) {
        request.pairingName.sanitizedAgentDisplayValue(MAX_AGENT_CALLER_NAME_LENGTH)
    }
    AgentApprovalDialog(
        icon = {
            Icon(
                modifier = if (iconCompact) Modifier else Modifier.fillMaxSize(),
                imageVector = icon,
                contentDescription = null,
            )
        },
        iconBadgePadding = 0.dp,
        iconCompact = iconCompact,
        title = title,
        // A release names the browser by its pairing, a pairing by the verified browser.
        message = { who -> message(pairingName ?: who) },
        callerInfo = callerInfo,
        details = browserApprovalDetails(request, callerInfo?.secondaryLabel),
        expiresAt = request.expiresAt,
        cancelText = stringResource(Res.string.ipc_approval_deny),
        confirmText = stringResource(Res.string.ipc_approval_approve),
        confirmEnabled = confirmEnabled,
        onCancel = onDeny,
        onConfirm = onAllow,
        content = content,
    )
}

/** The lines about the browser: whether its connector is verified, the process, and the extension. */
@Composable
private fun browserApprovalDetails(
    request: BrowserApprovalRequest,
    callerDetails: String?,
): List<String> {
    val session = request.session
    val origin = remember(session.origin) {
        session.origin.sanitizedAgentDisplayValue(MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH)
    }
    return listOfNotNull(
        // Without a verified connector, the browser process may be another program.
        stringResource(Res.string.browser_extension_approval_unverified)
            .takeUnless { session.verifiedHost },
        callerDetails,
        origin?.let { stringResource(Res.string.browser_extension_approval_origin, it) },
    )
}
