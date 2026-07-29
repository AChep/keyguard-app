//! Stable wire layouts shared by the JNI and C bridges.
//!
//! Scalar native operations return an `i64`. A negative value (other than the
//! reserved `-1` end-of-file marker, which is unrepresentable here because
//! its reserved bits are non-zero) is a packed [`TxnError`]; a non-negative
//! value is a function-specific success payload. `txn_commit` packs its
//! success payload with [`pack_commit_success`]. The orphan sweeper returns a
//! packed scalar root failures and otherwise a size- and version-tagged
//! [`SweepReportWire`].
//!
//! Failure layout (`bit63 = 1`):
//!
//! | bits    | content                                  |
//! |---------|------------------------------------------|
//! | 0..=7   | [`Operation`]                            |
//! | 8..=15  | [`FailureKind`] (never `None`)           |
//! | 16..=23 | [`ErrorDomain`]                          |
//! | 24..=55 | raw native error bit pattern (`u32`)     |
//! | 56      | cleanup incomplete after primary failure |
//! | 57..=62 | reserved, zero                           |
//!
//! Commit-report layout (`bit63 = 0`):
//!
//! | bits    | content                                             |
//! |---------|-----------------------------------------------------|
//! | 0..=3   | commit outcome                                      |
//! | 4..=7   | [`AchievedSyncLevel`], or `0xf` if not established |
//! | 8..=15  | reported [`FailureKind`] (`None` = none)            |
//! | 16..=23 | reported [`ErrorDomain`]                            |
//! | 24..=55 | reported raw native error (`u32`)                   |
//! | 56..=62 | publication [`Operation`] for unknown outcomes      |

use std::mem::size_of;

use crate::{
    error::{FileSystemFailure, Operation, TxnError},
    sweep::{SweepReport, SweepStatus},
    txn::{CleanupState, CommitSuccess, CommitSuccessProjection, PublicationOperation},
};

const FAILURE_MARKER: u64 = 1 << 63;
const KIND_SHIFT: u32 = 8;
const DOMAIN_SHIFT: u32 = 16;
const RAW_CODE_SHIFT: u32 = 24;
const ACHIEVED_SHIFT: u32 = 4;
const PUBLICATION_OPERATION_SHIFT: u32 = 56;
const ACHIEVED_NOT_ESTABLISHED: u64 = 0x0f;
const FAILURE_CLEANUP_INCOMPLETE: u64 = 1 << 56;
/// Width of the commit report's publication-operation field, bits 56..=62.
///
/// The field is seven bits because bit 63 is [`FAILURE_MARKER`]. Masking rather
/// than trusting the discriminant is deliberate: an [`Operation`] above 127
/// would otherwise set the marker and turn a success report into a packed
/// failure — a published write reported as failed. Truncating a diagnostic
/// field is a far smaller lie than inverting the outcome, and the Kotlin
/// decoder already masks the same field with the same width.
///
/// `Operation` grew from fourteen to fifteen variants between ABI v1, so
/// this is a live evolution risk rather than a theoretical one. The assertions
/// below pin today's values as exact so the mask never has to act.
const PUBLICATION_OPERATION_MASK: u64 = 0x7f;
/// Width of the operation field at bits 0..=7 of the failure layout.
const FAILURE_OPERATION_MASK: u64 = 0xff;

const _: () = {
    assert!(
        PUBLICATION_OPERATION_MASK << PUBLICATION_OPERATION_SHIFT
            == !0_u64 >> 1 & !((1 << PUBLICATION_OPERATION_SHIFT) - 1),
        "the publication-operation field must span bits 56..=62 exactly",
    );
    assert!(
        (PUBLICATION_OPERATION_MASK << PUBLICATION_OPERATION_SHIFT) & FAILURE_MARKER == 0,
        "the publication-operation field must not reach the failure marker",
    );
    assert!(
        FAILURE_CLEANUP_INCOMPLETE > (FAILURE_OPERATION_MASK << RAW_CODE_SHIFT),
        "the cleanup bit must sit above the raw-code field",
    );
    // Every field's widest current value must survive its mask untouched, so a
    // mask that starts truncating is a compile error rather than a silent
    // change in reported diagnostics.
    assert!(
        (Operation::HardLink as u64) & !PUBLICATION_OPERATION_MASK == 0,
        "Operation no longer fits the commit report's publication field",
    );
    assert!(
        (Operation::HardLink as u64) & !FAILURE_OPERATION_MASK == 0,
        "Operation no longer fits the failure layout's operation field",
    );
};

/// Version of [`TxnOptionsWire`].
pub const TXN_OPTIONS_WIRE_VERSION: u32 = 1;
/// Transaction options contain no defined flag bits in ABI v1.
pub const TXN_OPTIONS_FLAGS_ALL: u32 = 0;

/// Size- and version-tagged transaction options shared with native callers.
///
/// Every field, including fields irrelevant to the selected publication or
/// parent policy, is validated before filesystem access. Reserved fields and
/// unknown flags must be zero.
#[repr(C)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct TxnOptionsWire {
    /// Byte size of this structure.
    pub size: u32,
    /// [`TXN_OPTIONS_WIRE_VERSION`].
    pub version: u32,
    /// Publication policy wire code.
    pub publication: i32,
    /// Staged-file permission policy wire code.
    pub file_permissions: i32,
    /// Parent creation policy wire code.
    pub parent_creation: i32,
    /// Created-directory permission policy wire code.
    pub directory_permissions: i32,
    /// Existing-parent link policy wire code.
    pub existing_parent_links: i32,
    /// Preferred synchronization-level wire code.
    pub preferred_sync_level: i32,
    /// Minimum synchronization-level wire code.
    pub minimum_sync_level: i32,
    /// Synchronization policy mode wire code.
    pub sync_policy_mode: i32,
    /// Future option bits; no bits are currently defined.
    pub flags: u32,
    /// Reserved for future compatible extensions; must be all zero.
    pub reserved: [u32; 5],
}

impl TxnOptionsWire {
    /// Number of 32-bit fields used by the JNI record representation.
    pub const JNI_FIELD_COUNT: usize = 16;

    /// Returns the options in the JNI record field order.
    #[must_use]
    pub const fn as_jni_fields(self) -> [i32; Self::JNI_FIELD_COUNT] {
        [
            self.size as i32,
            self.version as i32,
            self.publication,
            self.file_permissions,
            self.parent_creation,
            self.directory_permissions,
            self.existing_parent_links,
            self.preferred_sync_level,
            self.minimum_sync_level,
            self.sync_policy_mode,
            self.flags as i32,
            self.reserved[0] as i32,
            self.reserved[1] as i32,
            self.reserved[2] as i32,
            self.reserved[3] as i32,
            self.reserved[4] as i32,
        ]
    }

    /// Constructs the wire record from the JNI field order.
    #[must_use]
    pub const fn from_jni_fields(fields: [i32; Self::JNI_FIELD_COUNT]) -> Self {
        Self {
            size: fields[0] as u32,
            version: fields[1] as u32,
            publication: fields[2],
            file_permissions: fields[3],
            parent_creation: fields[4],
            directory_permissions: fields[5],
            existing_parent_links: fields[6],
            preferred_sync_level: fields[7],
            minimum_sync_level: fields[8],
            sync_policy_mode: fields[9],
            flags: fields[10] as u32,
            reserved: [
                fields[11] as u32,
                fields[12] as u32,
                fields[13] as u32,
                fields[14] as u32,
                fields[15] as u32,
            ],
        }
    }
}

/// Wire value of the `Published` commit outcome.
pub const COMMIT_OUTCOME_PUBLISHED: u8 = 0;
/// Wire value of the `DestinationExists` commit outcome.
pub const COMMIT_OUTCOME_DESTINATION_EXISTS: u8 = 1;
/// Wire value of a published commit whose cleanup is incomplete.
pub const COMMIT_OUTCOME_PUBLISHED_CLEANUP_INCOMPLETE: u8 = 2;
/// Wire value of the `PublishedDurabilityUnknown` commit outcome.
pub const COMMIT_OUTCOME_PUBLISHED_DURABILITY_UNKNOWN: u8 = 3;
/// Wire value of a publication whose synchronization is unknown and whose
/// cleanup/finalization was also incomplete. The packed failure describes the
/// synchronization failure; the outcome code retains cleanup state.
pub const COMMIT_OUTCOME_PUBLISHED_DURABILITY_UNKNOWN_CLEANUP_INCOMPLETE: u8 = 4;
/// Wire value of `DestinationExists` when staged-artifact cleanup was
/// incomplete. The packed secondary failure describes cleanup.
pub const COMMIT_OUTCOME_DESTINATION_EXISTS_CLEANUP_INCOMPLETE: u8 = 5;
/// Wire value of an issued publication whose result could not be established.
pub const COMMIT_OUTCOME_PUBLICATION_UNKNOWN: u8 = 6;
/// Wire value of an issued publication whose result could not be established
/// and whose staged-artifact cleanup is also incomplete.
pub const COMMIT_OUTCOME_PUBLICATION_UNKNOWN_CLEANUP_INCOMPLETE: u8 = 7;

/// Version of [`SweepReportWire`].
pub const SWEEP_REPORT_WIRE_VERSION: u32 = 1;
/// Wire value of a complete sweep.
pub const SWEEP_STATUS_COMPLETE: u32 = 0;
/// Reserved wire value for a platform-wide busy no-op.
pub const SWEEP_STATUS_BUSY: u32 = 1;
/// Wire value of a sweep interrupted by enumeration or candidate failure.
pub const SWEEP_STATUS_INCOMPLETE: u32 = 2;

/// Size- and version-tagged orphan-sweep report shared with native callers.
///
/// The first six fields are 32-bit so the counters begin at an explicitly
/// eight-byte-aligned offset on every supported ABI.
#[repr(C)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct SweepReportWire {
    /// Byte size of this structure.
    pub size: u32,
    /// [`SWEEP_REPORT_WIRE_VERSION`].
    pub version: u32,
    /// One of the `SWEEP_STATUS_*` constants.
    pub status: u32,
    /// Stable [`crate::FailureKind`] code, or zero when absent.
    pub first_failure_kind: u32,
    /// Stable [`crate::ErrorDomain`] code, or zero when absent.
    pub first_failure_domain: u32,
    /// Raw native error code, or zero when absent.
    pub first_failure_raw_code: u32,
    /// Directory entries successfully observed.
    pub entries_seen: u64,
    /// Entries whose canonical name and role mask matched.
    pub candidate_names: u64,
    /// Eligible artifacts successfully removed.
    pub removed: u64,
    /// Candidates skipped because they were younger than the cutoff.
    pub skipped_young: u64,
    /// Candidates skipped because a live producer or platform lock held them.
    pub skipped_busy: u64,
    /// Candidates rejected as unsafe or unsupported filesystem objects.
    pub skipped_unsafe: u64,
    /// Candidates skipped because their identity changed during inspection.
    pub skipped_changed: u64,
    /// Candidate inspection failures.
    pub inspection_failed: u64,
    /// Eligible artifacts that could not be removed.
    pub removal_failed: u64,
}

impl SweepReportWire {
    /// Converts the typed core report into its stable native representation.
    #[must_use]
    pub fn from_report(report: SweepReport) -> Self {
        let status = match report.status {
            SweepStatus::Complete => SWEEP_STATUS_COMPLETE,
            SweepStatus::Busy => SWEEP_STATUS_BUSY,
            SweepStatus::Incomplete => SWEEP_STATUS_INCOMPLETE,
        };
        let (first_failure_kind, first_failure_domain, first_failure_raw_code) =
            failure_wire_parts(report.first_failure);
        Self {
            size: size_of::<Self>() as u32,
            version: SWEEP_REPORT_WIRE_VERSION,
            status,
            first_failure_kind,
            first_failure_domain,
            first_failure_raw_code,
            entries_seen: report.entries_seen,
            candidate_names: report.candidate_names,
            removed: report.removed,
            skipped_young: report.skipped_young,
            skipped_busy: report.skipped_busy,
            skipped_unsafe: report.skipped_unsafe,
            skipped_changed: report.skipped_changed,
            inspection_failed: report.inspection_failed,
            removal_failed: report.removal_failed,
        }
    }

    /// Returns the fields in the order used by the JNI `LongArray` bridge.
    #[must_use]
    pub const fn as_jni_fields(self) -> [i64; 15] {
        [
            self.size as i64,
            self.version as i64,
            self.status as i64,
            self.first_failure_kind as i64,
            self.first_failure_domain as i64,
            self.first_failure_raw_code as i64,
            self.entries_seen as i64,
            self.candidate_names as i64,
            self.removed as i64,
            self.skipped_young as i64,
            self.skipped_busy as i64,
            self.skipped_unsafe as i64,
            self.skipped_changed as i64,
            self.inspection_failed as i64,
            self.removal_failed as i64,
        ]
    }
}

fn failure_wire_parts(failure: Option<FileSystemFailure>) -> (u32, u32, u32) {
    failure.map_or((0, 0, 0), |failure| {
        let (kind, domain, raw_code) = failure.wire_parts();
        (kind as u32, domain as u32, raw_code)
    })
}

/// Packs a protocol failure into the negative scalar representation.
#[must_use]
pub const fn pack_txn_error(error: TxnError) -> i64 {
    let (kind, domain, raw_code) = error.failure().wire_parts();
    (FAILURE_MARKER
        | (error.operation() as u64 & FAILURE_OPERATION_MASK)
        | ((kind as u64) << KIND_SHIFT)
        | ((domain as u64) << DOMAIN_SHIFT)
        | ((raw_code as u64) << RAW_CODE_SHIFT)
        | if error.cleanup_incomplete() {
            FAILURE_CLEANUP_INCOMPLETE
        } else {
            0
        }) as i64
}

/// Packs a commit report into the scalar representation.
///
/// The report's private variant-specific representation makes an invalid
/// publication/tier/operation combination unrepresentable to callers.
#[must_use]
pub const fn pack_commit_success(success: CommitSuccess) -> i64 {
    let (outcome, achieved, reported_failure, publication_operation) = match success.projection() {
        CommitSuccessProjection::Published {
            achieved, cleanup, ..
        } => match cleanup {
            CleanupState::Complete => (COMMIT_OUTCOME_PUBLISHED, achieved as u64, None, None),
            CleanupState::Incomplete(failure) => (
                COMMIT_OUTCOME_PUBLISHED_CLEANUP_INCOMPLETE,
                achieved as u64,
                failure,
                None,
            ),
        },
        CommitSuccessProjection::DestinationExists {
            achieved,
            cleanup_failure,
        } => match cleanup_failure {
            None => (
                COMMIT_OUTCOME_DESTINATION_EXISTS,
                achieved as u64,
                None,
                None,
            ),
            Some(failure) => (
                COMMIT_OUTCOME_DESTINATION_EXISTS_CLEANUP_INCOMPLETE,
                achieved as u64,
                Some(failure),
                None,
            ),
        },
        CommitSuccessProjection::PublishedDurabilityUnknown {
            primary_failure,
            achieved,
            cleanup,
            ..
        } => match cleanup {
            CleanupState::Complete => (
                COMMIT_OUTCOME_PUBLISHED_DURABILITY_UNKNOWN,
                achieved as u64,
                Some(primary_failure),
                None,
            ),
            CleanupState::Incomplete(_) => (
                COMMIT_OUTCOME_PUBLISHED_DURABILITY_UNKNOWN_CLEANUP_INCOMPLETE,
                achieved as u64,
                Some(primary_failure),
                None,
            ),
        },
        CommitSuccessProjection::PublicationUnknown {
            primary_failure,
            publication_operation,
            cleanup,
        } => {
            let operation = match publication_operation {
                PublicationOperation::Rename => Operation::Rename,
                PublicationOperation::HardLink => Operation::HardLink,
            };
            match cleanup {
                CleanupState::Complete => (
                    COMMIT_OUTCOME_PUBLICATION_UNKNOWN,
                    ACHIEVED_NOT_ESTABLISHED,
                    Some(primary_failure),
                    Some(operation),
                ),
                CleanupState::Incomplete(_) => (
                    COMMIT_OUTCOME_PUBLICATION_UNKNOWN_CLEANUP_INCOMPLETE,
                    ACHIEVED_NOT_ESTABLISHED,
                    Some(primary_failure),
                    Some(operation),
                ),
            }
        }
    };
    let (kind, domain, raw_code) = match reported_failure {
        Some(failure) => {
            let (kind, domain, raw_code) = failure.wire_parts();
            (kind as u64, domain as u64, raw_code as u64)
        }
        None => (0, 0, 0),
    };
    let operation = match publication_operation {
        Some(operation) => operation as u64,
        None => 0,
    };
    ((outcome as u64)
        | (achieved << ACHIEVED_SHIFT)
        | (kind << KIND_SHIFT)
        | (domain << DOMAIN_SHIFT)
        | (raw_code << RAW_CODE_SHIFT)
        | ((operation & PUBLICATION_OPERATION_MASK) << PUBLICATION_OPERATION_SHIFT)) as i64
}

/// Packs a bridge-side failure without touching the protocol.
#[must_use]
pub const fn pack_bridge_invalid_argument() -> i64 {
    pack_txn_error(TxnError::bridge_invalid_argument())
}

/// Packs a contained-panic failure for a native ABI boundary.
#[must_use]
pub const fn pack_bridge_panic() -> i64 {
    pack_txn_error(TxnError::bridge_panic())
}

/// Golden wire vectors asserted byte-identically by the Kotlin
/// `NativeIoProtocolTest`; changing any value is an ABI break.
#[cfg(test)]
mod golden {
    /// `TxnError(Bridge, InvalidInput, Bridge domain, code 1)`.
    pub const BRIDGE_INVALID_ARGUMENT: i64 = 0x8000_0000_0103_0800_u64 as i64;
    /// `TxnError(Bridge, Internal, Bridge domain, code 2)`.
    pub const BRIDGE_PANIC: i64 = 0x8000_0000_0203_0C00_u64 as i64;
    /// `CommitSuccess(Published, Durable)`.
    pub const COMMIT_PUBLISHED_DURABLE: i64 = 0x0000_0000_0000_0020;
    /// `CommitSuccess(DestinationExists, Ordered)`.
    pub const COMMIT_DESTINATION_EXISTS_ORDERED: i64 = 0x0000_0000_0000_0011;
    /// `PublicationUnknown(Rename, PermissionDenied)`.
    pub const COMMIT_PUBLICATION_UNKNOWN_RENAME: i64 = 0x0600_0000_0000_01F6;
    /// `PublicationUnknownCleanupIncomplete(HardLink, Other)`.
    pub const COMMIT_PUBLICATION_UNKNOWN_HARD_LINK: i64 = 0x0E00_0000_0000_0BF7;
    /// `TxnError(HardLink, Unsupported)`.
    pub const HARD_LINK_UNSUPPORTED: i64 = 0x8000_0000_0000_0A0E_u64 as i64;
    /// `TxnError(Write, PermissionDenied, PosixErrno, EACCES=13)`.
    #[cfg(unix)]
    pub const WRITE_EACCES: i64 = 0x8000_0000_0D01_0103_u64 as i64;
    /// `TxnError(FlushFile, PermissionDenied, PosixErrno, EACCES=13)`.
    #[cfg(unix)]
    pub const FLUSH_EACCES: i64 = 0x8000_0000_0D01_0104_u64 as i64;
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        durability::AchievedSyncLevel,
        error::{ErrorDomain, FailureKind, FileSystemFailure, Operation},
    };

    #[test]
    fn bridge_failures_match_the_golden_vectors() {
        assert_eq!(
            pack_bridge_invalid_argument(),
            golden::BRIDGE_INVALID_ARGUMENT
        );
        assert_eq!(pack_bridge_panic(), golden::BRIDGE_PANIC);
    }

    #[cfg(unix)]
    #[test]
    fn protocol_failures_match_the_golden_vectors() {
        let write_error = TxnError::new(
            Operation::Write,
            FileSystemFailure::from_io_error(&std::io::Error::from_raw_os_error(libc::EACCES)),
        );
        assert_eq!(pack_txn_error(write_error), golden::WRITE_EACCES);

        let flush_error = TxnError::new(
            Operation::FlushFile,
            FileSystemFailure::from_io_error(&std::io::Error::from_raw_os_error(libc::EACCES)),
        );
        assert_eq!(pack_txn_error(flush_error), golden::FLUSH_EACCES);
    }

    #[test]
    fn sweep_reports_match_the_golden_layout() {
        let report = SweepReport {
            status: SweepStatus::Incomplete,
            entries_seen: 11,
            candidate_names: 10,
            removed: 4,
            skipped_young: 1,
            skipped_busy: 1,
            skipped_unsafe: 1,
            skipped_changed: 1,
            inspection_failed: 1,
            removal_failed: 1,
            first_failure: Some(FileSystemFailure::from_io_error(
                &std::io::Error::from_raw_os_error(raw_permission_denied()),
            )),
        };
        let wire = SweepReportWire::from_report(report);
        assert_eq!(size_of::<SweepReportWire>(), 96);
        assert_eq!(
            [
                std::mem::offset_of!(SweepReportWire, size),
                std::mem::offset_of!(SweepReportWire, version),
                std::mem::offset_of!(SweepReportWire, status),
                std::mem::offset_of!(SweepReportWire, first_failure_kind),
                std::mem::offset_of!(SweepReportWire, first_failure_domain),
                std::mem::offset_of!(SweepReportWire, first_failure_raw_code),
                std::mem::offset_of!(SweepReportWire, entries_seen),
                std::mem::offset_of!(SweepReportWire, candidate_names),
                std::mem::offset_of!(SweepReportWire, removed),
                std::mem::offset_of!(SweepReportWire, skipped_young),
                std::mem::offset_of!(SweepReportWire, skipped_busy),
                std::mem::offset_of!(SweepReportWire, skipped_unsafe),
                std::mem::offset_of!(SweepReportWire, skipped_changed),
                std::mem::offset_of!(SweepReportWire, inspection_failed),
                std::mem::offset_of!(SweepReportWire, removal_failed),
            ],
            [0, 4, 8, 12, 16, 20, 24, 32, 40, 48, 56, 64, 72, 80, 88],
        );
        assert_eq!(wire.size, size_of::<SweepReportWire>() as u32);
        assert_eq!(wire.version, SWEEP_REPORT_WIRE_VERSION);
        assert_eq!(wire.status, SWEEP_STATUS_INCOMPLETE);
        assert_eq!(wire.entries_seen, 11);
        assert_eq!(wire.candidate_names, 10);
        assert_eq!(wire.removed, 4);
        assert_eq!(
            wire.first_failure_kind,
            FailureKind::PermissionDenied as u32
        );
        assert_eq!(wire.first_failure_domain, expected_native_domain() as u32);
        assert_eq!(
            wire.as_jni_fields(),
            [
                size_of::<SweepReportWire>() as i64,
                SWEEP_REPORT_WIRE_VERSION as i64,
                SWEEP_STATUS_INCOMPLETE as i64,
                FailureKind::PermissionDenied as i64,
                expected_native_domain() as i64,
                raw_permission_denied() as i64,
                11,
                10,
                4,
                1,
                1,
                1,
                1,
                1,
                1,
            ],
        );
    }

    #[test]
    fn transaction_options_match_the_v1_layout() {
        assert_eq!(size_of::<TxnOptionsWire>(), 64);
        assert_eq!(
            [
                std::mem::offset_of!(TxnOptionsWire, size),
                std::mem::offset_of!(TxnOptionsWire, version),
                std::mem::offset_of!(TxnOptionsWire, publication),
                std::mem::offset_of!(TxnOptionsWire, file_permissions),
                std::mem::offset_of!(TxnOptionsWire, parent_creation),
                std::mem::offset_of!(TxnOptionsWire, directory_permissions),
                std::mem::offset_of!(TxnOptionsWire, existing_parent_links),
                std::mem::offset_of!(TxnOptionsWire, preferred_sync_level),
                std::mem::offset_of!(TxnOptionsWire, minimum_sync_level),
                std::mem::offset_of!(TxnOptionsWire, sync_policy_mode),
                std::mem::offset_of!(TxnOptionsWire, flags),
                std::mem::offset_of!(TxnOptionsWire, reserved),
            ],
            [0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44],
        );
        let wire = TxnOptionsWire {
            size: size_of::<TxnOptionsWire>() as u32,
            version: TXN_OPTIONS_WIRE_VERSION,
            publication: 2,
            file_permissions: 1,
            parent_creation: 1,
            directory_permissions: 0,
            existing_parent_links: 1,
            preferred_sync_level: 2,
            minimum_sync_level: 1,
            sync_policy_mode: 1,
            flags: 0,
            reserved: [0; 5],
        };
        assert_eq!(
            wire.as_jni_fields(),
            [64, 1, 2, 1, 1, 0, 1, 2, 1, 1, 0, 0, 0, 0, 0, 0],
        );
        assert_eq!(TxnOptionsWire::from_jni_fields(wire.as_jni_fields()), wire);
    }

    #[test]
    fn commit_successes_match_the_golden_vectors() {
        assert_eq!(
            pack_commit_success(CommitSuccess::from_projection(
                CommitSuccessProjection::Published {
                    achieved: AchievedSyncLevel::FileAndNamespaceSynchronized,
                    publication_operation: PublicationOperation::Rename,
                    cleanup: CleanupState::Complete,
                },
            )),
            golden::COMMIT_PUBLISHED_DURABLE
        );
        assert_eq!(
            pack_commit_success(CommitSuccess::from_projection(
                CommitSuccessProjection::DestinationExists {
                    achieved: AchievedSyncLevel::FileSynchronized,
                    cleanup_failure: None,
                },
            )),
            golden::COMMIT_DESTINATION_EXISTS_ORDERED
        );
    }

    #[test]
    fn published_cleanup_may_be_incomplete_without_a_fabricated_failure() {
        let packed = pack_commit_success(CommitSuccess::from_projection(
            CommitSuccessProjection::Published {
                achieved: AchievedSyncLevel::FileSynchronized,
                publication_operation: PublicationOperation::Rename,
                cleanup: CleanupState::Incomplete(None),
            },
        )) as u64;

        assert_eq!(
            (packed & 0x0f) as u8,
            COMMIT_OUTCOME_PUBLISHED_CLEANUP_INCOMPLETE
        );
        assert_eq!(
            ((packed >> 4) & 0x0f) as u8,
            AchievedSyncLevel::FileSynchronized as u8
        );
        assert_eq!((packed >> 8) & 0x0000_ffff_ffff_ffff, 0);
        assert_eq!((packed >> 56) & 0x7f, 0);
    }

    #[test]
    fn failures_are_negative_and_never_collide_with_end_of_file() {
        let packed = pack_bridge_panic();
        assert!(packed < 0);
        assert_ne!(packed, -1);
        // Bits 57..=62 remain zero for every valid failure, while `-1` has
        // them all set; decoders reject `-1` structurally.
        assert_eq!((packed as u64 >> 57) & 0x3f, 0);
    }

    #[test]
    fn failure_layout_places_every_field_at_its_documented_offset() {
        let error = TxnError::new(
            Operation::FlushFile,
            FileSystemFailure::from_io_error(&std::io::Error::from_raw_os_error(
                raw_permission_denied(),
            )),
        );
        let packed = pack_txn_error(error) as u64;

        assert_eq!(packed >> 63, 1);
        assert_eq!(packed as u8, Operation::FlushFile as u8);
        assert_eq!((packed >> 8) as u8, FailureKind::PermissionDenied as u8);
        assert_eq!((packed >> 16) as u8, expected_native_domain() as u8);
        assert_eq!((packed >> 24) as u32, raw_permission_denied() as u32);
        assert_eq!((packed >> 56) & 0x01, 0);
        assert_eq!((packed >> 57) & 0x3f, 0);
    }

    #[test]
    fn failure_layout_retains_primary_failure_and_cleanup_state() {
        let error = TxnError::new(
            Operation::FlushFile,
            FileSystemFailure::semantic(FailureKind::StorageFull),
        )
        .with_cleanup_incomplete();
        let packed = pack_txn_error(error) as u64;

        assert_eq!(packed as u8, Operation::FlushFile as u8);
        assert_eq!((packed >> 8) as u8, FailureKind::StorageFull as u8);
        assert_eq!((packed >> 56) & 0x01, 1);
        assert_eq!((packed >> 57) & 0x3f, 0);
    }

    #[test]
    fn commit_success_layout_preserves_a_full_secondary_raw_code() {
        let failure = FileSystemFailure::from_io_error(&std::io::Error::from_raw_os_error(
            raw_permission_denied(),
        ));
        let packed = pack_commit_success(CommitSuccess::from_projection(
            CommitSuccessProjection::PublishedDurabilityUnknown {
                primary_failure: failure,
                achieved: AchievedSyncLevel::FileSynchronized,
                publication_operation: PublicationOperation::Rename,
                cleanup: CleanupState::Complete,
            },
        )) as u64;

        assert_eq!(packed >> 63, 0);
        assert_eq!(
            (packed & 0x0f) as u8,
            COMMIT_OUTCOME_PUBLISHED_DURABILITY_UNKNOWN
        );
        assert_eq!(
            ((packed >> 4) & 0x0f) as u8,
            AchievedSyncLevel::FileSynchronized as u8
        );
        assert_eq!((packed >> 8) as u8, FailureKind::PermissionDenied as u8);
        assert_eq!((packed >> 16) as u8, expected_native_domain() as u8);
        assert_eq!((packed >> 24) as u32, raw_permission_denied() as u32);
        assert_eq!((packed >> 56) & 0x7f, 0);
    }

    #[test]
    fn combined_sync_and_cleanup_failure_has_a_distinct_outcome() {
        let synchronization_failure =
            FileSystemFailure::semantic(FailureKind::DurabilityUnavailable);
        let cleanup_failure = FileSystemFailure::semantic(FailureKind::PermissionDenied);
        let packed = pack_commit_success(CommitSuccess::from_projection(
            CommitSuccessProjection::PublishedDurabilityUnknown {
                primary_failure: synchronization_failure,
                achieved: AchievedSyncLevel::FileSynchronized,
                publication_operation: PublicationOperation::HardLink,
                cleanup: CleanupState::Incomplete(Some(cleanup_failure)),
            },
        )) as u64;

        assert_eq!(
            (packed & 0x0f) as u8,
            COMMIT_OUTCOME_PUBLISHED_DURABILITY_UNKNOWN_CLEANUP_INCOMPLETE
        );
        assert_eq!(
            (packed >> 8) as u8,
            FailureKind::DurabilityUnavailable as u8,
            "the synchronization failure remains the authoritative payload"
        );
    }

    #[test]
    fn destination_exists_cleanup_failure_has_a_distinct_outcome() {
        let cleanup_failure = FileSystemFailure::semantic(FailureKind::PermissionDenied);
        let packed = pack_commit_success(CommitSuccess::from_projection(
            CommitSuccessProjection::DestinationExists {
                achieved: AchievedSyncLevel::FileSynchronized,
                cleanup_failure: Some(cleanup_failure),
            },
        )) as u64;

        assert_eq!(
            (packed & 0x0f) as u8,
            COMMIT_OUTCOME_DESTINATION_EXISTS_CLEANUP_INCOMPLETE
        );
        assert_eq!((packed >> 8) as u8, FailureKind::PermissionDenied as u8);
    }

    #[test]
    fn publication_unknown_reports_match_the_v1_golden_vectors() {
        let rename = pack_commit_success(CommitSuccess::from_projection(
            CommitSuccessProjection::PublicationUnknown {
                primary_failure: FileSystemFailure::semantic(FailureKind::PermissionDenied),
                publication_operation: PublicationOperation::Rename,
                cleanup: CleanupState::Complete,
            },
        ));
        assert_eq!(rename, golden::COMMIT_PUBLICATION_UNKNOWN_RENAME);

        let hard_link = pack_commit_success(CommitSuccess::from_projection(
            CommitSuccessProjection::PublicationUnknown {
                primary_failure: FileSystemFailure::semantic(FailureKind::Other),
                publication_operation: PublicationOperation::HardLink,
                cleanup: CleanupState::Incomplete(None),
            },
        ));
        assert_eq!(hard_link, golden::COMMIT_PUBLICATION_UNKNOWN_HARD_LINK);
    }

    #[test]
    fn hard_link_failure_matches_the_v1_golden_vector() {
        let packed = pack_txn_error(TxnError::new(
            Operation::HardLink,
            FileSystemFailure::semantic(FailureKind::Unsupported),
        ));
        assert_eq!(packed, golden::HARD_LINK_UNSUPPORTED);
    }

    #[cfg(unix)]
    fn raw_permission_denied() -> i32 {
        libc::EACCES
    }

    #[cfg(windows)]
    fn raw_permission_denied() -> i32 {
        5 // ERROR_ACCESS_DENIED
    }

    #[cfg(unix)]
    fn expected_native_domain() -> ErrorDomain {
        ErrorDomain::PosixErrno
    }

    #[cfg(windows)]
    fn expected_native_domain() -> ErrorDomain {
        ErrorDomain::Win32LastError
    }
}
