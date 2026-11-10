package com.artemchep.keyguard.feature.keyguardsync.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.auth.keyguard.pairing.keyguardPlatformIcon
import com.artemchep.keyguard.feature.home.vault.component.FlatDropdownSimpleExpressive
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_device_last_seen
import com.artemchep.keyguard.res.app_link_this_device
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.theme.monoFontFamily
import com.artemchep.keyguard.ui.theme.ok
import com.artemchep.keyguard.ui.theme.warning
import com.artemchep.keyguard.ui.time.rememberLocalizedRelativeTime
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun KeyguardStatusDot(
    color: Color,
) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color, CircleShape),
    )
}

@Composable
internal fun KeyguardDevicesState.Health.color(): Color = when (this) {
    KeyguardDevicesState.Health.ERROR -> MaterialTheme.colorScheme.error
    KeyguardDevicesState.Health.OK -> MaterialTheme.colorScheme.ok
    KeyguardDevicesState.Health.NEUTRAL -> MaterialTheme.colorScheme.outline
    KeyguardDevicesState.Health.WARNING -> MaterialTheme.colorScheme.warning
}

@Composable
internal fun KeyguardDeviceItem(
    device: KeyguardDevicesState.Device,
    shapeState: Int,
) {
    FlatDropdownSimpleExpressive(
        elevation = 1.dp,
        shapeState = shapeState,
        leading = {
            Icon(
                imageVector = keyguardPlatformIcon(device.platform),
                contentDescription = null,
            )
        },
        content = {
            FlatItemTextContent(
                title = {
                    KeyguardDeviceTitle(
                        device = device,
                    )
                },
                text = {
                    KeyguardDeviceText(
                        device = device,
                    )
                },
            )
        },
        trailing = {
            val health = device.health
            if (health != null) {
                KeyguardStatusDot(
                    color = health.color(),
                )
            }
        },
        dropdown = device.dropdown,
    )
}

@Composable
private fun KeyguardDeviceTitle(
    device: KeyguardDevicesState.Device,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            modifier = Modifier
                .weight(1f, fill = false),
            text = device.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (device.isThisDevice) {
            Text(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.shapes.small,
                    )
                    .padding(
                        horizontal = 6.dp,
                        vertical = 2.dp,
                    ),
                text = stringResource(Res.string.app_link_this_device),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun KeyguardDeviceText(
    device: KeyguardDevicesState.Device,
) {
    Column {
        Text(
            text = device.fingerprint,
            fontFamily = monoFontFamily,
        )
        Text(
            text = device.addedText,
        )
        val statusText = keyguardDeviceStatusText(device)
        if (statusText != null) {
            Text(
                text = statusText,
            )
        }
    }
}

/** Returns how this device reaches the [device], or `null` for this device. */
@Composable
internal fun keyguardDeviceStatusText(
    device: KeyguardDevicesState.Device,
): String? = device.statusText
    ?: device.lastSeen?.let { lastSeen ->
        stringResource(
            Res.string.app_link_device_last_seen,
            rememberLocalizedRelativeTime(lastSeen.at),
            lastSeen.transportText,
        )
    }
