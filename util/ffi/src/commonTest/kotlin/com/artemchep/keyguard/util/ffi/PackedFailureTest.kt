package com.artemchep.keyguard.util.ffi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Mirrors the vectors of the `keyguard_ffi::failure` tests; changing one is an ABI break. */
class PackedFailureTest {
    @Test
    fun goldenVectorsDecodeToTheirFields() {
        assertEquals("8000000001030800".toULong(16).toLong(), PackedFailure.BRIDGE_INVALID_ARGUMENT)
        assertEquals(
            PackedFailure(operation = 0, kind = 8, domain = 3, rawCode = 1),
            PackedFailure.decode(PackedFailure.BRIDGE_INVALID_ARGUMENT),
        )
        // Write, PermissionDenied, PosixErrno, EACCES=13.
        assertEquals(
            PackedFailure(operation = 3, kind = 1, domain = 1, rawCode = 13),
            PackedFailure.decode("800000000D010103".toULong(16).toLong()),
        )
        // Operation 14, Unsupported, no domain, no raw code.
        assertEquals(
            PackedFailure(operation = 14, kind = 10, domain = 0, rawCode = 0),
            PackedFailure.decode("8000000000000A0E".toULong(16).toLong()),
        )
        // The largest fields; the raw code keeps its bits.
        assertEquals(
            PackedFailure(operation = 0xff, kind = 13, domain = 3, rawCode = -1),
            PackedFailure.decode("80FFFFFFFF030DFF".toULong(16).toLong()),
        )
    }

    @Test
    fun successesAndReservedBitsAreNotFailures() {
        assertNull(PackedFailure.decode(0L))
        assertNull(PackedFailure.decode(Long.MAX_VALUE))
        // -1 sets every reserved bit.
        assertNull(PackedFailure.decode(-1L))
        assertNull(PackedFailure.decode(PackedFailure.BRIDGE_INVALID_ARGUMENT or (1L shl 56)))
        assertNull(PackedFailure.decode(PackedFailure.BRIDGE_INVALID_ARGUMENT or (1L shl 62)))

        // A bridge that assigns bit 56 passes the other reserved bits.
        val flagged = PackedFailure.BRIDGE_INVALID_ARGUMENT or (1L shl 56)
        val others = PackedFailure.RESERVED_BITS and (1L shl 56).inv()
        assertEquals(PackedFailure.decode(PackedFailure.BRIDGE_INVALID_ARGUMENT), PackedFailure.decode(flagged, others))
        assertNull(PackedFailure.decode(flagged or (1L shl 57), others))
    }
}
