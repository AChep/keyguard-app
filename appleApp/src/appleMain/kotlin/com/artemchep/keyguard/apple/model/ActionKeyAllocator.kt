package com.artemchep.keyguard.apple.model

import com.artemchep.keyguard.ui.FlatItemAction

/** Allocates stable handler keys from action ids with a safe fallback. */
internal class ActionKeyAllocator(
    private val prefix: String,
) {
    private val seen = HashMap<String, Int>()

    fun keyFor(action: FlatItemAction, title: String): String {
        val base = action.id ?: run {
            // Surface missing ids instead of silently drifting.
            println("[Keyguard][action] MISSING id for '$title' (type=${action.type}) — using content-key fallback")
            "${action.type?.name ?: "action"}:$title"
        }
        val n = seen.getOrElse(base) { 0 }
        seen[base] = n + 1
        if (n > 0) {
            // Keep duplicate actions distinct and report the collision.
            println(
                "[Keyguard][action] duplicate action key '$base' (#$n) under '$prefix' — sibling actions share an id",
            )
        }
        val unique = if (n == 0) base else "$base#$n"
        return "$prefix:$unique"
    }
}

/**
 * Invokes the handler registered for [id], or logs a miss instead of silently doing
 * nothing — so a key mismatch surfaces during testing as a log line rather than a dead
 * tap. Use from the bridge's `invoke*(id:)` methods.
 */
internal fun Map<String, () -> Unit>.invokeAction(id: String) {
    val handler = this[id]
    if (handler == null) {
        println("[Keyguard][action] no handler for id=$id")
    } else {
        handler()
    }
}
