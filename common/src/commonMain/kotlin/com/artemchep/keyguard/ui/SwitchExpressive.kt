package com.artemchep.keyguard.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A switch with the Material 3 Expressive handle: the thumb carries a check
 * icon when the switch is on, and a close icon when it is off.
 *
 * The thumb grows to the size of its content, so the icons use
 * [SwitchDefaults.IconSize] instead of the usual icon size.
 */
@Composable
fun SwitchExpressive(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        thumbContent = {
            Icon(
                imageVector = if (checked) {
                    Icons.Rounded.Check
                } else {
                    Icons.Rounded.Close
                },
                contentDescription = null,
                modifier = Modifier
                    .size(SwitchDefaults.IconSize),
            )
        },
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
    )
}
