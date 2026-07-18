package com.artemchep.keyguard.platform.windows

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

private const val TOKEN_QUERY = 0x0008
private const val TOKEN_USER_INFORMATION_CLASS = 1
private const val SDDL_REVISION_1 = 1
private const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000

/**
 * Owns a self-relative security descriptor and its non-inheritable
 * `SECURITY_ATTRIBUTES` wrapper.
 */
internal class WindowsOwnerOnlySecurityAttributes private constructor(
    private val securityDescriptor: Pointer,
    private val securityAttributes: WindowsSecurityAttributes,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val pointer: Pointer
        get() {
            check(!closed.get()) { "Windows security attributes are closed" }
            return securityAttributes.pointer
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        WindowsSecurityKernel32.INSTANCE.LocalFree(securityDescriptor)
    }

    companion object {
        /**
         * Builds `O:<sid>D:P(A;;GA;;;<sid>)`: the current user owns the object,
         * inheritance is disabled, and only that user receives full access.
         */
        fun create(): WindowsOwnerOnlySecurityAttributes {
            val sid = currentWindowsUserSidString()
            val securityDescriptor = PointerByReference()
            val ok = WindowsSecurityAdvapi32.INSTANCE
                .ConvertStringSecurityDescriptorToSecurityDescriptorW(
                    WString(ownerOnlySecurityDescriptorSddl(sid)),
                    SDDL_REVISION_1,
                    securityDescriptor,
                    null,
                )
            if (!ok) {
                throw windowsSecurityException(
                    "ConvertStringSecurityDescriptorToSecurityDescriptorW",
                    Native.getLastError(),
                )
            }

            val pointer = securityDescriptor.value
            return try {
                WindowsOwnerOnlySecurityAttributes(
                    securityDescriptor = pointer,
                    securityAttributes = WindowsSecurityAttributes(pointer),
                )
            } catch (error: Throwable) {
                WindowsSecurityKernel32.INSTANCE.LocalFree(pointer)
                throw error
            }
        }
    }
}

internal fun ownerOnlySecurityDescriptorSddl(sid: String): String =
    "O:${sid}D:P(A;;GA;;;$sid)"

internal fun currentWindowsUserSidString(): String = windowsProcessUserSidString(
    WindowsSecurityKernel32.INSTANCE.GetCurrentProcess(),
)

internal fun windowsProcessUserSidString(pid: Int): String {
    val processHandle = WindowsSecurityKernel32.INSTANCE.OpenProcess(
        PROCESS_QUERY_LIMITED_INFORMATION,
        false,
        pid,
    )
    if (Pointer.nativeValue(processHandle) == 0L) {
        throw windowsSecurityException("OpenProcess", Native.getLastError())
    }
    try {
        return windowsProcessUserSidString(processHandle)
    } finally {
        WindowsSecurityKernel32.INSTANCE.CloseHandle(processHandle)
    }
}

private fun windowsProcessUserSidString(processHandle: Pointer): String {
    val token = PointerByReference()
    val opened = WindowsSecurityAdvapi32.INSTANCE.OpenProcessToken(
        processHandle,
        TOKEN_QUERY,
        token,
    )
    if (!opened) {
        throw windowsSecurityException("OpenProcessToken", Native.getLastError())
    }

    val tokenHandle = token.value
    try {
        val size = IntByReference()
        WindowsSecurityAdvapi32.INSTANCE.GetTokenInformation(
            tokenHandle,
            TOKEN_USER_INFORMATION_CLASS,
            null,
            0,
            size,
        )
        val sizeError = Native.getLastError()
        if (size.value <= 0) {
            throw windowsSecurityException("GetTokenInformation", sizeError)
        }

        // TOKEN_USER { SID_AND_ATTRIBUTES User { PSID Sid; DWORD Attributes } }.
        // The SID pointer occupies the first machine word of the buffer.
        return Memory(size.value.toLong()).use { buffer ->
            val ok = WindowsSecurityAdvapi32.INSTANCE.GetTokenInformation(
                tokenHandle,
                TOKEN_USER_INFORMATION_CLASS,
                buffer,
                size.value,
                size,
            )
            if (!ok) {
                throw windowsSecurityException("GetTokenInformation", Native.getLastError())
            }

            val sidPointer = buffer.getPointer(0)
            val stringSid = PointerByReference()
            if (!WindowsSecurityAdvapi32.INSTANCE.ConvertSidToStringSidW(sidPointer, stringSid)) {
                throw windowsSecurityException("ConvertSidToStringSidW", Native.getLastError())
            }

            val stringSidPointer = stringSid.value
            try {
                stringSidPointer.getWideString(0)
            } finally {
                WindowsSecurityKernel32.INSTANCE.LocalFree(stringSidPointer)
            }
        }
    } finally {
        WindowsSecurityKernel32.INSTANCE.CloseHandle(tokenHandle)
    }
}

private fun windowsSecurityException(
    functionName: String,
    error: Int,
): IOException = IOException("$functionName failed with Windows error $error")

@Structure.FieldOrder("nLength", "lpSecurityDescriptor", "bInheritHandle")
private class WindowsSecurityAttributes(
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
private interface WindowsSecurityKernel32 : StdCallLibrary {
    companion object {
        val INSTANCE: WindowsSecurityKernel32 by lazy {
            Native.load(
                "kernel32",
                WindowsSecurityKernel32::class.java,
            ) as WindowsSecurityKernel32
        }
    }

    fun OpenProcess(
        ProcessAccess: Int,
        InheritHandle: Boolean,
        ProcessId: Int,
    ): Pointer

    fun GetCurrentProcess(): Pointer

    fun CloseHandle(
        hObject: Pointer,
    ): Boolean

    fun LocalFree(
        hMem: Pointer,
    ): Pointer?
}

@Suppress("FunctionName")
private interface WindowsSecurityAdvapi32 : StdCallLibrary {
    companion object {
        val INSTANCE: WindowsSecurityAdvapi32 by lazy {
            Native.load(
                "advapi32",
                WindowsSecurityAdvapi32::class.java,
            ) as WindowsSecurityAdvapi32
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
