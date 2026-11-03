package com.artemchep.keyguard.ui.shortcut

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.keyboard.KeyShortcut
import com.artemchep.keyguard.ui.tooltip.Tooltip

@Composable
fun ShortcutTooltip(
    valueOrNull: KeyShortcut?,
    content: @Composable () -> Unit,
) {
    Tooltip(
        valueOrNull = valueOrNull,
        tooltip = { shortcut ->
            val shortcutText = shortcut.toText()
            Text(shortcutText)
        },
        content = content,
    )
}
