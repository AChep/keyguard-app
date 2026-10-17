package com.artemchep.keyguard.feature.agent

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.service.agent.AgentApprovalCallerInfo
import com.artemchep.keyguard.common.service.agent.AgentCallerIdentity
import com.artemchep.keyguard.common.service.agent.buildAgentApprovalCallerInfo
import com.artemchep.keyguard.feature.dialog.DialogContent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.ic_keyguard
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.painterResource
import kotlin.time.Instant

/**
 * The layout every approval window shares: a badged icon, the title, who
 * asks and the [details] about them, the request [content], and the
 * deny and approve buttons that count down to [expiresAt].
 *
 * @param message The message, given the verified caller name if known.
 */
@Composable
fun AgentApprovalDialog(
    icon: @Composable BoxScope.() -> Unit,
    title: String,
    message: @Composable (who: String?) -> AnnotatedString,
    callerInfo: AgentApprovalCallerInfo?,
    expiresAt: Instant?,
    cancelText: String,
    confirmText: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    details: List<String> = listOfNotNull(callerInfo?.secondaryLabel),
    iconBadgePadding: Dp = 4.dp,
    confirmEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    DialogContent(
        icon = {
            AgentApprovalIcon(
                badgePadding = iconBadgePadding,
                content = icon,
            )
        },
        title = {
            Text(
                text = title,
            )
        },
        content = {
            Column {
                Text(
                    modifier = Modifier
                        .padding(horizontal = Dimens.textHorizontalPadding),
                    text = message(callerInfo?.primaryLabel),
                )
                details.forEach { text ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        modifier = Modifier
                            .padding(horizontal = Dimens.textHorizontalPadding),
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalContentColor.current
                            .combineAlpha(DisabledEmphasisAlpha),
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                content()
            }
        },
        fill = true,
        actions = {
            AgentApprovalButtons(
                expiresAt = expiresAt,
                cancelText = cancelText,
                confirmText = confirmText,
                confirmEnabled = confirmEnabled,
                onCancel = onCancel,
                onConfirm = onConfirm,
            )
        },
    )
}

/** The display lines of the [caller], with the application name the OS reports. */
@Composable
fun rememberAgentApprovalCallerInfo(
    caller: AgentCallerIdentity?,
): AgentApprovalCallerInfo? {
    val applicationPresentation = rememberAgentApplicationPresentation(caller)
    return remember(
        caller,
        applicationPresentation.displayName,
    ) {
        buildAgentApprovalCallerInfo(
            caller,
            resolvedAppName = applicationPresentation.displayName,
        )
    }
}

@Composable
private fun AgentApprovalIcon(
    badgePadding: Dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f, matchHeightConstraintsFirst = true)
            .fillMaxSize(),
    ) {
        content()
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(badgePadding),
        ) {
            Image(
                modifier = Modifier
                    .size(24.dp),
                painter = painterResource(Res.drawable.ic_keyguard),
                contentDescription = null,
            )
        }
    }
}
