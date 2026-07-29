//! Windows orphan sweeping through an anchored directory handle.

use std::{
    ffi::c_void,
    io,
    mem::{offset_of, size_of},
    path::Path,
    ptr::null_mut,
    time::Duration,
};

use windows_sys::{
    Wdk::Storage::FileSystem::{
        FILE_DIRECTORY_FILE, FILE_NON_DIRECTORY_FILE, FILE_OPEN_REPARSE_POINT,
        FILE_SYNCHRONOUS_IO_NONALERT,
    },
    Win32::{
        Foundation::{
            ERROR_CANT_ACCESS_FILE, ERROR_FILE_NOT_FOUND, ERROR_HANDLE_EOF, ERROR_INVALID_FUNCTION,
            ERROR_INVALID_LEVEL, ERROR_INVALID_PARAMETER, ERROR_LOCK_VIOLATION,
            ERROR_NO_MORE_FILES, ERROR_NOT_FOUND, ERROR_NOT_SUPPORTED, ERROR_PATH_NOT_FOUND,
            ERROR_REPARSE_POINT_ENCOUNTERED, ERROR_SHARING_VIOLATION, FILETIME, GetLastError,
            HANDLE, OBJ_CASE_INSENSITIVE, OBJ_DONT_REPARSE,
        },
        Storage::FileSystem::{
            BY_HANDLE_FILE_INFORMATION, DELETE, FILE_ATTRIBUTE_DIRECTORY,
            FILE_ATTRIBUTE_REPARSE_POINT, FILE_ATTRIBUTE_TAG_INFO, FILE_BASIC_INFO,
            FILE_ID_BOTH_DIR_INFO, FILE_ID_EXTD_DIR_INFO, FILE_ID_INFO, FILE_LIST_DIRECTORY,
            FILE_READ_ATTRIBUTES, FILE_SHARE_READ, FILE_SHARE_WRITE, FILE_TRAVERSE,
            FileAttributeTagInfo, FileBasicInfo, FileIdBothDirectoryInfo,
            FileIdBothDirectoryRestartInfo, FileIdExtdDirectoryInfo,
            FileIdExtdDirectoryRestartInfo, FileIdInfo, FileStandardInfo,
            GetFileInformationByHandle, GetFileInformationByHandleEx, SYNCHRONIZE,
        },
        System::SystemInformation::GetSystemTimePreciseAsFileTime,
    },
};

use super::{SweepOptions, SweepReport};
use crate::{
    naming::{
        TemporaryArtifactEntryKind, TemporaryArtifactProtocol, parse_temporary_artifact_name,
    },
    windows_nt::{
        FileStandardInfoBytes, NtAbsolutePath, OwnedHandle, STATUS_DELETE_PENDING,
        mark_delete_on_close, nt_open_file, nt_open_file_status, nt_status_to_io_error,
        query_file_information,
    },
};

const ENUMERATION_BUFFER_BYTES: usize = 64 * 1024;

pub(super) fn sweep_orphans(directory: &Path, options: SweepOptions) -> io::Result<SweepReport> {
    let root = match open_sweep_root(directory) {
        Ok(root) => root,
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            return Ok(SweepReport::default());
        }
        Err(error) => return Err(error),
    };
    validate_sweep_root(root.as_raw())?;

    let now = precise_file_time();
    let minimum_age = duration_to_file_time_ticks(options.older_than);
    let mut report = SweepReport::default();
    let candidates = enumerate_candidates(root.as_raw(), options.role_mask, &mut report)?;

    for candidate in candidates {
        match inspect_and_remove(root.as_raw(), &candidate, now, minimum_age) {
            CandidateOutcome::Removed => {
                report.removed = report.removed.saturating_add(1);
            }
            CandidateOutcome::Young => {
                report.skipped_young = report.skipped_young.saturating_add(1);
            }
            CandidateOutcome::Busy => {
                report.skipped_busy = report.skipped_busy.saturating_add(1);
            }
            CandidateOutcome::Unsafe => {
                report.skipped_unsafe = report.skipped_unsafe.saturating_add(1);
            }
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

    debug_assert!(report.candidate_partition_holds());
    Ok(report)
}

#[derive(Debug)]
struct Candidate {
    name: Vec<u16>,
    file_id: CandidateFileId,
}

/// Directory-enumeration information class served by the volume.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum EnumerationScheme {
    /// `FileIdExtdDirectoryInfo`: a 128-bit file ID and a reparse tag.
    ExtendedFileId,
    /// `FileIdBothDirectoryInfo`: a 64-bit file reference number and no reparse
    /// tag. The FAT family implements this class and not the extended one.
    BothFileId,
}

/// An enumerated object's reference number, tagged with its source class.
///
/// The two classes report unrelated widths, so an enumeration value is only
/// ever compared against a per-handle value read through the matching class.
/// The variants therefore never compare equal, and a mismatch means
/// [`CandidateOutcome::Changed`] — the sweep declines to delete.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum CandidateFileId {
    Extended([u8; 16]),
    Both(i64),
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

/// Opens the sweep root.
///
/// `FILE_TRAVERSE` is requested alongside enumeration access because this
/// handle is also used as the NT `RootDirectory` for every candidate open.
/// Microsoft gives that exact access pair for a relative-open root: "To perform
/// two open operations that won't cause a sharing conflict, you can open
/// RootDirectory by requesting traverse | read-attribute." Without it, relative
/// opens succeed only by virtue of *Bypass traverse checking*
/// (`SeChangeNotifyPrivilege`), which a hardened host may withhold — and the
/// sweep would then silently stop reclaiming plaintext.
///
/// `OBJ_DONT_REPARSE` is deliberately absent, unlike every relative open in
/// this module. The flag refuses *object-manager* reparses too, and a
/// drive-letter NT path is one: `\??\C:` is a symbolic link object, so
/// `NtOpenFile` answers `STATUS_REPARSE_POINT_ENCOUNTERED` for any path this
/// crate can construct — the volume-GUID and `\??\UNC\` forms included.
/// Combining it with an absolute path therefore does not harden the sweep, it
/// disables it outright, and `sweep_orphans` only tolerates `NotFound`, so
/// every pass would fail and no staged plaintext would ever be reclaimed.
/// [`FsOps::open_root`] omits it on the same path shape for the same reason.
///
/// What still holds: `FILE_OPEN_REPARSE_POINT` opens a reparse-point root as
/// itself rather than following it, and [`validate_sweep_root`] then refuses
/// it. Intermediate components are resolved by the object manager and the
/// filesystem, which matches the trust boundary the rest of the crate
/// documents — a sweep root, like an [`crate::AtomicDirectory`] root, is
/// caller-selected and resolved once.
fn open_sweep_root(directory: &Path) -> io::Result<OwnedHandle> {
    let nt_path = NtAbsolutePath::parse(directory)?;
    nt_open_file(
        null_mut(),
        nt_path.as_slice(),
        FILE_LIST_DIRECTORY | FILE_TRAVERSE | FILE_READ_ATTRIBUTES | SYNCHRONIZE,
        FILE_SHARE_READ | FILE_SHARE_WRITE,
        FILE_DIRECTORY_FILE | FILE_OPEN_REPARSE_POINT | FILE_SYNCHRONOUS_IO_NONALERT,
        OBJ_CASE_INSENSITIVE,
    )
}

fn validate_sweep_root(handle: HANDLE) -> io::Result<()> {
    let attributes: FILE_ATTRIBUTE_TAG_INFO = query_file_information(handle, FileAttributeTagInfo)?;
    if attributes.FileAttributes & FILE_ATTRIBUTE_DIRECTORY == 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "orphan sweep root is not a directory",
        ));
    }
    if attributes.FileAttributes & FILE_ATTRIBUTE_REPARSE_POINT != 0 || attributes.ReparseTag != 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "orphan sweep root is a reparse point",
        ));
    }
    Ok(())
}

/// Enumerates sweep candidates, descending to a weaker class where required.
///
/// FAT, FAT32, and exFAT do not implement the extended class: the reference
/// `fastfat` driver's `FatQueryDirectory` handles `FileDirectoryInformation`,
/// `FileFullDirectoryInformation`, `FileIdFullDirectoryInformation`,
/// `FileNamesInformation`, `FileBothDirectoryInformation`, and
/// `FileIdBothDirectoryInformation`, then falls through to
/// `STATUS_INVALID_INFO_CLASS`. Without a fallback the sweep fails outright on
/// removable media, which is where leaked plaintext matters most.
///
/// The descent is attempted only when the very first request is refused, so a
/// mid-enumeration failure still yields the partial report rather than
/// restarting under a different class.
fn enumerate_candidates(
    root: HANDLE,
    role_mask: u32,
    report: &mut SweepReport,
) -> io::Result<Vec<Candidate>> {
    match enumerate_with_scheme(root, role_mask, report, EnumerationScheme::ExtendedFileId) {
        Ok(candidates) => Ok(candidates),
        Err(error) if enumeration_class_unrecognized(&error) => {
            enumerate_with_scheme(root, role_mask, report, EnumerationScheme::BothFileId)
        }
        Err(error) => Err(error),
    }
}

/// Returns whether the volume rejected the information class itself.
///
/// `STATUS_INVALID_INFO_CLASS` and `STATUS_INVALID_PARAMETER` reach Win32 as
/// `ERROR_INVALID_PARAMETER`; `ERROR_INVALID_LEVEL` is the documented spelling
/// when a class is not valid for the request. Every code here selects a strictly
/// more compatible class, so a broad set only widens support.
fn enumeration_class_unrecognized(error: &io::Error) -> bool {
    matches!(
        error.raw_os_error(),
        Some(code) if code == ERROR_INVALID_PARAMETER as i32
            || code == ERROR_INVALID_LEVEL as i32
            || code == ERROR_NOT_SUPPORTED as i32
            || code == ERROR_INVALID_FUNCTION as i32
    )
}

fn enumerate_with_scheme(
    root: HANDLE,
    role_mask: u32,
    report: &mut SweepReport,
    scheme: EnumerationScheme,
) -> io::Result<Vec<Candidate>> {
    let word_count = ENUMERATION_BUFFER_BYTES.div_ceil(size_of::<usize>());
    let mut storage = vec![0_usize; word_count];
    let mut candidates = Vec::new();
    let mut restart = true;

    loop {
        storage.fill(0);
        let information_class = match (scheme, restart) {
            (EnumerationScheme::ExtendedFileId, true) => FileIdExtdDirectoryRestartInfo,
            (EnumerationScheme::ExtendedFileId, false) => FileIdExtdDirectoryInfo,
            (EnumerationScheme::BothFileId, true) => FileIdBothDirectoryRestartInfo,
            (EnumerationScheme::BothFileId, false) => FileIdBothDirectoryInfo,
        };
        // SAFETY: `root` is a live directory handle, and `storage` exposes a
        // writable, suitably aligned buffer for the duration of the call.
        let succeeded = unsafe {
            GetFileInformationByHandleEx(
                root,
                information_class,
                storage.as_mut_ptr().cast::<c_void>(),
                u32::try_from(storage.len() * size_of::<usize>())
                    .expect("enumeration buffer size fits in u32"),
            )
        };
        if succeeded == 0 {
            // SAFETY: `GetLastError` is read immediately after the failed call.
            let code = unsafe { GetLastError() };
            if matches!(code, ERROR_NO_MORE_FILES | ERROR_HANDLE_EOF) {
                break;
            }
            let error = io::Error::from_raw_os_error(code as i32);
            if restart {
                return Err(error);
            }
            report.record_failure(&error);
            break;
        }
        restart = false;

        // SAFETY: `storage` remains allocated and initialized for the returned
        // slice, whose byte length is exactly its allocation length.
        let bytes = unsafe {
            std::slice::from_raw_parts(
                storage.as_ptr().cast::<u8>(),
                storage.len() * size_of::<usize>(),
            )
        };
        let entries = match parse_directory_buffer(bytes, scheme) {
            Ok(entries) => entries,
            Err(error) => {
                // The enumeration syscall already succeeded, so preserve all
                // work observed so far and return the parser failure in the
                // partial report instead of discarding it as a root error.
                report.record_failure(&error);
                break;
            }
        };
        for entry in entries {
            if is_dot_entry(&entry.name) {
                continue;
            }
            report.entries_seen = report.entries_seen.saturating_add(1);

            let Ok(name) = String::from_utf16(&entry.name) else {
                continue;
            };
            let Some(artifact) = parse_temporary_artifact_name(&name) else {
                continue;
            };
            if artifact.role.mask_bit() & role_mask == 0 {
                continue;
            }
            report.candidate_names = report.candidate_names.saturating_add(1);
            if artifact.protocol != TemporaryArtifactProtocol::FileLeaseV1
                || artifact.entry_kind != TemporaryArtifactEntryKind::Data
            {
                // Recognize versioned readers before writers emit them, but
                // retain the names until their protocol-specific lease can be
                // established on this platform.
                report.skipped_unsafe = report.skipped_unsafe.saturating_add(1);
                continue;
            }

            if entry.file_attributes & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT)
                != 0
                || entry.reparse_tag != 0
            {
                report.skipped_unsafe = report.skipped_unsafe.saturating_add(1);
                continue;
            }
            candidates.push(Candidate {
                name: entry.name,
                file_id: entry.file_id,
            });
        }
    }
    Ok(candidates)
}

struct DirectoryEntry {
    name: Vec<u16>,
    file_attributes: u32,
    /// Always zero under [`EnumerationScheme::BothFileId`], whose record has no
    /// such field. The authoritative reparse check is the per-handle
    /// `FileAttributeTagInfo` query in [`inspect_and_remove`]; this is only a
    /// pre-filter, and the FAT family supports no reparse points at all.
    reparse_tag: u32,
    file_id: CandidateFileId,
}

/// Fixed fields of one enumeration record, decoded for either scheme.
struct DecodedRecord {
    name_offset: usize,
    name_length: usize,
    next_entry_offset: u32,
    file_attributes: u32,
    reparse_tag: u32,
    file_id: CandidateFileId,
}

/// Copies one record's fixed fields out of the enumeration buffer.
///
/// Bounds the named fields, not `size_of` the struct. The kernel writes a record
/// as its fields plus the name, with no trailing struct padding, so the final
/// record can legitimately end inside the padding that follows `FileName` in the
/// Rust type. Requiring the padded size rejects a valid last record — a short
/// name landing near the end of the buffer — as a malformed enumeration.
///
/// # Safety
///
/// `T` must be a `#[repr(C)]` aggregate of integers and integer arrays, so that
/// every bit pattern is a valid value and zeroed storage is a valid `T`. Both
/// enumeration records satisfy this.
unsafe fn copy_record<T>(bytes: &[u8], offset: usize, name_offset: usize) -> io::Result<T> {
    let header_end = offset
        .checked_add(name_offset)
        .ok_or_else(malformed_enumeration)?;
    if header_end > bytes.len() {
        return Err(malformed_enumeration());
    }
    // Copy only the bytes the record actually occupies into zeroed storage, so
    // the trailing padding is never read out of the buffer. Every field the
    // caller uses precedes `FileName`; the name is read separately.
    let available = bytes.len() - offset;
    let copied = available.min(size_of::<T>());
    let mut record = std::mem::MaybeUninit::<T>::zeroed();
    // SAFETY: `record` is writable storage of exactly the destination type, the
    // source has `copied` readable bytes from `offset`, and `copied` never
    // exceeds the destination size. The regions cannot overlap because `record`
    // is a fresh local. The caller guarantees every bit pattern is a valid `T`.
    unsafe {
        std::ptr::copy_nonoverlapping(
            bytes.as_ptr().add(offset),
            record.as_mut_ptr().cast::<u8>(),
            copied,
        );
        Ok(record.assume_init())
    }
}

fn decode_record(
    bytes: &[u8],
    offset: usize,
    scheme: EnumerationScheme,
) -> io::Result<DecodedRecord> {
    match scheme {
        EnumerationScheme::ExtendedFileId => {
            let name_offset = offset_of!(FILE_ID_EXTD_DIR_INFO, FileName);
            // SAFETY: `FILE_ID_EXTD_DIR_INFO` is a `#[repr(C)]` aggregate of
            // integers and integer arrays.
            let header: FILE_ID_EXTD_DIR_INFO = unsafe { copy_record(bytes, offset, name_offset) }?;
            Ok(DecodedRecord {
                name_offset,
                name_length: usize::try_from(header.FileNameLength)
                    .map_err(|_| malformed_enumeration())?,
                next_entry_offset: header.NextEntryOffset,
                file_attributes: header.FileAttributes,
                reparse_tag: header.ReparsePointTag,
                file_id: CandidateFileId::Extended(header.FileId.Identifier),
            })
        }
        EnumerationScheme::BothFileId => {
            let name_offset = offset_of!(FILE_ID_BOTH_DIR_INFO, FileName);
            // SAFETY: `FILE_ID_BOTH_DIR_INFO` is a `#[repr(C)]` aggregate of
            // integers and integer arrays.
            let header: FILE_ID_BOTH_DIR_INFO = unsafe { copy_record(bytes, offset, name_offset) }?;
            Ok(DecodedRecord {
                name_offset,
                name_length: usize::try_from(header.FileNameLength)
                    .map_err(|_| malformed_enumeration())?,
                next_entry_offset: header.NextEntryOffset,
                file_attributes: header.FileAttributes,
                // This record carries no reparse tag; see `DirectoryEntry`.
                reparse_tag: 0,
                file_id: CandidateFileId::Both(header.FileId),
            })
        }
    }
}

fn parse_directory_buffer(
    bytes: &[u8],
    scheme: EnumerationScheme,
) -> io::Result<Vec<DirectoryEntry>> {
    let mut entries = Vec::new();
    let mut offset = 0_usize;

    loop {
        let record = decode_record(bytes, offset, scheme)?;
        let name_offset = record.name_offset;
        let name_length = record.name_length;
        if name_length == 0 || name_length % size_of::<u16>() != 0 {
            return Err(malformed_enumeration());
        }
        let name_start = offset
            .checked_add(name_offset)
            .ok_or_else(malformed_enumeration)?;
        let name_end = name_start
            .checked_add(name_length)
            .ok_or_else(malformed_enumeration)?;
        if name_end > bytes.len() {
            return Err(malformed_enumeration());
        }
        let mut name = Vec::with_capacity(name_length / size_of::<u16>());
        let (code_units, remainder) = bytes[name_start..name_end].as_chunks::<2>();
        debug_assert!(remainder.is_empty());
        for code_unit in code_units {
            name.push(u16::from_ne_bytes(*code_unit));
        }
        entries.push(DirectoryEntry {
            name,
            file_attributes: record.file_attributes,
            reparse_tag: record.reparse_tag,
            file_id: record.file_id,
        });

        let next =
            usize::try_from(record.next_entry_offset).map_err(|_| malformed_enumeration())?;
        if next == 0 {
            break;
        }
        if next < name_offset + name_length {
            return Err(malformed_enumeration());
        }
        offset = offset.checked_add(next).ok_or_else(malformed_enumeration)?;
        if offset >= bytes.len() {
            return Err(malformed_enumeration());
        }
    }
    Ok(entries)
}

fn inspect_and_remove(
    root: HANDLE,
    candidate: &Candidate,
    now: u64,
    minimum_age: u64,
) -> CandidateOutcome {
    // Opened through the status-preserving entry point: a candidate that a
    // concurrent sweeper or transaction has already marked for deletion is
    // still enumerable but no longer openable, and that state is only
    // distinguishable from a real permission failure before the NTSTATUS is
    // converted. Treating it as an inspection failure would report routine
    // concurrency as a persistent sweep error.
    let handle = match nt_open_file_status(
        root,
        &candidate.name,
        DELETE | FILE_READ_ATTRIBUTES | SYNCHRONIZE,
        0,
        FILE_NON_DIRECTORY_FILE | FILE_OPEN_REPARSE_POINT | FILE_SYNCHRONOUS_IO_NONALERT,
        OBJ_DONT_REPARSE,
    ) {
        Ok(handle) => handle,
        Err(status) if status == STATUS_DELETE_PENDING => return CandidateOutcome::Changed,
        Err(status) => return classify_candidate_open_error(nt_status_to_io_error(status)),
    };

    let attributes: FILE_ATTRIBUTE_TAG_INFO =
        match query_file_information(handle.as_raw(), FileAttributeTagInfo) {
            Ok(attributes) => attributes,
            Err(error) => return CandidateOutcome::InspectionFailed(error),
        };
    if attributes.FileAttributes & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT) != 0
        || attributes.ReparseTag != 0
    {
        return CandidateOutcome::Unsafe;
    }

    let standard: FileStandardInfoBytes =
        match query_file_information(handle.as_raw(), FileStandardInfo) {
            Ok(standard) => standard,
            Err(error) => return CandidateOutcome::InspectionFailed(error),
        };
    if standard.is_directory() {
        return CandidateOutcome::Unsafe;
    }
    if standard.is_delete_pending() {
        return CandidateOutcome::Changed;
    }

    match handle_file_id(handle.as_raw(), candidate.file_id) {
        Ok(identity) if identity == candidate.file_id => {}
        Ok(_) => return CandidateOutcome::Changed,
        Err(error) => return CandidateOutcome::InspectionFailed(error),
    }

    let basic: FILE_BASIC_INFO = match query_file_information(handle.as_raw(), FileBasicInfo) {
        Ok(basic) => basic,
        Err(error) => return CandidateOutcome::InspectionFailed(error),
    };
    let latest_activity = match latest_activity_time(&basic) {
        Ok(latest_activity) => latest_activity,
        Err(error) => return CandidateOutcome::InspectionFailed(error),
    };
    if latest_activity > now || now - latest_activity < minimum_age {
        return CandidateOutcome::Young;
    }

    match mark_delete_on_close(handle.as_raw()) {
        Ok(()) => {
            drop(handle);
            CandidateOutcome::Removed
        }
        Err(error) => CandidateOutcome::RemovalFailed(error),
    }
}

/// Reads an open candidate's reference number through `expected`'s class.
///
/// The class must match the one enumeration used, or every candidate would
/// compare unequal and nothing would ever be reclaimed. `FileIdInfo` is
/// unimplemented on the FAT family — `fastfat`'s `FatCommonQueryInformation`
/// has no `FileIdInformation` case — so the 64-bit reference number is read with
/// `GetFileInformationByHandle`, whose `nFileIndex` is the same file reference
/// number that `FileIdBothDirectoryInformation` reports.
fn handle_file_id(handle: HANDLE, expected: CandidateFileId) -> io::Result<CandidateFileId> {
    match expected {
        CandidateFileId::Extended(_) => {
            let identity: FILE_ID_INFO = query_file_information(handle, FileIdInfo)?;
            Ok(CandidateFileId::Extended(identity.FileId.Identifier))
        }
        CandidateFileId::Both(_) => {
            let mut information = std::mem::MaybeUninit::<BY_HANDLE_FILE_INFORMATION>::uninit();
            // SAFETY: `handle` is a live non-pipe file handle and `information`
            // is writable storage of exactly the out-parameter's type.
            if unsafe { GetFileInformationByHandle(handle, information.as_mut_ptr()) } == 0 {
                // SAFETY: `GetLastError` is read immediately after the failure.
                return Err(io::Error::from_raw_os_error(
                    unsafe { GetLastError() } as i32
                ));
            }
            // SAFETY: A successful call initialized the output structure.
            let information = unsafe { information.assume_init() };
            let index = (u64::from(information.nFileIndexHigh) << 32)
                | u64::from(information.nFileIndexLow);
            Ok(CandidateFileId::Both(index as i64))
        }
    }
}

/// Newest of the timestamps this volume actually maintains.
///
/// `ChangeTime` is optional: `fastfat`'s `FatQueryBasicInfo` zeroes the output
/// and then writes only `LastWriteTime`, `CreationTime`, and `LastAccessTime`,
/// leaving `ChangeTime` at zero, and MS-FSCC 2.4.7 confirms zero is a legal
/// queried value — "A valid time for this field is an integer greater than or
/// equal to 0." (The "0 means do not change" rule applies only when *setting*
/// attributes.) Treating an absent change time as invalid would make every
/// candidate on a FAT volume or an SMB server that reports zero permanently
/// un-sweepable. `LastWriteTime` remains required, since a volume that reports
/// no write time at all cannot support an age decision.
fn latest_activity_time(basic: &FILE_BASIC_INFO) -> io::Result<u64> {
    let last_write = positive_file_time(basic.LastWriteTime, "last-write")?;
    let changed = maintained_file_time(basic.ChangeTime);
    Ok(last_write.max(changed))
}

fn positive_file_time(value: i64, label: &str) -> io::Result<u64> {
    u64::try_from(value)
        .ok()
        .filter(|value| *value != 0)
        .ok_or_else(|| {
            io::Error::new(
                io::ErrorKind::InvalidData,
                format!("candidate has an invalid {label} timestamp"),
            )
        })
}

/// Zero for a timestamp the volume does not maintain, so it loses the `max`.
fn maintained_file_time(value: i64) -> u64 {
    u64::try_from(value).unwrap_or(0)
}

/// Classifies a candidate open failure that has already been converted to a
/// Win32 error.
///
/// Delete-pending is deliberately absent: it is handled at the call site from
/// the raw NTSTATUS, because `RtlNtStatusToDosError` folds
/// `STATUS_DELETE_PENDING` into `ERROR_ACCESS_DENIED` and never produces
/// `ERROR_DELETE_PENDING`. Matching that Win32 code here would be dead code,
/// and matching `ERROR_ACCESS_DENIED` instead would misreport a genuine
/// permission failure as a vanished candidate — the sweeper would then skip a
/// file it should have reported.
fn classify_candidate_open_error(error: io::Error) -> CandidateOutcome {
    match error.raw_os_error().map(|code| code as u32) {
        Some(ERROR_SHARING_VIOLATION | ERROR_LOCK_VIOLATION) => CandidateOutcome::Busy,
        Some(ERROR_FILE_NOT_FOUND | ERROR_PATH_NOT_FOUND | ERROR_NOT_FOUND) => {
            CandidateOutcome::Changed
        }
        Some(ERROR_CANT_ACCESS_FILE | ERROR_REPARSE_POINT_ENCOUNTERED) => CandidateOutcome::Unsafe,
        _ => CandidateOutcome::InspectionFailed(error),
    }
}

fn precise_file_time() -> u64 {
    let mut now = FILETIME::default();
    // SAFETY: `now` points to a writable `FILETIME`.
    unsafe {
        GetSystemTimePreciseAsFileTime(&raw mut now);
    }
    (u64::from(now.dwHighDateTime) << 32) | u64::from(now.dwLowDateTime)
}

fn duration_to_file_time_ticks(duration: Duration) -> u64 {
    let ticks = duration.as_nanos().div_ceil(100);
    u64::try_from(ticks).unwrap_or(u64::MAX)
}

fn malformed_enumeration() -> io::Error {
    io::Error::new(
        io::ErrorKind::InvalidData,
        "Windows returned a malformed directory enumeration record",
    )
}

fn is_dot_entry(name: &[u16]) -> bool {
    name == [b'.' as u16] || name == [b'.' as u16, b'.' as u16]
}

#[cfg(test)]
mod tests {
    use std::fs::OpenOptions;

    use super::*;
    use crate::naming::{TemporaryFileRole, new_file_lease_artifact_name};

    /// Builds one enumeration record exactly as the kernel lays it out: the
    /// named fields followed by the name, with no trailing struct padding.
    fn synthetic_record(name: &str, next_entry_offset: u32, scheme: EnumerationScheme) -> Vec<u8> {
        let (name_offset, next_at, length_at) = match scheme {
            EnumerationScheme::ExtendedFileId => (
                offset_of!(FILE_ID_EXTD_DIR_INFO, FileName),
                offset_of!(FILE_ID_EXTD_DIR_INFO, NextEntryOffset),
                offset_of!(FILE_ID_EXTD_DIR_INFO, FileNameLength),
            ),
            EnumerationScheme::BothFileId => (
                offset_of!(FILE_ID_BOTH_DIR_INFO, FileName),
                offset_of!(FILE_ID_BOTH_DIR_INFO, NextEntryOffset),
                offset_of!(FILE_ID_BOTH_DIR_INFO, FileNameLength),
            ),
        };
        let units: Vec<u16> = name.encode_utf16().collect();
        let name_bytes = units.len() * size_of::<u16>();
        let mut record = vec![0_u8; name_offset + name_bytes];

        record[next_at..next_at + size_of::<u32>()]
            .copy_from_slice(&next_entry_offset.to_ne_bytes());
        record[length_at..length_at + size_of::<u32>()]
            .copy_from_slice(&(name_bytes as u32).to_ne_bytes());
        for (index, unit) in units.iter().enumerate() {
            let at = name_offset + index * size_of::<u16>();
            record[at..at + size_of::<u16>()].copy_from_slice(&unit.to_ne_bytes());
        }
        record
    }

    /// The FAT-family record has its own layout, so the framing logic must be
    /// correct for it too — a wrong `FileName` offset would silently mis-decode
    /// every name on removable media rather than fail loudly.
    #[test]
    fn both_scheme_records_are_framed_and_decoded() {
        let record = synthetic_record("kg.tmp", 0, EnumerationScheme::BothFileId);
        let entries = parse_directory_buffer(&record, EnumerationScheme::BothFileId)
            .expect("a valid FileIdBothDirectoryInfo record must parse");

        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].name, "kg.tmp".encode_utf16().collect::<Vec<_>>());
        assert!(matches!(entries[0].file_id, CandidateFileId::Both(_)));
        assert_eq!(
            entries[0].reparse_tag, 0,
            "this class carries no reparse tag"
        );

        let truncated = &record[..offset_of!(FILE_ID_BOTH_DIR_INFO, FileName) - 1];
        assert!(
            parse_directory_buffer(truncated, EnumerationScheme::BothFileId).is_err(),
            "a record missing part of its fixed fields must stay malformed",
        );
    }

    /// An enumeration value must only ever be compared against a per-handle
    /// value read through the same class; the widths are unrelated, so the
    /// variants must not alias.
    #[test]
    fn candidate_file_ids_never_alias_across_classes() {
        let mut zero_extended = [0_u8; 16];
        zero_extended[..8].copy_from_slice(&7_i64.to_ne_bytes());

        assert_ne!(
            CandidateFileId::Both(7),
            CandidateFileId::Extended(zero_extended)
        );
        assert_eq!(CandidateFileId::Both(7), CandidateFileId::Both(7));
        assert_ne!(CandidateFileId::Both(7), CandidateFileId::Both(8));
    }

    /// Only a class rejection descends to the weaker enumeration. A genuine
    /// failure must propagate, or a permission problem would be retried as a
    /// capability problem and reported as a clean empty sweep.
    #[test]
    fn only_class_rejections_descend_to_the_weaker_enumeration() {
        for code in [
            ERROR_INVALID_PARAMETER,
            ERROR_INVALID_LEVEL,
            ERROR_NOT_SUPPORTED,
            ERROR_INVALID_FUNCTION,
        ] {
            assert!(
                enumeration_class_unrecognized(&io::Error::from_raw_os_error(code as i32)),
                "{code} must select FileIdBothDirectoryInfo"
            );
        }
        for code in [
            ERROR_SHARING_VIOLATION,
            ERROR_FILE_NOT_FOUND,
            ERROR_PATH_NOT_FOUND,
            ERROR_CANT_ACCESS_FILE,
        ] {
            assert!(
                !enumeration_class_unrecognized(&io::Error::from_raw_os_error(code as i32)),
                "{code} must propagate as a sweep failure"
            );
        }
    }

    /// `fastfat` never writes `ChangeTime`, and MS-FSCC allows zero as a
    /// queried value. Rejecting it made every candidate on a FAT volume
    /// permanently un-sweepable while reporting a persistent inspection
    /// failure.
    #[test]
    fn absent_change_time_does_not_block_the_age_decision() {
        let mut basic = FILE_BASIC_INFO {
            CreationTime: 0,
            LastAccessTime: 0,
            LastWriteTime: 4_000,
            ChangeTime: 0,
            FileAttributes: 0,
        };
        assert_eq!(
            latest_activity_time(&basic).expect("an absent change time must not fail"),
            4_000,
        );

        basic.ChangeTime = 9_000;
        assert_eq!(
            latest_activity_time(&basic).expect("a present change time must be used"),
            9_000,
            "the newest maintained timestamp must win"
        );

        basic.LastWriteTime = 0;
        assert!(
            latest_activity_time(&basic).is_err(),
            "a volume reporting no write time cannot support an age decision"
        );
    }

    /// A short-named final record can end before the padded size of the Rust
    /// struct. Requiring `size_of` would reject a buffer the kernel is
    /// entitled to produce, aborting the sweep and recording a false
    /// "malformed enumeration" failure.
    #[test]
    fn final_record_ending_inside_struct_padding_is_accepted() {
        let record = synthetic_record("a", 0, EnumerationScheme::ExtendedFileId);
        assert!(
            record.len() < size_of::<FILE_ID_EXTD_DIR_INFO>(),
            "the fixture must reproduce a record shorter than the padded struct",
        );

        let entries = parse_directory_buffer(&record, EnumerationScheme::ExtendedFileId)
            .expect("a valid final record must not be rejected as malformed");

        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].name, "a".encode_utf16().collect::<Vec<_>>());
    }

    #[test]
    fn record_truncated_before_its_named_fields_is_still_rejected() {
        let record = synthetic_record("a", 0, EnumerationScheme::ExtendedFileId);
        let truncated = &record[..offset_of!(FILE_ID_EXTD_DIR_INFO, FileName) - 1];

        assert!(
            parse_directory_buffer(truncated, EnumerationScheme::ExtendedFileId).is_err(),
            "a record missing part of its fixed fields must stay malformed",
        );
    }

    #[test]
    fn record_promising_a_name_beyond_the_buffer_is_rejected() {
        let mut record = synthetic_record("ab", 0, EnumerationScheme::ExtendedFileId);
        let length_at = offset_of!(FILE_ID_EXTD_DIR_INFO, FileNameLength);
        record[length_at..length_at + size_of::<u32>()].copy_from_slice(&64_u32.to_ne_bytes());

        assert!(
            parse_directory_buffer(&record, EnumerationScheme::ExtendedFileId).is_err(),
            "a name length past the end of the buffer must stay malformed",
        );
    }

    fn test_directory() -> std::path::PathBuf {
        let mut nonce = [0_u8; 8];
        getrandom::fill(&mut nonce).expect("test nonce generation must succeed");
        let name: String = nonce.iter().map(|byte| format!("{byte:02x}")).collect();
        let path = std::env::temp_dir().join(format!("keyguard-windows-sweep-test-{name}"));
        std::fs::create_dir_all(&path).expect("test directory must be created");
        path
    }

    fn options(role_mask: u32) -> SweepOptions {
        SweepOptions {
            older_than: Duration::ZERO,
            role_mask,
        }
    }

    #[test]
    fn sweep_removes_only_selected_canonical_artifacts() {
        let directory = test_directory();
        let selected = directory.join(
            new_file_lease_artifact_name(TemporaryFileRole::New).expect("name must generate"),
        );
        let other_role = directory.join(
            new_file_lease_artifact_name(TemporaryFileRole::Scratch).expect("name must generate"),
        );
        let user_file = directory.join("vault.kdbx");
        std::fs::write(&selected, b"stale").expect("write must succeed");
        std::fs::write(&other_role, b"stale").expect("write must succeed");
        std::fs::write(&user_file, b"precious").expect("write must succeed");

        let report = sweep_orphans(&directory, options(TemporaryFileRole::New.mask_bit()))
            .expect("sweep must succeed");

        assert_eq!(report.candidate_names, 1);
        assert_eq!(report.removed, 1);
        assert!(report.candidate_partition_holds());
        assert!(!selected.exists());
        assert!(other_role.exists());
        assert!(user_file.exists());
        let _ = std::fs::remove_dir_all(&directory);
    }

    #[test]
    fn open_candidate_is_busy_until_the_live_handle_closes() {
        let directory = test_directory();
        let candidate = directory.join(
            new_file_lease_artifact_name(TemporaryFileRole::New).expect("name must generate"),
        );
        let live = OpenOptions::new()
            .create_new(true)
            .read(true)
            .write(true)
            .open(&candidate)
            .expect("live candidate must open");

        let busy_report =
            sweep_orphans(&directory, options(u32::MAX)).expect("busy sweep must succeed");
        assert_eq!(busy_report.candidate_names, 1);
        assert_eq!(busy_report.skipped_busy, 1);
        assert!(candidate.exists());

        drop(live);
        let removed_report =
            sweep_orphans(&directory, options(u32::MAX)).expect("second sweep must succeed");
        assert_eq!(removed_report.removed, 1);
        assert!(!candidate.exists());
        let _ = std::fs::remove_dir_all(&directory);
    }

    #[test]
    fn canonical_symlink_is_never_followed_or_removed_as_a_regular_file() {
        let directory = test_directory();
        let target = directory.join("target.bin");
        let candidate = directory.join(
            new_file_lease_artifact_name(TemporaryFileRole::New).expect("name must generate"),
        );
        std::fs::write(&target, b"precious").expect("target write must succeed");
        if let Err(error) = std::os::windows::fs::symlink_file(&target, &candidate) {
            if error.kind() == io::ErrorKind::PermissionDenied {
                let _ = std::fs::remove_dir_all(&directory);
                return;
            }
            panic!("symlink creation failed unexpectedly: {error}");
        }

        let report =
            sweep_orphans(&directory, options(u32::MAX)).expect("symlink sweep must succeed");
        assert_eq!(report.candidate_names, 1);
        assert_eq!(report.skipped_unsafe, 1);
        assert_eq!(std::fs::read(&target).unwrap(), b"precious");
        assert!(candidate.exists());
        let _ = std::fs::remove_dir_all(&directory);
    }

    #[test]
    fn versioned_artifacts_are_recognized_but_not_removed_without_their_lease() {
        let directory = test_directory();
        let names = [
            ".kg-tmp-v1d-n-123e4567-e89b-42d3-a456-426614174000.tmp",
            ".kg-tmp-v1s-s-123e4567-e89b-42d3-a456-426614174001.tmp",
            ".kg-tmp-v1s-s-123e4567-e89b-42d3-a456-426614174001.lease",
        ];
        for name in names {
            std::fs::write(directory.join(name), b"reserved").expect("write versioned artifact");
        }

        let report = sweep_orphans(&directory, options(u32::MAX)).expect("sweep must succeed");

        assert_eq!(report.candidate_names, 3);
        assert_eq!(report.skipped_unsafe, 3);
        assert!(report.candidate_partition_holds());
        for name in names {
            assert!(directory.join(name).exists());
        }
        let _ = std::fs::remove_dir_all(&directory);
    }

    #[test]
    fn change_time_participates_in_the_age_boundary() {
        let basic = FILE_BASIC_INFO {
            LastWriteTime: 100,
            ChangeTime: 200,
            ..FILE_BASIC_INFO::default()
        };

        assert_eq!(
            latest_activity_time(&basic).expect("timestamps must parse"),
            200,
        );
    }
}
