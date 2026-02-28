package com.artemchep.keyguard.feature.sshagent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.service.sshagent.SshAgentApprovalRequest
import com.artemchep.keyguard.feature.dialog.DialogContent
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.text.annotatedResource
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource

/**
 * Renders the content for the SSH signing approval window.
 *
 * @param request The pending approval request.
 * @param onDismiss Called after the request has been resolved (either
 *   approved or denied) so the caller can close the window.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SshAgentApprovalContent(
    request: SshAgentApprovalRequest,
    onDismiss: () -> Unit,
) {
    DialogContent(
        icon = {
            Icon(Icons.Outlined.Key, contentDescription = null)
        },
        title = {
            Text(
                text = stringResource(Res.string.ssh_agent_request_approval_sign_title),
            )
        },
        content = {
            Column {
                val caller = request.caller
                val appName = caller?.appName?.takeIf { it.isNotBlank() }
                val processName = caller?.processName?.takeIf { it.isNotBlank() }
                val who = appName ?: processName
                Text(
                    modifier = Modifier
                        .padding(horizontal = Dimens.textHorizontalPadding),
                    text = if (who != null) {
                        annotatedResource(
                            Res.string.ssh_agent_request_approval_sign_message_known_app,
                            who to SpanStyle(
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    } else {
                        annotatedResource(Res.string.ssh_agent_request_approval_sign_message_unknown_app)
                    },
                )
                Spacer(modifier = Modifier.height(12.dp))
                FlatItemSimpleExpressive(
                    title = {
                        Text(
                            text = request.keyName,
                        )
                    },
                    text = {
                        Text(
                            text = request.keyFingerprint,
                            fontFamily = FontFamily.Monospace,
                        )
                    },
                    enabled = true,
                )

                if (caller != null && appName != null && processName != null && appName != processName) {
                    val pid = caller.pid.takeIf { it != 0 }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        modifier = Modifier
                            .padding(horizontal = Dimens.textHorizontalPadding),
                        text = buildString {
                            append(processName)
                            if (pid != null) {
                                append(" (pid ")
                                append(pid)
                                append(")")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalContentColor.current
                            .combineAlpha(DisabledEmphasisAlpha),
                    )
                }
            }
        },
        fill = true,
        actions = {
            TextButton(
                onClick = {
                    request.deferred.complete(false)
                    onDismiss()
                },
            ) {
                Text(stringResource(Res.string.cancel))
            }
            TextButton(
                onClick = {
                    request.deferred.complete(true)
                    onDismiss()
                },
            ) {
                Text(stringResource(Res.string.ok))
            }
        },
    )
}
