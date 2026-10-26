package com.artemchep.keyguard.feature.home.settings.shortcuts

import androidx.compose.ui.input.key.Key
import com.artemchep.keyguard.common.model.GlobalShortcutBinding
import com.artemchep.keyguard.common.model.GlobalShortcutKey
import com.artemchep.keyguard.platform.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ShortcutRecorderTest {
    private fun ShortcutRecorder.press(
        key: Key,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
        meta: Boolean = false,
        altGraph: Boolean = false,
    ) = handle(key, ctrl, shift, alt, meta, altGraph)

    @Test fun `captures exact modifiers and ignores modifier keys`() {
        val recorder = ShortcutRecorder(Platform.Desktop.MacOS.Jvm)
        assertEquals(ShortcutRecorder.Result.Ignored, recorder.press(Key.CtrlLeft, ctrl = true))
        assertEquals(ShortcutRecorder.Result.Ignored, recorder.press(Key.MetaRight, ctrl = true, meta = true))
        assertEquals(
            ShortcutRecorder.Result.Captured(GlobalShortcutBinding(GlobalShortcutKey.K, ctrl = true, meta = true)),
            recorder.press(Key.K, ctrl = true, meta = true),
        )
        assertEquals(
            ShortcutRecorder.Result.Captured(GlobalShortcutBinding(GlobalShortcutKey.K, shift = true, meta = true)),
            recorder.press(Key.K, shift = true, meta = true),
        )
    }

    @Test fun `escape cancels while modified escape is a binding`() {
        val recorder = ShortcutRecorder(Platform.Desktop.Windows)
        assertEquals(ShortcutRecorder.Result.Cancel, recorder.press(Key.Escape))
        assertEquals(ShortcutRecorder.Result.Invalid, recorder.press(Key.Escape, shift = true))
        assertIs<ShortcutRecorder.Result.Captured>(recorder.press(Key.Escape, ctrl = true))
    }

    @Test fun `tab traverses and control tab records`() {
        val recorder = ShortcutRecorder(Platform.Desktop.Windows)
        assertEquals(ShortcutRecorder.Result.Traverse, recorder.press(Key.Tab))
        assertEquals(ShortcutRecorder.Result.Traverse, recorder.press(Key.Tab, shift = true))
        assertIs<ShortcutRecorder.Result.Captured>(recorder.press(Key.Tab, ctrl = true))
    }

    @Test fun `rejects plain and shift only keys including function keys`() {
        val recorder = ShortcutRecorder(Platform.Desktop.Windows)
        listOf(Key.A, Key.F1).forEach { key ->
            listOf(false, true).forEach { shift ->
                assertEquals(ShortcutRecorder.Result.Invalid, recorder.press(key, shift = shift))
            }
        }
    }

    @Test fun `rejects alt graph because it types characters`() {
        assertEquals(
            ShortcutRecorder.Result.Invalid,
            ShortcutRecorder(Platform.Desktop.Windows).press(Key.Q, ctrl = true, alt = true, altGraph = true),
        )
    }

    @Test fun `rejects platform unsupported and unmapped keys`() {
        assertEquals(
            ShortcutRecorder.Result.Invalid,
            ShortcutRecorder(Platform.Desktop.MacOS.Jvm).press(Key.Insert, meta = true),
        )
        assertEquals(
            ShortcutRecorder.Result.Invalid,
            ShortcutRecorder(Platform.Desktop.Windows).press(Key.Semicolon, ctrl = true),
        )
    }

    @Test fun `every shortcut key can be recorded from its compose key`() {
        val recorder = ShortcutRecorder(Platform.Desktop.Windows)
        assertEquals(GlobalShortcutKey.entries.toSet(), shortcutKeys.keys)
        assertEquals(GlobalShortcutKey.entries.size, shortcutKeys.values.toSet().size)
        shortcutKeys.forEach { (shortcutKey, key) ->
            assertEquals(
                ShortcutRecorder.Result.Captured(GlobalShortcutBinding(shortcutKey, ctrl = true, alt = true)),
                recorder.press(key, ctrl = true, alt = true),
            )
        }
    }
}
