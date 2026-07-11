package com.artemchep.keyguard.common.service.agent

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.util.concurrent.atomic.AtomicBoolean

private const val MAX_PACKET_SIZE = 16 * 1024 * 1024

private const val PIPE_ACCESS_DUPLEX = 0x00000003
private const val FILE_FLAG_FIRST_PIPE_INSTANCE = 0x00080000
private const val PIPE_TYPE_BYTE = 0x00000000
private const val PIPE_READMODE_BYTE = 0x00000000
private const val PIPE_WAIT = 0x00000000
private const val PIPE_REJECT_REMOTE_CLIENTS = 0x00000008
private const val PIPE_UNLIMITED_INSTANCES = 255
private const val PIPE_BUFFER_SIZE = 64 * 1024

private const val ERROR_INVALID_HANDLE = 6
private const val ERROR_HANDLE_EOF = 38
private const val ERROR_BROKEN_PIPE = 109
private const val ERROR_NO_DATA = 232
private const val ERROR_PIPE_NOT_CONNECTED = 233
private const val ERROR_PIPE_CONNECTED = 535
private const val ERROR_OPERATION_ABORTED = 995

private const val INVALID_HANDLE_VALUE = -1L

private const val TOKEN_QUERY = 0x0008
private const val TOKEN_USER_INFORMATION_CLASS = 1
private const val SDDL_REVISION_1 = 1
private const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000

internal class WindowsNamedPipeServer(
    private val pipeName: String,
) : AutoCloseable {
    private val lifecycleLock = Any()
    private var closed = false
    private var firstInstance = true
    private var preparedHandle: WindowsNamedPipeHandle? = null
    private val handles = mutableSetOf<WindowsNamedPipeHandle>()

    // Restrict the pipe to the current user via an explicit DACL, mirroring
    // the owner-only (0600) permissions used for the Unix domain socket.
    // Without this the pipe would be created with the default security
    // descriptor, which grants read access to Everyone.
    private var securityDescriptor: Pointer? = buildOwnerOnlySecurityDescriptor()
    private val securityAttributes: WindowsSecurityAttributes =
        WindowsSecurityAttributes(requireNotNull(securityDescriptor))

    /**
     * Creates and registers the first owner-only pipe instance before callers
     * are told that the server is ready. A named pipe does not exist in the
     * Windows namespace until CreateNamedPipeW succeeds, so deferring this to
     * [accept] leaves a startup window in which the one-shot helper open fails.
     */
    fun prepare() {
        synchronized(lifecycleLock) {
            if (closed) {
                throw ClosedChannelException()
            }
            if (preparedHandle == null) {
                preparedHandle = createPipeInstanceLocked()
            }
        }
    }

    fun accept(): WindowsNamedPipeConnection {
        val handle = takePreparedOrCreatePipeInstance()
        var connected = false
        try {
            connected = Kernel32.INSTANCE.ConnectNamedPipe(handle.value, null)
            if (!connected) {
                val error = Native.getLastError()
                connected = error == ERROR_PIPE_CONNECTED
                if (!connected) {
                    throw pipeException("ConnectNamedPipe", error)
                }
            }

            return WindowsNamedPipeConnection(
                handle = handle,
                onClose = {
                    synchronized(lifecycleLock) {
                        handles.remove(handle)
                    }
                },
            )
        } catch (e: Throwable) {
            handle.close()
            synchronized(lifecycleLock) {
                handles.remove(handle)
            }
            if (isClosed()) {
                throw ClosedChannelException()
            }
            throw e
        }
    }

    override fun close() {
        val resources = synchronized(lifecycleLock) {
            if (closed) {
                return
            }
            closed = true
            val activeHandles = handles.toList()
            handles.clear()
            preparedHandle = null
            val descriptor = securityDescriptor
            securityDescriptor = null
            activeHandles to descriptor
        }

        resources.first.forEach { handle ->
            handle.close()
        }

        // Release the security descriptor allocated by
        // ConvertStringSecurityDescriptorToSecurityDescriptorW.
        resources.second?.let(Kernel32.INSTANCE::LocalFree)
    }

    private fun takePreparedOrCreatePipeInstance(): WindowsNamedPipeHandle = synchronized(lifecycleLock) {
        if (closed) {
            throw ClosedChannelException()
        }

        preparedHandle?.also {
            preparedHandle = null
            return@synchronized it
        }
        createPipeInstanceLocked()
    }

    /** Must be called while [lifecycleLock] is held. */
    private fun createPipeInstanceLocked(): WindowsNamedPipeHandle {
        check(Thread.holdsLock(lifecycleLock))

        // Creation and registration share the lifecycle lock with close(), so
        // the security descriptor cannot be freed mid-call and every created
        // handle is either registered before shutdown or never exposed.
        val firstPipeFlag = if (firstInstance) {
            FILE_FLAG_FIRST_PIPE_INSTANCE
        } else {
            0
        }
        val rawHandle = Kernel32.INSTANCE.CreateNamedPipeW(
            WString(pipeName),
            PIPE_ACCESS_DUPLEX or firstPipeFlag,
            PIPE_TYPE_BYTE or PIPE_READMODE_BYTE or PIPE_WAIT or PIPE_REJECT_REMOTE_CLIENTS,
            PIPE_UNLIMITED_INSTANCES,
            PIPE_BUFFER_SIZE,
            PIPE_BUFFER_SIZE,
            0,
            securityAttributes.pointer,
        )

        if (Pointer.nativeValue(rawHandle) == INVALID_HANDLE_VALUE) {
            throw pipeException("CreateNamedPipeW", Native.getLastError())
        }

        firstInstance = false
        val handle = WindowsNamedPipeHandle(rawHandle)
        handles.add(handle)
        return handle
    }

    private fun isClosed(): Boolean = synchronized(lifecycleLock) {
        closed
    }
}

/** Owns a raw pipe handle and guarantees that the native value is closed once. */
internal class WindowsNamedPipeHandle(
    val value: Pointer,
) {
    private val closed = AtomicBoolean(false)

    fun isClosed(): Boolean = closed.get()

    fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        runCatching {
            Kernel32.INSTANCE.DisconnectNamedPipe(value)
        }
        closeHandle(value)
    }
}

internal class WindowsNamedPipeConnection(
    private val handle: WindowsNamedPipeHandle,
    private val onClose: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    /**
     * Checks the client-reported PID/user against the spawned helper as
     * defense in depth before the independent 32-byte IPC authentication.
     *
     * `GetNamedPipeClientProcessId` is spoofable through handle transfer and
     * PID reuse, so this check must never establish caller authorization by
     * itself. The per-launch random pipe name and stdin-delivered auth token
     * remain the security boundary for private helper IPC.
     */
    fun verifyClient(expectedProcess: Process) {
        ensureOpen()
        check(expectedProcess.isAlive) {
            "Expected agent process ${expectedProcess.pid()} is no longer alive"
        }

        val clientPid = IntByReference()
        val pidAvailable = Kernel32.INSTANCE.GetNamedPipeClientProcessId(
            handle.value,
            clientPid,
        )
        if (!pidAvailable) {
            throw pipeException(
                "GetNamedPipeClientProcessId",
                Native.getLastError(),
            )
        }

        val actualPid = Integer.toUnsignedLong(clientPid.value)
        val expectedPid = expectedProcess.pid()
        check(actualPid == expectedPid) {
            "IPC peer PID mismatch: expected $expectedPid, got $actualPid"
        }

        val expectedSid = currentUserSidString()
        val actualSid = processUserSidString(clientPid.value)
        check(actualSid == expectedSid) {
            "IPC peer user SID mismatch"
        }

        // This catches ordinary exit/reuse races, but does not make the
        // client-reported PID a non-spoofable identity.
        check(expectedProcess.isAlive) {
            "Expected agent process $expectedPid exited during peer verification"
        }
    }

    fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        checkBounds(buffer, offset, length)
        if (length == 0) {
            return 0
        }
        ensureOpen()

        val target = if (offset == 0 && length == buffer.size) {
            buffer
        } else {
            ByteArray(length)
        }
        val bytesRead = IntByReference()
        val ok = Kernel32.INSTANCE.ReadFile(
            handle.value,
            target,
            length,
            bytesRead,
            null,
        )
        if (!ok) {
            val error = Native.getLastError()
            if (error == ERROR_BROKEN_PIPE ||
                error == ERROR_PIPE_NOT_CONNECTED ||
                error == ERROR_NO_DATA ||
                error == ERROR_HANDLE_EOF
            ) {
                return -1
            }
            if (error == ERROR_INVALID_HANDLE || error == ERROR_OPERATION_ABORTED) {
                throw ClosedChannelException()
            }
            throw pipeException("ReadFile", error)
        }

        val count = bytesRead.value
        if (target !== buffer && count > 0) {
            target.copyInto(
                destination = buffer,
                destinationOffset = offset,
                startIndex = 0,
                endIndex = count,
            )
        }
        return count
    }

    fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        checkBounds(buffer, offset, length)
        ensureOpen()

        var remaining = length
        var sourceOffset = offset
        while (remaining > 0) {
            val chunk = if (sourceOffset == 0 && remaining == buffer.size) {
                buffer
            } else {
                buffer.copyOfRange(sourceOffset, sourceOffset + remaining)
            }
            val written = IntByReference()
            val ok = Kernel32.INSTANCE.WriteFile(
                handle.value,
                chunk,
                chunk.size,
                written,
                null,
            )
            if (!ok) {
                val error = Native.getLastError()
                if (error == ERROR_INVALID_HANDLE ||
                    error == ERROR_OPERATION_ABORTED ||
                    error == ERROR_BROKEN_PIPE ||
                    error == ERROR_NO_DATA ||
                    error == ERROR_PIPE_NOT_CONNECTED
                ) {
                    throw ClosedChannelException()
                }
                throw pipeException("WriteFile", error)
            }

            val count = written.value
            if (count <= 0) {
                throw EOFException("Named pipe write made no progress")
            }
            sourceOffset += count
            remaining -= count
        }
    }

    fun flush() {
        ensureOpen()
        val ok = Kernel32.INSTANCE.FlushFileBuffers(handle.value)
        if (!ok) {
            val error = Native.getLastError()
            if (error == ERROR_BROKEN_PIPE ||
                error == ERROR_NO_DATA ||
                error == ERROR_PIPE_NOT_CONNECTED
            ) {
                return
            }
            if (error == ERROR_INVALID_HANDLE || error == ERROR_OPERATION_ABORTED) {
                throw ClosedChannelException()
            }
            throw pipeException("FlushFileBuffers", error)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }

        handle.close()
        onClose()
    }

    private fun ensureOpen() {
        if (closed.get() || handle.isClosed()) {
            throw ClosedChannelException()
        }
    }
}

internal class WindowsNamedPipePacketChannel(
    private val connection: WindowsNamedPipeConnection,
) : AgentPacketChannel {
    override fun readPacket(): ByteArray? {
        val lenBuf = ByteArray(4)
        val bytesRead = readFully(lenBuf)
        if (bytesRead < 4) {
            return null
        }

        val len = ByteBuffer.wrap(lenBuf).int
        if (len <= 0 || len > MAX_PACKET_SIZE) {
            throw IllegalArgumentException("Invalid message length: $len")
        }

        val packet = ByteArray(len)
        readFully(packet)
        return packet
    }

    override fun writePacket(packet: ByteArray) {
        require(packet.size in 1..MAX_PACKET_SIZE) {
            "Invalid message length: ${packet.size}"
        }

        val lenBuf = ByteBuffer.allocate(4)
            .putInt(packet.size)
            .array()
        connection.write(lenBuf, 0, lenBuf.size)
        connection.write(packet, 0, packet.size)
        connection.flush()
    }

    private fun readFully(buffer: ByteArray): Int {
        var totalRead = 0
        while (totalRead < buffer.size) {
            val count = connection.read(
                buffer = buffer,
                offset = totalRead,
                length = buffer.size - totalRead,
            )
            if (count < 0) {
                if (totalRead == 0) {
                    return -1
                }
                throw EOFException("Unexpected end of stream")
            }
            if (count == 0) {
                continue
            }
            totalRead += count
        }
        return totalRead
    }
}

private fun checkBounds(
    buffer: ByteArray,
    offset: Int,
    length: Int,
) {
    require(offset >= 0) {
        "offset must be non-negative"
    }
    require(length >= 0) {
        "length must be non-negative"
    }
    require(offset <= buffer.size && length <= buffer.size - offset) {
        "offset=$offset length=$length size=${buffer.size}"
    }
}

private fun closeHandle(handle: Pointer) {
    Kernel32.INSTANCE.CloseHandle(handle)
}

private fun pipeException(
    functionName: String,
    error: Int,
): IOException = IOException("$functionName failed with Windows error $error")

/**
 * Builds a self-relative security descriptor whose DACL grants full control
 * to the current user only, and returns its native pointer (allocated by
 * [Advapi32.ConvertStringSecurityDescriptorToSecurityDescriptorW], to be freed
 * with [Kernel32.LocalFree]).
 *
 * The SDDL `O:<sid>D:P(A;;GA;;;<sid>)` assigns ownership to the current user
 * and installs a protected DACL (`P`, no inherited ACEs) with one `GENERIC_ALL`
 * (`GA`) allow ACE for that same SID. Explicit ownership matters for elevated
 * administrator tokens, whose default owner may otherwise be the Administrators
 * group even though the DACL names only the user.
 */
private fun buildOwnerOnlySecurityDescriptor(): Pointer {
    val sid = currentUserSidString()
    val sddl = "O:${sid}D:P(A;;GA;;;$sid)"
    val securityDescriptor = PointerByReference()
    val ok = Advapi32.INSTANCE.ConvertStringSecurityDescriptorToSecurityDescriptorW(
        WString(sddl),
        SDDL_REVISION_1,
        securityDescriptor,
        null,
    )
    if (!ok) {
        throw pipeException(
            "ConvertStringSecurityDescriptorToSecurityDescriptorW",
            Native.getLastError(),
        )
    }
    return securityDescriptor.value
}

/**
 * Returns the current process user's SID in string form (e.g. `S-1-5-21-...`).
 */
private fun currentUserSidString(): String = processUserSidString(
    Kernel32.INSTANCE.GetCurrentProcess(),
)

private fun processUserSidString(pid: Int): String {
    val processHandle = Kernel32.INSTANCE.OpenProcess(
        PROCESS_QUERY_LIMITED_INFORMATION,
        false,
        pid,
    )
    if (Pointer.nativeValue(processHandle) == 0L) {
        throw pipeException("OpenProcess", Native.getLastError())
    }
    try {
        return processUserSidString(processHandle)
    } finally {
        Kernel32.INSTANCE.CloseHandle(processHandle)
    }
}

private fun processUserSidString(processHandle: Pointer): String {
    val token = PointerByReference()
    val opened = Advapi32.INSTANCE.OpenProcessToken(
        processHandle,
        TOKEN_QUERY,
        token,
    )
    if (!opened) {
        throw pipeException("OpenProcessToken", Native.getLastError())
    }

    val tokenHandle = token.value
    try {
        // First call to determine the required buffer size.
        val size = IntByReference()
        Advapi32.INSTANCE.GetTokenInformation(
            tokenHandle,
            TOKEN_USER_INFORMATION_CLASS,
            null,
            0,
            size,
        )
        val sizeError = Native.getLastError()
        if (size.value <= 0) {
            throw pipeException("GetTokenInformation", sizeError)
        }

        // TOKEN_USER { SID_AND_ATTRIBUTES User { PSID Sid; DWORD Attributes } }.
        // The SID pointer occupies the first machine word of the buffer.
        val buffer = Memory(size.value.toLong())
        val ok = Advapi32.INSTANCE.GetTokenInformation(
            tokenHandle,
            TOKEN_USER_INFORMATION_CLASS,
            buffer,
            size.value,
            size,
        )
        if (!ok) {
            throw pipeException("GetTokenInformation", Native.getLastError())
        }

        val sidPointer = buffer.getPointer(0)
        val stringSid = PointerByReference()
        if (!Advapi32.INSTANCE.ConvertSidToStringSidW(sidPointer, stringSid)) {
            throw pipeException("ConvertSidToStringSidW", Native.getLastError())
        }

        val stringSidPointer = stringSid.value
        try {
            return stringSidPointer.getWideString(0)
        } finally {
            Kernel32.INSTANCE.LocalFree(stringSidPointer)
        }
    } finally {
        Kernel32.INSTANCE.CloseHandle(tokenHandle)
    }
}

@Structure.FieldOrder("nLength", "lpSecurityDescriptor", "bInheritHandle")
internal class WindowsSecurityAttributes(
    securityDescriptor: Pointer,
) : Structure() {
    @JvmField
    var nLength: Int = 0

    @JvmField
    var lpSecurityDescriptor: Pointer? = null

    @JvmField
    var bInheritHandle: Int = 0

    init {
        lpSecurityDescriptor = securityDescriptor
        bInheritHandle = 0
        nLength = size()
        write()
    }
}

@Suppress("FunctionName")
private interface Kernel32 : StdCallLibrary {
    companion object {
        val INSTANCE: Kernel32 by lazy {
            Native.load(
                "kernel32",
                Kernel32::class.java,
            ) as Kernel32
        }
    }

    fun CreateNamedPipeW(
        lpName: WString,
        dwOpenMode: Int,
        dwPipeMode: Int,
        nMaxInstances: Int,
        nOutBufferSize: Int,
        nInBufferSize: Int,
        nDefaultTimeOut: Int,
        lpSecurityAttributes: Pointer?,
    ): Pointer

    fun ConnectNamedPipe(
        hNamedPipe: Pointer,
        lpOverlapped: Pointer?,
    ): Boolean

    fun ReadFile(
        hFile: Pointer,
        lpBuffer: ByteArray,
        nNumberOfBytesToRead: Int,
        lpNumberOfBytesRead: IntByReference,
        lpOverlapped: Pointer?,
    ): Boolean

    fun WriteFile(
        hFile: Pointer,
        lpBuffer: ByteArray,
        nNumberOfBytesToWrite: Int,
        lpNumberOfBytesWritten: IntByReference,
        lpOverlapped: Pointer?,
    ): Boolean

    fun FlushFileBuffers(
        hFile: Pointer,
    ): Boolean

    fun DisconnectNamedPipe(
        hNamedPipe: Pointer,
    ): Boolean

    fun CloseHandle(
        hObject: Pointer,
    ): Boolean

    fun GetNamedPipeClientProcessId(
        Pipe: Pointer,
        ClientProcessId: IntByReference,
    ): Boolean

    fun OpenProcess(
        ProcessAccess: Int,
        InheritHandle: Boolean,
        ProcessId: Int,
    ): Pointer

    fun GetCurrentProcess(): Pointer

    fun LocalFree(
        hMem: Pointer,
    ): Pointer?
}

@Suppress("FunctionName")
private interface Advapi32 : StdCallLibrary {
    companion object {
        val INSTANCE: Advapi32 by lazy {
            Native.load(
                "advapi32",
                Advapi32::class.java,
            ) as Advapi32
        }
    }

    fun OpenProcessToken(
        ProcessHandle: Pointer,
        DesiredAccess: Int,
        TokenHandle: PointerByReference,
    ): Boolean

    fun GetTokenInformation(
        TokenHandle: Pointer,
        TokenInformationClass: Int,
        TokenInformation: Pointer?,
        TokenInformationLength: Int,
        ReturnLength: IntByReference,
    ): Boolean

    fun ConvertSidToStringSidW(
        Sid: Pointer,
        StringSid: PointerByReference,
    ): Boolean

    fun ConvertStringSecurityDescriptorToSecurityDescriptorW(
        StringSecurityDescriptor: WString,
        StringSDRevision: Int,
        SecurityDescriptor: PointerByReference,
        SecurityDescriptorSize: IntByReference?,
    ): Boolean
}
