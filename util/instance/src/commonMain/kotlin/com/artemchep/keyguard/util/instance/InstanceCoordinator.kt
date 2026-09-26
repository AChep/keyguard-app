package com.artemchep.keyguard.util.instance

/**
 * Coordinates one application instance within a private local directory.
 *
 * This blocking operation belongs in process bootstrap, before starting services that use
 * shared application data. Only [InstanceResult.Primary] authorizes those services to start.
 */
object InstanceCoordinator {
    /** Throws [InstanceException] when ownership or acknowledged activation cannot be obtained. */
    fun acquireOrActivate(config: InstanceConfig): InstanceResult {
        val result = NativeInstance.acquireOrActivate(
            config.coordinationDirectory,
            config.runtimeDirectory,
            config.identity,
            config.timeoutMillis,
        ).checked()
        return if (result == 0L) {
            InstanceResult.Activated
        } else {
            InstanceResult.Primary(PrimaryInstance(result))
        }
    }
}

/**
 * [coordinationDirectory] is a stable, private, local directory containing permanent ownership
 * files. It must not be deleted while the application runs. [runtimeDirectory] is an existing
 * local directory with a short absolute path; the module creates a private per-run directory
 * within it for IPC endpoints. The runtime root may be shared temporary storage.
 *
 * [identity] is stable across updates and JVM/native builds; use separate identities for
 * release/development channels and independent application data. It contains 1–64 ASCII letters,
 * digits, dots, underscores or hyphens, excluding the names `.` and `..`. Directory resolution
 * belongs to the application. [timeoutMillis] is between 1 and 60,000 inclusive and bounds
 * acquisition and secondary activation attempts.
 */
data class InstanceConfig(
    val coordinationDirectory: String,
    val runtimeDirectory: String,
    val identity: String,
    val timeoutMillis: Long = 5_000L,
)

sealed interface InstanceResult {
    /** Ownership is held and the activation listener is ready. */
    data class Primary(val instance: PrimaryInstance) : InstanceResult

    /** An existing instance accepted and queued activation; window focus is not guaranteed. */
    data object Activated : InstanceResult
}

/**
 * Process-owned instance handle. Keep it alive while any shared-state application services run.
 * Closing a window, hiding to tray, or cancelling a UI scope must not release ownership.
 *
 * Calls are safe across threads, with exactly one [awaitActivation] receiver. Coroutine
 * cancellation does not interrupt a native wait: call [stop] or [close] to wake the receiver.
 */
class PrimaryInstance internal constructor(private val handle: Long) : AutoCloseable {
    /**
     * Blocks off the UI thread until activation is available, or returns false after shutdown.
     * Multiple pending activations coalesce, including those received before this first call.
     * Listener failures trigger bounded recovery without releasing ownership. Throws if
     * recovery is exhausted; the application must then provide a visible restart/quit path.
     */
    fun awaitActivation(): Boolean = when (val result = NativeInstance.waitEvent(handle)) {
        0L, NATIVE_INVALID_HANDLE -> false
        1L -> true
        else -> {
            result.checked()
            throw InstanceException(InstanceFailureKind.INTERNAL)
        }
    }

    /** Stops accepting activation and wakes the receiver, retaining ownership. Idempotent. */
    fun stop() {
        NativeInstance.stop(handle).checked(allowClosed = true)
    }

    /** Stops transport work and releases ownership last. Idempotent; may wake a blocked receiver. */
    override fun close() {
        NativeInstance.close(handle).checked(allowClosed = true)
    }
}

enum class InstanceFailureKind {
    INVALID_ARGUMENT,
    IO,
    TIMEOUT,
    PROTOCOL,
    INVALID_HANDLE,
    UNAVAILABLE,
    PERMISSION,
    INTERNAL,
}

/** A stable failure category without endpoint credentials or user paths in the message. */
class InstanceException(
    val kind: InstanceFailureKind,
    cause: Throwable? = null,
    val diagnostic: String? = null,
) : Exception("Instance coordination failed: $kind" + diagnostic?.let { " ($it)" }.orEmpty(), cause)

private const val NATIVE_INVALID_HANDLE = -5L

private fun Long.checked(allowClosed: Boolean = false): Long {
    if (this >= 0L || (allowClosed && this == NATIVE_INVALID_HANDLE)) return this
    val kind = when (this) {
        -1L -> InstanceFailureKind.INVALID_ARGUMENT
        -2L -> InstanceFailureKind.IO
        -3L -> InstanceFailureKind.TIMEOUT
        -4L -> InstanceFailureKind.PROTOCOL
        NATIVE_INVALID_HANDLE -> InstanceFailureKind.INVALID_HANDLE
        -6L -> InstanceFailureKind.UNAVAILABLE
        -7L -> InstanceFailureKind.PERMISSION
        else -> InstanceFailureKind.INTERNAL
    }
    throw InstanceException(kind, diagnostic = NativeInstance.lastError())
}
