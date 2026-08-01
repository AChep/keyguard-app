//! Typed bridge operations shared by the JNI and C adapters.
//!
//! Both bridges unmarshal their argument encodings, call these functions, and
//! return the packed `i64` unchanged — keeping the two ABIs mechanically
//! identical and the packing logic in exactly one place.

use std::{mem::size_of, path::Path, sync::OnceLock, time::Duration};

use crate::{
    abi::{
        TXN_OPTIONS_FLAGS_ALL, TXN_OPTIONS_WIRE_VERSION, TxnOptionsWire, pack_commit_success,
        pack_txn_error,
    },
    directory::{AtomicDirectory, RelativeDestination},
    durability::{SyncLevel, SyncPolicy},
    error::{FailureKind, FileSystemFailure, Operation, TxnError},
    fsops::{FsOps, RealFs},
    registry::{Registry, RegistryError, RegistryKind},
    scratch::ScratchFile,
    sweep::{SweepOptions, SweepReport, sweep_orphans},
    txn::{
        AtomicWriteOptions, AtomicWriteTxn, DirectoryPermissions, ExistingParentLinkPolicy,
        ParentDirectoryPolicy, Permissions, PublishPolicy, ReplacementAccessPolicy,
    },
};

/// All currently defined temporary-artifact role-mask bits.
pub const SWEEP_ROLE_MASK_ALL: u32 = 0x7;

fn txns() -> &'static Registry<AtomicWriteTxn<RealFs>> {
    static TXNS: OnceLock<Registry<AtomicWriteTxn<RealFs>>> = OnceLock::new();
    TXNS.get_or_init(|| Registry::new(RegistryKind::Transaction))
}

type NativeAtomicDirectory = AtomicDirectory<<RealFs as FsOps>::Dir>;

fn directories() -> &'static Registry<NativeAtomicDirectory> {
    static DIRECTORIES: OnceLock<Registry<NativeAtomicDirectory>> = OnceLock::new();
    DIRECTORIES.get_or_init(|| Registry::new(RegistryKind::Directory))
}

fn scratches() -> &'static Registry<ScratchFile> {
    static SCRATCHES: OnceLock<Registry<ScratchFile>> = OnceLock::new();
    SCRATCHES.get_or_init(|| Registry::new(RegistryKind::Scratch))
}

fn registry_error(error: RegistryError) -> i64 {
    let failure = match error {
        RegistryError::UnknownHandle => TxnError::bridge_unknown_handle(),
        RegistryError::Busy => TxnError::new(
            Operation::Bridge,
            FileSystemFailure::semantic(FailureKind::ResourceBusy),
        ),
    };
    pack_txn_error(failure)
}

fn io_failure(operation: Operation, error: &std::io::Error) -> i64 {
    pack_txn_error(TxnError::from_io_error(operation, error))
}

/// Opens an atomic-write transaction and returns its positive handle.
///
/// The complete size- and version-tagged options record is validated before
/// the destination path reaches the filesystem.
#[must_use]
pub fn txn_begin(destination: &str, wire: TxnOptionsWire) -> i64 {
    let Ok(options) = decode_txn_options(wire) else {
        return pack_txn_error(TxnError::bridge_invalid_argument());
    };
    match AtomicWriteTxn::begin(RealFs, Path::new(destination), options) {
        Ok(txn) => txns().insert(txn) as i64,
        Err(error) => pack_txn_error(error),
    }
}

/// Resolves an existing absolute directory once and returns its positive
/// retained-capability handle.
#[must_use]
pub fn directory_open(directory: &str) -> i64 {
    match AtomicDirectory::open(&RealFs, Path::new(directory)) {
        Ok(directory) => directories().insert(directory) as i64,
        Err(error) => pack_txn_error(error),
    }
}

/// Closes one logical retained-directory handle.
///
/// Transactions that already cloned the capability remain valid.
#[must_use]
pub fn directory_close(handle: u64) -> i64 {
    match directories().remove(handle) {
        Ok(directory) => {
            drop(directory);
            0
        }
        Err(error) => registry_error(error),
    }
}

/// Opens an atomic-write transaction at a strict relative path beneath a
/// retained directory capability.
///
/// Options and the complete relative spelling are validated before the
/// directory handle is looked up or the filesystem is accessed.
#[must_use]
pub fn txn_begin_at_directory(
    directory_handle: u64,
    relative_destination: &str,
    wire: TxnOptionsWire,
) -> i64 {
    let Ok(options) = decode_txn_options(wire) else {
        return pack_txn_error(TxnError::bridge_invalid_argument());
    };
    let Ok(destination) = RelativeDestination::parse(relative_destination) else {
        return pack_txn_error(TxnError::bridge_invalid_argument());
    };
    if options.existing_parent_links != ExistingParentLinkPolicy::Reject {
        return pack_txn_error(TxnError::bridge_invalid_argument());
    }
    let directory = match directories().with(directory_handle, |directory| directory.clone()) {
        Ok(directory) => directory,
        Err(error) => return registry_error(error),
    };
    match AtomicWriteTxn::begin_at_directory(RealFs, &directory, destination, options) {
        Ok(txn) => txns().insert(txn) as i64,
        Err(error) => pack_txn_error(error),
    }
}

fn decode_txn_options(wire: TxnOptionsWire) -> Result<AtomicWriteOptions, ()> {
    if wire.size != size_of::<TxnOptionsWire>() as u32
        || wire.version != TXN_OPTIONS_WIRE_VERSION
        || wire.flags & !TXN_OPTIONS_FLAGS_ALL != 0
        || wire.reserved != [0; 5]
    {
        return Err(());
    }
    let permissions = Permissions::try_from(wire.file_permissions)?;
    let directory_permissions = match wire.directory_permissions {
        0 => DirectoryPermissions::OwnerOnly,
        1 => DirectoryPermissions::ProcessDefault,
        _ => return Err(()),
    };
    let parent_directory = match wire.parent_creation {
        0 => ParentDirectoryPolicy::RequireExisting,
        1 => ParentDirectoryPolicy::CreateMissing {
            permissions: directory_permissions,
        },
        _ => return Err(()),
    };
    let existing_parent_links = ExistingParentLinkPolicy::try_from(wire.existing_parent_links)?;
    let preferred = SyncLevel::try_from(wire.preferred_sync_level)?;
    let minimum = SyncLevel::try_from(wire.minimum_sync_level)?;
    let synchronization = match wire.sync_policy_mode {
        0 if preferred == minimum => SyncPolicy::Required(preferred),
        0 => return Err(()),
        1 if minimum <= preferred => SyncPolicy::Prefer { preferred, minimum },
        1 => return Err(()),
        _ => return Err(()),
    };
    let publication = match wire.publication {
        0 => PublishPolicy::Create { permissions },
        1 => PublishPolicy::Replace {
            access: ReplacementAccessPolicy::UseRequestedPermissions { permissions },
        },
        2 => PublishPolicy::Replace {
            access: ReplacementAccessPolicy::PreserveExistingBasicPermissions {
                if_destination_missing: permissions,
            },
        },
        _ => return Err(()),
    };
    Ok(AtomicWriteOptions {
        publication,
        parent_directory,
        existing_parent_links,
        synchronization,
    })
}

/// Appends bytes to a transaction; returns the byte count.
///
/// The first failed append permanently poisons the transaction. Later writes
/// return the original failure without replaying I/O, while the retained
/// handle remains available for abort. Commit consumes a poisoned handle,
/// performs cleanup only, and returns the original write failure.
#[must_use]
pub fn txn_write(handle: u64, bytes: &[u8]) -> i64 {
    txn_write_in(txns(), handle, bytes)
}

fn txn_write_in<F: FsOps>(
    registry: &Registry<AtomicWriteTxn<F>>,
    handle: u64,
    bytes: &[u8],
) -> i64 {
    match registry.with(handle, |txn| txn.write(bytes)) {
        Ok(Ok(())) => bytes.len() as i64,
        Ok(Err(error)) => pack_txn_error(error),
        Err(error) => registry_error(error),
    }
}

/// Commits a transaction, consuming its handle on every result.
#[must_use]
pub fn txn_commit(handle: u64) -> i64 {
    txn_commit_in(txns(), handle)
}

fn txn_commit_in<F: FsOps>(registry: &Registry<AtomicWriteTxn<F>>, handle: u64) -> i64 {
    match registry.remove(handle) {
        Ok(txn) => match txn.commit() {
            Ok(success) => pack_commit_success(success),
            Err(error) => pack_txn_error(error),
        },
        Err(error) => registry_error(error),
    }
}

/// Aborts a transaction, consuming its handle and removing its staged
/// artifact.
///
/// Returns zero on clean removal or a cleanup-tagged packed failure. A failed
/// cleanup leaves the destination unpublished and may leave a sweepable
/// temporary behind.
#[must_use]
pub fn txn_abort(handle: u64) -> i64 {
    txn_abort_in(txns(), handle)
}

fn txn_abort_in<F: FsOps>(registry: &Registry<AtomicWriteTxn<F>>, handle: u64) -> i64 {
    match registry.remove(handle) {
        Ok(txn) => txn.abort().map_or_else(pack_txn_error, |()| 0),
        Err(error) => registry_error(error),
    }
}

/// Opens private scratch storage and returns its positive handle.
#[must_use]
pub fn scratch_open(directory: &str) -> i64 {
    match ScratchFile::open(Path::new(directory)) {
        Ok(scratch) => scratches().insert(scratch) as i64,
        Err(error) => io_failure(Operation::CreateStaged, &error),
    }
}

/// Appends bytes to scratch storage; returns the byte count.
#[must_use]
pub fn scratch_write(handle: u64, bytes: &[u8]) -> i64 {
    match scratches().with(handle, |scratch| scratch.write(bytes)) {
        Ok(Ok(written)) => written as i64,
        Ok(Err(error)) => io_failure(Operation::Write, &error),
        Err(error) => registry_error(error),
    }
}

/// Seals scratch storage for reading; returns zero.
#[must_use]
pub fn scratch_seal(handle: u64) -> i64 {
    match scratches().with(handle, ScratchFile::seal) {
        Ok(Ok(())) => 0,
        Ok(Err(error)) => io_failure(Operation::Write, &error),
        Err(error) => registry_error(error),
    }
}

/// Returns the sealed or in-progress scratch length in bytes.
#[must_use]
pub fn scratch_length(handle: u64) -> i64 {
    match scratches().with(handle, |scratch| scratch.length()) {
        Ok(Ok(length)) => length.min(i64::MAX as u64) as i64,
        Ok(Err(error)) => io_failure(Operation::Metadata, &error),
        Err(error) => registry_error(error),
    }
}

/// Reads scratch bytes at `position`; returns the byte count or `-1` at
/// end-of-file.
#[must_use]
pub fn scratch_read_at(handle: u64, position: u64, buffer: &mut [u8]) -> i64 {
    match scratches().with(handle, |scratch| scratch.read_at(position, buffer)) {
        Ok(Ok(0)) => -1,
        Ok(Ok(read)) => read as i64,
        Ok(Err(error)) => io_failure(Operation::Read, &error),
        Err(error) => registry_error(error),
    }
}

/// Closes scratch storage, consuming its handle; returns zero.
#[must_use]
pub fn scratch_close(handle: u64) -> i64 {
    match scratches().remove(handle) {
        Ok(scratch) => {
            drop(scratch);
            0
        }
        Err(error) => registry_error(error),
    }
}

/// Sweeps a directory for orphaned temporary artifacts.
///
/// Root and pre-scan failures retain the packed scalar error representation;
/// successful, busy, and partially completed sweeps return a typed report.
pub fn sweep(directory: &str, older_than_ms: u64, role_mask: u32) -> Result<SweepReport, i64> {
    if role_mask & !SWEEP_ROLE_MASK_ALL != 0 {
        return Err(pack_txn_error(TxnError::bridge_invalid_argument()));
    }
    let options = SweepOptions {
        older_than: Duration::from_millis(older_than_ms),
        role_mask,
    };
    match sweep_orphans(Path::new(directory), options) {
        Ok(report) => Ok(report),
        Err(error) => Err(io_failure(Operation::Sweep, &error)),
    }
}

#[cfg(test)]
mod tests {
    use std::panic::{AssertUnwindSafe, catch_unwind};

    use super::*;
    use crate::{durability::SyncLevel, simfs::SimFsBuilder};

    #[test]
    fn v1_options_decode_to_typed_policies() {
        assert_eq!(
            decode_txn_options(valid_wire()),
            Ok(AtomicWriteOptions {
                publication: PublishPolicy::Create {
                    permissions: Permissions::OwnerOnly,
                },
                parent_directory: ParentDirectoryPolicy::CreateMissing {
                    permissions: DirectoryPermissions::OwnerOnly,
                },
                existing_parent_links: ExistingParentLinkPolicy::Reject,
                synchronization: SyncPolicy::Prefer {
                    preferred: SyncLevel::FileAndNamespaceSynchronized,
                    minimum: SyncLevel::FileSynchronized,
                },
            })
        );
    }

    #[test]
    fn every_v1_options_field_is_validated() {
        let invalid_records = [
            TxnOptionsWire {
                size: 0,
                ..valid_wire()
            },
            TxnOptionsWire {
                version: TXN_OPTIONS_WIRE_VERSION + 1,
                ..valid_wire()
            },
            TxnOptionsWire {
                publication: 3,
                ..valid_wire()
            },
            TxnOptionsWire {
                file_permissions: 2,
                ..valid_wire()
            },
            TxnOptionsWire {
                parent_creation: 2,
                ..valid_wire()
            },
            TxnOptionsWire {
                directory_permissions: 2,
                ..valid_wire()
            },
            TxnOptionsWire {
                existing_parent_links: 2,
                ..valid_wire()
            },
            TxnOptionsWire {
                preferred_sync_level: 3,
                ..valid_wire()
            },
            TxnOptionsWire {
                minimum_sync_level: 3,
                ..valid_wire()
            },
            TxnOptionsWire {
                preferred_sync_level: 1,
                minimum_sync_level: 2,
                ..valid_wire()
            },
            TxnOptionsWire {
                sync_policy_mode: 0,
                preferred_sync_level: 2,
                minimum_sync_level: 1,
                ..valid_wire()
            },
            TxnOptionsWire {
                sync_policy_mode: 2,
                ..valid_wire()
            },
            TxnOptionsWire {
                flags: 1,
                ..valid_wire()
            },
            TxnOptionsWire {
                reserved: [0, 0, 1, 0, 0],
                ..valid_wire()
            },
        ];
        for wire in invalid_records {
            assert_eq!(decode_txn_options(wire), Err(()));
        }
    }

    #[test]
    fn unknown_sweep_role_bits_are_rejected_before_filesystem_access() {
        assert_eq!(
            sweep("", u64::MAX, SWEEP_ROLE_MASK_ALL + 1),
            Err(pack_txn_error(TxnError::bridge_invalid_argument())),
        );
    }

    #[test]
    fn retained_directory_transaction_survives_logical_directory_close() {
        let directory = unique_test_directory("close-survival");
        std::fs::create_dir_all(&directory).expect("test root must be created");
        let directory_path = directory.to_str().expect("test path must be UTF-8");

        let directory_handle = directory_open(directory_path);
        assert!(directory_handle > 0);
        let txn_handle =
            txn_begin_at_directory(directory_handle as u64, "nested/vault.bin", valid_wire());
        assert!(txn_handle > 0, "relative begin failed: {txn_handle:#x}");

        assert_eq!(directory_close(directory_handle as u64), 0);
        assert_eq!(txn_write(txn_handle as u64, b"retained"), 8);
        assert!(txn_commit(txn_handle as u64) >= 0);
        assert_eq!(
            std::fs::read(directory.join("nested/vault.bin"))
                .expect("published file must be readable"),
            b"retained"
        );

        std::fs::remove_dir_all(directory).expect("test root must be removed");
    }

    #[test]
    fn retained_directory_close_reports_busy_unknown_and_double_close() {
        let directory = unique_test_directory("close-errors");
        std::fs::create_dir_all(&directory).expect("test root must be created");
        let handle = directory_open(directory.to_str().expect("test path must be UTF-8")) as u64;
        let held = directories().clone_entry_for_tests(handle);

        // A busy close consumes the handle: the capability is destroyed by the
        // concurrent user's last reference, so the caller must not — and cannot
        // — close it again.
        assert_eq!(directory_close(handle), registry_error(RegistryError::Busy));
        assert_eq!(
            directory_close(handle),
            registry_error(RegistryError::UnknownHandle)
        );
        drop(held);
        assert_eq!(
            directory_close(handle),
            registry_error(RegistryError::UnknownHandle)
        );
        assert_eq!(
            directory_close(u64::MAX),
            registry_error(RegistryError::UnknownHandle)
        );

        std::fs::remove_dir_all(directory).expect("test root must be removed");
    }

    #[test]
    fn relative_validation_precedes_directory_handle_lookup() {
        assert_eq!(
            txn_begin_at_directory(u64::MAX, "../escape", valid_wire()),
            pack_txn_error(TxnError::bridge_invalid_argument())
        );
        assert_eq!(
            txn_begin_at_directory(u64::MAX, "valid.bin", valid_wire()),
            registry_error(RegistryError::UnknownHandle)
        );

        let follow_wire = TxnOptionsWire {
            existing_parent_links: 1,
            ..valid_wire()
        };
        assert_eq!(
            txn_begin_at_directory(u64::MAX, "valid.bin", follow_wire),
            pack_txn_error(TxnError::bridge_invalid_argument())
        );
    }

    #[test]
    fn poisoned_write_is_packed_without_replay_and_commit_consumes_handle() {
        let fs = SimFsBuilder::new()
            .partial_write_fault(0, 3, std::io::ErrorKind::StorageFull)
            .build();
        let registry = Registry::new(RegistryKind::Transaction);
        let options = decode_txn_options(valid_wire()).expect("wire options must decode");
        let txn = AtomicWriteTxn::begin(
            fs.clone(),
            Path::new(test_absolute_path!("/vault.bin")),
            options,
        )
        .expect("transaction must begin");
        let handle = registry.insert(txn);

        let first = txn_write_in(&registry, handle, b"new contents");
        assert_eq!(txn_write_in(&registry, handle, b"must not replay"), first);
        assert_eq!(
            fs.operations()
                .iter()
                .filter(|operation| operation.op == crate::simfs::SimOp::WriteAll)
                .count(),
            1
        );
        assert_eq!(txn_commit_in(&registry, handle), first);
        assert_eq!(
            txn_abort_in(&registry, handle),
            registry_error(RegistryError::UnknownHandle)
        );
        assert!(fs.final_snapshot().live_listing().is_empty());
    }

    #[test]
    fn contained_write_panic_leaves_registered_transaction_unpublishable() {
        let fs = SimFsBuilder::new().partial_write_panic(0, 2).build();
        let registry = Registry::new(RegistryKind::Transaction);
        let mut options = decode_txn_options(valid_wire()).expect("wire options must decode");
        options.synchronization = SyncPolicy::Required(SyncLevel::ProcessAtomic);
        let txn = AtomicWriteTxn::begin(
            fs.clone(),
            Path::new(test_absolute_path!("/vault.bin")),
            options,
        )
        .expect("transaction must begin");
        let handle = registry.insert(txn);

        let panic = catch_unwind(AssertUnwindSafe(|| {
            txn_write_in(&registry, handle, b"new contents")
        }));
        assert!(panic.is_err(), "simulated write must unwind");
        assert_eq!(
            txn_commit_in(&registry, handle),
            pack_txn_error(TxnError::bridge_panic())
        );
        assert!(fs.final_snapshot().live_listing().is_empty());
    }

    fn unique_test_directory(label: &str) -> std::path::PathBuf {
        let mut nonce = [0_u8; 8];
        getrandom::fill(&mut nonce).expect("test nonce generation must succeed");
        let nonce: String = nonce.iter().map(|byte| format!("{byte:02x}")).collect();
        std::env::temp_dir().join(format!("keyguard-io-{label}-{nonce}"))
    }

    fn valid_wire() -> TxnOptionsWire {
        TxnOptionsWire {
            size: size_of::<TxnOptionsWire>() as u32,
            version: TXN_OPTIONS_WIRE_VERSION,
            publication: 0,
            file_permissions: 0,
            parent_creation: 1,
            directory_permissions: 0,
            existing_parent_links: 0,
            preferred_sync_level: 2,
            minimum_sync_level: 1,
            sync_policy_mode: 1,
            flags: 0,
            reserved: [0; 5],
        }
    }
}
