package com.artemchep.keyguard.ipctestclient.ipc

import android.app.PendingIntent
import android.content.Intent
import android.os.Parcelable

private const val INLINE_BYTES_LIMIT = 64

/**
 * Every extra on an intent, by name, with its type. Nothing is interpreted here
 * - this is the view that shows an extra the decoders do not know about.
 */
@Suppress("DEPRECATION")
fun Intent.dumpExtras(): String {
    val bundle = extras ?: return "action=${action ?: "<none>"}\n<no extras>"
    val keys = bundle.keySet().sorted()
    val body = keys.joinToString("\n") { key ->
        "  $key = ${describeExtra(bundle.get(key))}"
    }
    return "action=${action ?: "<none>"}\n" +
        if (keys.isEmpty()) "<no extras>" else body
}

private fun describeExtra(value: Any?): String = when (value) {
    null -> "null"
    is ByteArray -> "byte[${value.size}] ${value.inlineOrSummary()}"
    is LongArray -> "long[${value.size}] " +
        value.joinToString(prefix = "[", postfix = "]") { it.toKeyIdHex() }

    is IntArray -> "int[${value.size}] ${value.joinToString(prefix = "[", postfix = "]")}"
    is Array<*> -> "array[${value.size}] " +
        value.joinToString(prefix = "[", postfix = "]") { "$it" }

    is PendingIntent -> "PendingIntent(creator=${value.creatorPackage})"
    is String -> "\"$value\""
    is Parcelable -> "${value.javaClass.simpleName} $value"
    else -> "${value.javaClass.simpleName} $value"
}

private fun ByteArray.inlineOrSummary(): String =
    if (size <= INLINE_BYTES_LIMIT) toHex() else toHex().take(INLINE_BYTES_LIMIT * 2) + "…"
