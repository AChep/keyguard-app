use crate::{AgentKind, Conflict, Edit, Environment, Plan, SetupError, planner};
use anyhow::{Context, Result, anyhow, ensure};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
#[cfg(not(unix))]
use std::io::Write;
use std::{
    fs::{self, File, OpenOptions, TryLockError},
    io::Read,
    path::{Component, Path, PathBuf},
    time::{Duration, Instant},
};

#[cfg(unix)]
mod nofollow;

const MAX_FILE_SIZE: u64 = 1024 * 1024;

pub(crate) fn observe(env: &Environment, plan: &mut Plan, path: &Path) -> Result<Option<String>> {
    let text = read(env, path)?;
    record_observation(plan, observation(path, text.as_deref()))?;
    Ok(text)
}

/// [`observe`] for a file that setup only reads. Dotfile managers often link
/// these into the home, so links and other owners are accepted.
pub(crate) fn observe_dependency(plan: &mut Plan, path: &Path) -> Result<Option<String>> {
    let text = read_text(path)?;
    record_observation(plan, observation(path, text.as_deref()))?;
    Ok(text)
}

fn observation(path: &Path, text: Option<&str>) -> crate::ObservedFile {
    crate::ObservedFile {
        path: path.to_owned(),
        sha256: text.map(|s| format!("{:x}", Sha256::digest(s.as_bytes()))),
    }
}

fn record_observation(plan: &mut Plan, observation: crate::ObservedFile) -> Result<()> {
    let path = &observation.path;
    if let Some(prior) = plan.observed_files.iter().find(|entry| &entry.path == path) {
        ensure!(prior == &observation, SetupError::Changed);
    } else {
        plan.observed_files.push(observation);
    }
    Ok(())
}

/// What Keyguard owns in each file. Undo removes only these blocks, so edits
/// made outside them survive, unlike restoring a snapshot of the whole file.
///
/// Each of [`AgentKind::blocks`] has a journal file of its own, so a version
/// of Keyguard that knows fewer blocks reads and rewrites only its own ones.
#[derive(Debug, Serialize, Deserialize)]
struct Journal {
    request: crate::Request,
    files: Vec<OwnedBlock>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct OwnedBlock {
    path: PathBuf,
    /// One of [`AgentKind::blocks`], from the name of the journal file.
    #[serde(skip)]
    id: &'static str,
    /// Keyguard created the file, so Undo deletes it once nothing else is left.
    created: bool,
    /// The exact block, markers included, as last written.
    block: String,
    /// The block this file held before an apply that may not have finished.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    previous: Option<String>,
}

impl OwnedBlock {
    fn matches(&self, block: &str) -> bool {
        self.block == block || self.previous.as_deref() == Some(block)
    }

    fn is(&self, path: &Path, id: &str) -> bool {
        self.path == path && self.id == id
    }
}

/// Where setup edits files: the home, and the Documents folder, which
/// Windows can redirect outside the home.
fn roots(env: &Environment) -> Vec<&Path> {
    [Some(&env.home), env.documents.as_ref()]
        .into_iter()
        .flatten()
        .map(PathBuf::as_path)
        .collect()
}

pub(crate) fn check_path(env: &Environment, path: &Path) -> Result<()> {
    check_safe_path(&roots(env), path)
}

/// Fails with [`SetupError::UnsafeFile`] unless `path` is inside one of
/// `roots`, and neither it nor a parent up to that root is a link, another
/// user's file, a file others can write, or a hard-linked file.
pub fn check_safe_path(roots: &[impl AsRef<Path>], path: &Path) -> Result<()> {
    #[cfg(unix)]
    return nofollow::check(roots, path);
    #[cfg(not(unix))]
    check(roots, path).with_context(|| SetupError::UnsafeFile {
        path: path.to_owned(),
    })
}

/// The root of `roots` that holds `path`.
fn root_of<'a>(roots: &'a [impl AsRef<Path>], path: &Path) -> Result<&'a Path> {
    let root = roots
        .iter()
        .map(AsRef::as_ref)
        .find(|root| path.is_absolute() && path.starts_with(root))
        .ok_or_else(|| {
            anyhow::anyhow!(
                "configuration must be inside the user home: {}",
                path.display()
            )
        })?;
    ensure!(
        !path
            .components()
            .any(|c| matches!(c, Component::ParentDir | Component::CurDir)),
        "configuration path must not contain dot components"
    );
    Ok(root)
}

#[cfg(not(unix))]
fn check(roots: &[impl AsRef<Path>], path: &Path) -> Result<()> {
    let root = root_of(roots, path)?;
    let mut parent = Some(path);
    while let Some(path) = parent {
        match fs::symlink_metadata(path) {
            Ok(meta) => {
                // On Windows this also covers junctions, but not OneDrive
                // folders, whose reparse points are not name surrogates.
                ensure!(
                    !meta.file_type().is_symlink(),
                    "refusing to replace or follow a symbolic link: {}",
                    path.display()
                );
                ensure!(
                    meta.is_file() || meta.is_dir(),
                    "not a regular file or directory: {}",
                    path.display()
                );
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => (),
            Err(error) => return Err(error.into()),
        }
        if path == root {
            break;
        }
        parent = path.parent();
    }
    Ok(())
}

/// Whether `gid` is the user's own primary group, named after the user.
/// Fedora and RHEL create one per user and use a umask of 002.
#[cfg(unix)]
fn private_group(uid: libc::uid_t, gid: libc::gid_t) -> bool {
    use std::{ffi::CStr, mem::MaybeUninit, ptr::null_mut};
    let mut user = MaybeUninit::<libc::passwd>::uninit();
    let mut group = MaybeUninit::<libc::group>::uninit();
    let mut user_buffer = [0 as libc::c_char; 4096];
    let mut group_buffer = [0 as libc::c_char; 4096];
    let mut user_result = null_mut();
    let mut group_result = null_mut();
    // SAFETY: each lookup writes only into its own record and buffer, which
    // outlive the reads. A failed or missing lookup leaves a null result.
    unsafe {
        libc::getpwuid_r(
            uid,
            user.as_mut_ptr(),
            user_buffer.as_mut_ptr(),
            user_buffer.len(),
            &mut user_result,
        ) == 0
            && !user_result.is_null()
            && libc::getgrgid_r(
                gid,
                group.as_mut_ptr(),
                group_buffer.as_mut_ptr(),
                group_buffer.len(),
                &mut group_result,
            ) == 0
            && !group_result.is_null()
            && (*user_result).pw_gid == gid
            && CStr::from_ptr((*user_result).pw_name) == CStr::from_ptr((*group_result).gr_name)
    }
}

pub(crate) fn read(env: &Environment, path: &Path) -> Result<Option<String>> {
    read_safe_text(&roots(env), path)
}

/// Reads a UTF-8 file of at most 1 MiB after [`check_safe_path`]; `None`
/// means the file is missing.
pub fn read_safe_text(roots: &[impl AsRef<Path>], path: &Path) -> Result<Option<String>> {
    let Some(file) = open_safe_file(roots, path)? else {
        return Ok(None);
    };
    let text = read_text_file(file).with_context(|| SetupError::UnsafeFile {
        path: path.to_owned(),
    })?;
    Ok(Some(text))
}

const NOT_A_SMALL_FILE: &str = "configuration is not a regular file smaller than 1 MiB";

/// Reads `file`, which must be a regular file of at most 1 MiB that holds
/// UTF-8 text.
pub fn read_text_file(mut file: File) -> Result<String> {
    let meta = file.metadata()?;
    ensure!(
        meta.is_file() && meta.len() <= MAX_FILE_SIZE,
        NOT_A_SMALL_FILE
    );
    let bytes = read_at_most(&mut file, MAX_FILE_SIZE).context(NOT_A_SMALL_FILE)?;
    String::from_utf8(bytes).context("configuration is not UTF-8 text")
}

/// Opens the file at `path` to read after [`check_safe_path`]. On Unix, it
/// follows no link on the way: a folder that someone swaps for a link after
/// the checks can't lead elsewhere. `None` means the file is missing. It
/// may be a folder or another kind of file.
pub fn open_safe_file(roots: &[impl AsRef<Path>], path: &Path) -> Result<Option<File>> {
    #[cfg(unix)]
    {
        match nofollow::Parent::open(roots, path, false)? {
            Some(parent) => parent.open_file(),
            None => Ok(None),
        }
    }
    #[cfg(not(unix))]
    {
        check_safe_path(roots, path)?;
        match File::open(path) {
            Ok(file) => Ok(Some(file)),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(error) => Err(error).with_context(|| SetupError::UnsafeFile {
                path: path.to_owned(),
            }),
        }
    }
}

/// Reads all of `reader`, which must hold at most `limit` bytes. A file can
/// grow after a check of its size, so this reads at most one byte more.
fn read_at_most(reader: impl Read, limit: u64) -> Result<Vec<u8>> {
    let mut bytes = Vec::new();
    reader
        .take(limit.saturating_add(1))
        .read_to_end(&mut bytes)?;
    ensure!(
        bytes.len() as u64 <= limit,
        "the file holds more than {limit} bytes"
    );
    Ok(bytes)
}

fn read_text(path: &Path) -> Result<Option<String>> {
    match fs::metadata(path) {
        Ok(meta) => {
            // Only a regular file opens: a FIFO would wait for a writer.
            let text = if meta.is_file() {
                File::open(path)
                    .map_err(anyhow::Error::from)
                    .and_then(read_text_file)
            } else {
                Err(anyhow!(NOT_A_SMALL_FILE))
            };
            let text = text.with_context(|| SetupError::UnsafeFile {
                path: path.to_owned(),
            })?;
            Ok(Some(text))
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(error) => Err(error.into()),
    }
}

fn mkdir(roots: &[impl AsRef<Path>], path: &Path) -> Result<()> {
    check_safe_path(roots, path)?;
    if path.exists() {
        ensure!(path.is_dir(), "not a directory: {}", path.display());
        return Ok(());
    }
    mkdir(
        roots,
        path.parent()
            .ok_or_else(|| anyhow::anyhow!("missing parent directory"))?,
    )?;
    private_folders().create(path)?;
    Ok(())
}

/// Makes folders that only the user can open.
fn private_folders() -> fs::DirBuilder {
    #[cfg_attr(not(unix), expect(unused_mut))]
    let mut builder = fs::DirBuilder::new();
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        builder.mode(0o700);
    }
    builder
}

fn write(env: &Environment, path: &Path, text: Option<&str>) -> Result<()> {
    write_safe_file(&roots(env), path, text.map(str::as_bytes), false)
}

/// Atomically replaces `path` with `contents`, or deletes it for `None`,
/// after [`check_safe_path`]. Missing parents become private directories.
/// A replaced file keeps its permissions; on Unix, `executable` sets them
/// to `rwxr-xr-x` instead.
///
/// On Unix, the checks, the temporary file, the rename and the removal all
/// go through one descriptor of the file's folder, opened without following
/// links: a folder that someone swaps for a link after the checks can't
/// redirect the change.
pub fn write_safe_file(
    roots: &[impl AsRef<Path>],
    path: &Path,
    contents: Option<&[u8]>,
    executable: bool,
) -> Result<()> {
    #[cfg(unix)]
    return write_checked_file(roots, path, contents, executable, || ());
    #[cfg(not(unix))]
    write_file(roots, path, contents, executable)
}

/// [`write_safe_file`], which runs `checked` between its checks and the
/// change.
#[cfg(unix)]
fn write_checked_file(
    roots: &[impl AsRef<Path>],
    path: &Path,
    contents: Option<&[u8]>,
    executable: bool,
    checked: impl FnOnce(),
) -> Result<()> {
    let Some(parent) = nofollow::Parent::open(roots, path, contents.is_some())? else {
        // A missing folder holds nothing to delete. A write makes missing
        // folders only below the root, so it fails without the root, as the
        // folder above the root is outside all of them.
        if contents.is_some() {
            let root = root_of(roots, path)?;
            let above = root.parent().unwrap_or(root);
            return Err(anyhow!(
                "configuration must be inside the user home: {}",
                above.display()
            ))
            .with_context(|| SetupError::UnsafeFile {
                path: above.to_owned(),
            });
        }
        return Ok(());
    };
    let current = parent.entry()?;
    checked();
    let Some(contents) = contents else {
        if current.is_some() {
            parent.remove()?;
        }
        return Ok(());
    };
    let mode = if executable {
        0o755
    } else {
        current.map_or(0o600, |entry| entry.mode & 0o7777)
    };
    parent.replace(contents, mode)
}

#[cfg(not(unix))]
fn write_file(
    roots: &[impl AsRef<Path>],
    path: &Path,
    contents: Option<&[u8]>,
    executable: bool,
) -> Result<()> {
    check_safe_path(roots, path)?;
    let Some(contents) = contents else {
        if path.exists() {
            fs::remove_file(path)?;
        }
        return Ok(());
    };
    let parent = path
        .parent()
        .ok_or_else(|| anyhow::anyhow!("missing parent directory"))?;
    mkdir(roots, parent)?;
    let mut temp = tempfile::NamedTempFile::new_in(parent)?;
    if let Ok(metadata) = fs::metadata(path) {
        temp.as_file().set_permissions(metadata.permissions())?;
    }
    let _ = executable;
    temp.write_all(contents)?;
    temp.as_file().sync_all()?;
    persist(temp, path)?;
    Ok(())
}

#[cfg(not(any(unix, windows)))]
fn persist(temp: tempfile::NamedTempFile, path: &Path) -> Result<()> {
    temp.persist(path).map_err(|e| e.error)?;
    Ok(())
}

#[cfg(windows)]
fn persist(temp: tempfile::NamedTempFile, path: &Path) -> Result<()> {
    use std::os::windows::ffi::OsStrExt;
    if path.exists() {
        // ReplaceFile preserves the original file's DACL and metadata. A plain
        // rename would replace it with the temporary file's inherited ACL.
        let original: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
        // ReplaceFile opens the replacement without sharing; close our handle first.
        let temp = temp.into_temp_path();
        let replacement: Vec<u16> = temp.as_os_str().encode_wide().chain(Some(0)).collect();
        // SAFETY: both paths are valid NUL-terminated buffers for the duration
        // of this synchronous call; optional parameters are null.
        let success = unsafe {
            windows_sys::Win32::Storage::FileSystem::ReplaceFileW(
                original.as_ptr(),
                replacement.as_ptr(),
                std::ptr::null(),
                0,
                std::ptr::null(),
                std::ptr::null(),
            )
        };
        ensure!(
            success != 0,
            "could not replace configuration while preserving its ACL: {}",
            std::io::Error::last_os_error()
        );
    } else {
        temp.persist(path).map_err(|e| e.error)?;
    }
    Ok(())
}

/// An exclusive lock on a file, across processes, until it is dropped.
pub struct FileLock(File);

impl Drop for FileLock {
    fn drop(&mut self) {
        // Unlock explicitly: an unrelated concurrent subprocess can briefly
        // inherit a descriptor between fork and exec. Closing only our copy
        // would leave the lock held until that child closes its copy as well.
        let _ = self.0.unlock();
    }
}

fn lock(env: &Environment) -> Result<FileLock> {
    let dir = env.state_dir();
    mkdir(&roots(env), &dir)?;
    let file = open_lock(&roots(env), &dir.join("lock"))?;
    file.try_lock().context(SetupError::Busy)?;
    Ok(FileLock(file))
}

/// Opens the lock file at `path`, made if missing, after [`check_safe_path`].
fn open_lock(roots: &[impl AsRef<Path>], path: &Path) -> Result<File> {
    check_safe_path(roots, path)?;
    let mut options = OpenOptions::new();
    options.read(true).write(true).create(true).truncate(false);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600).custom_flags(libc::O_NOFOLLOW);
    }
    Ok(options.open(path)?)
}

/// How often [`wait_for_lock`] tries again.
const LOCK_RETRY: Duration = Duration::from_millis(50);

/// Waits up to `wait` for an exclusive lock on the file at `path`. Its
/// folder, made private with the folders above it if missing, must be the
/// user's, and no one else may change it. `None`: another process still
/// holds the lock.
pub fn wait_for_lock(path: &Path, wait: Duration) -> Result<Option<FileLock>> {
    let folder = path
        .parent()
        .ok_or_else(|| anyhow!("missing parent directory"))?;
    private_folders().recursive(true).create(folder)?;
    let file = open_lock(&[folder], path)?;
    let deadline = Instant::now() + wait;
    loop {
        match file.try_lock() {
            Ok(()) => return Ok(Some(FileLock(file))),
            Err(TryLockError::WouldBlock) if Instant::now() < deadline => {
                std::thread::sleep(LOCK_RETRY);
            }
            Err(TryLockError::WouldBlock) => return Ok(None),
            Err(TryLockError::Error(error)) => return Err(error.into()),
        }
    }
}

fn journal_path(env: &Environment, id: &str) -> PathBuf {
    env.state_dir().join(format!("{id}.json"))
}

/// The journals of the agent's blocks as one, with an observation of each
/// journal file, which undo records as a read dependency.
fn journal(
    env: &Environment,
    kind: AgentKind,
) -> Result<(Vec<crate::ObservedFile>, Option<Journal>)> {
    let mut observed = Vec::new();
    let mut merged: Option<Journal> = None;
    for &block_id in kind.blocks() {
        let path = journal_path(env, block_id);
        let text = read(env, &path)?;
        observed.push(observation(&path, text.as_deref()));
        let Some(text) = text else {
            continue;
        };
        let mut journal: Journal = serde_json::from_str(&text)
            .context("invalid setup journal; preserve it for recovery")
            .context(SetupError::ManagedBlock { path })?;
        for file in &mut journal.files {
            file.id = block_id;
        }
        match &mut merged {
            Some(merged) => merged.files.append(&mut journal.files),
            None => merged = Some(journal),
        }
    }
    Ok((observed, merged))
}

/// Writes each edit only while its file still has the reviewed contents.
fn write_edits(env: &Environment, edits: &[Edit]) -> Result<()> {
    for edit in edits {
        ensure!(read(env, &edit.path)? == edit.before, SetupError::Changed);
        write(env, &edit.path, edit.after.as_deref())?;
    }
    Ok(())
}

/// Removes this agent's blocks from files no longer selected, and reports
/// blocks that Undo could not find, such as ones in copied dotfiles.
pub(crate) fn reconcile(env: &Environment, plan: &mut Plan) -> Result<()> {
    let kind = plan.request.agent.kind();
    let (observed, journal) = journal(env, kind)?;
    if let Some(journal) = &journal {
        let undo = undo_journal(env, observed, journal)?;
        for entry in undo.observed_files {
            record_observation(plan, entry)?;
        }
        plan.conflicts.extend(undo.conflicts);
        // The plan's own edit of a file already sets or removes each of
        // this agent's blocks in it.
        for edit in undo.edits {
            if !plan.edits.iter().any(|current| current.path == edit.path) {
                plan.edits.push(edit);
            }
        }
    } else {
        for entry in observed {
            record_observation(plan, entry)?;
        }
    }
    let recorded = journal.as_ref().map_or(&[][..], |journal| &journal.files);
    for edit in &plan.edits {
        for &block in kind.blocks() {
            let known = recorded.iter().any(|file| file.is(&edit.path, block));
            if !known && owned(edit.before.as_deref(), block, &edit.path)?.is_some() {
                plan.conflicts.push(Conflict::UnrecordedBlock {
                    path: edit.path.clone(),
                });
                break;
            }
        }
    }
    Ok(())
}

/// Applies only an unchanged, conflict-free plan generated in this environment.
/// A journal is persisted first, so Undo can recover an interrupted apply.
pub fn apply(env: &Environment, plan: &Plan) -> Result<()> {
    ensure!(
        plan.version == crate::PLAN_VERSION,
        SetupError::UpdateRequired
    );
    ensure!(
        plan.conflicts.is_empty(),
        "resolve configuration conflicts before applying"
    );
    // An unchanged setup must not create even a lock file or journal.
    let changes = plan.undo || !plan.edits.is_empty();
    let _lock = if changes { Some(lock(env)?) } else { None };
    let fresh = if plan.undo {
        undo(env, plan.request.agent.kind())?
    } else {
        planner::plan(env, plan.request.clone())?
    };
    ensure!(&fresh == plan, SetupError::Changed);
    if !changes {
        return Ok(());
    }
    let kind = plan.request.agent.kind();
    if plan.undo {
        write_edits(env, &plan.edits)?;
        for &block_id in kind.blocks() {
            write(env, &journal_path(env, block_id), None)?;
        }
        return Ok(());
    }
    let mut journal = journal(env, kind)?.1.unwrap_or(Journal {
        request: plan.request.clone(),
        files: Vec::new(),
    });
    journal.request = plan.request.clone();
    let mut retired = Vec::new();
    for edit in &plan.edits {
        let path = &edit.path;
        let managed = || SetupError::ManagedBlock { path: path.clone() };
        for &block_id in kind.blocks() {
            let index = journal
                .files
                .iter()
                .position(|file| file.is(path, block_id));
            let current = owned(edit.before.as_deref(), block_id, path)?;
            // A recorded block may be missing: the user removed it, or an
            // interrupted apply never wrote it. Writing it again is safe.
            match (index, &current) {
                (Some(index), Some(current)) if !journal.files[index].matches(current) => {
                    return Err(
                        anyhow!("Keyguard's configuration was edited manually").context(managed())
                    );
                }
                (None, Some(_)) => {
                    return Err(
                        anyhow!("found a Keyguard block without an undo record").context(managed())
                    );
                }
                _ => (),
            }
            // Until its file is written, the journal keeps the block it replaces.
            match (index, owned(edit.after.as_deref(), block_id, path)?) {
                (Some(index), Some(block)) => {
                    let file = &mut journal.files[index];
                    file.previous = current.filter(|current| current != &block);
                    file.block = block;
                }
                (Some(_), None) => retired.push((path, block_id)),
                (None, Some(block)) => journal.files.push(OwnedBlock {
                    path: path.clone(),
                    id: block_id,
                    created: edit.before.is_none(),
                    block,
                    previous: None,
                }),
                (None, None) => (),
            }
        }
    }
    save(env, kind, &journal)?;
    write_edits(env, &plan.edits)
        .context("some files may already be changed; use Undo to recover")?;
    journal.files.retain(|file| {
        !retired
            .iter()
            .any(|(path, block_id)| file.is(path, block_id))
    });
    for file in &mut journal.files {
        if plan.edits.iter().any(|edit| edit.path == file.path) {
            file.previous = None;
        }
    }
    save(env, kind, &journal)
}

/// Writes the journal file of each block; the agent's own one even without
/// blocks, and no other file without blocks.
fn save(env: &Environment, kind: AgentKind, journal: &Journal) -> Result<()> {
    let id = kind.id();
    for &block_id in kind.blocks() {
        let files: Vec<_> = journal
            .files
            .iter()
            .filter(|file| file.id == block_id)
            .cloned()
            .collect();
        let path = journal_path(env, block_id);
        if files.is_empty() && block_id != id {
            write(env, &path, None)?;
            continue;
        }
        let record = serde_json::to_string_pretty(&Journal {
            request: journal.request.clone(),
            files,
        })?;
        ensure!(
            record.len() as u64 <= MAX_FILE_SIZE,
            "configuration exceeds the undo record size limit; configure these files manually"
        );
        write(env, &path, Some(&record))?;
    }
    Ok(())
}

fn owned(text: Option<&str>, id: &str, path: &Path) -> Result<Option<String>> {
    let text = text.unwrap_or_default();
    Ok(planner::block_in(text, id, path)?.map(|(start, end)| text[start..end].to_owned()))
}

/// Previews removal of this agent's settings; [`apply`] performs it. Unrelated
/// user edits survive; edits inside a managed block require manual resolution.
pub fn undo(env: &Environment, kind: AgentKind) -> Result<Plan> {
    let id = kind.id();
    let (observed, journal) = journal(env, kind)?;
    let journal = journal.ok_or_else(|| anyhow::anyhow!("no recorded {id} setup to undo"))?;
    undo_journal(env, observed, &journal)
}

fn undo_journal(
    env: &Environment,
    observed: Vec<crate::ObservedFile>,
    journal: &Journal,
) -> Result<Plan> {
    let mut plan = Plan {
        version: crate::PLAN_VERSION,
        undo: true,
        request: journal.request.clone(),
        edits: Vec::new(),
        conflicts: Vec::new(),
        overrides: Vec::new(),
        observed_files: observed,
    };
    // The blocks of each file, in the order of the journal.
    let mut files: Vec<(&PathBuf, Vec<&OwnedBlock>)> = Vec::new();
    for file in &journal.files {
        match files.iter_mut().find(|(path, _)| *path == &file.path) {
            Some((_, blocks)) => blocks.push(file),
            None => files.push((&file.path, vec![file])),
        }
    }
    for (path, blocks) in files {
        let current = observe(env, &mut plan, path)?;
        let mut rest = current.clone().unwrap_or_default();
        let (mut found, mut changed) = (false, false);
        for file in &blocks {
            let block_id = file.id;
            // An interrupted apply may not have reached this file, or the user
            // already removed the block.
            let Some(block) = owned(current.as_deref(), block_id, path)? else {
                continue;
            };
            found = true;
            changed |= !file.matches(&block);
            rest = planner::remove_block(&rest, block_id)?;
        }
        if changed {
            plan.conflicts
                .push(Conflict::ChangedBlock { path: path.clone() });
            continue;
        }
        if !found {
            continue;
        }
        // Windows PowerShell profiles may keep the BOM that setup added.
        let empty = rest.trim_start_matches('\u{feff}').is_empty();
        // Zsh loads a compiled copy of a missing startup file, so keep it empty.
        let created = blocks.iter().any(|file| file.created);
        let delete = created && empty && !crate::shell::compiled(path).exists();
        plan.edits.push(Edit {
            path: path.clone(),
            before: current,
            after: if delete { None } else { Some(rest) },
        });
    }
    plan.observed_files.sort_by(|a, b| a.path.cmp(&b.path));
    Ok(plan)
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;

    /// A home with the folder `browser/hosts`, which someone else can
    /// change, such as a sandboxed browser its own folder, and a folder
    /// `elsewhere` that holds a file of the same name.
    fn home() -> (tempfile::TempDir, PathBuf) {
        let temp = tempfile::tempdir().unwrap();
        let home = temp.path().join("home");
        fs::create_dir_all(home.join("browser/hosts")).unwrap();
        fs::create_dir_all(home.join("elsewhere")).unwrap();
        fs::write(home.join("elsewhere/host.json"), "theirs").unwrap();
        (temp, home)
    }

    /// Moves `browser/hosts` aside, and puts a link to `elsewhere` in its
    /// place.
    fn swap_for_a_link(home: &Path) {
        fs::rename(home.join("browser/hosts"), home.join("browser/moved")).unwrap();
        std::os::unix::fs::symlink(home.join("elsewhere"), home.join("browser/hosts")).unwrap();
    }

    #[test]
    fn a_folder_swapped_for_a_link_after_the_checks_gets_no_write() {
        let (_temp, home) = home();
        let path = home.join("browser/hosts/host.json");
        write_checked_file(&[&home], &path, Some(b"ours"), false, || {
            swap_for_a_link(&home)
        })
        .unwrap();
        assert_eq!(
            fs::read_to_string(home.join("elsewhere/host.json")).unwrap(),
            "theirs"
        );
        // The write went to the folder that was checked.
        assert_eq!(
            fs::read_to_string(home.join("browser/moved/host.json")).unwrap(),
            "ours"
        );
        assert_eq!(fs::read_dir(home.join("elsewhere")).unwrap().count(), 1);
    }

    #[test]
    fn a_folder_swapped_for_a_link_after_the_checks_loses_no_file() {
        let (_temp, home) = home();
        let path = home.join("browser/hosts/host.json");
        fs::write(&path, "ours").unwrap();
        write_checked_file(&[&home], &path, None, false, || swap_for_a_link(&home)).unwrap();
        assert_eq!(
            fs::read_to_string(home.join("elsewhere/host.json")).unwrap(),
            "theirs"
        );
        assert!(!home.join("browser/moved/host.json").exists());
    }

    #[test]
    fn writes_make_private_folders_and_keep_a_file_mode() {
        use std::os::unix::fs::PermissionsExt;
        let (_temp, home) = home();
        let path = home.join("new/folder/host.json");
        write_safe_file(&[&home], &path, Some(b"one"), false).unwrap();
        let mode = |path: &Path| fs::metadata(path).unwrap().permissions().mode() & 0o7777;
        assert_eq!(mode(&home.join("new")), 0o700);
        assert_eq!(mode(&home.join("new/folder")), 0o700);
        assert_eq!(mode(&path), 0o600);
        fs::set_permissions(&path, fs::Permissions::from_mode(0o644)).unwrap();
        write_safe_file(&[&home], &path, Some(b"two"), false).unwrap();
        assert_eq!(mode(&path), 0o644);
        write_safe_file(&[&home], &path, Some(b"three"), true).unwrap();
        assert_eq!(mode(&path), 0o755);
        assert_eq!(read_safe_text(&[&home], &path).unwrap().unwrap(), "three");
        // Only the file is left: no temporary file.
        assert_eq!(fs::read_dir(home.join("new/folder")).unwrap().count(), 1);
        write_safe_file(&[&home], &path, None, false).unwrap();
        assert!(!path.exists());
        assert_eq!(read_safe_text(&[&home], &path).unwrap(), None);
    }

    #[test]
    fn reads_follow_no_link_and_wait_for_no_fifo() {
        let (_temp, home) = home();
        let link = home.join("browser/hosts/host.json");
        std::os::unix::fs::symlink(home.join("elsewhere/host.json"), &link).unwrap();
        assert!(read_safe_text(&[&home], &link).is_err());
        let fifo = home.join("browser/hosts/fifo");
        let name = std::ffi::CString::new(fifo.as_os_str().as_encoded_bytes()).unwrap();
        // SAFETY: `name` is a NUL-terminated path that outlives the call.
        assert_eq!(unsafe { libc::mkfifo(name.as_ptr(), 0o600) }, 0);
        assert!(read_safe_text(&[&home], &fifo).is_err());
        assert!(check_safe_path(&[&home], &fifo).is_err());
    }

    #[test]
    fn writes_need_their_root() {
        let (temp, home) = home();
        // Such as an XDG_DATA_HOME outside the home that is missing.
        let root = temp.path().join("data");
        let path = root.join("keyguard/host");
        let error = write_safe_file(&[&home, &root], &path, Some(b"ours"), true).unwrap_err();
        assert!(
            matches!(error.downcast_ref(), Some(SetupError::UnsafeFile { path }) if path == temp.path()),
            "{error:#}"
        );
        assert!(!root.exists());
        // It holds nothing to delete.
        write_safe_file(&[&home, &root], &path, None, false).unwrap();
    }

    #[test]
    fn folders_on_the_way_need_only_permission_to_search() {
        use std::os::unix::fs::PermissionsExt;
        let (_temp, home) = home();
        // The user may change the folder and open what is in it, but not
        // list it.
        let locked = home.join("locked");
        fs::create_dir_all(locked.join("hosts")).unwrap();
        fs::set_permissions(&locked, fs::Permissions::from_mode(0o300)).unwrap();
        let result = (|| -> Result<()> {
            for root in [&home, &locked] {
                let path = locked.join("hosts/host.json");
                write_safe_file(&[root], &path, Some(b"ours"), false)?;
                assert_eq!(read_safe_text(&[root], &path)?.as_deref(), Some("ours"));
                check_safe_path(&[root], &path)?;
                write_safe_file(&[root], &path, None, false)?;
                assert_eq!(read_safe_text(&[root], &path)?, None);
                // A missing folder below it is made.
                write_safe_file(&[root], &locked.join("new/host.json"), Some(b"ours"), false)?;
                write_safe_file(&[root], &locked.join("new/host.json"), None, false)?;
            }
            Ok(())
        })();
        fs::set_permissions(&locked, fs::Permissions::from_mode(0o700)).unwrap();
        result.unwrap();
    }

    /// A reader of `length` bytes that counts what was read from it.
    struct Counting {
        length: u64,
        read: u64,
    }

    impl Read for Counting {
        fn read(&mut self, buffer: &mut [u8]) -> std::io::Result<usize> {
            let count = (self.length - self.read).min(buffer.len() as u64);
            buffer[..count as usize].fill(b'a');
            self.read += count;
            Ok(count as usize)
        }
    }

    #[test]
    fn reads_stop_after_the_limit() {
        assert_eq!(read_at_most(&b"1234"[..], 4).unwrap(), b"1234");
        // Such as a file that grew after the check of its size.
        let mut grown = Counting {
            length: 64 * MAX_FILE_SIZE,
            read: 0,
        };
        assert!(read_at_most(&mut grown, MAX_FILE_SIZE).is_err());
        assert_eq!(grown.read, MAX_FILE_SIZE + 1);
    }
}

#[cfg(test)]
mod lock_tests {
    use super::*;

    #[test]
    fn a_lock_waits_for_its_holder() {
        let temp = tempfile::tempdir().unwrap();
        let path = temp.path().join("state/browsers.lock");
        let held = wait_for_lock(&path, Duration::ZERO).unwrap().unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = |path: &Path| fs::metadata(path).unwrap().permissions().mode() & 0o777;
            assert_eq!(mode(path.parent().unwrap()), 0o700);
            assert_eq!(mode(&path), 0o600);
        }
        assert!(
            wait_for_lock(&path, Duration::from_millis(200))
                .unwrap()
                .is_none()
        );
        std::thread::scope(|scope| {
            let waiter = scope.spawn(|| {
                let lock = wait_for_lock(&path, Duration::from_secs(30)).unwrap();
                (Instant::now(), lock.is_some())
            });
            std::thread::sleep(Duration::from_millis(300));
            let released = Instant::now();
            drop(held);
            let (locked_at, locked) = waiter.join().unwrap();
            assert!(locked && locked_at >= released);
        });
        // A link in place of the folder is refused.
        #[cfg(unix)]
        {
            let link = temp.path().join("link");
            std::os::unix::fs::symlink(path.parent().unwrap(), &link).unwrap();
            assert!(wait_for_lock(&link.join("browsers.lock"), Duration::ZERO).is_err());
        }
    }
}
