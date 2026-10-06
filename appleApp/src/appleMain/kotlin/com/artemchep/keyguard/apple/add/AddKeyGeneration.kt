package com.artemchep.keyguard.apple.add

import com.artemchep.keyguard.common.model.GetPasswordResult

/** Holds key material in Kotlin until the user explicitly applies it to the draft. */
internal class AddKeyGeneration(
    private val kind: AddItemKind,
    private val apply: (GetPasswordResult) -> Boolean,
) {
    private var closed = false
    private var latest: GetPasswordResult? = null
    private var rejected: GetPasswordResult? = null
    private var candidate: GetPasswordResult? = null
    private var requested = false

    val canUse: Boolean get() = !closed && candidate != null
    val userId: String? get() = (candidate as? GetPasswordResult.AsyncGpgKey)?.gpgKey?.userId

    fun update(loaded: Boolean, result: GetPasswordResult?) {
        if (closed) return
        latest = result
        val matches = when (result) {
            is GetPasswordResult.AsyncKey -> kind == AddItemKind.SSH_KEY
            is GetPasswordResult.AsyncGpgKey -> kind == AddItemKind.GPG_KEY
            else -> false
        }
        candidate = result.takeIf { requested && loaded && matches && it !== rejected }
    }

    /** Rejects the previous value while the producer catches up with a changed option or refresh. */
    fun invalidate() {
        rejected = latest
        candidate = null
        requested = false
    }

    fun generate() {
        invalidate()
        requested = true
    }

    fun use(): Boolean {
        val result = candidate?.takeUnless { closed } ?: return false
        return apply(result).also { applied ->
            if (applied) close()
        }
    }

    fun close() {
        closed = true
        latest = null
        rejected = null
        candidate = null
    }
}
