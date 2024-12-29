package autotype

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import platform.CoreGraphics.CGEventCreateKeyboardEvent
import platform.CoreGraphics.CGEventPost

private const val KEY_PRESS_DELAY_MS = 10L

internal class AutoTypeCommand(
    private val list: List<suspend () -> Unit>,
) {
    companion object {
        fun ofOrThrow(payload: String) = convertToAutoTypeCommandsOrThrow(payload)

        suspend fun executeOrThrow(payload: String) = ofOrThrow(payload)
            .execute()
    }

    suspend fun execute() {
        list.forEach { command ->
            command.invoke()
        }
    }
}

/**
 * Returns a list of auto type commands, or throws if the
 * given payload is not possible to type.
 */
private fun convertToAutoTypeCommandsOrThrow(
    payload: String,
): AutoTypeCommand {
    val shiftKeyCode = keyMapping["shift"]!!.toUShort()

    val out: MutableList<suspend () -> Unit> = mutableListOf()
    payload.forEach { char ->
        // In addition to the key event, send the
        // shift key.
        val requiresShiftPress = AutoTypeUtil.requiresShiftPress(char)
        if (requiresShiftPress) {
            out += {
                keyDown(shiftKeyCode)
                keyDown(shiftKeyCode) // for some reason first shift press gets eaten
            }
        }

        val charKeyCode = keyMapping[char.lowercase()]
            ?.toUShort()
        requireNotNull(charKeyCode) {
            "Could not find a key code for '$char' symbol!"
        }
        out += {
            keyDown(charKeyCode)
            keyUp(charKeyCode)
        }

        // Release the shift key.
        if (requiresShiftPress) {
            out += {
                keyUp(shiftKeyCode)
            }
        }
    }
    return AutoTypeCommand(out)
}

private suspend fun keyDown(
    keyCode: UShort,
) = emitKeyPress(
    keyCode = keyCode,
    keyDown = true,
)

private suspend fun keyUp(
    keyCode: UShort,
) = emitKeyPress(
    keyCode = keyCode,
    keyDown = false,
)

@OptIn(ExperimentalForeignApi::class)
private suspend fun emitKeyPress(
    keyCode: UShort,
    keyDown: Boolean,
) {
    val keyEvent = CGEventCreateKeyboardEvent(null, keyCode, keyDown)
    val keyTap = 0u // cghidEventTap
    CGEventPost(keyTap, keyEvent)
    delay(KEY_PRESS_DELAY_MS)
}
