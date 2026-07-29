package com.artemchep.keyguard.util.io.artifact

import com.artemchep.keyguard.util.io.FileSystemFailure
import com.artemchep.keyguard.util.io.FileSystemFailureKind
import com.artemchep.keyguard.util.io.NativeErrorDiagnostic
import com.artemchep.keyguard.util.io.NativeErrorDomain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Golden wire vectors mirrored byte-identically by the Rust
 * `keyguard-io-core/src/abi.rs` test module; changing any value is an ABI
 * break.
 */
private object GoldenVectors {
    val INCOMPLETE_SWEEP_REPORT = longArrayOf(
        96, // structure size
        1, // report version
        2, // incomplete
        1, // permission denied
        1, // POSIX errno
        13, // EACCES
        11, // entries seen
        10, // candidate names
        4, // removed
        1, // young
        1, // busy
        1, // unsafe
        1, // changed
        1, // inspection failed
        1, // removal failed
    )
}

class OrphanSweeperTest {
    @Test
    fun sweepReportVectorDecodesWithoutCounterSaturation() {
        assertEquals(
            SweepReport(
                status = SweepStatus.Incomplete,
                entriesSeen = 11uL,
                candidateNames = 10uL,
                removed = 4uL,
                skippedYoung = 1uL,
                skippedBusy = 1uL,
                skippedUnsafe = 1uL,
                skippedChanged = 1uL,
                inspectionFailed = 1uL,
                removalFailed = 1uL,
                firstFailure = FileSystemFailure(
                    kind = FileSystemFailureKind.PermissionDenied,
                    diagnostic = NativeErrorDiagnostic(
                        domain = NativeErrorDomain.PosixErrno,
                        code = 13u,
                    ),
                ),
            ),
            decodeNativeIoSweepReport(GoldenVectors.INCOMPLETE_SWEEP_REPORT),
        )

        val aboveU16Limit = completeWire(
            entriesSeen = 70_000L,
            candidateNames = 70_000L,
            removed = 70_000L,
        )
        assertEquals(
            70_000uL,
            decodeNativeIoSweepReport(aboveU16Limit).removed,
        )
    }

    @Test
    fun highBitCountersRoundTripAsUnsignedValues() {
        val wire = completeWire(
            entriesSeen = Long.MIN_VALUE,
            candidateNames = Long.MIN_VALUE,
            removed = Long.MIN_VALUE,
        )

        val report = decodeNativeIoSweepReport(wire)

        assertEquals(ULong.MAX_VALUE / 2u + 1u, report.entriesSeen)
        assertEquals(ULong.MAX_VALUE / 2u + 1u, report.candidateNames)
        assertEquals(ULong.MAX_VALUE / 2u + 1u, report.removed)
    }

    @Test
    fun busyIsANormalNoOpAndCandidatePartitionIsValidated() {
        val busy = completeWire(status = 1)
        assertEquals(SweepStatus.Busy, decodeNativeIoSweepReport(busy).status)

        val invalid = completeWire(
            candidateNames = 2,
            removed = 1,
        )
        assertFailsWith<IllegalArgumentException> {
            decodeNativeIoSweepReport(invalid)
        }
    }

    @Test
    fun enumerationFailureCanBeIncompleteBeforeAEntryIsClassified() {
        val wire = completeWire(status = 2).apply {
            this[3] = 1 // permission denied
            this[4] = 1 // POSIX errno
            this[5] = 13 // EACCES
        }

        val report = decodeNativeIoSweepReport(wire)

        assertEquals(SweepStatus.Incomplete, report.status)
        assertEquals(0uL, report.entriesSeen)
        assertEquals(0uL, report.inspectionFailed)
        assertEquals(0uL, report.removalFailed)
        assertEquals(FileSystemFailureKind.PermissionDenied, report.firstFailure?.kind)
    }

    @Test
    fun sweepMaskBitsAreStable() {
        assertEquals(1, TemporaryArtifactRole.New.sweepMaskBit)
        assertEquals(2, TemporaryArtifactRole.Previous.sweepMaskBit)
        assertEquals(4, TemporaryArtifactRole.Scratch.sweepMaskBit)
    }
}

private fun completeWire(
    status: Long = 0,
    entriesSeen: Long = 0,
    candidateNames: Long = 0,
    removed: Long = 0,
): LongArray = longArrayOf(
    96,
    1,
    status,
    0,
    0,
    0,
    entriesSeen,
    candidateNames,
    removed,
    0,
    0,
    0,
    0,
    0,
    0,
)
