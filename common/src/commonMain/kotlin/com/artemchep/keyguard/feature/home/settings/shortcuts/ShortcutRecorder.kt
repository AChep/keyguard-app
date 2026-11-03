package com.artemchep.keyguard.feature.home.settings.shortcuts

import androidx.compose.ui.input.key.Key
import com.artemchep.keyguard.common.model.GlobalShortcutBinding
import com.artemchep.keyguard.common.model.GlobalShortcutKey
import com.artemchep.keyguard.platform.Platform

// Records a pressed key and names a saved one, like the in-app shortcut tooltips.
internal val shortcutKeys: Map<GlobalShortcutKey, Key> =
    mapOf(
        GlobalShortcutKey.A to Key.A,
        GlobalShortcutKey.B to Key.B,
        GlobalShortcutKey.C to Key.C,
        GlobalShortcutKey.D to Key.D,
        GlobalShortcutKey.E to Key.E,
        GlobalShortcutKey.F to Key.F,
        GlobalShortcutKey.G to Key.G,
        GlobalShortcutKey.H to Key.H,
        GlobalShortcutKey.I to Key.I,
        GlobalShortcutKey.J to Key.J,
        GlobalShortcutKey.K to Key.K,
        GlobalShortcutKey.L to Key.L,
        GlobalShortcutKey.M to Key.M,
        GlobalShortcutKey.N to Key.N,
        GlobalShortcutKey.O to Key.O,
        GlobalShortcutKey.P to Key.P,
        GlobalShortcutKey.Q to Key.Q,
        GlobalShortcutKey.R to Key.R,
        GlobalShortcutKey.S to Key.S,
        GlobalShortcutKey.T to Key.T,
        GlobalShortcutKey.U to Key.U,
        GlobalShortcutKey.V to Key.V,
        GlobalShortcutKey.W to Key.W,
        GlobalShortcutKey.X to Key.X,
        GlobalShortcutKey.Y to Key.Y,
        GlobalShortcutKey.Z to Key.Z,
        GlobalShortcutKey.Digit0 to Key.Zero,
        GlobalShortcutKey.Digit1 to Key.One,
        GlobalShortcutKey.Digit2 to Key.Two,
        GlobalShortcutKey.Digit3 to Key.Three,
        GlobalShortcutKey.Digit4 to Key.Four,
        GlobalShortcutKey.Digit5 to Key.Five,
        GlobalShortcutKey.Digit6 to Key.Six,
        GlobalShortcutKey.Digit7 to Key.Seven,
        GlobalShortcutKey.Digit8 to Key.Eight,
        GlobalShortcutKey.Digit9 to Key.Nine,
        GlobalShortcutKey.Space to Key.Spacebar,
        GlobalShortcutKey.Enter to Key.Enter,
        GlobalShortcutKey.Tab to Key.Tab,
        GlobalShortcutKey.Escape to Key.Escape,
        GlobalShortcutKey.Backspace to Key.Backspace,
        GlobalShortcutKey.Delete to Key.Delete,
        GlobalShortcutKey.Insert to Key.Insert,
        GlobalShortcutKey.Home to Key.MoveHome,
        GlobalShortcutKey.End to Key.MoveEnd,
        GlobalShortcutKey.PageUp to Key.PageUp,
        GlobalShortcutKey.PageDown to Key.PageDown,
        GlobalShortcutKey.ArrowLeft to Key.DirectionLeft,
        GlobalShortcutKey.ArrowRight to Key.DirectionRight,
        GlobalShortcutKey.ArrowUp to Key.DirectionUp,
        GlobalShortcutKey.ArrowDown to Key.DirectionDown,
        GlobalShortcutKey.F1 to Key.F1,
        GlobalShortcutKey.F2 to Key.F2,
        GlobalShortcutKey.F3 to Key.F3,
        GlobalShortcutKey.F4 to Key.F4,
        GlobalShortcutKey.F5 to Key.F5,
        GlobalShortcutKey.F6 to Key.F6,
        GlobalShortcutKey.F7 to Key.F7,
        GlobalShortcutKey.F8 to Key.F8,
        GlobalShortcutKey.F9 to Key.F9,
        GlobalShortcutKey.F10 to Key.F10,
        GlobalShortcutKey.F11 to Key.F11,
        GlobalShortcutKey.F12 to Key.F12,
    )

private val recordedKeys: Map<Key, GlobalShortcutKey> =
    shortcutKeys.entries.associate { (shortcutKey, key) -> key to shortcutKey }

private val modifierKeys =
    setOf(
        Key.CtrlLeft,
        Key.CtrlRight,
        Key.ShiftLeft,
        Key.ShiftRight,
        Key.AltLeft,
        Key.AltRight,
        Key.MetaLeft,
        Key.MetaRight,
    )

/** Turns a key press in the recorder into a draft binding. */
internal class ShortcutRecorder(
    private val platform: Platform,
) {
    sealed interface Result {
        data object Ignored : Result

        data object Cancel : Result

        data object Traverse : Result

        data object Invalid : Result

        data class Captured(
            val binding: GlobalShortcutBinding,
        ) : Result
    }

    @Suppress("LongParameterList")
    fun handle(
        key: Key,
        ctrl: Boolean,
        shift: Boolean,
        alt: Boolean,
        meta: Boolean,
        altGraph: Boolean,
    ): Result {
        val command = ctrl || alt || meta
        return when {
            key in modifierKeys -> {
                Result.Ignored
            }

            key == Key.Escape && !command && !shift -> {
                Result.Cancel
            }

            key == Key.Tab && !command -> {
                Result.Traverse
            }

            altGraph -> {
                Result.Invalid
            }

            else -> {
                val shortcutKey = recordedKeys[key] ?: return Result.Invalid
                val binding = GlobalShortcutBinding(shortcutKey, ctrl, shift, alt, meta)
                if (binding.isValid(platform)) Result.Captured(binding) else Result.Invalid
            }
        }
    }
}
