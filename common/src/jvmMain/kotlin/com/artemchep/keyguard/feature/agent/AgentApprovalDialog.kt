package com.artemchep.keyguard.feature.agent

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
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
 * asks and the [details] about them, which expand on click, the request
 * [content], and the
 * deny and approve buttons that count down to [expiresAt]. The buttons
 * arm after 1 s of focus with no input, see [AgentApprovalInputGuard].
 *
 * @param message The message, given the verified caller name if known.
 * @param iconCompact Shows the icon at the size of a dialog's, without the badge.
 * @param nextStep `true` for a later step of the prompt, see [AgentApprovalButtons].
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
    iconCompact: Boolean = false,
    confirmEnabled: Boolean = true,
    nextStep: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    DialogContent(
        icon = {
            if (iconCompact) {
                Box(content = icon)
            } else {
                AgentApprovalIcon(
                    badgePadding = iconBadgePadding,
                    content = icon,
                )
            }
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
                if (details.isNotEmpty()) {
                    AgentApprovalDetails(details)
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
                nextStep = nextStep,
            )
        },
    )
}

/**
 * The [details] about who asks: one line of them, and each one in full
 * once clicked.
 */
@Composable
private fun AgentApprovalDetails(
    details: List<String>,
) {
    var expanded by remember { mutableStateOf(false) }
    // Nothing to expand if the line shows everything.
    var overflowed by remember(details) { mutableStateOf(false) }
    val summary = remember(details) {
        details.joinToString(separator = " • ")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.textHorizontalPadding - DetailsInset)
            .clip(MaterialTheme.shapes.small)
            .clickable(
                enabled = expanded || overflowed,
                role = Role.Button,
            ) {
                expanded = !expanded
            }
            .padding(horizontal = DetailsInset, vertical = 4.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ProvideTextStyle(
            MaterialTheme.typography.labelSmall
                .copy(color = LocalContentColor.current.combineAlpha(DisabledEmphasisAlpha)),
        ) {
            if (expanded) {
                details.forEach { text ->
                    Text(text = text)
                }
            } else {
                Text(
                    text = summary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { overflowed = it.hasVisualOverflow },
                )
            }
        }
    }
}

/** The room around the details for the click highlight; their text stays in line with the message. */
private val DetailsInset = 4.dp

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
