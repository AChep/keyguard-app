package com.artemchep.keyguard.ui.shortcut

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.artemchep.keyguard.feature.navigation.keyboard.KeyShortcut
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.key.toText

internal fun KeyShortcut.toText(platform: Platform = CurrentPlatform): AnnotatedString {
    val mac = platform is Platform.Desktop.MacOS
    return shortcutText(
        keyLabel = key.toText(),
        // Navigation shortcuts use Command as the primary modifier on macOS.
        ctrl = isCtrlPressed && !mac,
        shift = isShiftPressed,
        alt = isAltPressed,
        meta = isCtrlPressed && mac,
        platform = platform,
    )
}

internal fun shortcutText(
    keyLabel: String,
    ctrl: Boolean = false,
    shift: Boolean = false,
    alt: Boolean = false,
    meta: Boolean = false,
    platform: Platform = CurrentPlatform,
): AnnotatedString {
    val mac = platform is Platform.Desktop.MacOS
    return buildAnnotatedString {
        fun appendKey(label: String) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(label)
            }
        }

        fun appendModifier(label: String) {
            appendKey(label)
            append("+")
        }

        if (ctrl) appendModifier(if (mac) "⌃" else "Ctrl")
        // Keep Command first for existing macOS navigation shortcuts.
        if (meta && mac) appendModifier("⌘")
        if (shift) appendModifier(if (mac) "⇧" else "Shift")
        if (alt) appendModifier(if (mac) "⌥" else "Alt")
        if (meta && !mac) appendModifier(Key.MetaLeft.toText())
        appendKey(keyLabel)
    }
}
