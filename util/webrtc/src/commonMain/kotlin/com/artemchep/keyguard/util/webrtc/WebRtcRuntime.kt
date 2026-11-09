package com.artemchep.keyguard.util.webrtc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * A native WebRTC runtime: two worker threads that drive every peer created
 * from it. Create one when the peers are needed, and [shutdown] it when they
 * are not; the library keeps no runtime of its own.
 */
class WebRtcRuntime private constructor(
    private val handle: Long,
) {
    companion object {
        /**
         * Loads the native library and checks its ABI without starting a
         * runtime. Throws [WebRtcException] with [WebRtcFailure.UNAVAILABLE].
         */
        fun ensureAvailable() {
            if (NativeWebrtc.abiVersion() != NATIVE_WEBRTC_ABI_VERSION) {
                throw WebRtcException(WebRtcFailure.UNAVAILABLE)
            }
        }

        /** Starts a runtime, loading the native library on first use. */
        suspend fun create(): WebRtcRuntime = WebRtcRuntime(
            createOwned(
                create = NativeWebrtc::runtimeNew,
                release = NativeWebrtc::runtimeShutdown,
            ),
        )
    }

    /** Creates a peer that lives until it is closed or this runtime shuts down. */
    suspend fun createPeer(config: WebRtcConfig = WebRtcConfig()): WebRtcPeer {
        val json = encodeConfig(config)
        return WebRtcPeer(
            createOwned(
                create = { NativeWebrtc.peerNew(handle, json) },
                release = NativeWebrtc::peerClose,
            ),
        )
    }

    /**
     * Closes every peer of this runtime and stops its threads, waiting a few
     * seconds at most. Idempotent, and completes even if the caller is cancelled.
     */
    suspend fun shutdown() {
        withContext(Dispatchers.IO + NonCancellable) {
            checkNativeWebrtc(NativeWebrtc.runtimeShutdown(handle))
        }
    }
}

/**
 * Creates a native handle on [Dispatchers.IO]. `withContext` drops its result if the caller
 * is cancelled meanwhile, so the handle is kept outside of it and [release]d unless it
 * reaches the caller.
 */
private suspend fun createOwned(
    create: () -> Long,
    release: (Long) -> Long,
): Long {
    var handle: Long? = null
    var returned = false
    try {
        withContext(Dispatchers.IO) {
            handle = checkNativeWebrtc(create())
        }
        return checkNotNull(handle).also { returned = true }
    } finally {
        val owned = handle
        if (!returned && owned != null) {
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { release(owned) }
            }
        }
    }
}
