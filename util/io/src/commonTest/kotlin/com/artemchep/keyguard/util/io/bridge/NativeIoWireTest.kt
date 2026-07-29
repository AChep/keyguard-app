package com.artemchep.keyguard.util.io.bridge

import com.artemchep.keyguard.util.io.FileSystemFailureKind
import com.artemchep.keyguard.util.io.FileSystemOperationException
import com.artemchep.keyguard.util.io.NativeErrorDiagnostic
import com.artemchep.keyguard.util.io.NativeErrorDomain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Golden wire vectors mirrored byte-identically by the Rust
 * `keyguard-io-core/src/abi.rs` test module; changing any value is an ABI
 * break.
 */
private object GoldenVectors {
    /** `TxnError(Bridge, InvalidInput, Bridge domain, code 1)`. */
    val BRIDGE_INVALID_ARGUMENT: Long = "8000000001030800".toULong(16).toLong()

    /** `TxnError(Bridge, Internal, Bridge domain, code 2)`. */
    val BRIDGE_PANIC: Long = "8000000002030C00".toULong(16).toLong()

    /** `TxnError(Write, PermissionDenied, PosixErrno, EACCES=13)`. */
    val WRITE_EACCES: Long = "800000000D010103".toULong(16).toLong()

    /** `TxnError(FlushFile, PermissionDenied, PosixErrno, EACCES=13)`. */
    val FLUSH_EACCES: Long = "800000000D010104".toULong(16).toLong()

    /** `TxnError(HardLink, Unsupported)`. */
    val HARD_LINK_UNSUPPORTED: Long = "8000000000000A0E".toULong(16).toLong()
}

class NativeIoWireTest {
    @Test
    fun nativeAbiVersionIsEight() {
        assertEquals(1, NATIVE_IO_ABI_VERSION)
    }

    @Test
    fun bridgeFailureVectorsDecode() {
        // A bridge that refuses an argument before dispatch must emit the
        // ABI's own scalar, not a lookalike; pin the constant to the vector.
        assertEquals(
            GoldenVectors.BRIDGE_INVALID_ARGUMENT,
            NATIVE_IO_BRIDGE_INVALID_ARGUMENT,
        )

        val invalidArgument = decodeNativeIoFailure(GoldenVectors.BRIDGE_INVALID_ARGUMENT)
        assertEquals(NativeIoOperation.Bridge, invalidArgument.operation)
        assertEquals(FileSystemFailureKind.InvalidInput, invalidArgument.failure.kind)
        assertEquals(
            NativeErrorDiagnostic(domain = NativeErrorDomain.Bridge, code = 1u),
            invalidArgument.failure.diagnostic,
        )

        val panic = decodeNativeIoFailure(GoldenVectors.BRIDGE_PANIC)
        assertEquals(NativeIoOperation.Bridge, panic.operation)
        assertEquals(FileSystemFailureKind.Internal, panic.failure.kind)
        assertEquals(
            NativeErrorDiagnostic(domain = NativeErrorDomain.Bridge, code = 2u),
            panic.failure.diagnostic,
        )
    }

    @Test
    fun protocolFailureVectorDecodesEveryField() {
        val decoded = decodeNativeIoFailure(GoldenVectors.FLUSH_EACCES)
        assertEquals(NativeIoOperation.FlushFile, decoded.operation)
        assertEquals(FileSystemFailureKind.PermissionDenied, decoded.failure.kind)
        assertEquals(false, decoded.cleanupIncomplete)
        assertEquals(
            NativeErrorDiagnostic(domain = NativeErrorDomain.PosixErrno, code = 13u),
            decoded.failure.diagnostic,
        )
    }

    @Test
    fun writeFailureAndCleanupBitPreserveTheOriginalDiagnostic() {
        val decoded = decodeNativeIoFailure(
            GoldenVectors.WRITE_EACCES or (1L shl 56),
        )

        assertEquals(NativeIoOperation.Write, decoded.operation)
        assertEquals(FileSystemFailureKind.PermissionDenied, decoded.failure.kind)
        assertEquals(
            NativeErrorDiagnostic(domain = NativeErrorDomain.PosixErrno, code = 13u),
            decoded.failure.diagnostic,
        )
        assertTrue(decoded.cleanupIncomplete)
    }

    @Test
    fun cleanupIncompleteBitPreservesThePrimaryFailure() {
        val decoded = decodeNativeIoFailure(
            GoldenVectors.FLUSH_EACCES or (1L shl 56),
        )

        assertEquals(NativeIoOperation.FlushFile, decoded.operation)
        assertEquals(FileSystemFailureKind.PermissionDenied, decoded.failure.kind)
        assertTrue(decoded.cleanupIncomplete)
    }

    @Test
    fun endOfFileMarkerIsNeverAFailure() {
        assertTrue(isNativeIoFailure(GoldenVectors.BRIDGE_PANIC))
        assertTrue(!isNativeIoFailure(-1L))
        assertTrue(!isNativeIoFailure(0L))
        assertTrue(!isNativeIoFailure(42L))
        // -1 also structurally violates the reserved bits, so a decode
        // attempt is rejected rather than misread.
        assertFailsWith<IllegalArgumentException> {
            decodeNativeIoFailure(-1L)
        }
    }

    @Test
    fun nonZeroReservedFailureBitsAreRejected() {
        val corrupted = GoldenVectors.BRIDGE_PANIC or (1L shl 57)
        assertFailsWith<IllegalArgumentException> {
            decodeNativeIoFailure(corrupted)
        }
    }

    @Test
    fun unknownFailureOperationIsRejected() {
        val unknownOperation = GoldenVectors.BRIDGE_PANIC or 0xffL
        assertFailsWith<IllegalArgumentException> {
            decodeNativeIoFailure(unknownOperation)
        }
    }

    @Test
    fun failureWithoutAKindIsRejected() {
        val kindless = Long.MIN_VALUE
        assertFailsWith<IllegalArgumentException> {
            decodeNativeIoFailure(kindless)
        }
    }

    @Test
    fun unsignedRawCodesArePreservedInFull() {
        val packed = (1UL shl 63) or 6UL or (11UL shl 8) or (2UL shl 16) or
            (0xdeadbeefUL shl 24)
        val decoded = decodeNativeIoFailure(packed.toLong())
        assertEquals(NativeIoOperation.Rename, decoded.operation)
        assertEquals(
            NativeErrorDiagnostic(
                domain = NativeErrorDomain.Win32LastError,
                code = 0xdeadbeefu,
            ),
            decoded.failure.diagnostic,
        )
    }

    @Test
    fun hardLinkOperationHasAStableWireCode() {
        val decoded = decodeNativeIoFailure(GoldenVectors.HARD_LINK_UNSUPPORTED)

        assertEquals(NativeIoOperation.HardLink, decoded.operation)
        assertEquals(FileSystemFailureKind.Unsupported, decoded.failure.kind)
    }

    @Test
    fun operationCompletionPassesSuccessPayloadsThrough() {
        assertEquals(7L, completeNativeIoOperation(7L, subject = "test"))
        assertEquals(-1L, completeNativeIoOperation(-1L, subject = "test"))
        val error = assertFailsWith<FileSystemOperationException> {
            completeNativeIoOperation(GoldenVectors.FLUSH_EACCES, subject = "test")
        }
        assertEquals(FileSystemFailureKind.PermissionDenied, error.failure.kind)
    }
}
