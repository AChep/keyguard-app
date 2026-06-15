package com.artemchep.keyguard.util.signalr

enum class HubMessageType(
    val value: Int,
) {
    INVOCATION(1),
    STREAM_ITEM(2),
    COMPLETION(3),
    STREAM_INVOCATION(4),
    CANCEL_INVOCATION(5),
    PING(6),
    CLOSE(7),
}