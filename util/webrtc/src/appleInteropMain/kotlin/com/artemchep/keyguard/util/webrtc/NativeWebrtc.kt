@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.artemchep.keyguard.util.webrtc

import com.artemchep.keyguard.util.ffi.StaticLibrary
import com.artemchep.keyguard.util.ffi.hasRange
import com.artemchep.keyguard.util.ffi.withNativePointer
import com.artemchep.keyguard.util.ffi.withStrictUtf8
import com.artemchep.keyguard.util.webrtc.ffi.keyguard_webrtc_abi_version
import com.artemchep.keyguard.util.webrtc.ffi.keyguard_webrtc_channel_buffered_amount
import com.artemchep.keyguard.util.webrtc.ffi.keyguard_webrtc_channel_send
import com.artemchep.keyguard.util.webrtc.ffi.keyguard_webrtc_peer_add_ice_candidate
import com.artemchep.keyguard.util.webrtc.ffi.keyguard_webrtc_peer_close
import com.artemchep.keyguard.util.webrtc.ffi.keyguard_webrtc_peer_create_answer
import com.artemchep.keyguard.util.webrtc.ffi.keyguard_webrtc_peer_create_channel
import com.artemchep.keyguard.util.webrtc.ffi.keyguard_webrtc_peer_create_offer
import com.artemchep.keyguard.util.webrtc.ffi.keyguard_webrtc_peer_new
import com.artemchep.keyguard.util.webrtc.ffi.keyguard_webrtc_peer_next_event
import com.artemchep.keyguard.util.webrtc.ffi.keyguard_webrtc_peer_set_remote
import com.artemchep.keyguard.util.webrtc.ffi.keyguard_webrtc_runtime_new
import com.artemchep.keyguard.util.webrtc.ffi.keyguard_webrtc_runtime_shutdown
import kotlinx.cinterop.convert

@Suppress("TooManyFunctions") // One function per ABI export.
internal actual object NativeWebrtc {
    private val library = StaticLibrary {
        if (abiVersion() != NATIVE_WEBRTC_ABI_VERSION) {
            throw WebRtcException(WebRtcFailure.UNAVAILABLE)
        }
    }

    actual fun abiVersion(): Int = keyguard_webrtc_abi_version().toInt()

    actual fun runtimeNew(): Long = verified { keyguard_webrtc_runtime_new() }

    actual fun runtimeShutdown(runtime: Long): Long =
        verified { keyguard_webrtc_runtime_shutdown(runtime) }

    actual fun peerNew(runtime: Long, configJson: String): Long = verified {
        configJson.withStrictUtf8(NATIVE_WEBRTC_INVALID_ARGUMENT) { pointer, size ->
            keyguard_webrtc_peer_new(runtime, pointer, size)
        }
    }

    actual fun peerCreateOffer(peer: Long, out: ByteArray): Long = verified {
        out.withNativePointer { pointer, size -> keyguard_webrtc_peer_create_offer(peer, pointer, size) }
    }

    actual fun peerCreateAnswer(peer: Long, out: ByteArray): Long = verified {
        out.withNativePointer { pointer, size -> keyguard_webrtc_peer_create_answer(peer, pointer, size) }
    }

    actual fun peerSetRemote(peer: Long, kind: Int, sdp: String): Long = verified {
        if (kind < 0) return@verified NATIVE_WEBRTC_INVALID_ARGUMENT
        sdp.withStrictUtf8(NATIVE_WEBRTC_INVALID_ARGUMENT) { pointer, size ->
            keyguard_webrtc_peer_set_remote(peer, kind.convert(), pointer, size)
        }
    }

    actual fun peerAddIceCandidate(peer: Long, candidateJson: String): Long = verified {
        candidateJson.withStrictUtf8(NATIVE_WEBRTC_INVALID_ARGUMENT) { pointer, size ->
            keyguard_webrtc_peer_add_ice_candidate(peer, pointer, size)
        }
    }

    actual fun peerCreateChannel(peer: Long, label: String): Long = verified {
        label.withStrictUtf8(NATIVE_WEBRTC_INVALID_ARGUMENT) { pointer, size ->
            keyguard_webrtc_peer_create_channel(peer, pointer, size)
        }
    }

    actual fun channelSend(peer: Long, data: ByteArray, offset: Int, length: Int): Long = verified {
        if (!data.hasRange(offset, length)) return@verified NATIVE_WEBRTC_INVALID_ARGUMENT
        data.withNativePointer(offset, length) { pointer, size ->
            keyguard_webrtc_channel_send(peer, pointer, size)
        }
    }

    actual fun channelBufferedAmount(peer: Long): Long =
        verified { keyguard_webrtc_channel_buffered_amount(peer) }

    actual fun peerNextEvent(peer: Long, out: ByteArray, timeoutMs: Int): Long = verified {
        if (timeoutMs < 0) return@verified NATIVE_WEBRTC_INVALID_ARGUMENT
        out.withNativePointer { pointer, size ->
            keyguard_webrtc_peer_next_event(peer, pointer, size, timeoutMs.convert())
        }
    }

    actual fun peerClose(peer: Long): Long = verified { keyguard_webrtc_peer_close(peer) }

    private inline fun verified(block: () -> Long): Long {
        library.ensureVerified()
        return block()
    }
}
