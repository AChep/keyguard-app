package com.artemchep.keyguard.util.agentipc

// JNI exports are pinned to this package and object. Moving it requires changing the Rust ABI.
internal object NativeAgentIpcJni {
    external fun abiVersion(): Int

    external fun lastError(): String?

    external fun listen(parent: String?, prefix: String, exactName: String?): Long

    external fun endpoint(listener: Long): String?

    external fun accept(listener: Long): Long

    external fun closeListener(listener: Long): Long

    external fun peerPid(connection: Long): Long

    external fun read(connection: Long, buffer: ByteArray, offset: Int, length: Int): Long

    external fun write(connection: Long, buffer: ByteArray, offset: Int, length: Int): Long

    external fun awaitInput(connection: Long): Long

    external fun close(connection: Long): Long
}
