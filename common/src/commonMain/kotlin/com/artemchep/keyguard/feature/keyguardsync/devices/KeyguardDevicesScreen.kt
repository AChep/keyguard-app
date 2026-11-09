package com.artemchep.keyguard.feature.keyguardsync.devices

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.feature.home.vault.component.FlatDropdownSimpleExpressive
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_alert_removed_this_by_former_text
import com.artemchep.keyguard.res.app_link_removed_this_device_text
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.theme.Dimens
import org.jetbrains.compose.resources.stringResource

/** Warns that this device was removed from the group, if it was. */
@Composable
internal fun KeyguardDevicesRemovedNote(
    state: KeyguardDevicesState,
) {
    if (!state.removed) {
        return
    }
    val text = if (state.removedByRemoved) {
        Res.string.app_link_alert_removed_this_by_former_text
    } else {
        Res.string.app_link_removed_this_device_text
    }
    FlatSimpleNote(
        type = SimpleNote.Type.WARNING,
        text = stringResource(text),
    )
    Spacer(Modifier.height(8.dp))
}

/**
 * The [devices] of the group, and what adds one: a popup asks which of the two devices
 * shows the code, see [addDeviceActions]. Without actions there is no add item.
 */
@Composable
internal fun KeyguardDevicesList(
    devices: List<KeyguardDevicesState.Device>,
    addDeviceTitle: String,
    addDeviceActions: List<ContextItem>,
) {
    val canAddDevice = addDeviceActions.isNotEmpty()
    devices.forEachIndexed { index, device ->
        val shapeState = getShapeState(
            list = devices,
            index = index,
            predicate = { _, _ -> true },
        )
        KeyguardDeviceItem(
            device = device,
            // The add item closes the group.
            shapeState = if (canAddDevice) {
                shapeState and ShapeState.END.inv()
            } else {
                shapeState
            },
        )
    }
    if (canAddDevice) {
        KeyguardDevicesAddItem(
            title = addDeviceTitle,
            shapeState = if (devices.isEmpty()) ShapeState.ALL else ShapeState.END,
            actions = addDeviceActions,
        )
    }
}

@Composable
private fun KeyguardDevicesAddItem(
    title: String,
    shapeState: Int,
    actions: List<ContextItem>,
) {
    FlatDropdownSimpleExpressive(
        elevation = 1.dp,
        shapeState = shapeState,
        leading = {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
            )
        },
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        text = title,
                    )
                },
            )
        },
        dropdown = actions,
    )
}

@Composable
internal fun KeyguardDevicesLeaveItem(
    title: String,
    text: String,
    onClick: (() -> Unit)?,
) {
    FlatItemSimpleExpressive(
        leading = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Logout,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.error,
            )
        },
        text = {
            Text(
                text = text,
            )
        },
        onClick = onClick,
    )
}

/** How the devices of the group are doing: a status dot, and the status lines. */
@Composable
internal fun KeyguardDevicesHeader(
    header: KeyguardDevicesState.Header,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .padding(horizontal = Dimens.textHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyguardStatusDot(
            color = header.health.color(),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            val upToDateText = header.upToDateText
            if (upToDateText != null) {
                Text(
                    text = upToDateText,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                text = header.relayText,
                style = if (upToDateText != null) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.titleMedium
                },
            )
            val storageText = header.storageText
            if (storageText != null) {
                Text(
                    text = storageText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
