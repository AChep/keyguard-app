package com.artemchep.keyguard.util.agentipc

import com.artemchep.keyguard.util.ffi.JniLibrary

// Every call loads and verifies the library first: a missing library or another ABI
// version is AgentIpcException(UNAVAILABLE), an Exception rather than a linkage Error.
internal actual object NativeAgentIpc {
    private val library = JniLibrary(
        name = "keyguard_agent_ipc_jni",
        pathProperty = "keyguard.nativeAgentIpc.libraryPath",
        unavailable = { cause -> AgentIpcException(AgentIpcFailureKind.UNAVAILABLE, cause = cause) },
        verify = {
            if (NativeAgentIpcJni.abiVersion() != AGENT_IPC_ABI_VERSION) {
                throw AgentIpcException(AgentIpcFailureKind.UNAVAILABLE)
            }
        },
    )

    actual fun abiVersion(): Int {
        library.ensureLoaded()
        return NativeAgentIpcJni.abiVersion()
    }

    actual fun lastError(): String? {
        library.ensureLoaded()
        return NativeAgentIpcJni.lastError()
    }

    actual fun listen(parent: String?, prefix: String, exactName: String?): Long {
        library.ensureLoaded()
        return NativeAgentIpcJni.listen(parent, prefix, exactName)
    }

    actual fun endpoint(listener: Long): String? {
        library.ensureLoaded()
        return NativeAgentIpcJni.endpoint(listener)
    }

    actual fun accept(listener: Long): Long {
        library.ensureLoaded()
        return NativeAgentIpcJni.accept(listener)
    }

    actual fun closeListener(listener: Long): Long {
        library.ensureLoaded()
        return NativeAgentIpcJni.closeListener(listener)
    }

    actual fun peerPid(connection: Long): Long {
        library.ensureLoaded()
        return NativeAgentIpcJni.peerPid(connection)
    }

    actual fun read(connection: Long, buffer: ByteArray, offset: Int, length: Int): Long {
        library.ensureLoaded()
        return NativeAgentIpcJni.read(connection, buffer, offset, length)
    }

    actual fun write(connection: Long, buffer: ByteArray, offset: Int, length: Int): Long {
        library.ensureLoaded()
        return NativeAgentIpcJni.write(connection, buffer, offset, length)
    }

    actual fun awaitInput(connection: Long): Long {
        library.ensureLoaded()
        return NativeAgentIpcJni.awaitInput(connection)
    }

    actual fun close(connection: Long): Long {
        library.ensureLoaded()
        return NativeAgentIpcJni.close(connection)
    }
}
