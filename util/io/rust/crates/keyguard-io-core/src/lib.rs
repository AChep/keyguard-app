//! Native filesystem core shared by the Keyguard JNI and C bridges.
//!
//! The atomic-write protocol — stage, write, flush, rename, directory flush —
//! lives entirely in this crate on every platform, behind the [`fsops::FsOps`]
//! fault-injection seam so power-cut behavior is provable in tests.

// The simulated filesystem validates host absolute-path syntax and resolves
// every component beneath its single virtual root.
#[cfg(all(test, windows))]
macro_rules! test_absolute_path {
    ($path:literal) => {
        concat!("C:", $path)
    };
}

#[cfg(all(test, not(windows)))]
macro_rules! test_absolute_path {
    ($path:literal) => {
        $path
    };
}

#[cfg(all(test, windows))]
fn windows_symlink_unavailable(error: &std::io::Error) -> bool {
    const ERROR_PRIVILEGE_NOT_HELD: i32 = 1_314;

    error.kind() == std::io::ErrorKind::PermissionDenied
        || error.raw_os_error() == Some(ERROR_PRIVILEGE_NOT_HELD)
}

pub mod abi;
pub mod bridge;
pub mod directory;
pub mod durability;
pub mod error;
pub mod fsops;
pub mod naming;
mod parent;
pub mod registry;
pub mod scratch;
pub mod sweep;
pub mod txn;

#[cfg(windows)]
pub mod windows_file;
#[cfg(windows)]
mod windows_nt;
#[cfg(windows)]
mod winfs;

#[cfg(test)]
mod crash_tests;
#[cfg(test)]
mod simfs;

pub use directory::{AtomicDirectory, RelativeDestination};
pub use durability::{AchievedSyncLevel, SyncLevel, SyncPolicy, SyncPolicyError};
pub use error::{ErrorDomain, FailureKind, FileSystemFailure, Operation, TxnError};
pub use naming::TemporaryFileRole;
pub use registry::{Registry, RegistryError, RegistryKind};
pub use scratch::ScratchFile;
pub use sweep::{SweepOptions, SweepReport, SweepStatus, sweep_orphans};
pub use txn::{
    AtomicWriteOptions, AtomicWriteTxn, CleanupState, CommitOutcome, CommitSuccess,
    DirectoryPermissions, ExistingParentLinkPolicy, ParentDirectoryPolicy, Permissions,
    PublicationOperation, PublishPolicy, ReplacementAccessPolicy,
};

/// Version of the direct native function ABI.
pub const ABI_VERSION: u32 = 1;

pub use keyguard_ffi::{BRIDGE_ERROR_INTERNAL, BRIDGE_ERROR_INVALID_ARGUMENT, BRIDGE_ERROR_PANIC};

/// Reserved error code returned for an unknown or consumed native handle.
pub const BRIDGE_ERROR_UNKNOWN_HANDLE: u32 = 4;
