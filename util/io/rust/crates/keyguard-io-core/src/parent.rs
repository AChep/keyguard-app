//! Anchored preparation of an atomic write's destination directory.
//!
//! Missing components are created one at a time relative to retained
//! directory handles. For a durable transaction, every directory entry
//! created by this operation is persisted before the staged file is created.

use std::{io, path::Path};

use crate::{
    directory::{AtomicDirectory, RelativeDestination, split_absolute_path},
    durability::{SyncLevel, SyncPolicy, SyncPolicyError},
    error::{FailureKind, FileSystemFailure, Operation, TxnError},
    fsops::{FlushOutcome, FsOps},
    txn::{ExistingParentLinkPolicy, ParentDirectoryPolicy},
};

/// Prepared destination-parent capability and the synchronization level
/// selected before staging.
pub(crate) struct PreparedParent<D> {
    base: PreparedParentBase<D>,
    descendants: Vec<D>,
    pub sync_level: SyncLevel,
}

enum PreparedParentBase<D> {
    Owned(Vec<D>),
    Retained(AtomicDirectory<D>),
}

impl<D> PreparedParent<D> {
    pub fn dir(&self) -> Result<&D, TxnError> {
        if let Some(directory) = self.descendants.last() {
            return Ok(directory);
        }
        match &self.base {
            PreparedParentBase::Owned(chain) => chain.last().ok_or_else(TxnError::bridge_internal),
            PreparedParentBase::Retained(directory) => directory.dir(),
        }
    }
}

/// Opens the destination parent and durably prepares any missing components.
pub(crate) fn prepare_parent<F: FsOps>(
    fs: &F,
    parent: &Path,
    policy: ParentDirectoryPolicy,
    existing_parent_links: ExistingParentLinkPolicy,
    synchronization: SyncPolicy,
    sync_level: SyncLevel,
    preflight_existing_namespace: bool,
) -> Result<PreparedParent<F::Dir>, TxnError> {
    let (root, components) = split_absolute_path(parent)?;
    let (base, missing_components, first_missing_observed) = match (policy, existing_parent_links) {
        (ParentDirectoryPolicy::RequireExisting, ExistingParentLinkPolicy::FollowAndPin) => (
            open_absolute(fs, parent)?,
            &components[components.len()..],
            false,
        ),
        (ParentDirectoryPolicy::RequireExisting, ExistingParentLinkPolicy::Reject) => {
            let root = open_absolute(fs, &root)?;
            let directory = open_strict_descendant(fs, &root, &components)?;
            (directory.unwrap_or(root), &[][..], false)
        }
        (ParentDirectoryPolicy::CreateMissing { .. }, ExistingParentLinkPolicy::FollowAndPin) => {
            let (directory, depth) = open_deepest_absolute(fs, parent, &root, &components)?;
            (directory, &components[depth..], depth < components.len())
        }
        (ParentDirectoryPolicy::CreateMissing { .. }, ExistingParentLinkPolicy::Reject) => {
            let root = open_absolute(fs, &root)?;
            let (directory, depth) = open_deepest_strict_descendant(fs, &root, &components)?;
            (
                directory.unwrap_or(root),
                &components[depth..],
                depth < components.len(),
            )
        }
    };
    let (descendants, selected) = prepare_components(
        fs,
        &base,
        missing_components,
        first_missing_observed,
        policy,
        ExistingParentLinkPolicy::Reject,
        synchronization,
        sync_level,
        preflight_existing_namespace,
    )?;
    let mut chain = Vec::with_capacity(descendants.len() + 1);
    chain.push(base);
    chain.extend(descendants);
    Ok(PreparedParent {
        base: PreparedParentBase::Owned(chain),
        descendants: Vec::new(),
        sync_level: selected,
    })
}

/// Opens a strict relative destination beneath a retained trusted root.
pub(crate) fn prepare_parent_at<F: FsOps>(
    fs: &F,
    directory: &AtomicDirectory<F::Dir>,
    destination: &RelativeDestination,
    policy: ParentDirectoryPolicy,
    synchronization: SyncPolicy,
    sync_level: SyncLevel,
    preflight_existing_namespace: bool,
) -> Result<PreparedParent<F::Dir>, TxnError> {
    let root = directory.dir()?;
    let components = destination.parent_components();
    let (deepest, depth, first_missing_observed) = match policy {
        ParentDirectoryPolicy::RequireExisting => (
            open_strict_descendant(fs, root, components)?,
            components.len(),
            false,
        ),
        ParentDirectoryPolicy::CreateMissing { .. } => {
            let (deepest, depth) = open_deepest_strict_descendant(fs, root, components)?;
            (deepest, depth, depth < components.len())
        }
    };
    let base = deepest.as_ref().unwrap_or(root);
    let (descendants, selected) = prepare_components(
        fs,
        base,
        &components[depth..],
        first_missing_observed,
        policy,
        ExistingParentLinkPolicy::Reject,
        synchronization,
        sync_level,
        preflight_existing_namespace,
    )?;
    let mut all_descendants =
        Vec::with_capacity(descendants.len() + usize::from(deepest.is_some()));
    if let Some(deepest) = deepest {
        all_descendants.push(deepest);
    }
    all_descendants.extend(descendants);
    Ok(PreparedParent {
        base: PreparedParentBase::Retained(directory.clone()),
        descendants: all_descendants,
        sync_level: selected,
    })
}

fn open_absolute<F: FsOps>(fs: &F, path: &Path) -> Result<F::Dir, TxnError> {
    fs.open_root(path)
        .map_err(|error| parent_resolution_error(&error))
}

fn open_strict_descendant<F: FsOps>(
    fs: &F,
    root: &F::Dir,
    components: &[String],
) -> Result<Option<F::Dir>, TxnError> {
    if components.is_empty() {
        return Ok(None);
    }
    fs.open_dir_path_at(root, components)
        .map(Some)
        .map_err(|error| parent_resolution_error(&error))
}

fn open_deepest_absolute<F: FsOps>(
    fs: &F,
    parent: &Path,
    root: &Path,
    components: &[String],
) -> Result<(F::Dir, usize), TxnError> {
    match fs.open_root(parent) {
        Ok(directory) => return Ok((directory, components.len())),
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(error) => return Err(parent_resolution_error(&error)),
    }

    let root = open_absolute(fs, root)?;
    let (deepest, depth) = walk_deepest_descendant(
        fs,
        &root,
        components,
        ExistingParentLinkPolicy::FollowAndPin,
    )?;
    Ok((deepest.unwrap_or(root), depth))
}

fn open_deepest_strict_descendant<F: FsOps>(
    fs: &F,
    root: &F::Dir,
    components: &[String],
) -> Result<(Option<F::Dir>, usize), TxnError> {
    if components.is_empty() {
        return Ok((None, 0));
    }
    match fs.open_dir_path_at(root, components) {
        Ok(directory) => return Ok((Some(directory), components.len())),
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(error) => return Err(parent_resolution_error(&error)),
    }

    walk_deepest_descendant(fs, root, components, ExistingParentLinkPolicy::Reject)
}

fn walk_deepest_descendant<F: FsOps>(
    fs: &F,
    root: &F::Dir,
    components: &[String],
    existing_parent_links: ExistingParentLinkPolicy,
) -> Result<(Option<F::Dir>, usize), TxnError> {
    let mut current = None;
    for (depth, component) in components.iter().enumerate() {
        let parent = current.as_ref().unwrap_or(root);
        let follow_links = existing_parent_links == ExistingParentLinkPolicy::FollowAndPin;
        match fs.open_dir_at_for_traversal(parent, component, follow_links) {
            Ok(directory) => current = Some(directory),
            Err(error) if error.kind() == io::ErrorKind::NotFound => {
                let current = current
                    .as_ref()
                    .map(|directory| {
                        fs.reopen_dir(directory).map_err(|error| {
                            TxnError::from_io_error(Operation::PrepareParent, &error)
                        })
                    })
                    .transpose()?;
                return Ok((current, depth));
            }
            Err(error) => return Err(parent_resolution_error(&error)),
        }
    }

    let current = current
        .as_ref()
        .map(|directory| {
            fs.reopen_dir(directory)
                .map_err(|error| TxnError::from_io_error(Operation::PrepareParent, &error))
        })
        .transpose()?;
    Ok((current, components.len()))
}

#[allow(clippy::too_many_arguments)]
fn prepare_components<F: FsOps>(
    fs: &F,
    root: &F::Dir,
    components: &[String],
    first_missing_observed: bool,
    policy: ParentDirectoryPolicy,
    existing_parent_links: ExistingParentLinkPolicy,
    synchronization: SyncPolicy,
    sync_level: SyncLevel,
    preflight_existing_namespace: bool,
) -> Result<(Vec<F::Dir>, SyncLevel), TxnError> {
    let mut directories = Vec::with_capacity(components.len());
    let mut first_unstable_parent = None;
    let mut selected = sync_level;

    for (index, component) in components.iter().enumerate() {
        let parent = directories.last().unwrap_or(root);
        let follow_links = existing_parent_links == ExistingParentLinkPolicy::FollowAndPin;
        let existing = if first_missing_observed && index == 0 {
            debug_assert!(matches!(
                policy,
                ParentDirectoryPolicy::CreateMissing { .. }
            ));
            None
        } else {
            match fs.open_dir_at(parent, component, follow_links) {
                Ok(child) => Some(child),
                Err(error) if error.kind() == io::ErrorKind::NotFound => {
                    if policy == ParentDirectoryPolicy::RequireExisting {
                        return Err(parent_resolution_error(&error));
                    }
                    None
                }
                Err(error) => return Err(parent_resolution_error(&error)),
            }
        };
        let child = if let Some(child) = existing {
            child
        } else {
            let ParentDirectoryPolicy::CreateMissing { permissions } = policy else {
                return Err(TxnError::bridge_internal());
            };
            if preflight_existing_namespace && selected == SyncLevel::FileAndNamespaceSynchronized {
                let outcome = fs
                    .flush_directory(parent)
                    .map_err(|error| TxnError::from_io_error(Operation::FlushParent, &error))?;
                if outcome != FlushOutcome::Full {
                    selected = synchronization
                        .negotiate_capability(selected, SyncLevel::FileSynchronized)
                        .map_err(parent_sync_policy_error)?;
                }
            }
            let parent_index = directories.len();
            first_unstable_parent.get_or_insert(parent_index);
            match fs.create_and_open_dir_at(parent, component, permissions) {
                Ok(child) => child,
                Err(error) if error.kind() == io::ErrorKind::AlreadyExists => fs
                    .open_dir_at(parent, component, false)
                    .map_err(|error| parent_resolution_error(&error))?,
                Err(error) => {
                    return Err(TxnError::from_io_error(Operation::PrepareParent, &error));
                }
            }
        };
        directories.push(child);
    }

    if selected == SyncLevel::FileAndNamespaceSynchronized {
        if let Some(first_unstable_parent) = first_unstable_parent {
            for index in (first_unstable_parent..=directories.len()).rev() {
                let directory = if index == 0 {
                    root
                } else {
                    directories
                        .get(index - 1)
                        .ok_or_else(TxnError::bridge_internal)?
                };
                let outcome = fs
                    .flush_directory(directory)
                    .map_err(|error| TxnError::from_io_error(Operation::FlushParent, &error))?;
                if outcome != FlushOutcome::Full {
                    selected = synchronization
                        .negotiate_capability(selected, SyncLevel::FileSynchronized)
                        .map_err(parent_sync_policy_error)?;
                    break;
                }
            }
        }

        // Existing parents have no creation barrier to reveal namespace-sync
        // support. Probe the retained destination-directory capability before
        // the staged file exists; a later post-publication failure never
        // negotiates.
        if preflight_existing_namespace && selected == SyncLevel::FileAndNamespaceSynchronized {
            let directory = directories.last().unwrap_or(root);
            let outcome = fs
                .flush_directory(directory)
                .map_err(|error| TxnError::from_io_error(Operation::FlushParent, &error))?;
            if outcome != FlushOutcome::Full {
                selected = synchronization
                    .negotiate_capability(selected, SyncLevel::FileSynchronized)
                    .map_err(parent_sync_policy_error)?;
            }
        }
    }

    Ok((directories, selected))
}

fn parent_resolution_error(error: &io::Error) -> TxnError {
    // EMLINK joins the set because FreeBSD documents it for the case Linux and
    // Darwin report as ELOOP: "[EMLINK] O_NOFOLLOW was specified and the target
    // is a symbolic link." Without it, a rejected linked parent surfaces as an
    // unclassified OS error on the BSD targets these `cfg(not(...))` branches
    // compile for, instead of the InvalidInput the policy promises.
    #[cfg(unix)]
    if matches!(
        error.raw_os_error(),
        Some(code) if code == libc::ELOOP
            || code == libc::EXDEV
            || code == libc::ENOTDIR
            || code == libc::EMLINK
    ) {
        return TxnError::new(
            Operation::PrepareParent,
            FileSystemFailure::semantic(FailureKind::InvalidInput),
        );
    }
    TxnError::from_io_error(Operation::PrepareParent, error)
}

fn parent_sync_policy_error(error: SyncPolicyError) -> TxnError {
    debug_assert_eq!(error, SyncPolicyError::Unavailable);
    TxnError::new(
        Operation::FlushParent,
        FileSystemFailure::semantic(FailureKind::DurabilityUnavailable),
    )
}

#[cfg(test)]
mod tests {
    #[cfg(unix)]
    use std::path::PathBuf;

    use super::*;

    #[test]
    fn splits_an_absolute_host_path() {
        let (root, names) = split_absolute_path(Path::new(test_absolute_path!("/vault/nested")))
            .expect("path must parse");
        assert_eq!(root, Path::new(test_absolute_path!("/")));
        assert_eq!(names, ["vault", "nested"]);
    }

    #[test]
    fn rejects_relative_and_parent_components() {
        assert!(split_absolute_path(Path::new("vault")).is_err());
        assert!(split_absolute_path(Path::new(test_absolute_path!("/vault/../other"))).is_err());
    }

    #[cfg(unix)]
    fn test_directory(label: &str) -> PathBuf {
        let mut nonce = [0_u8; 8];
        getrandom::fill(&mut nonce).expect("test nonce generation must succeed");
        let nonce: String = nonce.iter().map(|byte| format!("{byte:02x}")).collect();
        let directory = std::env::temp_dir().join(format!("keyguard-parent-{label}-{nonce}"));
        std::fs::create_dir(&directory).expect("test directory must be created");
        directory
    }

    #[cfg(unix)]
    #[test]
    fn absolute_follow_and_pin_normalizes_full_path_resolution_errors() {
        use crate::fsops::RealFs;

        let base = test_directory("full-path-error");
        let file = base.join("file");
        let selected = file.join("selected");
        std::fs::write(&file, []).expect("blocking file must be created");

        let require_existing = prepare_parent(
            &RealFs,
            &selected,
            ParentDirectoryPolicy::RequireExisting,
            ExistingParentLinkPolicy::FollowAndPin,
            SyncPolicy::Required(SyncLevel::ProcessAtomic),
            SyncLevel::ProcessAtomic,
            false,
        )
        .err()
        .expect("a non-directory parent must fail");
        let create_missing = prepare_parent(
            &RealFs,
            &selected,
            ParentDirectoryPolicy::CreateMissing {
                permissions: crate::txn::DirectoryPermissions::OwnerOnly,
            },
            ExistingParentLinkPolicy::FollowAndPin,
            SyncPolicy::Required(SyncLevel::ProcessAtomic),
            SyncLevel::ProcessAtomic,
            false,
        )
        .err()
        .expect("creation below a non-directory parent must fail");

        std::fs::remove_dir_all(&base).expect("test directory must be removed");
        assert_eq!(require_existing.failure().kind(), FailureKind::InvalidInput);
        assert_eq!(create_missing.failure().kind(), FailureKind::InvalidInput);
    }

    #[cfg(any(
        target_os = "aix",
        target_os = "android",
        target_os = "freebsd",
        target_os = "illumos",
        target_os = "linux",
        target_os = "netbsd",
        target_os = "solaris",
        target_vendor = "apple",
    ))]
    #[cfg(unix)]
    #[test]
    fn absolute_follow_and_pin_uses_search_only_ancestors() {
        use std::os::unix::fs::PermissionsExt;

        use crate::fsops::RealFs;

        let base = test_directory("follow-search-only");
        let ancestor = base.join("search-only");
        let selected = ancestor.join("selected");
        std::fs::create_dir_all(&selected).expect("selected directory must be created");
        std::fs::set_permissions(&ancestor, std::fs::Permissions::from_mode(0o111))
            .expect("ancestor must become search-only");

        let prepared = prepare_parent(
            &RealFs,
            &selected,
            ParentDirectoryPolicy::RequireExisting,
            ExistingParentLinkPolicy::FollowAndPin,
            SyncPolicy::Required(SyncLevel::ProcessAtomic),
            SyncLevel::ProcessAtomic,
            false,
        );

        std::fs::set_permissions(&ancestor, std::fs::Permissions::from_mode(0o700))
            .expect("ancestor permissions must be restored");
        std::fs::remove_dir_all(&base).expect("test directory must be removed");
        prepared.expect("the selected parent must open through a search-only ancestor");
    }

    #[cfg(any(
        target_os = "aix",
        target_os = "android",
        target_os = "freebsd",
        target_os = "illumos",
        target_os = "linux",
        target_os = "netbsd",
        target_os = "solaris",
        target_vendor = "apple",
    ))]
    #[cfg(unix)]
    #[test]
    fn absolute_create_missing_pins_the_deepest_existing_parent() {
        use std::os::unix::fs::PermissionsExt;

        use crate::fsops::RealFs;

        let base = test_directory("create-search-only");
        let ancestor = base.join("search-only");
        let existing = ancestor.join("existing");
        let selected = existing.join("missing").join("nested");
        std::fs::create_dir_all(&existing).expect("existing parent must be created");
        std::fs::set_permissions(&ancestor, std::fs::Permissions::from_mode(0o111))
            .expect("ancestor must become search-only");

        let prepared = prepare_parent(
            &RealFs,
            &selected,
            ParentDirectoryPolicy::CreateMissing {
                permissions: crate::txn::DirectoryPermissions::OwnerOnly,
            },
            ExistingParentLinkPolicy::FollowAndPin,
            SyncPolicy::Required(SyncLevel::ProcessAtomic),
            SyncLevel::ProcessAtomic,
            false,
        );

        std::fs::set_permissions(&ancestor, std::fs::Permissions::from_mode(0o700))
            .expect("ancestor permissions must be restored");
        assert!(selected.is_dir(), "the missing suffix must be created");
        std::fs::remove_dir_all(&base).expect("test directory must be removed");
        prepared.expect("creation must start from the deepest directly-opened parent");
    }

    #[cfg(any(
        target_os = "aix",
        target_os = "android",
        target_os = "freebsd",
        target_os = "illumos",
        target_os = "linux",
        target_os = "netbsd",
        target_os = "solaris",
        target_vendor = "apple",
    ))]
    #[cfg(unix)]
    #[test]
    fn strict_relative_parent_uses_search_only_intermediates() {
        use std::os::unix::fs::PermissionsExt;

        use crate::fsops::RealFs;

        let base = test_directory("strict-search-only");
        let ancestor = base.join("search-only");
        let selected = ancestor.join("selected");
        std::fs::create_dir_all(&selected).expect("selected directory must be created");
        let directory =
            AtomicDirectory::open(&RealFs, &base).expect("trusted root must open directly");
        let destination = RelativeDestination::parse("search-only/selected/vault.bin")
            .expect("relative destination must parse");
        std::fs::set_permissions(&ancestor, std::fs::Permissions::from_mode(0o111))
            .expect("ancestor must become search-only");

        let prepared = prepare_parent_at(
            &RealFs,
            &directory,
            &destination,
            ParentDirectoryPolicy::RequireExisting,
            SyncPolicy::Required(SyncLevel::ProcessAtomic),
            SyncLevel::ProcessAtomic,
            false,
        );

        std::fs::set_permissions(&ancestor, std::fs::Permissions::from_mode(0o700))
            .expect("ancestor permissions must be restored");
        std::fs::remove_dir_all(&base).expect("test directory must be removed");
        prepared.expect("strict resolution must not require reading intermediate directories");
    }

    #[cfg(any(
        target_os = "aix",
        target_os = "android",
        target_os = "freebsd",
        target_os = "illumos",
        target_os = "linux",
        target_os = "netbsd",
        target_os = "solaris",
        target_vendor = "apple",
    ))]
    #[cfg(unix)]
    #[test]
    fn strict_relative_create_missing_pins_the_deepest_existing_parent() {
        use std::os::unix::fs::PermissionsExt;

        use crate::fsops::RealFs;

        let base = test_directory("strict-create-search-only");
        let ancestor = base.join("search-only");
        let existing = ancestor.join("existing");
        let selected = existing.join("missing").join("nested");
        std::fs::create_dir_all(&existing).expect("existing parent must be created");
        let directory =
            AtomicDirectory::open(&RealFs, &base).expect("trusted root must open directly");
        let destination =
            RelativeDestination::parse("search-only/existing/missing/nested/vault.bin")
                .expect("relative destination must parse");
        std::fs::set_permissions(&ancestor, std::fs::Permissions::from_mode(0o111))
            .expect("ancestor must become search-only");

        let prepared = prepare_parent_at(
            &RealFs,
            &directory,
            &destination,
            ParentDirectoryPolicy::CreateMissing {
                permissions: crate::txn::DirectoryPermissions::OwnerOnly,
            },
            SyncPolicy::Required(SyncLevel::ProcessAtomic),
            SyncLevel::ProcessAtomic,
            false,
        );

        std::fs::set_permissions(&ancestor, std::fs::Permissions::from_mode(0o700))
            .expect("ancestor permissions must be restored");
        assert!(selected.is_dir(), "the missing suffix must be created");
        std::fs::remove_dir_all(&base).expect("test directory must be removed");
        prepared.expect("strict creation must start from the deepest pinned parent");
    }
}
