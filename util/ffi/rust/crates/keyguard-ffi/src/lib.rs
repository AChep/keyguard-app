//! FFI scaffolding shared by the Keyguard native bridges.
//!
//! Each bridge crate links this library into its own static or dynamic
//! library, so three rules keep it safe to share:
//!
//! - It exports no symbols. Every bridge keeps its literal `extern` entry
//!   points; headers, cinterop definitions and bundle checks name them.
//! - It owns no state that must be per-library. Static libraries built without
//!   LTO share this crate's objects once linked into one Apple binary, so a
//!   bridge owns its [`OperationRegistry`], [`SessionTable`]s and
//!   [`SharedTable`]s as its own `static`s.
//! - Failures cross the boundary as stable codes, never as messages.
//!
//! Use a [`SessionTable`] when each call needs its object to itself: a call
//! gets `&mut T`, and a panic drops only that object. Use a [`SharedTable`]
//! when the object locks itself and calls overlap, such as a blocking poll
//! next to sends: a call gets an `Arc<T>`. Both tag their handles with a
//! [`SessionTag`] in one layout.

#![deny(unsafe_code)]

mod failure;
#[cfg(feature = "jni")]
mod java;
mod operation;
mod panic;
mod raw;
mod sessions;
mod shared;

pub use failure::{
    BRIDGE_ERROR_INTERNAL, BRIDGE_ERROR_INVALID_ARGUMENT, BRIDGE_ERROR_PANIC, BRIDGE_INTERNAL,
    BRIDGE_INVALID_ARGUMENT, BRIDGE_PANIC, DOMAIN_SHIFT, ErrorDomain, FAILURE_MARKER, FailureKind,
    KIND_SHIFT, OPERATION_BRIDGE, OPERATION_MASK, RAW_CODE_SHIFT, pack_failure,
};
#[cfg(feature = "jni")]
pub use java::{
    execute_operation, java_byte_range, java_string, java_string_within, jbytes, jbytes_mut,
    with_java_env,
};
pub use operation::{
    OperationGuard, OperationLimits, OperationRegistry, OperationStatus, execute_into, respond,
};
pub use panic::{PanicHook, contained, flatten, install_redacting_panic_hook};
pub use raw::{bytes_from_raw, bytes_from_raw_mut, string_from_raw};
pub use sessions::{SessionError, SessionTable, SessionTag};
pub use shared::{SharedError, SharedTable};
