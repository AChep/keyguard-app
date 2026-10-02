package com.artemchep.keyguard.apple.core

/**
 * Main-confined lifetime and action ownership shared by native presentations.
 *
 * A session is observed once and closed once: closing drops the published actions,
 * cancels the producer, and runs [onDispose]. Every callback the session hands out
 * — snapshots, [gated] side channels, completion — is ignored after that.
 */
internal class DetailSession<Snapshot, Actions>(
    private val onDispose: () -> Unit = {},
) {
    private var started = false
    private var isClosed = false
    private var observation: KeyguardCancellable? = null
    private var actions: Actions? = null

    fun observe(
        onChange: (Snapshot) -> Unit,
        subscribe: (publish: (Snapshot, Actions) -> Unit) -> KeyguardCancellable,
    ): KeyguardCancellable {
        check(!started && !isClosed) { "A session can only be observed once" }
        started = true
        val subscription = subscribe { snapshot, handlers ->
            if (!isClosed) {
                actions = handlers
                onChange(snapshot)
            }
        }
        // A synchronous initial callback may already have closed its presentation.
        if (isClosed) subscription.cancel() else observation = subscription
        return KeyguardCancellable(::close)
    }

    /** Observes a form: its producer may `complete` once, which closes the session and then runs [onClose]. */
    fun observe(
        onChange: (Snapshot) -> Unit,
        onClose: () -> Unit,
        subscribe: (publish: (Snapshot, Actions) -> Unit, complete: () -> Unit) -> KeyguardCancellable,
    ): KeyguardCancellable = observe(onChange) { publish ->
        subscribe(publish) {
            if (!isClosed) {
                close()
                onClose()
            }
        }
    }

    /** A side channel of this session that goes quiet once the session closes. */
    fun <T> gated(block: (T) -> Unit): (T) -> Unit = { value -> runIfOpen { block(value) } }

    fun runIfOpen(block: () -> Unit) {
        if (!isClosed) block()
    }

    fun withActions(block: (Actions) -> Unit) {
        if (!isClosed) actions?.let(block)
    }

    fun close() {
        if (isClosed) return
        isClosed = true
        // Clear handlers before asynchronous producer cancellation finishes.
        actions = null
        observation?.cancel()
        observation = null
        onDispose()
    }
}
