//! Handle-relative Windows filesystem operations for private application state.
//!
//! Names are single components; reparse points are never followed. Callers retain
//! directory handles and choose sharing flags to keep their namespace pinned.
//! Existing objects keep their permissions and must be validated by the caller.

use std::{
    ffi::OsStr,
    fs::File,
    io,
    mem::{align_of, size_of, size_of_val},
    os::windows::io::{AsRawHandle, FromRawHandle},
    path::{Component, Path, Prefix},
    ptr,
};

use windows_sys::{
    Wdk::Storage::FileSystem::{
        FILE_CREATE, FILE_DIRECTORY_FILE, FILE_NON_DIRECTORY_FILE, FILE_OPEN, FILE_OPEN_IF,
        FILE_OPEN_REPARSE_POINT, FILE_RENAME_INFORMATION, FILE_RENAME_POSIX_SEMANTICS,
        FILE_RENAME_REPLACE_IF_EXISTS, FILE_SYNCHRONOUS_IO_NONALERT, FileRenameInformation,
        FileRenameInformationEx,
    },
    Win32::{
        Foundation::{OBJ_CASE_INSENSITIVE, OBJ_DONT_REPARSE},
        Storage::FileSystem::{
            CREATE_NEW, DELETE, FILE_ATTRIBUTE_NORMAL, FILE_ATTRIBUTE_REPARSE_POINT,
            FILE_ATTRIBUTE_TAG_INFO, FILE_READ_ATTRIBUTES, FILE_SHARE_READ, FILE_SHARE_WRITE,
            FILE_TRAVERSE, FileAttributeTagInfo, OPEN_ALWAYS, OPEN_EXISTING, SYNCHRONIZE,
        },
    },
};

use crate::{
    windows_nt::{
        NtAbsolutePath, NtCreateOptions, NtRelativeName, file_information_extension_unsupported,
        mark_delete_on_close, nt_create_file, nt_open_file, nt_set_file_information_bytes,
        query_file_information,
    },
    winfs::{owner_only_directory_security, owner_only_file_security},
};

/// Options for a non-inheritable relative file or directory handle.
pub struct OpenOptions {
    /// Win32 access mask. Read-attribute access is added; files also need `SYNCHRONIZE`.
    pub access: u32,
    /// Win32 sharing mask. Omit `FILE_SHARE_DELETE` to prevent replacement.
    pub sharing: u32,
    /// `OPEN_EXISTING`, `OPEN_ALWAYS`, or `CREATE_NEW`.
    pub disposition: u32,
    /// Whether the object must be a directory instead of a regular file.
    pub directory: bool,
}

/// Opens a local drive root without sharing delete.
///
/// This requires traversal and attribute access, not permission to read its ACL.
/// Descendants must be opened with [`open_at`], which refuses reparse points.
///
/// # Errors
/// Returns invalid-path, reparse-point, or operating-system access errors.
pub fn open_drive_root(path: &Path) -> io::Result<File> {
    let mut components = path.components();
    if !matches!(components.next(), Some(Component::Prefix(prefix))
        if matches!(prefix.kind(), Prefix::Disk(_) | Prefix::VerbatimDisk(_)))
        || components.next() != Some(Component::RootDir)
        || components.next().is_some()
    {
        return Err(io::ErrorKind::InvalidInput.into());
    }
    let path = NtAbsolutePath::parse(path)?;
    let handle = nt_open_file(
        ptr::null_mut(),
        path.as_slice(),
        FILE_TRAVERSE | FILE_READ_ATTRIBUTES,
        FILE_SHARE_READ | FILE_SHARE_WRITE,
        FILE_DIRECTORY_FILE | FILE_OPEN_REPARSE_POINT,
        // Drive letters are themselves object-manager symbolic links. Permit
        // that resolution only for the root; all filesystem children use
        // handle-relative OBJ_DONT_REPARSE opens below.
        OBJ_CASE_INSENSITIVE,
    )?;
    // SAFETY: Transfer the uniquely owned file handle exactly once.
    let file = unsafe { File::from_raw_handle(handle.into_raw()) };
    reject_reparse(&file)?;
    Ok(file)
}

/// Opens a child of a retained directory, creating it privately when requested.
///
/// Existing objects are never chmodded or assigned a new owner. The caller must
/// validate their security before using them as private application state.
///
/// # Errors
/// Returns invalid-name, wrong-type, reparse-point, or operating-system errors.
pub fn open_at(parent: &File, name: &OsStr, options: &OpenOptions) -> io::Result<File> {
    let name = NtRelativeName::parse_os(name)?;
    let disposition = match options.disposition {
        OPEN_EXISTING => FILE_OPEN,
        OPEN_ALWAYS => FILE_OPEN_IF,
        CREATE_NEW => FILE_CREATE,
        _ => return Err(io::ErrorKind::InvalidInput.into()),
    };
    let creating = disposition != FILE_OPEN;
    let directory_security = (creating && options.directory)
        .then(owner_only_directory_security)
        .transpose()?;
    let file_security = (creating && !options.directory)
        .then(owner_only_file_security)
        .transpose()?;
    let security_descriptor = directory_security
        .as_ref()
        .map(|security| security.descriptor())
        .or_else(|| file_security.as_ref().map(|security| security.descriptor()))
        .unwrap_or(ptr::null());
    let handle = nt_create_file(
        parent.as_raw_handle(),
        &name,
        &NtCreateOptions {
            desired_access: options.access
                | FILE_READ_ATTRIBUTES
                | if options.directory { 0 } else { SYNCHRONIZE },
            share_access: options.sharing,
            disposition,
            create_options: FILE_OPEN_REPARSE_POINT
                | if options.directory {
                    FILE_DIRECTORY_FILE
                } else {
                    FILE_NON_DIRECTORY_FILE | FILE_SYNCHRONOUS_IO_NONALERT
                },
            file_attributes: FILE_ATTRIBUTE_NORMAL,
            object_attributes: OBJ_CASE_INSENSITIVE | OBJ_DONT_REPARSE,
            security_descriptor,
        },
    )?;
    // SAFETY: Transfer the uniquely owned filesystem handle exactly once.
    let file = unsafe { File::from_raw_handle(handle.into_raw()) };
    reject_reparse(&file)?;
    Ok(file)
}

fn reject_reparse(file: &File) -> io::Result<()> {
    let info: FILE_ATTRIBUTE_TAG_INFO =
        query_file_information(file.as_raw_handle(), FileAttributeTagInfo)?;
    if info.FileAttributes & FILE_ATTRIBUTE_REPARSE_POINT != 0 || info.ReparseTag != 0 {
        return Err(io::ErrorKind::PermissionDenied.into());
    }
    Ok(())
}

/// Atomically replaces a child with the exact file referenced by `file`.
///
/// The source handle must have `DELETE` access. POSIX rename semantics allow old
/// readers to finish using the previous destination. No absolute path is resolved.
///
/// # Errors
/// Returns invalid-name or OS errors. A failed rename may have taken effect;
/// cleanup must remove only the staging name, never delete the source by handle.
pub fn replace_at(file: &File, parent: &File, name: &OsStr) -> io::Result<()> {
    let name = NtRelativeName::parse_os(name)?;
    let name = name.as_slice();
    let len = size_of::<FILE_RENAME_INFORMATION>() + size_of_val(name);
    const { assert!(align_of::<FILE_RENAME_INFORMATION>() <= align_of::<usize>()) };
    let mut storage = vec![0usize; len.div_ceil(size_of::<usize>())];
    let info = storage.as_mut_ptr().cast::<FILE_RENAME_INFORMATION>();
    // SAFETY: Word-aligned, initialized storage covers the header and UTF-16 name.
    unsafe {
        (*info).Anonymous.Flags = FILE_RENAME_REPLACE_IF_EXISTS | FILE_RENAME_POSIX_SEMANTICS;
        (*info).RootDirectory = parent.as_raw_handle();
        (*info).FileNameLength = size_of_val(name) as u32;
        ptr::copy_nonoverlapping(
            name.as_ptr(),
            (&raw mut (*info).FileName).cast(),
            name.len(),
        );
    }
    let dispatch = |storage: &[usize], class| {
        // SAFETY: The storage contains at least len initialized bytes and remains live.
        let bytes = unsafe { std::slice::from_raw_parts(storage.as_ptr().cast(), len) };
        nt_set_file_information_bytes(file.as_raw_handle(), class, bytes)
    };
    match dispatch(&storage, FileRenameInformationEx) {
        Err(error) if file_information_extension_unsupported(&error) => {
            // Only unsupported-operation errors permit a legacy retry. Other
            // failures can be reported after a filesystem filter already renamed.
            // SAFETY: info still points into storage; clear the extended flags.
            unsafe { (*info).Anonymous.Flags = FILE_RENAME_REPLACE_IF_EXISTS };
            dispatch(&storage, FileRenameInformation)
        }
        result => result,
    }
}

/// Removes a staging name relative to a retained directory, without following links.
///
/// # Errors
/// Returns invalid-name, sharing, or operating-system errors.
pub fn remove_at(parent: &File, name: &OsStr) -> io::Result<()> {
    let file = open_at(
        parent,
        name,
        &OpenOptions {
            access: DELETE,
            sharing: FILE_SHARE_READ | FILE_SHARE_WRITE,
            disposition: OPEN_EXISTING,
            directory: false,
        },
    )?;
    mark_delete_on_close(file.as_raw_handle())
}
