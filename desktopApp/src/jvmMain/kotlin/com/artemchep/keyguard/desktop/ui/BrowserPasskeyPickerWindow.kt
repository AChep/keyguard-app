package com.artemchep.keyguard.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_NAME_LENGTH
import com.artemchep.keyguard.common.service.agent.sanitizedAgentDisplayValue
import com.artemchep.keyguard.common.service.browser.BrowserPasskeyPicker
import com.artemchep.keyguard.common.service.browser.BrowserPasskeyPickers
import com.artemchep.keyguard.platform.lifecycle.LePlatformLifecycleProvider
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.browser_extension_approval_unverified
import com.artemchep.keyguard.res.browser_extension_history_pairing
import com.artemchep.keyguard.res.browser_extension_passkey_another_device
import com.artemchep.keyguard.res.browser_extension_passkey_create_title
import com.artemchep.keyguard.res.browser_extension_passkey_rp_id
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource

/**
 * Shows a vault window for each passkey that a browser extension asks to
 * save, where the user picks the item. Reads the requests here, so their
 * transitions do not recompose the whole application.
 */
@Composable
internal fun ApplicationScope.BrowserPasskeyPickerHost(
    pickers: BrowserPasskeyPickers,
    processLifecycleProvider: LePlatformLifecycleProvider,
) {
    val list by pickers.passkeyPickers.collectAsState()
    list.forEach { picker ->
        key(picker.id) {
            PasskeyVaultWindow(
                requestId = picker.id,
                title = stringResource(Res.string.browser_extension_passkey_create_title),
                reason = picker.rpId,
                expiresAt = picker.expiresAt,
                mode = picker.mode,
                focusTag = "BrowserPasskeyPicker",
                processLifecycleProvider = processLifecycleProvider,
                onCancel = picker.onDismiss,
                footer = {
                    BrowserPasskeyPickerFooter(picker)
                },
            )
        }
    }
}

/**
 * The page, the relying party and the browser that ask, as an approval prompt
 * names them, since the pick approves the request; and the way to hand it back
 * to the browser.
 */
@Composable
private fun BrowserPasskeyPickerFooter(
    picker: BrowserPasskeyPicker,
) {
    val origin = remember(picker.origin) {
        picker.origin.sanitizedAgentDisplayValue(MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH)
    }
    val rpId = remember(picker.rpId) {
        picker.rpId.sanitizedAgentDisplayValue(MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH)
    }
    val pairingName = remember(picker.pairingName) {
        picker.pairingName.sanitizedAgentDisplayValue(MAX_AGENT_CALLER_NAME_LENGTH)
    }
    HorizontalDivider()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        ) {
            // Wraps instead of cutting off: a lookalike origin must show in full.
            Text(
                text = origin.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
            )
            // May be of another site, which lists the page among its related origins.
            if (rpId != null) {
                BrowserPasskeyPickerNote(
                    text = stringResource(Res.string.browser_extension_passkey_rp_id, rpId),
                )
            }
            if (pairingName != null) {
                BrowserPasskeyPickerNote(
                    text = stringResource(Res.string.browser_extension_history_pairing, pairingName),
                )
            }
            // Without a verified connector, the browser process may be another program.
            if (!picker.session.verifiedHost) {
                BrowserPasskeyPickerNote(
                    text = stringResource(Res.string.browser_extension_approval_unverified),
                )
            }
        }
        TextButton(
            onClick = picker.onAnotherDevice,
        ) {
            Text(
                text = stringResource(Res.string.browser_extension_passkey_another_device),
            )
        }
    }
}

@Composable
private fun BrowserPasskeyPickerNote(
    text: String,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalContentColor.current
            .combineAlpha(MediumEmphasisAlpha),
    )
}
