//! Power-cut and fault-injection tests for the atomic-write protocol.
//!
//! Each test runs the full protocol against [`SimFs`], then replays every
//! reachable post-power-loss projection after every step and asserts the
//! crash contract: at `Ordered` and above the destination is always the
//! complete old file or the complete new file, everything else left behind
//! is a sweepable temporary artifact, and the reported outcome is truthful.

use std::{
    collections::HashMap,
    io,
    panic::{AssertUnwindSafe, catch_unwind},
    path::Path,
};

use crate::{
    durability::{AchievedSyncLevel, SyncLevel},
    error::{FailureKind, Operation},
    fsops::{CreatedStaged, FsOps, StagedNameResidual},
    naming::{
        TemporaryArtifactEntryKind, TemporaryArtifactProtocol, TemporaryFileRole,
        parse_temporary_artifact_name,
    },
    simfs::{NamespaceMutation, SimFs, SimFsBuilder, SimOp, Snapshot},
    txn::{
        AtomicWriteOptions, AtomicWriteTxn, CleanupState, CommitOutcome, DirectoryPermissions,
        ExistingParentLinkPolicy, ParentDirectoryPolicy, Permissions, PublicationOperation,
        PublishPolicy, ReplacementAccessPolicy,
    },
};

const DEST: &str = "vault.kdbx";
const DEST_PATH: &str = "/vault.kdbx";
const OLD: &[u8] = b"the complete old vault contents";
const NEW: &[u8] = b"the complete NEW vault contents, longer than before";
const OLD_BASIC_PERMISSIONS: u32 = 0o640;
const OWNER_ONLY_BASIC_PERMISSIONS: u32 = 0o600;

fn create_publication() -> PublishPolicy {
    PublishPolicy::Create {
        permissions: Permissions::OwnerOnly,
    }
}

fn replace_preserving_publication() -> PublishPolicy {
    PublishPolicy::Replace {
        access: ReplacementAccessPolicy::PreserveExistingBasicPermissions {
            if_destination_missing: Permissions::OwnerOnly,
        },
    }
}

fn replace_requested_publication(permissions: Permissions) -> PublishPolicy {
    PublishPolicy::Replace {
        access: ReplacementAccessPolicy::UseRequestedPermissions { permissions },
    }
}

fn options(publication: PublishPolicy, durability: SyncLevel) -> AtomicWriteOptions {
    AtomicWriteOptions {
        publication,
        parent_directory: ParentDirectoryPolicy::CreateMissing {
            permissions: DirectoryPermissions::OwnerOnly,
        },
        existing_parent_links: ExistingParentLinkPolicy::FollowAndPin,
        synchronization: crate::SyncPolicy::Required(durability),
    }
}

fn operation_labels(fs: &SimFs) -> Vec<SimOp> {
    fs.operations()
        .into_iter()
        .map(|operation| operation.op)
        .collect()
}

fn run_protocol(
    fs: &SimFs,
    publication: PublishPolicy,
    durability: SyncLevel,
) -> Result<crate::txn::CommitSuccess, crate::error::TxnError> {
    run_protocol_at(fs, DEST_PATH, publication, durability)
}

fn run_protocol_at(
    fs: &SimFs,
    destination: &str,
    publication: PublishPolicy,
    durability: SyncLevel,
) -> Result<crate::txn::CommitSuccess, crate::error::TxnError> {
    let mut txn = AtomicWriteTxn::begin(
        fs.clone(),
        Path::new(destination),
        options(publication, durability),
    )?;
    // Two writes so a cut can land between them.
    let middle = NEW.len() / 2;
    txn.write(&NEW[..middle])?;
    txn.write(&NEW[middle..])?;
    txn.commit()
}

/// Asserts the crash contract over every projection of every snapshot.
///
/// `old` is the destination content that existed before the transaction, or
/// `None` for create-mode runs against an empty directory.
fn assert_crash_contract(
    snapshots: &[Snapshot],
    old: Option<(&[u8], u32)>,
    new_basic_permissions: u32,
) {
    for snapshot in snapshots {
        for projection in snapshot.crash_projections() {
            for (name, contents) in &projection {
                if name == DEST || name.ends_with(&format!("/{DEST}")) {
                    for state in contents {
                        let is_old = old.is_some_and(|(old, _)| state.bytes == old);
                        let is_new = state.bytes == NEW;
                        assert!(
                            is_old || is_new,
                            "after a cut at {:?}, destination holds {} bytes that are \
                             neither the old nor the new file",
                            snapshot.label,
                            state.bytes.len(),
                        );
                        if let Some((_, old_basic_permissions)) = old
                            && is_old
                        {
                            assert_eq!(
                                state.basic_permissions, old_basic_permissions,
                                "old bytes have wrong permissions after a cut at {:?}",
                                snapshot.label,
                            );
                        }
                        if is_new {
                            assert_eq!(
                                state.basic_permissions, new_basic_permissions,
                                "new bytes have wrong permissions after a cut at {:?}",
                                snapshot.label,
                            );
                        }
                    }
                    if old.is_none() {
                        // Create mode: a visible destination must already be
                        // the complete new file.
                        for state in contents {
                            assert_eq!(state.bytes, NEW, "cut at {:?}", snapshot.label);
                        }
                    }
                } else {
                    assert!(
                        Path::new(name)
                            .file_name()
                            .and_then(|name| name.to_str())
                            .and_then(parse_temporary_artifact_name)
                            .is_some(),
                        "after a cut at {:?}, leftover {name:?} is not sweepable",
                        snapshot.label,
                    );
                }
            }
        }
    }
}

#[test]
fn replace_survives_a_power_cut_at_every_step() {
    for durability in [
        SyncLevel::FileSynchronized,
        SyncLevel::FileAndNamespaceSynchronized,
    ] {
        let fs = SimFsBuilder::new()
            .preexisting_destination(DEST, OLD)
            .build();
        let success = run_protocol(&fs, replace_preserving_publication(), durability)
            .expect("commit must succeed");
        assert_eq!(success.outcome(), CommitOutcome::Published);
        assert_eq!(
            success.achieved(),
            Some(AchievedSyncLevel::from_selected(durability))
        );
        assert_crash_contract(
            &fs.snapshots(),
            Some((OLD, OLD_BASIC_PERMISSIONS)),
            OLD_BASIC_PERMISSIONS,
        );
        assert!(!fs.fsyncgate_violated());
    }
}

#[test]
fn preserved_permissions_are_applied_and_verified_before_the_single_file_flush() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .build();
    run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("commit must succeed");

    let labels = fs
        .snapshots()
        .into_iter()
        .map(|snapshot| snapshot.label)
        .collect::<Vec<_>>();
    let capture = labels
        .iter()
        .position(|label| *label == SimOp::ReadMetadata)
        .expect("preservation must capture destination permissions");
    let create_staged = labels
        .iter()
        .position(|label| *label == SimOp::CreateFileAt)
        .expect("transaction must create a staged file");
    let apply = labels
        .iter()
        .position(|label| *label == SimOp::ApplyMetadata)
        .expect("preservation must apply permissions");
    let verify = labels
        .iter()
        .position(|label| *label == SimOp::VerifyMetadata)
        .expect("applied permissions must be verified");
    let flush = labels
        .iter()
        .position(|label| *label == SimOp::FlushFile)
        .expect("ordered publication must flush the staged file");
    let rename = labels
        .iter()
        .position(|label| *label == SimOp::Rename)
        .expect("commit must publish");

    assert!(capture < create_staged);
    assert!(create_staged < apply);
    assert!(apply < verify);
    assert!(verify < flush);
    assert!(flush < rename);
    assert_eq!(
        labels
            .iter()
            .filter(|label| **label == SimOp::FlushFile)
            .count(),
        1,
        "bytes and permissions must share one staged-file flush"
    );
}

#[test]
fn capture_permissions_failure_happens_before_staging() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .fault(SimOp::ReadMetadata, 0, io::ErrorKind::PermissionDenied)
        .build();
    let error = match AtomicWriteTxn::begin(
        fs.clone(),
        Path::new(DEST_PATH),
        options(
            replace_preserving_publication(),
            SyncLevel::FileAndNamespaceSynchronized,
        ),
    ) {
        Ok(_) => panic!("permission capture must fail before staging"),
        Err(error) => error,
    };

    assert_eq!(error.operation(), Operation::Metadata);
    assert_eq!(error.failure().kind(), FailureKind::PermissionDenied);
    assert!(
        fs.snapshots()
            .iter()
            .all(|snapshot| snapshot.label != SimOp::CreateFileAt),
        "permission capture failure must happen before staged-file creation"
    );
    let live = fs.final_snapshot().live_listing();
    assert_eq!(live.len(), 1);
    assert_eq!(live.get(DEST).expect("destination").bytes, OLD);
}

#[test]
fn preserving_a_non_regular_destination_fails_before_staging() {
    let fs = SimFsBuilder::new().preexisting_directory(DEST).build();
    let error = match AtomicWriteTxn::begin(
        fs.clone(),
        Path::new(DEST_PATH),
        options(
            replace_preserving_publication(),
            SyncLevel::FileAndNamespaceSynchronized,
        ),
    ) {
        Ok(_) => panic!("a directory must not be treated as a missing destination"),
        Err(error) => error,
    };

    assert_eq!(error.operation(), Operation::Metadata);
    assert_eq!(error.failure().kind(), FailureKind::InvalidInput);
    assert!(
        fs.snapshots()
            .iter()
            .all(|snapshot| snapshot.label != SimOp::CreateFileAt),
        "non-regular destinations must be rejected before staged-file creation"
    );
}

#[test]
fn apply_permissions_failure_leaves_the_old_destination_untouched() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .fault(SimOp::ApplyMetadata, 0, io::ErrorKind::PermissionDenied)
        .build();
    let error = run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect_err("permission application must fail before publication");

    assert_eq!(error.operation(), Operation::Metadata);
    assert_eq!(error.failure().kind(), FailureKind::PermissionDenied);
    let live = fs.final_snapshot().live_listing();
    let destination = live.get(DEST).expect("old destination must remain");
    assert_eq!(destination.bytes, OLD);
    assert_eq!(
        destination.basic_permissions, OLD_BASIC_PERMISSIONS,
        "failed preservation must not alter the destination"
    );
    assert_eq!(live.len(), 1, "staged temporary must be cleaned up");
    assert!(
        fs.snapshots()
            .iter()
            .all(|snapshot| snapshot.label != SimOp::Rename),
        "metadata failure must happen before publication"
    );
}

#[test]
fn verification_rejects_a_silent_wrong_permission_application_before_publication() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .with_corrupt_metadata_application()
        .build();
    let error = run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect_err("verification must detect silently misapplied permissions");

    assert_eq!(error.operation(), Operation::Metadata);
    assert_eq!(error.failure().kind(), FailureKind::Other);
    let live = fs.final_snapshot().live_listing();
    let destination = live.get(DEST).expect("old destination must remain");
    assert_eq!(destination.bytes, OLD);
    assert_eq!(destination.basic_permissions, OLD_BASIC_PERMISSIONS);
    assert_eq!(live.len(), 1, "staged temporary must be cleaned up");
    assert!(
        fs.snapshots()
            .iter()
            .any(|snapshot| snapshot.label == SimOp::ApplyMetadata),
        "the corrupt apply must report success before verification rejects it"
    );
    assert!(
        fs.snapshots()
            .iter()
            .all(|snapshot| { !matches!(snapshot.label, SimOp::FlushFile | SimOp::Rename) }),
        "verification failure must precede the staged-file flush and publication"
    );
}

#[test]
fn missing_preserved_destination_uses_the_explicit_fallback_permissions() {
    let fs = SimFsBuilder::new().build();
    run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("replace against a missing destination must use its fallback");

    let live = fs.final_snapshot().live_listing();
    let destination = live.get(DEST).expect("destination must be published");
    assert_eq!(destination.bytes, NEW);
    assert_eq!(destination.basic_permissions, OWNER_ONLY_BASIC_PERMISSIONS);
    assert!(
        fs.snapshots().iter().all(|snapshot| {
            !matches!(snapshot.label, SimOp::ApplyMetadata | SimOp::VerifyMetadata)
        }),
        "fallback permissions are established at staged-file creation"
    );
}

#[test]
fn requested_replace_permissions_do_not_enter_the_metadata_path() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .build();
    run_protocol(
        &fs,
        replace_requested_publication(Permissions::ProcessDefault),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("commit must succeed");

    let live = fs.final_snapshot().live_listing();
    let destination = live.get(DEST).expect("destination must be published");
    assert_eq!(destination.bytes, NEW);
    assert_eq!(destination.basic_permissions, 0o666);
    assert!(
        fs.snapshots().iter().all(|snapshot| {
            !matches!(
                snapshot.label,
                SimOp::ReadMetadata | SimOp::ApplyMetadata | SimOp::VerifyMetadata
            )
        }),
        "requested permissions do not capture, apply, or verify destination metadata"
    );
}

#[test]
fn preservation_excludes_special_mode_bits() {
    let fs = SimFsBuilder::new()
        .preexisting_destination_with_permissions(DEST, OLD, 0o4755)
        .build();
    run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("commit must succeed");

    let durable = fs.final_snapshot().durable_projection();
    let destination = durable.get(DEST).expect("destination must be durable");
    assert_eq!(destination.bytes, NEW);
    assert_eq!(
        destination.basic_permissions, 0o755,
        "set-ID and sticky bits are outside basic-permission preservation"
    );
}

#[test]
fn create_survives_a_power_cut_at_every_step() {
    for durability in [
        SyncLevel::FileSynchronized,
        SyncLevel::FileAndNamespaceSynchronized,
    ] {
        let fs = SimFsBuilder::new().build();
        let success =
            run_protocol(&fs, create_publication(), durability).expect("commit must succeed");
        assert_eq!(success.outcome(), CommitOutcome::Published);
        assert_crash_contract(&fs.snapshots(), None, OWNER_ONLY_BASIC_PERMISSIONS);
        assert!(
            fs.snapshots().iter().all(|snapshot| {
                !matches!(
                    snapshot.label,
                    SimOp::ReadMetadata | SimOp::ApplyMetadata | SimOp::VerifyMetadata
                )
            }),
            "create publication must not enter the replacement metadata path"
        );
    }
}

#[test]
fn durable_commit_is_durable_in_the_minimal_projection() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .build();
    let success = run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("commit must succeed");
    assert_eq!(
        success.achieved(),
        Some(AchievedSyncLevel::FileAndNamespaceSynchronized)
    );

    // After a Durable commit returns, even a projection where nothing
    // unflushed persisted must contain the new bytes: this is the assertion
    // that fails when the directory fsync is forgotten.
    let durable = fs.final_snapshot().durable_projection();
    assert_eq!(
        durable.get(DEST).map(|state| state.bytes.as_slice()),
        Some(NEW)
    );
}

#[test]
fn ordered_commit_without_dir_flush_may_lose_the_publish_but_never_tears() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .build();
    let success = run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileSynchronized,
    )
    .expect("commit must succeed");
    assert_eq!(
        success.achieved(),
        Some(AchievedSyncLevel::FileSynchronized)
    );

    // Without the directory flush the rename may be lost (old file back), but
    // the destination must still be complete either way.
    let durable = fs.final_snapshot().durable_projection();
    let content = durable.get(DEST).expect("destination must exist");
    assert!(content.bytes.as_slice() == OLD || content.bytes.as_slice() == NEW);
}

#[test]
fn flush_failure_aborts_cleanly_and_never_retries_the_descriptor() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .fault(SimOp::FlushFile, 0, io::ErrorKind::Other)
        .build();
    let error = run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect_err("flush failure must fail the commit");

    assert_eq!(error.operation(), Operation::FlushFile);
    assert!(
        !fs.fsyncgate_violated(),
        "the failed descriptor was flushed again"
    );
    // Destination untouched, staged temporary removed.
    let live = fs.final_snapshot().live_listing();
    assert_eq!(
        live.get(DEST).map(|state| state.bytes.as_slice()),
        Some(OLD)
    );
    assert_eq!(live.len(), 1, "staged temporary must be cleaned up");
    assert_crash_contract(
        &fs.snapshots(),
        Some((OLD, OLD_BASIC_PERMISSIONS)),
        OLD_BASIC_PERMISSIONS,
    );
}

#[test]
fn failed_file_flush_promotes_neither_staged_bytes_nor_permissions() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .fault(SimOp::FlushFile, 0, io::ErrorKind::Other)
        .build();
    run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect_err("flush failure must fail the commit");

    let snapshots = fs.snapshots();
    let failed_flush = snapshots
        .iter()
        .find(|snapshot| snapshot.label == SimOp::FlushFile)
        .expect("failed flush state must be observable");
    for projection in failed_flush.crash_projections() {
        for (name, states) in projection {
            if name == DEST {
                for state in states {
                    assert_eq!(state.bytes, OLD);
                    assert_eq!(state.basic_permissions, OLD_BASIC_PERMISSIONS);
                }
            } else {
                for state in states {
                    assert!(
                        state.bytes.is_empty(),
                        "a failed flush must not make staged bytes durable"
                    );
                    assert_eq!(
                        state.basic_permissions, OWNER_ONLY_BASIC_PERMISSIONS,
                        "a failed flush must not make staged permission changes durable"
                    );
                }
            }
        }
    }
}

#[test]
fn rename_failure_leaves_the_destination_untouched() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .fault(SimOp::PrepareRename, 0, io::ErrorKind::PermissionDenied)
        .build();
    let error = run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect_err("rename failure must fail the commit");

    assert_eq!(error.operation(), Operation::Rename);
    assert_eq!(error.failure().kind(), FailureKind::PermissionDenied);
    let live = fs.final_snapshot().live_listing();
    assert_eq!(
        live.get(DEST).map(|state| state.bytes.as_slice()),
        Some(OLD)
    );
    assert_eq!(live.len(), 1, "staged temporary must be cleaned up");
}

#[test]
fn applied_rename_with_lost_reply_is_positively_reconciled_and_synchronized() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .fault(SimOp::RenameReply, 0, io::ErrorKind::Other)
        .build();

    let success = run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("destination identity must positively reconcile publication");

    assert_eq!(success.outcome(), CommitOutcome::Published);
    assert_eq!(
        success.achieved(),
        Some(AchievedSyncLevel::FileAndNamespaceSynchronized)
    );
    assert_eq!(
        success.publication_operation(),
        Some(PublicationOperation::Rename)
    );
    assert_eq!(success.cleanup(), CleanupState::Complete);
    assert_eq!(
        fs.final_snapshot()
            .live_listing()
            .get(DEST)
            .map(|state| state.bytes.as_slice()),
        Some(NEW)
    );
    assert!(
        operation_labels(&fs).contains(&SimOp::FlushPublication),
        "a positively reconciled publication must still run its barrier"
    );
}

#[test]
fn applied_rename_that_disappears_before_reconciliation_remains_unknown() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .fault(SimOp::RenameReply, 0, io::ErrorKind::PermissionDenied)
        .mutate_before(
            SimOp::ObserveIdentity,
            0,
            NamespaceMutation::remove_entry("/", DEST),
        )
        .build();

    let success = run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("an absent destination is not proof that publication never occurred");

    let CommitOutcome::PublicationUnknown(failure) = success.outcome() else {
        panic!("an applied rename hidden by later removal must remain unknown");
    };
    assert_eq!(failure.kind(), FailureKind::PermissionDenied);
    assert_eq!(success.achieved(), None);
    let operations = operation_labels(&fs);
    assert_eq!(
        operations
            .iter()
            .filter(|operation| **operation == SimOp::PrepareRename)
            .count(),
        1,
    );
    assert_eq!(
        operations
            .iter()
            .filter(|operation| **operation == SimOp::Rename)
            .count(),
        1,
    );
    assert!(!operations.contains(&SimOp::HardLink));
    assert!(!operations.contains(&SimOp::FlushPublication));
    assert!(
        !fs.final_snapshot().live_listing().contains_key(DEST),
        "the injected removal must leave the destination absent",
    );
}

#[test]
fn reconciled_rename_with_failed_publication_barrier_reports_unknown_durability() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .fault(SimOp::RenameReply, 0, io::ErrorKind::Other)
        .fault(SimOp::FlushPublication, 0, io::ErrorKind::Other)
        .build();

    let success = run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("publication identity was established before the failed barrier");

    assert!(matches!(
        success.outcome(),
        CommitOutcome::PublishedDurabilityUnknown(_)
    ));
    assert_eq!(
        success.achieved(),
        Some(AchievedSyncLevel::FileSynchronized)
    );
    assert_eq!(
        success.publication_operation(),
        Some(PublicationOperation::Rename)
    );
}

#[test]
fn applied_hard_link_with_lost_reply_is_positively_reconciled() {
    let fs = SimFsBuilder::new()
        .without_exclusive_rename()
        .fault(SimOp::HardLinkReply, 0, io::ErrorKind::Other)
        .build();

    let success = run_protocol(
        &fs,
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("hard-link destination identity must establish publication");

    assert_eq!(success.outcome(), CommitOutcome::Published);
    assert_eq!(
        success.publication_operation(),
        Some(PublicationOperation::HardLink)
    );
    assert_eq!(success.cleanup(), CleanupState::Complete);
    assert_eq!(
        fs.final_snapshot()
            .live_listing()
            .get(DEST)
            .map(|state| state.bytes.as_slice()),
        Some(NEW)
    );
}

#[test]
fn hard_link_dispatch_failure_without_an_applied_link_remains_unknown() {
    let fs = SimFsBuilder::new()
        .without_exclusive_rename()
        .fault(SimOp::HardLink, 0, io::ErrorKind::Other)
        .build();

    let success = run_protocol(
        &fs,
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("post-dispatch failure cannot prove that no link was created");

    assert!(matches!(
        success.outcome(),
        CommitOutcome::PublicationUnknown(_)
    ));
    assert_eq!(success.achieved(), None);
    assert_eq!(
        success.publication_operation(),
        Some(PublicationOperation::HardLink)
    );
    let operations = operation_labels(&fs);
    assert!(operations.contains(&SimOp::HardLink));
    assert!(!operations.contains(&SimOp::HardLinkReply));
    assert!(!operations.contains(&SimOp::FlushPublication));
}

#[test]
fn applied_hard_link_with_failed_identity_inspection_remains_unknown() {
    let fs = SimFsBuilder::new()
        .without_exclusive_rename()
        .fault(SimOp::HardLinkReply, 0, io::ErrorKind::Other)
        .fault(SimOp::ObserveIdentity, 2, io::ErrorKind::PermissionDenied)
        .build();

    let success = run_protocol(
        &fs,
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("inspection failure after an applied link must remain unknown");

    assert!(matches!(
        success.outcome(),
        CommitOutcome::PublicationUnknown(_)
    ));
    assert_eq!(success.achieved(), None);
    assert_eq!(
        success.publication_operation(),
        Some(PublicationOperation::HardLink)
    );
    assert!(!operation_labels(&fs).contains(&SimOp::FlushPublication));
}

#[test]
fn may_have_mutated_errors_never_retry_fallback_or_report_collision() {
    for kind in [
        io::ErrorKind::AlreadyExists,
        io::ErrorKind::PermissionDenied,
        io::ErrorKind::Unsupported,
        io::ErrorKind::ResourceBusy,
    ] {
        let fs = SimFsBuilder::new().fault(SimOp::Rename, 0, kind).build();

        let success = run_protocol(
            &fs,
            create_publication(),
            SyncLevel::FileAndNamespaceSynchronized,
        )
        .expect("ambiguous publication is a positive unknown outcome");

        let CommitOutcome::PublicationUnknown(failure) = success.outcome() else {
            panic!("post-dispatch {kind:?} must remain publication-unknown");
        };
        assert_eq!(failure.kind(), FailureKind::from_io_error_kind(kind));
        assert_eq!(success.achieved(), None);
        assert_eq!(
            success.publication_operation(),
            Some(PublicationOperation::Rename)
        );
        let operations = operation_labels(&fs);
        assert_eq!(
            operations
                .iter()
                .filter(|operation| **operation == SimOp::PrepareRename)
                .count(),
            1,
            "the request must be fully prepared exactly once",
        );
        assert_eq!(
            operations
                .iter()
                .filter(|operation| **operation == SimOp::Rename)
                .count(),
            1
        );
        assert!(!operations.contains(&SimOp::HardLink));
        assert!(!operations.contains(&SimOp::RenameReply));
        assert!(!operations.contains(&SimOp::FlushPublication));
    }
}

#[test]
fn ambiguous_rename_with_concurrently_replaced_destination_remains_unknown() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .fault(SimOp::RenameReply, 0, io::ErrorKind::Other)
        .mutate_before(
            SimOp::ObserveIdentity,
            0,
            NamespaceMutation::replace_file("/", DEST),
        )
        .build();

    let success = run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("concurrent replacement must be reported as unknown");

    assert!(matches!(
        success.outcome(),
        CommitOutcome::PublicationUnknown(_)
    ));
    assert_eq!(success.achieved(), None);
    let operations = operation_labels(&fs);
    assert_eq!(
        operations
            .iter()
            .filter(|operation| **operation == SimOp::PrepareRename)
            .count(),
        1,
        "an ambiguous publication must not prepare another request",
    );
    assert_eq!(
        operations
            .iter()
            .filter(|operation| **operation == SimOp::Rename)
            .count(),
        1,
        "an ambiguous publication must not dispatch another request",
    );
    assert!(!operations.contains(&SimOp::HardLink));
    assert!(!operations.contains(&SimOp::FlushPublication));
    let destination = fs
        .final_snapshot()
        .live_listing()
        .get(DEST)
        .cloned()
        .expect("concurrent replacement keeps the destination present");
    assert_eq!(
        destination.bytes, NEW,
        "the replacement preserves published bytes while changing identity"
    );
    assert_eq!(
        destination.basic_permissions, OLD_BASIC_PERMISSIONS,
        "the replacement preserves permissions while changing identity"
    );
}

#[test]
fn ambiguous_rename_identity_inspection_failure_remains_unknown() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .fault(SimOp::Rename, 0, io::ErrorKind::Other)
        .fault(SimOp::ObserveIdentity, 0, io::ErrorKind::PermissionDenied)
        .build();

    let success = run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("inspection failure must be contained as publication unknown");

    assert!(matches!(
        success.outcome(),
        CommitOutcome::PublicationUnknown(_)
    ));
    assert_eq!(success.achieved(), None);
}

#[test]
fn ambiguous_rename_with_changed_retained_identity_remains_unknown() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .fault(SimOp::RenameReply, 0, io::ErrorKind::Other)
        .fault(SimOp::CaptureIdentity, 1, io::ErrorKind::PermissionDenied)
        .build();

    let success = run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("failed retained-identity revalidation must remain publication-unknown");

    assert!(matches!(
        success.outcome(),
        CommitOutcome::PublicationUnknown(_)
    ));
    assert_eq!(success.achieved(), None);
    assert!(!operation_labels(&fs).contains(&SimOp::FlushPublication));
}

#[test]
fn reconciled_ambiguous_rename_removes_an_exact_residual_temporary() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .fault(SimOp::RenameReply, 0, io::ErrorKind::Other)
        .mutate_before(
            SimOp::ObserveIdentity,
            0,
            NamespaceMutation::restore_renamed_temporary("/", DEST),
        )
        .build();

    let success = run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("an exact residual link must be finalized");

    assert_eq!(success.outcome(), CommitOutcome::Published);
    assert_eq!(success.cleanup(), CleanupState::Complete);
    assert_eq!(
        fs.final_snapshot().live_listing().len(),
        1,
        "exact residual temporary must be removed"
    );
}

#[test]
fn reconciled_ambiguous_rename_never_unlinks_a_changed_residual_temporary() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .fault(SimOp::RenameReply, 0, io::ErrorKind::Other)
        .mutate_before(
            SimOp::ObserveIdentity,
            0,
            NamespaceMutation::restore_renamed_temporary("/", DEST),
        )
        .mutate_before(
            SimOp::ObserveIdentity,
            1,
            NamespaceMutation::replace_temporary_entry("/", TemporaryArtifactEntryKind::Data),
        )
        .build();

    let success = run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("publication remains established despite unsafe residual cleanup");

    assert_eq!(success.outcome(), CommitOutcome::Published);
    assert!(success.cleanup().is_incomplete());
    assert_eq!(fs.final_snapshot().live_listing().len(), 2);
    assert!(
        operation_labels(&fs)
            .iter()
            .all(|operation| *operation != SimOp::Unlink),
        "changed residual must be rejected before unlink"
    );
}

#[test]
fn publication_unknown_preserves_primary_failure_when_exact_cleanup_fails() {
    let fs = SimFsBuilder::new()
        .fault(SimOp::Rename, 0, io::ErrorKind::ResourceBusy)
        .fault(SimOp::Unlink, 0, io::ErrorKind::PermissionDenied)
        .build();

    let success = run_protocol(
        &fs,
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("publication uncertainty must be returned with cleanup state");

    let CommitOutcome::PublicationUnknown(primary) = success.outcome() else {
        panic!("post-dispatch failure must remain unknown");
    };
    assert_eq!(primary.kind(), FailureKind::ResourceBusy);
    assert_eq!(
        success.cleanup().failure().map(|failure| failure.kind()),
        Some(FailureKind::PermissionDenied)
    );
}

#[test]
fn publication_unknown_never_unlinks_a_substituted_temporary() {
    let fs = SimFsBuilder::new()
        .fault(SimOp::Rename, 0, io::ErrorKind::Other)
        .mutate_before(
            SimOp::ObserveIdentity,
            1,
            NamespaceMutation::replace_temporary_entry("/", TemporaryArtifactEntryKind::Data),
        )
        .build();

    let success = run_protocol(
        &fs,
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("ambiguous publication must retain independent cleanup state");

    assert!(matches!(
        success.outcome(),
        CommitOutcome::PublicationUnknown(_)
    ));
    assert!(success.cleanup().is_incomplete());
    assert!(
        !operation_labels(&fs).contains(&SimOp::Unlink),
        "foreign temporary entry must be rejected before unlink"
    );
    assert_eq!(
        fs.final_snapshot().live_listing().len(),
        1,
        "the substituted entry must remain untouched"
    );
}

#[test]
fn windows_style_unknown_cleanup_closes_without_disposition() {
    let fs = SimFsBuilder::new()
        .without_ambiguous_exact_cleanup()
        .fault(SimOp::Rename, 0, io::ErrorKind::Other)
        .build();

    let success = run_protocol(
        &fs,
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("unknown state must close the retained handle");

    assert!(matches!(
        success.outcome(),
        CommitOutcome::PublicationUnknown(_)
    ));
    assert_eq!(success.cleanup(), CleanupState::Incomplete(None));
    let operations = operation_labels(&fs);
    assert_eq!(
        operations
            .iter()
            .filter(|operation| **operation == SimOp::Close)
            .count(),
        1
    );
    assert!(!operations.contains(&SimOp::Unlink));
    assert_eq!(
        fs.final_snapshot().live_listing().len(),
        1,
        "close-only cleanup must retain the recognizable temporary"
    );
}

#[test]
fn windows_style_reconciled_rename_closes_only_after_the_barrier() {
    let fs = SimFsBuilder::new()
        .without_ambiguous_exact_cleanup()
        .preexisting_destination(DEST, OLD)
        .fault(SimOp::RenameReply, 0, io::ErrorKind::Other)
        .build();

    let success = run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("destination identity positively establishes publication");

    assert_eq!(success.outcome(), CommitOutcome::Published);
    assert_eq!(success.cleanup(), CleanupState::Incomplete(None));
    let operations = operation_labels(&fs);
    assert!(!operations.contains(&SimOp::Unlink));
    assert_eq!(
        operations
            .iter()
            .filter(|operation| **operation == SimOp::Close)
            .count(),
        1
    );
    let barrier = operations
        .iter()
        .position(|operation| *operation == SimOp::FlushPublication)
        .expect("reconciled publication must run its requested barrier");
    let close = operations
        .iter()
        .position(|operation| *operation == SimOp::Close)
        .expect("retained handle must close");
    assert!(barrier < close, "the retained handle must span the barrier");
    assert_eq!(
        fs.final_snapshot()
            .live_listing()
            .get(DEST)
            .map(|state| state.bytes.as_slice()),
        Some(NEW)
    );
}

#[test]
fn windows_style_reconciled_rename_never_unlinks_an_exact_residual_name() {
    let fs = SimFsBuilder::new()
        .without_ambiguous_exact_cleanup()
        .preexisting_destination(DEST, OLD)
        .fault(SimOp::RenameReply, 0, io::ErrorKind::Other)
        .mutate_before(
            SimOp::ObserveIdentity,
            0,
            NamespaceMutation::restore_renamed_temporary("/", DEST),
        )
        .build();

    let success = run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("destination identity positively establishes publication");

    assert_eq!(success.outcome(), CommitOutcome::Published);
    assert_eq!(success.cleanup(), CleanupState::Incomplete(None));
    let operations = operation_labels(&fs);
    assert!(!operations.contains(&SimOp::Unlink));
    assert_eq!(
        operations
            .iter()
            .filter(|operation| **operation == SimOp::Close)
            .count(),
        1
    );
    assert_eq!(
        fs.final_snapshot().live_listing().len(),
        2,
        "close-only policy must leave the exact residual for maintenance"
    );
}

#[test]
fn dir_flush_failure_reports_published_durability_unknown() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .fault(SimOp::FlushPublication, 0, io::ErrorKind::Other)
        .build();
    let success = run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("a published transaction must not report failure");

    assert!(matches!(
        success.outcome(),
        CommitOutcome::PublishedDurabilityUnknown(_)
    ));
    assert_eq!(
        success.achieved(),
        Some(AchievedSyncLevel::FileSynchronized)
    );
    // The publication is visible live even though durability is unknown.
    let live = fs.final_snapshot().live_listing();
    assert_eq!(
        live.get(DEST).map(|state| state.bytes.as_slice()),
        Some(NEW)
    );
    assert_crash_contract(
        &fs.snapshots(),
        Some((OLD, OLD_BASIC_PERMISSIONS)),
        OLD_BASIC_PERMISSIONS,
    );
}

#[test]
fn create_mode_detects_an_existing_destination_without_touching_it() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .build();
    let success = run_protocol(
        &fs,
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("commit must succeed");

    assert_eq!(success.outcome(), CommitOutcome::DestinationExists);
    let live = fs.final_snapshot().live_listing();
    assert_eq!(
        live.get(DEST).map(|state| state.bytes.as_slice()),
        Some(OLD)
    );
    assert_eq!(live.len(), 1, "staged temporary must be cleaned up");
    let operations = operation_labels(&fs);
    assert!(
        !operations.contains(&SimOp::Rename),
        "occupied create destination must prevent rename dispatch"
    );
    assert!(
        !operations.contains(&SimOp::HardLink),
        "occupied create destination must prevent fallback dispatch"
    );
}

#[test]
fn create_mode_treats_an_existing_leaf_directory_as_occupied() {
    let fs = SimFsBuilder::new().preexisting_directory(DEST).build();

    let success = run_protocol(
        &fs,
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("leaf directory collision must complete without publication");

    assert_eq!(success.outcome(), CommitOutcome::DestinationExists);
    let operations = operation_labels(&fs);
    assert!(!operations.contains(&SimOp::Rename));
    assert!(!operations.contains(&SimOp::HardLink));
}

#[test]
fn create_mode_treats_an_existing_leaf_directory_link_as_occupied() {
    let fs = SimFsBuilder::new()
        .preexisting_directory("target")
        .preexisting_directory_link(DEST, "/target")
        .build();

    let success = run_protocol(
        &fs,
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("leaf reparse or symlink collision must complete without publication");

    assert_eq!(success.outcome(), CommitOutcome::DestinationExists);
    let operations = operation_labels(&fs);
    assert!(!operations.contains(&SimOp::Rename));
    assert!(!operations.contains(&SimOp::HardLink));
}

#[test]
fn create_mode_reobserves_destination_before_a_rename_retry() {
    let fs = SimFsBuilder::new()
        .fault(SimOp::PrepareRename, 0, io::ErrorKind::ResourceBusy)
        .mutate_before(
            SimOp::ObserveIdentity,
            1,
            NamespaceMutation::create_file("/", DEST),
        )
        .build();

    let success = run_protocol(
        &fs,
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("a destination appearing before retry must be a collision");

    assert_eq!(success.outcome(), CommitOutcome::DestinationExists);
    let operations = operation_labels(&fs);
    assert_eq!(
        operations
            .iter()
            .filter(|operation| **operation == SimOp::PrepareRename)
            .count(),
        1,
        "the occupied retry preflight must prevent another attempt"
    );
    assert!(!operations.contains(&SimOp::Rename));
    assert!(!operations.contains(&SimOp::HardLink));
}

#[test]
fn create_mode_reobserves_destination_before_hard_link_fallback() {
    let fs = SimFsBuilder::new()
        .without_exclusive_rename()
        .mutate_before(
            SimOp::ObserveIdentity,
            1,
            NamespaceMutation::create_file("/", DEST),
        )
        .build();

    let success = run_protocol(
        &fs,
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("a destination appearing before fallback must be a collision");

    assert_eq!(success.outcome(), CommitOutcome::DestinationExists);
    let operations = operation_labels(&fs);
    assert!(!operations.contains(&SimOp::Rename));
    assert!(
        !operations.contains(&SimOp::HardLink),
        "occupied fallback preflight must prevent hard-link dispatch"
    );
}

#[test]
fn destination_exists_retains_failed_staged_cleanup() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .fault(SimOp::Unlink, 0, io::ErrorKind::PermissionDenied)
        .build();
    let success = run_protocol(
        &fs,
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("commit must complete");

    assert_eq!(success.outcome(), CommitOutcome::DestinationExists);
    assert_eq!(
        success
            .cleanup()
            .failure()
            .expect("cleanup failure must be retained")
            .kind(),
        FailureKind::PermissionDenied,
    );
    assert_eq!(
        fs.final_snapshot()
            .live_listing()
            .get(DEST)
            .map(|state| state.bytes.as_slice()),
        Some(OLD),
    );
}

#[test]
fn hard_link_destination_exists_retains_failed_staged_cleanup() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .without_exclusive_rename()
        .fault(SimOp::Unlink, 0, io::ErrorKind::PermissionDenied)
        .build();
    let success = run_protocol(
        &fs,
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("commit must complete");

    assert_eq!(success.outcome(), CommitOutcome::DestinationExists);
    assert_eq!(
        success
            .cleanup()
            .failure()
            .expect("cleanup failure must be retained")
            .kind(),
        FailureKind::PermissionDenied,
    );
}

#[test]
fn create_mode_falls_back_to_hard_link_and_stays_crash_safe() {
    let fs = SimFsBuilder::new().without_exclusive_rename().build();
    let success = run_protocol(
        &fs,
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("commit must succeed");

    assert_eq!(success.outcome(), CommitOutcome::Published);
    let live = fs.final_snapshot().live_listing();
    assert_eq!(
        live.get(DEST).map(|state| state.bytes.as_slice()),
        Some(NEW)
    );
    assert_eq!(live.len(), 1, "temporary link must be cleaned up");
    assert_crash_contract(&fs.snapshots(), None, OWNER_ONLY_BASIC_PERMISSIONS);
}

#[test]
fn hard_link_fallback_rejects_a_substituted_source_before_dispatch() {
    let fs = SimFsBuilder::new()
        .without_exclusive_rename()
        .mutate_before(
            SimOp::PrepareHardLink,
            0,
            NamespaceMutation::replace_temporary_entry("/", TemporaryArtifactEntryKind::Data),
        )
        .build();

    let error = run_protocol(
        &fs,
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect_err("fallback must prove the source still names the retained staged file");

    assert_eq!(error.operation(), Operation::HardLink);
    assert!(
        !operation_labels(&fs).contains(&SimOp::HardLink),
        "source substitution must be rejected before dispatch"
    );
}

#[test]
fn rename_rejects_a_substituted_source_before_dispatch() {
    let fs = SimFsBuilder::new()
        .mutate_before(
            SimOp::PrepareRename,
            0,
            NamespaceMutation::replace_temporary_entry("/", TemporaryArtifactEntryKind::Data),
        )
        .build();

    let error = run_protocol(
        &fs,
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect_err("rename must prove the source still names the retained staged file");

    assert_eq!(error.operation(), Operation::Rename);
    assert!(error.cleanup_incomplete());
    assert!(
        !operation_labels(&fs).contains(&SimOp::Rename),
        "source substitution must be rejected before rename dispatch"
    );
    assert!(
        !operation_labels(&fs).contains(&SimOp::Unlink),
        "cleanup must not unlink the substituted entry"
    );
}

#[test]
fn hard_link_cleanup_failure_never_masks_unknown_durability() {
    let fs = SimFsBuilder::new()
        .without_exclusive_rename()
        .fault(SimOp::Unlink, 0, io::ErrorKind::PermissionDenied)
        .fault(SimOp::FlushPublication, 0, io::ErrorKind::Other)
        .build();
    let success = run_protocol(
        &fs,
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("publication already succeeded");

    assert!(matches!(
        success.outcome(),
        CommitOutcome::PublishedDurabilityUnknown(_)
    ));
    assert_eq!(
        success.achieved(),
        Some(AchievedSyncLevel::FileSynchronized)
    );
    let live = fs.final_snapshot().live_listing();
    assert_eq!(
        live.get(DEST).map(|state| state.bytes.as_slice()),
        Some(NEW)
    );
    assert_eq!(live.len(), 2, "failed cleanup leaves one sweepable link");
}

#[test]
fn create_mode_without_any_exclusive_primitive_reports_unsupported() {
    let fs = SimFsBuilder::new()
        .without_exclusive_rename()
        .without_hard_link()
        .build();
    let error = run_protocol(
        &fs,
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect_err("no exclusive primitive must fail the commit");

    assert_eq!(error.operation(), Operation::HardLink);
    assert_eq!(error.failure().kind(), FailureKind::Unsupported);
    assert!(fs.final_snapshot().live_listing().is_empty());
}

#[test]
fn transient_rename_interference_is_retried_within_bounds() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .fault(SimOp::PrepareRename, 0, io::ErrorKind::ResourceBusy)
        .fault(SimOp::PrepareRename, 1, io::ErrorKind::ResourceBusy)
        .build();
    let success = run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("bounded retries must absorb transient interference");

    assert_eq!(success.outcome(), CommitOutcome::Published);
    let live = fs.final_snapshot().live_listing();
    assert_eq!(
        live.get(DEST).map(|state| state.bytes.as_slice()),
        Some(NEW)
    );
}

#[test]
fn persistent_rename_interference_fails_after_the_retry_budget() {
    let mut builder = SimFsBuilder::new().preexisting_destination(DEST, OLD);
    for occurrence in 0..16 {
        builder = builder.fault(
            SimOp::PrepareRename,
            occurrence,
            io::ErrorKind::ResourceBusy,
        );
    }
    let fs = builder.build();
    let error = run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect_err("persistent interference must eventually fail");

    assert_eq!(error.operation(), Operation::Rename);
    let live = fs.final_snapshot().live_listing();
    assert_eq!(
        live.get(DEST).map(|state| state.bytes.as_slice()),
        Some(OLD)
    );
    assert_eq!(live.len(), 1, "staged temporary must be cleaned up");
}

#[test]
fn unsupported_file_flush_fails_before_publication() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .file_flush_support(crate::simfs::SimFlushSupport::Unsupported)
        .build();
    let error = run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect_err("strict durability must reject unsupported flushes");

    assert_eq!(error.operation(), Operation::FlushFile);
    assert_eq!(error.failure().kind(), FailureKind::DurabilityUnavailable);
    let live = fs.final_snapshot().live_listing();
    assert_eq!(
        live.get(DEST).map(|state| state.bytes.as_slice()),
        Some(OLD)
    );
}

#[test]
fn primary_failure_remains_authoritative_when_cleanup_also_fails() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .fault(SimOp::FlushFile, 0, io::ErrorKind::StorageFull)
        .fault(SimOp::Unlink, 0, io::ErrorKind::PermissionDenied)
        .build();
    let error = run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect_err("file flush must remain the primary failure");

    assert_eq!(error.operation(), Operation::FlushFile);
    assert_eq!(error.failure().kind(), FailureKind::StorageFull);
    assert!(error.cleanup_incomplete());
    assert_eq!(
        fs.final_snapshot()
            .live_listing()
            .get(DEST)
            .map(|state| state.bytes.as_slice()),
        Some(OLD),
    );
}

#[test]
fn degraded_file_flush_fails_before_publication() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .file_flush_support(crate::simfs::SimFlushSupport::Degraded)
        .build();
    let error = run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect_err("strict durability must reject degraded flushes");

    assert_eq!(error.operation(), Operation::FlushFile);
    assert_eq!(error.failure().kind(), FailureKind::DurabilityUnavailable);
}

#[test]
fn file_flush_capability_is_independent_and_fails_before_publication() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .file_flush_support(crate::simfs::SimFlushSupport::Unsupported)
        .build();
    let error = run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect_err("unsupported file synchronization must fail before publication");

    assert_eq!(error.operation(), Operation::FlushFile);
    let operations = operation_labels(&fs);
    assert!(operations.contains(&SimOp::FlushFile));
    assert!(!operations.contains(&SimOp::Rename));
    assert!(!operations.contains(&SimOp::FlushPublication));
}

#[test]
fn created_parent_flush_capability_is_independent_and_fails_before_staging() {
    let fs = SimFsBuilder::new()
        .created_parent_flush_support(crate::simfs::SimFlushSupport::Unsupported)
        .build();
    let error = run_protocol_at(
        &fs,
        "/vault/accounts/vault.kdbx",
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect_err("unsupported parent synchronization must fail before staging");

    assert_eq!(error.operation(), Operation::FlushParent);
    let operations = operation_labels(&fs);
    assert!(operations.contains(&SimOp::FlushDirectory));
    assert!(!operations.contains(&SimOp::CreateFileAt));
    assert!(!operations.contains(&SimOp::FlushFile));
    assert!(!operations.contains(&SimOp::FlushPublication));
}

#[test]
fn publication_flush_capability_is_independent_and_degrades_after_publication() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .publication_flush_support(crate::simfs::SimFlushSupport::Unsupported)
        .build();
    let success = run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("unsupported publication synchronization is discovered after rename");

    assert!(matches!(
        success.outcome(),
        CommitOutcome::PublishedDurabilityUnknown(_)
    ));
    assert_eq!(
        success.achieved(),
        Some(AchievedSyncLevel::FileSynchronized)
    );

    let operations = operation_labels(&fs);
    let file_flush = operations
        .iter()
        .position(|operation| *operation == SimOp::FlushFile)
        .expect("file synchronization must be attempted");
    let rename = operations
        .iter()
        .position(|operation| *operation == SimOp::Rename)
        .expect("publication must happen");
    let publication_flush = operations
        .iter()
        .position(|operation| *operation == SimOp::FlushPublication)
        .expect("publication synchronization must be attempted");
    assert!(file_flush < rename);
    assert!(rename < publication_flush);

    let live = fs.final_snapshot().live_listing();
    assert_eq!(
        live.get(DEST).map(|state| state.bytes.as_slice()),
        Some(NEW)
    );
    let durable = fs.final_snapshot().durable_projection();
    assert_eq!(
        durable.get(DEST).map(|state| state.bytes.as_slice()),
        Some(OLD),
        "without publication synchronization the durable namespace may remain old"
    );
}

#[test]
fn operation_log_records_faulted_attempts_with_their_occurrence() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .fault(SimOp::FlushFile, 0, io::ErrorKind::Other)
        .build();
    run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect_err("the injected file flush fault must fail the commit");

    let attempts = fs
        .operations()
        .into_iter()
        .filter(|operation| operation.op == SimOp::FlushFile)
        .collect::<Vec<_>>();
    assert_eq!(attempts.len(), 1);
    assert_eq!(attempts[0].occurrence, 0);
}

#[test]
fn directory_link_can_be_followed_and_is_rejected_by_a_no_follow_open() {
    let fs = SimFsBuilder::new()
        .preexisting_directory("real")
        .preexisting_directory_link("alias", "/real")
        .build();
    let root = fs.open_root(Path::new("/")).expect("root must open");

    let followed = fs
        .open_dir_at(&root, "alias", true)
        .expect("follow policy must resolve the directory link");
    let rejected = fs
        .open_dir_at(&root, "alias", false)
        .expect_err("reject policy must not traverse the directory link");
    assert_eq!(rejected.kind(), io::ErrorKind::InvalidInput);

    let mut probe = fs
        .create_file_at(&followed, "probe", true)
        .expect("the followed handle must address the target directory");
    fs.write_all(&mut probe, b"pinned")
        .expect("probe write must succeed");
    let live = fs.final_snapshot().live_listing();
    assert_eq!(
        live.get("real/probe").map(|state| state.bytes.as_slice()),
        Some(b"pinned".as_slice())
    );
    assert_eq!(
        live.get("alias/probe").map(|state| state.bytes.as_slice()),
        Some(b"pinned".as_slice())
    );
}

#[test]
fn retained_directory_handle_survives_an_ancestor_rename() {
    let fs = SimFsBuilder::new()
        .preexisting_directory("vault")
        .mutate_before(
            SimOp::CreateFileAt,
            0,
            NamespaceMutation::rename_entry("/", "vault", "moved"),
        )
        .build();
    let success = run_protocol_at(
        &fs,
        "/vault/vault.kdbx",
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("the transaction must remain bound to the opened directory");

    assert_eq!(success.outcome(), CommitOutcome::Published);
    let live = fs.final_snapshot().live_listing();
    assert!(!live.contains_key("vault/vault.kdbx"));
    assert_eq!(
        live.get("moved/vault.kdbx")
            .map(|state| state.bytes.as_slice()),
        Some(NEW)
    );
}

#[test]
fn concurrent_directory_create_is_reopened_after_already_exists() {
    let fs = SimFsBuilder::new()
        .mutate_before(
            SimOp::CreateDirAt,
            0,
            NamespaceMutation::create_directory("/", "vault"),
        )
        .build();
    let success = run_protocol_at(
        &fs,
        "/vault/vault.kdbx",
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("the concurrently-created real directory must be reopened");

    assert_eq!(success.outcome(), CommitOutcome::Published);
    let opens = fs
        .operations()
        .into_iter()
        .filter(|operation| operation.op == SimOp::OpenDirAt)
        .map(|operation| operation.occurrence)
        .collect::<Vec<_>>();
    assert_eq!(opens, [0, 1], "missing component must be reopened once");
    assert_eq!(
        fs.final_snapshot()
            .live_listing()
            .get("vault/vault.kdbx")
            .map(|state| state.bytes.as_slice()),
        Some(NEW)
    );
}

#[test]
fn concurrent_directory_link_is_rejected_during_the_already_exists_reopen() {
    let fs = SimFsBuilder::new()
        .preexisting_directory("outside")
        .mutate_before(
            SimOp::CreateDirAt,
            0,
            NamespaceMutation::create_directory_link("/", "vault", "/outside"),
        )
        .build();
    let error = run_protocol_at(
        &fs,
        "/vault/vault.kdbx",
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect_err("the no-follow reopen must reject a concurrently-created link");

    assert_eq!(error.operation(), Operation::PrepareParent);
    assert_eq!(error.failure().kind(), FailureKind::InvalidInput);
    assert!(
        !fs.operations()
            .iter()
            .any(|operation| operation.op == SimOp::CreateFileAt),
        "link substitution must fail before staging"
    );
}

#[test]
fn durable_commit_persists_every_new_parent_component() {
    const NESTED_DESTINATION: &str = "/vault/accounts/current/vault.kdbx";
    const NESTED_KEY: &str = "vault/accounts/current/vault.kdbx";

    let fs = SimFsBuilder::new().build();
    let success = run_protocol_at(
        &fs,
        NESTED_DESTINATION,
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("nested durable commit must succeed");

    assert_eq!(success.outcome(), CommitOutcome::Published);
    assert_eq!(
        success.achieved(),
        Some(AchievedSyncLevel::FileAndNamespaceSynchronized)
    );
    let durable = fs.final_snapshot().durable_projection();
    assert_eq!(
        durable.get(NESTED_KEY).map(|state| state.bytes.as_slice()),
        Some(NEW)
    );
}

#[test]
fn parent_flush_failure_happens_before_staging_or_publication() {
    let fs = SimFsBuilder::new()
        .fault(SimOp::FlushDirectory, 1, io::ErrorKind::Other)
        .build();
    let error = run_protocol_at(
        &fs,
        "/vault/accounts/vault.kdbx",
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect_err("parent flush failure must abort begin");

    assert_eq!(error.operation(), Operation::FlushParent);
    assert!(
        fs.snapshots()
            .iter()
            .all(|snapshot| snapshot.label != SimOp::CreateFileAt),
        "parent durability must be established before staging"
    );
    assert!(fs.final_snapshot().live_listing().is_empty());
}

#[test]
fn require_existing_rejects_a_missing_parent_before_staging() {
    let fs = SimFsBuilder::new().build();
    let mut options = options(
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    );
    options.parent_directory = ParentDirectoryPolicy::RequireExisting;
    let error =
        match AtomicWriteTxn::begin(fs.clone(), Path::new("/vault/accounts/vault.kdbx"), options) {
            Ok(_) => panic!("the explicit existing-parent policy must not create directories"),
            Err(error) => error,
        };

    assert_eq!(error.operation(), Operation::PrepareParent);
    assert_eq!(error.failure().kind(), FailureKind::NotFound);
    assert!(
        fs.snapshots()
            .iter()
            .all(|snapshot| snapshot.label != SimOp::CreateFileAt),
        "missing required parents must fail before staging"
    );
    assert!(fs.final_snapshot().live_listing().is_empty());
}

#[test]
fn abort_removes_the_staged_temporary() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .build();
    let mut txn = AtomicWriteTxn::begin(
        fs.clone(),
        Path::new(DEST_PATH),
        options(
            replace_preserving_publication(),
            SyncLevel::FileAndNamespaceSynchronized,
        ),
    )
    .expect("begin must succeed");
    txn.write(NEW).expect("write must succeed");
    txn.abort().expect("abort must remove the staged temporary");

    let live = fs.final_snapshot().live_listing();
    assert_eq!(
        live.get(DEST).map(|state| state.bytes.as_slice()),
        Some(OLD)
    );
    assert_eq!(live.len(), 1, "staged temporary must be removed by abort");
    let operations = operation_labels(&fs);
    let unlink = operations
        .iter()
        .rposition(|operation| *operation == SimOp::Unlink)
        .expect("abort must unlink the staged name");
    let close = operations
        .iter()
        .rposition(|operation| *operation == SimOp::Close)
        .expect("abort must close the staged file");
    assert!(
        unlink < close,
        "the staged lease must remain held until its name is absent",
    );
}

#[test]
fn dropping_an_uncommitted_transaction_cleans_up() {
    let fs = SimFsBuilder::new().build();
    {
        let mut txn = AtomicWriteTxn::begin(
            fs.clone(),
            Path::new(DEST_PATH),
            options(
                create_publication(),
                SyncLevel::FileAndNamespaceSynchronized,
            ),
        )
        .expect("begin must succeed");
        txn.write(NEW).expect("write must succeed");
        // Dropped without commit or abort, as after a bridge panic.
    }
    assert!(fs.final_snapshot().live_listing().is_empty());
}

#[test]
fn producer_lease_failure_cleans_up_before_begin_returns() {
    let fs = SimFsBuilder::new()
        .fault(
            SimOp::ProbeDirectoryLeaseExclusive,
            0,
            io::ErrorKind::PermissionDenied,
        )
        .build();

    let error = match AtomicWriteTxn::begin(
        fs.clone(),
        Path::new(DEST_PATH),
        options(
            create_publication(),
            SyncLevel::FileAndNamespaceSynchronized,
        ),
    ) {
        Ok(_) => panic!("injected producer lease failure must reject begin"),
        Err(error) => error,
    };

    assert_eq!(error.operation(), Operation::CreateStaged);
    assert_eq!(error.failure().kind(), FailureKind::PermissionDenied);
    assert!(fs.final_snapshot().live_listing().is_empty());
    assert!(
        fs.snapshots()
            .iter()
            .all(|snapshot| snapshot.label != SimOp::WriteAll),
        "lease failure must happen before any staged bytes are written"
    );
}

#[test]
fn unsupported_producer_leases_do_not_break_atomic_writes() {
    let fs = SimFsBuilder::new().without_file_locks().build();

    let success = run_protocol(
        &fs,
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("unsupported leases are a documented writer fallback");

    assert_eq!(success.outcome(), CommitOutcome::Published);
    assert_eq!(
        fs.final_snapshot()
            .live_listing()
            .get(DEST)
            .map(|state| state.bytes.as_slice()),
        Some(NEW)
    );
}

#[test]
fn sidecar_fallback_creates_lease_before_data_and_removes_both() {
    let fs = SimFsBuilder::new().without_directory_locks().build();

    run_protocol(
        &fs,
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("regular-file sidecar lease must provide the fallback");

    let observed_sidecar_only = fs.snapshots().into_iter().any(|snapshot| {
        let listing = snapshot.live_listing();
        listing.len() == 1
            && listing.keys().any(|name| {
                parse_temporary_artifact_name(name).is_some_and(|artifact| {
                    artifact.protocol == TemporaryArtifactProtocol::SidecarLeaseV1
                        && artifact.entry_kind == TemporaryArtifactEntryKind::Lease
                })
            })
    });
    assert!(
        observed_sidecar_only,
        "sidecar-only must be an observable pre-data crash state"
    );
    assert_eq!(
        fs.final_snapshot()
            .live_listing()
            .get(DEST)
            .map(|state| state.bytes.as_slice()),
        Some(NEW)
    );
}

#[test]
fn forced_v1s_named_scratch_removes_data_then_sidecar_before_releasing_lease() {
    let fs = SimFsBuilder::new().without_directory_locks().build();
    let dir = fs.open_root(Path::new("/")).expect("root must open");
    let CreatedStaged {
        name,
        file: mut staged,
    } = fs
        .create_staged_at(&dir, TemporaryFileRole::Scratch, true)
        .expect("forced v1s scratch must be created");
    let parsed = parse_temporary_artifact_name(&name).expect("scratch name must parse");
    assert_eq!(parsed.protocol, TemporaryArtifactProtocol::SidecarLeaseV1);
    assert_eq!(parsed.role, TemporaryFileRole::Scratch);

    fs.finalize_staged_after_publication(
        &dir,
        &name,
        &mut staged,
        StagedNameResidual::PresentAfterHardLink,
    )
    .expect("scratch names must be removed");
    fs.close(staged).expect("pathless scratch must close");

    assert!(fs.final_snapshot().live_listing().is_empty());
    let operations = operation_labels(&fs);
    let data_revalidation = operations
        .iter()
        .position(|operation| *operation == SimOp::RevalidateStagedData)
        .expect("data identity must be checked");
    let first_unlink = operations
        .iter()
        .position(|operation| *operation == SimOp::Unlink)
        .expect("data name must be removed");
    let sidecar_revalidation = operations
        .iter()
        .rposition(|operation| *operation == SimOp::RevalidateSidecar)
        .expect("sidecar identity must be checked again");
    let last_unlink = operations
        .iter()
        .rposition(|operation| *operation == SimOp::Unlink)
        .expect("sidecar name must be removed");
    let release = operations
        .iter()
        .position(|operation| *operation == SimOp::ReleaseLease)
        .expect("sidecar lease must be released");
    assert!(
        data_revalidation < first_unlink
            && first_unlink < sidecar_revalidation
            && sidecar_revalidation < last_unlink
            && last_unlink < release
    );
}

#[test]
fn forced_v1s_named_scratch_cleanup_faults_preserve_safe_partitions_and_release_order() {
    for (fault_occurrence, expected_data, expected_lease) in [(0, true, true), (1, false, true)] {
        let fs = SimFsBuilder::new()
            .without_directory_locks()
            .fault(
                SimOp::Unlink,
                fault_occurrence,
                io::ErrorKind::PermissionDenied,
            )
            .build();
        let dir = fs.open_root(Path::new("/")).expect("root must open");
        let CreatedStaged {
            name,
            file: mut staged,
        } = fs
            .create_staged_at(&dir, TemporaryFileRole::Scratch, true)
            .expect("forced v1s scratch must be created");

        fs.finalize_staged_after_publication(
            &dir,
            &name,
            &mut staged,
            StagedNameResidual::PresentAfterHardLink,
        )
        .expect_err("injected cleanup fault must be reported");
        fs.close(staged)
            .expect("closing after cleanup failure must release the lease");

        let mut data = false;
        let mut lease = false;
        for name in fs.final_snapshot().live_listing().keys() {
            let parsed = parse_temporary_artifact_name(name).expect("name must stay canonical");
            assert_eq!(parsed.protocol, TemporaryArtifactProtocol::SidecarLeaseV1);
            match parsed.entry_kind {
                TemporaryArtifactEntryKind::Data => data = true,
                TemporaryArtifactEntryKind::Lease => lease = true,
            }
        }
        assert_eq!((data, lease), (expected_data, expected_lease));
        let operations = operation_labels(&fs);
        let failed_unlink = operations
            .iter()
            .rposition(|operation| *operation == SimOp::Unlink)
            .expect("cleanup must attempt an unlink");
        let release = operations
            .iter()
            .rposition(|operation| *operation == SimOp::ReleaseLease)
            .expect("close must release the lease");
        assert!(failed_unlink < release);
    }

    let fs = SimFsBuilder::new()
        .without_directory_locks()
        .fault(SimOp::ReleaseLease, 0, io::ErrorKind::Other)
        .build();
    let dir = fs.open_root(Path::new("/")).expect("root must open");
    let CreatedStaged {
        name,
        file: mut staged,
    } = fs
        .create_staged_at(&dir, TemporaryFileRole::Scratch, true)
        .expect("forced v1s scratch must be created");
    fs.finalize_staged_after_publication(
        &dir,
        &name,
        &mut staged,
        StagedNameResidual::PresentAfterHardLink,
    )
    .expect_err("lease close fault must remain observable");
    fs.close(staged)
        .expect("a consumed failed close must not be retried");
    assert!(fs.final_snapshot().live_listing().is_empty());
    assert_eq!(
        operation_labels(&fs)
            .iter()
            .filter(|operation| **operation == SimOp::ReleaseLease)
            .count(),
        1,
        "a consumed lease close must be attempted exactly once"
    );
}

#[test]
fn forced_v1s_named_scratch_crash_projections_never_contain_data_only() {
    let fs = SimFsBuilder::new().without_directory_locks().build();
    let dir = fs.open_root(Path::new("/")).expect("root must open");
    let CreatedStaged {
        name,
        file: mut staged,
    } = fs
        .create_staged_at(&dir, TemporaryFileRole::Scratch, true)
        .expect("forced v1s scratch must be created");
    fs.finalize_staged_after_publication(
        &dir,
        &name,
        &mut staged,
        StagedNameResidual::PresentAfterHardLink,
    )
    .expect("scratch pair cleanup must succeed");
    fs.close(staged).expect("pathless scratch must close");

    for snapshot in fs.snapshots() {
        for projection in snapshot.crash_projections() {
            let mut pairs = HashMap::<String, (bool, bool)>::new();
            for candidate in projection.keys() {
                let Some(parsed) = parse_temporary_artifact_name(candidate) else {
                    continue;
                };
                if parsed.protocol != TemporaryArtifactProtocol::SidecarLeaseV1
                    || parsed.role != TemporaryFileRole::Scratch
                {
                    continue;
                }
                let pair = pairs.entry(parsed.nonce.to_owned()).or_default();
                match parsed.entry_kind {
                    TemporaryArtifactEntryKind::Data => pair.0 = true,
                    TemporaryArtifactEntryKind::Lease => pair.1 = true,
                }
            }
            assert!(
                pairs
                    .values()
                    .all(|(data_present, lease_present)| !data_present || *lease_present),
                "a cut at {:?} projected a v1s scratch data name without its sidecar",
                snapshot.label,
            );
        }
    }
}

#[test]
fn competing_shared_writer_does_not_block_directory_lease_creation() {
    let fs = SimFsBuilder::new()
        .with_competing_directory_writer()
        .build();

    run_protocol(
        &fs,
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("EX-busy followed by SH acquisition must allow another writer");

    let operations = fs.operations();
    let probe = operations
        .iter()
        .position(|operation| operation.op == SimOp::ProbeDirectoryLeaseExclusive)
        .expect("exclusive probe must be recorded");
    let shared = operations
        .iter()
        .position(|operation| operation.op == SimOp::AcquireDirectoryLeaseShared)
        .expect("shared acquisition must be recorded");
    assert!(probe < shared);
}

#[test]
fn active_exclusive_sweeper_returns_busy_before_data_creation() {
    let fs = SimFsBuilder::new().with_active_directory_sweeper().build();

    let error = match AtomicWriteTxn::begin(
        fs.clone(),
        Path::new(DEST_PATH),
        options(
            create_publication(),
            SyncLevel::FileAndNamespaceSynchronized,
        ),
    ) {
        Ok(_) => panic!("exclusive sweeper must block staged creation"),
        Err(error) => error,
    };

    assert_eq!(error.operation(), Operation::CreateStaged);
    assert_eq!(error.failure().kind(), FailureKind::ResourceBusy);
    assert!(fs.final_snapshot().live_listing().is_empty());
    assert!(
        fs.operations()
            .iter()
            .all(|operation| operation.op != SimOp::CreateFileAt)
    );
}

#[test]
fn substituted_sidecar_name_is_never_unlinked() {
    let fs = SimFsBuilder::new()
        .without_directory_locks()
        .mutate_before(
            SimOp::RevalidateSidecar,
            0,
            NamespaceMutation::replace_temporary_entry("/", TemporaryArtifactEntryKind::Lease),
        )
        .build();

    let error = match AtomicWriteTxn::begin(
        fs.clone(),
        Path::new(DEST_PATH),
        options(
            create_publication(),
            SyncLevel::FileAndNamespaceSynchronized,
        ),
    ) {
        Ok(_) => panic!("changed sidecar binding must reject begin"),
        Err(error) => error,
    };

    assert_eq!(error.operation(), Operation::CreateStaged);
    assert_eq!(error.failure().kind(), FailureKind::Other);
    assert!(error.cleanup_incomplete());
    let live = fs.final_snapshot().live_listing();
    assert_eq!(live.len(), 1, "the substituted lease name must remain");
    assert!(live.keys().all(|name| {
        parse_temporary_artifact_name(name).is_some_and(|artifact| {
            artifact.protocol == TemporaryArtifactProtocol::SidecarLeaseV1
                && artifact.entry_kind == TemporaryArtifactEntryKind::Lease
        })
    }));
}

#[test]
fn substituted_sidecar_name_is_never_unlinked_during_finalization() {
    let fs = SimFsBuilder::new()
        .without_directory_locks()
        .mutate_before(
            SimOp::RevalidateSidecar,
            1,
            NamespaceMutation::replace_temporary_entry("/", TemporaryArtifactEntryKind::Lease),
        )
        .build();

    let success = run_protocol(
        &fs,
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("publication must remain successful when final cleanup detects substitution");

    assert_eq!(success.outcome(), CommitOutcome::Published);
    assert_eq!(
        success.cleanup().failure().map(|failure| failure.kind()),
        Some(FailureKind::Other)
    );
    let live = fs.final_snapshot().live_listing();
    assert_eq!(
        live.get(DEST).map(|state| state.bytes.as_slice()),
        Some(NEW)
    );
    assert_eq!(live.len(), 2, "the substituted lease name must remain");
    assert!(
        live.keys()
            .filter(|name| name.as_str() != DEST)
            .all(|name| {
                parse_temporary_artifact_name(name).is_some_and(|artifact| {
                    artifact.protocol == TemporaryArtifactProtocol::SidecarLeaseV1
                        && artifact.entry_kind == TemporaryArtifactEntryKind::Lease
                })
            })
    );
    assert!(
        fs.operations()
            .iter()
            .all(|operation| operation.op != SimOp::Unlink),
        "finalization must reject the identity mismatch before unlink"
    );
}

#[test]
fn substituted_staged_data_name_is_never_unlinked_on_abort() {
    let fs = SimFsBuilder::new()
        .without_directory_locks()
        .mutate_before(
            SimOp::RevalidateStagedData,
            0,
            NamespaceMutation::replace_temporary_entry("/", TemporaryArtifactEntryKind::Data),
        )
        .build();
    let txn = AtomicWriteTxn::begin(
        fs.clone(),
        Path::new(DEST_PATH),
        options(
            create_publication(),
            SyncLevel::FileAndNamespaceSynchronized,
        ),
    )
    .expect("begin must create a sidecar/data pair");

    let error = txn
        .abort()
        .expect_err("identity substitution must prevent staged-name removal");

    assert_eq!(error.operation(), Operation::Cleanup);
    assert!(error.cleanup_incomplete());
    let live = fs.final_snapshot().live_listing();
    assert_eq!(
        live.len(),
        2,
        "both the substituted data name and its sidecar must remain"
    );
    assert!(live.keys().all(|name| {
        parse_temporary_artifact_name(name)
            .is_some_and(|artifact| artifact.protocol == TemporaryArtifactProtocol::SidecarLeaseV1)
    }));
    assert!(
        fs.operations()
            .iter()
            .all(|operation| operation.op != SimOp::Unlink),
        "identity mismatch must stop before any unlink attempt"
    );
}

#[test]
fn sidecar_create_to_lock_busy_leaves_sidecar_for_the_winning_sweeper() {
    let fs = SimFsBuilder::new()
        .without_directory_locks()
        .with_busy_sidecar_lock()
        .build();

    let error = match AtomicWriteTxn::begin(
        fs.clone(),
        Path::new(DEST_PATH),
        options(
            create_publication(),
            SyncLevel::FileAndNamespaceSynchronized,
        ),
    ) {
        Ok(_) => panic!("a lost create-to-lock race must reject begin"),
        Err(error) => error,
    };

    assert_eq!(error.operation(), Operation::CreateStaged);
    assert_eq!(error.failure().kind(), FailureKind::ResourceBusy);
    assert!(error.cleanup_incomplete());
    let live = fs.final_snapshot().live_listing();
    assert_eq!(live.len(), 1, "the winning sweeper owns the sidecar name");
    assert!(live.keys().all(|name| {
        parse_temporary_artifact_name(name).is_some_and(|artifact| {
            artifact.protocol == TemporaryArtifactProtocol::SidecarLeaseV1
                && artifact.entry_kind == TemporaryArtifactEntryKind::Lease
        })
    }));
    let operations = operation_labels(&fs);
    assert!(!operations.contains(&SimOp::Unlink));
    assert!(
        operations
            .iter()
            .position(|operation| *operation == SimOp::LockFile)
            < operations
                .iter()
                .position(|operation| *operation == SimOp::ReleaseLease)
    );
}

#[test]
fn paired_data_appearance_retries_only_after_sidecar_cleanup() {
    let fs = SimFsBuilder::new()
        .without_directory_locks()
        .with_paired_data_appearing_before_create()
        .build();

    let error = match AtomicWriteTxn::begin(
        fs.clone(),
        Path::new(DEST_PATH),
        options(
            create_publication(),
            SyncLevel::FileAndNamespaceSynchronized,
        ),
    ) {
        Ok(_) => panic!("persistent nonce interference must exhaust the retry budget"),
        Err(error) => error,
    };

    assert_eq!(error.operation(), Operation::CreateStaged);
    assert_eq!(error.failure().kind(), FailureKind::AlreadyExists);
    assert!(!error.cleanup_incomplete());
    assert!(fs.final_snapshot().live_listing().is_empty());
}

#[test]
fn unsupported_directory_and_sidecar_leases_fail_before_data_creation() {
    let fs = SimFsBuilder::new()
        .without_directory_locks()
        .without_file_locks()
        .build();

    let error = match AtomicWriteTxn::begin(
        fs.clone(),
        Path::new(DEST_PATH),
        options(
            create_publication(),
            SyncLevel::FileAndNamespaceSynchronized,
        ),
    ) {
        Ok(_) => panic!("writer must fail closed when neither lease protocol works"),
        Err(error) => error,
    };

    assert_eq!(error.operation(), Operation::CreateStaged);
    assert_eq!(error.failure().kind(), FailureKind::Unsupported);
    assert!(!error.cleanup_incomplete());
    assert!(fs.final_snapshot().live_listing().is_empty());
}

#[test]
fn sidecar_lock_failure_preserves_primary_and_marks_failed_cleanup() {
    let fs = SimFsBuilder::new()
        .without_directory_locks()
        .without_file_locks()
        .fault(SimOp::Unlink, 0, io::ErrorKind::PermissionDenied)
        .build();

    let error = match AtomicWriteTxn::begin(
        fs.clone(),
        Path::new(DEST_PATH),
        options(
            create_publication(),
            SyncLevel::FileAndNamespaceSynchronized,
        ),
    ) {
        Ok(_) => panic!("unsupported sidecar lock must reject begin"),
        Err(error) => error,
    };

    assert_eq!(error.operation(), Operation::CreateStaged);
    assert_eq!(error.failure().kind(), FailureKind::Unsupported);
    assert!(error.cleanup_incomplete());
    let listing = fs.final_snapshot().live_listing();
    assert_eq!(listing.len(), 1);
    assert!(listing.keys().all(|name| {
        parse_temporary_artifact_name(name).is_some_and(|artifact| {
            artifact.protocol == TemporaryArtifactProtocol::SidecarLeaseV1
                && artifact.entry_kind == TemporaryArtifactEntryKind::Lease
        })
    }));
}

#[test]
fn published_sidecar_cleanup_failure_is_reported_without_skipping_flush() {
    let fs = SimFsBuilder::new()
        .without_directory_locks()
        .fault(SimOp::Unlink, 0, io::ErrorKind::PermissionDenied)
        .build();

    let success = run_protocol(
        &fs,
        create_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("publication cleanup failure must not undo publication");

    assert_eq!(success.outcome(), CommitOutcome::Published);
    assert_eq!(
        success.cleanup().failure().map(|failure| failure.kind()),
        Some(FailureKind::PermissionDenied)
    );
    assert!(
        fs.operations()
            .iter()
            .any(|operation| operation.op == SimOp::FlushPublication),
        "published bytes must still receive their requested barrier"
    );
}

#[test]
fn write_failure_is_reported_and_abort_cleans_up() {
    let fs = SimFsBuilder::new()
        .fault(SimOp::WriteAll, 1, io::ErrorKind::StorageFull)
        .build();
    let mut txn = AtomicWriteTxn::begin(
        fs.clone(),
        Path::new(DEST_PATH),
        options(
            create_publication(),
            SyncLevel::FileAndNamespaceSynchronized,
        ),
    )
    .expect("begin must succeed");
    txn.write(&NEW[..4]).expect("first write must succeed");
    let error = txn
        .write(&NEW[4..])
        .expect_err("injected storage-full write must fail");
    assert_eq!(error.operation(), Operation::Write);
    assert_eq!(error.failure().kind(), FailureKind::StorageFull);
    txn.abort().expect("abort must remove the staged temporary");
    assert!(fs.final_snapshot().live_listing().is_empty());
}

#[test]
fn partial_write_failure_poisons_transaction_and_prevents_replay() {
    let fs = SimFsBuilder::new()
        .partial_write_fault(0, 4, io::ErrorKind::StorageFull)
        .build();
    let mut txn = AtomicWriteTxn::begin(
        fs.clone(),
        Path::new(DEST_PATH),
        options(
            create_publication(),
            SyncLevel::FileAndNamespaceSynchronized,
        ),
    )
    .expect("begin must succeed");

    let first = txn
        .write(NEW)
        .expect_err("partial storage-full write must fail");
    assert_eq!(first.operation(), Operation::Write);
    assert_eq!(first.failure().kind(), FailureKind::StorageFull);
    let live = fs.final_snapshot().live_listing();
    let staged = live
        .iter()
        .find(|(name, _)| {
            parse_temporary_artifact_name(name)
                .is_some_and(|artifact| artifact.entry_kind == TemporaryArtifactEntryKind::Data)
        })
        .map(|(_, state)| state)
        .expect("staged data must remain available for explicit cleanup");
    assert_eq!(staged.bytes, NEW[..4]);

    let repeated = txn
        .write(b"must not be replayed")
        .expect_err("poisoned write must remain failed");
    assert_eq!(repeated, first);
    assert_eq!(
        fs.operations()
            .iter()
            .filter(|operation| operation.op == SimOp::WriteAll)
            .count(),
        1,
        "poisoned write must not re-enter the filesystem"
    );

    txn.abort().expect("abort must remove the staged temporary");
    assert!(fs.final_snapshot().live_listing().is_empty());
}

#[test]
fn poisoned_commit_preserves_destination_and_skips_finalization() {
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .partial_write_fault(0, 5, io::ErrorKind::StorageFull)
        .build();
    let mut txn = AtomicWriteTxn::begin(
        fs.clone(),
        Path::new(DEST_PATH),
        options(
            replace_preserving_publication(),
            SyncLevel::FileAndNamespaceSynchronized,
        ),
    )
    .expect("begin must succeed");
    let write_error = txn
        .write(NEW)
        .expect_err("partial storage-full write must fail");

    let commit_error = txn
        .commit()
        .expect_err("poisoned transaction must not publish");
    assert_eq!(commit_error, write_error);
    let live = fs.final_snapshot().live_listing();
    assert_eq!(
        live.get(DEST).map(|state| state.bytes.as_slice()),
        Some(OLD)
    );
    assert_eq!(live.len(), 1, "staged temporary must be cleaned up");

    let operations = operation_labels(&fs);
    for forbidden in [
        SimOp::ApplyMetadata,
        SimOp::VerifyMetadata,
        SimOp::FlushFile,
        SimOp::CaptureIdentity,
        SimOp::ObserveIdentity,
        SimOp::PrepareRename,
        SimOp::Rename,
        SimOp::PrepareHardLink,
        SimOp::HardLink,
        SimOp::FlushPublication,
    ] {
        assert!(
            !operations.contains(&forbidden),
            "poisoned commit dispatched forbidden operation {forbidden:?}"
        );
    }
}

#[test]
fn poisoned_commit_preserves_primary_when_cleanup_fails() {
    let fs = SimFsBuilder::new()
        .partial_write_fault(0, 3, io::ErrorKind::StorageFull)
        .fault(SimOp::Unlink, 0, io::ErrorKind::PermissionDenied)
        .build();
    let mut txn = AtomicWriteTxn::begin(
        fs.clone(),
        Path::new(DEST_PATH),
        options(
            create_publication(),
            SyncLevel::FileAndNamespaceSynchronized,
        ),
    )
    .expect("begin must succeed");
    let write_error = txn
        .write(NEW)
        .expect_err("partial storage-full write must fail");

    let commit_error = txn
        .commit()
        .expect_err("poisoned commit cleanup must report failure");
    assert_eq!(commit_error.operation(), write_error.operation());
    assert_eq!(commit_error.failure(), write_error.failure());
    assert!(commit_error.cleanup_incomplete());
    assert!(
        fs.final_snapshot()
            .live_listing()
            .keys()
            .any(|name| parse_temporary_artifact_name(name).is_some()),
        "cleanup failure must leave a recognizable temporary"
    );
}

#[test]
fn caught_partial_write_panic_cannot_be_committed() {
    let fs = SimFsBuilder::new().partial_write_panic(0, 6).build();
    let mut txn = AtomicWriteTxn::begin(
        fs.clone(),
        Path::new(DEST_PATH),
        options(
            create_publication(),
            SyncLevel::FileAndNamespaceSynchronized,
        ),
    )
    .expect("begin must succeed");

    let panic = catch_unwind(AssertUnwindSafe(|| txn.write(NEW)));
    assert!(panic.is_err(), "simulated write must unwind");

    let error = txn
        .commit()
        .expect_err("an interrupted write must remain unpublishable");
    assert_eq!(error.operation(), Operation::Bridge);
    assert_eq!(error.failure().kind(), FailureKind::Internal);
    assert!(fs.final_snapshot().live_listing().is_empty());
    assert!(
        !operation_labels(&fs).contains(&SimOp::FlushFile),
        "panic-poisoned commit must not start finalization"
    );
}

#[test]
fn every_snapshot_projection_is_sweepable() {
    // Even for a fully successful run, every intermediate crash state must
    // leave only the destination and sweepable artifacts.
    let fs = SimFsBuilder::new()
        .preexisting_destination(DEST, OLD)
        .build();
    run_protocol(
        &fs,
        replace_preserving_publication(),
        SyncLevel::FileAndNamespaceSynchronized,
    )
    .expect("commit must succeed");

    for snapshot in fs.snapshots() {
        for projection in snapshot.crash_projections() {
            for name in projection.keys() {
                assert!(
                    name == DEST || parse_temporary_artifact_name(name).is_some(),
                    "unsweepable leftover {name:?} after a cut at {:?}",
                    snapshot.label,
                );
            }
        }
    }
}
