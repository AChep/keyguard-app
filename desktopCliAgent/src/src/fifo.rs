//! Crash-recoverable FIFO ownership; never replace a user's file or follow a
//! replacement path. Every write and cleanup revalidates the pinned parent/inode.
use anyhow::{Context, Result, ensure};
use keyguard_agent_identity::socket_lifecycle::current_euid;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeSet,
    ffi::CString,
    fs::{self, File, OpenOptions},
    io::{Read, Seek, SeekFrom, Write},
    os::{
        fd::{AsRawFd, FromRawFd},
        unix::{
            ffi::OsStrExt,
            fs::{FileTypeExt, MetadataExt, OpenOptionsExt},
        },
    },
    path::{Component, Path, PathBuf},
    sync::{Mutex, PoisonError, TryLockError},
    time::Duration,
};

#[derive(Clone, Serialize, Deserialize)]
struct Identity {
    path: PathBuf,
    device: u64,
    inode: u64,
}

impl Identity {
    /// Makes an owner-only FIFO `name` in the pinned `parent`, at `path`.
    fn make(parent: &File, name: &CString, path: PathBuf) -> Result<Self> {
        ensure!(
            // SAFETY: parent is pinned and name is a single NUL-terminated filename.
            unsafe { libc::mkfifoat(parent.as_raw_fd(), name.as_ptr(), 0o600) } == 0,
            "path_unavailable"
        );
        let metadata = fs::symlink_metadata(&path)?;
        validate_fifo(&metadata)?;
        Ok(Self::of(path, &metadata))
    }

    fn of(path: PathBuf, metadata: &fs::Metadata) -> Self {
        Self {
            path,
            device: metadata.dev(),
            inode: metadata.ino(),
        }
    }

    fn matches(&self, metadata: &fs::Metadata) -> bool {
        self.device == metadata.dev() && self.inode == metadata.ino()
    }
}

#[derive(Serialize, Deserialize)]
struct Identities {
    // Keep the original state-file format readable after an upgrade.
    #[serde(flatten)]
    current: Identity,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pending: Option<Identity>,
}

/// The identities in a record slot, and how recent they are.
#[derive(Serialize, Deserialize)]
struct Record<T> {
    // Records from before the slots alternated have none.
    #[serde(default)]
    sequence: u64,
    #[serde(flatten)]
    identities: T,
}

struct State {
    identities: Identities,
    /// The two record slots: the primary file, whose inode retains the
    /// record's flock across every update, and the journal. A save rewrites
    /// the slot without the newest record, so an interrupted write leaves it.
    slots: [File; 2],
    /// The sequence of the newest record, and its slot.
    sequence: u64,
    newest: usize,
    /// The sequence of the newest synced record.
    synced: u64,
}

impl State {
    /// Saves the identities over the older record. A save that is not
    /// `durable` may be lost to a power failure, so it never overwrites the
    /// last synced record: that one must remain to recover from.
    fn save(&mut self, durable: bool) -> Result<()> {
        // Unless the newest record is synced, the older slot holds that one.
        let durable = durable || self.synced != self.sequence;
        let slot = 1 - self.newest;
        let sequence = self.sequence + 1;
        let bytes = serde_json::to_vec(&Record {
            sequence,
            identities: &self.identities,
        })?;
        let file = &mut self.slots[slot];
        file.set_len(0)?;
        file.seek(SeekFrom::Start(0))?;
        file.write_all(&bytes)?;
        if durable {
            file.sync_all()?;
            self.synced = sequence;
        }
        self.sequence = sequence;
        self.newest = slot;
        Ok(())
    }
}

/// The name of the ownership record of mount `id` in the state directory.
fn record_digest(id: &str) -> String {
    hex::encode(Sha256::digest(id.as_bytes()))
}

/// Holds a record exclusively, as long as its descriptor stays open.
fn lock_state(state: &File) -> Result<()> {
    ensure!(
        // SAFETY: state is a live descriptor; nonblocking exclusive flock does not alter contents.
        unsafe { libc::flock(state.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } == 0,
        "mount_busy"
    );
    Ok(())
}

/// Reads a locked record: the complete slot with the newest sequence, and
/// that slot. Without sequences, a complete journal is the newer one.
fn read_record(slots: &mut [File; 2]) -> Result<Option<(Record<Identities>, usize)>> {
    let mut newest: Option<(Record<Identities>, usize)> = None;
    let mut empty = true;
    for (slot, file) in slots.iter_mut().enumerate() {
        let mut bytes = Vec::new();
        file.read_to_end(&mut bytes)?;
        empty &= bytes.is_empty();
        if let Ok(record) = serde_json::from_slice::<Record<Identities>>(&bytes)
            && newest
                .as_ref()
                .is_none_or(|(newest, _)| record.sequence >= newest.sequence)
        {
            newest = Some((record, slot));
        }
    }
    ensure!(newest.is_some() || empty, "invalid_mount_state");
    Ok(newest)
}

fn open_state(path: &Path) -> Result<File> {
    let file = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC)
        .open(path)?;
    let metadata = file.metadata()?;
    ensure!(
        metadata.is_file()
            && metadata.nlink() == 1
            && metadata.uid() == current_euid()
            && metadata.mode() & 0o077 == 0
            && metadata.len() <= 16384,
        "unsafe_mount_state"
    );
    Ok(file)
}

pub(crate) struct Fifo {
    requested_parent: PathBuf,
    canonical_parent: PathBuf,
    parent: File,
    /// Group and other write bits of the parent accepted at creation, and its group.
    parent_write: u32,
    parent_gid: libc::gid_t,
    name: CString,
    path: PathBuf,
    state: Mutex<State>,
    _grant: Option<Grant>,
}

/// Access to the directory of a FIFO, which the app grants to the sandboxed
/// macOS agent.
#[cfg(target_os = "macos")]
pub(crate) type Grant = crate::bookmark::DirectoryGrant;
#[cfg(not(target_os = "macos"))]
pub(crate) type Grant = std::convert::Infallible;

/// Opens the directory grant of the FIFO at `requested`.
pub(crate) fn open_grant(requested: &Path, bookmark: &[u8]) -> Result<Grant> {
    #[cfg(target_os = "macos")]
    return Grant::open(bookmark, requested.parent().context("invalid_path")?);
    #[cfg(not(target_os = "macos"))]
    {
        let _ = (requested, bookmark);
        anyhow::bail!("unsupported_bookmark")
    }
}

/// Creates or tightens an owner-only directory, as the other agents do.
pub(crate) fn private_directory(path: &Path) -> Result<()> {
    // A fresh system may not have the directory's parents yet.
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    keyguard_agent_identity::socket_lifecycle::SocketLifecycle::new("cli")
        .ensure_safe_managed_parent_dir(path, current_euid())
}

/// Whether only this user can replace entries in a directory. Group write
/// is accepted for the user's private group: systems with user private
/// groups create project directories with mode 0775.
fn is_owner_writable(metadata: &fs::Metadata) -> bool {
    metadata.uid() == current_euid()
        && metadata.mode() & 0o002 == 0
        && (metadata.mode() & 0o020 == 0 || is_private_group(metadata.gid()))
}

/// A user private group: the user's primary group, named after the user,
/// with no other members.
fn is_private_group(gid: libc::gid_t) -> bool {
    use std::ffi::{CStr, c_char};
    let mut user_buffer = vec![0 as c_char; 16 * 1024];
    // SAFETY: passwd holds only integers and pointers, for which zero is valid.
    let mut user: libc::passwd = unsafe { std::mem::zeroed() };
    let mut found = std::ptr::null_mut();
    // SAFETY: every pointer is valid for the stated buffer length during the call.
    let status = unsafe {
        libc::getpwuid_r(
            current_euid(),
            &mut user,
            user_buffer.as_mut_ptr(),
            user_buffer.len(),
            &mut found,
        )
    };
    if status != 0 || found.is_null() || user.pw_gid != gid {
        return false;
    }
    // SAFETY: a found entry points its name into user_buffer, NUL-terminated.
    let user_name = unsafe { CStr::from_ptr(user.pw_name) };
    let mut group_buffer = vec![0 as c_char; 16 * 1024];
    // SAFETY: group holds only integers and pointers, for which zero is valid.
    let mut group: libc::group = unsafe { std::mem::zeroed() };
    let mut found = std::ptr::null_mut();
    // SAFETY: every pointer is valid for the stated buffer length during the call.
    let status = unsafe {
        libc::getgrgid_r(
            gid,
            &mut group,
            group_buffer.as_mut_ptr(),
            group_buffer.len(),
            &mut found,
        )
    };
    // SAFETY: a found entry points its name into group_buffer, NUL-terminated.
    if status != 0 || found.is_null() || unsafe { CStr::from_ptr(group.gr_name) } != user_name {
        return false;
    }
    let mut member = group.gr_mem;
    if member.is_null() {
        return true;
    }
    loop {
        // SAFETY: gr_mem is a null-terminated array of NUL-terminated names in group_buffer.
        let name = unsafe { *member };
        if name.is_null() {
            return true;
        }
        // SAFETY: name is a non-null member of that array.
        if unsafe { CStr::from_ptr(name) } != user_name {
            return false;
        }
        // SAFETY: the array continues until its null terminator, not yet reached.
        member = unsafe { member.add(1) };
    }
}

impl Fifo {
    pub(crate) fn create(
        id: &str,
        requested: &Path,
        state_dir: &Path,
        grant: Option<Grant>,
    ) -> Result<Self> {
        ensure!(
            requested.is_absolute()
                && !requested
                    .components()
                    .any(|c| matches!(c, Component::ParentDir | Component::CurDir)),
            "invalid_path"
        );
        let requested_parent = requested.parent().context("invalid_path")?.to_owned();
        let canonical_parent = fs::canonicalize(&requested_parent).context("path_unavailable")?;
        let parent = OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC)
            .open(&canonical_parent)?;
        let metadata = parent.metadata()?;
        ensure!(is_owner_writable(&metadata), "unsafe_parent_directory");
        let (parent_write, parent_gid) = (metadata.mode() & 0o022, metadata.gid());
        let filename = requested.file_name().context("invalid_path")?;
        let name = CString::new(filename.as_bytes()).context("invalid_path")?;
        let path = canonical_parent.join(filename);
        private_directory(state_dir)?;
        let digest = record_digest(id);
        let state = open_state(&state_dir.join(format!("{digest}.json")))?;
        lock_state(&state)?;
        let journal = open_state(&state_dir.join(format!("{digest}.pending.json")))?;
        let mut slots = [state, journal];
        let record = read_record(&mut slots)?;
        // Without a record, the first save goes to the primary file.
        let (sequence, newest) = record
            .as_ref()
            .map_or((0, 1), |(record, slot)| (record.sequence, *slot));
        let old = record.map(|(record, _)| record.identities);
        let existing = fs::symlink_metadata(&path);
        let identity = match existing {
            Ok(metadata) => {
                let previous = old.as_ref().context("path_exists")?;
                ensure!(
                    previous.current.path == path
                        && (previous.current.matches(&metadata)
                            || previous
                                .pending
                                .as_ref()
                                .is_some_and(|p| p.matches(&metadata))),
                    "path_exists"
                );
                validate_fifo(&metadata)?;
                Identity::of(path.clone(), &metadata)
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                Identity::make(&parent, &name, path.clone())?
            }
            Err(_) => anyhow::bail!("path_unavailable"),
        };
        // An exchange may have stopped on either side of the atomic rename.
        // Remove only the recorded sibling inode, never a replacement file.
        if let Some(previous) = old.as_ref()
            && let Some(pending) = previous.pending.as_ref()
        {
            remove_pending(&parent, &path, &previous.current, pending)?;
        }
        let mut state = State {
            identities: Identities {
                current: identity,
                pending: None,
            },
            slots,
            sequence,
            newest,
            synced: sequence,
        };
        state.save(true)?;
        let fifo = Self {
            requested_parent,
            canonical_parent,
            parent,
            parent_write,
            parent_gid,
            name,
            path,
            state: Mutex::new(state),
            _grant: grant,
        };
        fifo.revalidate()?;
        Ok(fifo)
    }

    pub(crate) fn revalidate(&self) -> Result<()> {
        let state = self.state.lock().unwrap_or_else(PoisonError::into_inner);
        self.revalidate_identity(&state.identities.current)
    }

    fn revalidate_identity(&self, identity: &Identity) -> Result<()> {
        ensure!(
            fs::canonicalize(&self.requested_parent)? == self.canonical_parent,
            "parent_changed"
        );
        let parent = fs::metadata(&self.canonical_parent)?;
        let pinned = self.parent.metadata()?;
        // Write access may only narrow after creation.
        let write = parent.mode() & 0o022;
        ensure!(
            parent.dev() == pinned.dev()
                && parent.ino() == pinned.ino()
                && write & !self.parent_write == 0
                && (write == 0 || parent.gid() == self.parent_gid),
            "parent_changed"
        );
        let metadata = fs::symlink_metadata(&self.path)?;
        validate_fifo(&metadata)?;
        ensure!(identity.matches(&metadata), "path_changed");
        Ok(())
    }

    /// Nonblocking open returns None until a reader is present, without receiving a secret.
    pub(crate) fn open_writer(&self) -> Result<Option<File>> {
        let state = self.state.lock().unwrap_or_else(PoisonError::into_inner);
        let identity = &state.identities.current;
        self.revalidate_identity(identity)?;
        let writer = self.open_at(identity)?;
        if writer.is_some() {
            self.revalidate_identity(identity)?;
        }
        Ok(writer)
    }

    /// Like `open_writer`, but cheap enough to poll: it only checks that the
    /// path still leads to this FIFO, so it never opens a user's file. The
    /// full check runs on `accept`, and from time to time while idle.
    pub(crate) fn probe(&self) -> Result<Option<File>> {
        let state = match self.state.try_lock() {
            Ok(state) => state,
            Err(TryLockError::Poisoned(error)) => error.into_inner(),
            // A check holds the record; poll again later.
            Err(TryLockError::WouldBlock) => return Ok(None),
        };
        let identity = &state.identities.current;
        let metadata = fs::symlink_metadata(&self.path)?;
        validate_fifo(&metadata)?;
        ensure!(identity.matches(&metadata), "path_changed");
        self.open_at(identity)
    }

    fn open_at(&self, identity: &Identity) -> Result<Option<File>> {
        // SAFETY: parent is pinned, name is terminated, and no-follow rejects replacement links.
        let fd = unsafe {
            libc::openat(
                self.parent.as_raw_fd(),
                self.name.as_ptr(),
                libc::O_WRONLY | libc::O_NONBLOCK | libc::O_CLOEXEC | libc::O_NOFOLLOW,
            )
        };
        if fd < 0 {
            let error = std::io::Error::last_os_error();
            if error.raw_os_error() == Some(libc::ENXIO) {
                return Ok(None);
            }
            return Err(error.into());
        }
        // SAFETY: successful openat transferred this descriptor exclusively.
        let file = unsafe { File::from_raw_fd(fd) };
        let metadata = file.metadata()?;
        validate_fifo(&metadata)?;
        ensure!(identity.matches(&metadata), "path_changed");
        Ok(Some(file))
    }

    /// Pins the readers of a `probe` writer to one delivery, and publishes a
    /// fresh FIFO before this writer can close. Old descriptors can never
    /// read its successor. Syncs the record, so it may wait on the disk.
    pub(crate) fn accept(&self, writer: File) -> Result<File> {
        let mut state = self.state.lock().unwrap_or_else(PoisonError::into_inner);
        ensure!(
            state.identities.current.matches(&writer.metadata()?),
            "path_changed"
        );
        self.rotate(&mut state)?;
        Ok(writer)
    }

    fn rotate(&self, state: &mut State) -> Result<()> {
        self.prepare_rotation(state)?;
        let pending = state
            .identities
            .pending
            .as_ref()
            .context("invalid_mount_state")?;
        let name = CString::new(pending.path.file_name().context("invalid_path")?.as_bytes())?;
        self.revalidate_identity(&state.identities.current)?;
        let metadata = fs::symlink_metadata(&pending.path)?;
        validate_fifo(&metadata)?;
        ensure!(pending.matches(&metadata), "path_changed");
        exchange(&self.parent, &self.name, &name)?;
        // Both identities were synced before the exchange, so a restart can
        // recognize the published inode even before the final state write.
        let previous = state.identities.current.clone();
        state.identities.current.device = pending.device;
        state.identities.current.inode = pending.inode;
        let pending = state
            .identities
            .pending
            .as_mut()
            .context("invalid_mount_state")?;
        pending.device = previous.device;
        pending.inode = previous.inode;
        self.revalidate_identity(&state.identities.current)?;
        remove_pending(&self.parent, &self.path, &state.identities.current, pending)?;
        state.identities.pending = None;
        // The synced record names both inodes, so a restart recovers without
        // this one: it need not wait on the disk.
        state.save(false)
    }

    fn prepare_rotation(&self, state: &mut State) -> Result<()> {
        ensure!(state.identities.pending.is_none(), "rotation_incomplete");
        self.revalidate_identity(&state.identities.current)?;
        let mut random = [0u8; 16];
        getrandom::fill(&mut random).map_err(|_| anyhow::anyhow!("random_unavailable"))?;
        let name = CString::new(format!(".keyguard-fifo-{}", hex::encode(random)))?;
        let path = self.canonical_parent.join(name.to_str()?);
        state.identities.pending = Some(Identity::make(&self.parent, &name, path)?);
        // The record must name the new inode before the exchange publishes it.
        state.save(true)
    }
}

/// Exchanges two owned FIFOs without a missing-path window or deleting either
/// inode. Filesystems without atomic exchange support fail closed.
fn exchange(parent: &File, current: &CString, pending: &CString) -> Result<()> {
    #[cfg(target_os = "linux")]
    // SAFETY: both names are terminated, and parent is a live directory descriptor.
    let result = unsafe {
        libc::renameat2(
            parent.as_raw_fd(),
            current.as_ptr(),
            parent.as_raw_fd(),
            pending.as_ptr(),
            libc::RENAME_EXCHANGE,
        )
    };
    #[cfg(target_os = "macos")]
    // SAFETY: both names are terminated, and parent is a live directory descriptor.
    let result = unsafe {
        libc::renameatx_np(
            parent.as_raw_fd(),
            current.as_ptr(),
            parent.as_raw_fd(),
            pending.as_ptr(),
            libc::RENAME_SWAP,
        )
    };
    #[cfg(not(any(target_os = "linux", target_os = "macos")))]
    let result = {
        let _ = (parent, current, pending);
        anyhow::bail!("fifo_exchange_unsupported");
    };
    if result != 0 {
        return Err(std::io::Error::last_os_error()).context("fifo_exchange_unavailable");
    }
    Ok(())
}

fn remove_pending(
    parent: &File,
    path: &Path,
    current: &Identity,
    pending: &Identity,
) -> Result<()> {
    ensure!(
        pending.path.parent() == path.parent() && pending.path != path,
        "invalid_mount_state"
    );
    let metadata = match fs::symlink_metadata(&pending.path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error.into()),
    };
    validate_fifo(&metadata)?;
    ensure!(
        current.matches(&metadata) || pending.matches(&metadata),
        "path_changed"
    );
    let name = CString::new(pending.path.file_name().context("invalid_path")?.as_bytes())?;
    ensure!(
        // SAFETY: name is a single filename, its inode is owned, and parent is pinned.
        unsafe { libc::unlinkat(parent.as_raw_fd(), name.as_ptr(), 0) } == 0,
        "path_unavailable"
    );
    Ok(())
}

/// Removes the records of the mounts other than `keep`, and the FIFOs they
/// own, such as that of a mount disabled while the agent was not running.
/// A live agent holds its records, so they are skipped. A record whose FIFO
/// cannot be reached stays for a later sweep.
pub(crate) fn sweep<'a>(state_dir: &Path, keep: impl IntoIterator<Item = &'a str>) {
    let keep: BTreeSet<_> = keep.into_iter().map(record_digest).collect();
    let Ok(entries) = fs::read_dir(state_dir) else {
        return;
    };
    let orphans: Vec<_> = entries
        .flatten()
        .filter_map(|entry| {
            let name = entry.file_name().into_string().ok()?;
            let digest = name.strip_suffix(".json")?;
            (digest.len() == 64
                && digest
                    .bytes()
                    .all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
                && !keep.contains(digest))
            .then(|| digest.to_owned())
        })
        .collect();
    for digest in orphans {
        let _ = sweep_record(state_dir, &digest);
    }
}

fn sweep_record(state_dir: &Path, digest: &str) -> Result<()> {
    let path = state_dir.join(format!("{digest}.json"));
    let journal_path = state_dir.join(format!("{digest}.pending.json"));
    let state = open_state(&path)?;
    lock_state(&state)?;
    let journal = open_state(&journal_path)?;
    // The slots hold the lock until the record is gone.
    let mut slots = [state, journal];
    if let Some((Record { identities, .. }, _)) = read_record(&mut slots)? {
        if let Some(pending) = &identities.pending {
            remove_orphan(&pending.path, &identities)?;
        }
        remove_orphan(&identities.current.path, &identities)?;
    }
    // A primary record alone is complete, so it goes last.
    fs::remove_file(&journal_path)?;
    fs::remove_file(&path)?;
    Ok(())
}

/// Removes the FIFO at `path` if it is one of the recorded inodes; an
/// exchange may have stopped on either side. Anything else is the user's.
fn remove_orphan(path: &Path, identities: &Identities) -> Result<()> {
    let parent_path = path.parent().context("invalid_mount_state")?;
    let name = CString::new(path.file_name().context("invalid_mount_state")?.as_bytes())?;
    let parent = match OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC)
        .open(parent_path)
    {
        Ok(parent) => parent,
        // The FIFO went with its directory.
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error.into()),
    };
    let pinned = parent.metadata()?;
    ensure!(is_owner_writable(&pinned), "unsafe_parent_directory");
    let owned = |metadata: &fs::Metadata| {
        validate_fifo(metadata).is_ok()
            && (identities.current.matches(metadata)
                || identities
                    .pending
                    .as_ref()
                    .is_some_and(|p| p.matches(metadata)))
    };
    let metadata = match fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error.into()),
    };
    let resolved = fs::metadata(parent_path)?;
    ensure!(
        resolved.dev() == pinned.dev() && resolved.ino() == pinned.ino(),
        "parent_changed"
    );
    if !owned(&metadata) {
        return Ok(());
    }
    // Unlinking alone leaves a reader blocked in open waiting for a writer
    // forever. Release it with an empty EOF first, as Drop does.
    // SAFETY: parent is pinned, name is terminated, and no-follow rejects replacement links.
    let fd = unsafe {
        libc::openat(
            parent.as_raw_fd(),
            name.as_ptr(),
            libc::O_WRONLY | libc::O_NONBLOCK | libc::O_CLOEXEC | libc::O_NOFOLLOW,
        )
    };
    if fd >= 0 {
        // SAFETY: successful openat transferred this descriptor exclusively.
        let writer = unsafe { File::from_raw_fd(fd) };
        ensure!(owned(&writer.metadata()?), "path_changed");
    } else {
        let error = std::io::Error::last_os_error();
        // Without a reader there is none to release.
        if error.raw_os_error() != Some(libc::ENXIO) {
            return Err(error.into());
        }
    }
    ensure!(
        // SAFETY: name is a single filename in the pinned parent, and its inode is owned.
        unsafe { libc::unlinkat(parent.as_raw_fd(), name.as_ptr(), 0) } == 0,
        "path_unavailable"
    );
    Ok(())
}

/// One delivery to a reader. Closing the write end ends the reader's file, so
/// a delivery that stopped part way keeps it open until the reader closes,
/// even when its task is canceled: the reader never gets a truncated file.
pub(crate) struct Delivery<'a> {
    writer: Option<File>,
    content: &'a [u8],
    written: usize,
}

impl<'a> Delivery<'a> {
    pub(crate) fn new(writer: File, content: &'a [u8]) -> Self {
        // Linux can buffer all the content, so that one write completes it.
        #[cfg(target_os = "linux")]
        // SAFETY: writer is a live pipe descriptor; these calls only size its buffer.
        unsafe {
            let size = libc::fcntl(writer.as_raw_fd(), libc::F_GETPIPE_SZ);
            if usize::try_from(size).is_ok_and(|size| size < content.len()) {
                libc::fcntl(
                    writer.as_raw_fd(),
                    libc::F_SETPIPE_SZ,
                    content.len() as libc::c_int,
                );
            }
        }
        Self {
            writer: Some(writer),
            content,
            written: 0,
        }
    }

    pub(crate) fn is_complete(&self) -> bool {
        self.written == self.content.len()
    }

    fn is_partial(&self) -> bool {
        self.written > 0 && !self.is_complete()
    }

    /// Writes what the pipe accepts without blocking.
    pub(crate) fn write(&mut self) -> std::io::Result<()> {
        let Some(writer) = self.writer.as_mut() else {
            return Err(std::io::ErrorKind::BrokenPipe.into());
        };
        match writer.write(&self.content[self.written..])? {
            0 => Err(std::io::ErrorKind::WriteZero.into()),
            size => {
                self.written += size;
                Ok(())
            }
        }
    }
}

impl Drop for Delivery<'_> {
    fn drop(&mut self) {
        if let Some(writer) = self.writer.take()
            && self.is_partial()
            && reader_present(&writer).unwrap_or(true)
        {
            park(writer);
        }
    }
}

/// Write ends of partial deliveries whose task ended.
static PARKED: Mutex<Vec<File>> = Mutex::new(Vec::new());

/// Holds `writer` open until its reader closes.
fn park(writer: File) {
    let mut parked = PARKED.lock().unwrap_or_else(PoisonError::into_inner);
    parked.push(writer);
    // One thread closes them; it exits when none are left.
    let reaper = || {
        loop {
            std::thread::sleep(Duration::from_millis(250));
            let mut parked = PARKED.lock().unwrap_or_else(PoisonError::into_inner);
            parked.retain(|writer| reader_present(writer).unwrap_or(true));
            if parked.is_empty() {
                return;
            }
        }
    };
    if parked.len() == 1 && std::thread::Builder::new().spawn(reaper).is_err() {
        parked.clear();
    }
}

/// Whether a pipe still has a reader. Linux reports a closed reader to poll;
/// macOS fails a zero-length write instead.
pub(crate) fn reader_present(writer: &File) -> std::io::Result<bool> {
    let mut poll = libc::pollfd {
        fd: writer.as_raw_fd(),
        events: libc::POLLOUT,
        revents: 0,
    };
    loop {
        // SAFETY: poll reads and writes one valid pollfd and does not wait.
        if unsafe { libc::poll(&mut poll, 1, 0) } >= 0 {
            break;
        }
        let error = std::io::Error::last_os_error();
        if error.kind() != std::io::ErrorKind::Interrupted {
            return Err(error);
        }
    }
    if poll.revents & libc::POLLNVAL != 0 {
        return Err(std::io::Error::from_raw_os_error(libc::EBADF));
    }
    if poll.revents & (libc::POLLERR | libc::POLLHUP) != 0 {
        return Ok(false);
    }
    loop {
        // SAFETY: writer is a live descriptor and a zero-length write transfers no data.
        if unsafe { libc::write(writer.as_raw_fd(), b"".as_ptr().cast(), 0) } == 0 {
            return Ok(true);
        }
        let error = std::io::Error::last_os_error();
        match error.kind() {
            std::io::ErrorKind::BrokenPipe => return Ok(false),
            std::io::ErrorKind::Interrupted => continue,
            _ => return Err(error),
        }
    }
}

/// Observes only this writer's reader cohort, without writing secret bytes.
pub(crate) async fn reader_closed(writer: &File) -> std::io::Result<()> {
    while reader_present(writer)? {
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    Ok(())
}

fn validate_fifo(metadata: &fs::Metadata) -> Result<()> {
    ensure!(
        metadata.file_type().is_fifo()
            && metadata.uid() == current_euid()
            && metadata.nlink() == 1
            && metadata.mode() & 0o777 == 0o600,
        "unsafe_fifo"
    );
    Ok(())
}

impl Drop for Fifo {
    fn drop(&mut self) {
        // Release any reader currently blocked in open with an empty EOF before
        // removing the pathname. This opens only our identity-checked FIFO.
        let _ = self.open_writer();
        if self.revalidate().is_ok() {
            let state = self.state.get_mut().unwrap_or_else(PoisonError::into_inner);
            if let Some(pending) = &state.identities.pending
                && remove_pending(&self.parent, &self.path, &state.identities.current, pending)
                    .is_err()
            {
                return;
            }
            // SAFETY: revalidation proves name still denotes our FIFO within the pinned directory.
            if unsafe { libc::unlinkat(self.parent.as_raw_fd(), self.name.as_ptr(), 0) } == 0 {
                for slot in &mut state.slots {
                    let _ = slot.set_len(0);
                    let _ = slot.sync_all();
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;

    fn reader(path: &Path) -> File {
        OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_NONBLOCK)
            .open(path)
            .unwrap()
    }

    fn simulate_crash(mut fifo: Fifo) {
        // Release process resources while skipping normal pathname cleanup.
        fifo.requested_parent.push("missing-after-crash");
        drop(fifo);
    }

    /// Leaves the start of a record, as a write cut off by a power failure.
    fn tear(file: &mut File) {
        file.set_len(0).unwrap();
        file.seek(SeekFrom::Start(0)).unwrap();
        file.write_all(b"{\"path\":").unwrap();
    }

    impl Fifo {
        fn accept_reader(&self) -> Result<Option<File>> {
            self.probe()?.map(|writer| self.accept(writer)).transpose()
        }
    }

    #[test]
    fn accepted_readers_are_isolated_from_later_opens() {
        let temp = tempfile::tempdir().unwrap();
        fs::set_permissions(temp.path(), fs::Permissions::from_mode(0o700)).unwrap();
        let path = temp.path().join(".env");
        let fifo = Fifo::create("id", &path, &temp.path().join("state"), None).unwrap();
        let mut first = reader(&path);
        let another_first = reader(&path);
        let mut first_writer = fifo.accept_reader().unwrap().unwrap();
        let mut next = reader(&path);
        let mut next_writer = fifo.accept_reader().unwrap().unwrap();
        assert_ne!(
            first.metadata().unwrap().ino(),
            next.metadata().unwrap().ino()
        );
        assert!(reader_present(&first_writer).unwrap());
        drop(another_first);
        assert!(reader_present(&first_writer).unwrap());
        first_writer.write_all(b"first").unwrap();
        next_writer.write_all(b"next").unwrap();
        drop(first_writer);
        drop(next_writer);
        let mut content = String::new();
        first.read_to_string(&mut content).unwrap();
        assert_eq!(content, "first");
        let mut content = String::new();
        next.read_to_string(&mut content).unwrap();
        assert_eq!(content, "next");
        assert_eq!(first.read(&mut [0]).unwrap(), 0);
        let mut final_reader = reader(&path);
        let final_writer = fifo.accept_reader().unwrap().unwrap();
        assert!(reader_present(&final_writer).unwrap());
        drop(final_reader);
        assert!(!reader_present(&final_writer).unwrap());
        // Other reader cohorts still exist, but they cannot keep this writer alive.
        final_reader = reader(&path);
        assert!(!reader_present(&final_writer).unwrap());
        drop(final_reader);
    }

    #[test]
    fn interrupted_rotations_recover_before_and_after_exchange() {
        for exchanged in [false, true] {
            // The save after the exchange is not synced, so a power failure
            // can lose it or cut it off.
            for torn_save in [false, true] {
                let temp = tempfile::tempdir().unwrap();
                fs::set_permissions(temp.path(), fs::Permissions::from_mode(0o700)).unwrap();
                let path = temp.path().join(".env");
                let state_dir = temp.path().join("state");
                let fifo = Fifo::create("id", &path, &state_dir, None).unwrap();
                let pending_path;
                {
                    let mut state = fifo.state.lock().unwrap();
                    fifo.prepare_rotation(&mut state).unwrap();
                    let pending = state.identities.pending.as_ref().unwrap();
                    pending_path = pending.path.clone();
                    if exchanged {
                        let name =
                            CString::new(pending.path.file_name().unwrap().as_bytes()).unwrap();
                        exchange(&fifo.parent, &fifo.name, &name).unwrap();
                    }
                    if torn_save {
                        let slot = 1 - state.newest;
                        tear(&mut state.slots[slot]);
                    }
                }
                simulate_crash(fifo);
                let recovered = Fifo::create("id", &path, &state_dir, None).unwrap();
                recovered.revalidate().unwrap();
                assert!(!pending_path.exists());
                assert!(recovered.state.lock().unwrap().identities.pending.is_none());
                drop(recovered);
                assert!(!path.exists());
            }
        }
    }

    #[test]
    fn a_record_is_the_newest_complete_slot() {
        let temp = tempfile::tempdir().unwrap();
        let open = |name: &str| {
            OpenOptions::new()
                .read(true)
                .write(true)
                .create(true)
                .truncate(true)
                .open(temp.path().join(name))
                .unwrap()
        };
        let record = |sequence: Option<u64>, inode: u64| {
            let mut record = serde_json::json!({ "path": "/p/.env", "device": 1, "inode": inode });
            if let Some(sequence) = sequence {
                record["sequence"] = sequence.into();
            }
            serde_json::to_vec(&record).unwrap()
        };
        let read = |primary: &[u8], journal: &[u8]| {
            let mut slots = [open("primary"), open("journal")];
            slots[0].write_all(primary).unwrap();
            slots[1].write_all(journal).unwrap();
            for slot in &mut slots {
                slot.seek(SeekFrom::Start(0)).unwrap();
            }
            read_record(&mut slots)
                .map(|record| record.map(|(record, slot)| (record.identities.current.inode, slot)))
        };
        assert_eq!(read(b"", b"").unwrap(), None);
        assert_eq!(
            read(&record(Some(3), 30), &record(Some(2), 20)).unwrap(),
            Some((30, 0))
        );
        assert_eq!(
            read(&record(Some(3), 30), &record(Some(4), 40)).unwrap(),
            Some((40, 1))
        );
        // A cut off newer record leaves the older one.
        assert_eq!(
            read(&record(Some(3), 30), b"{\"path\":").unwrap(),
            Some((30, 0))
        );
        // A complete journal is newer than a record without sequences.
        assert_eq!(
            read(&record(None, 10), &record(None, 11)).unwrap(),
            Some((11, 1))
        );
        assert_eq!(read(&record(None, 10), b"").unwrap(), Some((10, 0)));
        assert!(read(b"{\"path\":", b"").is_err());
    }

    #[test]
    fn an_unsynced_save_never_overwrites_the_synced_record() {
        let temp = tempfile::tempdir().unwrap();
        fs::set_permissions(temp.path(), fs::Permissions::from_mode(0o700)).unwrap();
        let path = temp.path().join(".env");
        let fifo = Fifo::create("id", &path, &temp.path().join("state"), None).unwrap();
        let mut state = fifo.state.lock().unwrap();
        let synced = state.synced;
        assert_eq!(synced, state.sequence);
        state.save(false).unwrap();
        assert_eq!(state.synced, synced);
        // The next save would overwrite the synced record, so it is synced.
        state.save(false).unwrap();
        assert_eq!(state.synced, state.sequence);
        state.save(false).unwrap();
        assert_eq!(state.synced, state.sequence - 1);
    }

    #[test]
    fn a_probe_never_opens_a_replacement() {
        let temp = tempfile::tempdir().unwrap();
        fs::set_permissions(temp.path(), fs::Permissions::from_mode(0o700)).unwrap();
        let path = temp.path().join(".env");
        let fifo = Fifo::create("id", &path, &temp.path().join("state"), None).unwrap();
        assert!(fifo.probe().unwrap().is_none());
        fs::remove_file(&path).unwrap();
        let name = CString::new(path.as_os_str().as_bytes()).unwrap();
        // SAFETY: name is a valid C path and permissions are a valid mode.
        assert_eq!(unsafe { libc::mkfifo(name.as_ptr(), 0o600) }, 0);
        // A reader blocked in open returns only once a writer opens.
        let foreign_reader = std::thread::spawn({
            let path = path.clone();
            move || fs::read(path)
        });
        std::thread::sleep(Duration::from_millis(200));
        assert!(fifo.probe().is_err());
        std::thread::sleep(Duration::from_millis(100));
        assert!(!foreign_reader.is_finished());
        drop(
            OpenOptions::new()
                .write(true)
                .custom_flags(libc::O_NONBLOCK)
                .open(&path)
                .unwrap(),
        );
        assert_eq!(foreign_reader.join().unwrap().unwrap(), b"");
    }

    #[test]
    fn rotation_and_recovery_preserve_replacement_files() {
        let temp = tempfile::tempdir().unwrap();
        fs::set_permissions(temp.path(), fs::Permissions::from_mode(0o700)).unwrap();
        let path = temp.path().join(".env");
        let state_dir = temp.path().join("state");
        let fifo = Fifo::create("id", &path, &state_dir, None).unwrap();
        let pending_path;
        {
            let mut state = fifo.state.lock().unwrap();
            fifo.prepare_rotation(&mut state).unwrap();
            pending_path = state.identities.pending.as_ref().unwrap().path.clone();
        }
        fs::remove_file(&pending_path).unwrap();
        fs::write(&pending_path, b"user temporary file").unwrap();
        simulate_crash(fifo);
        assert!(Fifo::create("id", &path, &state_dir, None).is_err());
        assert_eq!(fs::read(&pending_path).unwrap(), b"user temporary file");
        assert!(fs::symlink_metadata(&path).unwrap().file_type().is_fifo());

        fs::remove_file(&path).unwrap();
        fs::write(&path, b"user environment file").unwrap();
        assert!(Fifo::create("id", &path, &state_dir, None).is_err());
        assert_eq!(fs::read(&path).unwrap(), b"user environment file");
    }

    #[test]
    fn an_exchange_error_leaves_the_original_fifo_and_cleans_its_pending_inode() {
        let temp = tempfile::tempdir().unwrap();
        fs::set_permissions(temp.path(), fs::Permissions::from_mode(0o700)).unwrap();
        let path = temp.path().join(".env");
        let fifo = Fifo::create("id", &path, &temp.path().join("state"), None).unwrap();
        let pending_path;
        {
            let mut state = fifo.state.lock().unwrap();
            fifo.prepare_rotation(&mut state).unwrap();
            pending_path = state.identities.pending.as_ref().unwrap().path.clone();
            // Exercise an OS exchange error without depending on an optional
            // filesystem or falling back to a non-atomic rename.
            let missing = CString::new("missing-fifo").unwrap();
            assert!(exchange(&fifo.parent, &fifo.name, &missing).is_err());
        }
        fifo.revalidate().unwrap();
        drop(fifo);
        assert!(!path.exists());
        assert!(!pending_path.exists());
    }

    #[test]
    fn never_overwrites_a_regular_file_or_foreign_fifo() {
        let temp = tempfile::tempdir().unwrap();
        fs::set_permissions(temp.path(), fs::Permissions::from_mode(0o700)).unwrap();
        let path = temp.path().join(".env");
        fs::write(&path, b"precious").unwrap();
        assert!(Fifo::create("id", &path, &temp.path().join("state"), None).is_err());
        assert_eq!(fs::read(&path).unwrap(), b"precious");
        fs::remove_file(&path).unwrap();
        let name = CString::new(path.as_os_str().as_bytes()).unwrap();
        // SAFETY: name is a valid C path and permissions are a valid mode.
        assert_eq!(unsafe { libc::mkfifo(name.as_ptr(), 0o600) }, 0);
        assert!(Fifo::create("id", &path, &temp.path().join("state"), None).is_err());
        assert!(fs::symlink_metadata(path).unwrap().file_type().is_fifo());
    }

    #[test]
    fn replacement_file_survives_cleanup() {
        let temp = tempfile::tempdir().unwrap();
        fs::set_permissions(temp.path(), fs::Permissions::from_mode(0o700)).unwrap();
        let path = temp.path().join(".env");
        let fifo = Fifo::create("id", &path, &temp.path().join("state"), None).unwrap();
        fs::remove_file(&path).unwrap();
        fs::write(&path, b"replacement").unwrap();
        assert!(fifo.open_writer().is_err());
        drop(fifo);
        assert_eq!(fs::read(path).unwrap(), b"replacement");
    }

    #[test]
    fn a_partial_delivery_withholds_eof_until_the_reader_closes() {
        let temp = tempfile::tempdir().unwrap();
        fs::set_permissions(temp.path(), fs::Permissions::from_mode(0o700)).unwrap();
        let path = temp.path().join(".env");
        let fifo = Fifo::create("id", &path, &temp.path().join("state"), None).unwrap();
        let mut reader = OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_NONBLOCK)
            .open(&path)
            .unwrap();
        // More than a pipe buffers without privileges.
        let content = vec![b'x'; 16 * 1024 * 1024];
        let mut delivery = Delivery::new(fifo.open_writer().unwrap().unwrap(), &content);
        while delivery.write().is_ok() {}
        if delivery.is_complete() {
            return;
        }
        drop(delivery);
        let mut buffer = vec![0; content.len()];
        let mut read = 0;
        loop {
            match reader.read(&mut buffer[read..]) {
                Ok(0) => panic!("a partial delivery ended with EOF"),
                Ok(size) => read += size,
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => break,
                Err(error) => panic!("{error}"),
            }
        }
        assert!(read > 0 && read < content.len());
        drop(reader);
        let deadline = std::time::Instant::now() + Duration::from_secs(5);
        while !PARKED.lock().unwrap().is_empty() {
            assert!(
                std::time::Instant::now() < deadline,
                "the writer stayed open"
            );
            std::thread::sleep(Duration::from_millis(50));
        }
    }

    #[test]
    fn a_parked_delivery_does_not_block_the_next_reader() {
        let temp = tempfile::tempdir().unwrap();
        fs::set_permissions(temp.path(), fs::Permissions::from_mode(0o700)).unwrap();
        let path = temp.path().join(".env");
        let fifo = Fifo::create("id", &path, &temp.path().join("state"), None).unwrap();
        let stalled = reader(&path);
        // More than a pipe buffers without privileges.
        let content = vec![b'x'; 16 * 1024 * 1024];
        let mut delivery = Delivery::new(fifo.accept_reader().unwrap().unwrap(), &content);
        while delivery.write().is_ok() {}
        if delivery.is_complete() {
            return;
        }
        drop(delivery);
        let mut next = reader(&path);
        let mut delivery = Delivery::new(fifo.accept_reader().unwrap().unwrap(), b"next");
        delivery.write().unwrap();
        assert!(delivery.is_complete());
        drop(delivery);
        let mut content = String::new();
        next.read_to_string(&mut content).unwrap();
        assert_eq!(content, "next");
        drop(stalled);
    }

    #[test]
    fn a_group_writable_parent_is_accepted_only_for_a_private_group() {
        let temp = tempfile::tempdir().unwrap();
        fs::set_permissions(temp.path(), fs::Permissions::from_mode(0o775)).unwrap();
        let private = is_private_group(fs::metadata(temp.path()).unwrap().gid());
        let path = temp.path().join(".env");
        let fifo = Fifo::create("id", &path, &temp.path().join("state"), None);
        assert_eq!(fifo.is_ok(), private);
        fs::set_permissions(temp.path(), fs::Permissions::from_mode(0o777)).unwrap();
        drop(fifo);
        let fifo = Fifo::create(
            "other",
            &temp.path().join("b"),
            &temp.path().join("s"),
            None,
        );
        assert!(fifo.is_err());
    }

    #[test]
    fn no_reader_does_not_open_a_writer_and_normal_cleanup_removes_fifo() {
        let temp = tempfile::tempdir().unwrap();
        fs::set_permissions(temp.path(), fs::Permissions::from_mode(0o700)).unwrap();
        let path = temp.path().join(".env");
        let fifo = Fifo::create("id", &path, &temp.path().join("state"), None).unwrap();
        assert!(fifo.open_writer().unwrap().is_none());
        drop(fifo);
        assert!(!path.exists());
    }

    fn record_count(state_dir: &Path) -> usize {
        fs::read_dir(state_dir).unwrap().count()
    }

    #[test]
    fn a_sweep_removes_the_fifos_that_unlisted_mounts_left_after_a_crash() {
        // Without a rotation, with a prepared one, and with one stopped after its exchange.
        for exchanged in [None, Some(false), Some(true)] {
            let temp = tempfile::tempdir().unwrap();
            fs::set_permissions(temp.path(), fs::Permissions::from_mode(0o700)).unwrap();
            let path = temp.path().join(".env");
            let state_dir = temp.path().join("state");
            let fifo = Fifo::create("id", &path, &state_dir, None).unwrap();
            let mut pending_path = None;
            if let Some(exchanged) = exchanged {
                let mut state = fifo.state.lock().unwrap();
                fifo.prepare_rotation(&mut state).unwrap();
                let pending = state.identities.pending.as_ref().unwrap();
                if exchanged {
                    let name = CString::new(pending.path.file_name().unwrap().as_bytes()).unwrap();
                    exchange(&fifo.parent, &fifo.name, &name).unwrap();
                }
                pending_path = Some(pending.path.clone());
            }
            simulate_crash(fifo);
            sweep(&state_dir, ["listed"]);
            assert!(fs::symlink_metadata(&path).is_err());
            if let Some(pending_path) = pending_path {
                assert!(fs::symlink_metadata(pending_path).is_err());
            }
            assert_eq!(record_count(&state_dir), 0);
            // Another mount can take the path now.
            drop(Fifo::create("other", &path, &state_dir, None).unwrap());
        }
    }

    #[test]
    fn a_sweep_keeps_listed_and_live_mounts() {
        let temp = tempfile::tempdir().unwrap();
        fs::set_permissions(temp.path(), fs::Permissions::from_mode(0o700)).unwrap();
        let state_dir = temp.path().join("state");
        let live_path = temp.path().join("live.env");
        let live = Fifo::create("live", &live_path, &state_dir, None).unwrap();
        let listed_path = temp.path().join("listed.env");
        simulate_crash(Fifo::create("listed", &listed_path, &state_dir, None).unwrap());
        sweep(&state_dir, ["listed"]);
        live.revalidate().unwrap();
        // The listed mount still reclaims its FIFO.
        Fifo::create("listed", &listed_path, &state_dir, None)
            .unwrap()
            .revalidate()
            .unwrap();
        drop(live);
        assert!(!live_path.exists());
    }

    #[test]
    fn a_sweep_keeps_a_replacement_file_and_forgets_its_record() {
        let temp = tempfile::tempdir().unwrap();
        fs::set_permissions(temp.path(), fs::Permissions::from_mode(0o700)).unwrap();
        let path = temp.path().join(".env");
        let state_dir = temp.path().join("state");
        simulate_crash(Fifo::create("id", &path, &state_dir, None).unwrap());
        fs::remove_file(&path).unwrap();
        fs::write(&path, b"user environment file").unwrap();
        sweep(&state_dir, []);
        assert_eq!(fs::read(&path).unwrap(), b"user environment file");
        assert_eq!(record_count(&state_dir), 0);
    }

    #[test]
    fn a_sweep_retries_a_record_in_an_unsafe_directory() {
        let temp = tempfile::tempdir().unwrap();
        fs::set_permissions(temp.path(), fs::Permissions::from_mode(0o700)).unwrap();
        let directory = temp.path().join("project");
        fs::create_dir(&directory).unwrap();
        fs::set_permissions(&directory, fs::Permissions::from_mode(0o700)).unwrap();
        let path = directory.join(".env");
        let state_dir = temp.path().join("state");
        simulate_crash(Fifo::create("id", &path, &state_dir, None).unwrap());
        // Another user could replace the FIFO between the check and the removal.
        fs::set_permissions(&directory, fs::Permissions::from_mode(0o777)).unwrap();
        sweep(&state_dir, []);
        assert!(fs::symlink_metadata(&path).unwrap().file_type().is_fifo());
        assert_eq!(record_count(&state_dir), 2);
        fs::set_permissions(&directory, fs::Permissions::from_mode(0o700)).unwrap();
        sweep(&state_dir, []);
        assert!(fs::symlink_metadata(&path).is_err());
        assert_eq!(record_count(&state_dir), 0);
    }

    #[test]
    fn a_sweep_releases_a_reader_blocked_in_open() {
        let temp = tempfile::tempdir().unwrap();
        fs::set_permissions(temp.path(), fs::Permissions::from_mode(0o700)).unwrap();
        let path = temp.path().join(".env");
        let state_dir = temp.path().join("state");
        simulate_crash(Fifo::create("id", &path, &state_dir, None).unwrap());
        let started = std::sync::Arc::new(std::sync::Barrier::new(2));
        let reader = std::thread::spawn({
            let path = path.clone();
            let started = started.clone();
            move || {
                started.wait();
                fs::read(path)
            }
        });
        started.wait();
        // Give the reader time to block in open.
        std::thread::sleep(Duration::from_millis(200));
        sweep(&state_dir, []);
        assert!(fs::symlink_metadata(&path).is_err());
        let deadline = std::time::Instant::now() + Duration::from_secs(5);
        while !reader.is_finished() {
            assert!(
                std::time::Instant::now() < deadline,
                "the reader stayed blocked"
            );
            std::thread::sleep(Duration::from_millis(10));
        }
        assert_eq!(reader.join().unwrap().unwrap(), b"");
    }
}
