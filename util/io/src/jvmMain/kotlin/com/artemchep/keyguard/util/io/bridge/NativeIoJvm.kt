package com.artemchep.keyguard.util.io.bridge

import com.artemchep.keyguard.util.io.FileSystemFailure
import com.artemchep.keyguard.util.io.FileSystemFailureKind
import com.artemchep.keyguard.util.io.FileSystemOperationException
import com.artemchep.keyguard.util.io.NativeIoJni
import kotlinx.io.IOException

internal expect object NativeIoLibraryLoader {
    fun ensureLoaded()
}

// The cohesive actual object mirrors the fixed JNI ABI surface; splitting it would obscure
// expect/actual parity and move calls away from the load and compatibility checks guarding them.
@Suppress("TooManyFunctions")
internal actual object NativeIo {
    @Volatile
    private var abiVerified = false

    actual fun directoryOpen(directory: String): Long = withLibrary {
        NativeIoJni.directoryOpen(directory)
    }

    actual fun directoryClose(handle: Long): Long = withLibrary {
        NativeIoJni.directoryClose(handle)
    }

    actual fun txnBegin(
        destination: String,
        options: NativeIoTxnOptions,
    ): Long = withLibrary {
        NativeIoJni.txnBegin(
            destination,
            options.toWireFields(),
        )
    }

    actual fun txnBeginAtDirectory(
        directoryHandle: Long,
        relativeDestination: String,
        options: NativeIoTxnOptions,
    ): Long = withLibrary {
        NativeIoJni.txnBeginAtDirectory(
            directoryHandle,
            relativeDestination,
            options.toWireFields(),
        )
    }

    actual fun txnWrite(
        handle: Long,
        input: ByteArray,
        offset: Int,
        length: Int,
    ): Long = withLibrary {
        NativeIoJni.txnWrite(handle, input, offset, length)
    }

    actual fun txnCommit(handle: Long): Long = withLibrary {
        NativeIoJni.txnCommit(handle)
    }

    actual fun txnAbort(handle: Long): Long = withLibrary {
        NativeIoJni.txnAbort(handle)
    }

    actual fun scratchOpen(directory: String): Long = withLibrary {
        NativeIoJni.scratchOpen(directory)
    }

    actual fun scratchWrite(
        handle: Long,
        input: ByteArray,
        offset: Int,
        length: Int,
    ): Long = withLibrary {
        NativeIoJni.scratchWrite(handle, input, offset, length)
    }

    actual fun scratchSeal(handle: Long): Long = withLibrary {
        NativeIoJni.scratchSeal(handle)
    }

    actual fun scratchLength(handle: Long): Long = withLibrary {
        NativeIoJni.scratchLength(handle)
    }

    actual fun scratchReadAt(
        handle: Long,
        position: Long,
        output: ByteArray,
        offset: Int,
        length: Int,
    ): Long = withLibrary {
        NativeIoJni.scratchReadAt(handle, position, output, offset, length)
    }

    actual fun scratchClose(handle: Long): Long = withLibrary {
        NativeIoJni.scratchClose(handle)
    }

    actual fun sweepOrphans(
        directory: String,
        olderThanMs: Long,
        roleMask: Int,
    ): LongArray = withLibrary {
        NativeIoJni.sweepOrphans(directory, olderThanMs, roleMask)
    }

    private inline fun <T> withLibrary(block: () -> T): T {
        return try {
            NativeIoLibraryLoader.ensureLoaded()
            ensureCompatibleAbi()
            block()
        } catch (error: FileSystemOperationException) {
            throw error
        } catch (error: IOException) {
            throw nativeIoInternalFailure(
                message = "Native IO operation failed inside the platform bridge",
                cause = error,
            )
        } catch (error: UnsatisfiedLinkError) {
            nativeIoUnavailable(error)
        } catch (error: SecurityException) {
            nativeIoUnavailable(error)
        }
    }

    private fun ensureCompatibleAbi() {
        if (abiVerified) return
        synchronized(this) {
            if (abiVerified) return
            val actual = try {
                NativeIoJni.abiVersion()
            } catch (error: UnsatisfiedLinkError) {
                nativeIoUnavailable(error)
            } catch (error: SecurityException) {
                nativeIoUnavailable(error)
            }
            if (actual != NATIVE_IO_ABI_VERSION) {
                throw nativeIoInternalFailure(
                    message = "Unsupported native IO ABI $actual; expected $NATIVE_IO_ABI_VERSION",
                )
            }
            abiVerified = true
        }
    }

    private fun nativeIoUnavailable(cause: Throwable): Nothing =
        throw nativeIoInternalFailure(
            message = "Native IO library is unavailable",
            cause = cause,
        )

    private fun nativeIoInternalFailure(
        message: String,
        cause: Throwable? = null,
    ): FileSystemOperationException = FileSystemOperationException(
        message = message,
        cause = cause,
        failure = FileSystemFailure(
            kind = FileSystemFailureKind.Internal,
        ),
    )
}
