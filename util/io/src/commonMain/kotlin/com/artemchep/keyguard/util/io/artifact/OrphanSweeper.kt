package com.artemchep.keyguard.util.io.artifact

import com.artemchep.keyguard.util.io.FileSystemFailure
import com.artemchep.keyguard.util.io.LocalPath
import com.artemchep.keyguard.util.io.bridge.NativeIo
import com.artemchep.keyguard.util.io.bridge.completeNativeIoOperation
import com.artemchep.keyguard.util.io.bridge.decodeNativeErrorDiagnostic
import com.artemchep.keyguard.util.io.bridge.decodeNativeIoFailureKind
import com.artemchep.keyguard.util.io.bridge.invalidNativeIoResult
import com.artemchep.keyguard.util.io.bridge.isNativeIoFailure
import kotlin.time.Duration

private const val SWEEP_REPORT_FIELD_COUNT: Int = 15
private const val SWEEP_REPORT_SIZE_BYTES: Long = 96L
private const val SWEEP_REPORT_VERSION: Long = 1L
private const val SWEEP_STATUS_INDEX: Int = 2
private const val SWEEP_FIRST_FAILURE_KIND_INDEX: Int = 3
private const val SWEEP_FIRST_FAILURE_DOMAIN_INDEX: Int = 4
private const val SWEEP_FIRST_FAILURE_RAW_CODE_INDEX: Int = 5
private const val SWEEP_ENTRIES_SEEN_INDEX: Int = 6
private const val SWEEP_CANDIDATE_NAMES_INDEX: Int = 7
private const val SWEEP_REMOVED_INDEX: Int = 8
private const val SWEEP_SKIPPED_YOUNG_INDEX: Int = 9
private const val SWEEP_SKIPPED_BUSY_INDEX: Int = 10
private const val SWEEP_SKIPPED_UNSAFE_INDEX: Int = 11
private const val SWEEP_SKIPPED_CHANGED_INDEX: Int = 12
private const val SWEEP_INSPECTION_FAILED_INDEX: Int = 13
private const val SWEEP_REMOVAL_FAILED_INDEX: Int = 14

/**
 * Completion state of an orphan sweep.
 */
enum class SweepStatus {
    /** The complete eligible set was inspected. */
    Complete,

    /** Reserved platform-wide busy state; no work was attempted. */
    Busy,

    /** The returned counts are partial because enumeration or a candidate failed. */
    Incomplete,
}

/**
 * Auditable counts and first failure from an orphan sweep.
 *
 * [candidateNames] is partitioned by [removed], all `skipped*` fields,
 * [inspectionFailed], and [removalFailed]. Counters retain their full unsigned
 * 64-bit native representation.
 */
data class SweepReport(
    val status: SweepStatus,
    val entriesSeen: ULong,
    val candidateNames: ULong,
    val removed: ULong,
    val skippedYoung: ULong,
    val skippedBusy: ULong,
    val skippedUnsafe: ULong,
    val skippedChanged: ULong,
    val inspectionFailed: ULong,
    val removalFailed: ULong,
    val firstFailure: FileSystemFailure?,
)

val TemporaryArtifactRole.sweepMaskBit: Int
    get() = when (this) {
        TemporaryArtifactRole.New -> 1
        TemporaryArtifactRole.Previous -> 1 shl 1
        TemporaryArtifactRole.Scratch -> 1 shl 2
    }

/**
 * Sweeps [directory] (non-recursively) for orphaned Keyguard temporary
 * artifacts.
 *
 * Crash- and kill-based cleanup cannot be guaranteed by any transaction, so
 * applications should sweep trusted, application-owned storage roots at
 * startup or on idle. The canonical name is a reserved namespace, not
 * authentication: another writer with directory access can fabricate one.
 * Handle-relative native inspection rejects unsafe objects and the age filter
 * protects ordinary live transactions.
 * [directory] must be an absolute path to a real directory rather than a
 * symbolic link or reparse point.
 *
 * Mid-enumeration and candidate failures are returned as an [SweepStatus.Incomplete]
 * report with the first filesystem failure.
 *
 * @throws FileSystemOperationException when the root cannot be opened for
 * enumeration; a missing directory yields an empty report.
 */
fun sweepTemporaryArtifacts(
    directory: LocalPath,
    olderThan: Duration,
    roles: Set<TemporaryArtifactRole> = TemporaryArtifactRole.entries.toSet(),
): SweepReport {
    val roleMask = roles.fold(0) { mask, role -> mask or role.sweepMaskBit }
    val wire = NativeIo.sweepOrphans(
        directory = directory.value,
        olderThanMs = olderThan.inWholeMilliseconds.coerceAtLeast(0L),
        roleMask = roleMask,
    )
    return try {
        decodeNativeIoSweepReport(wire)
    } catch (error: IllegalArgumentException) {
        throw invalidNativeIoResult(
            subject = "sweepOrphans",
            cause = error,
        )
    }
}

/**
 * Decodes the version-1 `sweepOrphans` report carried by the native ABI.
 *
 * The report has its own size/version prefix and remains stable independently
 * of the transaction ABI generation.
 */
internal fun decodeNativeIoSweepReport(wire: LongArray): SweepReport {
    if (wire.size == 1) {
        throwNativeIoSweepFailure(wire.single())
    }
    wire.requireValidSweepReportHeader()
    val status = wire.decodeSweepStatus()
    val firstFailure = wire.decodeFirstSweepFailure()
    return wire.toSweepReport(
        status = status,
        firstFailure = firstFailure,
    ).also(SweepReport::requireValid)
}

private fun throwNativeIoSweepFailure(packedFailure: Long): Nothing {
    require(isNativeIoFailure(packedFailure)) {
        "Native IO returned a scalar sweep result without a failure"
    }
    completeNativeIoOperation(
        packedResult = packedFailure,
        subject = "sweepOrphans",
    )
    error("unreachable")
}

private fun LongArray.requireValidSweepReportHeader() {
    require(size == SWEEP_REPORT_FIELD_COUNT) {
        "Native IO returned an unexpected sweep report field count"
    }
    require(this[0] == SWEEP_REPORT_SIZE_BYTES) {
        "Native IO returned an unexpected sweep report size"
    }
    require(this[1] == SWEEP_REPORT_VERSION) {
        "Native IO returned an unsupported sweep report version"
    }
}

private fun LongArray.decodeSweepStatus(): SweepStatus =
    when (u32At(SWEEP_STATUS_INDEX)) {
        0u -> SweepStatus.Complete
        1u -> SweepStatus.Busy
        2u -> SweepStatus.Incomplete
        else -> throw IllegalArgumentException("Native IO returned an unknown sweep status")
    }

private fun LongArray.decodeFirstSweepFailure(): FileSystemFailure? {
    val kind = decodeNativeIoFailureKind(
        u32At(SWEEP_FIRST_FAILURE_KIND_INDEX).toInt(),
    )
    val diagnostic = decodeNativeErrorDiagnostic(
        domainCode = u32At(SWEEP_FIRST_FAILURE_DOMAIN_INDEX).toInt(),
        nativeErrorCode = u32At(SWEEP_FIRST_FAILURE_RAW_CODE_INDEX),
    )
    val firstFailure = kind?.let { decodedKind ->
        FileSystemFailure(
            kind = decodedKind,
            diagnostic = diagnostic,
        )
    }
    if (firstFailure == null) {
        require(diagnostic == null) {
            "Native IO returned sweep diagnostics without a failure kind"
        }
    }
    return firstFailure
}

private fun LongArray.toSweepReport(
    status: SweepStatus,
    firstFailure: FileSystemFailure?,
): SweepReport = SweepReport(
    status = status,
    entriesSeen = this[SWEEP_ENTRIES_SEEN_INDEX].toULong(),
    candidateNames = this[SWEEP_CANDIDATE_NAMES_INDEX].toULong(),
    removed = this[SWEEP_REMOVED_INDEX].toULong(),
    skippedYoung = this[SWEEP_SKIPPED_YOUNG_INDEX].toULong(),
    skippedBusy = this[SWEEP_SKIPPED_BUSY_INDEX].toULong(),
    skippedUnsafe = this[SWEEP_SKIPPED_UNSAFE_INDEX].toULong(),
    skippedChanged = this[SWEEP_SKIPPED_CHANGED_INDEX].toULong(),
    inspectionFailed = this[SWEEP_INSPECTION_FAILED_INDEX].toULong(),
    removalFailed = this[SWEEP_REMOVAL_FAILED_INDEX].toULong(),
    firstFailure = firstFailure,
)

private fun SweepReport.requireValid() {
    require(entriesSeen >= candidateNames) {
        "Native IO returned more candidate names than observed entries"
    }
    require(candidatePartitionHolds()) {
        "Native IO returned an invalid sweep candidate partition"
    }
    when (status) {
        SweepStatus.Complete -> require(
            inspectionFailed == 0uL &&
                removalFailed == 0uL &&
                firstFailure == null,
        ) {
            "Native IO returned failures for a complete sweep"
        }

        SweepStatus.Busy -> require(
            entriesSeen == 0uL &&
                candidateNames == 0uL &&
                firstFailure == null,
        ) {
            "Native IO returned work or a failure for a busy sweep"
        }

        SweepStatus.Incomplete -> require(
            firstFailure != null,
        ) {
            "Native IO returned an incomplete sweep without a failure"
        }
    }
}

private fun SweepReport.candidatePartitionHolds(): Boolean {
    var classified = 0uL
    val counts = arrayOf(
        removed,
        skippedYoung,
        skippedBusy,
        skippedUnsafe,
        skippedChanged,
        inspectionFailed,
        removalFailed,
    )
    for (count in counts) {
        if (ULong.MAX_VALUE - classified < count) {
            return false
        }
        classified += count
    }
    return candidateNames == classified
}

private fun LongArray.u32At(index: Int): UInt {
    val value = this[index]
    require(value >= 0L && value ushr UInt.SIZE_BITS == 0L) {
        "Native IO returned an out-of-range 32-bit sweep field"
    }
    return value.toUInt()
}
