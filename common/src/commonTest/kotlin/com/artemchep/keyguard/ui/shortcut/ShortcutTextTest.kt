package com.artemchep.keyguard.ui.shortcut

import androidx.compose.ui.text.font.FontWeight
import com.artemchep.keyguard.platform.Platform
import kotlin.test.Test
import kotlin.test.assertEquals

class ShortcutTextTest {
    @Test
    fun `modifiers preserve order and bold key spans`() {
        listOf(
            Platform.Desktop.MacOS.Jvm to listOf("⌃", "⇧", "⌥", "K"),
            Platform.Desktop.Windows to listOf("Ctrl", "Shift", "Alt", "K"),
            Platform.Desktop.Linux.native to listOf("Ctrl", "Shift", "Alt", "K"),
        ).forEach { (platform, labels) ->
            val text = shortcutText(
                keyLabel = "K",
                ctrl = true,
                shift = true,
                alt = true,
                platform = platform,
            )

            assertEquals(labels.joinToString("+"), text.text)
            assertEquals(labels, text.spanStyles.map { text.text.substring(it.start, it.end) })
            text.spanStyles.forEach { assertEquals(FontWeight.Bold, it.item.fontWeight) }
        }
    }

    @Test
    fun `macos distinguishes physical control from command`() {
        val platform = Platform.Desktop.MacOS.Jvm
        assertEquals("⌃+K", shortcutText("K", ctrl = true, platform = platform).text)
        assertEquals("⌘+K", shortcutText("K", meta = true, platform = platform).text)

        val text = shortcutText(
            keyLabel = "K",
            ctrl = true,
            shift = true,
            alt = true,
            meta = true,
            platform = platform,
        )
        val labels = listOf("⌃", "⌘", "⇧", "⌥", "K")
        assertEquals(labels.joinToString("+"), text.text)
        assertEquals(labels, text.spanStyles.map { text.text.substring(it.start, it.end) })
        text.spanStyles.forEach { assertEquals(FontWeight.Bold, it.item.fontWeight) }
    }

    @Test
    fun `plain shortcuts preserve the supplied key label and bold span`() {
        listOf(
            Platform.Desktop.MacOS.Jvm,
            Platform.Desktop.Windows,
            Platform.Desktop.Linux.native,
        ).forEach { platform ->
            listOf("K", "F1", "Page Up").forEach { label ->
                val text = shortcutText(keyLabel = label, platform = platform)

                assertEquals(label, text.text)
                val span = text.spanStyles.single()
                assertEquals(0, span.start)
                assertEquals(label.length, span.end)
                assertEquals(FontWeight.Bold, span.item.fontWeight)
            }
        }
    }
}
