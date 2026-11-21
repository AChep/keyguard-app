//! Files under a root, reached from the root one folder at a time without
//! following links. The checks, and every later step on a file, go through
//! the descriptor of its folder, so whoever can write a folder on the way,
//! such as a sandboxed browser in its own folder, can't swap it for a link
//! in between and redirect a change.
use super::{private_group, root_of};
use crate::SetupError;
use anyhow::{Context, Result, bail, ensure};
use std::{
    ffi::{CStr, CString, OsStr},
    fs::{File, Metadata},
    io::{self, Write},
    os::{
        fd::{AsRawFd, FromRawFd},
        unix::{
            ffi::OsStrExt,
            fs::{MetadataExt, PermissionsExt},
        },
    },
    path::Path,
};

/// Opens a folder, and only a folder, to reach the files in it. That needs
/// only the permission to search it, as path lookups do, not to read it:
/// Linux's `O_PATH` and macOS's `O_SEARCH`. Such a descriptor works for each
/// `*at` call here and for `fstat`. Elsewhere, folders open to read.
#[cfg(any(target_os = "linux", target_os = "android"))]
const SEARCH: libc::c_int = libc::O_PATH | libc::O_DIRECTORY;
#[cfg(target_os = "macos")]
const SEARCH: libc::c_int = libc::O_SEARCH;
#[cfg(not(any(target_os = "linux", target_os = "android", target_os = "macos")))]
const SEARCH: libc::c_int = libc::O_RDONLY | libc::O_DIRECTORY;

/// What the checks need of a file.
#[derive(Clone, Copy)]
pub(super) struct Entry {
    kind: Kind,
    /// The type and permission bits.
    pub(super) mode: u32,
    uid: libc::uid_t,
    gid: libc::gid_t,
    links: u64,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Kind {
    File,
    Folder,
    Link,
    Other,
}

impl Entry {
    // The type of `mode_t` differs between Unix platforms.
    #[allow(clippy::unnecessary_cast)]
    fn new(mode: u32, uid: libc::uid_t, gid: libc::gid_t, links: u64) -> Self {
        let kind = match mode as libc::mode_t & libc::S_IFMT {
            libc::S_IFLNK => Kind::Link,
            libc::S_IFREG => Kind::File,
            libc::S_IFDIR => Kind::Folder,
            _ => Kind::Other,
        };
        Self {
            kind,
            mode,
            uid,
            gid,
            links,
        }
    }

    fn of(metadata: &Metadata) -> Self {
        Self::new(
            metadata.mode(),
            metadata.uid(),
            metadata.gid(),
            metadata.nlink(),
        )
    }

    // The types of these fields differ between Unix platforms.
    #[allow(clippy::unnecessary_cast)]
    fn of_stat(stat: &libc::stat) -> Self {
        Self::new(
            stat.st_mode as u32,
            stat.st_uid,
            stat.st_gid,
            stat.st_nlink as u64,
        )
    }

    /// Fails unless this is a file or folder of the user that others can't
    /// write, and a file has no other links.
    fn check(&self, path: &Path) -> Result<()> {
        ensure!(
            self.kind != Kind::Link,
            "refusing to replace or follow a symbolic link: {}",
            path.display()
        );
        ensure!(
            matches!(self.kind, Kind::File | Kind::Folder),
            "not a regular file or directory: {}",
            path.display()
        );
        // SAFETY: geteuid reads the current process's effective user ID.
        let uid = unsafe { libc::geteuid() };
        ensure!(
            self.uid == uid,
            "configuration belongs to another user: {}",
            path.display()
        );
        ensure!(
            self.mode & 0o002 == 0 && (self.mode & 0o020 == 0 || private_group(uid, self.gid)),
            "configuration is writable by another user: {}",
            path.display()
        );
        ensure!(
            self.kind != Kind::File || self.links == 1,
            "refusing a hard-linked configuration file: {}",
            path.display()
        );
        Ok(())
    }
}

/// [`super::check_safe_path`].
pub(super) fn check(roots: &[impl AsRef<Path>], path: &Path) -> Result<()> {
    let root = root_of(roots, path).with_context(|| unsafe_file(path))?;
    if path == root {
        open_root(root).with_context(|| unsafe_file(path))?;
        return Ok(());
    }
    if let Some(parent) = Parent::open(roots, path, false)? {
        parent.entry()?;
    }
    Ok(())
}

fn unsafe_file(path: &Path) -> SetupError {
    SetupError::UnsafeFile {
        path: path.to_owned(),
    }
}

/// The folder of a file, open after the checks of the folder and of each
/// folder above it up to the root.
pub(super) struct Parent<'a> {
    folder: File,
    name: CString,
    path: &'a Path,
}

impl<'a> Parent<'a> {
    /// `None`: a folder on the way is missing. With `create`, missing
    /// folders below the root are made, private, instead.
    pub(super) fn open(
        roots: &[impl AsRef<Path>],
        path: &'a Path,
        create: bool,
    ) -> Result<Option<Self>> {
        let unsafe_file = || unsafe_file(path);
        let root = root_of(roots, path).with_context(unsafe_file)?;
        let names: Vec<&OsStr> = path
            .strip_prefix(root)
            .with_context(unsafe_file)?
            .iter()
            .collect();
        let Some((name, folders)) = names.split_last() else {
            bail!(unsafe_file());
        };
        let Some(mut folder) = open_root(root).with_context(unsafe_file)? else {
            return Ok(None);
        };
        let mut current = root.to_owned();
        for &part in folders {
            current.push(part);
            let part = c_name(part).with_context(unsafe_file)?;
            match stat_at(&folder, &part).with_context(unsafe_file)? {
                Some(entry) => entry.check(&current).with_context(unsafe_file)?,
                // Fails like any other write.
                None if create => make_folder(&folder, &part)?,
                None => return Ok(None),
            }
            folder = open_at(&folder, &part, SEARCH, 0)
                .map_err(anyhow::Error::from)
                .and_then(|folder| {
                    Entry::of(&folder.metadata()?).check(&current)?;
                    Ok(folder)
                })
                .with_context(unsafe_file)?;
        }
        Ok(Some(Self {
            folder,
            name: c_name(name).with_context(unsafe_file)?,
            path,
        }))
    }

    /// The file, after its checks, or `None` if it is missing.
    pub(super) fn entry(&self) -> Result<Option<Entry>> {
        let entry = stat_at(&self.folder, &self.name).with_context(|| unsafe_file(self.path))?;
        if let Some(entry) = &entry {
            entry
                .check(self.path)
                .with_context(|| unsafe_file(self.path))?;
        }
        Ok(entry)
    }

    /// Opens the file to read, after its checks. A FIFO in its place opens
    /// without waiting for a writer.
    pub(super) fn open_file(&self) -> Result<Option<File>> {
        let file = match open_at(&self.folder, &self.name, libc::O_NONBLOCK, 0) {
            Ok(file) => file,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
            Err(error) => {
                // Name what is in the way, such as a link.
                self.entry()?;
                return Err(error).with_context(|| unsafe_file(self.path));
            }
        };
        file.metadata()
            .map_err(anyhow::Error::from)
            .and_then(|metadata| Entry::of(&metadata).check(self.path))
            .with_context(|| unsafe_file(self.path))?;
        Ok(Some(file))
    }

    pub(super) fn remove(&self) -> Result<()> {
        Ok(unlink_at(&self.folder, &self.name)?)
    }

    /// Writes `contents` to a new file in the folder, then renames it over
    /// the file.
    pub(super) fn replace(&self, contents: &[u8], mode: u32) -> Result<()> {
        let (mut temp, temp_name) = self.create_temp()?;
        let written = (|| {
            temp.set_permissions(std::fs::Permissions::from_mode(mode))?;
            temp.write_all(contents)?;
            temp.sync_all()?;
            rename_at(&self.folder, &temp_name, &self.name)
        })();
        if written.is_err() {
            let _ = unlink_at(&self.folder, &temp_name);
        }
        Ok(written?)
    }

    /// A new, private file with a random name in the folder.
    fn create_temp(&self) -> Result<(File, CString)> {
        use std::hash::{BuildHasher, Hasher};
        let mut attempts = 0;
        loop {
            let random = std::collections::hash_map::RandomState::new()
                .build_hasher()
                .finish();
            let name = CString::new(format!(".keyguard-{random:016x}.tmp"))?;
            let flags = libc::O_WRONLY | libc::O_CREAT | libc::O_EXCL;
            match open_at(&self.folder, &name, flags, 0o600) {
                Ok(file) => return Ok((file, name)),
                Err(error) if error.kind() == io::ErrorKind::AlreadyExists && attempts < 8 => {
                    attempts += 1;
                }
                Err(error) => return Err(error.into()),
            }
        }
    }
}

/// Opens the root, which must be a folder that passes the checks.
/// `None`: it is missing.
fn open_root(root: &Path) -> Result<Option<File>> {
    // Links above the root are the user's, and are followed.
    match std::fs::symlink_metadata(root) {
        Ok(metadata) => Entry::of(&metadata).check(root)?,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error.into()),
    }
    let root_name = c_name(root.as_os_str())?;
    // SAFETY: the name is a NUL-terminated string for the whole call.
    let fd = unsafe {
        libc::open(
            root_name.as_ptr(),
            SEARCH | libc::O_NOFOLLOW | libc::O_NONBLOCK | libc::O_CLOEXEC,
        )
    };
    if fd < 0 {
        return Err(io::Error::last_os_error().into());
    }
    // SAFETY: `open` returned a new descriptor that nothing else owns.
    let folder = unsafe { File::from_raw_fd(fd) };
    Entry::of(&folder.metadata()?).check(root)?;
    Ok(Some(folder))
}

fn c_name(name: &OsStr) -> io::Result<CString> {
    CString::new(name.as_bytes())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))
}

/// Opens `name` in `folder`, never through a link. `flags` add to a read,
/// or set the access themselves; `mode` is that of a created file.
fn open_at(folder: &File, name: &CStr, flags: libc::c_int, mode: libc::c_uint) -> io::Result<File> {
    let flags = flags | libc::O_NOFOLLOW | libc::O_CLOEXEC;
    // SAFETY: the folder's descriptor is open and the name is a
    // NUL-terminated string, both for the whole call.
    let fd = unsafe { libc::openat(folder.as_raw_fd(), name.as_ptr(), flags, mode) };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: `openat` returned a new descriptor that nothing else owns.
    Ok(unsafe { File::from_raw_fd(fd) })
}

/// The entry `name` in `folder` itself, even a link; `None` if missing.
fn stat_at(folder: &File, name: &CStr) -> io::Result<Option<Entry>> {
    let mut stat = std::mem::MaybeUninit::<libc::stat>::uninit();
    // SAFETY: the folder's descriptor is open, the name is a NUL-terminated
    // string, and `stat` is writable for a whole `struct stat`.
    let result = unsafe {
        libc::fstatat(
            folder.as_raw_fd(),
            name.as_ptr(),
            stat.as_mut_ptr(),
            libc::AT_SYMLINK_NOFOLLOW,
        )
    };
    if result != 0 {
        let error = io::Error::last_os_error();
        return match error.kind() {
            io::ErrorKind::NotFound => Ok(None),
            _ => Err(error),
        };
    }
    // SAFETY: a successful fstatat filled in `stat`.
    Ok(Some(Entry::of_stat(unsafe { stat.assume_init_ref() })))
}

/// Makes the private folder `name` in `folder`. One that someone made
/// meanwhile is checked as it opens.
fn make_folder(folder: &File, name: &CStr) -> io::Result<()> {
    // SAFETY: the folder's descriptor is open and the name is a
    // NUL-terminated string, both for the whole call.
    let result = unsafe { libc::mkdirat(folder.as_raw_fd(), name.as_ptr(), 0o700) };
    if result != 0 {
        let error = io::Error::last_os_error();
        if error.kind() != io::ErrorKind::AlreadyExists {
            return Err(error);
        }
    }
    Ok(())
}

fn unlink_at(folder: &File, name: &CStr) -> io::Result<()> {
    // SAFETY: the folder's descriptor is open and the name is a
    // NUL-terminated string, both for the whole call.
    let result = unsafe { libc::unlinkat(folder.as_raw_fd(), name.as_ptr(), 0) };
    if result != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

fn rename_at(folder: &File, from: &CStr, to: &CStr) -> io::Result<()> {
    // SAFETY: the folder's descriptor is open and both names are
    // NUL-terminated strings, all for the whole call.
    let result = unsafe {
        libc::renameat(
            folder.as_raw_fd(),
            from.as_ptr(),
            folder.as_raw_fd(),
            to.as_ptr(),
        )
    };
    if result != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}
