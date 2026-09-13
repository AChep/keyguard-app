package com.artemchep.keyguard.android.ipc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_NAME_LENGTH
import com.artemchep.keyguard.common.service.agent.sanitizedAgentDisplayValue
import com.artemchep.keyguard.feature.dialog.DialogContent
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.ipc_approval_approve
import com.artemchep.keyguard.res.ipc_approval_deny
import com.artemchep.keyguard.res.ipc_approval_message
import com.artemchep.keyguard.res.ipc_approval_no_keys
import com.artemchep.keyguard.res.ipc_approval_no_keys_continue
import com.artemchep.keyguard.res.ipc_approval_operation
import com.artemchep.keyguard.res.ipc_approval_operation_with_registration
import com.artemchep.keyguard.res.ipc_approval_title
import com.artemchep.keyguard.res.ipc_approval_unavailable
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.text.annotatedResource
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.GlobalExpressive
import com.artemchep.keyguard.ui.theme.LocalExpressive
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AndroidIpcApprovalScreen(
    state: AndroidIpcApprovalState,
) {
    when (state) {
        AndroidIpcApprovalState.Loading -> Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
        }

        AndroidIpcApprovalState.Unavailable -> Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Dimens.horizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(Res.string.ipc_approval_unavailable))
        }

        is AndroidIpcApprovalState.Ready -> AndroidIpcApprovalReadyScreen(state)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AndroidIpcApprovalReadyScreen(
    state: AndroidIpcApprovalState.Ready,
) {
    DialogContent(
        icon = {
            Icon(
                modifier = Modifier
                    .size(40.dp),
                imageVector = Icons.Outlined.Key,
                contentDescription = null,
            )
        },
        title = {
            Text(
                text = stringResource(Res.string.ipc_approval_title),
            )
        },
        content = {
            // The activity hosts the screen outside of a scaffold, so the
            // expressive flag has to be provided manually. Otherwise the
            // key items would end up with no background at all.
            CompositionLocalProvider(
                LocalExpressive provides GlobalExpressive.current,
            ) {
                Column {
                    AndroidIpcApprovalHeader(state)
                    Spacer(Modifier.height(12.dp))
                    AndroidIpcApprovalCandidates(state)
                }
            }
        },
        fill = true,
        actions = {
            AndroidIpcApprovalActions(state)
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AndroidIpcApprovalActions(
    state: AndroidIpcApprovalState.Ready,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(
            modifier = Modifier
                .weight(1f, fill = true),
            onClick = state.onDeny,
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(
                text = stringResource(Res.string.ipc_approval_deny),
                maxLines = 1,
            )
        }
        Button(
            modifier = Modifier
                .weight(1f, fill = true),
            onClick = state.onApprove,
            enabled = state.selectedKeyIds.isNotEmpty() || state.allowEmpty,
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(
                text = stringResource(Res.string.ipc_approval_approve),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun AndroidIpcApprovalHeader(
    state: AndroidIpcApprovalState.Ready,
) {
    val operationClause = stringResource(
        Res.string.ipc_approval_operation,
        stringResource(state.protocolLabel),
        stringResource(state.operation),
    )
    // The registration wraps the whole clause, so that it reads as
    // 'register with Keyguard and use GPG to ...' instead of
    // 'use GPG to register with Keyguard and ...'.
    val requestClause = if (state.registerApp) {
        stringResource(
            Res.string.ipc_approval_operation_with_registration,
            operationClause,
        )
    } else {
        operationClause
    }
    // The app label and the package name come from the package manager,
    // so they are controlled by the calling app and must not be able to
    // rearrange the prompt.
    val packageName = state.packageName
        .sanitizedAgentDisplayValue(MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH)
        .orEmpty()
    val appLabel = state.appLabel
        .sanitizedAgentDisplayValue(MAX_AGENT_CALLER_NAME_LENGTH)
        ?: packageName
    Text(
        modifier = Modifier
            .padding(horizontal = Dimens.textHorizontalPadding),
        text = annotatedResource(
            Res.string.ipc_approval_message,
            appLabel to SpanStyle(
                fontWeight = FontWeight.Bold,
            ),
            requestClause to SpanStyle(),
        ),
    )
    Spacer(Modifier.height(4.dp))
    Text(
        modifier = Modifier
            .padding(horizontal = Dimens.textHorizontalPadding),
        text = packageName,
        style = MaterialTheme.typography.labelSmall,
        color = LocalContentColor.current
            .combineAlpha(DisabledEmphasisAlpha),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun AndroidIpcApprovalCandidates(
    state: AndroidIpcApprovalState.Ready,
    modifier: Modifier = Modifier,
) {
    // The dialog content is already scrollable, so this column must not
    // introduce a nested scroller of its own.
    Column(
        modifier = modifier,
    ) {
        state.candidates.forEachIndexed { index, candidate ->
            val shapeState = getShapeState(
                list = state.candidates,
                index = index,
            ) { _, _ -> true }
            AndroidIpcApprovalCandidate(
                state = state,
                candidate = candidate,
                shapeState = shapeState,
            )
        }
        if (state.candidates.isEmpty()) {
            Text(
                modifier = Modifier
                    .padding(horizontal = Dimens.textHorizontalPadding),
                text = if (state.allowEmpty) {
                    stringResource(Res.string.ipc_approval_no_keys_continue)
                } else {
                    stringResource(Res.string.ipc_approval_no_keys)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (state.allowEmpty) {
                    LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha)
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

@Composable
private fun AndroidIpcApprovalCandidate(
    state: AndroidIpcApprovalState.Ready,
    candidate: AndroidIpcApprovalCoordinator.Candidate,
    shapeState: Int = ShapeState.ALL,
) {
    val selected = candidate.id in state.selectedKeyIds
    val onSelect = {
        state.onSelect(candidate.id)
    }
    FlatItemLayoutExpressive(
        shapeState = shapeState,
        leading = {
            if (state.allowMultiple) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = {
                        onSelect()
                    },
                )
            } else {
                RadioButton(
                    selected = selected,
                    onClick = onSelect,
                )
            }
        },
        content = {
            Text(
                text = candidate.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (candidate.description.isNotBlank()) {
                Text(
                    text = candidate.description,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        onClick = onSelect,
    )
}
