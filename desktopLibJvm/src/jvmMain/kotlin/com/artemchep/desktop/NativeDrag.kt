package com.artemchep.desktop

import com.artemchep.jna.findLibBinaryPathOrNull
import com.artemchep.keyguard.util.ffi.JniLibrary
import java.util.concurrent.atomic.AtomicBoolean

/** A started native drag offer. */
public fun interface NativeDragSession {
    /**
     * Stops new reads from opening. Reads in flight finish, then the offer retires.
     * Idempotent; any thread.
     */
    public fun revoke()
}

/**
 * Callbacks the native drag bridges retain until the offer retires. Native code looks
 * each method up by name and signature on the object's class.
 */
public interface NativeDragCallbacks {
    /** Reports the drag outcome once. An accepted drop keeps the offer readable. */
    public fun ended(accepted: Boolean)

    /** Called exactly once, when native ownership ends and every reader opened through the offer is closed. */
    public fun retired()
}

/** The most files one drag carries. Mirrors `MAX_FILES` in drag.rs and `kg_promise_max_files` in file_promise.m. */
internal const val NATIVE_DRAG_MAX_FILES = 64

/** Revokes native offer [handle] through [revokeHandle] at most once. */
internal class NativeDragHandle(
    private val handle: Long,
    private val revokeHandle: (Long) -> Unit,
) : NativeDragSession {
    private val revoked = AtomicBoolean()

    override fun revoke() {
        if (revoked.compareAndSet(false, true)) {
            revokeHandle(handle)
        }
    }
}

/**
 * Loads the packaged desktop library for the [platform] drag bridge, whose
 * [abiVersion] must report [expectedAbiVersion].
 */
internal fun nativeDragLibrary(
    platform: String,
    expectedAbiVersion: Int,
    abiVersion: () -> Int,
): JniLibrary = JniLibrary.fromAbsolutePath(
    path = { findLibBinaryPathOrNull()?.toString() },
    unavailable = { IllegalStateException("The native $platform drag bridge is unavailable", it) },
    verify = { check(abiVersion() == expectedAbiVersion) { "Unsupported native $platform drag bridge" } },
)
