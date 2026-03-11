package com.artemchep.keyguard.desktop.ui

import com.artemchep.keyguard.platform.recordLogDebug
import java.awt.Desktop

internal fun requestAppForeground(
    tag: String,
    requestRevision: Int? = null,
): Boolean {
    val prefix = buildString {
        append(tag)
        requestRevision?.let {
            append('[')
            append(it)
            append(']')
        }
    }

    if (!Desktop.isDesktopSupported()) {
        recordLogDebug {
            "$prefix app foreground unsupported: desktop not supported"
        }
        return false
    }

    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
        recordLogDebug {
            "$prefix app foreground unsupported: action not supported"
        }
        return false
    }

    return runCatching {
        desktop.requestForeground(false)
        recordLogDebug {
            "$prefix app foreground requested"
        }
        true
    }.getOrElse { error ->
        recordLogDebug {
            "$prefix app foreground request failed: ${error.message ?: error::class.simpleName}"
        }
        false
    }
}
