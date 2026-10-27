use crate::{AgentKind, Conflict, Edit, Environment, Plan, SetupError, planner};
use anyhow::{Context, Result, anyhow, ensure};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File, OpenOptions},
    io::Write,
    path::{Component, Path, PathBuf},
};

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
#[derive(Debug, Serialize, Deserialize)]
struct Journal {
    request: crate::Request,
    files: Vec<OwnedBlock>,
}

#[derive(Debug, Serialize, Deserialize)]
struct OwnedBlock {
    path: PathBuf,
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
}

pub(crate) fn check_path(env: &Environment, path: &Path) -> Result<()> {
    check(env, path).with_context(|| SetupError::UnsafeFile {
        path: path.to_owned(),
    })
}

fn check(env: &Environment, path: &Path) -> Result<()> {
    let root = [Some(&env.home), env.documents.as_ref()]
        .into_iter()
        .flatten()
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
                #[cfg(unix)]
                {
                    use std::os::unix::fs::MetadataExt;
                    // SAFETY: geteuid reads the current process's effective user ID.
                    let uid = unsafe { libc::geteuid() };
                    ensure!(
                        meta.uid() == uid,
                        "configuration belongs to another user: {}",
                        path.display()
                    );
                    ensure!(
                        meta.mode() & 0o002 == 0
                            && (meta.mode() & 0o020 == 0 || private_group(uid, meta.gid())),
                        "configuration is writable by another user: {}",
                        path.display()
                    );
                    ensure!(
                        !meta.is_file() || meta.nlink() == 1,
                        "refusing a hard-linked configuration file: {}",
                        path.display()
                    );
                }
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
    check_path(env, path)?;
    read_text(path)
}

fn read_text(path: &Path) -> Result<Option<String>> {
    match fs::metadata(path) {
        Ok(meta) => {
            let text = if meta.is_file() && meta.len() <= MAX_FILE_SIZE {
                fs::read_to_string(path).context("configuration is not UTF-8 text")
            } else {
                Err(anyhow!(
                    "configuration is not a regular file smaller than 1 MiB"
                ))
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

fn mkdir(env: &Environment, path: &Path) -> Result<()> {
    check_path(env, path)?;
    if path.exists() {
        ensure!(path.is_dir(), "not a directory: {}", path.display());
        return Ok(());
    }
    mkdir(
        env,
        path.parent()
            .ok_or_else(|| anyhow::anyhow!("missing parent directory"))?,
    )?;
    let builder = fs::DirBuilder::new();
    #[cfg(unix)]
    let mut builder = builder;
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        builder.mode(0o700);
    }
    builder.create(path)?;
    Ok(())
}

fn write(env: &Environment, path: &Path, text: Option<&str>) -> Result<()> {
    check_path(env, path)?;
    let Some(text) = text else {
        if path.exists() {
            fs::remove_file(path)?;
        }
        return Ok(());
    };
    let parent = path
        .parent()
        .ok_or_else(|| anyhow::anyhow!("missing parent directory"))?;
    mkdir(env, parent)?;
    let mut temp = tempfile::NamedTempFile::new_in(parent)?;
    if let Ok(metadata) = fs::metadata(path) {
        temp.as_file().set_permissions(metadata.permissions())?;
    }
    temp.write_all(text.as_bytes())?;
    temp.as_file().sync_all()?;
    persist(temp, path)?;
    Ok(())
}

#[cfg(not(windows))]
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

struct SetupLock(File);

impl Drop for SetupLock {
    fn drop(&mut self) {
        // Unlock explicitly: an unrelated concurrent subprocess can briefly
        // inherit a descriptor between fork and exec. Closing only our copy
        // would leave the lock held until that child closes its copy as well.
        let _ = self.0.unlock();
    }
}

fn lock(env: &Environment) -> Result<SetupLock> {
    let dir = env.state_dir();
    mkdir(env, &dir)?;
    let path = dir.join("lock");
    check_path(env, &path)?;
    let mut options = OpenOptions::new();
    options.read(true).write(true).create(true).truncate(false);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600).custom_flags(libc::O_NOFOLLOW);
    }
    let file = options.open(path)?;
    file.try_lock().context(SetupError::Busy)?;
    Ok(SetupLock(file))
}

fn journal_path(env: &Environment, id: &str) -> PathBuf {
    env.state_dir().join(format!("{id}.json"))
}

/// The journal with its exact text, which undo records as a read dependency.
fn journal(env: &Environment, id: &str) -> Result<Option<(String, Journal)>> {
    let path = journal_path(env, id);
    read(env, &path)?
        .map(|text| {
            let journal = serde_json::from_str(&text)
                .context("invalid setup journal; preserve it for recovery")
                .context(SetupError::ManagedBlock { path })?;
            Ok((text, journal))
        })
        .transpose()
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
    let id = plan.request.agent.id();
    let mut recorded = Vec::new();
    if let Some((text, journal)) = journal(env, id)? {
        recorded.extend(journal.files.iter().map(|file| file.path.clone()));
        let undo = undo_journal(env, id, &text, journal)?;
        for entry in undo.observed_files {
            record_observation(plan, entry)?;
        }
        plan.conflicts.extend(undo.conflicts);
        for edit in undo.edits {
            if !plan.edits.iter().any(|current| current.path == edit.path) {
                plan.edits.push(edit);
            }
        }
    } else {
        record_observation(plan, observation(&journal_path(env, id), None))?;
    }
    for edit in &plan.edits {
        if !recorded.contains(&edit.path)
            && owned(edit.before.as_deref(), id, &edit.path)?.is_some()
        {
            plan.conflicts.push(Conflict::UnrecordedBlock {
                path: edit.path.clone(),
            });
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
    let id = plan.request.agent.id();
    if plan.undo {
        write_edits(env, &plan.edits)?;
        return write(env, &journal_path(env, id), None);
    }
    let mut journal = journal(env, id)?
        .map(|(_, journal)| journal)
        .unwrap_or(Journal {
            request: plan.request.clone(),
            files: Vec::new(),
        });
    journal.request = plan.request.clone();
    let mut retired = Vec::new();
    for edit in &plan.edits {
        let path = &edit.path;
        let managed = || SetupError::ManagedBlock { path: path.clone() };
        let index = journal.files.iter().position(|file| &file.path == path);
        let current = owned(edit.before.as_deref(), id, path)?;
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
        match (index, owned(edit.after.as_deref(), id, path)?) {
            (Some(index), Some(block)) => {
                let file = &mut journal.files[index];
                file.previous = current.filter(|current| current != &block);
                file.block = block;
            }
            (Some(_), None) => retired.push(path),
            (None, Some(block)) => journal.files.push(OwnedBlock {
                path: path.clone(),
                created: edit.before.is_none(),
                block,
                previous: None,
            }),
            (None, None) => (),
        }
    }
    save(env, id, &journal)?;
    write_edits(env, &plan.edits)
        .context("some files may already be changed; use Undo to recover")?;
    journal.files.retain(|file| !retired.contains(&&file.path));
    for file in &mut journal.files {
        if plan.edits.iter().any(|edit| edit.path == file.path) {
            file.previous = None;
        }
    }
    save(env, id, &journal)
}

fn save(env: &Environment, id: &str, journal: &Journal) -> Result<()> {
    let record = serde_json::to_string_pretty(journal)?;
    ensure!(
        record.len() as u64 <= MAX_FILE_SIZE,
        "configuration exceeds the undo record size limit; configure these files manually"
    );
    write(env, &journal_path(env, id), Some(&record))
}

fn owned(text: Option<&str>, id: &str, path: &Path) -> Result<Option<String>> {
    let text = text.unwrap_or_default();
    Ok(planner::block_in(text, id, path)?.map(|(start, end)| text[start..end].to_owned()))
}

/// Previews removal of this agent's settings; [`apply`] performs it. Unrelated
/// user edits survive; edits inside a managed block require manual resolution.
pub fn undo(env: &Environment, kind: AgentKind) -> Result<Plan> {
    let id = kind.id();
    let (text, journal) =
        journal(env, id)?.ok_or_else(|| anyhow::anyhow!("no recorded {id} setup to undo"))?;
    undo_journal(env, id, &text, journal)
}

fn undo_journal(env: &Environment, id: &str, text: &str, journal: Journal) -> Result<Plan> {
    let mut plan = Plan {
        version: crate::PLAN_VERSION,
        undo: true,
        request: journal.request,
        edits: Vec::new(),
        conflicts: Vec::new(),
        overrides: Vec::new(),
        observed_files: vec![observation(&journal_path(env, id), Some(text))],
    };
    for file in journal.files {
        let current = observe(env, &mut plan, &file.path)?;
        // An interrupted apply may not have reached this file, or the user
        // already removed the block.
        let Some(block) = owned(current.as_deref(), id, &file.path)? else {
            continue;
        };
        if !file.matches(&block) {
            plan.conflicts
                .push(Conflict::ChangedBlock { path: file.path });
            continue;
        }
        let rest = planner::remove_block(current.as_deref().unwrap_or_default(), id)?;
        // Windows PowerShell profiles may keep the BOM that setup added.
        let empty = rest.trim_start_matches('\u{feff}').is_empty();
        // Zsh loads a compiled copy of a missing startup file, so keep it empty.
        let delete = file.created && empty && !crate::shell::compiled(&file.path).exists();
        plan.edits.push(Edit {
            path: file.path,
            before: current,
            after: if delete { None } else { Some(rest) },
        });
    }
    plan.observed_files.sort_by(|a, b| a.path.cmp(&b.path));
    Ok(plan)
}
