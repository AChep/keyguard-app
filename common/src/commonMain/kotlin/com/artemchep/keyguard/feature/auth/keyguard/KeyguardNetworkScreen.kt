package com.artemchep.keyguard.feature.auth.keyguard

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.permissions.LocalNetworkPermissionNote
import com.artemchep.keyguard.feature.permissions.rememberLocalNetworkPermission
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardRelayDefaults
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_connection_allow_lan_text
import com.artemchep.keyguard.res.app_link_connection_allow_lan_title
import com.artemchep.keyguard.res.app_link_connection_ice_url_label
import com.artemchep.keyguard.res.app_link_connection_relay_storage_text
import com.artemchep.keyguard.res.app_link_connection_relay_storage_title
import com.artemchep.keyguard.res.app_link_connection_relay_token_label
import com.artemchep.keyguard.res.app_link_connection_relay_url_hint
import com.artemchep.keyguard.res.app_link_connection_relay_url_label
import com.artemchep.keyguard.ui.ConcealedFlatTextField
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.SwitchExpressive
import com.artemchep.keyguard.ui.UrlFlatTextField
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource

/**
 * The network settings of this device: the servers, a hint, and the local network and
 * relay storage switches.
 */
@Composable
internal fun KeyguardNetworkFields(
    network: KeyguardNetworkState,
) {
    KeyguardNetworkServerFields(
        advanced = network,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        modifier = Modifier
            .padding(horizontal = Dimens.textHorizontalPadding),
        text = stringResource(Res.string.app_link_connection_relay_url_hint),
        style = MaterialTheme.typography.bodySmall,
        color = LocalContentColor.current
            .combineAlpha(MediumEmphasisAlpha),
    )
    Spacer(Modifier.height(16.dp))
    KeyguardNetworkAllowLan(
        allowLan = network.allowLan.checked,
        onChange = network.allowLan.onChange,
    )
    KeyguardNetworkSwitch(
        title = stringResource(Res.string.app_link_connection_relay_storage_title),
        text = stringResource(Res.string.app_link_connection_relay_storage_text),
        checked = network.relayStorage.checked,
        onChange = network.relayStorage.onChange,
    )
}

@Composable
private fun KeyguardNetworkServerFields(
    advanced: KeyguardNetworkState,
) {
    UrlFlatTextField(
        modifier = Modifier
            .padding(horizontal = Dimens.fieldHorizontalPadding),
        label = stringResource(Res.string.app_link_connection_relay_url_label),
        placeholder = KeyguardRelayDefaults.RELAY_URL,
        value = advanced.relayUrl,
        shapeState = ShapeState.START,
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Next,
        ),
        leading = {
            IconBox(
                main = Icons.Outlined.Dns,
            )
        },
    )
    Spacer(Modifier.height(3.dp))
    UrlFlatTextField(
        modifier = Modifier
            .padding(horizontal = Dimens.fieldHorizontalPadding),
        label = stringResource(Res.string.app_link_connection_ice_url_label),
        placeholder = KeyguardRelayDefaults.ICE_URL,
        value = advanced.iceUrl,
        shapeState = ShapeState.CENTER,
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Next,
        ),
        leading = {
            IconBox(
                main = Icons.Outlined.Router,
            )
        },
    )
    Spacer(Modifier.height(3.dp))
    ConcealedFlatTextField(
        modifier = Modifier
            .padding(horizontal = Dimens.fieldHorizontalPadding),
        label = stringResource(Res.string.app_link_connection_relay_token_label),
        value = advanced.accessToken,
        shapeState = ShapeState.END,
        singleLine = true,
        maxLines = 1,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        leading = {
            IconBox(
                main = Icons.Outlined.Key,
            )
        },
    )
}

@Composable
private fun KeyguardNetworkAllowLan(
    allowLan: Boolean,
    onChange: ((Boolean) -> Unit)?,
) {
    KeyguardNetworkSwitch(
        title = stringResource(Res.string.app_link_connection_allow_lan_title),
        text = stringResource(Res.string.app_link_connection_allow_lan_text),
        checked = allowLan,
        onChange = onChange,
    )
    val localNetworkPermission = rememberLocalNetworkPermission()
        ?.takeIf { allowLan }
    ExpandedIfNotEmpty(
        valueOrNull = localNetworkPermission,
    ) { permission ->
        LocalNetworkPermissionNote(
            permission = permission,
        )
    }
}

@Composable
private fun KeyguardNetworkSwitch(
    title: String,
    text: String,
    checked: Boolean,
    onChange: ((Boolean) -> Unit)?,
) {
    FlatItemSimpleExpressive(
        title = {
            Text(
                text = title,
            )
        },
        text = {
            Text(
                text = text,
            )
        },
        trailing = {
            SwitchExpressive(
                checked = checked,
                onCheckedChange = null,
            )
        },
        onClick = onChange?.let { f ->
            // lambda
            {
                f(!checked)
            }
        },
    )
}
