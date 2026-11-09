package com.artemchep.keyguard.util.webrtc

// JNI export names in keyguard-webrtc-jni are pinned to this package and
// class. Moving it compiles but fails at runtime with UnsatisfiedLinkError.
@Suppress("TooManyFunctions") // One function per ABI export.
internal object NativeWebrtcJni {
    external fun abiVersion(): Int

    external fun runtimeNew(): Long

    external fun runtimeShutdown(runtime: Long): Long

    external fun peerNew(runtime: Long, configJson: String): Long

    external fun peerCreateOffer(peer: Long, out: ByteArray): Long

    external fun peerCreateAnswer(peer: Long, out: ByteArray): Long

    external fun peerSetRemote(peer: Long, kind: Int, sdp: String): Long

    external fun peerAddIceCandidate(peer: Long, candidateJson: String): Long

    external fun peerCreateChannel(peer: Long, label: String): Long

    external fun channelSend(peer: Long, data: ByteArray, offset: Int, length: Int): Long

    external fun channelBufferedAmount(peer: Long): Long

    external fun peerNextEvent(peer: Long, out: ByteArray, timeoutMs: Int): Long

    external fun peerClose(peer: Long): Long
}
