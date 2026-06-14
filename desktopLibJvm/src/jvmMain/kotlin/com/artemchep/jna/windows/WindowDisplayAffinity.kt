package com.artemchep.jna.windows

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary

private const val WDA_NONE = 0x00000000
private const val WDA_EXCLUDEFROMCAPTURE = 0x00000011

public fun setWindowExcludedFromCapture(
    windowHandle: Long,
    excluded: Boolean,
): Boolean {
    if (windowHandle == 0L) {
        return false
    }

    val affinity = if (excluded) {
        WDA_EXCLUDEFROMCAPTURE
    } else {
        WDA_NONE
    }
    return User32.INSTANCE.SetWindowDisplayAffinity(
        Pointer.createConstant(windowHandle),
        affinity,
    )
}

@Suppress("FunctionName")
private interface User32 : StdCallLibrary {
    companion object {
        val INSTANCE: User32 by lazy {
            Native.load(
                "user32",
                User32::class.java,
            ) as User32
        }
    }

    fun SetWindowDisplayAffinity(
        hWnd: Pointer,
        dwAffinity: Int,
    ): Boolean
}
