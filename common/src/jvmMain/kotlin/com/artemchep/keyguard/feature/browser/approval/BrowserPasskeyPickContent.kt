package com.artemchep.keyguard.feature.browser.approval

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.service.browser.BrowserApprovalRequest
import com.artemchep.keyguard.common.service.browser.BrowserOperation
import com.artemchep.keyguard.common.service.browser.BrowserRelease
import com.artemchep.keyguard.feature.agent.AgentApprovalChoiceList
import com.artemchep.keyguard.feature.agent.LocalAgentApprovalInputGuard
import com.artemchep.keyguard.feature.agent.agentApprovalChoiceItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.browser_extension_passkey_another_device
import com.artemchep.keyguard.res.browser_extension_passkey_get_title
import org.jetbrains.compose.resources.stringResource

/**
 * The approval window of signing in to a page with one of the passkeys of
 * the [operation], which the user picks: the passkeys, and the notes of
 * the selected one.
 *
 * @param onAnotherDevice Hands the request back to the browser, which may
 *   use another device; `null` hides the option.
 */
@Composable
fun BrowserPasskeyPickContent(
    request: BrowserApprovalRequest,
    operation: BrowserOperation.PasskeyPick,
    selected: Int,
    onSelect: (Int) -> Unit,
    onAnotherDevice: (() -> Unit)?,
    busy: Boolean,
    onDeny: () -> Unit,
    onAllow: () -> Unit,
) {
    val items = remember(operation.choices) {
        operation.choices.map { it.release.toChoiceItem() }
    }
    val index = selected.coerceIn(operation.choices.indices)
    val release = operation.choices[index].release
    BrowserApprovalDialog(
        request = request,
        icon = Icons.Outlined.Key,
        iconCompact = true,
        title = stringResource(Res.string.browser_extension_passkey_get_title),
        message = { who -> browserApprovalMessage(operation, who = who) },
        confirmEnabled = !busy,
        onDeny = onDeny,
        onAllow = onAllow,
    ) {
        AgentApprovalChoiceList(
            items = items,
            selected = index,
            onSelect = onSelect.takeUnless { busy },
        )
        Spacer(modifier = Modifier.height(12.dp))
        BrowserReleasePageNotes(release)
        BrowserReleaseNote(release)
        if (onAnotherDevice != null) {
            Spacer(modifier = Modifier.height(8.dp))
            // It answers the prompt, so it waits for the input guard as the buttons do.
            TextButton(
                modifier = Modifier
                    .padding(horizontal = 8.dp),
                enabled = !busy && LocalAgentApprovalInputGuard.current.armed,
                onClick = onAnotherDevice,
            ) {
                Text(
                    text = stringResource(Res.string.browser_extension_passkey_another_device),
                )
            }
        }
    }
}

/** The item's name, and its username and account, as an approval prompt shows them. */
fun BrowserRelease.toChoiceItem() = agentApprovalChoiceItem(name, username, accountName)
