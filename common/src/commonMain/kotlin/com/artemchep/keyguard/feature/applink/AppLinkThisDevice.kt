package com.artemchep.keyguard.feature.applink

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.feature.auth.keyguard.pairing.keyguardPlatformIcon
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_connection_title
import com.artemchep.keyguard.res.app_link_device_name_label
import com.artemchep.keyguard.res.app_link_this_device
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.theme.monoFontFamily
import org.jetbrains.compose.resources.stringResource

/**
 * This device: its name and the relay it reaches the other devices through. Both belong
 * to the device, not to a pairing, so they are set here, linked or not.
 *
 * @param fingerprint the fingerprint of this device, once it is linked.
 */
@Composable
internal fun AppLinkThisDeviceSection(
    thisDevice: AppLinkState.ThisDevice,
    fingerprint: String?,
) {
    Section(
        text = stringResource(Res.string.app_link_this_device),
    )
    AppLinkThisDeviceNameItem(
        thisDevice = thisDevice,
        fingerprint = fingerprint,
    )
    AppLinkThisDeviceRelayItem(
        thisDevice = thisDevice,
    )
}

/** The name of this device; renames it. */
@Composable
private fun AppLinkThisDeviceNameItem(
    thisDevice: AppLinkState.ThisDevice,
    fingerprint: String?,
) {
    FlatItemSimpleExpressive(
        elevation = 1.dp,
        shapeState = ShapeState.START,
        leading = {
            Icon(
                imageVector = keyguardPlatformIcon(thisDevice.platform),
                contentDescription = null,
            )
        },
        title = {
            Text(
                text = stringResource(Res.string.app_link_device_name_label),
            )
        },
        text = {
            Column {
                Text(
                    text = thisDevice.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (fingerprint != null) {
                    Text(
                        text = fingerprint,
                        fontFamily = monoFontFamily,
                    )
                }
            }
        },
        trailing = {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = null,
            )
        },
        onClick = thisDevice.onRename,
    )
}

/** The relay this device reaches the other ones through; opens its settings. */
@Composable
private fun AppLinkThisDeviceRelayItem(
    thisDevice: AppLinkState.ThisDevice,
) {
    FlatItemSimpleExpressive(
        elevation = 1.dp,
        shapeState = ShapeState.END,
        leading = {
            Icon(
                imageVector = Icons.Outlined.Dns,
                contentDescription = null,
            )
        },
        title = {
            Text(
                text = stringResource(Res.string.app_link_connection_title),
            )
        },
        text = {
            Column {
                Text(
                    text = thisDevice.relayText,
                )
                val lanText = thisDevice.lanText
                if (lanText != null) {
                    Text(
                        text = lanText,
                    )
                }
            }
        },
        trailing = {
            ChevronIcon()
        },
        onClick = thisDevice.onConnection,
    )
}
