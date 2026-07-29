//! Handle-relative POSIX orphan sweeping.

use std::{
    ffi::{CStr, CString},
    fs::File,
    io,
    mem::MaybeUninit,
    os::{
        fd::{AsRawFd, FromRawFd, OwnedFd, RawFd},
        unix::{ffi::OsStrExt, fs::MetadataExt},
    },
    path::Path,
    ptr::NonNull,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use crate::{
    fsops::lock_capability_absent,
    naming::{
        TemporaryArtifactEntryKind, TemporaryArtifactProtocol, TemporaryFileRole,
        parse_temporary_artifact_name, temporary_artifact_names_from_nonce,
    },
    sweep::{SweepOptions, SweepReport, SweepStatus},
};

pub(super) fn sweep_orphans(directory: &Path, options: SweepOptions) -> io::Result<SweepReport> {
    sweep_orphans_with_lease_probes(
        directory,
        options,
        acquire_directory_lease,
        try_lock_exclusive,
    )
}

fn sweep_orphans_with_lease_probes<D, F>(
    directory: &Path,
    options: SweepOptions,
    directory_lease_probe: D,
    file_lease_probe: F,
) -> io::Result<SweepReport>
where
    D: FnOnce(&OwnedFd) -> io::Result<DirectoryLease>,
    F: Fn(RawFd) -> io::Result<LeaseAttempt>,
{
    validate_sweep_root(directory)?;
    let directory = match open_directory(directory) {
        Ok(directory) => directory,
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            return Ok(SweepReport::default());
        }
        Err(error) => return Err(error),
    };

    // The v1d protocol requires the exclusive directory lease during both
    // enumeration and deletion. Probe before touching the directory stream.
    // A duplicate fd used by fdopendir shares this lease with `directory`.
    let directory_lease = directory_lease_probe(&directory)?;
    if directory_lease == DirectoryLease::Busy {
        return Ok(SweepReport {
            status: SweepStatus::Busy,
            ..SweepReport::default()
        });
    }

    let mut report = SweepReport::default();
    let candidates = enumerate_candidates(&directory, options.role_mask, &mut report)?;
    process_inventory(
        directory.as_raw_fd(),
        candidates,
        directory_lease,
        SystemTime::now(),
        options,
        &file_lease_probe,
        &mut report,
    );

    debug_assert!(report.candidate_partition_holds());
    Ok(report)
}

fn process_inventory<F>(
    directory_fd: RawFd,
    mut candidates: Vec<Candidate>,
    directory_lease: DirectoryLease,
    now: SystemTime,
    options: SweepOptions,
    file_lease_probe: &F,
    report: &mut SweepReport,
) where
    F: Fn(RawFd) -> io::Result<LeaseAttempt>,
{
    // Never mutate the directory through the same stream that is still being
    // enumerated. A stable inventory also makes every observed candidate get
    // one terminal classification even if deletion changes directory offsets.
    candidates.sort_unstable_by(|left, right| {
        (left.role as i32, left.nonce.as_str(), left.name.to_bytes()).cmp(&(
            right.role as i32,
            right.nonce.as_str(),
            right.name.to_bytes(),
        ))
    });

    let mut first = 0;
    while first < candidates.len() {
        let role = candidates[first].role;
        let nonce = candidates[first].nonce.as_str();
        let mut end = first + 1;
        while end < candidates.len()
            && candidates[end].role == role
            && candidates[end].nonce == nonce
        {
            end += 1;
        }
        process_candidate_group(
            directory_fd,
            &candidates[first..end],
            directory_lease,
            now,
            options,
            file_lease_probe,
            report,
        );
        first = end;
    }
}

fn validate_sweep_root(directory: &Path) -> io::Result<()> {
    if directory.is_absolute()
        && !directory
            .components()
            .any(|component| matches!(component, std::path::Component::ParentDir))
    {
        Ok(())
    } else {
        Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "orphan sweep root must be an absolute path without parent components",
        ))
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum DirectoryLease {
    Acquired,
    Busy,
    Unsupported,
}

fn acquire_directory_lease(directory: &OwnedFd) -> io::Result<DirectoryLease> {
    let before = stat_fd(directory.as_raw_fd())?;
    if !is_directory(&before) {
        return Err(io::Error::other(
            "orphan sweep root descriptor is not a directory",
        ));
    }
    let outcome = try_lock_directory_exclusive(directory.as_raw_fd())?;
    if outcome != LeaseAttempt::Acquired {
        return Ok(match outcome {
            LeaseAttempt::Acquired => unreachable!("handled above"),
            LeaseAttempt::Busy => DirectoryLease::Busy,
            LeaseAttempt::Unsupported => DirectoryLease::Unsupported,
        });
    }
    let after = stat_fd(directory.as_raw_fd())?;
    if !same_directory(&before, &after) {
        return Err(io::Error::other(
            "orphan sweep root identity changed while acquiring its lease",
        ));
    }
    Ok(DirectoryLease::Acquired)
}

/// Probes the exclusive directory lease.
///
/// `EBADF` is deliberately not folded into an absent-capability answer here.
/// The claim it previously carried — that NFS reports it because a directory
/// descriptor cannot be opened for writing — does not hold: `nfs_dir_operations`
/// declares no `.flock`, so `SYSCALL_DEFINE2(flock, ...)` falls through to
/// `locks_lock_file_wait` and the lock is taken host-locally. `flock(2)` defines
/// `EBADF` as "fd is not an open file descriptor", which is a defect in this
/// crate — and a fresh `fstat` of this descriptor immediately precedes the
/// attempt — so it must surface rather than silently skip the lease.
fn try_lock_directory_exclusive(fd: RawFd) -> io::Result<LeaseAttempt> {
    try_lock_exclusive(fd)
}

#[derive(Debug)]
struct Candidate {
    name: CString,
    protocol: TemporaryArtifactProtocol,
    role: TemporaryFileRole,
    nonce: String,
    entry_kind: TemporaryArtifactEntryKind,
}

fn enumerate_candidates(
    directory: &OwnedFd,
    role_mask: u32,
    report: &mut SweepReport,
) -> io::Result<Vec<Candidate>> {
    let mut entries = DirectoryEntries::new(directory)?;
    let mut candidates = Vec::new();
    loop {
        let name = match entries.next_name() {
            Ok(Some(name)) => name,
            Ok(None) => break,
            Err(error) => {
                report.record_failure(&error);
                break;
            }
        };
        if name.to_bytes() == b"." || name.to_bytes() == b".." {
            continue;
        }
        report.entries_seen = report.entries_seen.saturating_add(1);
        let Ok(name_utf8) = name.to_str() else {
            continue;
        };
        let Some(artifact) = parse_temporary_artifact_name(name_utf8) else {
            continue;
        };
        if artifact.role.mask_bit() & role_mask == 0 {
            continue;
        }
        let protocol = artifact.protocol;
        let role = artifact.role;
        let nonce = artifact.nonce.to_owned();
        let entry_kind = artifact.entry_kind;
        report.candidate_names = report.candidate_names.saturating_add(1);
        candidates.push(Candidate {
            name,
            protocol,
            role,
            nonce,
            entry_kind,
        });
    }
    Ok(candidates)
}

fn process_candidate_group<F>(
    directory_fd: RawFd,
    candidates: &[Candidate],
    directory_lease: DirectoryLease,
    now: SystemTime,
    options: SweepOptions,
    file_lease_probe: &F,
    report: &mut SweepReport,
) where
    F: Fn(RawFd) -> io::Result<LeaseAttempt>,
{
    let protocol = candidates[0].protocol;
    if candidates
        .iter()
        .any(|candidate| candidate.protocol != protocol)
    {
        // Reusing a nonce across protocol versions is not a valid producer
        // state. Treat the entire identity as attacker-controlled.
        classify_many(report, candidates.len(), OutcomeClass::Unsafe);
        return;
    }

    match protocol {
        TemporaryArtifactProtocol::FileLeaseV1 => {
            if candidates.len() != 1 || candidates[0].entry_kind != TemporaryArtifactEntryKind::Data
            {
                classify_many(report, candidates.len(), OutcomeClass::Unsafe);
                return;
            }
            let outcome = process_file_lease(
                directory_fd,
                &candidates[0],
                now,
                options.older_than,
                file_lease_probe,
            );
            apply_outcome(report, outcome);
        }
        TemporaryArtifactProtocol::DirectoryLeaseV1 => {
            if candidates.len() != 1 || candidates[0].entry_kind != TemporaryArtifactEntryKind::Data
            {
                classify_many(report, candidates.len(), OutcomeClass::Unsafe);
                return;
            }
            match directory_lease {
                DirectoryLease::Acquired => apply_outcome(
                    report,
                    process_directory_lease_artifact(
                        directory_fd,
                        &candidates[0],
                        now,
                        options.older_than,
                    ),
                ),
                DirectoryLease::Unsupported => {
                    classify_many(report, 1, OutcomeClass::InspectionFailed);
                    report.record_failure(&io::Error::new(
                        io::ErrorKind::Unsupported,
                        "directory leases are unsupported",
                    ));
                }
                DirectoryLease::Busy => {
                    unreachable!("busy directory leases return before enumeration")
                }
            }
        }
        TemporaryArtifactProtocol::SidecarLeaseV1 => {
            let data = candidates
                .iter()
                .filter(|candidate| candidate.entry_kind == TemporaryArtifactEntryKind::Data)
                .collect::<Vec<_>>();
            let leases = candidates
                .iter()
                .filter(|candidate| candidate.entry_kind == TemporaryArtifactEntryKind::Lease)
                .collect::<Vec<_>>();
            match (data.as_slice(), leases.as_slice(), candidates.len()) {
                ([data], [sidecar], 2) => process_sidecar_pair(
                    directory_fd,
                    data,
                    sidecar,
                    now,
                    options.older_than,
                    file_lease_probe,
                    report,
                ),
                ([], [sidecar], 1) => apply_outcome(
                    report,
                    process_sidecar_only(
                        directory_fd,
                        sidecar,
                        now,
                        options.older_than,
                        file_lease_probe,
                    ),
                ),
                ([_], [], 1) => {
                    classify_many(report, 1, OutcomeClass::InspectionFailed);
                    report.record_failure(&io::Error::other(
                        "sidecar-lease data is missing its lease entry",
                    ));
                }
                _ => classify_many(report, candidates.len(), OutcomeClass::Unsafe),
            }
        }
    }
}

fn process_file_lease<F>(
    directory_fd: RawFd,
    candidate: &Candidate,
    now: SystemTime,
    older_than: Duration,
    file_lease_probe: &F,
) -> CandidateOutcome
where
    F: Fn(RawFd) -> io::Result<LeaseAttempt>,
{
    let opened = match inspect_open(
        directory_fd,
        &candidate.name,
        Security::Regular,
        OpenAccess::ReadWrite,
    ) {
        Ok(opened) => opened,
        Err(outcome) => return outcome,
    };
    match file_lease_probe(opened.file.as_raw_fd()) {
        Ok(LeaseAttempt::Acquired) => {}
        Ok(LeaseAttempt::Busy) => return CandidateOutcome::Busy,
        Ok(LeaseAttempt::Unsupported) => {
            return CandidateOutcome::InspectionFailed(io::Error::new(
                io::ErrorKind::Unsupported,
                "regular-file leases are unsupported",
            ));
        }
        Err(error) => return CandidateOutcome::InspectionFailed(error),
    }
    if !old_enough(now, opened.latest_activity, older_than) {
        return CandidateOutcome::Young;
    }
    unlink_exact(directory_fd, &candidate.name, &opened, Security::Regular)
}

fn process_directory_lease_artifact(
    directory_fd: RawFd,
    candidate: &Candidate,
    now: SystemTime,
    older_than: Duration,
) -> CandidateOutcome {
    let opened = match inspect_open(
        directory_fd,
        &candidate.name,
        Security::Regular,
        OpenAccess::ReadOnly,
    ) {
        Ok(opened) => opened,
        Err(outcome) => return outcome,
    };
    if !old_enough(now, opened.latest_activity, older_than) {
        return CandidateOutcome::Young;
    }
    unlink_exact(directory_fd, &candidate.name, &opened, Security::Regular)
}

fn process_sidecar_pair<F>(
    directory_fd: RawFd,
    data: &Candidate,
    sidecar: &Candidate,
    now: SystemTime,
    older_than: Duration,
    file_lease_probe: &F,
    report: &mut SweepReport,
) where
    F: Fn(RawFd) -> io::Result<LeaseAttempt>,
{
    let sidecar_opened = match inspect_open(
        directory_fd,
        &sidecar.name,
        Security::OwnerOnlySidecar,
        OpenAccess::ReadWrite,
    ) {
        Ok(opened) => opened,
        Err(outcome) => {
            apply_outcome(report, outcome);
            classify_many(report, 1, OutcomeClass::Unsafe);
            return;
        }
    };
    match file_lease_probe(sidecar_opened.file.as_raw_fd()) {
        Ok(LeaseAttempt::Acquired) => {}
        Ok(LeaseAttempt::Busy) => {
            classify_many(report, 2, OutcomeClass::Busy);
            return;
        }
        Ok(LeaseAttempt::Unsupported) => {
            classify_many(report, 2, OutcomeClass::InspectionFailed);
            report.record_failure(&io::Error::new(
                io::ErrorKind::Unsupported,
                "regular-file sidecar leases are unsupported",
            ));
            return;
        }
        Err(error) => {
            classify_many(report, 2, OutcomeClass::InspectionFailed);
            report.record_failure(&error);
            return;
        }
    }
    if let Err(outcome) = revalidate_exact(
        directory_fd,
        &sidecar.name,
        &sidecar_opened,
        Security::OwnerOnlySidecar,
    ) {
        apply_outcome(report, outcome);
        classify_many(report, 1, OutcomeClass::Unsafe);
        return;
    }

    let data_opened = match inspect_open(
        directory_fd,
        &data.name,
        Security::Regular,
        OpenAccess::ReadOnly,
    ) {
        Ok(opened) => opened,
        Err(outcome) => {
            apply_outcome(report, outcome);
            classify_many(report, 1, OutcomeClass::Unsafe);
            return;
        }
    };
    let newest_activity = data_opened
        .latest_activity
        .max(sidecar_opened.latest_activity);
    if !old_enough(now, newest_activity, older_than) {
        classify_many(report, 2, OutcomeClass::Young);
        return;
    }

    let (data_outcome, sidecar_outcome) = data_then_sidecar_while_lease_held(
        sidecar_opened,
        |lease| {
            if let Err(outcome) = revalidate_exact(
                directory_fd,
                &sidecar.name,
                lease,
                Security::OwnerOnlySidecar,
            ) {
                return outcome;
            }
            unlink_exact(directory_fd, &data.name, &data_opened, Security::Regular)
        },
        |lease| {
            let absent = ensure_name_absent(directory_fd, &data.name);
            if !matches!(absent, CandidateOutcome::Removed) {
                return absent;
            }
            unlink_exact(
                directory_fd,
                &sidecar.name,
                lease,
                Security::OwnerOnlySidecar,
            )
        },
    );
    let data_removed = matches!(data_outcome, CandidateOutcome::Removed);
    apply_outcome(report, data_outcome);
    match sidecar_outcome {
        Some(outcome) => apply_outcome(report, outcome),
        None if data_removed => unreachable!("successful data removal must run sidecar cleanup"),
        None => classify_many(report, 1, OutcomeClass::Unsafe),
    }
}

fn process_sidecar_only<F>(
    directory_fd: RawFd,
    sidecar: &Candidate,
    now: SystemTime,
    older_than: Duration,
    file_lease_probe: &F,
) -> CandidateOutcome
where
    F: Fn(RawFd) -> io::Result<LeaseAttempt>,
{
    let opened = match inspect_open(
        directory_fd,
        &sidecar.name,
        Security::OwnerOnlySidecar,
        OpenAccess::ReadWrite,
    ) {
        Ok(opened) => opened,
        Err(outcome) => return outcome,
    };
    match file_lease_probe(opened.file.as_raw_fd()) {
        Ok(LeaseAttempt::Acquired) => {}
        Ok(LeaseAttempt::Busy) => return CandidateOutcome::Busy,
        Ok(LeaseAttempt::Unsupported) => {
            return CandidateOutcome::InspectionFailed(io::Error::new(
                io::ErrorKind::Unsupported,
                "regular-file sidecar leases are unsupported",
            ));
        }
        Err(error) => return CandidateOutcome::InspectionFailed(error),
    }
    if let Err(outcome) = revalidate_exact(
        directory_fd,
        &sidecar.name,
        &opened,
        Security::OwnerOnlySidecar,
    ) {
        return outcome;
    }
    if !old_enough(now, opened.latest_activity, older_than) {
        return CandidateOutcome::Young;
    }

    let Some(names) = temporary_artifact_names_from_nonce(
        sidecar.role,
        TemporaryArtifactProtocol::SidecarLeaseV1,
        &sidecar.nonce,
    ) else {
        return CandidateOutcome::InspectionFailed(io::Error::other(
            "parsed sidecar nonce could not be reconstructed",
        ));
    };
    let data_name = match CString::new(names.data) {
        Ok(name) => name,
        Err(_) => {
            return CandidateOutcome::InspectionFailed(io::Error::new(
                io::ErrorKind::InvalidData,
                "reconstructed sidecar data name contains a NUL byte",
            ));
        }
    };
    let absent = ensure_name_absent(directory_fd, &data_name);
    if !matches!(absent, CandidateOutcome::Removed) {
        return absent;
    }
    let outcome = unlink_exact(
        directory_fd,
        &sidecar.name,
        &opened,
        Security::OwnerOnlySidecar,
    );
    drop(opened);
    outcome
}

fn data_then_sidecar_while_lease_held<L, D, S>(
    lease: L,
    remove_data: D,
    remove_sidecar: S,
) -> (CandidateOutcome, Option<CandidateOutcome>)
where
    D: FnOnce(&L) -> CandidateOutcome,
    S: FnOnce(&L) -> CandidateOutcome,
{
    let data = remove_data(&lease);
    let sidecar = matches!(data, CandidateOutcome::Removed).then(|| remove_sidecar(&lease));
    drop(lease);
    (data, sidecar)
}

#[derive(Clone, Copy)]
enum Security {
    Regular,
    OwnerOnlySidecar,
}

#[derive(Clone, Copy)]
enum OpenAccess {
    ReadOnly,
    ReadWrite,
}

struct OpenedCandidate {
    file: File,
    observed: libc::stat,
    latest_activity: SystemTime,
}

fn inspect_open(
    directory_fd: RawFd,
    name: &CStr,
    security: Security,
    access: OpenAccess,
) -> Result<OpenedCandidate, CandidateOutcome> {
    let observed = stat_at(directory_fd, name).map_err(classify_inspection_error)?;
    validate_security(&observed, security)?;
    let file = open_candidate(directory_fd, name, access).map_err(classify_open_error)?;
    let descriptor = stat_fd(file.as_raw_fd()).map_err(CandidateOutcome::InspectionFailed)?;
    if !same_regular_file(&observed, &descriptor) {
        return Err(CandidateOutcome::Changed);
    }
    validate_security(&descriptor, security)?;
    let metadata = file
        .metadata()
        .map_err(CandidateOutcome::InspectionFailed)?;
    let latest_activity = latest_activity(&metadata).map_err(CandidateOutcome::InspectionFailed)?;
    Ok(OpenedCandidate {
        file,
        observed,
        latest_activity,
    })
}

fn revalidate_exact(
    directory_fd: RawFd,
    name: &CStr,
    opened: &OpenedCandidate,
    security: Security,
) -> Result<(), CandidateOutcome> {
    let current = stat_at(directory_fd, name).map_err(classify_inspection_error)?;
    if !same_regular_file(&opened.observed, &current) {
        return Err(CandidateOutcome::Changed);
    }
    validate_security(&current, security)
}

fn unlink_exact(
    directory_fd: RawFd,
    name: &CStr,
    opened: &OpenedCandidate,
    security: Security,
) -> CandidateOutcome {
    if let Err(outcome) = revalidate_exact(directory_fd, name, opened, security) {
        return outcome;
    }
    // SAFETY: `name` is NUL-terminated and `directory_fd` remains valid.
    if unsafe { libc::unlinkat(directory_fd, name.as_ptr(), 0) } == 0 {
        CandidateOutcome::Removed
    } else {
        let error = io::Error::last_os_error();
        if name_changed_error(&error) {
            CandidateOutcome::Changed
        } else {
            CandidateOutcome::RemovalFailed(error)
        }
    }
}

fn ensure_name_absent(directory_fd: RawFd, name: &CStr) -> CandidateOutcome {
    match stat_at(directory_fd, name) {
        Err(error) if error.kind() == io::ErrorKind::NotFound => CandidateOutcome::Removed,
        Err(error) if name_changed_error(&error) => CandidateOutcome::Removed,
        Err(error) => CandidateOutcome::InspectionFailed(error),
        Ok(_) => CandidateOutcome::Changed,
    }
}

fn validate_security(stat: &libc::stat, security: Security) -> Result<(), CandidateOutcome> {
    if !is_regular(stat) {
        return Err(CandidateOutcome::Unsafe);
    }
    if matches!(security, Security::OwnerOnlySidecar) {
        // Sidecars are security-sensitive lock objects. Exact ownership,
        // permissions, and a single link prevent accepting an inherited,
        // substituted, or externally pinned lease inode.
        // SAFETY: `geteuid` has no arguments and no failure mode.
        let effective_uid = unsafe { libc::geteuid() };
        if stat.st_uid != effective_uid
            || u64::from(stat.st_mode) & 0o777 != 0o600
            || stat.st_nlink != 1
        {
            return Err(CandidateOutcome::Unsafe);
        }
    }
    Ok(())
}

fn classify_inspection_error(error: io::Error) -> CandidateOutcome {
    if name_changed_error(&error) {
        CandidateOutcome::Changed
    } else {
        CandidateOutcome::InspectionFailed(error)
    }
}

fn classify_open_error(error: io::Error) -> CandidateOutcome {
    if name_changed_error(&error) || error.raw_os_error() == Some(libc::ELOOP) {
        CandidateOutcome::Changed
    } else if busy_error(&error) {
        CandidateOutcome::Busy
    } else {
        CandidateOutcome::InspectionFailed(error)
    }
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

#[derive(Clone, Copy)]
enum OutcomeClass {
    Busy,
    Unsafe,
    InspectionFailed,
    Young,
}

fn apply_outcome(report: &mut SweepReport, outcome: CandidateOutcome) {
    match outcome {
        CandidateOutcome::Removed => report.removed = report.removed.saturating_add(1),
        CandidateOutcome::Young => report.skipped_young = report.skipped_young.saturating_add(1),
        CandidateOutcome::Busy => report.skipped_busy = report.skipped_busy.saturating_add(1),
        CandidateOutcome::Unsafe => report.skipped_unsafe = report.skipped_unsafe.saturating_add(1),
        CandidateOutcome::Changed => {
            report.skipped_changed = report.skipped_changed.saturating_add(1);
        }
        CandidateOutcome::InspectionFailed(error) => {
            report.inspection_failed = report.inspection_failed.saturating_add(1);
            report.record_failure(&error);
        }
        CandidateOutcome::RemovalFailed(error) => {
            report.removal_failed = report.removal_failed.saturating_add(1);
            report.record_failure(&error);
        }
    }
}

fn classify_many(report: &mut SweepReport, count: usize, class: OutcomeClass) {
    let count = u64::try_from(count).unwrap_or(u64::MAX);
    match class {
        OutcomeClass::Busy => {
            report.skipped_busy = report.skipped_busy.saturating_add(count);
        }
        OutcomeClass::Unsafe => {
            report.skipped_unsafe = report.skipped_unsafe.saturating_add(count);
        }
        OutcomeClass::InspectionFailed => {
            report.inspection_failed = report.inspection_failed.saturating_add(count);
        }
        OutcomeClass::Young => {
            report.skipped_young = report.skipped_young.saturating_add(count);
        }
    }
}

fn old_enough(now: SystemTime, latest_activity: SystemTime, threshold: Duration) -> bool {
    now.duration_since(latest_activity)
        .ok()
        .is_some_and(|age| age >= threshold)
}

fn latest_activity(metadata: &std::fs::Metadata) -> io::Result<SystemTime> {
    let modified = metadata.modified()?;
    let changed = system_time_from_unix_parts(metadata.ctime(), metadata.ctime_nsec())?;
    Ok(modified.max(changed))
}

fn system_time_from_unix_parts(seconds: i64, nanoseconds: i64) -> io::Result<SystemTime> {
    let nanoseconds = u32::try_from(nanoseconds)
        .ok()
        .filter(|value| *value < 1_000_000_000)
        .ok_or_else(|| io::Error::other("invalid filesystem timestamp"))?;
    if seconds >= 0 {
        UNIX_EPOCH
            .checked_add(Duration::new(seconds as u64, nanoseconds))
            .ok_or_else(|| io::Error::other("filesystem timestamp overflow"))
    } else {
        UNIX_EPOCH
            .checked_sub(Duration::from_secs(seconds.unsigned_abs()))
            .and_then(|time| time.checked_add(Duration::from_nanos(u64::from(nanoseconds))))
            .ok_or_else(|| io::Error::other("filesystem timestamp overflow"))
    }
}

fn open_directory(path: &Path) -> io::Result<OwnedFd> {
    let path = CString::new(path.as_os_str().as_bytes())
        .map_err(|_| io::Error::from_raw_os_error(libc::EINVAL))?;
    // SAFETY: `path` is NUL-terminated for the call and no out-pointers exist.
    let fd = unsafe {
        libc::open(
            path.as_ptr(),
            libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC | libc::O_NOFOLLOW,
        )
    };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: `open` returned a new uniquely-owned descriptor.
    Ok(unsafe { OwnedFd::from_raw_fd(fd) })
}

/// Opens a candidate for inspection.
///
/// `ReadWrite` for the lease-bearing artifacts is a requirement, not an
/// oversight, and must not be relaxed to `ReadOnly`. `flock(2)` places a lock
/// "regardless of the mode in which the file was opened" only for *local* locks:
/// since Linux 2.6.37 `flock()` on NFS is emulated with `fcntl()` byte-range
/// locks, and `fcntl(2)` requires that "in order to place a write lock, fd must
/// be open for writing". A read-only descriptor would therefore fail `LOCK_EX`
/// with `EBADF` on a network mount and silently forfeit the writer/sweeper
/// exclusion. The writer opens its own sidecar `O_RDWR` for the same reason.
///
/// O_NONBLOCK prevents a concurrently substituted FIFO or device from blocking
/// before its descriptor metadata can be rejected.
fn open_candidate(directory_fd: RawFd, name: &CStr, access: OpenAccess) -> io::Result<File> {
    let access_flag = match access {
        OpenAccess::ReadOnly => libc::O_RDONLY,
        OpenAccess::ReadWrite => libc::O_RDWR,
    };
    let flags =
        access_flag | libc::O_CLOEXEC | libc::O_NOFOLLOW | libc::O_NONBLOCK | libc::O_NOCTTY;
    // SAFETY: `name` is NUL-terminated and the directory descriptor is valid.
    let fd = unsafe { libc::openat(directory_fd, name.as_ptr(), flags) };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: `openat` returned a new uniquely-owned descriptor.
    Ok(unsafe { File::from_raw_fd(fd) })
}

fn stat_at(directory_fd: RawFd, name: &CStr) -> io::Result<libc::stat> {
    let mut stat = MaybeUninit::<libc::stat>::uninit();
    // SAFETY: `name` is NUL-terminated, the descriptor is valid, and `stat`
    // points to writable storage of the exact output type.
    let result = unsafe {
        libc::fstatat(
            directory_fd,
            name.as_ptr(),
            stat.as_mut_ptr(),
            libc::AT_SYMLINK_NOFOLLOW,
        )
    };
    if result != 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: successful `fstatat` fully initialized the output.
    Ok(unsafe { stat.assume_init() })
}

fn stat_fd(fd: RawFd) -> io::Result<libc::stat> {
    let mut stat = MaybeUninit::<libc::stat>::uninit();
    // SAFETY: `fd` is live and `stat` points to writable storage of the exact
    // output type.
    if unsafe { libc::fstat(fd, stat.as_mut_ptr()) } != 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: successful `fstat` fully initialized the output.
    Ok(unsafe { stat.assume_init() })
}

fn is_regular(stat: &libc::stat) -> bool {
    u64::from(stat.st_mode) & u64::from(libc::S_IFMT) == u64::from(libc::S_IFREG)
}

fn is_directory(stat: &libc::stat) -> bool {
    u64::from(stat.st_mode) & u64::from(libc::S_IFMT) == u64::from(libc::S_IFDIR)
}

fn same_regular_file(left: &libc::stat, right: &libc::stat) -> bool {
    is_regular(right) && left.st_dev == right.st_dev && left.st_ino == right.st_ino
}

fn same_directory(left: &libc::stat, right: &libc::stat) -> bool {
    is_directory(right) && left.st_dev == right.st_dev && left.st_ino == right.st_ino
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum LeaseAttempt {
    Acquired,
    Busy,
    Unsupported,
}

fn try_lock_exclusive(fd: RawFd) -> io::Result<LeaseAttempt> {
    loop {
        // SAFETY: the file descriptor is valid and the flags take no pointer.
        if unsafe { libc::flock(fd, libc::LOCK_EX | libc::LOCK_NB) } == 0 {
            return Ok(LeaseAttempt::Acquired);
        }
        let error = io::Error::last_os_error();
        if error.kind() == io::ErrorKind::Interrupted {
            continue;
        }
        if busy_error(&error) {
            return Ok(LeaseAttempt::Busy);
        }
        if lock_capability_absent(&error) {
            return Ok(LeaseAttempt::Unsupported);
        }
        return Err(error);
    }
}

fn name_changed_error(error: &io::Error) -> bool {
    matches!(
        error.raw_os_error(),
        Some(code) if code == libc::ENOENT || code == libc::ESTALE
    )
}

fn busy_error(error: &io::Error) -> bool {
    matches!(
        error.raw_os_error(),
        Some(code)
            if code == libc::EAGAIN
                || code == libc::EWOULDBLOCK
                || code == libc::EBUSY
    )
}

struct DirectoryEntries {
    stream: NonNull<libc::DIR>,
}

impl DirectoryEntries {
    fn new(directory: &OwnedFd) -> io::Result<Self> {
        // SAFETY: `directory` is a valid descriptor. F_DUPFD_CLOEXEC returns
        // a new descriptor and prevents it leaking across a concurrent exec.
        let duplicate = unsafe { libc::fcntl(directory.as_raw_fd(), libc::F_DUPFD_CLOEXEC, 0) };
        if duplicate < 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: `duplicate` is a valid directory descriptor whose ownership
        // transfers to `fdopendir` on success.
        let stream = unsafe { libc::fdopendir(duplicate) };
        let Some(stream) = NonNull::new(stream) else {
            let error = io::Error::last_os_error();
            // SAFETY: `fdopendir` failed, so ownership remains with the caller.
            let _ = unsafe { libc::close(duplicate) };
            return Err(error);
        };
        Ok(Self { stream })
    }

    fn next_name(&mut self) -> io::Result<Option<CString>> {
        clear_errno();
        // SAFETY: `stream` remains live and is used by only this iterator.
        let entry = unsafe { libc::readdir(self.stream.as_ptr()) };
        if entry.is_null() {
            let errno = current_errno();
            return if errno == 0 {
                Ok(None)
            } else {
                Err(io::Error::from_raw_os_error(errno))
            };
        }
        // SAFETY: POSIX guarantees a NUL-terminated d_name for a successful
        // readdir result, valid until the next operation on this stream.
        let name = unsafe { CStr::from_ptr((*entry).d_name.as_ptr()) };
        Ok(Some(name.to_owned()))
    }
}

impl Drop for DirectoryEntries {
    fn drop(&mut self) {
        // SAFETY: this object uniquely owns the live DIR pointer.
        let _ = unsafe { libc::closedir(self.stream.as_ptr()) };
    }
}

#[cfg(target_vendor = "apple")]
fn current_errno() -> libc::c_int {
    // SAFETY: `__error` returns the calling thread's errno storage.
    unsafe { *libc::__error() }
}

#[cfg(target_vendor = "apple")]
fn clear_errno() {
    // SAFETY: `__error` returns the calling thread's errno storage.
    unsafe { *libc::__error() = 0 };
}

#[cfg(target_os = "android")]
fn current_errno() -> libc::c_int {
    // SAFETY: `__errno` returns the calling thread's errno storage.
    unsafe { *libc::__errno() }
}

#[cfg(target_os = "android")]
fn clear_errno() {
    // SAFETY: `__errno` returns the calling thread's errno storage.
    unsafe { *libc::__errno() = 0 };
}

#[cfg(not(any(target_vendor = "apple", target_os = "android")))]
fn current_errno() -> libc::c_int {
    // SAFETY: `__errno_location` returns the calling thread's errno storage.
    unsafe { *libc::__errno_location() }
}

#[cfg(not(any(target_vendor = "apple", target_os = "android")))]
fn clear_errno() {
    // SAFETY: `__errno_location` returns the calling thread's errno storage.
    unsafe { *libc::__errno_location() = 0 };
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::naming::{
        TemporaryArtifactProtocol, TemporaryFileRole, new_file_lease_artifact_name,
        new_temporary_artifact_names, temporary_artifact_names_from_nonce,
    };
    use std::{
        cell::{Cell, RefCell},
        fs::OpenOptions,
        os::unix::{fs::PermissionsExt, fs::symlink},
        rc::Rc,
    };

    /// The sweeper and the writer must agree on what "this volume has no lease
    /// protocol" looks like, or one keeps working while the other refuses.
    ///
    /// Agreement is now structural — both call
    /// [`crate::fsops::lock_capability_absent`] — which is the actual fix. This
    /// test previously claimed to establish parity while only exercising a
    /// second, divergent copy local to this module: it asserted `EINVAL` was an
    /// absent capability here, while the writer treated the same errno as a hard
    /// error and so never reached its sidecar fallback. A volume answering
    /// `EINVAL` left the sweeper reclaiming happily and every user-initiated
    /// save failing.
    ///
    /// `ENOTSUP` remains load-bearing: Darwin's `flock(2)` documents it as the
    /// wrong-descriptor-type error and defines it distinctly from `EOPNOTSUPP`,
    /// while Linux aliases the two. Omitting it disabled the whole sweeper on
    /// affected Apple volumes and left decrypted staged files on disk.
    #[test]
    fn lock_capability_errors_match_the_writer_across_platforms() {
        for code in [libc::ENOTSUP, libc::EOPNOTSUPP, libc::ENOSYS, libc::EINVAL] {
            assert!(
                lock_capability_absent(&io::Error::from_raw_os_error(code)),
                "errno {code} must be treated as an absent lease protocol"
            );
        }

        // Contention and real failures must stay distinguishable from a
        // missing capability, or a busy directory would be swept anyway.
        for code in [libc::EAGAIN, libc::EWOULDBLOCK, libc::EBUSY, libc::EIO] {
            assert!(
                !lock_capability_absent(&io::Error::from_raw_os_error(code)),
                "errno {code} must not be mistaken for an absent lease protocol"
            );
        }
    }

    struct TestDirectory(std::path::PathBuf);

    impl TestDirectory {
        fn new() -> Self {
            let mut nonce = [0_u8; 8];
            getrandom::fill(&mut nonce).expect("test nonce generation must succeed");
            let name: String = nonce.iter().map(|byte| format!("{byte:02x}")).collect();
            let path = std::env::temp_dir().join(format!("keyguard-sweep-v2-test-{name}"));
            std::fs::create_dir_all(&path).expect("test directory must be created");
            Self(path)
        }
    }

    impl Drop for TestDirectory {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn options() -> SweepOptions {
        SweepOptions {
            older_than: Duration::ZERO,
            role_mask: u32::MAX,
        }
    }

    fn create_sidecar_pair(directory: &Path, nonce: &str) -> crate::naming::TemporaryArtifactNames {
        let names = temporary_artifact_names_from_nonce(
            TemporaryFileRole::New,
            TemporaryArtifactProtocol::SidecarLeaseV1,
            nonce,
        )
        .expect("test nonce is canonical");
        std::fs::write(directory.join(&names.data), b"data").expect("write data");
        let lease = names.lease.as_ref().expect("v1s has a lease");
        std::fs::write(directory.join(lease), b"").expect("write lease");
        std::fs::set_permissions(
            directory.join(lease),
            std::fs::Permissions::from_mode(0o600),
        )
        .expect("make sidecar owner-only");
        names
    }

    #[test]
    fn removes_only_selected_canonical_regular_files() {
        let directory = TestDirectory::new();
        let orphan = directory.0.join(
            new_file_lease_artifact_name(TemporaryFileRole::New).expect("name must generate"),
        );
        let other_role = directory.0.join(
            new_file_lease_artifact_name(TemporaryFileRole::Scratch).expect("name must generate"),
        );
        let user_file = directory.0.join("vault.kdbx");
        std::fs::write(&orphan, b"stale").expect("write orphan");
        std::fs::write(&other_role, b"stale").expect("write other role");
        std::fs::write(&user_file, b"precious").expect("write user file");

        let report = sweep_orphans(
            &directory.0,
            SweepOptions {
                role_mask: TemporaryFileRole::New.mask_bit(),
                ..options()
            },
        )
        .expect("sweep must succeed");

        assert_eq!(report.status, SweepStatus::Complete);
        assert_eq!(report.candidate_names, 1);
        assert_eq!(report.removed, 1);
        assert!(report.candidate_partition_holds());
        assert!(!orphan.exists());
        assert!(other_role.exists());
        assert_eq!(
            std::fs::read(&user_file).expect("read user file"),
            b"precious"
        );
    }

    #[test]
    fn directory_lease_busy_is_global_and_precedes_enumeration() {
        let directory = TestDirectory::new();
        std::fs::write(
            directory
                .0
                .join(new_file_lease_artifact_name(TemporaryFileRole::New).expect("name")),
            b"candidate",
        )
        .expect("write candidate");

        let report = sweep_orphans_with_lease_probes(
            &directory.0,
            options(),
            |_| Ok(DirectoryLease::Busy),
            try_lock_exclusive,
        )
        .expect("busy is a contained outcome");

        assert_eq!(report.status, SweepStatus::Busy);
        assert_eq!(report.entries_seen, 0);
        assert_eq!(report.candidate_names, 0);
        assert!(report.candidate_partition_holds());
    }

    #[test]
    fn lock_contention_and_unsupported_capability_are_not_conflated() {
        assert!(busy_error(&io::Error::from_raw_os_error(libc::EAGAIN)));
        assert!(!busy_error(&io::Error::from_raw_os_error(libc::EACCES)));
        assert!(lock_capability_absent(&io::Error::from_raw_os_error(
            libc::EOPNOTSUPP
        )));
        assert!(!lock_capability_absent(&io::Error::from_raw_os_error(
            libc::ENOLCK
        )));
        // `EBADF` is a defect in this crate, not a filesystem without locks, so
        // neither the writer nor the sweeper may skip the lease on it.
        assert!(!lock_capability_absent(&io::Error::from_raw_os_error(
            libc::EBADF
        )));
    }

    #[test]
    fn unsupported_directory_lease_fails_v1d_closed_but_processes_other_protocols() {
        let directory = TestDirectory::new();
        let file_lease = directory
            .0
            .join(new_file_lease_artifact_name(TemporaryFileRole::New).expect("file_lease name"));
        std::fs::write(&file_lease, b"file_lease").expect("write file_lease");
        let v1d = new_temporary_artifact_names(
            TemporaryFileRole::New,
            TemporaryArtifactProtocol::DirectoryLeaseV1,
        )
        .expect("v1d name");
        std::fs::write(directory.0.join(&v1d.data), b"v1d").expect("write v1d");

        let report = sweep_orphans_with_lease_probes(
            &directory.0,
            options(),
            |_| Ok(DirectoryLease::Unsupported),
            try_lock_exclusive,
        )
        .expect("unsupported directory locking is contained");

        assert_eq!(report.status, SweepStatus::Incomplete);
        assert_eq!(report.candidate_names, 2);
        assert_eq!(report.removed, 1);
        assert_eq!(report.inspection_failed, 1);
        assert!(report.candidate_partition_holds());
        assert!(!file_lease.exists());
        assert!(directory.0.join(v1d.data).exists());
    }

    #[test]
    fn unsupported_directory_lease_still_reclaims_a_valid_v1s_pair() {
        let directory = TestDirectory::new();
        let names = create_sidecar_pair(&directory.0, "123e4567-e89b-42d3-a456-426614174010");

        let report = sweep_orphans_with_lease_probes(
            &directory.0,
            options(),
            |_| Ok(DirectoryLease::Unsupported),
            try_lock_exclusive,
        )
        .expect("v1s is the supported fallback");

        assert_eq!(report.status, SweepStatus::Complete);
        assert_eq!(report.candidate_names, 2);
        assert_eq!(report.removed, 2);
        assert!(report.candidate_partition_holds());
        assert!(!directory.0.join(names.data).exists());
        assert!(
            !directory
                .0
                .join(names.lease.expect("v1s has a lease"))
                .exists()
        );
    }

    #[test]
    fn unsupported_sidecar_lock_fails_the_pair_closed_and_incomplete() {
        let directory = TestDirectory::new();
        let names = create_sidecar_pair(&directory.0, "123e4567-e89b-42d3-a456-426614174011");
        let lease = names.lease.as_ref().expect("v1s has a lease");

        let report = sweep_orphans_with_lease_probes(
            &directory.0,
            options(),
            |_| Ok(DirectoryLease::Unsupported),
            |_| Ok(LeaseAttempt::Unsupported),
        )
        .expect("unsupported sidecar locking is contained");

        assert_eq!(report.status, SweepStatus::Incomplete);
        assert_eq!(report.candidate_names, 2);
        assert_eq!(report.inspection_failed, 2);
        assert!(report.candidate_partition_holds());
        assert!(directory.0.join(names.data).exists());
        assert!(directory.0.join(lease).exists());
    }

    #[test]
    fn lock_bearing_candidates_are_opened_read_write() {
        let directory = TestDirectory::new();
        let file_lease = directory
            .0
            .join(new_file_lease_artifact_name(TemporaryFileRole::New).expect("file_lease name"));
        std::fs::write(&file_lease, b"file_lease").expect("write file_lease");
        let v1s = create_sidecar_pair(&directory.0, "123e4567-e89b-42d3-a456-426614174012");
        let probes = Cell::new(0_u32);

        let report = sweep_orphans_with_lease_probes(
            &directory.0,
            options(),
            acquire_directory_lease,
            |fd| {
                // SAFETY: `fd` is a live candidate descriptor and F_GETFL has
                // no pointer argument.
                let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
                if flags < 0 {
                    return Err(io::Error::last_os_error());
                }
                if flags & libc::O_ACCMODE != libc::O_RDWR {
                    return Err(io::Error::other(
                        "lock-bearing candidate was not opened read-write",
                    ));
                }
                probes.set(probes.get().saturating_add(1));
                try_lock_exclusive(fd)
            },
        )
        .expect("sweep succeeds");

        assert_eq!(probes.get(), 2);
        assert_eq!(report.candidate_names, 3);
        assert_eq!(report.removed, 3);
        assert!(report.candidate_partition_holds());
        assert!(!file_lease.exists());
        assert!(!directory.0.join(v1s.data).exists());
    }

    #[test]
    fn closing_the_directory_stream_duplicate_keeps_the_original_lease() {
        let directory = TestDirectory::new();
        let retained = open_directory(&directory.0).expect("open retained directory");
        assert_eq!(
            acquire_directory_lease(&retained).expect("directory lock succeeds"),
            DirectoryLease::Acquired
        );
        {
            let mut entries = DirectoryEntries::new(&retained).expect("open directory stream");
            while entries.next_name().expect("enumeration succeeds").is_some() {}
        }

        let competing = open_directory(&directory.0).expect("open competing directory");
        assert_eq!(
            try_lock_exclusive(competing.as_raw_fd()).expect("lock probe succeeds"),
            LeaseAttempt::Busy
        );
        drop(retained);
        assert_eq!(
            try_lock_exclusive(competing.as_raw_fd()).expect("lock succeeds after release"),
            LeaseAttempt::Acquired
        );
    }

    #[test]
    fn real_directory_lease_removes_v1d_data() {
        let directory = TestDirectory::new();
        let names = new_temporary_artifact_names(
            TemporaryFileRole::New,
            TemporaryArtifactProtocol::DirectoryLeaseV1,
        )
        .expect("v1d name");
        let data = directory.0.join(&names.data);
        std::fs::write(&data, b"orphan").expect("write v1d data");

        let report = sweep_orphans(&directory.0, options()).expect("sweep succeeds");

        assert_eq!(report.candidate_names, 1);
        assert_eq!(report.removed, 1);
        assert!(report.candidate_partition_holds());
        assert!(!data.exists());
    }

    #[test]
    fn real_shared_directory_writer_lease_returns_global_busy() {
        let directory = TestDirectory::new();
        let directory_file = File::open(&directory.0).expect("open directory");
        // SAFETY: the descriptor is valid and flock takes no pointer.
        let lock_result =
            unsafe { libc::flock(directory_file.as_raw_fd(), libc::LOCK_SH | libc::LOCK_NB) };
        assert_eq!(lock_result, 0);

        let report = sweep_orphans(&directory.0, options()).expect("busy is contained");

        assert_eq!(report.status, SweepStatus::Busy);
        assert_eq!(report.entries_seen, 0);
        assert_eq!(report.candidate_names, 0);
    }

    #[test]
    fn complete_sidecar_pair_is_removed_data_then_sidecar() {
        let directory = TestDirectory::new();
        let names = create_sidecar_pair(&directory.0, "123e4567-e89b-42d3-a456-426614174000");
        let data = directory.0.join(&names.data);
        let sidecar = directory
            .0
            .join(names.lease.as_ref().expect("lease is present"));

        let report = sweep_orphans(&directory.0, options()).expect("sweep succeeds");

        assert_eq!(report.candidate_names, 2);
        assert_eq!(report.removed, 2);
        assert!(report.candidate_partition_holds());
        assert!(!data.exists());
        assert!(!sidecar.exists());
    }

    #[test]
    fn an_old_sidecar_without_data_is_a_reclaimable_crash_state() {
        let directory = TestDirectory::new();
        let names = temporary_artifact_names_from_nonce(
            TemporaryFileRole::New,
            TemporaryArtifactProtocol::SidecarLeaseV1,
            "123e4567-e89b-42d3-a456-426614174013",
        )
        .expect("name");
        let lease_name = names.lease.as_ref().expect("v1s has a lease");
        let lease = directory.0.join(lease_name);
        std::fs::write(&lease, b"").expect("write lease");
        std::fs::set_permissions(&lease, std::fs::Permissions::from_mode(0o600))
            .expect("secure lease");

        let report = sweep_orphans(&directory.0, options()).expect("sweep succeeds");

        assert_eq!(report.status, SweepStatus::Complete);
        assert_eq!(report.candidate_names, 1);
        assert_eq!(report.removed, 1);
        assert!(report.candidate_partition_holds());
        assert!(!lease.exists());
        assert!(!directory.0.join(names.data).exists());
    }

    #[test]
    fn a_live_sidecar_without_data_is_busy_and_retained() {
        let directory = TestDirectory::new();
        let names = temporary_artifact_names_from_nonce(
            TemporaryFileRole::New,
            TemporaryArtifactProtocol::SidecarLeaseV1,
            "123e4567-e89b-42d3-a456-426614174014",
        )
        .expect("name");
        let lease = directory.0.join(names.lease.as_ref().expect("lease"));
        std::fs::write(&lease, b"").expect("write lease");
        std::fs::set_permissions(&lease, std::fs::Permissions::from_mode(0o600))
            .expect("secure lease");
        let live = OpenOptions::new()
            .read(true)
            .write(true)
            .open(&lease)
            .expect("open live lease");
        // SAFETY: the descriptor is live and flock takes no pointer argument.
        let lock_result = unsafe { libc::flock(live.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) };
        assert_eq!(lock_result, 0);

        let report = sweep_orphans(&directory.0, options()).expect("sweep succeeds");

        assert_eq!(report.candidate_names, 1);
        assert_eq!(report.skipped_busy, 1);
        assert!(report.candidate_partition_holds());
        assert!(lease.exists());
    }

    #[test]
    fn a_young_sidecar_without_data_is_retained() {
        let directory = TestDirectory::new();
        let names = temporary_artifact_names_from_nonce(
            TemporaryFileRole::New,
            TemporaryArtifactProtocol::SidecarLeaseV1,
            "123e4567-e89b-42d3-a456-426614174015",
        )
        .expect("name");
        let lease = directory.0.join(names.lease.as_ref().expect("lease"));
        std::fs::write(&lease, b"").expect("write lease");
        std::fs::set_permissions(&lease, std::fs::Permissions::from_mode(0o600))
            .expect("secure lease");

        let report = sweep_orphans(
            &directory.0,
            SweepOptions {
                older_than: Duration::from_secs(60),
                ..options()
            },
        )
        .expect("sweep succeeds");

        assert_eq!(report.candidate_names, 1);
        assert_eq!(report.skipped_young, 1);
        assert!(report.candidate_partition_holds());
        assert!(lease.exists());
    }

    #[test]
    fn live_sidecar_lease_classifies_the_pair_busy() {
        let directory = TestDirectory::new();
        let names = create_sidecar_pair(&directory.0, "123e4567-e89b-42d3-a456-426614174001");
        let lease_path = directory
            .0
            .join(names.lease.as_ref().expect("lease is present"));
        let lease = OpenOptions::new()
            .read(true)
            .write(true)
            .open(&lease_path)
            .expect("open lease");
        // SAFETY: the descriptor is valid and flock takes no pointer.
        let lock_result = unsafe { libc::flock(lease.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) };
        assert_eq!(lock_result, 0);

        let report = sweep_orphans(&directory.0, options()).expect("sweep succeeds");

        assert_eq!(report.candidate_names, 2);
        assert_eq!(report.skipped_busy, 2);
        assert!(report.candidate_partition_holds());
        assert!(directory.0.join(names.data).exists());
        assert!(lease_path.exists());
    }

    #[test]
    fn newest_sidecar_activity_controls_pair_age() {
        let directory = TestDirectory::new();
        let names = create_sidecar_pair(&directory.0, "123e4567-e89b-42d3-a456-426614174002");
        let report = sweep_orphans(
            &directory.0,
            SweepOptions {
                older_than: Duration::from_secs(60),
                ..options()
            },
        )
        .expect("sweep succeeds");

        assert_eq!(report.candidate_names, 2);
        assert_eq!(report.skipped_young, 2);
        assert!(report.candidate_partition_holds());
        assert!(directory.0.join(names.data).exists());
        assert!(
            directory
                .0
                .join(names.lease.expect("lease is present"))
                .exists()
        );
    }

    #[test]
    fn sidecar_must_be_exact_owner_only_single_link_regular_file() {
        let directory = TestDirectory::new();
        let names = create_sidecar_pair(&directory.0, "123e4567-e89b-42d3-a456-426614174003");
        let lease = directory
            .0
            .join(names.lease.as_ref().expect("lease is present"));
        std::fs::set_permissions(&lease, std::fs::Permissions::from_mode(0o644))
            .expect("weaken sidecar mode");

        let report = sweep_orphans(&directory.0, options()).expect("sweep succeeds");

        assert_eq!(report.candidate_names, 2);
        assert_eq!(report.skipped_unsafe, 2);
        assert!(report.candidate_partition_holds());
        assert!(directory.0.join(names.data).exists());
        assert!(lease.exists());
    }

    #[test]
    fn data_without_its_sidecar_is_retained_and_reported_incomplete() {
        let directory = TestDirectory::new();
        let data_only = temporary_artifact_names_from_nonce(
            TemporaryFileRole::New,
            TemporaryArtifactProtocol::SidecarLeaseV1,
            "123e4567-e89b-42d3-a456-426614174004",
        )
        .expect("name");
        std::fs::write(directory.0.join(&data_only.data), b"data only").expect("write data");
        let malformed = ".kg-tmp-v1s-n-not-a-canonical-uuid.tmp";
        std::fs::write(directory.0.join(malformed), b"reserved").expect("write malformed");

        let report = sweep_orphans(&directory.0, options()).expect("sweep succeeds");

        assert_eq!(report.status, SweepStatus::Incomplete);
        assert_eq!(report.candidate_names, 1);
        assert_eq!(report.inspection_failed, 1);
        assert!(report.candidate_partition_holds());
        assert!(directory.0.join(data_only.data).exists());
        assert!(directory.0.join(malformed).exists());
    }

    #[test]
    fn mixed_protocols_reusing_an_identity_are_all_retained() {
        let directory = TestDirectory::new();
        let nonce = "123e4567-e89b-42d3-a456-426614174006";
        let v1d = temporary_artifact_names_from_nonce(
            TemporaryFileRole::New,
            TemporaryArtifactProtocol::DirectoryLeaseV1,
            nonce,
        )
        .expect("v1d name");
        let v1s = create_sidecar_pair(&directory.0, nonce);
        std::fs::write(directory.0.join(&v1d.data), b"mixed").expect("write v1d");

        let report = sweep_orphans(&directory.0, options()).expect("sweep succeeds");

        assert_eq!(report.candidate_names, 3);
        assert_eq!(report.skipped_unsafe, 3);
        assert!(report.candidate_partition_holds());
        assert!(directory.0.join(v1d.data).exists());
        assert!(directory.0.join(v1s.data).exists());
        assert!(directory.0.join(v1s.lease.expect("sidecar")).exists());
    }

    #[test]
    fn removal_helper_orders_data_sidecar_and_lease_release() {
        #[derive(Clone)]
        struct LoggedLease(Rc<RefCell<Vec<&'static str>>>);
        impl Drop for LoggedLease {
            fn drop(&mut self) {
                self.0.borrow_mut().push("release");
            }
        }

        let events = Rc::new(RefCell::new(Vec::new()));
        let lease = LoggedLease(Rc::clone(&events));
        let (data, sidecar) = data_then_sidecar_while_lease_held(
            lease,
            |lease| {
                lease.0.borrow_mut().push("data");
                CandidateOutcome::Removed
            },
            |lease| {
                lease.0.borrow_mut().push("sidecar");
                CandidateOutcome::Removed
            },
        );

        assert!(matches!(data, CandidateOutcome::Removed));
        assert!(matches!(sidecar, Some(CandidateOutcome::Removed)));
        assert_eq!(&*events.borrow(), &["data", "sidecar", "release"]);
    }

    #[test]
    fn removal_helper_retains_sidecar_when_data_removal_fails() {
        #[derive(Clone)]
        struct LoggedLease(Rc<RefCell<Vec<&'static str>>>);
        impl Drop for LoggedLease {
            fn drop(&mut self) {
                self.0.borrow_mut().push("release");
            }
        }

        let events = Rc::new(RefCell::new(Vec::new()));
        let lease = LoggedLease(Rc::clone(&events));
        let (_, sidecar) = data_then_sidecar_while_lease_held(
            lease,
            |lease| {
                lease.0.borrow_mut().push("data");
                CandidateOutcome::Changed
            },
            |lease| {
                lease.0.borrow_mut().push("sidecar");
                CandidateOutcome::Removed
            },
        );

        assert!(sidecar.is_none());
        assert_eq!(&*events.borrow(), &["data", "release"]);
    }

    #[test]
    fn data_removed_and_sidecar_unlink_failed_remains_an_exact_partition() {
        #[derive(Clone)]
        struct LoggedLease(Rc<RefCell<Vec<&'static str>>>);
        impl Drop for LoggedLease {
            fn drop(&mut self) {
                self.0.borrow_mut().push("release");
            }
        }

        let events = Rc::new(RefCell::new(Vec::new()));
        let (data, sidecar) = data_then_sidecar_while_lease_held(
            LoggedLease(Rc::clone(&events)),
            |lease| {
                lease.0.borrow_mut().push("data");
                CandidateOutcome::Removed
            },
            |lease| {
                lease.0.borrow_mut().push("sidecar");
                CandidateOutcome::RemovalFailed(io::Error::other("injected sidecar unlink failure"))
            },
        );
        let mut report = SweepReport {
            candidate_names: 2,
            ..SweepReport::default()
        };
        apply_outcome(&mut report, data);
        apply_outcome(
            &mut report,
            sidecar.expect("successful data removal runs sidecar cleanup"),
        );

        assert_eq!(report.status, SweepStatus::Incomplete);
        assert_eq!(report.removed, 1);
        assert_eq!(report.removal_failed, 1);
        assert!(report.candidate_partition_holds());
        assert_eq!(&*events.borrow(), &["data", "sidecar", "release"]);
    }

    #[test]
    fn retained_directory_is_swept_after_its_ancestor_name_changes() {
        let container = TestDirectory::new();
        let original = container.0.join("root");
        let moved = container.0.join("moved");
        std::fs::create_dir(&original).expect("create sweep root");
        let names = new_temporary_artifact_names(
            TemporaryFileRole::New,
            TemporaryArtifactProtocol::DirectoryLeaseV1,
        )
        .expect("v1d name");
        std::fs::write(original.join(&names.data), b"orphan").expect("write orphan");

        let retained = open_directory(&original).expect("open retained root");
        let lease = acquire_directory_lease(&retained).expect("acquire directory lease");
        assert_eq!(lease, DirectoryLease::Acquired);
        std::fs::rename(&original, &moved).expect("rename root");
        let mut report = SweepReport::default();
        let candidates = enumerate_candidates(&retained, u32::MAX, &mut report)
            .expect("enumerate retained root");
        process_inventory(
            retained.as_raw_fd(),
            candidates,
            lease,
            SystemTime::now(),
            options(),
            &try_lock_exclusive,
            &mut report,
        );

        assert_eq!(report.candidate_names, 1);
        assert_eq!(report.removed, 1);
        assert!(report.candidate_partition_holds());
        assert!(!moved.join(names.data).exists());
        assert!(!original.exists());
    }

    #[test]
    fn canonical_symlink_is_never_followed_or_removed() {
        let directory = TestDirectory::new();
        let precious = directory.0.join("precious");
        let link = directory.0.join(
            new_file_lease_artifact_name(TemporaryFileRole::New).expect("name must generate"),
        );
        std::fs::write(&precious, b"keep").expect("write precious file");
        symlink(&precious, &link).expect("create symlink");

        let report = sweep_orphans(&directory.0, options()).expect("sweep must succeed");

        assert_eq!(report.candidate_names, 1);
        assert_eq!(report.skipped_unsafe, 1);
        assert!(report.candidate_partition_holds());
        assert!(link.symlink_metadata().is_ok());
        assert_eq!(std::fs::read(&precious).expect("read precious"), b"keep");
    }

    #[test]
    fn file_lease_skips_a_live_candidate() {
        let directory = TestDirectory::new();
        let candidate = directory.0.join(
            new_file_lease_artifact_name(TemporaryFileRole::New).expect("name must generate"),
        );
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create_new(true)
            .open(&candidate)
            .expect("create candidate");
        // SAFETY: descriptor is valid and flock takes no pointer argument.
        assert_eq!(unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX) }, 0);

        let report = sweep_orphans(&directory.0, options()).expect("sweep must succeed");

        assert_eq!(report.candidate_names, 1);
        assert_eq!(report.skipped_busy, 1);
        assert!(report.candidate_partition_holds());
        assert!(candidate.exists());
    }

    #[test]
    fn recent_change_time_prevents_removal_after_mtime_is_backdated() {
        let directory = TestDirectory::new();
        let candidate = directory.0.join(
            new_file_lease_artifact_name(TemporaryFileRole::New).expect("name must generate"),
        );
        std::fs::write(&candidate, b"recently created").expect("write candidate");
        let old = libc::timespec {
            tv_sec: 1,
            tv_nsec: 0,
        };
        let times = [old, old];
        let candidate_c = CString::new(candidate.as_os_str().as_bytes()).expect("valid test path");
        // SAFETY: the test path is NUL-terminated and `times` contains the two
        // timestamps required by `utimensat`.
        let update_result =
            unsafe { libc::utimensat(libc::AT_FDCWD, candidate_c.as_ptr(), times.as_ptr(), 0) };
        assert_eq!(update_result, 0);

        let report = sweep_orphans(
            &directory.0,
            SweepOptions {
                older_than: Duration::from_secs(60),
                ..options()
            },
        )
        .expect("sweep must succeed");

        assert_eq!(report.candidate_names, 1);
        assert_eq!(report.skipped_young, 1);
        assert!(report.candidate_partition_holds());
        assert!(candidate.exists());
    }

    #[test]
    fn missing_directory_is_a_complete_empty_sweep() {
        let directory = TestDirectory::new().0.join("missing");
        let report = sweep_orphans(&directory, options()).expect("missing is not an error");
        assert_eq!(report, SweepReport::default());
    }

    #[test]
    fn relative_and_symlink_roots_are_rejected() {
        let relative = sweep_orphans(Path::new("relative"), options())
            .expect_err("relative roots must be rejected");
        assert_eq!(relative.kind(), io::ErrorKind::InvalidInput);

        let directory = TestDirectory::new();
        let link = directory.0.with_extension("link");
        symlink(&directory.0, &link).expect("create root symlink");
        sweep_orphans(&link, options()).expect_err("root symlinks must be rejected");
        let _ = std::fs::remove_file(link);
    }
}
