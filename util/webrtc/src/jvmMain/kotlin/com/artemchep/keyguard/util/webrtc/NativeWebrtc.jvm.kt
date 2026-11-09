package com.artemchep.keyguard.util.webrtc

import com.artemchep.keyguard.util.ffi.JniLibrary

@Suppress("TooManyFunctions") // One function per ABI export.
internal actual object NativeWebrtc {
    private val library = JniLibrary(
        name = "keyguard_webrtc_jni",
        pathProperty = "keyguard.nativeWebrtc.libraryPath",
        unavailable = { cause -> WebRtcException(WebRtcFailure.UNAVAILABLE, cause) },
        verify = {
            if (NativeWebrtcJni.abiVersion() != NATIVE_WEBRTC_ABI_VERSION) {
                throw WebRtcException(WebRtcFailure.UNAVAILABLE)
            }
        },
    )

    actual fun abiVersion(): Int = withLibrary { NativeWebrtcJni.abiVersion() }

    actual fun runtimeNew(): Long = withLibrary { NativeWebrtcJni.runtimeNew() }

    actual fun runtimeShutdown(runtime: Long): Long =
        withLibrary { NativeWebrtcJni.runtimeShutdown(runtime) }

    actual fun peerNew(runtime: Long, configJson: String): Long =
        withLibrary { NativeWebrtcJni.peerNew(runtime, configJson) }

    actual fun peerCreateOffer(peer: Long, out: ByteArray): Long =
        withLibrary { NativeWebrtcJni.peerCreateOffer(peer, out) }

    actual fun peerCreateAnswer(peer: Long, out: ByteArray): Long =
        withLibrary { NativeWebrtcJni.peerCreateAnswer(peer, out) }

    actual fun peerSetRemote(peer: Long, kind: Int, sdp: String): Long =
        withLibrary { NativeWebrtcJni.peerSetRemote(peer, kind, sdp) }

    actual fun peerAddIceCandidate(peer: Long, candidateJson: String): Long =
        withLibrary { NativeWebrtcJni.peerAddIceCandidate(peer, candidateJson) }

    actual fun peerCreateChannel(peer: Long, label: String): Long =
        withLibrary { NativeWebrtcJni.peerCreateChannel(peer, label) }

    actual fun channelSend(peer: Long, data: ByteArray, offset: Int, length: Int): Long =
        withLibrary { NativeWebrtcJni.channelSend(peer, data, offset, length) }

    actual fun channelBufferedAmount(peer: Long): Long =
        withLibrary { NativeWebrtcJni.channelBufferedAmount(peer) }

    actual fun peerNextEvent(peer: Long, out: ByteArray, timeoutMs: Int): Long =
        withLibrary { NativeWebrtcJni.peerNextEvent(peer, out, timeoutMs) }

    actual fun peerClose(peer: Long): Long = withLibrary { NativeWebrtcJni.peerClose(peer) }

    private inline fun <T> withLibrary(block: () -> T): T {
        return try {
            library.ensureLoaded()
            block()
        } catch (error: UnsatisfiedLinkError) {
            throw WebRtcException(WebRtcFailure.UNAVAILABLE, error)
        }
    }
}
