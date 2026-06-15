package com.artemchep.autotype

import com.artemchep.jna.DesktopLibJna
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean

private const val HOTKEY_RESULT_UNSUPPORTED_PLATFORM: Int = -1
private const val HOTKEY_RESULT_UNSUPPORTED_SESSION: Int = -2
private const val HOTKEY_RESULT_INVALID_SHORTCUT: Int = -3
private const val HOTKEY_RESULT_UNAVAILABLE: Int = -4
private const val HOTKEY_RESULT_INTERNAL_ERROR: Int = -5

private const val MACOS_MODIFIER_COMMAND: Int = 1 shl 8
private const val MACOS_MODIFIER_SHIFT: Int = 1 shl 9
private const val MACOS_MODIFIER_ALT: Int = 1 shl 11
private const val MACOS_MODIFIER_CONTROL: Int = 1 shl 12

private const val WINDOWS_MODIFIER_ALT: Int = 0x0001
private const val WINDOWS_MODIFIER_CONTROL: Int = 0x0002
private const val WINDOWS_MODIFIER_SHIFT: Int = 0x0004
private const val WINDOWS_MODIFIER_META: Int = 0x0008

private const val LINUX_MODIFIER_SHIFT: Int = 1 shl 0
private const val LINUX_MODIFIER_CONTROL: Int = 1 shl 2
private const val LINUX_MODIFIER_ALT: Int = 1 shl 3
private const val LINUX_MODIFIER_META: Int = 1 shl 6

private const val HOTKEY_CALLBACK_THREAD_NAME: String = "keyguard-global-hotkey-callback"

internal data class NativeGlobalHotKeySpec(
    val keyCode: Int,
    val modifiers: Int,
)

private object HotKeyCallbackDispatcher {
    private val executor = Executors.newSingleThreadExecutor(
        ThreadFactory { runnable ->
            Thread(runnable, HOTKEY_CALLBACK_THREAD_NAME).apply {
                isDaemon = true
            }
        },
    )

    fun dispatch(
        block: () -> Unit,
    ) {
        executor.execute(block)
    }
}

public interface GlobalHotKeyRegistration : AutoCloseable {
    public fun unregister(): Boolean

    override fun close() {
        unregister()
    }
}

public sealed interface GlobalHotKeyRegistrationResult {
    public data class Success(
        public val registration: GlobalHotKeyRegistration,
    ) : GlobalHotKeyRegistrationResult

    public data class Failure(
        public val reason: GlobalHotKeyRegistrationFailureReason,
    ) : GlobalHotKeyRegistrationResult
}

public enum class GlobalHotKeyRegistrationFailureReason {
    UnsupportedPlatform,
    UnsupportedSession,
    InvalidShortcut,
    Unavailable,
    InternalError,
}

private class DesktopLibGlobalHotKeyRegistration(
    private val lib: DesktopLibJna,
    private val id: Int,
    private val closed: AtomicBoolean,
    @Suppress("unused")
    private val callback: DesktopLibJna.GlobalHotKeyCallback,
) : GlobalHotKeyRegistration {
    override fun unregister(): Boolean {
        if (!closed.compareAndSet(false, true)) {
            return false
        }

        return lib.unregisterNativeGlobalHotKey(id)
    }
}

public fun registerGlobalHotKey(
    spec: GlobalHotKeySpec,
    onPressed: () -> Unit,
): GlobalHotKeyRegistrationResult = try {
    registerGlobalHotKey(
        lib = DesktopLibJna.get(),
        platform = currentGlobalHotKeyPlatform(),
        spec = spec,
        onPressed = onPressed,
    )
} catch (_: Throwable) {
    GlobalHotKeyRegistrationResult.Failure(
        reason = GlobalHotKeyRegistrationFailureReason.InternalError,
    )
}

internal fun registerGlobalHotKey(
    lib: DesktopLibJna,
    spec: GlobalHotKeySpec,
    onPressed: () -> Unit,
): GlobalHotKeyRegistrationResult = registerGlobalHotKey(
    lib = lib,
    platform = currentGlobalHotKeyPlatform(),
    spec = spec,
    onPressed = onPressed,
)

internal fun registerGlobalHotKey(
    lib: DesktopLibJna,
    platform: GlobalHotKeyPlatform,
    spec: GlobalHotKeySpec,
    onPressed: () -> Unit,
): GlobalHotKeyRegistrationResult {
    val nativeSpec = mapNativeGlobalHotKeySpec(
        platform = platform,
        spec = spec,
    ) ?: return when (platform) {
        GlobalHotKeyPlatform.Unsupported -> GlobalHotKeyRegistrationResult.Failure(
            reason = GlobalHotKeyRegistrationFailureReason.UnsupportedPlatform,
        )

        else -> GlobalHotKeyRegistrationResult.Failure(
            reason = GlobalHotKeyRegistrationFailureReason.InvalidShortcut,
        )
    }

    val closed = AtomicBoolean(false)
    val callback = object : DesktopLibJna.GlobalHotKeyCallback {
        override fun invoke(id: Int) {
            if (closed.get()) {
                return
            }

            HotKeyCallbackDispatcher.dispatch {
                if (!closed.get()) {
                    onPressed()
                }
            }
        }
    }
    val result = lib.registerNativeGlobalHotKey(
        nativeKeyCode = nativeSpec.keyCode,
        nativeModifiers = nativeSpec.modifiers,
        callback = callback,
    )
    if (result > 0) {
        return GlobalHotKeyRegistrationResult.Success(
            registration = DesktopLibGlobalHotKeyRegistration(
                lib = lib,
                id = result,
                closed = closed,
                callback = callback,
            ),
        )
    }

    return GlobalHotKeyRegistrationResult.Failure(
        reason = result.toRegistrationFailureReason(),
    )
}

internal fun mapNativeGlobalHotKeySpec(
    platform: GlobalHotKeyPlatform,
    spec: GlobalHotKeySpec,
): NativeGlobalHotKeySpec? {
    val modifiers = when (platform) {
        GlobalHotKeyPlatform.MacOS -> spec.buildMacOsModifierMask()
        GlobalHotKeyPlatform.Windows -> spec.buildWindowsModifierMask()
        GlobalHotKeyPlatform.Linux -> spec.buildLinuxModifierMask()
        GlobalHotKeyPlatform.Unsupported -> return null
    }
    val keyCode = when (platform) {
        GlobalHotKeyPlatform.MacOS -> spec.key.macosKeyCode
        GlobalHotKeyPlatform.Windows -> spec.key.windowsKeyCode
        GlobalHotKeyPlatform.Linux -> spec.key.x11KeySym
        GlobalHotKeyPlatform.Unsupported -> null
    } ?: return null

    return NativeGlobalHotKeySpec(
        keyCode = keyCode,
        modifiers = modifiers,
    )
}

private fun GlobalHotKeySpec.buildMacOsModifierMask(): Int {
    var modifiers = 0
    if (isCtrlPressed) {
        modifiers = modifiers or MACOS_MODIFIER_CONTROL
    }
    if (isShiftPressed) {
        modifiers = modifiers or MACOS_MODIFIER_SHIFT
    }
    if (isAltPressed) {
        modifiers = modifiers or MACOS_MODIFIER_ALT
    }
    if (isMetaPressed) {
        modifiers = modifiers or MACOS_MODIFIER_COMMAND
    }
    return modifiers
}

private fun GlobalHotKeySpec.buildWindowsModifierMask(): Int {
    var modifiers = 0
    if (isCtrlPressed) {
        modifiers = modifiers or WINDOWS_MODIFIER_CONTROL
    }
    if (isShiftPressed) {
        modifiers = modifiers or WINDOWS_MODIFIER_SHIFT
    }
    if (isAltPressed) {
        modifiers = modifiers or WINDOWS_MODIFIER_ALT
    }
    if (isMetaPressed) {
        modifiers = modifiers or WINDOWS_MODIFIER_META
    }
    return modifiers
}

private fun GlobalHotKeySpec.buildLinuxModifierMask(): Int {
    var modifiers = 0
    if (isCtrlPressed) {
        modifiers = modifiers or LINUX_MODIFIER_CONTROL
    }
    if (isShiftPressed) {
        modifiers = modifiers or LINUX_MODIFIER_SHIFT
    }
    if (isAltPressed) {
        modifiers = modifiers or LINUX_MODIFIER_ALT
    }
    if (isMetaPressed) {
        modifiers = modifiers or LINUX_MODIFIER_META
    }
    return modifiers
}

private fun Int.toRegistrationFailureReason(): GlobalHotKeyRegistrationFailureReason = when (this) {
    HOTKEY_RESULT_UNSUPPORTED_PLATFORM -> GlobalHotKeyRegistrationFailureReason.UnsupportedPlatform
    HOTKEY_RESULT_UNSUPPORTED_SESSION -> GlobalHotKeyRegistrationFailureReason.UnsupportedSession
    HOTKEY_RESULT_INVALID_SHORTCUT -> GlobalHotKeyRegistrationFailureReason.InvalidShortcut
    HOTKEY_RESULT_UNAVAILABLE,
    0,
        -> GlobalHotKeyRegistrationFailureReason.Unavailable

    HOTKEY_RESULT_INTERNAL_ERROR -> GlobalHotKeyRegistrationFailureReason.InternalError
    else -> GlobalHotKeyRegistrationFailureReason.InternalError
}
