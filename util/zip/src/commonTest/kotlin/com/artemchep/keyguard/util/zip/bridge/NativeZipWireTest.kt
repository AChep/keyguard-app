package com.artemchep.keyguard.util.zip.bridge

import com.artemchep.keyguard.util.zip.ZipException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Golden wire vectors mirrored by the Rust `keyguard-zip-core` error tests;
 * changing any value is an ABI break.
 */
private object GoldenVectors {
    val BRIDGE_INVALID_ARGUMENT: Long = "8000000001030800".toULong(16).toLong()
    val BRIDGE_PANIC: Long = "8000000002030C00".toULong(16).toLong()
    val BRIDGE_NAME_TOO_LONG: Long = "8000000006030800".toULong(16).toLong()
    val WRITE_PERMISSION_DENIED: Long = "800000000D010103".toULong(16).toLong()
    val FINISH_STORAGE_FULL: Long = "800000001C010505".toULong(16).toLong()
    val BRIDGE_WRONG_PASSWORD: Long = "8000000008030800".toULong(16).toLong()
    val BRIDGE_UNSUPPORTED_ENTRY: Long = "8000000009030A00".toULong(16).toLong()
    val BRIDGE_BUFFER_TOO_SMALL: Long = "800000000A030800".toULong(16).toLong()
    val READER_OPEN_NOT_FOUND: Long = "8000000002010307".toULong(16).toLong()
    val READ_ARCHIVE: Long = "8000000007030809".toULong(16).toLong()
}

class NativeZipWireTest {
    @Test
    fun nativeAbiVersionIsOne() {
        assertEquals(1, NATIVE_ZIP_ABI_VERSION)
    }

    @Test
    fun operationCodesArePinned() {
        assertEquals(0, NATIVE_ZIP_OP_BRIDGE)
        assertEquals(1, NATIVE_ZIP_OP_OPEN)
        assertEquals(2, NATIVE_ZIP_OP_BEGIN_ENTRY)
        assertEquals(3, NATIVE_ZIP_OP_WRITE)
        assertEquals(4, NATIVE_ZIP_OP_END_ENTRY)
        assertEquals(5, NATIVE_ZIP_OP_FINISH)
        assertEquals(6, NATIVE_ZIP_OP_ABORT)
        assertEquals(7, NATIVE_ZIP_OP_READER_OPEN)
        assertEquals(8, NATIVE_ZIP_OP_NEXT_ENTRY)
        assertEquals(9, NATIVE_ZIP_OP_READ)
        assertEquals(10, NATIVE_ZIP_OP_CLOSE)
    }

    @Test
    fun endOfArchiveIsUnrepresentableAsAFailure() {
        assertEquals(-1L, NATIVE_ZIP_END_OF_ARCHIVE)
        assertFailsWith<ZipException> {
            decodeNativeZipFailure(NATIVE_ZIP_END_OF_ARCHIVE)
        }
    }

    @Test
    fun failureKindCodesArePinned() {
        assertEquals(0, NATIVE_ZIP_FAILURE_NONE)
        assertEquals(1, NATIVE_ZIP_FAILURE_PERMISSION_DENIED)
        assertEquals(2, NATIVE_ZIP_FAILURE_READ_ONLY_FILESYSTEM)
        assertEquals(3, NATIVE_ZIP_FAILURE_NOT_FOUND)
        assertEquals(4, NATIVE_ZIP_FAILURE_ALREADY_EXISTS)
        assertEquals(5, NATIVE_ZIP_FAILURE_STORAGE_FULL)
        assertEquals(6, NATIVE_ZIP_FAILURE_QUOTA_EXCEEDED)
        assertEquals(7, NATIVE_ZIP_FAILURE_RESOURCE_BUSY)
        assertEquals(8, NATIVE_ZIP_FAILURE_INVALID_INPUT)
        assertEquals(9, NATIVE_ZIP_FAILURE_INTERRUPTED)
        assertEquals(10, NATIVE_ZIP_FAILURE_UNSUPPORTED)
        assertEquals(11, NATIVE_ZIP_FAILURE_OTHER)
        assertEquals(12, NATIVE_ZIP_FAILURE_INTERNAL)
    }

    @Test
    fun errorDomainAndBridgeCodesArePinned() {
        assertEquals(0, NATIVE_ZIP_DOMAIN_NONE)
        assertEquals(1, NATIVE_ZIP_DOMAIN_POSIX_ERRNO)
        assertEquals(3, NATIVE_ZIP_DOMAIN_BRIDGE)
        assertEquals(1, NATIVE_ZIP_BRIDGE_CODE_INVALID_ARGUMENT)
        assertEquals(2, NATIVE_ZIP_BRIDGE_CODE_PANIC)
        assertEquals(3, NATIVE_ZIP_BRIDGE_CODE_INTERNAL)
        assertEquals(4, NATIVE_ZIP_BRIDGE_CODE_INVALID_HANDLE)
        assertEquals(5, NATIVE_ZIP_BRIDGE_CODE_INVALID_STATE)
        assertEquals(6, NATIVE_ZIP_BRIDGE_CODE_NAME_TOO_LONG)
        assertEquals(7, NATIVE_ZIP_BRIDGE_CODE_ARCHIVE)
        assertEquals(8, NATIVE_ZIP_BRIDGE_CODE_WRONG_PASSWORD)
        assertEquals(9, NATIVE_ZIP_BRIDGE_CODE_UNSUPPORTED_ENTRY)
        assertEquals(10, NATIVE_ZIP_BRIDGE_CODE_BUFFER_TOO_SMALL)
        assertEquals(4096, NATIVE_ZIP_MAX_ENTRY_NAME_BYTES)
        assertEquals(4096, NATIVE_ZIP_MAX_PATH_BYTES)
    }

    @Test
    fun bridgeInvalidArgumentConstantMatchesTheGoldenVector() {
        assertEquals(
            GoldenVectors.BRIDGE_INVALID_ARGUMENT,
            NATIVE_ZIP_BRIDGE_INVALID_ARGUMENT,
        )
    }

    @Test
    fun successStatusesAreNotFailures() {
        assertFalse(isNativeZipFailure(NATIVE_ZIP_STATUS_SUCCESS))
        assertFalse(isNativeZipFailure(1L))
        assertFalse(isNativeZipFailure(Long.MAX_VALUE))
        assertTrue(isNativeZipFailure(GoldenVectors.BRIDGE_INVALID_ARGUMENT))
    }

    @Test
    fun bridgeFailureVectorsDecode() {
        assertEquals(
            NativeZipFailure(
                operation = NATIVE_ZIP_OP_BRIDGE,
                kind = NativeZipFailureKind.InvalidInput,
                domain = NATIVE_ZIP_DOMAIN_BRIDGE,
                rawCode = NATIVE_ZIP_BRIDGE_CODE_INVALID_ARGUMENT,
            ),
            decodeNativeZipFailure(GoldenVectors.BRIDGE_INVALID_ARGUMENT),
        )
        assertEquals(
            NativeZipFailure(
                operation = NATIVE_ZIP_OP_BRIDGE,
                kind = NativeZipFailureKind.Internal,
                domain = NATIVE_ZIP_DOMAIN_BRIDGE,
                rawCode = NATIVE_ZIP_BRIDGE_CODE_PANIC,
            ),
            decodeNativeZipFailure(GoldenVectors.BRIDGE_PANIC),
        )
    }

    @Test
    fun posixFailureVectorsDecode() {
        assertEquals(
            NativeZipFailure(
                operation = NATIVE_ZIP_OP_WRITE,
                kind = NativeZipFailureKind.PermissionDenied,
                domain = NATIVE_ZIP_DOMAIN_POSIX_ERRNO,
                rawCode = 13,
            ),
            decodeNativeZipFailure(GoldenVectors.WRITE_PERMISSION_DENIED),
        )
        assertEquals(
            NativeZipFailure(
                operation = NATIVE_ZIP_OP_FINISH,
                kind = NativeZipFailureKind.StorageFull,
                domain = NATIVE_ZIP_DOMAIN_POSIX_ERRNO,
                rawCode = 28,
            ),
            decodeNativeZipFailure(GoldenVectors.FINISH_STORAGE_FULL),
        )
    }

    @Test
    fun everyFieldRoundTrips() {
        // The raw code is unsigned 32 bit; its top bit must survive the trip.
        val packed = packNativeZipFailure(
            operation = NATIVE_ZIP_OP_BEGIN_ENTRY,
            kind = NATIVE_ZIP_FAILURE_QUOTA_EXCEEDED,
            domain = NATIVE_ZIP_DOMAIN_POSIX_ERRNO,
            rawCode = 0xffffffffL,
        )
        assertEquals(
            NativeZipFailure(
                operation = NATIVE_ZIP_OP_BEGIN_ENTRY,
                kind = NativeZipFailureKind.QuotaExceeded,
                domain = NATIVE_ZIP_DOMAIN_POSIX_ERRNO,
                rawCode = -1,
            ),
            decodeNativeZipFailure(packed),
        )
    }

    @Test
    fun unknownKindsDecodeInsteadOfThrowing() {
        val packed = packNativeZipFailure(
            operation = NATIVE_ZIP_OP_ABORT,
            kind = 42,
            domain = NATIVE_ZIP_DOMAIN_NONE,
            rawCode = 0L,
        )
        assertEquals(
            NativeZipFailureKind.Unknown,
            decodeNativeZipFailure(packed).kind,
        )
    }

    @Test
    fun successStatusIsRejectedByTheDecoder() {
        assertFailsWith<ZipException> {
            decodeNativeZipFailure(NATIVE_ZIP_STATUS_SUCCESS)
        }
    }

    @Test
    fun reservedBitsAreRejected() {
        val packed = GoldenVectors.BRIDGE_INVALID_ARGUMENT or (1L shl 56)
        assertFailsWith<ZipException> {
            decodeNativeZipFailure(packed)
        }
        val allReserved = GoldenVectors.BRIDGE_INVALID_ARGUMENT or (0x7fL shl 56)
        assertFailsWith<ZipException> {
            decodeNativeZipFailure(allReserved)
        }
    }

    @Test
    fun posixFailuresAreReportedWithTheirErrno() {
        assertEquals(
            "Native zip failed to write an entry: permission denied (errno 13)",
            nativeZipFailureException(GoldenVectors.WRITE_PERMISSION_DENIED).message,
        )
        assertEquals(
            "Native zip failed to finish the archive: no space left on the device (errno 28)",
            nativeZipFailureException(GoldenVectors.FINISH_STORAGE_FULL).message,
        )
    }

    @Test
    fun bridgeFailuresAreReportedByTheirCause() {
        assertEquals(
            "Native zip rejected an entry name that is too long",
            nativeZipFailureException(GoldenVectors.BRIDGE_NAME_TOO_LONG).message,
        )
        assertEquals(
            "Native zip rejected an argument",
            nativeZipFailureException(GoldenVectors.BRIDGE_INVALID_ARGUMENT).message,
        )
        assertEquals(
            "Native zip panicked while writing an archive",
            nativeZipFailureException(GoldenVectors.BRIDGE_PANIC).message,
        )
    }

    @Test
    fun readerFailuresAreReportedByTheirCause() {
        assertEquals(
            "Native zip rejected the archive password",
            nativeZipFailureException(GoldenVectors.BRIDGE_WRONG_PASSWORD).message,
        )
        assertEquals(
            "Native zip cannot read an entry with an unsupported method",
            nativeZipFailureException(GoldenVectors.BRIDGE_UNSUPPORTED_ENTRY).message,
        )
        assertEquals(
            "Native zip rejected a buffer too small for an entry name",
            nativeZipFailureException(GoldenVectors.BRIDGE_BUFFER_TOO_SMALL).message,
        )
        assertEquals(
            "Native zip could not read the archive",
            nativeZipFailureException(GoldenVectors.READ_ARCHIVE).message,
        )
        assertEquals(
            "Native zip failed to open an archive for reading: not found (errno 2)",
            nativeZipFailureException(GoldenVectors.READER_OPEN_NOT_FOUND).message,
        )
    }

    @Test
    fun readerFailureVectorsDecode() {
        assertEquals(
            NativeZipFailure(
                operation = NATIVE_ZIP_OP_BRIDGE,
                kind = NativeZipFailureKind.Unsupported,
                domain = NATIVE_ZIP_DOMAIN_BRIDGE,
                rawCode = NATIVE_ZIP_BRIDGE_CODE_UNSUPPORTED_ENTRY,
            ),
            decodeNativeZipFailure(GoldenVectors.BRIDGE_UNSUPPORTED_ENTRY),
        )
        assertEquals(
            NativeZipFailure(
                operation = NATIVE_ZIP_OP_READER_OPEN,
                kind = NativeZipFailureKind.NotFound,
                domain = NATIVE_ZIP_DOMAIN_POSIX_ERRNO,
                rawCode = 2,
            ),
            decodeNativeZipFailure(GoldenVectors.READER_OPEN_NOT_FOUND),
        )
        assertEquals(
            NativeZipFailure(
                operation = NATIVE_ZIP_OP_READ,
                kind = NativeZipFailureKind.InvalidInput,
                domain = NATIVE_ZIP_DOMAIN_BRIDGE,
                rawCode = NATIVE_ZIP_BRIDGE_CODE_ARCHIVE,
            ),
            decodeNativeZipFailure(GoldenVectors.READ_ARCHIVE),
        )
    }

    @Test
    fun failureMessagesNeverQuoteTheirArguments() {
        val messages = listOf(
            GoldenVectors.BRIDGE_INVALID_ARGUMENT,
            GoldenVectors.BRIDGE_NAME_TOO_LONG,
            GoldenVectors.WRITE_PERMISSION_DENIED,
            GoldenVectors.FINISH_STORAGE_FULL,
            GoldenVectors.BRIDGE_WRONG_PASSWORD,
            GoldenVectors.BRIDGE_UNSUPPORTED_ENTRY,
            GoldenVectors.BRIDGE_BUFFER_TOO_SMALL,
            GoldenVectors.READER_OPEN_NOT_FOUND,
            GoldenVectors.READ_ARCHIVE,
        ).map { packed -> nativeZipFailureException(packed).message.orEmpty() }
        messages.forEach { message ->
            assertTrue(message.startsWith("Native zip "), message)
            assertFalse(message.contains('/'), message)
        }
    }
}

private fun packNativeZipFailure(
    operation: Int,
    kind: Int,
    domain: Int,
    rawCode: Long,
): Long = (1L shl 63) or
    (rawCode shl 24) or
    (domain.toLong() shl 16) or
    (kind.toLong() shl 8) or
    operation.toLong()
