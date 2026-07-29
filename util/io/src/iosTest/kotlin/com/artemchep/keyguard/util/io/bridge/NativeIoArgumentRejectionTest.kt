package com.artemchep.keyguard.util.io.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Arguments the ABI cannot represent must be refused with the same scalar the
 * JNI bridge returns, not silently reinterpreted.
 *
 * Both values below reach an unsigned ABI parameter. Converting them without
 * a check does not fail — it asks a different question and answers it
 * successfully, which is the dangerous shape.
 */
class NativeIoArgumentRejectionTest {
    @Test
    fun negativePositionIsRejectedInsteadOfWrappingPastEndOfFile() {
        // Wrapped, -1 becomes 2^64-1: a read far past the end that the caller
        // cannot distinguish from a legitimate end-of-file.
        for (position in longArrayOf(-1L, -4096L, Long.MIN_VALUE)) {
            val result = NativeIo.scratchReadAt(
                handle = 0L,
                position = position,
                output = ByteArray(8),
                offset = 0,
                length = 8,
            )

            assertEquals(
                NATIVE_IO_BRIDGE_INVALID_ARGUMENT,
                result,
                "position=$position must be rejected",
            )
            assertTrue(result != -1L, "rejection must not be mistaken for end-of-file")
        }
    }

    @Test
    fun negativeSweepAgeIsRejectedInsteadOfSweepingEverything() {
        // Coerced to zero this would reclaim artifacts of any age, including
        // those a concurrent transaction is still writing.
        for (olderThanMs in longArrayOf(-1L, Long.MIN_VALUE)) {
            val wire = NativeIo.sweepOrphans(
                directory = "/nonexistent",
                olderThanMs = olderThanMs,
                roleMask = SWEEP_ROLE_MASK_ALL,
            )

            assertEquals(1, wire.size, "olderThanMs=$olderThanMs must be a scalar failure")
            assertEquals(
                NATIVE_IO_BRIDGE_INVALID_ARGUMENT,
                wire.single(),
                "olderThanMs=$olderThanMs must be rejected",
            )
        }
    }

    @Test
    fun nonNegativeBoundaryArgumentsStillReachTheNativeCall() {
        // The guards must not over-reject the smallest legal values.
        val read = NativeIo.scratchReadAt(
            handle = 0L,
            position = 0L,
            output = ByteArray(8),
            offset = 0,
            length = 8,
        )
        assertTrue(
            isNativeIoFailure(read) && read != NATIVE_IO_BRIDGE_INVALID_ARGUMENT,
            "position=0 must reach the native call and fail on the unknown handle",
        )

        val wire = NativeIo.sweepOrphans(
            directory = "/nonexistent",
            olderThanMs = 0L,
            roleMask = SWEEP_ROLE_MASK_ALL,
        )
        assertTrue(
            wire.size != 1 || wire.single() != NATIVE_IO_BRIDGE_INVALID_ARGUMENT,
            "olderThanMs=0 must reach the native call",
        )
    }

    private companion object {
        const val SWEEP_ROLE_MASK_ALL = 0x7
    }
}
