//! Pathless private scratch storage with a write → seal → read-many lifecycle.
//!
//! POSIX targets unlink the backing file immediately after creation, so the
//! bytes never have a reachable name. Windows denies all sharing, marks the
//! file delete-on-close, and restricts the DACL to the process owner. The
//! lifecycle is enforced here for safety and mirrored by the Kotlin wrapper
//! for friendlier errors.

use std::{io, path::Path};

/// A private scratch file.
pub struct ScratchFile {
    file: std::fs::File,
    length: u64,
    sealed: bool,
    /// Set when a write failed, at which point the file holds a prefix of the
    /// caller's byte stream that ends at an arbitrary offset.
    ///
    /// A scratch file is append-only and read back by absolute position, so a
    /// truncated stream cannot be repaired by continuing to append: a caller
    /// that caught the error and retried its chunk would write the accepted
    /// prefix twice and every later read would be misaligned. Refusing every
    /// subsequent operation forces the only correct recovery, which is to
    /// discard the storage and start again.
    poisoned: bool,
}

impl ScratchFile {
    /// Creates a scratch file inside `directory` with owner-only access.
    ///
    /// # Errors
    ///
    /// Returns an OS error when the directory cannot be created or no unique
    /// name could be claimed.
    pub fn open(directory: &Path) -> io::Result<Self> {
        // The old Windows path conversion made relative paths absolute after
        // directory provisioning. Snapshot the absolute spelling first so
        // provisioning and the retained-handle walk cannot observe different
        // process current directories.
        #[cfg(windows)]
        let absolute_directory = std::path::absolute(directory)?;
        #[cfg(windows)]
        let directory = absolute_directory.as_path();

        #[cfg(not(windows))]
        std::fs::create_dir_all(directory)?;
        let file = platform::create_pathless(directory)?;
        Ok(Self {
            file,
            length: 0,
            sealed: false,
            poisoned: false,
        })
    }

    /// Wraps an arbitrary descriptor so write failures can be provoked without
    /// contriving a full filesystem.
    #[cfg(test)]
    fn from_file_for_tests(file: std::fs::File) -> Self {
        Self {
            file,
            length: 0,
            sealed: false,
            poisoned: false,
        }
    }

    /// Appends bytes; valid only before [`Self::seal`].
    ///
    /// Either the whole buffer is appended or the storage is poisoned, so a
    /// caller never has to reason about how much of a failed write landed.
    ///
    /// # Errors
    ///
    /// Returns [`io::ErrorKind::InvalidInput`] once sealed or poisoned, or an
    /// OS error. An OS error here poisons the storage: the first failure
    /// reports the real cause, and every later call reports the lifecycle
    /// violation.
    pub fn write(&mut self, buffer: &[u8]) -> io::Result<usize> {
        self.usable()?;
        if self.sealed {
            return Err(sealed_state_error());
        }
        match self.append(buffer) {
            Ok(()) => Ok(buffer.len()),
            Err(error) => {
                self.poisoned = true;
                Err(error)
            }
        }
    }

    fn append(&mut self, buffer: &[u8]) -> io::Result<()> {
        append_counted(&mut self.file, buffer, &mut self.length)
    }

    /// Ends the write phase; reads become valid.
    ///
    /// # Errors
    ///
    /// Returns [`io::ErrorKind::InvalidInput`] when already sealed or poisoned.
    pub fn seal(&mut self) -> io::Result<()> {
        self.usable()?;
        if self.sealed {
            return Err(sealed_state_error());
        }
        self.sealed = true;
        Ok(())
    }

    /// Reads bytes at `position`; valid only after [`Self::seal`]. A zero
    /// result denotes end-of-file.
    ///
    /// # Errors
    ///
    /// Returns [`io::ErrorKind::InvalidInput`] before sealing or once poisoned,
    /// or an OS error.
    pub fn read_at(&self, position: u64, buffer: &mut [u8]) -> io::Result<usize> {
        self.usable()?;
        if !self.sealed {
            return Err(sealed_state_error());
        }
        platform::read_at(&self.file, position, buffer)
    }

    /// Returns the number of bytes accepted by the storage.
    ///
    /// # Errors
    ///
    /// Returns [`io::ErrorKind::InvalidInput`] once poisoned. The count is
    /// accurate even then, but reporting it would invite a caller to treat a
    /// truncated stream as complete.
    pub fn length(&self) -> io::Result<u64> {
        self.usable()?;
        Ok(self.length)
    }

    fn usable(&self) -> io::Result<()> {
        if self.poisoned {
            return Err(sealed_state_error());
        }
        Ok(())
    }
}

/// Appends the whole buffer, adding each accepted chunk to `length` as it lands.
///
/// Written in terms of [`io::Write::write`] rather than `write_all` because
/// `write_all` reports failure without saying how much it accepted, which would
/// leave the caller's length understating what reached the file. Generic over
/// the writer so the partial-write accounting is testable without contriving a
/// full filesystem.
fn append_counted<W: io::Write>(writer: &mut W, buffer: &[u8], length: &mut u64) -> io::Result<()> {
    let mut written = 0_usize;
    while written < buffer.len() {
        match writer.write(&buffer[written..]) {
            Ok(0) => {
                return Err(io::Error::new(
                    io::ErrorKind::WriteZero,
                    "scratch storage accepted no bytes",
                ));
            }
            Ok(count) => {
                written += count;
                *length += count as u64;
            }
            Err(error) if error.kind() == io::ErrorKind::Interrupted => {}
            Err(error) => return Err(error),
        }
    }
    Ok(())
}

/// Reported for every lifecycle violation, including use after a failed write.
///
/// One code covers both because the ABI's [`crate::FailureKind`] set is fixed
/// and a caller's recovery is identical: discard the storage. The genuine cause
/// of a poisoning failure is never hidden — the write that poisoned it returned
/// the underlying OS error.
fn sealed_state_error() -> io::Error {
    io::Error::new(
        io::ErrorKind::InvalidInput,
        "scratch storage lifecycle violation",
    )
}

#[cfg(unix)]
mod platform {
    use std::{
        ffi::CString,
        fs::File,
        io,
        os::{
            fd::{AsRawFd, FromRawFd, OwnedFd},
            unix::{ffi::OsStrExt, fs::FileExt},
        },
        path::Path,
    };

    pub(super) fn create_pathless(directory: &Path) -> io::Result<File> {
        let directory = open_directory(directory)?;
        #[cfg(any(target_os = "linux", target_os = "android"))]
        match create_unnamed(&directory) {
            Ok(file) => return Ok(file),
            Err(error) if unnamed_file_unsupported(&error) => {}
            Err(error) => return Err(error),
        }

        crate::fsops::create_pathless_named_scratch_at(directory.as_raw_fd())
    }

    pub(super) fn read_at(file: &File, position: u64, buffer: &mut [u8]) -> io::Result<usize> {
        file.read_at(buffer, position)
    }

    fn open_directory(path: &Path) -> io::Result<OwnedFd> {
        let path = CString::new(path.as_os_str().as_bytes())
            .map_err(|_| io::Error::from_raw_os_error(libc::EINVAL))?;
        // SAFETY: `path` is NUL-terminated for the call and a successful call
        // returns a new directory descriptor.
        let raw_fd = unsafe {
            libc::open(
                path.as_ptr(),
                libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC | libc::O_NOFOLLOW,
            )
        };
        if raw_fd < 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: `open` returned a new uniquely-owned descriptor.
        Ok(unsafe { OwnedFd::from_raw_fd(raw_fd) })
    }

    #[cfg(any(target_os = "linux", target_os = "android"))]
    fn create_unnamed(directory: &OwnedFd) -> io::Result<File> {
        const CURRENT_DIRECTORY: &[u8] = b".\0";
        // O_EXCL gives O_TMPFILE its special "cannot later be linked" meaning,
        // preserving the scratch API's pathless lifetime contract.
        // SAFETY: the path is a static NUL-terminated string, the retained
        // directory descriptor is valid, and a successful call returns a new
        // descriptor.
        let raw_fd = unsafe {
            libc::openat(
                directory.as_raw_fd(),
                CURRENT_DIRECTORY.as_ptr().cast(),
                libc::O_RDWR | libc::O_CLOEXEC | libc::O_TMPFILE | libc::O_EXCL,
                0o600,
            )
        };
        if raw_fd < 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: `openat` returned a new uniquely-owned descriptor.
        Ok(unsafe { File::from_raw_fd(raw_fd) })
    }

    #[cfg(any(target_os = "linux", target_os = "android"))]
    fn unnamed_file_unsupported(error: &io::Error) -> bool {
        matches!(
            error.raw_os_error(),
            Some(code) if code == libc::EINVAL
                || code == libc::EISDIR
                || code == libc::ENOENT
                || code == libc::ENOSYS
                || code == libc::ENOTSUP
                || code == libc::EOPNOTSUPP
        )
    }
}

#[cfg(windows)]
mod platform {
    use std::{
        ffi::{OsStr, OsString},
        fs::File,
        io,
        os::windows::{fs::FileExt, io::FromRawHandle},
        path::{Component, Path, PathBuf},
    };

    use windows_sys::{
        Wdk::Storage::FileSystem::{
            FILE_CREATE, FILE_DELETE_ON_CLOSE, FILE_NON_DIRECTORY_FILE, FILE_OPEN_REPARSE_POINT,
            FILE_SYNCHRONOUS_IO_NONALERT,
        },
        Win32::{
            Foundation::{GENERIC_READ, GENERIC_WRITE, OBJ_CASE_INSENSITIVE, OBJ_DONT_REPARSE},
            Storage::FileSystem::{
                DELETE, FILE_ATTRIBUTE_DIRECTORY, FILE_ATTRIBUTE_REPARSE_POINT,
                FILE_ATTRIBUTE_TAG_INFO, FILE_ATTRIBUTE_TEMPORARY, FileAttributeTagInfo,
                READ_CONTROL, SYNCHRONIZE,
            },
        },
    };

    use crate::{
        fsops::{RealFs, WinDir},
        txn::DirectoryPermissions,
        windows_nt::{
            NtAbsolutePath, NtCreateOptions, NtRelativeName, nt_create_file, query_file_information,
        },
        winfs::{owner_only_file_security, verify_owner_only_file},
    };

    /// A scratch root whose final path component has been verified as a real
    /// directory. Every candidate file is created relative to this capability.
    pub(super) struct WindowsScratchRoot {
        directory: WinDir,
    }

    impl WindowsScratchRoot {
        pub(super) fn open(directory: &Path) -> io::Result<Self> {
            let directory = open_or_create_directory(&RealFs, directory)?;
            validate_scratch_root(directory.traversal_handle())?;
            Ok(Self { directory })
        }

        pub(super) fn create_pathless(&self) -> io::Result<File> {
            let security = owner_only_file_security()?;
            for _ in 0..crate::naming::MAX_TEMPORARY_ARTIFACT_ATTEMPTS {
                let name = crate::naming::new_file_lease_artifact_name(
                    crate::naming::TemporaryFileRole::Scratch,
                )?;
                let name = NtRelativeName::parse(&name)?;
                let handle = match nt_create_file(
                    self.directory.traversal_handle(),
                    &name,
                    &NtCreateOptions {
                        desired_access: GENERIC_READ
                            | GENERIC_WRITE
                            | DELETE
                            | READ_CONTROL
                            | SYNCHRONIZE,
                        share_access: 0,
                        disposition: FILE_CREATE,
                        create_options: FILE_NON_DIRECTORY_FILE
                            | FILE_OPEN_REPARSE_POINT
                            | FILE_DELETE_ON_CLOSE
                            | FILE_SYNCHRONOUS_IO_NONALERT,
                        file_attributes: FILE_ATTRIBUTE_TEMPORARY,
                        object_attributes: OBJ_CASE_INSENSITIVE | OBJ_DONT_REPARSE,
                        security_descriptor: security.descriptor(),
                    },
                ) {
                    Ok(handle) => handle,
                    Err(error) if error.kind() == io::ErrorKind::AlreadyExists => continue,
                    Err(error) => return Err(error),
                };

                // Verification is tied to the exact newly-created object.
                // `FILE_DELETE_ON_CLOSE` was part of the successful create, so
                // returning through `?` drops the handle and removes the
                // rejected object without resolving its name again.
                verify_owner_only_file(handle.as_raw(), &security)?;
                // SAFETY: `handle` uniquely owns a successful file-create
                // result and transfers that ownership into `File`.
                return Ok(unsafe { File::from_raw_handle(handle.into_raw()) });
            }
            Err(io::Error::new(
                io::ErrorKind::AlreadyExists,
                "could not allocate a unique private scratch file",
            ))
        }
    }

    pub(super) trait ScratchDirectoryFs {
        type Dir;

        fn open_root(&self, path: &Path) -> io::Result<Self::Dir>;

        fn open_root_no_follow_final(&self, path: &Path) -> io::Result<Self::Dir>;

        fn open_dir_at(
            &self,
            parent: &Self::Dir,
            name: &OsStr,
            follow_links: bool,
        ) -> io::Result<Self::Dir>;

        fn create_and_open_dir_at(
            &self,
            parent: &Self::Dir,
            name: &OsStr,
            permissions: DirectoryPermissions,
        ) -> io::Result<Self::Dir>;
    }

    impl ScratchDirectoryFs for RealFs {
        type Dir = WinDir;

        fn open_root(&self, path: &Path) -> io::Result<Self::Dir> {
            self.open_windows_root(path)
        }

        fn open_root_no_follow_final(&self, path: &Path) -> io::Result<Self::Dir> {
            self.open_windows_root_no_follow_final(path)
        }

        fn open_dir_at(
            &self,
            parent: &Self::Dir,
            name: &OsStr,
            follow_links: bool,
        ) -> io::Result<Self::Dir> {
            self.open_windows_dir_at(parent, name, follow_links)
        }

        fn create_and_open_dir_at(
            &self,
            parent: &Self::Dir,
            name: &OsStr,
            permissions: DirectoryPermissions,
        ) -> io::Result<Self::Dir> {
            self.create_and_open_windows_dir_at(parent, name, permissions)
        }
    }

    #[cfg(test)]
    impl ScratchDirectoryFs for crate::simfs::SimFs {
        type Dir = crate::simfs::SimDir;

        fn open_root(&self, path: &Path) -> io::Result<Self::Dir> {
            crate::fsops::FsOps::open_root(self, path)
        }

        fn open_root_no_follow_final(&self, path: &Path) -> io::Result<Self::Dir> {
            self.open_root_no_follow_final(path)
        }

        fn open_dir_at(
            &self,
            parent: &Self::Dir,
            name: &OsStr,
            follow_links: bool,
        ) -> io::Result<Self::Dir> {
            let name = name.to_str().ok_or_else(invalid_scratch_root)?;
            crate::fsops::FsOps::open_dir_at(self, parent, name, follow_links)
        }

        fn create_and_open_dir_at(
            &self,
            parent: &Self::Dir,
            name: &OsStr,
            permissions: DirectoryPermissions,
        ) -> io::Result<Self::Dir> {
            let name = name.to_str().ok_or_else(invalid_scratch_root)?;
            crate::fsops::FsOps::create_and_open_dir_at(self, parent, name, permissions)
        }
    }

    /// Splits a validated absolute scratch-root spelling into its platform root
    /// and ordinary components.
    fn split_absolute_path(directory: &Path) -> io::Result<(PathBuf, Vec<OsString>)> {
        if !directory.is_absolute() {
            return Err(invalid_scratch_root());
        }

        let mut root = PathBuf::new();
        let mut components = Vec::new();
        let mut saw_root = false;
        for component in directory.components() {
            match component {
                Component::Prefix(prefix) => root.push(prefix.as_os_str()),
                Component::RootDir => {
                    root.push(component.as_os_str());
                    saw_root = true;
                }
                Component::Normal(name) if !name.is_empty() => {
                    components.push(name.to_os_string());
                }
                Component::Normal(_) | Component::CurDir | Component::ParentDir => {
                    return Err(invalid_scratch_root());
                }
            }
        }
        if !saw_root {
            return Err(invalid_scratch_root());
        }
        Ok((root, components))
    }

    fn invalid_scratch_root() -> io::Error {
        io::Error::new(io::ErrorKind::InvalidInput, "invalid scratch root path")
    }

    /// Opens an existing root directly, or creates missing components beneath
    /// the deepest retained existing ancestor.
    ///
    /// Existing ancestor links are resolved once and pinned by the returned
    /// directory handle. A link that appears after a component was observed
    /// missing loses the exclusive-create race and is rejected by the
    /// no-follow collision open. The final component is never followed.
    pub(super) fn open_or_create_directory<F: ScratchDirectoryFs>(
        fs: &F,
        directory: &Path,
    ) -> io::Result<F::Dir> {
        NtAbsolutePath::parse(directory)?;
        let (root, components) = split_absolute_path(directory)?;
        let mut missing_error = match fs.open_root_no_follow_final(directory) {
            Ok(directory) => return Ok(directory),
            Err(error) if error.kind() == io::ErrorKind::NotFound => error,
            Err(error) => return Err(error),
        };

        // Shorter absolute prefixes may follow their final component because
        // it is an ancestor of the requested scratch root. A successful open
        // already yields the operational capability used for creation.
        let mut deepest = None;
        for depth in (0..components.len()).rev() {
            let mut candidate = root.clone();
            candidate.extend(components[..depth].iter());
            match fs.open_root(&candidate) {
                Ok(directory) => {
                    deepest = Some((directory, depth));
                    break;
                }
                Err(error) if error.kind() == io::ErrorKind::NotFound => missing_error = error,
                Err(error) => return Err(error),
            }
        }
        let (mut current, depth) = deepest.ok_or(missing_error)?;

        let first_missing_observed = depth < components.len();
        for (index, component) in components[depth..].iter().enumerate() {
            let existing = if first_missing_observed && index == 0 {
                None
            } else {
                match fs.open_dir_at(&current, component, false) {
                    Ok(directory) => Some(directory),
                    Err(error) if error.kind() == io::ErrorKind::NotFound => None,
                    Err(error) => return Err(error),
                }
            };
            let next = if let Some(directory) = existing {
                directory
            } else {
                match fs.create_and_open_dir_at(
                    &current,
                    component,
                    DirectoryPermissions::ProcessDefault,
                ) {
                    Ok(directory) => directory,
                    Err(error) if error.kind() == io::ErrorKind::AlreadyExists => {
                        fs.open_dir_at(&current, component, false)?
                    }
                    Err(error) => return Err(error),
                }
            };
            current = next;
        }

        Ok(current)
    }

    pub(super) fn create_pathless(directory: &Path) -> io::Result<File> {
        WindowsScratchRoot::open(directory)?.create_pathless()
    }

    pub(super) fn read_at(file: &File, position: u64, buffer: &mut [u8]) -> io::Result<usize> {
        file.seek_read(buffer, position)
    }

    fn validate_scratch_root(handle: windows_sys::Win32::Foundation::HANDLE) -> io::Result<()> {
        let attributes: FILE_ATTRIBUTE_TAG_INFO =
            query_file_information(handle, FileAttributeTagInfo)?;
        if attributes.FileAttributes & FILE_ATTRIBUTE_DIRECTORY == 0 {
            return Err(io::Error::new(
                io::ErrorKind::NotADirectory,
                "scratch root is not a directory",
            ));
        }
        if attributes.FileAttributes & FILE_ATTRIBUTE_REPARSE_POINT != 0
            || attributes.ReparseTag != 0
        {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "scratch root is a reparse point",
            ));
        }
        Ok(())
    }
}

#[cfg(not(any(unix, windows)))]
mod platform {
    use std::{fs::File, io, path::Path};

    pub(super) fn create_pathless(_directory: &Path) -> io::Result<File> {
        Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "private scratch storage is unsupported on this target",
        ))
    }

    pub(super) fn read_at(_file: &File, _position: u64, _buffer: &mut [u8]) -> io::Result<usize> {
        Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "private scratch storage is unsupported on this target",
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_directory() -> std::path::PathBuf {
        let mut nonce = [0_u8; 8];
        getrandom::fill(&mut nonce).expect("test nonce generation must succeed");
        let name: String = nonce.iter().map(|byte| format!("{byte:02x}")).collect();
        let path = std::env::temp_dir().join(format!("keyguard-scratch-test-{name}"));
        std::fs::create_dir_all(&path).expect("test directory must be created");
        path
    }

    #[cfg(windows)]
    struct WindowsTestDirectory(std::path::PathBuf);

    #[cfg(windows)]
    impl WindowsTestDirectory {
        fn new() -> Self {
            Self(test_directory())
        }

        fn path(&self) -> &Path {
            &self.0
        }
    }

    #[cfg(windows)]
    impl Drop for WindowsTestDirectory {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    /// Accepts `accept` bytes per call, then fails. Models a filesystem that
    /// takes part of a buffer before hitting ENOSPC or EDQUOT.
    struct PartialWriter {
        accept: usize,
        remaining_calls: usize,
    }

    impl io::Write for PartialWriter {
        fn write(&mut self, buffer: &[u8]) -> io::Result<usize> {
            if self.remaining_calls == 0 {
                return Err(io::Error::from(io::ErrorKind::StorageFull));
            }
            self.remaining_calls -= 1;
            Ok(self.accept.min(buffer.len()))
        }

        fn flush(&mut self) -> io::Result<()> {
            Ok(())
        }
    }

    /// The defect: `write_all` reports failure without saying how much it
    /// accepted, so the length silently understated the file. A caller that
    /// caught the error and retried its chunk would then write the accepted
    /// prefix twice while counting it once, misaligning every later read.
    #[test]
    fn a_partial_write_still_counts_the_bytes_that_landed() {
        let mut writer = PartialWriter {
            accept: 3,
            remaining_calls: 2,
        };
        let mut length = 0_u64;

        let error = append_counted(&mut writer, b"secret bytes", &mut length)
            .expect_err("the writer must fail before the buffer is consumed");

        assert_eq!(error.kind(), io::ErrorKind::StorageFull);
        assert_eq!(length, 6, "both accepted chunks must be counted");
    }

    /// A writer that accepts nothing must not spin forever.
    #[test]
    fn a_writer_accepting_no_bytes_is_reported_rather_than_retried() {
        let mut writer = PartialWriter {
            accept: 0,
            remaining_calls: usize::MAX,
        };
        let mut length = 0_u64;

        let error = append_counted(&mut writer, b"payload", &mut length)
            .expect_err("a zero-length write must not loop");

        assert_eq!(error.kind(), io::ErrorKind::WriteZero);
        assert_eq!(length, 0);
    }

    /// A failed write leaves the byte stream truncated at an arbitrary offset,
    /// so continuing to use the storage could only produce a corrupt payload.
    /// Every later operation must refuse, and the poisoning failure must not
    /// mask the real cause reported by the write itself.
    #[test]
    fn a_failed_write_poisons_the_storage() {
        let directory = test_directory();
        let path = directory.join("read-only");
        std::fs::write(&path, b"").expect("fixture must be created");
        let read_only = std::fs::File::open(&path).expect("fixture must reopen read-only");
        let mut scratch = ScratchFile::from_file_for_tests(read_only);

        let error = scratch
            .write(b"secret")
            .expect_err("a read-only descriptor must refuse the write");
        assert_ne!(
            error.kind(),
            io::ErrorKind::InvalidInput,
            "the first failure must report the underlying cause, not the lifecycle"
        );

        for kind in [
            scratch.write(b"retry").err().map(|error| error.kind()),
            scratch.seal().err().map(|error| error.kind()),
            scratch.length().err().map(|error| error.kind()),
        ] {
            assert_eq!(
                kind,
                Some(io::ErrorKind::InvalidInput),
                "a poisoned scratch file must refuse every later operation"
            );
        }
        assert_eq!(
            scratch.read_at(0, &mut [0_u8; 4]).err().map(|e| e.kind()),
            Some(io::ErrorKind::InvalidInput),
        );

        let _ = std::fs::remove_dir_all(directory);
    }

    #[test]
    fn lifecycle_write_seal_read_round_trips() {
        let directory = test_directory();
        let mut scratch = ScratchFile::open(&directory).expect("scratch must open");

        scratch.write(b"secret ").expect("write must succeed");
        scratch.write(b"bytes").expect("write must succeed");
        assert_eq!(scratch.length().expect("length must be readable"), 12);
        scratch.seal().expect("seal must succeed");

        let mut buffer = [0_u8; 12];
        assert_eq!(
            scratch.read_at(0, &mut buffer).expect("read must succeed"),
            12
        );
        assert_eq!(&buffer, b"secret bytes");
        let mut tail = [0_u8; 4];
        assert_eq!(scratch.read_at(7, &mut tail).expect("read must succeed"), 4);
        assert_eq!(&tail, b"byte");
        assert_eq!(
            scratch
                .read_at(12, &mut tail)
                .expect("end-of-file read must succeed"),
            0
        );
        let _ = std::fs::remove_dir_all(&directory);
    }

    #[test]
    fn lifecycle_violations_are_rejected() {
        let directory = test_directory();
        let mut scratch = ScratchFile::open(&directory).expect("scratch must open");

        let mut buffer = [0_u8; 1];
        assert_eq!(
            scratch
                .read_at(0, &mut buffer)
                .expect_err("read before seal must fail")
                .kind(),
            io::ErrorKind::InvalidInput
        );
        scratch.seal().expect("seal must succeed");
        assert_eq!(
            scratch
                .write(b"late")
                .expect_err("write after seal must fail")
                .kind(),
            io::ErrorKind::InvalidInput
        );
        assert_eq!(
            scratch.seal().expect_err("double seal must fail").kind(),
            io::ErrorKind::InvalidInput
        );
        let _ = std::fs::remove_dir_all(&directory);
    }

    #[cfg(windows)]
    #[test]
    fn windows_scratch_root_creates_multiple_missing_components() {
        let fixture = WindowsTestDirectory::new();
        let scratch_root = fixture.path().join("a").join("b").join("scratch");

        let scratch = ScratchFile::open(&scratch_root)
            .expect("every missing scratch-root component must be created");

        assert_eq!(
            std::fs::read_dir(&scratch_root)
                .expect("created scratch root must list")
                .count(),
            1,
        );
        drop(scratch);
        assert_eq!(
            std::fs::read_dir(&scratch_root)
                .expect("created scratch root must list after close")
                .count(),
            0,
        );
    }

    #[cfg(windows)]
    #[test]
    fn windows_scratch_root_preserves_non_unicode_components() {
        use std::{ffi::OsString, os::windows::ffi::OsStringExt as _};

        let fixture = WindowsTestDirectory::new();
        let component =
            OsString::from_wide(&[b'n' as u16, b'o' as u16, b'n' as u16, b'-' as u16, 0xD800]);
        let scratch_root = fixture.path().join(component).join("scratch");

        let scratch = ScratchFile::open(&scratch_root)
            .expect("valid non-Unicode Windows components must remain supported");

        assert_eq!(
            std::fs::read_dir(&scratch_root)
                .expect("non-Unicode scratch root must list")
                .count(),
            1,
        );
        drop(scratch);
        assert_eq!(
            std::fs::read_dir(&scratch_root)
                .expect("non-Unicode scratch root must list after close")
                .count(),
            0,
        );
    }

    #[cfg(windows)]
    #[test]
    fn windows_final_scratch_root_reparse_point_is_rejected() {
        let fixture = WindowsTestDirectory::new();
        let target = fixture.path().join("target");
        let link = fixture.path().join("link");
        std::fs::create_dir(&target).expect("target directory must be created");
        if let Err(error) = std::os::windows::fs::symlink_dir(&target, &link) {
            if crate::windows_symlink_unavailable(&error) {
                return;
            }
            panic!("directory symlink creation failed unexpectedly: {error}");
        }

        let error = match ScratchFile::open(&link) {
            Ok(_) => panic!("a final scratch-root reparse point must be rejected"),
            Err(error) => error,
        };

        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
        assert_eq!(
            std::fs::read_dir(&target)
                .expect("target directory must list")
                .count(),
            0,
            "rejected roots must not receive a scratch file",
        );
    }

    #[cfg(windows)]
    #[test]
    fn windows_scratch_creation_stays_anchored_after_root_path_replacement() {
        let fixture = WindowsTestDirectory::new();
        let original = fixture.path().join("original");
        let moved = fixture.path().join("moved");
        std::fs::create_dir(&original).expect("original root must be created");
        let root =
            platform::WindowsScratchRoot::open(&original).expect("scratch root handle must open");

        std::fs::rename(&original, &moved).expect("open root must permit rename");
        std::fs::create_dir(&original).expect("replacement path must be created");
        let scratch = root
            .create_pathless()
            .expect("scratch must be created through the retained handle");

        assert_eq!(
            std::fs::read_dir(&moved)
                .expect("moved root must list")
                .count(),
            1,
            "the retained handle must select the original directory",
        );
        assert_eq!(
            std::fs::read_dir(&original)
                .expect("replacement root must list")
                .count(),
            0,
            "the replacement path must not receive the scratch file",
        );

        drop(scratch);
        assert_eq!(
            std::fs::read_dir(&moved)
                .expect("moved root must list after close")
                .count(),
            0,
            "delete-on-close must remove the anchored scratch file",
        );
    }

    #[cfg(windows)]
    #[test]
    fn windows_scratch_root_may_resolve_an_ancestor_reparse_point_once() {
        let fixture = WindowsTestDirectory::new();
        let target = fixture.path().join("target");
        let link = fixture.path().join("link");
        let scratch_root = link.join("nested").join("scratch");
        std::fs::create_dir(&target).expect("target directory must be created");
        if let Err(error) = std::os::windows::fs::symlink_dir(&target, &link) {
            if crate::windows_symlink_unavailable(&error) {
                return;
            }
            panic!("directory symlink creation failed unexpectedly: {error}");
        }

        let scratch =
            ScratchFile::open(&scratch_root).expect("an ancestor reparse point may be resolved");

        assert_eq!(
            std::fs::read_dir(target.join("nested").join("scratch"))
                .expect("resolved scratch root must list")
                .count(),
            1,
            "the selected real directory must contain the scratch file",
        );
        drop(scratch);
        assert_eq!(
            std::fs::read_dir(target.join("nested").join("scratch"))
                .expect("resolved scratch root must list after close")
                .count(),
            0,
        );
    }

    #[cfg(windows)]
    #[test]
    fn windows_final_reparse_is_rejected_below_a_followed_ancestor() {
        let fixture = WindowsTestDirectory::new();
        let ancestor_target = fixture.path().join("ancestor-target");
        let final_target = fixture.path().join("final-target");
        let ancestor_link = fixture.path().join("ancestor-link");
        let final_link = ancestor_target.join("scratch");
        std::fs::create_dir(&ancestor_target).expect("ancestor target must be created");
        std::fs::create_dir(&final_target).expect("final target must be created");
        if let Err(error) = std::os::windows::fs::symlink_dir(&ancestor_target, &ancestor_link) {
            if crate::windows_symlink_unavailable(&error) {
                return;
            }
            panic!("ancestor symlink creation failed unexpectedly: {error}");
        }
        std::os::windows::fs::symlink_dir(&final_target, &final_link)
            .expect("final symlink must be created");

        let error = match ScratchFile::open(&ancestor_link.join("scratch")) {
            Ok(_) => panic!("the final reparse point must remain rejected"),
            Err(error) => error,
        };

        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
        assert_eq!(
            std::fs::read_dir(&final_target)
                .expect("final target must list")
                .count(),
            0,
            "a rejected final reparse target must not receive a scratch file",
        );
    }

    #[cfg(windows)]
    #[test]
    fn windows_scratch_root_rejects_a_link_that_wins_the_creation_race() {
        use crate::simfs::{NamespaceMutation, SimFsBuilder, SimOp};

        let fs = SimFsBuilder::new()
            .preexisting_directory("outside")
            .mutate_before(
                SimOp::CreateDirAt,
                0,
                NamespaceMutation::create_directory_link("/", "vault", "/outside"),
            )
            .build();

        let error = match platform::open_or_create_directory(
            &fs,
            Path::new(test_absolute_path!("/vault/scratch")),
        ) {
            Ok(_) => panic!("a concurrently inserted directory link must be rejected"),
            Err(error) => error,
        };

        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
        let directory_opens = fs
            .operations()
            .into_iter()
            .filter(|operation| operation.op == SimOp::OpenDirAt)
            .map(|operation| operation.occurrence)
            .collect::<Vec<_>>();
        assert_eq!(
            directory_opens,
            [0],
            "the creation collision must be reopened exactly once without following links",
        );
    }

    #[cfg(windows)]
    #[test]
    fn windows_scratch_root_rejects_a_preexisting_final_link_in_simulation() {
        use crate::simfs::SimFsBuilder;

        let fs = SimFsBuilder::new()
            .preexisting_directory("outside")
            .preexisting_directory_link("scratch", "/outside")
            .build();

        let error = match platform::open_or_create_directory(
            &fs,
            Path::new(test_absolute_path!("/scratch")),
        ) {
            Ok(_) => panic!("a pre-existing final directory link must be rejected"),
            Err(error) => error,
        };

        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
        assert!(
            fs.final_snapshot()
                .live_listing()
                .keys()
                .all(|path| !path.starts_with("outside/")),
            "the rejected final link target must remain untouched",
        );
    }

    #[cfg(windows)]
    #[test]
    fn windows_scratch_root_keeps_a_followed_ancestor_pinned_during_provisioning() {
        use crate::{
            fsops::FsOps as _,
            simfs::{NamespaceMutation, SimFsBuilder, SimOp},
        };

        let fs = SimFsBuilder::new()
            .preexisting_directory("first")
            .preexisting_directory("second")
            .preexisting_directory_link("selected", "/first")
            .mutate_before(
                SimOp::CreateDirAt,
                0,
                NamespaceMutation::rename_entry("/", "selected", "selected-old"),
            )
            .mutate_before(
                SimOp::CreateDirAt,
                1,
                NamespaceMutation::create_directory_link("/", "selected", "/second"),
            )
            .build();
        let directory = platform::open_or_create_directory(
            &fs,
            Path::new(test_absolute_path!("/selected/nested/scratch")),
        )
        .expect("provisioning must stay beneath the retained ancestor");
        let file = fs
            .create_file_at(&directory, "probe.bin", true)
            .expect("probe file must be created through the retained root");
        fs.close(file).expect("probe file must close");

        let snapshot = fs.final_snapshot();
        assert!(
            snapshot
                .live_listing()
                .contains_key("first/nested/scratch/probe.bin"),
            "the retained first target must receive the probe",
        );
        assert!(
            !snapshot
                .live_listing()
                .contains_key("second/nested/scratch/probe.bin"),
            "the replacement link target must remain untouched",
        );
    }

    #[cfg(windows)]
    #[test]
    fn windows_scratch_file_is_private_and_deleted_on_close() {
        use std::os::windows::io::AsRawHandle as _;

        use crate::winfs::{owner_only_file_security, verify_owner_only_file};

        let fixture = WindowsTestDirectory::new();
        let scratch = ScratchFile::open(fixture.path()).expect("scratch must open");
        let entries = std::fs::read_dir(fixture.path())
            .expect("scratch root must list")
            .collect::<Result<Vec<_>, _>>()
            .expect("scratch entry metadata must be readable");
        let [entry] = entries.as_slice() else {
            panic!(
                "exactly one scratch entry must exist, found {}",
                entries.len()
            );
        };

        assert!(
            std::fs::File::open(entry.path()).is_err(),
            "share-none must prevent a second open",
        );
        let expected =
            owner_only_file_security().expect("owner-only descriptor must be constructed");
        verify_owner_only_file(scratch.file.as_raw_handle(), &expected)
            .expect("scratch file must retain the exact owner-only DACL");

        // `FILE_DELETE_ON_CLOSE` promises removal after the last close. Some
        // Windows filesystems do not expose that create option through the
        // intermediate `FILE_STANDARD_INFO.DeletePending` value, so verify
        // the documented lifecycle outcome directly.
        drop(scratch);
        assert_eq!(
            std::fs::read_dir(fixture.path())
                .expect("scratch root must list after close")
                .count(),
            0,
            "the final handle close must remove the scratch entry",
        );
    }

    #[cfg(unix)]
    #[test]
    fn scratch_files_are_pathless_while_open() {
        let directory = test_directory();
        let _scratch = ScratchFile::open(&directory).expect("scratch must open");

        let entries: Vec<_> = std::fs::read_dir(&directory)
            .expect("directory must list")
            .collect();
        assert!(entries.is_empty(), "scratch file must not have a name");
        let _ = std::fs::remove_dir_all(&directory);
    }

    #[cfg(unix)]
    #[test]
    fn scratch_file_is_owner_only() {
        use std::os::unix::fs::PermissionsExt as _;

        let directory = test_directory();
        let scratch = ScratchFile::open(&directory).expect("scratch must open");

        let mode = scratch
            .file
            .metadata()
            .expect("scratch metadata must be readable")
            .permissions()
            .mode();
        assert_eq!(mode & 0o077, 0);
        let _ = std::fs::remove_dir_all(&directory);
    }

    #[cfg(unix)]
    #[test]
    fn scratch_root_symlink_is_rejected() {
        use std::os::unix::fs::symlink;

        let directory = test_directory();
        let link = directory.with_extension("link");
        symlink(&directory, &link).expect("create root symlink");

        assert!(
            ScratchFile::open(&link).is_err(),
            "scratch root symlinks must be rejected",
        );

        let _ = std::fs::remove_file(link);
        let _ = std::fs::remove_dir_all(directory);
    }
}
