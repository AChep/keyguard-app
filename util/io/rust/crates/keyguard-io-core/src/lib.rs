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
mod windows_nt;
#[cfg(windows)]
mod winfs;

#[cfg(test)]
mod crash_tests;
#[cfg(test)]
mod simfs;

use std::sync::Once;

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

/// Reserved error code returned for an invalid native ABI argument.
pub const BRIDGE_ERROR_INVALID_ARGUMENT: u32 = 1;

/// Reserved error code returned when a panic reaches a native ABI boundary.
pub const BRIDGE_ERROR_PANIC: u32 = 2;

/// Reserved error code returned when a native ABI adapter fails internally.
pub const BRIDGE_ERROR_INTERNAL: u32 = 3;

/// Reserved error code returned for an unknown or consumed native handle.
pub const BRIDGE_ERROR_UNKNOWN_HANDLE: u32 = 4;

static PANIC_HOOK: Once = Once::new();

/// Installs a process-wide panic hook that does not disclose paths or file data.
///
/// Rust's default hook prints panic payloads before [`std::panic::catch_unwind`]
/// runs. Native bridges install this hook before entering their panic boundary
/// and communicate only stable status codes to Kotlin.
pub fn install_redacting_panic_hook() {
    PANIC_HOOK.call_once(|| std::panic::set_hook(Box::new(|_| {})));
}
