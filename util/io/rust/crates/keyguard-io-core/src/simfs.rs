//! In-memory simulated filesystem with independent volatile and durable trees.
//!
//! Every directory has its own metadata journal. A directory flush promotes
//! only that directory, which lets the crash harness prove that every newly
//! created component of a destination path was persisted.

use std::{
    collections::{BTreeMap, HashMap},
    io,
    path::Path,
    sync::{Arc, Mutex},
    time::Duration,
};

use crate::{
    directory::split_absolute_path,
    durability::SyncLevel,
    fsops::{
        AmbiguousPublicationCleanup, CreatedStaged, FileIdentity, FlushKind, FlushOutcome, FsOps,
        PublicationAttemptError, PublicationUnknownCleanup, StagedCreationError,
        StagedCreationFailureKind, StagedNameResidual,
    },
    naming::{
        MAX_TEMPORARY_ARTIFACT_ATTEMPTS, TemporaryArtifactEntryKind, TemporaryArtifactProtocol,
        TemporaryFileRole, new_temporary_artifact_names, parse_temporary_artifact_name,
    },
    txn::DirectoryPermissions,
};

const ROOT_DIRECTORY: u64 = 0;

/// Identifies one [`FsOps`] method for fault injection.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub(crate) enum SimOp {
    OpenRoot,
    OpenDirAt,
    CreateDirAt,
    CreateFileAt,
    ProbeDirectoryLeaseExclusive,
    AcquireDirectoryLeaseShared,
    LockFile,
    RevalidateSidecar,
    RevalidateStagedData,
    CheckDataAbsent,
    ReleaseLease,
    WriteAll,
    FlushFile,
    ReadMetadata,
    ApplyMetadata,
    VerifyMetadata,
    CaptureIdentity,
    ObserveIdentity,
    PrepareRename,
    Rename,
    RenameReply,
    PrepareHardLink,
    HardLink,
    HardLinkReply,
    Unlink,
    FlushDirectory,
    FlushPublication,
    Close,
}

/// One attempted simulator operation, including attempts that faulted.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct SimOperation {
    pub op: SimOp,
    pub occurrence: u32,
}

/// A single injected fault: the `occurrence`-th call (zero-based) of `op`
/// fails with `kind`.
#[derive(Clone, Copy, Debug)]
pub(crate) struct Fault {
    pub op: SimOp,
    pub occurrence: u32,
    pub kind: io::ErrorKind,
}

#[derive(Clone, Copy, Debug)]
enum PartialWriteOutcome {
    Error(io::ErrorKind),
    Panic,
}

#[derive(Clone, Copy, Debug)]
struct PartialWrite {
    occurrence: u32,
    accepted: usize,
    outcome: PartialWriteOutcome,
}

/// Simulated flush behavior of the underlying volume.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum SimFlushSupport {
    Full,
    Degraded,
    Unsupported,
}

/// Independently configurable support for the protocol's three persistence
/// barriers.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct SimFlushCapabilities {
    pub file: SimFlushSupport,
    pub created_parent: SimFlushSupport,
    pub publication: SimFlushSupport,
}

impl Default for SimFlushCapabilities {
    fn default() -> Self {
        Self {
            file: SimFlushSupport::Full,
            created_parent: SimFlushSupport::Full,
            publication: SimFlushSupport::Full,
        }
    }
}

#[derive(Clone, Debug, Default)]
struct Inode {
    volatile: Vec<u8>,
    durable: Vec<u8>,
    volatile_basic_permissions: u32,
    durable_basic_permissions: u32,
    flush_failed: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Entry {
    File(u64),
    Directory(u64),
    /// A POSIX directory symlink or Windows name-surrogate reparse point.
    DirectoryLink(u64),
}

#[derive(Clone, Debug)]
enum MetaOp {
    Create { name: String, entry: Entry },
    Rename { from: String, to: String },
    Link { from: String, to: String },
    Unlink { name: String },
}

#[derive(Clone, Debug, Default)]
struct DirState {
    volatile: BTreeMap<String, Entry>,
    durable: BTreeMap<String, Entry>,
    pending: Vec<MetaOp>,
}

impl DirState {
    fn apply(entries: &mut BTreeMap<String, Entry>, op: &MetaOp) {
        match op {
            MetaOp::Create { name, entry } => {
                entries.insert(name.clone(), *entry);
            }
            MetaOp::Rename { from, to } => {
                if let Some(entry) = entries.remove(from) {
                    entries.insert(to.clone(), entry);
                }
            }
            MetaOp::Link { from, to } => {
                if let Some(entry) = entries.get(from).copied() {
                    entries.insert(to.clone(), entry);
                }
            }
            MetaOp::Unlink { name } => {
                entries.remove(name);
            }
        }
    }

    fn record(&mut self, op: MetaOp) {
        Self::apply(&mut self.volatile, &op);
        self.pending.push(op);
    }

    fn projection(&self, pending_prefix: usize) -> BTreeMap<String, Entry> {
        let mut entries = self.durable.clone();
        for operation in &self.pending[..pending_prefix] {
            Self::apply(&mut entries, operation);
        }
        entries
    }
}

/// One immutable snapshot of the whole simulation, taken after an operation.
#[derive(Clone, Debug)]
pub(crate) struct Snapshot {
    pub label: SimOp,
    inodes: HashMap<u64, Inode>,
    directories: HashMap<u64, DirState>,
}

/// One possible state of a simulated file after a power loss.
#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct SimFileState {
    pub bytes: Vec<u8>,
    pub basic_permissions: u32,
}

impl Snapshot {
    /// Enumerates every reachable post-power-loss directory tree.
    pub fn crash_projections(&self) -> Vec<BTreeMap<String, Vec<SimFileState>>> {
        let mut directory_ids = self.directories.keys().copied().collect::<Vec<_>>();
        directory_ids.sort_unstable();
        let mut views = HashMap::new();
        let mut projections = Vec::new();
        self.collect_directory_projections(&directory_ids, 0, &mut views, &mut projections);
        projections
    }

    fn collect_directory_projections(
        &self,
        directory_ids: &[u64],
        index: usize,
        views: &mut HashMap<u64, BTreeMap<String, Entry>>,
        projections: &mut Vec<BTreeMap<String, Vec<SimFileState>>>,
    ) {
        let Some(directory_id) = directory_ids.get(index).copied() else {
            projections.push(self.flatten_possible(ROOT_DIRECTORY, "", views));
            return;
        };
        let Some(directory) = self.directories.get(&directory_id) else {
            return;
        };
        for prefix in 0..=directory.pending.len() {
            views.insert(directory_id, directory.projection(prefix));
            self.collect_directory_projections(directory_ids, index + 1, views, projections);
        }
        views.remove(&directory_id);
    }

    fn flatten_possible(
        &self,
        directory_id: u64,
        prefix: &str,
        views: &HashMap<u64, BTreeMap<String, Entry>>,
    ) -> BTreeMap<String, Vec<SimFileState>> {
        let mut output = BTreeMap::new();
        let Some(entries) = views.get(&directory_id) else {
            return output;
        };
        for (name, entry) in entries {
            let path = join_path(prefix, name);
            match entry {
                Entry::File(inode_id) => {
                    if let Some(inode) = self.inodes.get(inode_id) {
                        let durable = SimFileState {
                            bytes: inode.durable.clone(),
                            basic_permissions: inode.durable_basic_permissions,
                        };
                        let volatile = SimFileState {
                            bytes: inode.volatile.clone(),
                            basic_permissions: inode.volatile_basic_permissions,
                        };
                        let mut states = vec![durable.clone()];
                        if volatile != durable {
                            states.push(volatile);
                        }
                        output.insert(path, states);
                    }
                }
                Entry::Directory(child_id) | Entry::DirectoryLink(child_id) => {
                    output.extend(self.flatten_possible(*child_id, &path, views));
                }
            }
        }
        output
    }

    /// The fully-durable projection: only flushed bytes and entries survive.
    pub fn durable_projection(&self) -> BTreeMap<String, SimFileState> {
        let views = self
            .directories
            .iter()
            .map(|(id, directory)| (*id, directory.durable.clone()))
            .collect();
        self.flatten_exact(ROOT_DIRECTORY, "", &views, true)
    }

    /// The live (no-crash) filesystem tree.
    pub fn live_listing(&self) -> BTreeMap<String, SimFileState> {
        let views = self
            .directories
            .iter()
            .map(|(id, directory)| (*id, directory.volatile.clone()))
            .collect();
        self.flatten_exact(ROOT_DIRECTORY, "", &views, false)
    }

    fn flatten_exact(
        &self,
        directory_id: u64,
        prefix: &str,
        views: &HashMap<u64, BTreeMap<String, Entry>>,
        durable: bool,
    ) -> BTreeMap<String, SimFileState> {
        let mut output = BTreeMap::new();
        let Some(entries) = views.get(&directory_id) else {
            return output;
        };
        for (name, entry) in entries {
            let path = join_path(prefix, name);
            match entry {
                Entry::File(inode_id) => {
                    if let Some(inode) = self.inodes.get(inode_id) {
                        output.insert(
                            path,
                            if durable {
                                SimFileState {
                                    bytes: inode.durable.clone(),
                                    basic_permissions: inode.durable_basic_permissions,
                                }
                            } else {
                                SimFileState {
                                    bytes: inode.volatile.clone(),
                                    basic_permissions: inode.volatile_basic_permissions,
                                }
                            },
                        );
                    }
                }
                Entry::Directory(child_id) | Entry::DirectoryLink(child_id) => {
                    output.extend(self.flatten_exact(*child_id, &path, views, durable));
                }
            }
        }
        output
    }
}

fn join_path(parent: &str, name: &str) -> String {
    if parent.is_empty() {
        name.to_owned()
    } else {
        format!("{parent}/{name}")
    }
}

/// A deterministic namespace change applied immediately before one simulated
/// filesystem operation.
#[derive(Clone, Debug)]
pub(crate) enum NamespaceMutation {
    RenameEntry {
        parent: Vec<String>,
        from: String,
        to: String,
    },
    CreateDirectory {
        parent: Vec<String>,
        name: String,
    },
    CreateDirectoryLink {
        parent: Vec<String>,
        name: String,
        target: Vec<String>,
    },
    CreateFile {
        parent: Vec<String>,
        name: String,
    },
    RemoveEntry {
        parent: Vec<String>,
        name: String,
    },
    RestoreRenamedTemporary {
        parent: Vec<String>,
        destination: String,
    },
    ReplaceFile {
        parent: Vec<String>,
        name: String,
    },
    ReplaceTemporaryEntry {
        parent: Vec<String>,
        entry_kind: TemporaryArtifactEntryKind,
    },
}

impl NamespaceMutation {
    pub fn rename_entry(parent: &str, from: &str, to: &str) -> Self {
        Self::RenameEntry {
            parent: path_components(parent),
            from: from.to_owned(),
            to: to.to_owned(),
        }
    }

    pub fn create_directory(parent: &str, name: &str) -> Self {
        Self::CreateDirectory {
            parent: path_components(parent),
            name: name.to_owned(),
        }
    }

    pub fn create_directory_link(parent: &str, name: &str, target: &str) -> Self {
        Self::CreateDirectoryLink {
            parent: path_components(parent),
            name: name.to_owned(),
            target: path_components(target),
        }
    }

    pub fn replace_file(parent: &str, name: &str) -> Self {
        Self::ReplaceFile {
            parent: path_components(parent),
            name: name.to_owned(),
        }
    }

    pub fn create_file(parent: &str, name: &str) -> Self {
        Self::CreateFile {
            parent: path_components(parent),
            name: name.to_owned(),
        }
    }

    pub fn remove_entry(parent: &str, name: &str) -> Self {
        Self::RemoveEntry {
            parent: path_components(parent),
            name: name.to_owned(),
        }
    }

    pub fn restore_renamed_temporary(parent: &str, destination: &str) -> Self {
        Self::RestoreRenamedTemporary {
            parent: path_components(parent),
            destination: destination.to_owned(),
        }
    }

    pub fn replace_temporary_entry(parent: &str, entry_kind: TemporaryArtifactEntryKind) -> Self {
        Self::ReplaceTemporaryEntry {
            parent: path_components(parent),
            entry_kind,
        }
    }
}

fn path_components(path: &str) -> Vec<String> {
    path.split('/')
        .filter(|component| !component.is_empty())
        .map(str::to_owned)
        .collect()
}

#[derive(Clone, Debug)]
struct MutationHook {
    operation: SimOperation,
    mutation: NamespaceMutation,
}

#[derive(Debug)]
struct SimState {
    inodes: HashMap<u64, Inode>,
    directories: HashMap<u64, DirState>,
    next_inode: u64,
    op_counts: HashMap<SimOp, u32>,
    operations: Vec<SimOperation>,
    snapshots: Vec<Snapshot>,
    fsyncgate_violation: bool,
}

impl SimState {
    fn resolve_directory(&self, components: &[String]) -> io::Result<u64> {
        let mut directory_id = ROOT_DIRECTORY;
        for component in components {
            let directory = self
                .directories
                .get(&directory_id)
                .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))?;
            directory_id = match directory.volatile.get(component) {
                Some(Entry::Directory(inode) | Entry::DirectoryLink(inode)) => *inode,
                Some(Entry::File(_)) => {
                    return Err(io::Error::from(io::ErrorKind::NotADirectory));
                }
                None => return Err(io::Error::from(io::ErrorKind::NotFound)),
            };
        }
        Ok(directory_id)
    }

    #[cfg(all(test, windows))]
    fn resolve_directory_no_follow_final(&self, components: &[String]) -> io::Result<u64> {
        let mut directory_id = ROOT_DIRECTORY;
        for (index, component) in components.iter().enumerate() {
            let directory = self
                .directories
                .get(&directory_id)
                .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))?;
            directory_id = match directory.volatile.get(component) {
                Some(Entry::Directory(inode)) => *inode,
                Some(Entry::DirectoryLink(inode)) if index + 1 != components.len() => *inode,
                Some(Entry::DirectoryLink(_)) => {
                    return Err(io::Error::from(io::ErrorKind::InvalidInput));
                }
                Some(Entry::File(_)) => {
                    return Err(io::Error::from(io::ErrorKind::NotADirectory));
                }
                None => return Err(io::Error::from(io::ErrorKind::NotFound)),
            };
        }
        Ok(directory_id)
    }

    fn create_directory(&mut self, parent: u64, name: String) -> io::Result<()> {
        let exists = self
            .directories
            .get(&parent)
            .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))?
            .volatile
            .contains_key(&name);
        if exists {
            return Err(io::Error::from(io::ErrorKind::AlreadyExists));
        }
        let inode = self.next_inode;
        self.next_inode += 1;
        self.directories.insert(inode, DirState::default());
        self.directories
            .get_mut(&parent)
            .expect("resolved parent directory")
            .record(MetaOp::Create {
                name,
                entry: Entry::Directory(inode),
            });
        Ok(())
    }

    fn replace_file(&mut self, parent: u64, name: &str) -> io::Result<()> {
        let directory = self
            .directories
            .get(&parent)
            .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))?;
        let replaced_inode = match directory.volatile.get(name) {
            Some(Entry::File(inode)) => *inode,
            Some(Entry::Directory(_) | Entry::DirectoryLink(_)) => {
                return Err(io::Error::from(io::ErrorKind::InvalidInput));
            }
            None => return Err(io::Error::from(io::ErrorKind::NotFound)),
        };
        let replacement = self
            .inodes
            .get(&replaced_inode)
            .cloned()
            .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))?;
        let inode = self.next_inode;
        self.next_inode += 1;
        // Preserve every observable file attribute while allocating a new
        // inode. This models an ABA replacement that cannot be distinguished
        // by bytes or permissions, only by stable identity.
        self.inodes.insert(inode, replacement);
        self.directories
            .get_mut(&parent)
            .expect("resolved parent directory")
            .record(MetaOp::Create {
                name: name.to_owned(),
                entry: Entry::File(inode),
            });
        Ok(())
    }

    fn apply_mutation(&mut self, mutation: &NamespaceMutation) -> io::Result<()> {
        match mutation {
            NamespaceMutation::RenameEntry { parent, from, to } => {
                let parent = self.resolve_directory(parent)?;
                let directory = self
                    .directories
                    .get_mut(&parent)
                    .expect("resolved parent directory");
                if !directory.volatile.contains_key(from) {
                    return Err(io::Error::from(io::ErrorKind::NotFound));
                }
                if directory.volatile.contains_key(to) {
                    return Err(io::Error::from(io::ErrorKind::AlreadyExists));
                }
                directory.record(MetaOp::Rename {
                    from: from.clone(),
                    to: to.clone(),
                });
                Ok(())
            }
            NamespaceMutation::CreateDirectory { parent, name } => {
                let parent = self.resolve_directory(parent)?;
                self.create_directory(parent, name.clone())
            }
            NamespaceMutation::CreateDirectoryLink {
                parent,
                name,
                target,
            } => {
                let parent = self.resolve_directory(parent)?;
                let target = self.resolve_directory(target)?;
                let directory = self
                    .directories
                    .get_mut(&parent)
                    .expect("resolved parent directory");
                if directory.volatile.contains_key(name) {
                    return Err(io::Error::from(io::ErrorKind::AlreadyExists));
                }
                directory.record(MetaOp::Create {
                    name: name.clone(),
                    entry: Entry::DirectoryLink(target),
                });
                Ok(())
            }
            NamespaceMutation::CreateFile { parent, name } => {
                let parent = self.resolve_directory(parent)?;
                let exists = self
                    .directories
                    .get(&parent)
                    .expect("resolved parent directory")
                    .volatile
                    .contains_key(name);
                if exists {
                    return Err(io::Error::from(io::ErrorKind::AlreadyExists));
                }
                let inode = self.next_inode;
                self.next_inode += 1;
                self.inodes.insert(inode, Inode::default());
                self.directories
                    .get_mut(&parent)
                    .expect("resolved parent directory")
                    .record(MetaOp::Create {
                        name: name.clone(),
                        entry: Entry::File(inode),
                    });
                Ok(())
            }
            NamespaceMutation::RemoveEntry { parent, name } => {
                let parent = self.resolve_directory(parent)?;
                let directory = self
                    .directories
                    .get_mut(&parent)
                    .expect("resolved parent directory");
                if !directory.volatile.contains_key(name) {
                    return Err(io::Error::from(io::ErrorKind::NotFound));
                }
                directory.record(MetaOp::Unlink { name: name.clone() });
                Ok(())
            }
            NamespaceMutation::RestoreRenamedTemporary {
                parent,
                destination,
            } => {
                let parent = self.resolve_directory(parent)?;
                let directory = self
                    .directories
                    .get_mut(&parent)
                    .expect("resolved parent directory");
                let temporary = directory
                    .pending
                    .iter()
                    .rev()
                    .find_map(|operation| match operation {
                        MetaOp::Rename { from, to }
                            if to == destination
                                && parse_temporary_artifact_name(from).is_some() =>
                        {
                            Some(from.clone())
                        }
                        _ => None,
                    })
                    .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))?;
                directory.record(MetaOp::Link {
                    from: destination.clone(),
                    to: temporary,
                });
                Ok(())
            }
            NamespaceMutation::ReplaceFile { parent, name } => {
                let parent = self.resolve_directory(parent)?;
                self.replace_file(parent, name)
            }
            NamespaceMutation::ReplaceTemporaryEntry { parent, entry_kind } => {
                let parent = self.resolve_directory(parent)?;
                let name = self
                    .directories
                    .get(&parent)
                    .expect("resolved parent directory")
                    .volatile
                    .keys()
                    .find(|name| {
                        parse_temporary_artifact_name(name)
                            .is_some_and(|artifact| artifact.entry_kind == *entry_kind)
                    })
                    .cloned()
                    .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))?;
                self.replace_file(parent, &name)
            }
        }
    }
}

impl Default for SimState {
    fn default() -> Self {
        let mut directories = HashMap::new();
        directories.insert(ROOT_DIRECTORY, DirState::default());
        Self {
            inodes: HashMap::new(),
            directories,
            next_inode: 1,
            op_counts: HashMap::new(),
            operations: Vec::new(),
            snapshots: Vec::new(),
            fsyncgate_violation: false,
        }
    }
}

/// Shared simulated filesystem handle.
#[derive(Clone)]
pub(crate) struct SimFs {
    state: Arc<Mutex<SimState>>,
    faults: Arc<Vec<Fault>>,
    partial_writes: Arc<Vec<PartialWrite>>,
    mutation_hooks: Arc<Vec<MutationHook>>,
    flush_capabilities: SimFlushCapabilities,
    exclusive_rename_supported: bool,
    hard_link_supported: bool,
    ambiguous_exact_cleanup: bool,
    directory_lock_supported: bool,
    competing_directory_writer: bool,
    active_directory_sweeper: bool,
    file_lock_supported: bool,
    sidecar_lock_busy: bool,
    paired_data_appears_before_create: bool,
    corrupt_applied_metadata: bool,
    preexisting: Vec<Preexisting>,
}

/// Anchored simulated directory.
#[derive(Debug)]
pub(crate) struct SimDir {
    inode: u64,
}

/// Open simulated file.
#[derive(Debug)]
pub(crate) struct SimFile {
    inode: u64,
    lease: SimLease,
}

#[derive(Debug)]
enum SimLease {
    None,
    Directory,
    Sidecar { name: String, inode: u64 },
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum SimNamedFileBinding {
    Same,
    Absent,
    Changed,
}

/// Captured simulated metadata.
#[derive(Debug)]
pub(crate) struct SimMetadata {
    mode: u32,
}

#[derive(Clone)]
enum Preexisting {
    File {
        name: String,
        content: Vec<u8>,
        basic_permissions: u32,
    },
    Directory {
        name: String,
    },
    DirectoryLink {
        name: String,
        target: Vec<String>,
    },
}

pub(crate) struct SimFsBuilder {
    faults: Vec<Fault>,
    partial_writes: Vec<PartialWrite>,
    mutation_hooks: Vec<MutationHook>,
    flush_capabilities: SimFlushCapabilities,
    exclusive_rename_supported: bool,
    hard_link_supported: bool,
    ambiguous_exact_cleanup: bool,
    directory_lock_supported: bool,
    competing_directory_writer: bool,
    active_directory_sweeper: bool,
    file_lock_supported: bool,
    sidecar_lock_busy: bool,
    paired_data_appears_before_create: bool,
    corrupt_applied_metadata: bool,
    preexisting: Vec<Preexisting>,
}

impl SimFsBuilder {
    pub fn new() -> Self {
        Self {
            faults: Vec::new(),
            partial_writes: Vec::new(),
            mutation_hooks: Vec::new(),
            flush_capabilities: SimFlushCapabilities::default(),
            exclusive_rename_supported: true,
            hard_link_supported: true,
            ambiguous_exact_cleanup: true,
            directory_lock_supported: true,
            competing_directory_writer: false,
            active_directory_sweeper: false,
            file_lock_supported: true,
            sidecar_lock_busy: false,
            paired_data_appears_before_create: false,
            corrupt_applied_metadata: false,
            preexisting: Vec::new(),
        }
    }

    pub fn fault(mut self, op: SimOp, occurrence: u32, kind: io::ErrorKind) -> Self {
        self.faults.push(Fault {
            op,
            occurrence,
            kind,
        });
        self
    }

    pub fn partial_write_fault(
        mut self,
        occurrence: u32,
        accepted: usize,
        kind: io::ErrorKind,
    ) -> Self {
        self.partial_writes.push(PartialWrite {
            occurrence,
            accepted,
            outcome: PartialWriteOutcome::Error(kind),
        });
        self
    }

    pub fn partial_write_panic(mut self, occurrence: u32, accepted: usize) -> Self {
        self.partial_writes.push(PartialWrite {
            occurrence,
            accepted,
            outcome: PartialWriteOutcome::Panic,
        });
        self
    }

    pub fn mutate_before(
        mut self,
        op: SimOp,
        occurrence: u32,
        mutation: NamespaceMutation,
    ) -> Self {
        self.mutation_hooks.push(MutationHook {
            operation: SimOperation { op, occurrence },
            mutation,
        });
        self
    }

    pub fn file_flush_support(mut self, support: SimFlushSupport) -> Self {
        self.flush_capabilities.file = support;
        self
    }

    pub fn created_parent_flush_support(mut self, support: SimFlushSupport) -> Self {
        self.flush_capabilities.created_parent = support;
        self
    }

    pub fn publication_flush_support(mut self, support: SimFlushSupport) -> Self {
        self.flush_capabilities.publication = support;
        self
    }

    pub fn without_exclusive_rename(mut self) -> Self {
        self.exclusive_rename_supported = false;
        self
    }

    pub fn without_hard_link(mut self) -> Self {
        self.hard_link_supported = false;
        self
    }

    pub fn without_ambiguous_exact_cleanup(mut self) -> Self {
        self.ambiguous_exact_cleanup = false;
        self
    }

    pub fn without_file_locks(mut self) -> Self {
        self.file_lock_supported = false;
        self
    }

    pub fn without_directory_locks(mut self) -> Self {
        self.directory_lock_supported = false;
        self
    }

    pub fn with_competing_directory_writer(mut self) -> Self {
        self.competing_directory_writer = true;
        self
    }

    pub fn with_active_directory_sweeper(mut self) -> Self {
        self.active_directory_sweeper = true;
        self
    }

    pub fn with_busy_sidecar_lock(mut self) -> Self {
        self.sidecar_lock_busy = true;
        self
    }

    pub fn with_paired_data_appearing_before_create(mut self) -> Self {
        self.paired_data_appears_before_create = true;
        self
    }

    pub fn with_corrupt_metadata_application(mut self) -> Self {
        self.corrupt_applied_metadata = true;
        self
    }

    pub fn preexisting_destination(mut self, name: &str, content: &[u8]) -> Self {
        self.preexisting.push(Preexisting::File {
            name: name.to_owned(),
            content: content.to_vec(),
            basic_permissions: 0o640,
        });
        self
    }

    pub fn preexisting_destination_with_permissions(
        mut self,
        name: &str,
        content: &[u8],
        basic_permissions: u32,
    ) -> Self {
        self.preexisting.push(Preexisting::File {
            name: name.to_owned(),
            content: content.to_vec(),
            basic_permissions,
        });
        self
    }

    pub fn preexisting_directory(mut self, name: &str) -> Self {
        self.preexisting.push(Preexisting::Directory {
            name: name.to_owned(),
        });
        self
    }

    pub fn preexisting_directory_link(mut self, name: &str, target: &str) -> Self {
        self.preexisting.push(Preexisting::DirectoryLink {
            name: name.to_owned(),
            target: path_components(target),
        });
        self
    }

    pub fn build(self) -> SimFs {
        let fs = SimFs {
            state: Arc::new(Mutex::new(SimState::default())),
            faults: Arc::new(self.faults),
            partial_writes: Arc::new(self.partial_writes),
            mutation_hooks: Arc::new(self.mutation_hooks),
            flush_capabilities: self.flush_capabilities,
            exclusive_rename_supported: self.exclusive_rename_supported,
            hard_link_supported: self.hard_link_supported,
            ambiguous_exact_cleanup: self.ambiguous_exact_cleanup,
            directory_lock_supported: self.directory_lock_supported,
            competing_directory_writer: self.competing_directory_writer,
            active_directory_sweeper: self.active_directory_sweeper,
            file_lock_supported: self.file_lock_supported,
            sidecar_lock_busy: self.sidecar_lock_busy,
            paired_data_appears_before_create: self.paired_data_appears_before_create,
            corrupt_applied_metadata: self.corrupt_applied_metadata,
            preexisting: self.preexisting,
        };
        if !fs.preexisting.is_empty() {
            let mut state = fs.state.lock().expect("simulation lock");
            for preexisting in &fs.preexisting {
                let (name, entry) = match preexisting {
                    Preexisting::File {
                        name,
                        content,
                        basic_permissions,
                    } => {
                        let inode = state.next_inode;
                        state.next_inode += 1;
                        state.inodes.insert(
                            inode,
                            Inode {
                                volatile: content.clone(),
                                durable: content.clone(),
                                volatile_basic_permissions: *basic_permissions,
                                durable_basic_permissions: *basic_permissions,
                                flush_failed: false,
                            },
                        );
                        (name, Entry::File(inode))
                    }
                    Preexisting::Directory { name } => {
                        let inode = state.next_inode;
                        state.next_inode += 1;
                        state.directories.insert(inode, DirState::default());
                        (name, Entry::Directory(inode))
                    }
                    Preexisting::DirectoryLink { .. } => continue,
                };
                let root = state
                    .directories
                    .get_mut(&ROOT_DIRECTORY)
                    .expect("root directory");
                root.volatile.insert(name.clone(), entry);
                root.durable.insert(name.clone(), entry);
            }
            for preexisting in &fs.preexisting {
                let Preexisting::DirectoryLink { name, target } = preexisting else {
                    continue;
                };
                let target = state
                    .resolve_directory(target)
                    .expect("preexisting directory-link target");
                let root = state
                    .directories
                    .get_mut(&ROOT_DIRECTORY)
                    .expect("root directory");
                let entry = Entry::DirectoryLink(target);
                root.volatile.insert(name.clone(), entry);
                root.durable.insert(name.clone(), entry);
            }
        }
        fs
    }
}

impl SimFs {
    #[cfg(all(test, windows))]
    pub(crate) fn open_root_no_follow_final(&self, path: &Path) -> io::Result<SimDir> {
        self.enter(SimOp::OpenRoot)?;
        let (_, components) =
            split_absolute_path(path).map_err(|_| io::Error::from(io::ErrorKind::InvalidInput))?;
        let state = self.state.lock().expect("simulation lock");
        let inode = state.resolve_directory_no_follow_final(&components)?;
        Ok(SimDir { inode })
    }

    pub fn operations(&self) -> Vec<SimOperation> {
        self.state
            .lock()
            .expect("simulation lock")
            .operations
            .clone()
    }

    pub fn snapshots(&self) -> Vec<Snapshot> {
        self.state
            .lock()
            .expect("simulation lock")
            .snapshots
            .clone()
    }

    pub fn fsyncgate_violated(&self) -> bool {
        self.state
            .lock()
            .expect("simulation lock")
            .fsyncgate_violation
    }

    pub fn final_snapshot(&self) -> Snapshot {
        let state = self.state.lock().expect("simulation lock");
        Snapshot {
            label: SimOp::Close,
            inodes: state.inodes.clone(),
            directories: state.directories.clone(),
        }
    }

    fn enter_operation(&self, op: SimOp) -> io::Result<SimOperation> {
        let mut state = self.state.lock().expect("simulation lock");
        let count = state.op_counts.entry(op).or_insert(0);
        let occurrence = *count;
        *count += 1;
        let operation = SimOperation { op, occurrence };
        state.operations.push(operation);
        for hook in self
            .mutation_hooks
            .iter()
            .filter(|hook| hook.operation == operation)
        {
            state.apply_mutation(&hook.mutation)?;
        }
        drop(state);
        for fault in self.faults.iter() {
            if fault.op == op && fault.occurrence == occurrence {
                return Err(io::Error::new(fault.kind, "injected fault"));
            }
        }
        Ok(operation)
    }

    fn enter(&self, op: SimOp) -> io::Result<()> {
        self.enter_operation(op).map(drop)
    }

    fn snapshot(&self, label: SimOp) {
        let mut state = self.state.lock().expect("simulation lock");
        let snapshot = Snapshot {
            label,
            inodes: state.inodes.clone(),
            directories: state.directories.clone(),
        };
        state.snapshots.push(snapshot);
    }

    fn flush_directory_state(
        &self,
        directory: u64,
        operation: SimOp,
        support: SimFlushSupport,
    ) -> io::Result<FlushOutcome> {
        self.enter(operation)?;
        if support == SimFlushSupport::Full {
            let mut state = self.state.lock().expect("simulation lock");
            let directory = state
                .directories
                .get_mut(&directory)
                .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))?;
            directory.durable = directory.volatile.clone();
            directory.pending.clear();
        }
        self.snapshot(operation);
        Ok(match support {
            SimFlushSupport::Full => FlushOutcome::Full,
            SimFlushSupport::Degraded => FlushOutcome::Degraded,
            SimFlushSupport::Unsupported => FlushOutcome::Unsupported,
        })
    }

    fn named_file_binding(
        &self,
        dir: &SimDir,
        name: &str,
        inode: u64,
    ) -> io::Result<SimNamedFileBinding> {
        let state = self.state.lock().expect("simulation lock");
        let directory = state
            .directories
            .get(&dir.inode)
            .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))?;
        Ok(match directory.volatile.get(name) {
            Some(Entry::File(named_inode)) if *named_inode == inode => SimNamedFileBinding::Same,
            None => SimNamedFileBinding::Absent,
            Some(_) => SimNamedFileBinding::Changed,
        })
    }

    fn unlink_exact_file(&self, dir: &SimDir, name: &str, inode: u64) -> io::Result<()> {
        self.enter(SimOp::Unlink)?;
        let mut state = self.state.lock().expect("simulation lock");
        let directory = state
            .directories
            .get_mut(&dir.inode)
            .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))?;
        match directory.volatile.get(name) {
            Some(Entry::File(named_inode)) if *named_inode == inode => {
                directory.record(MetaOp::Unlink {
                    name: name.to_owned(),
                });
            }
            None => return Ok(()),
            Some(_) => {
                return Err(io::Error::other(
                    "temporary name no longer refers to retained file",
                ));
            }
        }
        drop(state);
        self.snapshot(SimOp::Unlink);
        Ok(())
    }

    fn cleanup_sidecar_file(&self, dir: &SimDir, name: &str, inode: u64) -> io::Result<()> {
        let removal = self.unlink_exact_file(dir, name, inode);
        let release = self.enter(SimOp::ReleaseLease);
        removal.and(release)
    }

    fn remove_staged_data(
        &self,
        dir: &SimDir,
        name: &str,
        inode: u64,
        residual: StagedNameResidual,
    ) -> io::Result<()> {
        self.enter(SimOp::RevalidateStagedData)?;
        match self.named_file_binding(dir, name, inode)? {
            SimNamedFileBinding::Absent => {}
            SimNamedFileBinding::Same if residual == StagedNameResidual::PresentAfterHardLink => {
                self.unlink_exact_file(dir, name, inode)?;
            }
            SimNamedFileBinding::Same => {
                return Err(io::Error::other(
                    "rename left the staged data name unexpectedly present",
                ));
            }
            SimNamedFileBinding::Changed => {
                return Err(io::Error::other(
                    "staged data name no longer refers to retained file",
                ));
            }
        }
        if self.named_file_binding(dir, name, inode)? == SimNamedFileBinding::Absent {
            Ok(())
        } else {
            Err(io::Error::from(io::ErrorKind::AlreadyExists))
        }
    }

    fn remove_lease_after_data(&self, dir: &SimDir, file: &mut SimFile) -> io::Result<()> {
        if let SimLease::Sidecar { name, inode } = &file.lease {
            self.enter(SimOp::RevalidateSidecar)?;
            match self.named_file_binding(dir, name, *inode)? {
                SimNamedFileBinding::Same => {
                    self.unlink_exact_file(dir, name, *inode)?;
                }
                SimNamedFileBinding::Absent => {
                    return Err(io::Error::other("lease sidecar name disappeared"));
                }
                SimNamedFileBinding::Changed => {
                    return Err(io::Error::other(
                        "lease sidecar name no longer refers to retained file",
                    ));
                }
            }
            if self.named_file_binding(dir, name, *inode)? != SimNamedFileBinding::Absent {
                return Err(io::Error::from(io::ErrorKind::AlreadyExists));
            }
        }
        if matches!(file.lease, SimLease::None) {
            return Ok(());
        }
        file.lease = SimLease::None;
        self.enter(SimOp::ReleaseLease)
    }
}

impl FsOps for SimFs {
    type Dir = SimDir;
    type File = SimFile;
    type Metadata = SimMetadata;

    fn advertised_sync_level_ceiling(&self) -> SyncLevel {
        SyncLevel::FileAndNamespaceSynchronized
    }

    fn open_root(&self, path: &Path) -> io::Result<SimDir> {
        self.enter(SimOp::OpenRoot)?;
        let (_, components) =
            split_absolute_path(path).map_err(|_| io::Error::from(io::ErrorKind::InvalidInput))?;
        let state = self.state.lock().expect("simulation lock");
        let inode = state.resolve_directory(&components)?;
        Ok(SimDir { inode })
    }

    fn open_dir_at(&self, parent: &SimDir, name: &str, follow_links: bool) -> io::Result<SimDir> {
        self.enter(SimOp::OpenDirAt)?;
        let state = self.state.lock().expect("simulation lock");
        let parent = state
            .directories
            .get(&parent.inode)
            .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))?;
        match parent.volatile.get(name) {
            Some(Entry::Directory(inode)) => Ok(SimDir { inode: *inode }),
            Some(Entry::DirectoryLink(inode)) if follow_links => Ok(SimDir { inode: *inode }),
            Some(Entry::DirectoryLink(_)) => Err(io::Error::from(io::ErrorKind::InvalidInput)),
            Some(Entry::File(_)) => Err(io::Error::from(io::ErrorKind::NotADirectory)),
            None => Err(io::Error::from(io::ErrorKind::NotFound)),
        }
    }

    fn create_dir_at(
        &self,
        parent: &SimDir,
        name: &str,
        _permissions: DirectoryPermissions,
    ) -> io::Result<()> {
        self.enter(SimOp::CreateDirAt)?;
        let mut state = self.state.lock().expect("simulation lock");
        let exists = state
            .directories
            .get(&parent.inode)
            .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))?
            .volatile
            .contains_key(name);
        if exists {
            return Err(io::Error::from(io::ErrorKind::AlreadyExists));
        }
        state.create_directory(parent.inode, name.to_owned())?;
        drop(state);
        self.snapshot(SimOp::CreateDirAt);
        Ok(())
    }

    fn create_file_at(&self, dir: &SimDir, name: &str, owner_only: bool) -> io::Result<SimFile> {
        self.enter(SimOp::CreateFileAt)?;
        let mut state = self.state.lock().expect("simulation lock");
        let exists = state
            .directories
            .get(&dir.inode)
            .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))?
            .volatile
            .contains_key(name);
        if exists {
            return Err(io::Error::from(io::ErrorKind::AlreadyExists));
        }
        let inode = state.next_inode;
        state.next_inode += 1;
        state.inodes.insert(
            inode,
            Inode {
                volatile_basic_permissions: if owner_only { 0o600 } else { 0o666 },
                durable_basic_permissions: if owner_only { 0o600 } else { 0o666 },
                ..Inode::default()
            },
        );
        state
            .directories
            .get_mut(&dir.inode)
            .expect("destination directory")
            .record(MetaOp::Create {
                name: name.to_owned(),
                entry: Entry::File(inode),
            });
        drop(state);
        self.snapshot(SimOp::CreateFileAt);
        Ok(SimFile {
            inode,
            lease: SimLease::None,
        })
    }

    fn create_staged_at(
        &self,
        dir: &SimDir,
        role: TemporaryFileRole,
        owner_only: bool,
    ) -> Result<CreatedStaged<SimFile>, StagedCreationError> {
        self.enter(SimOp::ProbeDirectoryLeaseExclusive)
            .map_err(StagedCreationError::from)?;
        if self.directory_lock_supported {
            self.enter(SimOp::AcquireDirectoryLeaseShared)
                .map_err(StagedCreationError::from)?;
            if self.active_directory_sweeper {
                return Err(StagedCreationError::classified(
                    io::Error::from(io::ErrorKind::ResourceBusy),
                    StagedCreationFailureKind::ResourceBusy,
                ));
            }
            let _exclusive_probe_was_busy = self.competing_directory_writer;
            for _ in 0..MAX_TEMPORARY_ARTIFACT_ATTEMPTS {
                let names =
                    new_temporary_artifact_names(role, TemporaryArtifactProtocol::DirectoryLeaseV1)
                        .map_err(StagedCreationError::from)?;
                match self.create_file_at(dir, &names.data, owner_only) {
                    Ok(mut file) => {
                        file.lease = SimLease::Directory;
                        return Ok(CreatedStaged {
                            name: names.data,
                            file,
                        });
                    }
                    Err(error) if error.kind() == io::ErrorKind::AlreadyExists => {}
                    Err(error) => return Err(StagedCreationError::from(error)),
                }
            }
        } else {
            for _ in 0..MAX_TEMPORARY_ARTIFACT_ATTEMPTS {
                let names =
                    new_temporary_artifact_names(role, TemporaryArtifactProtocol::SidecarLeaseV1)
                        .map_err(StagedCreationError::from)?;
                let lease_name = names
                    .lease
                    .expect("sidecar protocol must generate a lease name");
                let lease = match self.create_file_at(dir, &lease_name, true) {
                    Ok(lease) => lease,
                    Err(error) if error.kind() == io::ErrorKind::AlreadyExists => continue,
                    Err(error) => return Err(StagedCreationError::from(error)),
                };
                let lease_inode = lease.inode;
                if let Err(error) = self.enter(SimOp::LockFile) {
                    let cleanup_incomplete = self
                        .cleanup_sidecar_file(dir, &lease_name, lease_inode)
                        .is_err();
                    let failure = StagedCreationError::from(error);
                    return Err(if cleanup_incomplete {
                        failure.with_cleanup_incomplete()
                    } else {
                        failure
                    });
                }
                if self.sidecar_lock_busy {
                    // A sweeper owns the lease after the create-to-lock race.
                    // The producer must close its non-owning handle without
                    // unlinking the now foreign-controlled name.
                    let _ = self.enter(SimOp::ReleaseLease);
                    return Err(StagedCreationError::classified(
                        io::Error::from(io::ErrorKind::ResourceBusy),
                        StagedCreationFailureKind::ResourceBusy,
                    )
                    .with_cleanup_incomplete());
                }
                if !self.file_lock_supported {
                    let cleanup_incomplete = self
                        .cleanup_sidecar_file(dir, &lease_name, lease_inode)
                        .is_err();
                    let failure = StagedCreationError::classified(
                        io::Error::from(io::ErrorKind::Unsupported),
                        StagedCreationFailureKind::Unsupported,
                    );
                    return Err(if cleanup_incomplete {
                        failure.with_cleanup_incomplete()
                    } else {
                        failure
                    });
                }
                if let Err(error) = self.enter(SimOp::RevalidateSidecar) {
                    let cleanup_incomplete = self
                        .cleanup_sidecar_file(dir, &lease_name, lease_inode)
                        .is_err();
                    let failure = StagedCreationError::from(error);
                    return Err(if cleanup_incomplete {
                        failure.with_cleanup_incomplete()
                    } else {
                        failure
                    });
                }
                if self
                    .named_file_binding(dir, &lease_name, lease_inode)
                    .map_err(StagedCreationError::from)?
                    != SimNamedFileBinding::Same
                {
                    let primary = StagedCreationError::from(io::Error::other(
                        "simulated sidecar binding changed",
                    ));
                    return Err(
                        if self
                            .cleanup_sidecar_file(dir, &lease_name, lease_inode)
                            .is_err()
                        {
                            primary.with_cleanup_incomplete()
                        } else {
                            primary
                        },
                    );
                }
                if let Err(error) = self.enter(SimOp::CheckDataAbsent) {
                    let cleanup_incomplete = self
                        .cleanup_sidecar_file(dir, &lease_name, lease_inode)
                        .is_err();
                    let failure = StagedCreationError::from(error);
                    return Err(if cleanup_incomplete {
                        failure.with_cleanup_incomplete()
                    } else {
                        failure
                    });
                }
                if self.paired_data_appears_before_create {
                    let error = io::Error::from(io::ErrorKind::AlreadyExists);
                    if self
                        .cleanup_sidecar_file(dir, &lease_name, lease_inode)
                        .is_err()
                    {
                        return Err(StagedCreationError::from(error).with_cleanup_incomplete());
                    }
                    continue;
                }
                match self.create_file_at(dir, &names.data, owner_only) {
                    Ok(mut file) => {
                        file.lease = SimLease::Sidecar {
                            name: lease_name,
                            inode: lease_inode,
                        };
                        return Ok(CreatedStaged {
                            name: names.data,
                            file,
                        });
                    }
                    Err(error) if error.kind() == io::ErrorKind::AlreadyExists => {
                        if self
                            .cleanup_sidecar_file(dir, &lease_name, lease_inode)
                            .is_err()
                        {
                            return Err(StagedCreationError::from(error).with_cleanup_incomplete());
                        }
                    }
                    Err(error) => {
                        let cleanup_incomplete = self
                            .cleanup_sidecar_file(dir, &lease_name, lease_inode)
                            .is_err();
                        let failure = StagedCreationError::from(error);
                        return Err(if cleanup_incomplete {
                            failure.with_cleanup_incomplete()
                        } else {
                            failure
                        });
                    }
                }
            }
        }
        Err(StagedCreationError::from(io::Error::from(
            io::ErrorKind::AlreadyExists,
        )))
    }

    fn write_all(&self, file: &mut SimFile, buffer: &[u8]) -> io::Result<()> {
        let operation = self.enter_operation(SimOp::WriteAll)?;
        if let Some(disruption) = self
            .partial_writes
            .iter()
            .find(|disruption| disruption.occurrence == operation.occurrence)
        {
            let accepted = disruption.accepted.min(buffer.len());
            let mut state = self.state.lock().expect("simulation lock");
            let inode = state
                .inodes
                .get_mut(&file.inode)
                .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))?;
            inode.volatile.extend_from_slice(&buffer[..accepted]);
            drop(state);
            self.snapshot(SimOp::WriteAll);
            match disruption.outcome {
                PartialWriteOutcome::Error(kind) => {
                    return Err(io::Error::new(kind, "injected partial-write fault"));
                }
                PartialWriteOutcome::Panic => panic!("injected partial-write panic"),
            }
        }
        let mut state = self.state.lock().expect("simulation lock");
        let inode = state
            .inodes
            .get_mut(&file.inode)
            .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))?;
        inode.volatile.extend_from_slice(buffer);
        drop(state);
        self.snapshot(SimOp::WriteAll);
        Ok(())
    }

    fn flush_file(&self, file: &mut SimFile, _kind: FlushKind) -> io::Result<FlushOutcome> {
        let entered = self.enter(SimOp::FlushFile);
        let mut state = self.state.lock().expect("simulation lock");
        let already_failed = state
            .inodes
            .get(&file.inode)
            .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))?
            .flush_failed;
        if already_failed {
            state.fsyncgate_violation = true;
        }
        let inode = state
            .inodes
            .get_mut(&file.inode)
            .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))?;
        if let Err(error) = entered {
            inode.flush_failed = true;
            inode.volatile = inode.durable.clone();
            inode.volatile_basic_permissions = inode.durable_basic_permissions;
            drop(state);
            self.snapshot(SimOp::FlushFile);
            return Err(error);
        }
        if self.flush_capabilities.file == SimFlushSupport::Full {
            inode.durable = inode.volatile.clone();
            inode.durable_basic_permissions = inode.volatile_basic_permissions;
        }
        drop(state);
        self.snapshot(SimOp::FlushFile);
        Ok(match self.flush_capabilities.file {
            SimFlushSupport::Full => FlushOutcome::Full,
            SimFlushSupport::Degraded => FlushOutcome::Degraded,
            SimFlushSupport::Unsupported => FlushOutcome::Unsupported,
        })
    }

    fn read_replace_metadata(&self, dir: &SimDir, name: &str) -> io::Result<Option<SimMetadata>> {
        self.enter(SimOp::ReadMetadata)?;
        let state = self.state.lock().expect("simulation lock");
        let directory = state
            .directories
            .get(&dir.inode)
            .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))?;
        let metadata = match directory.volatile.get(name) {
            Some(Entry::File(inode)) => {
                let inode = state
                    .inodes
                    .get(inode)
                    .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))?;
                Some(SimMetadata {
                    mode: inode.volatile_basic_permissions & 0o777,
                })
            }
            Some(Entry::Directory(_) | Entry::DirectoryLink(_)) => {
                return Err(io::Error::from(io::ErrorKind::InvalidInput));
            }
            None => None,
        };
        drop(state);
        self.snapshot(SimOp::ReadMetadata);
        Ok(metadata)
    }

    fn apply_replace_metadata(&self, file: &mut SimFile, metadata: &SimMetadata) -> io::Result<()> {
        self.enter(SimOp::ApplyMetadata)?;
        let mut state = self.state.lock().expect("simulation lock");
        let inode = state
            .inodes
            .get_mut(&file.inode)
            .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))?;
        inode.volatile_basic_permissions = if self.corrupt_applied_metadata {
            metadata.mode ^ 0o001
        } else {
            metadata.mode
        };
        drop(state);
        self.snapshot(SimOp::ApplyMetadata);
        Ok(())
    }

    fn verify_replace_metadata(
        &self,
        file: &mut SimFile,
        metadata: &SimMetadata,
    ) -> io::Result<()> {
        self.enter(SimOp::VerifyMetadata)?;
        let state = self.state.lock().expect("simulation lock");
        let inode = state
            .inodes
            .get(&file.inode)
            .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))?;
        if inode.volatile_basic_permissions != metadata.mode {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "simulated basic permissions do not match",
            ));
        }
        drop(state);
        self.snapshot(SimOp::VerifyMetadata);
        Ok(())
    }

    fn staged_file_identity(&self, file: &SimFile) -> io::Result<FileIdentity> {
        self.enter(SimOp::CaptureIdentity)?;
        Ok(FileIdentity::simulated(file.inode))
    }

    fn observe_file_identity_at(
        &self,
        dir: &SimDir,
        name: &str,
    ) -> io::Result<Option<FileIdentity>> {
        self.enter(SimOp::ObserveIdentity)?;
        let state = self.state.lock().expect("simulation lock");
        let directory = state
            .directories
            .get(&dir.inode)
            .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))?;
        Ok(directory.volatile.get(name).map(|entry| {
            let inode = match entry {
                Entry::File(inode) | Entry::Directory(inode) | Entry::DirectoryLink(inode) => {
                    *inode
                }
            };
            FileIdentity::simulated(inode)
        }))
    }

    fn rename(
        &self,
        dir: &SimDir,
        from: &str,
        file: &mut SimFile,
        to: &str,
        no_replace: bool,
    ) -> Result<(), PublicationAttemptError> {
        if no_replace && !self.exclusive_rename_supported {
            return Err(PublicationAttemptError::DefinitelyUnchanged(
                io::Error::from(io::ErrorKind::Unsupported),
            ));
        }
        self.enter(SimOp::PrepareRename)
            .map_err(PublicationAttemptError::DefinitelyUnchanged)?;
        match self
            .named_file_binding(dir, from, file.inode)
            .map_err(PublicationAttemptError::DefinitelyUnchanged)?
        {
            SimNamedFileBinding::Same => {}
            SimNamedFileBinding::Absent => {
                return Err(PublicationAttemptError::DefinitelyUnchanged(
                    io::Error::from(io::ErrorKind::NotFound),
                ));
            }
            SimNamedFileBinding::Changed => {
                return Err(PublicationAttemptError::DefinitelyUnchanged(
                    io::Error::other("rename source no longer refers to retained file"),
                ));
            }
        }
        self.enter(SimOp::Rename)
            .map_err(PublicationAttemptError::MayHaveMutated)?;
        let mut state = self.state.lock().expect("simulation lock");
        let directory = state
            .directories
            .get_mut(&dir.inode)
            .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))
            .map_err(PublicationAttemptError::MayHaveMutated)?;
        if !directory.volatile.contains_key(from) {
            return Err(PublicationAttemptError::MayHaveMutated(io::Error::from(
                io::ErrorKind::NotFound,
            )));
        }
        if no_replace && directory.volatile.contains_key(to) {
            return Err(PublicationAttemptError::MayHaveMutated(io::Error::from(
                io::ErrorKind::AlreadyExists,
            )));
        }
        directory.record(MetaOp::Rename {
            from: from.to_owned(),
            to: to.to_owned(),
        });
        drop(state);
        self.snapshot(SimOp::Rename);
        self.enter(SimOp::RenameReply)
            .map_err(PublicationAttemptError::MayHaveMutated)
    }

    fn hard_link(
        &self,
        dir: &SimDir,
        from: &str,
        file: &SimFile,
        to: &str,
    ) -> Result<(), PublicationAttemptError> {
        if !self.hard_link_supported {
            return Err(PublicationAttemptError::DefinitelyUnchanged(
                io::Error::from(io::ErrorKind::Unsupported),
            ));
        }
        self.enter(SimOp::PrepareHardLink)
            .map_err(PublicationAttemptError::DefinitelyUnchanged)?;
        match self
            .named_file_binding(dir, from, file.inode)
            .map_err(PublicationAttemptError::DefinitelyUnchanged)?
        {
            SimNamedFileBinding::Same => {}
            SimNamedFileBinding::Absent => {
                return Err(PublicationAttemptError::DefinitelyUnchanged(
                    io::Error::from(io::ErrorKind::NotFound),
                ));
            }
            SimNamedFileBinding::Changed => {
                return Err(PublicationAttemptError::DefinitelyUnchanged(
                    io::Error::other("hard-link source no longer refers to retained file"),
                ));
            }
        }
        self.enter(SimOp::HardLink)
            .map_err(PublicationAttemptError::MayHaveMutated)?;
        let mut state = self.state.lock().expect("simulation lock");
        let directory = state
            .directories
            .get_mut(&dir.inode)
            .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))
            .map_err(PublicationAttemptError::MayHaveMutated)?;
        if !directory.volatile.contains_key(from) {
            return Err(PublicationAttemptError::MayHaveMutated(io::Error::from(
                io::ErrorKind::NotFound,
            )));
        }
        if directory.volatile.contains_key(to) {
            return Err(PublicationAttemptError::MayHaveMutated(io::Error::from(
                io::ErrorKind::AlreadyExists,
            )));
        }
        directory.record(MetaOp::Link {
            from: from.to_owned(),
            to: to.to_owned(),
        });
        drop(state);
        self.snapshot(SimOp::HardLink);
        self.enter(SimOp::HardLinkReply)
            .map_err(PublicationAttemptError::MayHaveMutated)
    }

    fn unlink(&self, dir: &SimDir, name: &str) -> io::Result<()> {
        self.enter(SimOp::Unlink)?;
        let mut state = self.state.lock().expect("simulation lock");
        let directory = state
            .directories
            .get_mut(&dir.inode)
            .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))?;
        if !directory.volatile.contains_key(name) {
            return Err(io::Error::from(io::ErrorKind::NotFound));
        }
        directory.record(MetaOp::Unlink {
            name: name.to_owned(),
        });
        drop(state);
        self.snapshot(SimOp::Unlink);
        Ok(())
    }

    fn flush_directory(&self, dir: &SimDir) -> io::Result<FlushOutcome> {
        self.flush_directory_state(
            dir.inode,
            SimOp::FlushDirectory,
            self.flush_capabilities.created_parent,
        )
    }

    fn flush_publication(&self, dir: &SimDir, _file: &mut SimFile) -> io::Result<FlushOutcome> {
        self.flush_directory_state(
            dir.inode,
            SimOp::FlushPublication,
            self.flush_capabilities.publication,
        )
    }

    fn close(&self, mut file: SimFile) -> io::Result<()> {
        let data_close = self.enter(SimOp::Close);
        let lease_close = if matches!(file.lease, SimLease::None) {
            Ok(())
        } else {
            let result = self.enter(SimOp::ReleaseLease);
            file.lease = SimLease::None;
            result
        };
        data_close.and(lease_close)
    }

    fn finalize_staged_after_publication(
        &self,
        dir: &SimDir,
        name: &str,
        file: &mut SimFile,
        residual: StagedNameResidual,
    ) -> io::Result<()> {
        self.remove_staged_data(dir, name, file.inode, residual)?;
        self.remove_lease_after_data(dir, file)
    }

    fn discard_staged(&self, dir: &SimDir, name: &str, mut file: SimFile) -> io::Result<()> {
        let removal = self
            .remove_staged_data(
                dir,
                name,
                file.inode,
                StagedNameResidual::PresentAfterHardLink,
            )
            .and_then(|()| self.remove_lease_after_data(dir, &mut file));
        let close = self.close(file);
        removal.and(close)
    }

    fn cleanup_after_publication_unknown(
        &self,
        dir: &SimDir,
        name: &str,
        file: SimFile,
    ) -> PublicationUnknownCleanup {
        if self.ambiguous_exact_cleanup {
            return match self.discard_staged(dir, name, file) {
                Ok(()) => PublicationUnknownCleanup::Complete,
                Err(error) => PublicationUnknownCleanup::Incomplete(Some(error)),
            };
        }
        match self.close(file) {
            Ok(()) => PublicationUnknownCleanup::Incomplete(None),
            Err(error) => PublicationUnknownCleanup::Incomplete(Some(error)),
        }
    }

    fn ambiguous_publication_cleanup(&self) -> AmbiguousPublicationCleanup {
        if self.ambiguous_exact_cleanup {
            AmbiguousPublicationCleanup::ExactStagedName
        } else {
            AmbiguousPublicationCleanup::CloseOnly
        }
    }

    fn is_rename_unsupported(&self, error: &io::Error) -> bool {
        error.kind() == io::ErrorKind::Unsupported
    }

    fn is_rename_retryable(&self, error: &io::Error) -> bool {
        error.kind() == io::ErrorKind::ResourceBusy
    }

    fn rename_retry_delays(&self) -> &[Duration] {
        const DELAYS: [Duration; 3] = [Duration::ZERO, Duration::ZERO, Duration::ZERO];
        &DELAYS
    }

    fn sleep(&self, _duration: Duration) {}
}
