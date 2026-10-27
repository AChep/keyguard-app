package com.artemchep.keyguard.feature.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.service.agent.AgentRequest
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.cancel
import com.artemchep.keyguard.res.ok
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
import com.artemchep.keyguard.ui.theme.combineAlpha
import kotlinx.coroutines.delay
import kotlin.time.Instant
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Renders the content for an agent (SSH/GPG) signing approval window.
 *
 * @param request The pending approval request.
 * @param protocol The protocol name, such as SSH or GPG, passed to the
 *   [title] and message resources.
 * @param title The title resource shown at the top of the dialog.
 * @param messageKnownApp The message resource used when the caller app is known.
 * @param messageUnknownApp The message resource used when the caller app is unknown.
 * @param keyName The display name of the key being used, as known when
 *   the request was created. A request created while the vault was locked
 *   only knows the cached name, which may be just the fingerprint.
 * @param keyFingerprint The fingerprint of the key shown in the details.
 * @param cipherId Identity of the vault entry holding the key, when known;
 *   once the vault is unlocked the entry's title replaces [keyName].
 * @param keyIcon The key type shown beside its name and fingerprint.
 * @param onDecision Answers the request; the reason is only logged.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AgentApprovalContent(
    request: AgentRequest,
    protocol: StringResource,
    title: StringResource,
    messageKnownApp: StringResource,
    messageUnknownApp: StringResource,
    keyName: String,
    keyFingerprint: String,
    cipherId: String?,
    keyIcon: @Composable RowScope.() -> Unit = icon<RowScope>(Icons.Outlined.Terminal, Icons.Outlined.Key),
    onDecision: (approved: Boolean, reason: String) -> Unit,
) {
    var showKeyDetails by remember {
        mutableStateOf(false)
    }

    val protocolName = stringResource(protocol)
    val callerInfo = rememberAgentApprovalCallerInfo(request.caller)
    AgentApprovalDialog(
        icon = {
            FingerprintPlaneta(
                modifier = Modifier
                    .fillMaxSize(),
                fingerprint = keyFingerprint,
            )
        },
        title = stringResource(title, protocolName),
        message = { who ->
            if (who != null) {
                annotatedResource(
                    messageKnownApp,
                    who to SpanStyle(
                        fontWeight = FontWeight.Bold,
                    ),
                    protocolName to SpanStyle(),
                )
            } else {
                annotatedResource(
                    messageUnknownApp,
                    protocolName to SpanStyle(),
                )
            }
        },
        callerInfo = callerInfo,
        expiresAt = request.expiresAt,
        cancelText = stringResource(Res.string.cancel),
        confirmText = stringResource(Res.string.ok),
        onCancel = {
            onDecision(false, "approval_dialog_cancel")
        },
        onConfirm = {
            onDecision(true, "approval_dialog_confirm")
        },
    ) {
        FlatItemLayoutExpressive(
            leading = keyIcon,
            content = {
                val cipherName = rememberCipherName(
                    cipherId = cipherId,
                    initialValue = keyName,
                )
                FlatItemTextContent(
                    title = {
                        Text(
                            text = cipherName,
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
}

/**
 * The cancel and confirm buttons of an approval window. The confirm
 * button takes the initial focus and counts down to [expiresAt].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AgentApprovalButtons(
    expiresAt: Instant?,
    cancelText: String,
    confirmText: String,
    confirmEnabled: Boolean = true,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val focusRequester = remember {
        FocusRequester2()
    }
    LaunchedEffect(focusRequester) {
        delay(200L)
        focusRequester.requestFocus()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(
            modifier = Modifier
                .weight(1f, fill = true),
            onClick = onCancel,
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(
                text = cancelText,
                maxLines = 1,
            )
        }
        Button(
            modifier = Modifier
                .focusRequester2(focusRequester)
                .weight(1f, fill = true),
            enabled = confirmEnabled,
            onClick = onConfirm,
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(
                modifier = Modifier
                    .weight(1f, fill = false),
                text = confirmText,
                maxLines = 1,
            )

            val countdown = rememberCountdownSeconds(expiresAt)
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
}

@Composable
private fun rememberCipherName(
    cipherId: String?,
    initialValue: String,
): String {
    cipherId
        ?: return initialValue

    val getCiphers = koinInject<GetCiphers>()
    val cipherNameFlow = remember(getCiphers, cipherId) {
        getCiphers()
            .map { ciphers ->
                ciphers
                    .firstOrNull { it.id == cipherId }
                    ?.name
            }
    }
    return cipherNameFlow.collectAsState(initial = initialValue).value
        ?: initialValue
}
