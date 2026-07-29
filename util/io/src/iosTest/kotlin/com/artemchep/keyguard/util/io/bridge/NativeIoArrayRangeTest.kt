package com.artemchep.keyguard.util.io.bridge

import com.artemchep.keyguard.util.io.FileSystemFailureKind
import com.artemchep.keyguard.util.io.FileSystemOperationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The Apple bridge owns the only bounds check in front of the C ABI, which
 * validates neither the pointer nor the length of a caller-selected window.
 *
 * Every case here is rejected before any handle is touched, so an invalid
 * handle is enough to reach the check.
 */
class NativeIoArrayRangeTest {
    @Test
    fun overflowingWindowIsRejectedBeforeTheCBridge() {
        // `offset + length` wraps to Int.MIN_VALUE, which is not greater than
        // any array size. Computed that way the window is accepted and the C
        // ABI reads roughly two gigabytes past a four-byte array.
        assertRejected(offset = 2, length = Int.MAX_VALUE - 1)
        assertRejected(offset = 1, length = Int.MAX_VALUE)
        assertRejected(offset = 3, length = Int.MAX_VALUE - 2)
    }

    @Test
    fun outOfRangeWindowIsRejected() {
        assertRejected(offset = 0, length = 5)
        assertRejected(offset = 4, length = 1)
        assertRejected(offset = 5, length = 0)
        assertRejected(offset = -1, length = 1)
        assertRejected(offset = 0, length = -1)
        assertRejected(offset = Int.MIN_VALUE, length = 0)
        assertRejected(offset = 0, length = Int.MIN_VALUE)
    }

    @Test
    fun readAtIsBoundedToo() {
        // `scratchReadAt` is the write direction: an unvalidated window lets
        // the native side write past the end of the pinned array.
        val error = assertFailsWith<FileSystemOperationException> {
            NativeIo.scratchReadAt(
                handle = 0L,
                position = 0L,
                output = ByteArray(4),
                offset = 2,
                length = Int.MAX_VALUE - 1,
            )
        }

        assertEquals(FileSystemFailureKind.InvalidInput, error.failure.kind)
    }

    @Test
    fun exactlyFittingWindowIsAccepted() {
        // The guard must not over-reject: these reach the native call and
        // fail on the handle instead, which is a returned code rather than a
        // thrown InvalidInput.
        assertNotRejectedForRange(offset = 0, length = 4)
        assertNotRejectedForRange(offset = 4, length = 0)
        assertNotRejectedForRange(offset = 0, length = 0)
        assertNotRejectedForRange(offset = 3, length = 1)
    }

    private fun assertRejected(offset: Int, length: Int) {
        val error = assertFailsWith<FileSystemOperationException>(
            "offset=$offset length=$length must be rejected",
        ) {
            NativeIo.txnWrite(
                handle = 0L,
                input = ByteArray(4),
                offset = offset,
                length = length,
            )
        }

        assertEquals(
            FileSystemFailureKind.InvalidInput,
            error.failure.kind,
            "offset=$offset length=$length",
        )
    }

    private fun assertNotRejectedForRange(offset: Int, length: Int) {
        val result = NativeIo.txnWrite(
            handle = 0L,
            input = ByteArray(4),
            offset = offset,
            length = length,
        )

        // An unknown handle is a packed failure, not a thrown range error.
        assertEquals(
            true,
            isNativeIoFailure(result),
            "offset=$offset length=$length must reach the native call",
        )
    }
}
