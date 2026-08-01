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
    let root = fs
        .open_root(&root)
        .map_err(|error| TxnError::from_io_error(Operation::PrepareParent, &error))?;
    let (descendants, selected) = prepare_components(
        fs,
        &root,
        &components,
        policy,
        existing_parent_links,
        synchronization,
        sync_level,
        preflight_existing_namespace,
    )?;
    let mut chain = Vec::with_capacity(descendants.len() + 1);
    chain.push(root);
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
    let (descendants, selected) = prepare_components(
        fs,
        root,
        destination.parent_components(),
        policy,
        ExistingParentLinkPolicy::Reject,
        synchronization,
        sync_level,
        preflight_existing_namespace,
    )?;
    Ok(PreparedParent {
        base: PreparedParentBase::Retained(directory.clone()),
        descendants,
        sync_level: selected,
    })
}

#[allow(clippy::too_many_arguments)]
fn prepare_components<F: FsOps>(
    fs: &F,
    root: &F::Dir,
    components: &[String],
    policy: ParentDirectoryPolicy,
    existing_parent_links: ExistingParentLinkPolicy,
    synchronization: SyncPolicy,
    sync_level: SyncLevel,
    preflight_existing_namespace: bool,
) -> Result<(Vec<F::Dir>, SyncLevel), TxnError> {
    let mut directories = Vec::with_capacity(components.len());
    let mut first_unstable_parent = None;
    let mut selected = sync_level;

    for component in components {
        let parent = directories.last().unwrap_or(root);
        let follow_links = existing_parent_links == ExistingParentLinkPolicy::FollowAndPin;
        let child = match fs.open_dir_at(parent, component, follow_links) {
            Ok(child) => child,
            Err(error) if error.kind() == io::ErrorKind::NotFound => {
                let permissions = match policy {
                    ParentDirectoryPolicy::RequireExisting => {
                        return Err(parent_resolution_error(&error));
                    }
                    ParentDirectoryPolicy::CreateMissing { permissions } => permissions,
                };
                if preflight_existing_namespace
                    && selected == SyncLevel::FileAndNamespaceSynchronized
                {
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
            }
            Err(error) => {
                return Err(parent_resolution_error(&error));
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
}
