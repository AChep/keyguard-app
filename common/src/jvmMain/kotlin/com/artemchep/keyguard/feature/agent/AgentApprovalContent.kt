package com.artemchep.keyguard.feature.agent

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.service.agent.AgentCallerIdentity
import com.artemchep.keyguard.common.service.agent.AgentRequest
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_EXECUTABLE_PATH_LENGTH
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_NAME_LENGTH
import com.artemchep.keyguard.common.service.agent.completeWithLog
import com.artemchep.keyguard.common.service.agent.sanitizedAgentDisplayValue
import com.artemchep.keyguard.feature.dialog.DialogContent
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.cancel
import com.artemchep.keyguard.res.ic_keyguard
import com.artemchep.keyguard.res.ok
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.FingerprintPlaneta
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.focus.FocusRequester2
import com.artemchep.keyguard.ui.focus.focusRequester2
import com.artemchep.keyguard.ui.icons.DropdownIcon
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.rememberCountdownSeconds
import com.artemchep.keyguard.ui.text.annotatedResource
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Renders the content for an agent (SSH/GPG) signing approval window.
 *
 * @param request The pending approval request.
 * @param title The title resource shown at the top of the dialog.
 * @param messageKnownApp The message resource used when the caller app is known.
 * @param messageUnknownApp The message resource used when the caller app is unknown.
 * @param keyName The display name of the key being used.
 * @param keyFingerprint The fingerprint of the key shown in the details.
 * @param onDismiss Called after the request has been resolved (either
 *   approved or denied) so the caller can close the window.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AgentApprovalContent(
    request: AgentRequest,
    title: StringResource,
    messageKnownApp: StringResource,
    messageUnknownApp: StringResource,
    keyName: String,
    keyFingerprint: String,
    onDismiss: () -> Unit,
) {
    var showKeyDetails by remember {
        mutableStateOf(false)
    }

    val focusRequester = remember {
        FocusRequester2()
    }
    LaunchedEffect(focusRequester) {
        delay(200L)
        focusRequester.requestFocus()
    }

    val applicationPresentation = rememberAgentApplicationPresentation(request.caller)
    val callerInfo = remember(
        request.caller,
        applicationPresentation.displayName,
    ) {
        buildAgentApprovalCallerInfo(
            request.caller,
            resolvedAppName = applicationPresentation.displayName,
        )
    }
    DialogContent(
        icon = {
            Box(
                modifier = Modifier
                    .aspectRatio(1f, matchHeightConstraintsFirst = true)
                    .fillMaxSize(),
            ) {
                FingerprintPlaneta(
                    modifier = Modifier
                        .fillMaxSize(),
                    fingerprint = keyFingerprint,
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp),
                ) {
                    Image(
                        modifier = Modifier
                            .size(24.dp),
                        painter = painterResource(Res.drawable.ic_keyguard),
                        contentDescription = null,
                    )
                }
            }
        },
        title = {
            Text(
                text = stringResource(title),
            )
        },
        content = {
            Column {
                val who = callerInfo?.primaryLabel
                Text(
                    modifier = Modifier
                        .padding(horizontal = Dimens.textHorizontalPadding),
                    text = if (who != null) {
                        annotatedResource(
                            messageKnownApp,
                            who to SpanStyle(
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    } else {
                        annotatedResource(messageUnknownApp)
                    },
                )
                callerInfo?.secondaryLabel?.let { details ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        modifier = Modifier
                            .padding(horizontal = Dimens.textHorizontalPadding),
                        text = details,
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalContentColor.current
                            .combineAlpha(DisabledEmphasisAlpha),
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                FlatItemLayoutExpressive(
                    leading = icon<RowScope>(Icons.Outlined.Terminal, Icons.Outlined.Key),
                    content = {
                        FlatItemTextContent(
                            title = {
                                Text(
                                    text = keyName,
                                )
                            },
                        )
                        ExpandedIfNotEmpty(
                            keyFingerprint.takeIf { showKeyDetails },
                        ) { fingerprint ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth(),
                            ) {
                                Text(
                                    text = fingerprint,
                                    fontFamily = FontFamily.Monospace,
                                    color = LocalContentColor.current
                                        .combineAlpha(MediumEmphasisAlpha),
                                )
                            }
                        }
                    },
                    trailing = {
                        DropdownIcon(
                            expanded = showKeyDetails,
                        )
                    },
                    onClick = {
                        showKeyDetails = !showKeyDetails
                    },
                )
            }
        },
        fill = true,
        actions = {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    modifier = Modifier
                        .weight(1f, fill = true),
                    onClick = {
                        request.completeWithLog(
                            value = false,
                            reason = "approval_dialog_cancel",
                        )
                        onDismiss()
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(
                        text = stringResource(Res.string.cancel),
                        maxLines = 1,
                    )
                }
                Button(
                    modifier = Modifier
                        .focusRequester2(focusRequester)
                        .weight(1f, fill = true),
                    onClick = {
                        request.completeWithLog(
                            value = true,
                            reason = "approval_dialog_confirm",
                        )
                        onDismiss()
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(
                        modifier = Modifier
                            .weight(1f, fill = false),
                        text = stringResource(Res.string.ok),
                        maxLines = 1,
                    )

                    val countdown = rememberCountdownSeconds(request.expiresAt)
                    if (countdown != null) {
                        Spacer(
                            modifier = Modifier
                                .width(4.dp),
                        )
                        Text(
                            text = "($countdown)",
                            color = LocalContentColor.current
                                .combineAlpha(MediumEmphasisAlpha),
                            maxLines = 1,
                        )
                    }
                }
            }
        },
    )
}

internal data class AgentApprovalCallerInfo(
    val primaryLabel: String?,
    val secondaryLabel: String?,
)

internal fun buildAgentApprovalCallerInfo(
    caller: AgentCallerIdentity?,
    resolvedAppName: String? = null,
): AgentApprovalCallerInfo? {
    caller ?: return null

    val resolvedName = resolvedAppName
        .sanitizedAgentDisplayValue(MAX_AGENT_CALLER_NAME_LENGTH)
    val appName = caller.appName
        .sanitizedAgentDisplayValue(MAX_AGENT_CALLER_NAME_LENGTH)
    val appBundlePath = caller.appBundlePath
        .sanitizedAgentDisplayValue(MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH)
    val processName = caller.processName
        .sanitizedAgentDisplayValue(MAX_AGENT_CALLER_NAME_LENGTH)
    // Native collectors include an authenticated signing identifier or a
    // verified instance prefix in appName. Keep that security label primary;
    // the OS-resolved friendly name and icon are presentation-only hints.
    val primaryLabel = appName ?: resolvedName ?: appBundlePath ?: processName
    val secondaryLabel = buildSet {
        resolvedName
            ?.takeIf { it != primaryLabel }
            ?.let(::add)
        // Always pair friendly labels with the package or bundle location when
        // one is available.
        appBundlePath
            ?.takeIf { it != primaryLabel }
            ?.let(::add)
        if (processName != null && primaryLabel != processName && appBundlePath != processName) {
            add(processName)
        }
        caller.pid.takeIf { it != 0 }?.let { add("pid $it") }
        caller.executablePath
            .sanitizedAgentDisplayValue(MAX_AGENT_CALLER_EXECUTABLE_PATH_LENGTH)
            ?.let(::add)
    }.takeIf { it.isNotEmpty() }?.joinToString(separator = " • ")

    return AgentApprovalCallerInfo(
        primaryLabel = primaryLabel,
        secondaryLabel = secondaryLabel,
    )
}
