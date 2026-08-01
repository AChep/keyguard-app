//! Shared Windows NT handle and path primitives.
#![cfg(windows)]

use std::{
    ffi::{OsStr, c_void},
    io,
    mem::{ManuallyDrop, size_of, size_of_val},
    os::windows::ffi::OsStrExt as _,
    path::Path,
    ptr::{null, null_mut},
};

use windows_sys::{
    Wdk::{
        Foundation::OBJECT_ATTRIBUTES,
        Storage::FileSystem::{NtCreateFile, NtOpenFile, NtSetInformationFile},
    },
    Win32::{
        Foundation::{
            CloseHandle, ERROR_INVALID_FUNCTION, ERROR_INVALID_PARAMETER, ERROR_NOT_SUPPORTED,
            GetLastError, HANDLE, NTSTATUS, RtlNtStatusToDosError, UNICODE_STRING,
        },
        Storage::FileSystem::{
            FILE_DISPOSITION_FLAG_DELETE, FILE_DISPOSITION_FLAG_IGNORE_READONLY_ATTRIBUTE,
            FILE_DISPOSITION_FLAG_POSIX_SEMANTICS, FILE_DISPOSITION_INFO, FILE_DISPOSITION_INFO_EX,
            FILE_STANDARD_INFO, FileDispositionInfo, FileDispositionInfoEx,
            GetFileInformationByHandleEx, SetFileInformationByHandle,
        },
        System::IO::IO_STATUS_BLOCK,
    },
};

const BACKSLASH: u16 = b'\\' as u16;

/// `FILE_STANDARD_INFO` with its two `BOOLEAN` fields typed as bytes.
///
/// MS-FSCC 2.4.41 defines `DeletePending` and `Directory` as `BOOLEAN`, in which
/// *any* nonzero byte is TRUE, but `windows-sys` declares both as Rust `bool`.
/// Producing a `bool` from a byte other than 0 or 1 is undefined behavior, and
/// [`query_file_information`] hands the kernel raw storage and then returns the
/// value by move — so a filesystem that writes `0xFF` would create an invalid
/// `bool`, on exactly the fields whose `if` branches the compiler is entitled to
/// optimize using the 0/1 invariant. Reachable rather than theoretical:
/// [`NtAbsolutePath::parse`] accepts UNC paths, so a third-party SMB server
/// supplies these bytes.
#[repr(C)]
#[derive(Clone, Copy, Default)]
pub(crate) struct FileStandardInfoBytes {
    allocation_size: i64,
    end_of_file: i64,
    number_of_links: u32,
    delete_pending: u8,
    directory: u8,
}

// The buffer size passed to the kernel is `size_of` this mirror, so a layout
// drift from the real structure must fail the build rather than truncate the
// request.
const _: () = assert!(size_of::<FileStandardInfoBytes>() == size_of::<FILE_STANDARD_INFO>());

impl FileStandardInfoBytes {
    /// Whether the object has a delete pending, per `BOOLEAN` semantics.
    pub(crate) const fn is_delete_pending(self) -> bool {
        self.delete_pending != 0
    }

    /// Whether the object is a directory, per `BOOLEAN` semantics.
    pub(crate) const fn is_directory(self) -> bool {
        self.directory != 0
    }
}

/// A file object with a pending delete: still enumerable, not openable.
///
/// Callers must test this before conversion. `RtlNtStatusToDosError` maps it
/// onto `ERROR_ACCESS_DENIED`, indistinguishable from a real permission
/// failure, and no NTSTATUS maps to `ERROR_DELETE_PENDING` at all — testing
/// for that Win32 code can never match.
pub(crate) const STATUS_DELETE_PENDING: NTSTATUS = 0xC000_0056_u32 as NTSTATUS;
const STATUS_UNSUCCESSFUL: NTSTATUS = 0xC000_0001_u32 as NTSTATUS;
const STATUS_NAME_TOO_LONG: NTSTATUS = 0xC000_0106_u32 as NTSTATUS;

/// Absolute NT object-manager path parsed from an accepted Windows path form.
#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct NtAbsolutePath(Box<[u16]>);

impl NtAbsolutePath {
    /// Parses an absolute drive, UNC, or volume path.
    ///
    /// Forward slashes are normalized and trailing separators beyond the
    /// filesystem root are removed. Relative paths, DOS device paths, NULs,
    /// repeated separators, and dot components are rejected.
    ///
    /// # Errors
    ///
    /// Returns [`io::ErrorKind::InvalidInput`] when `path` is not one of the
    /// supported absolute path forms or cannot be represented by
    /// [`UNICODE_STRING`].
    pub(crate) fn parse(path: &Path) -> io::Result<Self> {
        let mut path: Vec<u16> = path.as_os_str().encode_wide().collect();
        if path.contains(&0) {
            return Err(invalid_absolute_path());
        }
        for code_unit in &mut path {
            if *code_unit == b'/' as u16 {
                *code_unit = BACKSLASH;
            }
        }

        let nt_path = if starts_with_ascii_case_insensitive(&path, r"\\?\UNC\") {
            let mut suffix = path[r"\\?\UNC\".len()..].to_vec();
            let root_end = validate_unc_suffix(&suffix)?;
            trim_trailing_separators(&mut suffix, root_end);
            prefixed_nt_path(r"\??\UNC\", &suffix)
        } else if starts_with_ascii_case_insensitive(&path, r"\\?\") {
            let mut suffix = path[r"\\?\".len()..].to_vec();
            let root_end = if is_drive_absolute(&suffix) {
                validate_relative_segments(&suffix[3..])?;
                3
            } else {
                validate_volume_suffix(&suffix)?
            };
            trim_trailing_separators(&mut suffix, root_end);
            prefixed_nt_path(r"\??\", &suffix)
        } else if path.starts_with(&[BACKSLASH, BACKSLASH]) {
            if path.get(2) == Some(&(b'.' as u16)) {
                return Err(invalid_absolute_path());
            }
            let mut suffix = path[2..].to_vec();
            let root_end = validate_unc_suffix(&suffix)?;
            trim_trailing_separators(&mut suffix, root_end);
            prefixed_nt_path(r"\??\UNC\", &suffix)
        } else if is_drive_absolute(&path) {
            validate_relative_segments(&path[3..])?;
            trim_trailing_separators(&mut path, 3);
            prefixed_nt_path(r"\??\", &path)
        } else {
            return Err(invalid_absolute_path());
        };

        if nt_path
            .len()
            .checked_mul(size_of::<u16>())
            .is_none_or(|length| length > usize::from(u16::MAX))
        {
            return Err(path_too_long());
        }
        Ok(Self(nt_path.into_boxed_slice()))
    }

    pub(crate) fn as_slice(&self) -> &[u16] {
        &self.0
    }
}

/// One validated name relative to an already-open directory.
#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct NtRelativeName(Box<[u16]>);

impl NtRelativeName {
    /// Parses a single relative Windows filename.
    ///
    /// # Errors
    ///
    /// Returns [`io::ErrorKind::InvalidInput`] for empty names, dot
    /// components, separators, alternate-data-stream separators, NULs, or a
    /// name too long for [`UNICODE_STRING`].
    pub(crate) fn parse(name: &str) -> io::Result<Self> {
        Self::parse_os(OsStr::new(name))
    }

    /// Parses one relative Windows filename without narrowing its UTF-16
    /// spelling through UTF-8.
    pub(crate) fn parse_os(name: &OsStr) -> io::Result<Self> {
        let name: Vec<u16> = name.encode_wide().collect();
        if name.is_empty()
            || name == [b'.' as u16]
            || name == [b'.' as u16, b'.' as u16]
            || name.iter().any(|code_unit| {
                *code_unit == 0
                    || *code_unit == BACKSLASH
                    || *code_unit == b'/' as u16
                    || *code_unit == b':' as u16
            })
            || name
                .len()
                .checked_mul(size_of::<u16>())
                .is_none_or(|length| length > usize::from(u16::MAX))
        {
            return Err(invalid_relative_name());
        }
        Ok(Self(name.into_boxed_slice()))
    }

    pub(crate) fn as_slice(&self) -> &[u16] {
        &self.0
    }
}

/// Uniquely owned closable Windows handle.
#[derive(Debug)]
pub(crate) struct OwnedHandle(HANDLE);

// SAFETY: Windows kernel handles are process-wide capabilities. Moving this
// uniquely-owned wrapper between threads does not invalidate the handle or
// transfer any thread-affine state.
unsafe impl Send for OwnedHandle {}

// SAFETY: Windows permits concurrent use of a live handle. Callers remain
// responsible for any operation-level synchronization; ownership and the
// single `CloseHandle` stay with this wrapper.
unsafe impl Sync for OwnedHandle {}

impl OwnedHandle {
    pub(crate) const fn as_raw(&self) -> HANDLE {
        self.0
    }

    pub(crate) fn into_raw(self) -> HANDLE {
        let this = ManuallyDrop::new(self);
        this.0
    }
}

impl Drop for OwnedHandle {
    fn drop(&mut self) {
        // SAFETY: `OwnedHandle` is constructed only from a successful native
        // open call and closes its uniquely-owned handle exactly once here.
        unsafe {
            CloseHandle(self.0);
        }
    }
}

/// Opens an NT path, optionally relative to an existing directory handle.
///
/// # Errors
///
/// Returns the Win32 error corresponding to the unsuccessful NT status.
pub(crate) fn nt_open_file_status(
    root: HANDLE,
    name: &[u16],
    desired_access: u32,
    share_access: u32,
    open_options: u32,
    object_attributes: u32,
) -> Result<OwnedHandle, NTSTATUS> {
    let byte_length = name
        .len()
        .checked_mul(size_of::<u16>())
        .and_then(|length| u16::try_from(length).ok())
        .ok_or(STATUS_NAME_TOO_LONG)?;
    let unicode_name = UNICODE_STRING {
        Length: byte_length,
        MaximumLength: byte_length,
        Buffer: name.as_ptr().cast_mut(),
    };
    let attributes = OBJECT_ATTRIBUTES {
        Length: u32::try_from(size_of::<OBJECT_ATTRIBUTES>())
            .expect("object attributes size fits in u32"),
        RootDirectory: root,
        ObjectName: &raw const unicode_name,
        Attributes: object_attributes,
        SecurityDescriptor: null(),
        SecurityQualityOfService: null(),
    };
    let mut handle = null_mut();
    let mut io_status = IO_STATUS_BLOCK::default();
    // SAFETY: Every pointer references a live stack value for this synchronous
    // call. The immutable UTF-16 name buffer remains allocated throughout.
    let status = unsafe {
        NtOpenFile(
            &raw mut handle,
            desired_access,
            &raw const attributes,
            &raw mut io_status,
            share_access,
            open_options,
        )
    };
    if status < 0 {
        return Err(status);
    }
    if handle.is_null() {
        return Err(STATUS_UNSUCCESSFUL);
    }
    Ok(OwnedHandle(handle))
}

/// A file open that has already lost its NTSTATUS to Win32 conversion.
///
/// Prefer [`nt_open_file_status`] where the distinction matters:
/// `RtlNtStatusToDosError` is many-to-one, and several statuses a caller may
/// need to tell apart — notably `STATUS_DELETE_PENDING` — collapse onto
/// `ERROR_ACCESS_DENIED`.
pub(crate) fn nt_open_file(
    root: HANDLE,
    name: &[u16],
    desired_access: u32,
    share_access: u32,
    open_options: u32,
    object_attributes: u32,
) -> io::Result<OwnedHandle> {
    nt_open_file_status(
        root,
        name,
        desired_access,
        share_access,
        open_options,
        object_attributes,
    )
    .map_err(nt_status_to_io_error)
}

/// Converts an NTSTATUS failure to its Win32 error.
///
/// The mapping is lossy and irreversible; there is no documented inverse.
pub(crate) fn nt_status_to_io_error(status: NTSTATUS) -> io::Error {
    // SAFETY: `RtlNtStatusToDosError` is a pure status-code conversion.
    let code = unsafe { RtlNtStatusToDosError(status) };
    io::Error::from_raw_os_error(code as i32)
}

/// Parameters for creating a file or directory through [`nt_create_file`].
pub(crate) struct NtCreateOptions {
    pub(crate) desired_access: u32,
    pub(crate) share_access: u32,
    pub(crate) disposition: u32,
    pub(crate) create_options: u32,
    pub(crate) file_attributes: u32,
    pub(crate) object_attributes: u32,
    pub(crate) security_descriptor: *const c_void,
}

/// Creates a file or directory relative to an existing directory handle.
///
/// # Errors
///
/// Returns the Win32 error corresponding to the unsuccessful NT status.
pub(crate) fn nt_create_file(
    root: HANDLE,
    name: &NtRelativeName,
    options: &NtCreateOptions,
) -> io::Result<OwnedHandle> {
    let name = name.as_slice();
    let byte_length =
        u16::try_from(size_of_val(name)).expect("validated relative name length fits in u16");
    let unicode_name = UNICODE_STRING {
        Length: byte_length,
        MaximumLength: byte_length,
        Buffer: name.as_ptr().cast_mut(),
    };
    let attributes = OBJECT_ATTRIBUTES {
        Length: u32::try_from(size_of::<OBJECT_ATTRIBUTES>())
            .expect("object attributes size fits in u32"),
        RootDirectory: root,
        ObjectName: &raw const unicode_name,
        Attributes: options.object_attributes,
        SecurityDescriptor: options.security_descriptor.cast(),
        SecurityQualityOfService: null(),
    };
    let mut handle = null_mut();
    let mut io_status = IO_STATUS_BLOCK::default();
    // SAFETY: Every pointer references a live value for this synchronous
    // call. `security_descriptor`, when non-null, is owned by the caller and
    // documented to outlive this call.
    let status = unsafe {
        NtCreateFile(
            &raw mut handle,
            options.desired_access,
            &raw const attributes,
            &raw mut io_status,
            null(),
            options.file_attributes,
            options.share_access,
            options.disposition,
            options.create_options,
            null(),
            0,
        )
    };
    owned_handle_from_nt_status(handle, status, "NtCreateFile")
}

/// Queries a fixed-size file-information structure from `handle`.
///
/// # Errors
///
/// Returns the Win32 error reported by `GetFileInformationByHandleEx`.
pub(crate) fn query_file_information<T: Default>(
    handle: HANDLE,
    information_class: i32,
) -> io::Result<T> {
    let mut information = T::default();
    // SAFETY: `information` is writable storage of exactly the supplied size,
    // and `handle` remains live for the duration of the synchronous call.
    let succeeded = unsafe {
        GetFileInformationByHandleEx(
            handle,
            information_class,
            (&raw mut information).cast::<c_void>(),
            u32::try_from(size_of::<T>()).expect("file information size fits in u32"),
        )
    };
    if succeeded == 0 {
        // SAFETY: `GetLastError` is read immediately after the failed call.
        return Err(io::Error::from_raw_os_error(
            unsafe { GetLastError() } as i32
        ));
    }
    Ok(information)
}

/// Sets a fixed-size file-information structure on `handle`.
///
/// # Errors
///
/// Returns the Win32 error reported by `SetFileInformationByHandle`.
pub(crate) fn set_file_information<T>(
    handle: HANDLE,
    information_class: i32,
    information: &T,
) -> io::Result<()> {
    // SAFETY: `information` points to a value of exactly the supplied size,
    // and `handle` remains live for the duration of the synchronous call.
    let succeeded = unsafe {
        SetFileInformationByHandle(
            handle,
            information_class,
            std::ptr::from_ref(information).cast::<c_void>(),
            u32::try_from(size_of::<T>()).expect("file information size fits in u32"),
        )
    };
    if succeeded == 0 {
        // SAFETY: `GetLastError` is read immediately after the failed call.
        return Err(io::Error::from_raw_os_error(
            unsafe { GetLastError() } as i32
        ));
    }
    Ok(())
}

/// Marks the exact object referenced by `handle` for deletion when it closes.
///
/// The strongest POSIX-style disposition is attempted first, followed by
/// older Windows disposition forms for filesystem compatibility.
///
/// # Errors
///
/// Returns the first non-capability Win32 error, or the legacy disposition
/// error when no supported form succeeds.
pub(crate) fn mark_delete_on_close(handle: HANDLE) -> io::Result<()> {
    let posix = FILE_DISPOSITION_INFO_EX {
        Flags: FILE_DISPOSITION_FLAG_DELETE
            | FILE_DISPOSITION_FLAG_POSIX_SEMANTICS
            | FILE_DISPOSITION_FLAG_IGNORE_READONLY_ATTRIBUTE,
    };
    match set_file_information(handle, FileDispositionInfoEx, &posix) {
        Ok(()) => return Ok(()),
        Err(error) if disposition_extension_unsupported(&error) => {}
        Err(error) => return Err(error),
    }

    let extended = FILE_DISPOSITION_INFO_EX {
        Flags: FILE_DISPOSITION_FLAG_DELETE | FILE_DISPOSITION_FLAG_IGNORE_READONLY_ATTRIBUTE,
    };
    match set_file_information(handle, FileDispositionInfoEx, &extended) {
        Ok(()) => return Ok(()),
        Err(error) if disposition_extension_unsupported(&error) => {}
        Err(error) => return Err(error),
    }

    let legacy = FILE_DISPOSITION_INFO { DeleteFile: true };
    set_file_information(handle, FileDispositionInfo, &legacy)
}

fn disposition_extension_unsupported(error: &io::Error) -> bool {
    matches!(
        error.raw_os_error().map(|code| code as u32),
        Some(ERROR_INVALID_FUNCTION | ERROR_INVALID_PARAMETER | ERROR_NOT_SUPPORTED)
    )
}

/// Sets a variable-size native file-information buffer on `handle`.
///
/// This is used for native information classes whose Windows structure ends
/// in a flexible array, such as `FILE_RENAME_INFORMATION`.
///
/// # Errors
///
/// Returns [`io::ErrorKind::InvalidInput`] when the buffer length cannot be
/// represented by the native API, or the Win32 error corresponding to the
/// unsuccessful NT status returned by `NtSetInformationFile`.
pub(crate) fn nt_set_file_information_bytes(
    handle: HANDLE,
    information_class: i32,
    information: &[u8],
) -> io::Result<()> {
    let information_length = u32::try_from(information.len()).map_err(|_| {
        io::Error::new(
            io::ErrorKind::InvalidInput,
            "information buffer is too long",
        )
    })?;
    let mut io_status = IO_STATUS_BLOCK::default();
    // SAFETY: `information` is a readable buffer of exactly the supplied
    // length, `io_status` is writable storage of the expected type, and
    // `handle` remains live for the synchronous call.
    let status = unsafe {
        NtSetInformationFile(
            handle,
            &raw mut io_status,
            information.as_ptr().cast::<c_void>(),
            information_length,
            information_class,
        )
    };
    if status < 0 {
        return Err(nt_status_to_io_error(status));
    }
    Ok(())
}

fn validate_unc_suffix(path: &[u16]) -> io::Result<usize> {
    let server_end = path
        .iter()
        .position(|code_unit| *code_unit == BACKSLASH)
        .ok_or_else(invalid_absolute_path)?;
    if server_end == 0 {
        return Err(invalid_absolute_path());
    }
    validate_single_segment(&path[..server_end])?;
    let share_start = server_end + 1;
    let share_end = path[share_start..]
        .iter()
        .position(|code_unit| *code_unit == BACKSLASH)
        .map_or(path.len(), |offset| share_start + offset);
    if share_end == share_start {
        return Err(invalid_absolute_path());
    }
    validate_single_segment(&path[share_start..share_end])?;
    if share_end < path.len() {
        validate_relative_segments(&path[share_end + 1..])?;
    }
    Ok(share_end)
}

fn validate_volume_suffix(path: &[u16]) -> io::Result<usize> {
    if !starts_with_ascii_case_insensitive(path, "Volume{") {
        return Err(invalid_absolute_path());
    }
    let root_separator = path
        .iter()
        .position(|code_unit| *code_unit == BACKSLASH)
        .ok_or_else(invalid_absolute_path)?;
    if root_separator == 0 || path[root_separator - 1] != b'}' as u16 {
        return Err(invalid_absolute_path());
    }
    validate_relative_segments(&path[root_separator + 1..])?;
    Ok(root_separator + 1)
}

fn validate_relative_segments(path: &[u16]) -> io::Result<()> {
    let mut start = 0_usize;
    for end in 0..=path.len() {
        if end != path.len() && path[end] != BACKSLASH {
            continue;
        }
        let segment = &path[start..end];
        if segment.is_empty() {
            if end == path.len() {
                return Ok(());
            }
            return Err(invalid_absolute_path());
        }
        validate_single_segment(segment)?;
        start = end + 1;
    }
    Ok(())
}

fn validate_single_segment(segment: &[u16]) -> io::Result<()> {
    if segment == [b'.' as u16] || segment == [b'.' as u16, b'.' as u16] {
        return Err(invalid_absolute_path());
    }
    Ok(())
}

fn is_drive_absolute(path: &[u16]) -> bool {
    let drive = path.first().copied().unwrap_or_default();
    path.len() >= 3
        && ((u16::from(b'A')..=u16::from(b'Z')).contains(&drive)
            || (u16::from(b'a')..=u16::from(b'z')).contains(&drive))
        && path[1] == b':' as u16
        && path[2] == BACKSLASH
}

fn starts_with_ascii_case_insensitive(value: &[u16], prefix: &str) -> bool {
    value.len() >= prefix.len()
        && value
            .iter()
            .copied()
            .zip(prefix.bytes())
            .take(prefix.len())
            .all(|(actual, expected)| {
                actual <= u16::from(u8::MAX) && (actual as u8).eq_ignore_ascii_case(&expected)
            })
}

fn trim_trailing_separators(path: &mut Vec<u16>, minimum_length: usize) {
    while path.len() > minimum_length && path.last() == Some(&BACKSLASH) {
        path.pop();
    }
}

fn prefixed_nt_path(prefix: &str, suffix: &[u16]) -> Vec<u16> {
    prefix
        .encode_utf16()
        .chain(suffix.iter().copied())
        .collect()
}

fn invalid_absolute_path() -> io::Error {
    io::Error::new(
        io::ErrorKind::InvalidInput,
        "Windows path must be an absolute drive, UNC, or volume path without dot components",
    )
}

fn path_too_long() -> io::Error {
    io::Error::new(io::ErrorKind::InvalidInput, "native path is too long")
}

fn invalid_relative_name() -> io::Error {
    io::Error::new(
        io::ErrorKind::InvalidInput,
        "Windows relative name must contain exactly one ordinary filename component",
    )
}

fn owned_handle_from_nt_status(
    handle: HANDLE,
    status: i32,
    operation: &'static str,
) -> io::Result<OwnedHandle> {
    if status < 0 {
        // SAFETY: `RtlNtStatusToDosError` is a pure status-code conversion.
        let code = unsafe { RtlNtStatusToDosError(status) };
        return Err(io::Error::from_raw_os_error(code as i32));
    }
    if handle.is_null() {
        return Err(io::Error::other(format!(
            "{operation} succeeded without returning a handle"
        )));
    }
    Ok(OwnedHandle(handle))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(path: &str) -> io::Result<String> {
        let path = NtAbsolutePath::parse(Path::new(path))?;
        String::from_utf16(path.as_slice())
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid UTF-16"))
    }

    /// `BOOLEAN` is true for any nonzero byte, so the accessors must not assume
    /// the kernel writes exactly 1. The companion guarantee — that the byte
    /// never becomes a Rust `bool` — is enforced at compile time by the layout
    /// assertion beside the type.
    #[test]
    fn boolean_bytes_are_true_for_any_nonzero_value() {
        for value in [1_u8, 2, 0x7f, 0xff] {
            let standard = FileStandardInfoBytes {
                allocation_size: 0,
                end_of_file: 0,
                number_of_links: 1,
                delete_pending: value,
                directory: value,
            };
            assert!(standard.is_delete_pending(), "byte {value} must read true");
            assert!(standard.is_directory(), "byte {value} must read true");
        }

        let cleared = FileStandardInfoBytes::default();
        assert!(!cleared.is_delete_pending());
        assert!(!cleared.is_directory());
    }

    #[test]
    fn path_conversion_accepts_supported_absolute_forms() {
        for (input, expected) in [
            (r"C:\vault\artifacts", r"\??\C:\vault\artifacts"),
            (r"C:/vault/artifacts", r"\??\C:\vault\artifacts"),
            (r"\\?\C:\vault\artifacts", r"\??\C:\vault\artifacts"),
            (
                r"\\server\share\artifacts",
                r"\??\UNC\server\share\artifacts",
            ),
            (
                r"\\?\UNC\server\share\artifacts",
                r"\??\UNC\server\share\artifacts",
            ),
            (
                r"\\?\Volume{01234567-89ab-4cde-8f01-23456789abcd}\artifacts",
                r"\??\Volume{01234567-89ab-4cde-8f01-23456789abcd}\artifacts",
            ),
        ] {
            assert_eq!(parse(input).expect("absolute path must parse"), expected);
        }
    }

    #[test]
    fn path_conversion_is_prefix_case_insensitive() {
        assert_eq!(
            parse(r"\\?\unc\server\share").expect("verbatim UNC path must parse"),
            r"\??\UNC\server\share"
        );
        assert_eq!(
            parse(r"\\?\volume{01234567-89ab-4cde-8f01-23456789abcd}\data")
                .expect("volume path must parse"),
            r"\??\volume{01234567-89ab-4cde-8f01-23456789abcd}\data"
        );
    }

    #[test]
    fn path_conversion_trims_only_non_root_trailing_separators() {
        for (input, expected) in [
            (r"C:\", r"\??\C:\"),
            (r"C:\vault\", r"\??\C:\vault"),
            (r"\\server\share\", r"\??\UNC\server\share"),
            (
                r"\\?\Volume{01234567-89ab-4cde-8f01-23456789abcd}\",
                r"\??\Volume{01234567-89ab-4cde-8f01-23456789abcd}\",
            ),
        ] {
            assert_eq!(
                parse(input).expect("absolute path with trailing separator must parse"),
                expected
            );
        }
    }

    #[test]
    fn path_conversion_rejects_unsupported_or_ambiguous_forms() {
        for path in [
            "",
            r"relative\artifacts",
            r"\root-relative",
            r"C:relative",
            r"\\.\C:\artifacts",
            r"\\?\GLOBALROOT\Device\HarddiskVolume1",
            r"\\server",
            r"\\server\\artifacts",
            r"C:\vault\..\artifacts",
            r"\\server\share\.\artifacts",
            "C:\\vault\0artifacts",
        ] {
            assert_eq!(
                NtAbsolutePath::parse(Path::new(path))
                    .expect_err("unsupported path must be rejected")
                    .kind(),
                io::ErrorKind::InvalidInput
            );
        }
    }

    #[test]
    fn relative_name_accepts_one_ordinary_component() {
        assert_eq!(
            NtRelativeName::parse("vault-Δ.kdbx")
                .expect("ordinary name must parse")
                .as_slice(),
            "vault-Δ.kdbx".encode_utf16().collect::<Vec<_>>()
        );
    }

    #[test]
    fn relative_name_rejects_navigation_streams_and_separators() {
        for name in ["", ".", "..", "nested/file", r"nested\file", "file:stream"] {
            assert_eq!(
                NtRelativeName::parse(name)
                    .expect_err("unsafe relative name must be rejected")
                    .kind(),
                io::ErrorKind::InvalidInput
            );
        }
    }
}
