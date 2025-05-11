package com.artemchep.keyguard.ui.shortcut

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.artemchep.keyguard.feature.navigation.keyboard.KeyShortcut
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.ui.tooltip.Tooltip
import java.awt.event.KeyEvent

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

private fun KeyShortcut.toText(): AnnotatedString {
    val mapping = when (CurrentPlatform) {
        is Platform.Desktop.MacOS -> ::buildMacOsKeyShortcut
        else -> ::buildGenericKeyShortcut
    }
    return buildAnnotatedString {
        fun appendKey(str: String) {
            withStyle(
                SpanStyle(
                    fontWeight = FontWeight.Bold,
                )
            ) {
                append(str)
            }
        }

        if (isCtrlPressed) {
            val key = mapping(KeyShortcutService.Ctrl)
            appendKey(key)
            append("+")
        }
        if (isShiftPressed) {
            val key = mapping(KeyShortcutService.Shift)
            appendKey(key)
            append("+")
        }
        if (isAltPressed) {
            val key = mapping(KeyShortcutService.Alt)
            appendKey(key)
            append("+")
        }

        val keyStr = KeyEvent.getKeyText(key.nativeKeyCode)
        appendKey(keyStr)
    }
}

private enum class KeyShortcutService {
    Ctrl,
    Shift,
    Alt,
}

private fun buildMacOsKeyShortcut(key: KeyShortcutService): String = when (key) {
    KeyShortcutService.Ctrl -> "⌘"
    KeyShortcutService.Shift -> "⇧"
    KeyShortcutService.Alt -> "⌥"
}

private fun buildGenericKeyShortcut(key: KeyShortcutService): String = when (key) {
    KeyShortcutService.Ctrl -> "Ctrl"
    KeyShortcutService.Shift -> "Shift"
    KeyShortcutService.Alt -> "Alt"
}
