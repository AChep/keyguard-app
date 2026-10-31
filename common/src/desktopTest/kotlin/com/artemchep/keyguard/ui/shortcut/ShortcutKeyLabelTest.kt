package com.artemchep.keyguard.ui.shortcut

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.font.FontWeight
import com.artemchep.keyguard.common.model.GlobalShortcutBinding
import com.artemchep.keyguard.common.model.GlobalShortcutKey
import com.artemchep.keyguard.feature.home.settings.shortcuts.displayText
import com.artemchep.keyguard.feature.navigation.keyboard.KeyShortcut
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.key.toText
import kotlin.test.Test
import kotlin.test.assertEquals

// Native key labels need a platform implementation rather than Android's host-test stubs.
class ShortcutKeyLabelTest {
    @Test
    fun `navigation shortcuts preserve modifier order and bold key spans`() {
        val shortcut = KeyShortcut(Key.K, isCtrlPressed = true, isShiftPressed = true, isAltPressed = true)
        val keyLabel = Key.K.toText()
        listOf(
            Platform.Desktop.MacOS.Jvm to listOf("⌘", "⇧", "⌥", keyLabel),
            Platform.Desktop.Windows to listOf("Ctrl", "Shift", "Alt", keyLabel),
            Platform.Desktop.Linux.native to listOf("Ctrl", "Shift", "Alt", keyLabel),
        ).forEach { (platform, labels) ->
            val text = shortcut.toText(platform)
            assertEquals(labels.joinToString("+"), text.text)
            assertEquals(labels, text.spanStyles.map { text.text.substring(it.start, it.end) })
            text.spanStyles.forEach { assertEquals(FontWeight.Bold, it.item.fontWeight) }
        }
    }

    @Test
    fun `global shortcuts distinguish physical control from command`() {
        val key = GlobalShortcutKey.K
        val keyLabel = Key.K.toText()
        val platform = Platform.Desktop.MacOS.Jvm
        assertEquals("⌃+$keyLabel", GlobalShortcutBinding(key, ctrl = true).displayText(platform))
        assertEquals("⌘+$keyLabel", GlobalShortcutBinding(key, meta = true).displayText(platform))
        assertEquals(
            "⌃+⌘+⇧+⌥+$keyLabel",
            GlobalShortcutBinding(
                key,
                ctrl = true,
                shift = true,
                alt = true,
                meta = true,
            ).displayText(platform),
        )
    }

    @Test
    fun `global shortcuts use the key names of in-app shortcuts`() {
        val platform = Platform.Desktop.Windows
        assertEquals(
            "Ctrl+Shift+Alt+${Key.Y.toText()}",
            GlobalShortcutBinding(GlobalShortcutKey.Y, ctrl = true, shift = true, alt = true).displayText(platform),
        )
        assertEquals(
            "Ctrl+${Key.Five.toText()}",
            GlobalShortcutBinding(GlobalShortcutKey.Digit5, ctrl = true).displayText(platform),
        )
        assertEquals(
            "Ctrl+${Key.F1.toText()}",
            GlobalShortcutBinding(GlobalShortcutKey.F1, ctrl = true).displayText(platform),
        )
        assertEquals(Key.K.toText(), KeyShortcut(Key.K).toText(Platform.Desktop.MacOS.Jvm).text)
        assertEquals(
            "${Key.MetaLeft.toText()}+K",
            shortcutText("K", meta = true, platform = platform).text,
        )
    }
}
