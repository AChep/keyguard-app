//! Orphaned temporary-artifact sweeper.
//!
//! Drop-based cleanup cannot survive a killed process or power loss, so the
//! application periodically sweeps storage roots for artifacts matching the
//! canonical naming contract. Platform implementations anchor the trusted
//! root once and perform every entry operation relative to that anchor.

use std::{io, path::Path, time::Duration};

use crate::FileSystemFailure;

#[cfg(unix)]
#[path = "sweep/posix.rs"]
mod platform;
#[cfg(windows)]
#[path = "sweep/windows.rs"]
mod platform;

#[cfg(not(any(unix, windows)))]
mod platform {
    use std::{io, path::Path};

    use super::{SweepOptions, SweepReport};

    pub(super) fn sweep_orphans(
        _directory: &Path,
        _options: SweepOptions,
    ) -> io::Result<SweepReport> {
        Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "orphan sweeping is unsupported on this target",
        ))
    }
}

/// Overall outcome of an orphan sweep.
#[repr(u8)]
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum SweepStatus {
    /// The directory was fully enumerated and every candidate was classified.
    #[default]
    Complete = 0,
    /// The platform could not begin without waiting on a platform-wide busy
    /// condition; no entries were processed.
    Busy = 1,
    /// Enumeration or at least one candidate operation failed.
    Incomplete = 2,
}

/// Sweep configuration.
#[derive(Clone, Copy, Debug)]
pub struct SweepOptions {
    /// Minimum artifact age; younger artifacts may belong to a live
    /// transaction and are never touched.
    pub older_than: Duration,
    /// Bitmask of [`crate::naming::TemporaryFileRole::mask_bit`] values to sweep.
    pub role_mask: u32,
}

/// Counts and diagnostics from an orphan sweep.
///
/// Every role-selected canonical candidate contributes to exactly one of
/// `removed`, the `skipped_*` fields, or the `*_failed` fields.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct SweepReport {
    /// Whether the sweep completed, was globally busy, or was incomplete.
    pub status: SweepStatus,
    /// Non-dot directory entries returned by the directory iterator.
    pub entries_seen: u64,
    /// Canonical temporary names selected by the requested role mask.
    pub candidate_names: u64,
    /// Candidates successfully unlinked.
    pub removed: u64,
    /// Candidates whose anchored metadata was younger than the threshold.
    pub skipped_young: u64,
    /// Candidates protected by a live producer or platform lock.
    pub skipped_busy: u64,
    /// Candidates that were not safe regular-file artifacts.
    pub skipped_unsafe: u64,
    /// Candidates whose name-to-object binding changed during inspection.
    pub skipped_changed: u64,
    /// Candidates that could not be safely inspected.
    pub inspection_failed: u64,
    /// Fully inspected candidates whose unlink operation failed.
    pub removal_failed: u64,
    /// First contained enumeration or candidate failure, without a path.
    pub first_failure: Option<FileSystemFailure>,
}

enum CandidateOutcome {
    Removed,
    Young,
    Busy,
    Unsafe,
    Changed,
    InspectionFailed(io::Error),
    RemovalFailed(io::Error),
}

impl SweepReport {
    fn record_outcome(&mut self, outcome: CandidateOutcome) {
        match outcome {
            CandidateOutcome::Removed => self.removed = self.removed.saturating_add(1),
            CandidateOutcome::Young => self.skipped_young = self.skipped_young.saturating_add(1),
            CandidateOutcome::Busy => self.skipped_busy = self.skipped_busy.saturating_add(1),
            CandidateOutcome::Unsafe => self.skipped_unsafe = self.skipped_unsafe.saturating_add(1),
            CandidateOutcome::Changed => {
                self.skipped_changed = self.skipped_changed.saturating_add(1);
            }
            CandidateOutcome::InspectionFailed(error) => {
                self.inspection_failed = self.inspection_failed.saturating_add(1);
                self.record_failure(&error);
            }
            CandidateOutcome::RemovalFailed(error) => {
                self.removal_failed = self.removal_failed.saturating_add(1);
                self.record_failure(&error);
            }
        }
    }

    pub(crate) fn record_failure(&mut self, error: &io::Error) {
        self.status = SweepStatus::Incomplete;
        if self.first_failure.is_none() {
            self.first_failure = Some(FileSystemFailure::from_io_error(error));
        }
    }

    /// Returns whether every selected candidate has exactly one terminal
    /// classification.
    #[must_use]
    pub fn candidate_partition_holds(&self) -> bool {
        [
            self.removed,
            self.skipped_young,
            self.skipped_busy,
            self.skipped_unsafe,
            self.skipped_changed,
            self.inspection_failed,
            self.removal_failed,
        ]
        .into_iter()
        .try_fold(0_u64, u64::checked_add)
            == Some(self.candidate_names)
    }
}

/// Sweeps `directory` (non-recursively) for orphaned temporary artifacts.
///
/// The directory is trusted as the caller-selected root. Once opened, all
/// enumeration, inspection, and removal remains relative to its retained
/// native handle. A missing directory yields a complete empty report. A zero
/// role mask is an unconditional complete no-op and does not access or
/// validate `directory`; native availability probes rely on this behavior.
///
/// # Errors
///
/// Returns an OS error when the directory cannot be opened or enumeration
/// cannot be initialized. Errors after enumeration starts are contained in an
/// incomplete [`SweepReport`].
pub fn sweep_orphans(directory: &Path, options: SweepOptions) -> io::Result<SweepReport> {
    if options.role_mask == 0 {
        return Ok(SweepReport::default());
    }
    platform::sweep_orphans(directory, options)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn status_wire_values_are_stable() {
        assert_eq!(SweepStatus::Complete as u8, 0);
        assert_eq!(SweepStatus::Busy as u8, 1);
        assert_eq!(SweepStatus::Incomplete as u8, 2);
    }

    #[test]
    fn candidate_partition_includes_every_terminal_counter() {
        let report = SweepReport {
            candidate_names: 7,
            removed: 1,
            skipped_young: 1,
            skipped_busy: 1,
            skipped_unsafe: 1,
            skipped_changed: 1,
            inspection_failed: 1,
            removal_failed: 1,
            ..SweepReport::default()
        };
        assert!(report.candidate_partition_holds());

        let invalid = SweepReport {
            candidate_names: 8,
            ..report
        };
        assert!(!invalid.candidate_partition_holds());

        let overflow = SweepReport {
            candidate_names: u64::MAX,
            removed: u64::MAX,
            skipped_young: 1,
            ..SweepReport::default()
        };
        assert!(!overflow.candidate_partition_holds());
    }

    #[test]
    fn outcomes_partition_candidates_and_preserve_the_first_failure() {
        let inspection_error = io::Error::from(io::ErrorKind::PermissionDenied);
        let expected_failure = FileSystemFailure::from_io_error(&inspection_error);
        let mut report = SweepReport::default();
        for outcome in [
            CandidateOutcome::Removed,
            CandidateOutcome::Young,
            CandidateOutcome::Busy,
            CandidateOutcome::Unsafe,
            CandidateOutcome::Changed,
            CandidateOutcome::InspectionFailed(inspection_error),
            CandidateOutcome::RemovalFailed(io::Error::other("removal failed")),
        ] {
            report.candidate_names += 1;
            report.record_outcome(outcome);
            assert!(report.candidate_partition_holds());
        }
        assert_eq!(
            report,
            SweepReport {
                status: SweepStatus::Incomplete,
                candidate_names: 7,
                removed: 1,
                skipped_young: 1,
                skipped_busy: 1,
                skipped_unsafe: 1,
                skipped_changed: 1,
                inspection_failed: 1,
                removal_failed: 1,
                first_failure: Some(expected_failure),
                ..SweepReport::default()
            }
        );
    }

    #[test]
    fn outcome_counters_saturate_without_losing_failures() {
        let mut report = SweepReport {
            removed: u64::MAX,
            removal_failed: u64::MAX,
            ..SweepReport::default()
        };
        let error = io::Error::other("removal failed");
        let expected_failure = FileSystemFailure::from_io_error(&error);
        report.record_outcome(CandidateOutcome::Removed);
        report.record_outcome(CandidateOutcome::RemovalFailed(error));
        assert_eq!(report.removed, u64::MAX);
        assert_eq!(report.removal_failed, u64::MAX);
        assert_eq!(report.status, SweepStatus::Incomplete);
        assert_eq!(report.first_failure, Some(expected_failure));
    }

    #[test]
    fn zero_role_mask_is_a_no_op_before_path_validation() {
        let report = sweep_orphans(
            Path::new(""),
            SweepOptions {
                older_than: Duration::MAX,
                role_mask: 0,
            },
        )
        .expect("zero role mask must not inspect the invalid path");

        assert_eq!(report, SweepReport::default());
    }
}
