package com.artemchep.autotype

import com.artemchep.jna.DesktopLibJna
import com.artemchep.jna.util.DisposableScope
import com.artemchep.jna.withDesktopLib
import com.artemchep.jna.util.asMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Status codes shared with desktopLibNative autotype.rs.
private const val INVALID_TEXT_CODE = 2
private const val INTERRUPTED_CODE = 3
private const val INPUT_FAILED_CODE = 4
private const val BUSY_CODE = 5
private const val KEYS_HELD_CODE = 6

public enum class AutoTypeLoginStatus(internal val code: Int) {
    SUCCESS(0),
    UNAVAILABLE(1),
    INVALID_TEXT(INVALID_TEXT_CODE),
    INTERRUPTED(INTERRUPTED_CODE),
    INPUT_FAILED(INPUT_FAILED_CODE),
    BUSY(BUSY_CODE),
    KEYS_HELD(KEYS_HELD_CODE),
    ;

    internal companion object {
        fun fromCode(code: Int): AutoTypeLoginStatus =
            entries.firstOrNull { it.code == code } ?: INPUT_FAILED
    }
}

public fun autoTypeCaptureTarget(): Long = DesktopLibJna.get().autoTypeCaptureTarget()

/** Prompts for the permission to send input if it has not been granted yet. */
public fun autoTypePermission(): Boolean = DesktopLibJna.get().autoTypePermission()

/** The callback is retained throughout the synchronous native call, including cancellation. */
public suspend fun autoTypeLogin(
    target: Long,
    username: String,
    password: String,
    delayMultiplier: Int = 1,
    isActive: () -> Boolean,
): AutoTypeLoginStatus = withContext(Dispatchers.IO) {
    withDesktopLib { lib -> autoTypeLogin(lib, target, username, password, delayMultiplier, isActive) }
}

internal fun DisposableScope.autoTypeLogin(
    lib: DesktopLibJna,
    target: Long,
    username: String,
    password: String,
    delayMultiplier: Int = 1,
    isActive: () -> Boolean,
): AutoTypeLoginStatus {
    // Native code reads C strings, so it would never see the text after a NUL.
    if ('\u0000' in username || '\u0000' in password) return AutoTypeLoginStatus.INVALID_TEXT
    val active = object : DesktopLibJna.AutotypeActiveCallback {
        override fun invoke(): Int = if (isActive()) 1 else 0
    }
    val code = try {
        lib.autoTypeLogin(
            target,
            register(username.asMemory()),
            register(password.asMemory()),
            delayMultiplier,
            active,
        )
    } finally {
        java.lang.ref.Reference.reachabilityFence(active)
    }
    return AutoTypeLoginStatus.fromCode(code)
}
