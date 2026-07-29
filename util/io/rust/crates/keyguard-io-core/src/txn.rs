//! The atomic-write transaction: the one place that sequences the protocol.
//!
//! Every step whose ordering matters for crash safety happens inside
//! [`AtomicWriteTxn`] — capture the replacement policy, create in the
//! destination directory, write, finalize permissions, flush the file, rename,
//! and flush the directory — on every platform. The Kotlin facade only moves
//! bytes and decodes results.

use std::path::Path;

use crate::{
    directory::{AtomicDirectory, RelativeDestination},
    durability::{
        AchievedSyncLevel, SyncLevel, SyncPolicy, SyncPolicyError, platform_max_sync_level,
    },
    error::{FailureKind, FileSystemFailure, Operation, TxnError},
    fsops::{
        AmbiguousPublicationCleanup, FileIdentity, FlushKind, FlushOutcome, FsOps,
        PublicationAttemptError, PublicationUnknownCleanup, StagedCreationFailureKind,
        StagedNameResidual,
    },
    naming::{TemporaryFileRole, is_reserved_temporary_artifact_name},
    parent::{PreparedParent, prepare_parent, prepare_parent_at},
};

/// Rejects a destination inside the reserved temporary namespace.
///
/// The orphan sweeper deletes stale entries whose basename matches the
/// `.kg-tmp-` pattern, and it deliberately treats malformed and unknown future
/// spellings as reserved rather than assuming they are safe. A caller that
/// published a destination with such a name would therefore have it removed by a
/// later maintenance pass — reported as an ordinary reclamation, with no error
/// anywhere. The namespace is the library's to allocate, so publishing into it
/// is rejected before any filesystem access.
fn validate_destination_name(name: &str) -> Result<(), TxnError> {
    if is_reserved_temporary_artifact_name(name) {
        return Err(TxnError::new(
            Operation::Begin,
            FileSystemFailure::semantic(FailureKind::InvalidInput),
        ));
    }
    Ok(())
}

/// Requested destination behavior and the permissions of the published file.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PublishPolicy {
    /// Publish only when the destination does not exist.
    Create {
        /// Security policy used to create the staged file.
        permissions: Permissions,
    },
    /// Atomically replace the destination when it exists.
    Replace {
        /// How the published file's basic access permissions are selected.
        access: ReplacementAccessPolicy,
    },
}

/// Access policy for a replacement publication.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ReplacementAccessPolicy {
    /// Use caller-requested permissions without inspecting the destination.
    UseRequestedPermissions {
        /// Security policy used to create and publish the staged file.
        permissions: Permissions,
    },
    /// Preserve the ordinary access permissions of an existing regular
    /// destination.
    ///
    /// On POSIX this is exactly the `0o777` read, write, and execute bits. On
    /// Windows this is the DACL and its protected/unprotected inheritance
    /// state. Ownership, special POSIX mode bits, POSIX ACLs, SACLs,
    /// timestamps, extended attributes, capabilities, and security labels are
    /// deliberately outside this policy. Platforms that cannot implement
    /// their corresponding basic permission model fail before publication
    /// instead of silently using weaker behavior. The permission snapshot is
    /// captured from the regular destination observed when the transaction
    /// opens; a later concurrent replacement does not update it. Symbolic
    /// links, Windows reparse points, and non-regular destinations are rejected
    /// instead of followed.
    PreserveExistingBasicPermissions {
        /// Security policy used when the destination does not exist.
        if_destination_missing: Permissions,
    },
}

/// Security policy applied when the staged file is created.
#[repr(i32)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Permissions {
    /// Grant file access only to the process user.
    OwnerOnly = 0,
    /// Use the process-default access policy.
    ProcessDefault = 1,
}

impl TryFrom<i32> for Permissions {
    type Error = ();

    fn try_from(value: i32) -> Result<Self, Self::Error> {
        match value {
            0 => Ok(Self::OwnerOnly),
            1 => Ok(Self::ProcessDefault),
            _ => Err(()),
        }
    }
}

/// Access policy for parent directories created by an atomic write.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum DirectoryPermissions {
    /// Restrict a new directory to the current process owner.
    OwnerOnly,
    /// Apply the process and platform defaults.
    ProcessDefault,
}

/// Policy for resolving the destination's parent directory.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ParentDirectoryPolicy {
    /// Every parent component must already exist. Existing symbolic links are
    /// resolved before the final directory is used for the transaction.
    RequireExisting,
    /// Create missing components with the selected access policy. Existing
    /// symbolic links are resolved, but a component created by this operation
    /// is reopened without following links. Components observed as existing
    /// are assumed to have been durably established by their creator. A
    /// durable transaction fails before staging when the platform cannot
    /// persist newly created directory entries.
    CreateMissing {
        /// Access policy for directories created by this operation.
        permissions: DirectoryPermissions,
    },
}

impl TryFrom<i32> for ParentDirectoryPolicy {
    type Error = ();

    fn try_from(value: i32) -> Result<Self, Self::Error> {
        match value {
            0 => Ok(Self::RequireExisting),
            1 => Ok(Self::CreateMissing {
                permissions: DirectoryPermissions::ProcessDefault,
            }),
            2 => Ok(Self::CreateMissing {
                permissions: DirectoryPermissions::OwnerOnly,
            }),
            _ => Err(()),
        }
    }
}

/// Policy for existing symbolic links or Windows name-surrogate reparse points
/// in the destination's parent path.
#[repr(i32)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ExistingParentLinkPolicy {
    /// Reject every linked existing parent component.
    Reject = 0,
    /// Resolve each existing linked component once and retain the resulting
    /// directory capability for the rest of the transaction.
    FollowAndPin = 1,
}

impl TryFrom<i32> for ExistingParentLinkPolicy {
    type Error = ();

    fn try_from(value: i32) -> Result<Self, Self::Error> {
        match value {
            0 => Ok(Self::Reject),
            1 => Ok(Self::FollowAndPin),
            _ => Err(()),
        }
    }
}

/// ABI v1 caller-selected transaction parameters.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct AtomicWriteOptions {
    /// Destination behavior and published-file access policy.
    pub publication: PublishPolicy,
    /// Whether and how missing destination-parent components are created.
    pub parent_directory: ParentDirectoryPolicy,
    /// Handling of linked existing parent components.
    pub existing_parent_links: ExistingParentLinkPolicy,
    /// Required or preferred synchronization protocol.
    pub synchronization: SyncPolicy,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct ResolvedTxnOptions {
    publication: PublishPolicy,
    synchronization: SyncPolicy,
    sync_level: SyncLevel,
}

/// Successful commit outcomes.
///
/// Rename failures with an untouched destination are reported as
/// [`TxnError`]; every variant here leaves the filesystem in a state the
/// caller can reason about.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum CommitOutcome {
    /// The destination now contains the staged bytes.
    Published,
    /// Create mode found an existing destination and changed nothing.
    DestinationExists,
    /// The destination contains the staged bytes, but the post-rename
    /// directory flush failed, so durability cannot be trusted.
    PublishedDurabilityUnknown(FileSystemFailure),
    /// A publication request failed after dispatch and identity reconciliation
    /// could not prove whether it took effect.
    PublicationUnknown(FileSystemFailure),
}

/// Namespace primitive used for publication.
#[repr(u8)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PublicationOperation {
    /// Handle- or name-relative atomic rename.
    Rename = 0,
    /// Exclusive hard-link publication fallback.
    HardLink = 1,
}

/// Independent state of staged-artifact cleanup.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum CleanupState {
    /// No recognizable staged artifact remains.
    Complete,
    /// Cleanup is incomplete, optionally because it was deliberately skipped
    /// to avoid deleting a possibly published destination.
    Incomplete(Option<FileSystemFailure>),
}

impl CleanupState {
    /// Returns whether recognizable staged state may remain.
    #[must_use]
    pub const fn is_incomplete(self) -> bool {
        matches!(self, Self::Incomplete(_))
    }

    /// Returns the cleanup failure when one was observed.
    #[must_use]
    pub const fn failure(self) -> Option<FileSystemFailure> {
        match self {
            Self::Complete | Self::Incomplete(None) => None,
            Self::Incomplete(failure) => failure,
        }
    }
}

/// Result of a completed commit.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct CommitSuccess {
    projection: CommitSuccessProjection,
}

/// Variant-specific commit state consumed by the ABI packer.
///
/// This remains crate-private so external callers can observe reports but
/// cannot manufacture impossible outcome/tier/operation combinations.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum CommitSuccessProjection {
    Published {
        achieved: AchievedSyncLevel,
        publication_operation: PublicationOperation,
        cleanup: CleanupState,
    },
    DestinationExists {
        achieved: AchievedSyncLevel,
        cleanup_failure: Option<FileSystemFailure>,
    },
    PublishedDurabilityUnknown {
        primary_failure: FileSystemFailure,
        achieved: AchievedSyncLevel,
        publication_operation: PublicationOperation,
        cleanup: CleanupState,
    },
    PublicationUnknown {
        primary_failure: FileSystemFailure,
        publication_operation: PublicationOperation,
        cleanup: CleanupState,
    },
}

impl CommitSuccess {
    /// What happened at the destination.
    #[must_use]
    pub const fn outcome(self) -> CommitOutcome {
        match self.projection {
            CommitSuccessProjection::Published { .. } => CommitOutcome::Published,
            CommitSuccessProjection::DestinationExists { .. } => CommitOutcome::DestinationExists,
            CommitSuccessProjection::PublishedDurabilityUnknown {
                primary_failure, ..
            } => CommitOutcome::PublishedDurabilityUnknown(primary_failure),
            CommitSuccessProjection::PublicationUnknown {
                primary_failure, ..
            } => CommitOutcome::PublicationUnknown(primary_failure),
        }
    }

    /// Synchronization actually established, or `None` when publication
    /// itself remains unknown.
    #[must_use]
    pub const fn achieved(self) -> Option<AchievedSyncLevel> {
        match self.projection {
            CommitSuccessProjection::Published { achieved, .. }
            | CommitSuccessProjection::DestinationExists { achieved, .. }
            | CommitSuccessProjection::PublishedDurabilityUnknown { achieved, .. } => {
                Some(achieved)
            }
            CommitSuccessProjection::PublicationUnknown { .. } => None,
        }
    }

    /// Namespace primitive involved in publication, when one was attempted.
    #[must_use]
    pub const fn publication_operation(self) -> Option<PublicationOperation> {
        match self.projection {
            CommitSuccessProjection::Published {
                publication_operation,
                ..
            }
            | CommitSuccessProjection::PublishedDurabilityUnknown {
                publication_operation,
                ..
            }
            | CommitSuccessProjection::PublicationUnknown {
                publication_operation,
                ..
            } => Some(publication_operation),
            CommitSuccessProjection::DestinationExists { .. } => None,
        }
    }

    /// Cleanup state retained independently from publication and durability.
    #[must_use]
    pub const fn cleanup(self) -> CleanupState {
        match self.projection {
            CommitSuccessProjection::Published { cleanup, .. }
            | CommitSuccessProjection::PublishedDurabilityUnknown { cleanup, .. }
            | CommitSuccessProjection::PublicationUnknown { cleanup, .. } => cleanup,
            CommitSuccessProjection::DestinationExists {
                cleanup_failure: None,
                ..
            } => CleanupState::Complete,
            CommitSuccessProjection::DestinationExists {
                cleanup_failure: Some(failure),
                ..
            } => CleanupState::Incomplete(Some(failure)),
        }
    }

    pub(crate) const fn from_projection(projection: CommitSuccessProjection) -> Self {
        Self { projection }
    }

    pub(crate) const fn projection(self) -> CommitSuccessProjection {
        self.projection
    }
}

/// A staged atomic write anchored to its destination directory.
pub struct AtomicWriteTxn<F: FsOps> {
    fs: F,
    parent: PreparedParent<F::Dir>,
    file: Option<F::File>,
    write_state: WriteState,
    temp_name: String,
    dest_name: String,
    options: ResolvedTxnOptions,
    metadata: Option<F::Metadata>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum WriteState {
    Writable,
    /// The filesystem call may already have accepted an arbitrary prefix.
    ///
    /// This remains observable if an unwind is contained by an outer ABI
    /// boundary before [`AtomicWriteTxn::write`] can record an ordinary error.
    Writing,
    Poisoned(TxnError),
}

impl<F: FsOps> AtomicWriteTxn<F> {
    /// Opens a transaction targeting `destination`.
    ///
    /// The destination directory is resolved according to the configured
    /// parent policy, opened as the transaction anchor, and the staged file is
    /// created exclusively inside it under a fresh temporary name.
    ///
    /// # Errors
    ///
    /// Returns a [`TxnError`] tagged with the failing step. The destination is
    /// unchanged and staged files are removed or remain sweepable. Under
    /// [`ParentDirectoryPolicy::CreateMissing`], empty directories created
    /// before a failure are deliberately retained.
    /// Synchronization capability negotiation completes before parent
    /// traversal or staged-file creation. [`SyncPolicy::Prefer`] may select a
    /// weaker level only because the advertised platform maximum is weaker;
    /// runtime I/O failures never trigger a downgrade.
    ///
    /// # Errors
    ///
    /// Returns a [`TxnError`] without filesystem access when the policy is
    /// invalid or its required/minimum level exceeds the platform maximum.
    /// Later failures retain the same publication-state contract as
    /// [`AtomicWriteTxn::begin`].
    pub fn begin(fs: F, destination: &Path, options: AtomicWriteOptions) -> Result<Self, TxnError> {
        Self::begin_with_platform_maximum_internal(
            fs,
            destination,
            options,
            platform_max_sync_level(),
            true,
        )
    }

    /// Opens a transaction at a strict relative path beneath a retained
    /// caller-selected directory.
    ///
    /// The directory's absolute path was resolved exactly once when the
    /// [`AtomicDirectory`] was opened. Every existing parent beneath it is
    /// opened without following links or crossing mounts, regardless of
    /// later changes to the lexical root path.
    ///
    /// # Errors
    ///
    /// Returns an invalid-argument error unless
    /// [`ExistingParentLinkPolicy::Reject`] is selected. Filesystem and
    /// synchronization failures use the same contract as
    /// [`AtomicWriteTxn::begin`].
    pub fn begin_at_directory(
        fs: F,
        directory: &AtomicDirectory<F::Dir>,
        destination: RelativeDestination,
        options: AtomicWriteOptions,
    ) -> Result<Self, TxnError> {
        if options.existing_parent_links != ExistingParentLinkPolicy::Reject {
            return Err(TxnError::new(
                Operation::Begin,
                FileSystemFailure::bridge_invalid_argument(),
            ));
        }
        validate_destination_name(destination.file_name())?;
        let sync_level = options
            .synchronization
            .negotiate(platform_max_sync_level())
            .map_err(sync_policy_error)?;
        let prepared_parent = prepare_parent_at(
            &fs,
            directory,
            &destination,
            options.parent_directory,
            options.synchronization,
            sync_level,
            true,
        )?;
        Self::begin_in_prepared_parent(
            fs,
            prepared_parent,
            destination.file_name().to_owned(),
            options,
        )
    }

    #[cfg(test)]
    fn begin_with_platform_maximum(
        fs: F,
        destination: &Path,
        options: AtomicWriteOptions,
        platform_maximum: SyncLevel,
    ) -> Result<Self, TxnError> {
        Self::begin_with_platform_maximum_internal(fs, destination, options, platform_maximum, true)
    }

    fn begin_with_platform_maximum_internal(
        fs: F,
        destination: &Path,
        options: AtomicWriteOptions,
        platform_maximum: SyncLevel,
        preflight_existing_namespace: bool,
    ) -> Result<Self, TxnError> {
        let sync_level = options
            .synchronization
            .negotiate(platform_maximum)
            .map_err(sync_policy_error)?;
        let dest_name = destination
            .file_name()
            .and_then(|name| name.to_str())
            .filter(|name| !name.is_empty())
            .ok_or_else(|| {
                TxnError::new(
                    Operation::Begin,
                    FileSystemFailure::bridge_invalid_argument(),
                )
            })?
            .to_owned();
        validate_destination_name(&dest_name)?;
        let parent = match destination.parent() {
            Some(parent) if !parent.as_os_str().is_empty() => parent.to_owned(),
            _ => Path::new(".").to_owned(),
        };

        let prepared_parent = prepare_parent(
            &fs,
            &parent,
            options.parent_directory,
            options.existing_parent_links,
            options.synchronization,
            sync_level,
            preflight_existing_namespace,
        )?;
        Self::begin_in_prepared_parent(fs, prepared_parent, dest_name, options)
    }

    fn begin_in_prepared_parent(
        fs: F,
        prepared_parent: PreparedParent<F::Dir>,
        dest_name: String,
        options: AtomicWriteOptions,
    ) -> Result<Self, TxnError> {
        let dir = prepared_parent.dir()?;
        // Capture an existing destination's permissions before creating the
        // staged sibling. Missing destinations use the caller's explicit
        // fallback. An existing preserved destination is always staged
        // owner-only and widened, if necessary, only after all writes finish.
        let (staged_permissions, metadata) = match options.publication {
            PublishPolicy::Create { permissions }
            | PublishPolicy::Replace {
                access: ReplacementAccessPolicy::UseRequestedPermissions { permissions },
            } => (permissions, None),
            PublishPolicy::Replace {
                access:
                    ReplacementAccessPolicy::PreserveExistingBasicPermissions {
                        if_destination_missing,
                    },
            } => match fs.read_replace_metadata(dir, &dest_name) {
                Ok(Some(metadata)) => (Permissions::OwnerOnly, Some(metadata)),
                Ok(None) => (if_destination_missing, None),
                Err(error) => {
                    return Err(TxnError::from_io_error(Operation::Metadata, &error));
                }
            },
        };

        let owner_only = staged_permissions == Permissions::OwnerOnly;
        let staged = fs
            .create_staged_at(dir, TemporaryFileRole::New, owner_only)
            .map_err(|error| {
                let kind = match error.kind() {
                    StagedCreationFailureKind::Inferred => None,
                    StagedCreationFailureKind::ResourceBusy => Some(FailureKind::ResourceBusy),
                    StagedCreationFailureKind::Unsupported => Some(FailureKind::Unsupported),
                };
                let primary = TxnError::new(
                    Operation::CreateStaged,
                    FileSystemFailure::from_io_error_with_kind(error.error(), kind),
                );
                if error.cleanup_incomplete() {
                    primary.with_cleanup_incomplete()
                } else {
                    primary
                }
            })?;
        let temp_name = staged.name;
        let file = staged.file;

        let sync_level = prepared_parent.sync_level;
        let txn = Self {
            fs,
            parent: prepared_parent,
            file: Some(file),
            write_state: WriteState::Writable,
            temp_name,
            dest_name,
            options: ResolvedTxnOptions {
                publication: options.publication,
                synchronization: options.synchronization,
                sync_level,
            },
            metadata,
        };
        Ok(txn)
    }

    /// Appends bytes to the staged file.
    ///
    /// # Errors
    ///
    /// Returns a [`TxnError`] and permanently prevents publication. The
    /// transaction stays open only so the caller can abort it (which
    /// best-effort removes the staged file). Later writes return the first
    /// failure without replaying I/O.
    pub fn write(&mut self, buffer: &[u8]) -> Result<(), TxnError> {
        if let Some(failure) = self.terminal_write_failure() {
            return Err(failure);
        }
        let file = self.file.as_mut().ok_or_else(TxnError::bridge_internal)?;
        self.write_state = WriteState::Writing;
        match self.fs.write_all(file, buffer) {
            Ok(()) => {
                self.write_state = WriteState::Writable;
                Ok(())
            }
            Err(error) => {
                let failure = TxnError::from_io_error(Operation::Write, &error);
                self.write_state = WriteState::Poisoned(failure);
                Err(failure)
            }
        }
    }

    /// Publishes the staged bytes at the destination name.
    ///
    /// # Errors
    ///
    /// A [`TxnError`] means publication was not dispatched or was definitely
    /// unchanged. Ambiguity after a publication syscall is instead returned
    /// as [`CommitOutcome::PublicationUnknown`]. A flush failure never retries
    /// the same descriptor: after a reported fsync error the kernel may already
    /// have dropped the dirty pages, so the only safe recovery is rewriting
    /// from the source of truth.
    pub fn commit(mut self) -> Result<CommitSuccess, TxnError> {
        if let Some(failure) = self.terminal_write_failure() {
            return Err(self.failure_after_cleanup(failure));
        }

        // Step 1: finalize and verify permissions while the staged file is
        // still private. The following file flush therefore covers both bytes
        // and every permission mutation performed by this transaction.
        if let Some(metadata) = self.metadata.take() {
            let file = self.file.as_mut().ok_or_else(TxnError::bridge_internal)?;
            if let Err(error) = self.fs.apply_replace_metadata(file, &metadata) {
                let failure = TxnError::from_io_error(Operation::Metadata, &error);
                return Err(self.failure_after_cleanup(failure));
            }
            if let Err(error) = self.fs.verify_replace_metadata(file, &metadata) {
                let failure = TxnError::from_io_error(Operation::Metadata, &error);
                return Err(self.failure_after_cleanup(failure));
            }
        }

        // Step 2: flush the staged bytes and finalized permissions ahead of
        // the rename.
        if self.options.sync_level >= SyncLevel::FileSynchronized {
            let kind = if self.options.sync_level == SyncLevel::FileAndNamespaceSynchronized {
                FlushKind::Durable
            } else {
                FlushKind::Ordered
            };
            let file = self.file.as_mut().ok_or_else(TxnError::bridge_internal)?;
            match self.fs.flush_file(file, kind) {
                Ok(FlushOutcome::Full) => {}
                Ok(outcome @ (FlushOutcome::Degraded | FlushOutcome::Unsupported)) => {
                    let capability_maximum = match outcome {
                        FlushOutcome::Degraded => SyncLevel::FileSynchronized,
                        FlushOutcome::Unsupported => SyncLevel::ProcessAtomic,
                        FlushOutcome::Full => unreachable!("full outcome handled above"),
                    };
                    match self
                        .options
                        .synchronization
                        .negotiate_capability(self.options.sync_level, capability_maximum)
                    {
                        Ok(selected) => self.options.sync_level = selected,
                        Err(error) => {
                            let failure =
                                sync_policy_error_for_operation(error, Operation::FlushFile);
                            return Err(self.failure_after_cleanup(failure));
                        }
                    }
                }
                Err(error) => {
                    let failure = TxnError::from_io_error(Operation::FlushFile, &error);
                    return Err(self.failure_after_cleanup(failure));
                }
            }
        }

        let achieved = AchievedSyncLevel::from_selected(self.options.sync_level);
        let staged_identity = {
            let file = self.file.as_ref().ok_or_else(TxnError::bridge_internal)?;
            match self.fs.staged_file_identity(file) {
                Ok(identity) => identity,
                Err(error) => {
                    let failure = TxnError::from_io_error(Operation::Metadata, &error);
                    return Err(self.failure_after_cleanup(failure));
                }
            }
        };

        // Step 3: publish. Create-mode destination observation is repeated
        // immediately before every mutation attempt, including retries.
        let no_replace = matches!(self.options.publication, PublishPolicy::Create { .. });
        let mut attempt = 0;
        loop {
            if no_replace {
                match self.destination_occupied_before_publication(Operation::Rename) {
                    Ok(true) => return Ok(self.destination_exists(achieved)),
                    Ok(false) => {}
                    Err(failure) => return Err(self.failure_after_cleanup(failure)),
                }
            }
            let dir = self.parent.dir()?;
            let file = self.file.as_mut().ok_or_else(TxnError::bridge_internal)?;
            match self
                .fs
                .rename(dir, &self.temp_name, file, &self.dest_name, no_replace)
            {
                Ok(()) => {
                    return Ok(self.finish_published(
                        achieved,
                        PublicationOperation::Rename,
                        Some(StagedNameResidual::AbsentAfterRename),
                        CleanupState::Complete,
                    ));
                }
                Err(PublicationAttemptError::MayHaveMutated(error)) => {
                    return Ok(self.reconcile_ambiguous_publication(
                        achieved,
                        staged_identity,
                        PublicationOperation::Rename,
                        FileSystemFailure::from_io_error(&error),
                    ));
                }
                Err(PublicationAttemptError::DefinitelyUnchanged(error)) => {
                    if self.fs.is_rename_unsupported(&error) && no_replace {
                        return self.publish_via_hard_link(achieved, staged_identity);
                    }
                    if self.fs.is_rename_retryable(&error) {
                        let delays = self.fs.rename_retry_delays();
                        if attempt < delays.len() {
                            let delay = delays[attempt];
                            attempt += 1;
                            self.fs.sleep(delay);
                            continue;
                        }
                    }
                    let kind = self
                        .fs
                        .is_rename_unsupported(&error)
                        .then_some(FailureKind::Unsupported);
                    let failure = TxnError::new(
                        Operation::Rename,
                        FileSystemFailure::from_io_error_with_kind(&error, kind),
                    );
                    return Err(self.failure_after_cleanup(failure));
                }
            }
        }
    }

    /// Abandons the transaction and removes the staged temporary.
    ///
    /// # Errors
    ///
    /// Returns a cleanup-tagged [`TxnError`] when the destination remains
    /// untouched but the staged artifact could not be removed.
    pub fn abort(mut self) -> Result<(), TxnError> {
        self.cleanup_staged_result()
    }

    fn publish_via_hard_link(
        mut self,
        achieved: AchievedSyncLevel,
        staged_identity: FileIdentity,
    ) -> Result<CommitSuccess, TxnError> {
        match self.destination_occupied_before_publication(Operation::HardLink) {
            Ok(true) => return Ok(self.destination_exists(achieved)),
            Ok(false) => {}
            Err(failure) => return Err(self.failure_after_cleanup(failure)),
        }
        let dir = self.parent.dir()?;
        let file = self.file.as_ref().ok_or_else(TxnError::bridge_internal)?;
        match self
            .fs
            .hard_link(dir, &self.temp_name, file, &self.dest_name)
        {
            Ok(()) => Ok(self.finish_published(
                achieved,
                PublicationOperation::HardLink,
                Some(StagedNameResidual::PresentAfterHardLink),
                CleanupState::Complete,
            )),
            Err(PublicationAttemptError::MayHaveMutated(error)) => Ok(self
                .reconcile_ambiguous_publication(
                    achieved,
                    staged_identity,
                    PublicationOperation::HardLink,
                    FileSystemFailure::from_io_error(&error),
                )),
            Err(PublicationAttemptError::DefinitelyUnchanged(error)) => {
                let kind = self
                    .fs
                    .is_hard_link_unsupported(&error)
                    .then_some(FailureKind::Unsupported);
                let failure = TxnError::new(
                    Operation::HardLink,
                    FileSystemFailure::from_io_error_with_kind(&error, kind),
                );
                Err(self.failure_after_cleanup(failure))
            }
        }
    }

    fn destination_occupied_before_publication(
        &self,
        operation: Operation,
    ) -> Result<bool, TxnError> {
        let dir = self.parent.dir()?;
        self.fs
            .observe_file_identity_at(dir, &self.dest_name)
            .map(|identity| identity.is_some())
            .map_err(|error| TxnError::from_io_error(operation, &error))
    }

    fn destination_exists(mut self, achieved: AchievedSyncLevel) -> CommitSuccess {
        let cleanup_failure = match self.cleanup_staged_state() {
            CleanupState::Complete => None,
            CleanupState::Incomplete(Some(failure)) => Some(failure),
            CleanupState::Incomplete(None) => {
                unreachable!("ordinary staged cleanup always reports a concrete failure")
            }
        };
        CommitSuccess::from_projection(CommitSuccessProjection::DestinationExists {
            achieved,
            cleanup_failure,
        })
    }

    fn reconcile_ambiguous_publication(
        self,
        achieved: AchievedSyncLevel,
        staged_identity: FileIdentity,
        operation: PublicationOperation,
        primary_failure: FileSystemFailure,
    ) -> CommitSuccess {
        let retained_matches = self
            .file
            .as_ref()
            .and_then(|file| self.fs.staged_file_identity(file).ok())
            == Some(staged_identity);
        let destination_matches = self.parent.dir().ok().and_then(|dir| {
            self.fs
                .observe_file_identity_at(dir, &self.dest_name)
                .ok()
                .flatten()
        }) == Some(staged_identity);
        if !retained_matches || !destination_matches {
            return self.publication_unknown(operation, primary_failure);
        }

        // A remote rename replay can leave either no temporary name or an
        // exact additional name. Only those two bindings are safe to finalize.
        let (residual, cleanup) = match self.fs.ambiguous_publication_cleanup() {
            AmbiguousPublicationCleanup::CloseOnly => (None, CleanupState::Incomplete(None)),
            AmbiguousPublicationCleanup::ExactStagedName => match self.parent.dir() {
                Ok(dir) => match self.fs.observe_file_identity_at(dir, &self.temp_name) {
                    Ok(None) => (
                        Some(StagedNameResidual::AbsentAfterRename),
                        CleanupState::Complete,
                    ),
                    Ok(Some(identity)) if identity == staged_identity => (
                        Some(StagedNameResidual::PresentAfterHardLink),
                        CleanupState::Complete,
                    ),
                    Ok(Some(_)) => (
                        None,
                        CleanupState::Incomplete(Some(FileSystemFailure::semantic(
                            FailureKind::Other,
                        ))),
                    ),
                    Err(error) => (
                        None,
                        CleanupState::Incomplete(Some(FileSystemFailure::from_io_error(&error))),
                    ),
                },
                Err(error) => (None, CleanupState::Incomplete(Some(error.failure()))),
            },
        };
        self.finish_published(achieved, operation, residual, cleanup)
    }

    fn publication_unknown(
        mut self,
        operation: PublicationOperation,
        primary_failure: FileSystemFailure,
    ) -> CommitSuccess {
        let cleanup = match self.file.take() {
            Some(file) => match self.parent.dir() {
                Ok(dir) => {
                    match self
                        .fs
                        .cleanup_after_publication_unknown(dir, &self.temp_name, file)
                    {
                        PublicationUnknownCleanup::Complete => CleanupState::Complete,
                        PublicationUnknownCleanup::Incomplete(Some(error)) => {
                            CleanupState::Incomplete(Some(FileSystemFailure::from_io_error(&error)))
                        }
                        PublicationUnknownCleanup::Incomplete(None) => {
                            CleanupState::Incomplete(None)
                        }
                    }
                }
                Err(error) => {
                    let parent_failure = error.failure();
                    let close_failure = self
                        .fs
                        .close(file)
                        .err()
                        .map(|error| FileSystemFailure::from_io_error(&error));
                    CleanupState::Incomplete(close_failure.or(Some(parent_failure)))
                }
            },
            None => {
                CleanupState::Incomplete(Some(FileSystemFailure::semantic(FailureKind::Internal)))
            }
        };
        CommitSuccess::from_projection(CommitSuccessProjection::PublicationUnknown {
            primary_failure,
            publication_operation: operation,
            cleanup,
        })
    }

    fn finish_published(
        mut self,
        mut achieved: AchievedSyncLevel,
        operation: PublicationOperation,
        residual: Option<StagedNameResidual>,
        mut cleanup: CleanupState,
    ) -> CommitSuccess {
        let mut outcome = CommitOutcome::Published;
        let dir = match self.parent.dir() {
            Ok(dir) => Some(dir),
            Err(error) => {
                Self::record_cleanup_failure(&mut cleanup, error.failure());
                if self.options.sync_level == SyncLevel::FileAndNamespaceSynchronized {
                    outcome = CommitOutcome::PublishedDurabilityUnknown(error.failure());
                    achieved = achieved.min(AchievedSyncLevel::FileSynchronized);
                }
                None
            }
        };

        if let (Some(dir), Some(residual), Some(file)) = (dir, residual, self.file.as_mut())
            && let Err(error) =
                self.fs
                    .finalize_staged_after_publication(dir, &self.temp_name, file, residual)
        {
            Self::record_cleanup_failure(&mut cleanup, FileSystemFailure::from_io_error(&error));
        }

        if self.options.sync_level == SyncLevel::FileAndNamespaceSynchronized
            && let (Some(dir), Some(file)) = (dir, self.file.as_mut())
        {
            match self.fs.flush_publication(dir, file) {
                Ok(FlushOutcome::Full) => {}
                Ok(FlushOutcome::Degraded | FlushOutcome::Unsupported) => {
                    outcome = CommitOutcome::PublishedDurabilityUnknown(
                        FileSystemFailure::semantic(FailureKind::DurabilityUnavailable),
                    );
                    achieved = achieved.min(AchievedSyncLevel::FileSynchronized);
                }
                Err(error) => {
                    outcome = CommitOutcome::PublishedDurabilityUnknown(
                        FileSystemFailure::from_io_error(&error),
                    );
                    achieved = achieved.min(AchievedSyncLevel::FileSynchronized);
                }
            }
        }

        if let Some(close_failure) = self.close_staged_failure() {
            Self::record_cleanup_failure(&mut cleanup, close_failure);
        }
        match outcome {
            CommitOutcome::Published => {
                CommitSuccess::from_projection(CommitSuccessProjection::Published {
                    achieved,
                    publication_operation: operation,
                    cleanup,
                })
            }
            CommitOutcome::PublishedDurabilityUnknown(primary_failure) => {
                CommitSuccess::from_projection(
                    CommitSuccessProjection::PublishedDurabilityUnknown {
                        primary_failure,
                        achieved,
                        publication_operation: operation,
                        cleanup,
                    },
                )
            }
            CommitOutcome::DestinationExists | CommitOutcome::PublicationUnknown(_) => {
                unreachable!("finish_published only constructs published outcomes")
            }
        }
    }

    fn record_cleanup_failure(cleanup: &mut CleanupState, failure: FileSystemFailure) {
        if matches!(
            cleanup,
            CleanupState::Complete | CleanupState::Incomplete(None)
        ) {
            *cleanup = CleanupState::Incomplete(Some(failure));
        }
    }

    fn cleanup_staged(&mut self) {
        let _ = self.cleanup_staged_result();
    }

    fn cleanup_staged_result(&mut self) -> Result<(), TxnError> {
        if let Some(file) = self.file.take() {
            let dir = self
                .parent
                .dir()
                .map_err(TxnError::with_cleanup_incomplete)?;
            self.fs
                .discard_staged(dir, &self.temp_name, file)
                .map_err(|error| {
                    TxnError::from_io_error(Operation::Cleanup, &error).with_cleanup_incomplete()
                })?;
        }
        Ok(())
    }

    fn cleanup_staged_state(&mut self) -> CleanupState {
        match self.cleanup_staged_result() {
            Ok(()) => CleanupState::Complete,
            Err(error) => CleanupState::Incomplete(Some(error.failure())),
        }
    }

    fn failure_after_cleanup(&mut self, primary: TxnError) -> TxnError {
        if self.cleanup_staged_result().is_err() {
            primary.with_cleanup_incomplete()
        } else {
            primary
        }
    }

    fn terminal_write_failure(&mut self) -> Option<TxnError> {
        match self.write_state {
            WriteState::Writable => None,
            WriteState::Writing => {
                let failure = TxnError::bridge_panic();
                self.write_state = WriteState::Poisoned(failure);
                Some(failure)
            }
            WriteState::Poisoned(failure) => Some(failure),
        }
    }

    fn close_staged_failure(&mut self) -> Option<FileSystemFailure> {
        self.file.take().and_then(|file| {
            self.fs
                .close(file)
                .err()
                .map(|error| FileSystemFailure::from_io_error(&error))
        })
    }
}

fn sync_policy_error(error: SyncPolicyError) -> TxnError {
    sync_policy_error_for_operation(error, Operation::Begin)
}

fn sync_policy_error_for_operation(error: SyncPolicyError, operation: Operation) -> TxnError {
    match error {
        SyncPolicyError::InvalidOrder => {
            TxnError::new(operation, FileSystemFailure::bridge_invalid_argument())
        }
        SyncPolicyError::Unavailable => TxnError::new(
            operation,
            FileSystemFailure::semantic(FailureKind::DurabilityUnavailable),
        ),
    }
}

impl<F: FsOps> Drop for AtomicWriteTxn<F> {
    fn drop(&mut self) {
        // Reached only when the transaction was neither committed nor
        // aborted (for example a bridge panic); leave nothing behind.
        self.cleanup_staged();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::simfs::{NamespaceMutation, SimFlushSupport, SimFsBuilder, SimOp};

    fn options(
        existing_parent_links: ExistingParentLinkPolicy,
        synchronization: SyncPolicy,
    ) -> AtomicWriteOptions {
        AtomicWriteOptions {
            publication: PublishPolicy::Create {
                permissions: Permissions::OwnerOnly,
            },
            parent_directory: ParentDirectoryPolicy::RequireExisting,
            existing_parent_links,
            synchronization,
        }
    }

    #[test]
    fn unsupported_required_level_fails_before_filesystem_access() {
        let fs = SimFsBuilder::new().build();
        let error = match AtomicWriteTxn::begin_with_platform_maximum(
            fs.clone(),
            Path::new("/vault.bin"),
            options(
                ExistingParentLinkPolicy::Reject,
                SyncPolicy::Required(SyncLevel::FileAndNamespaceSynchronized),
            ),
            SyncLevel::FileSynchronized,
        ) {
            Ok(_) => panic!("unsupported required synchronization must fail"),
            Err(error) => error,
        };

        assert_eq!(error.operation(), Operation::Begin);
        assert_eq!(error.failure().kind(), FailureKind::DurabilityUnavailable);
        assert!(
            fs.operations().is_empty(),
            "capability preflight must precede root or parent access"
        );
    }

    #[test]
    fn prefer_selects_the_known_platform_maximum_without_runtime_downgrade() {
        let fs = SimFsBuilder::new().build();
        let mut txn = AtomicWriteTxn::begin_with_platform_maximum(
            fs.clone(),
            Path::new("/vault.bin"),
            options(
                ExistingParentLinkPolicy::Reject,
                SyncPolicy::Prefer {
                    preferred: SyncLevel::FileAndNamespaceSynchronized,
                    minimum: SyncLevel::FileSynchronized,
                },
            ),
            SyncLevel::FileSynchronized,
        )
        .expect("known capability downgrade must satisfy the minimum");
        txn.write(b"new").expect("write must succeed");
        let success = txn.commit().expect("commit must succeed");

        assert_eq!(
            success.achieved(),
            Some(AchievedSyncLevel::FileSynchronized)
        );
        assert!(
            fs.operations()
                .iter()
                .all(|operation| operation.op != SimOp::FlushPublication),
            "selected file synchronization must not claim a namespace barrier"
        );
    }

    #[test]
    fn prefer_downgrades_namespace_on_destination_directory_capability() {
        let fs = SimFsBuilder::new()
            .created_parent_flush_support(SimFlushSupport::Unsupported)
            .build();
        let mut txn = AtomicWriteTxn::begin(
            fs.clone(),
            Path::new("/vault.bin"),
            options(
                ExistingParentLinkPolicy::Reject,
                SyncPolicy::Prefer {
                    preferred: SyncLevel::FileAndNamespaceSynchronized,
                    minimum: SyncLevel::FileSynchronized,
                },
            ),
        )
        .expect("known namespace capability shortfall may select file synchronization");
        txn.write(b"new").expect("write must succeed");

        let success = txn
            .commit()
            .expect("selected file synchronization must commit");

        assert_eq!(
            success.achieved(),
            Some(AchievedSyncLevel::FileSynchronized)
        );
        assert!(
            fs.operations()
                .iter()
                .all(|operation| operation.op != SimOp::FlushPublication)
        );
    }

    #[test]
    fn required_namespace_capability_and_ordinary_preflight_errors_fail_before_staging() {
        for fs in [
            SimFsBuilder::new()
                .created_parent_flush_support(SimFlushSupport::Unsupported)
                .build(),
            SimFsBuilder::new()
                .fault(
                    SimOp::FlushDirectory,
                    0,
                    std::io::ErrorKind::PermissionDenied,
                )
                .build(),
        ] {
            let error = match AtomicWriteTxn::begin(
                fs.clone(),
                Path::new("/vault.bin"),
                options(
                    ExistingParentLinkPolicy::Reject,
                    SyncPolicy::Required(SyncLevel::FileAndNamespaceSynchronized),
                ),
            ) {
                Ok(_) => panic!("required namespace preflight must fail"),
                Err(error) => error,
            };

            assert_eq!(error.operation(), Operation::FlushParent);
            assert!(
                fs.operations()
                    .iter()
                    .all(|operation| operation.op != SimOp::CreateFileAt)
            );
        }
    }

    #[test]
    fn required_namespace_capability_fails_before_missing_parent_creation() {
        let fs = SimFsBuilder::new()
            .created_parent_flush_support(SimFlushSupport::Unsupported)
            .build();
        let write_options = AtomicWriteOptions {
            publication: PublishPolicy::Create {
                permissions: Permissions::OwnerOnly,
            },
            parent_directory: ParentDirectoryPolicy::CreateMissing {
                permissions: DirectoryPermissions::OwnerOnly,
            },
            existing_parent_links: ExistingParentLinkPolicy::Reject,
            synchronization: SyncPolicy::Required(SyncLevel::FileAndNamespaceSynchronized),
        };

        let error = match AtomicWriteTxn::begin(
            fs.clone(),
            Path::new("/missing/nested/vault.bin"),
            write_options,
        ) {
            Ok(_) => panic!("known namespace shortfall must fail before parent creation"),
            Err(error) => error,
        };

        assert_eq!(error.operation(), Operation::FlushParent);
        assert!(
            fs.operations()
                .iter()
                .all(|operation| operation.op != SimOp::CreateDirAt)
        );
    }

    #[test]
    fn prefer_downgrades_only_file_flush_capability_before_publication() {
        for (support, minimum, expected) in [
            (
                SimFlushSupport::Degraded,
                SyncLevel::FileSynchronized,
                AchievedSyncLevel::FileSynchronized,
            ),
            (
                SimFlushSupport::Unsupported,
                SyncLevel::ProcessAtomic,
                AchievedSyncLevel::ProcessAtomic,
            ),
        ] {
            let fs = SimFsBuilder::new().file_flush_support(support).build();
            let mut txn = AtomicWriteTxn::begin(
                fs.clone(),
                Path::new("/vault.bin"),
                options(
                    ExistingParentLinkPolicy::Reject,
                    SyncPolicy::Prefer {
                        preferred: SyncLevel::FileAndNamespaceSynchronized,
                        minimum,
                    },
                ),
            )
            .expect("namespace preflight must succeed");
            txn.write(b"new").expect("write must succeed");

            let success = txn
                .commit()
                .expect("allowed file capability shortfall must commit");

            assert_eq!(success.achieved(), Some(expected));
            assert!(
                fs.operations()
                    .iter()
                    .all(|operation| operation.op != SimOp::FlushPublication)
            );
        }
    }

    #[test]
    fn required_file_capability_failure_is_not_downgraded_or_published() {
        let fs = SimFsBuilder::new()
            .file_flush_support(SimFlushSupport::Unsupported)
            .build();
        let mut txn = AtomicWriteTxn::begin(
            fs.clone(),
            Path::new("/vault.bin"),
            options(
                ExistingParentLinkPolicy::Reject,
                SyncPolicy::Required(SyncLevel::FileSynchronized),
            ),
        )
        .expect("transaction stages before filesystem file capability is known");
        txn.write(b"new").expect("write must succeed");

        let error = txn
            .commit()
            .expect_err("required file synchronization must not downgrade");

        assert_eq!(error.operation(), Operation::FlushFile);
        assert!(
            fs.operations()
                .iter()
                .all(|operation| operation.op != SimOp::Rename)
        );
    }

    #[test]
    fn reject_link_policy_fails_before_staging_while_follow_and_pin_opens() {
        let rejected_fs = SimFsBuilder::new()
            .preexisting_directory("real")
            .preexisting_directory_link("alias", "/real")
            .build();
        let error = match AtomicWriteTxn::begin(
            rejected_fs.clone(),
            Path::new("/alias/vault.bin"),
            options(
                ExistingParentLinkPolicy::Reject,
                SyncPolicy::Required(SyncLevel::ProcessAtomic),
            ),
        ) {
            Ok(_) => panic!("reject must not traverse a directory link"),
            Err(error) => error,
        };
        assert_eq!(error.operation(), Operation::PrepareParent);
        assert!(
            rejected_fs
                .operations()
                .iter()
                .all(|operation| operation.op != SimOp::CreateFileAt)
        );

        let followed_fs = SimFsBuilder::new()
            .preexisting_directory("real")
            .preexisting_directory_link("alias", "/real")
            .build();
        let txn = AtomicWriteTxn::begin(
            followed_fs.clone(),
            Path::new("/alias/vault.bin"),
            options(
                ExistingParentLinkPolicy::FollowAndPin,
                SyncPolicy::Required(SyncLevel::ProcessAtomic),
            ),
        )
        .expect("follow-and-pin must open the linked target");
        txn.abort().expect("abort must clean the staged artifact");
        assert!(
            followed_fs
                .operations()
                .iter()
                .any(|operation| operation.op == SimOp::CreateFileAt)
        );
    }

    #[test]
    fn retained_root_survives_link_retarget_and_uses_original_directory() {
        let fs = SimFsBuilder::new()
            .preexisting_directory("trusted")
            .preexisting_directory("evil")
            .preexisting_directory_link("selected", "/trusted")
            .mutate_before(
                SimOp::OpenDirAt,
                1,
                NamespaceMutation::rename_entry("/", "selected", "selected-old"),
            )
            .mutate_before(
                SimOp::OpenDirAt,
                1,
                NamespaceMutation::create_directory_link("/", "selected", "/evil"),
            )
            .build();
        let directory = AtomicDirectory::open(&fs, Path::new("/selected"))
            .expect("selected root must be pinned");
        let write_options = AtomicWriteOptions {
            publication: PublishPolicy::Create {
                permissions: Permissions::OwnerOnly,
            },
            parent_directory: ParentDirectoryPolicy::CreateMissing {
                permissions: DirectoryPermissions::OwnerOnly,
            },
            existing_parent_links: ExistingParentLinkPolicy::Reject,
            synchronization: SyncPolicy::Required(SyncLevel::ProcessAtomic),
        };

        let mut txn = AtomicWriteTxn::begin_at_directory(
            fs.clone(),
            &directory,
            RelativeDestination::parse("nested/vault.bin").expect("relative path must parse"),
            write_options,
        )
        .expect("transaction must stay beneath the pinned root");
        txn.write(b"trusted").expect("write must succeed");
        txn.commit().expect("commit must succeed");

        let listing = fs.final_snapshot().live_listing();
        assert_eq!(
            listing
                .get("trusted/nested/vault.bin")
                .expect("original root must receive publication")
                .bytes,
            b"trusted"
        );
        assert!(!listing.contains_key("evil/nested/vault.bin"));
    }

    #[test]
    fn retained_root_survives_ancestor_rename_after_open() {
        let fs = SimFsBuilder::new()
            .preexisting_directory("trusted")
            .mutate_before(
                SimOp::CreateFileAt,
                0,
                NamespaceMutation::rename_entry("/", "trusted", "renamed"),
            )
            .build();
        let directory = AtomicDirectory::open(&fs, Path::new("/trusted"))
            .expect("selected root must be pinned");

        let mut txn = AtomicWriteTxn::begin_at_directory(
            fs.clone(),
            &directory,
            RelativeDestination::parse("vault.bin").expect("relative path must parse"),
            options(
                ExistingParentLinkPolicy::Reject,
                SyncPolicy::Required(SyncLevel::ProcessAtomic),
            ),
        )
        .expect("renaming the selected root must not invalidate its handle");
        txn.write(b"renamed").expect("write must succeed");
        txn.commit().expect("commit must succeed");

        assert_eq!(
            fs.final_snapshot()
                .live_listing()
                .get("renamed/vault.bin")
                .expect("renamed root must receive publication")
                .bytes,
            b"renamed"
        );
    }

    #[test]
    fn retained_root_rejects_linked_descendant_before_staging() {
        let fs = SimFsBuilder::new()
            .preexisting_directory("trusted")
            .preexisting_directory("evil")
            .mutate_before(
                SimOp::OpenDirAt,
                1,
                NamespaceMutation::create_directory_link("/trusted", "nested", "/evil"),
            )
            .build();
        let directory = AtomicDirectory::open(&fs, Path::new("/trusted"))
            .expect("selected root must be pinned");

        let error = match AtomicWriteTxn::begin_at_directory(
            fs.clone(),
            &directory,
            RelativeDestination::parse("nested/vault.bin").expect("relative path must parse"),
            options(
                ExistingParentLinkPolicy::Reject,
                SyncPolicy::Required(SyncLevel::ProcessAtomic),
            ),
        ) {
            Ok(_) => panic!("linked descendants must be rejected"),
            Err(error) => error,
        };

        assert_eq!(error.operation(), Operation::PrepareParent);
        assert!(
            fs.operations()
                .iter()
                .all(|operation| operation.op != SimOp::CreateFileAt)
        );
    }

    #[test]
    fn begin_at_directory_rejects_follow_policy_before_filesystem_access() {
        let fs = SimFsBuilder::new().preexisting_directory("trusted").build();
        let directory = AtomicDirectory::open(&fs, Path::new("/trusted"))
            .expect("selected root must be pinned");
        let operation_count = fs.operations().len();

        let error = match AtomicWriteTxn::begin_at_directory(
            fs.clone(),
            &directory,
            RelativeDestination::parse("vault.bin").expect("relative path must parse"),
            options(
                ExistingParentLinkPolicy::FollowAndPin,
                SyncPolicy::Required(SyncLevel::ProcessAtomic),
            ),
        ) {
            Ok(_) => panic!("relative transactions must enforce reject"),
            Err(error) => error,
        };

        assert_eq!(error.operation(), Operation::Begin);
        assert_eq!(fs.operations().len(), operation_count);
    }

    /// A destination in the reserved namespace would be published successfully
    /// and then silently deleted by a later sweep, which reports it as an
    /// ordinary reclamation. Both entry points must refuse it, and must do so
    /// before touching the filesystem so no parent directory is created for a
    /// write that cannot proceed.
    #[test]
    fn reserved_destination_names_are_refused_before_filesystem_access() {
        // Exactly what the writer would generate, so the sweeper's own parser
        // recognizes it as a reclaimable artifact.
        let generated = crate::naming::new_file_lease_artifact_name(TemporaryFileRole::New)
            .expect("temporary name must generate");
        assert!(is_reserved_temporary_artifact_name(&generated));

        for name in [
            generated.as_str(),
            // Malformed and future spellings stay reserved, so they must be
            // refused too rather than treated as ordinary destinations.
            ".kg-tmp-",
            ".kg-tmp-not-a-real-artifact",
        ] {
            let absolute_fs = SimFsBuilder::new().build();
            let error = match AtomicWriteTxn::begin(
                absolute_fs.clone(),
                Path::new(&format!("/{name}")),
                options(
                    ExistingParentLinkPolicy::Reject,
                    SyncPolicy::Required(SyncLevel::ProcessAtomic),
                ),
            ) {
                Ok(_) => panic!("{name} must not be publishable"),
                Err(error) => error,
            };
            assert_eq!(error.operation(), Operation::Begin);
            assert_eq!(error.failure().kind(), FailureKind::InvalidInput);
            assert!(
                absolute_fs.operations().is_empty(),
                "{name} must be refused before any filesystem access"
            );

            let relative_fs = SimFsBuilder::new().preexisting_directory("vault").build();
            let directory = AtomicDirectory::open(&relative_fs, Path::new("/vault"))
                .expect("root must be pinned");
            let operation_count = relative_fs.operations().len();
            let error = match AtomicWriteTxn::begin_at_directory(
                relative_fs.clone(),
                &directory,
                RelativeDestination::parse(name).expect("relative path must parse"),
                options(
                    ExistingParentLinkPolicy::Reject,
                    SyncPolicy::Required(SyncLevel::ProcessAtomic),
                ),
            ) {
                Ok(_) => panic!("{name} must not be publishable beneath a retained root"),
                Err(error) => error,
            };
            assert_eq!(error.operation(), Operation::Begin);
            assert_eq!(error.failure().kind(), FailureKind::InvalidInput);
            assert_eq!(
                relative_fs.operations().len(),
                operation_count,
                "{name} must be refused before any filesystem access"
            );
        }
    }

    /// The reservation covers the basename wherever it appears, not only the
    /// directories the maintenance pass happens to sweep today: adding a sweep
    /// root must not retroactively make published files deletable.
    #[test]
    fn reserved_names_are_refused_in_nested_destinations() {
        let fs = SimFsBuilder::new().preexisting_directory("vault").build();
        let directory =
            AtomicDirectory::open(&fs, Path::new("/vault")).expect("root must be pinned");

        let error = match AtomicWriteTxn::begin_at_directory(
            fs,
            &directory,
            RelativeDestination::parse(".kg-tmp-n-nested").expect("relative path must parse"),
            options(
                ExistingParentLinkPolicy::Reject,
                SyncPolicy::Required(SyncLevel::ProcessAtomic),
            ),
        ) {
            Ok(_) => panic!("a reserved basename must be refused at any depth"),
            Err(error) => error,
        };
        assert_eq!(error.failure().kind(), FailureKind::InvalidInput);
    }

    /// Ordinary destinations that merely resemble the reserved prefix must stay
    /// writable; the reservation is a prefix rule, not a substring rule.
    #[test]
    fn ordinary_destinations_resembling_the_prefix_remain_writable() {
        for name in ["vault.kdbx", "kg-tmp-vault", "my.kg-tmp-vault", ".kg-tmp"] {
            let fs = SimFsBuilder::new().build();
            let mut txn = AtomicWriteTxn::begin(
                fs,
                Path::new(&format!("/{name}")),
                options(
                    ExistingParentLinkPolicy::Reject,
                    SyncPolicy::Required(SyncLevel::ProcessAtomic),
                ),
            )
            .unwrap_or_else(|_| panic!("{name} must remain publishable"));
            txn.write(b"payload").expect("write must succeed");
            txn.commit().expect("commit must succeed");
        }
    }

    #[test]
    fn explicit_abort_reports_cleanup_failure() {
        let fs = SimFsBuilder::new()
            .fault(SimOp::Unlink, 0, std::io::ErrorKind::PermissionDenied)
            .build();
        let txn = AtomicWriteTxn::begin_with_platform_maximum(
            fs,
            Path::new("/vault.bin"),
            options(
                ExistingParentLinkPolicy::Reject,
                SyncPolicy::Required(SyncLevel::ProcessAtomic),
            ),
            SyncLevel::FileAndNamespaceSynchronized,
        )
        .expect("transaction must stage before abort");

        let error = txn
            .abort()
            .expect_err("explicit abort must surface failed staged cleanup");

        assert_eq!(error.operation(), Operation::Cleanup);
        assert_eq!(error.failure().kind(), FailureKind::PermissionDenied);
        assert!(error.cleanup_incomplete());
    }

    #[test]
    fn sync_failure_retains_an_earlier_cleanup_failure() {
        let fs = SimFsBuilder::new()
            .without_exclusive_rename()
            .fault(SimOp::Unlink, 0, std::io::ErrorKind::PermissionDenied)
            .fault(SimOp::FlushPublication, 0, std::io::ErrorKind::Other)
            .build();
        let mut txn = AtomicWriteTxn::begin(
            fs,
            Path::new("/vault.bin"),
            options(
                ExistingParentLinkPolicy::Reject,
                SyncPolicy::Required(SyncLevel::FileAndNamespaceSynchronized),
            ),
        )
        .expect("transaction must stage");
        txn.write(b"new").expect("write must succeed");

        let success = txn.commit().expect("publication already succeeded");

        assert!(matches!(
            success.outcome(),
            CommitOutcome::PublishedDurabilityUnknown(_)
        ));
        assert_eq!(
            success
                .cleanup()
                .failure()
                .expect("cleanup state must survive synchronization failure")
                .kind(),
            FailureKind::PermissionDenied,
        );
    }

    #[test]
    fn post_publication_close_failure_is_cleanup_incomplete() {
        let fs = SimFsBuilder::new()
            .fault(SimOp::Close, 0, std::io::ErrorKind::Other)
            .build();
        let mut txn = AtomicWriteTxn::begin(
            fs,
            Path::new("/vault.bin"),
            options(
                ExistingParentLinkPolicy::Reject,
                SyncPolicy::Required(SyncLevel::ProcessAtomic),
            ),
        )
        .expect("transaction must stage");
        txn.write(b"new").expect("write must succeed");

        let success = txn.commit().expect("publication already succeeded");

        assert_eq!(success.outcome(), CommitOutcome::Published);
        assert_eq!(
            success
                .cleanup()
                .failure()
                .expect("close failure must be retained")
                .kind(),
            FailureKind::Other,
        );
    }
}
