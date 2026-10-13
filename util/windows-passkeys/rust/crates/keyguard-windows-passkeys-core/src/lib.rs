//! Windows passkey provider transport. Commands contain public metadata or signatures, never keys.
#![deny(
    clippy::multiple_unsafe_ops_per_block,
    clippy::borrow_as_ptr,
    clippy::cast_possible_truncation
)]

// Elsewhere only `status`, `start`, `stop`, `retire` and `limits` are understood, so most
// items serve the Windows provider.
#[cfg_attr(not(windows), allow(dead_code))]
mod error;
#[cfg_attr(not(windows), allow(dead_code))]
mod protocol;
#[cfg_attr(not(windows), allow(dead_code))]
mod requests;
#[cfg(windows)]
mod windows;

use error::Error;
use keyguard_ffi::{OperationLimits, OperationRegistry, OperationStatus, respond};
use prost::Message;
use protocol::{Command, Reply, command, reply::Outcome};
use std::sync::atomic::Ordering;

/// The bridge ABI that Kotlin checks before its first command, including incompatible
/// changes to the wire schema.
pub const ABI_VERSION: i32 = 6;

/// The largest command: a sync of the largest index the OS accepts.
const MAX_REQUEST: usize = 16 * 1024 * 1024;

/// Bounds every reply, including the event of a request with the most and longest
/// descriptors that Windows can deliver.
const MAX_RESPONSE: usize = 16 * 1024 * 1024;

/// Request and response bounds of the JNI adapter. A command always selects one variant,
/// so it is never shorter than two bytes.
pub const LIMITS: OperationLimits = OperationLimits {
    min_request: 2,
    max_request: MAX_REQUEST,
    max_response: MAX_RESPONSE,
};

/// The status byte of a reply. The core answers every command with a zero status byte and
/// a [`Reply`]; the others come from the shared JNI helper, before or after the core ran.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Transport {
    /// The request is outside the bounds, or its operation is unknown.
    InvalidArgument = 1,
    /// The operation was canceled, so nobody reads its reply.
    Canceled = 2,
    /// The reply is outside the bounds.
    Protocol = 3,
    /// Unused: commands claim no device.
    Busy = 4,
    /// The bridge failed internally, e.g. on a panic.
    Internal = 5,
}

impl OperationStatus for Transport {
    const INVALID_ARGUMENT: Self = Self::InvalidArgument;
    const CANCELED: Self = Self::Canceled;
    const PROTOCOL: Self = Self::Protocol;
    const BUSY: Self = Self::Busy;
    const INTERNAL: Self = Self::Internal;

    fn code(self) -> u8 {
        self as u8
    }
}

/// Each command runs as an operation of its own, which the caller may cancel from another
/// thread. Commands claim no device: the event wait runs alongside the others.
static OPERATIONS: OperationRegistry = OperationRegistry::new();

/// Returns the handle of a new operation, or zero while too many are live.
pub fn create() -> u64 {
    OPERATIONS.create()
}

/// Cancels an operation and ends its event wait. Windows Hello has no dismiss API, so a
/// canceled verification still returns only when its prompt closes.
pub fn cancel(id: u64) {
    OPERATIONS.cancel(id);
    wake();
}

/// Removes an operation; one still running is canceled.
pub fn close(id: u64) {
    OPERATIONS.close(id);
    wake();
}

/// Runs one command as the operation `id`. The JNI adapter bounds and contains it.
pub fn execute(id: u64, bytes: &[u8]) -> Vec<u8> {
    let operation = match OPERATIONS.begin_shared::<Transport>(id) {
        Ok(operation) => operation,
        Err(status) => return respond(Err(status), MAX_RESPONSE),
    };
    let canceled = operation.canceled();
    let outcome = Command::decode(bytes)
        .ok()
        .and_then(|command| command.command)
        .ok_or(Error::Invalid)
        .and_then(|command| match command {
            // The same on every platform, so Kotlin can check its copy without a provider.
            command::Command::Limits(_) => Ok(Outcome::Limits(protocol::limits())),
            command => dispatch(command, canceled),
        })
        .unwrap_or_else(|error| Outcome::Error(error.code()));
    // Like a coroutine that is canceled during a call, the caller discards any outcome.
    if canceled.load(Ordering::Acquire) {
        return respond(Err(Transport::Canceled), MAX_RESPONSE);
    }
    let reply = Reply {
        outcome: Some(outcome),
    };
    respond::<Transport>(Ok(reply.encode_to_vec()), MAX_RESPONSE)
}

#[cfg(windows)]
use crate::windows::{dispatch, wake};

#[cfg(not(windows))]
fn dispatch(
    command: command::Command,
    _canceled: &std::sync::atomic::AtomicBool,
) -> Result<Outcome, Error> {
    match command {
        command::Command::Status(_) => Ok(Outcome::Status(protocol::Status::Unsupported.into())),
        command::Command::Start(_) => Err(Error::Unsupported),
        command::Command::Stop(_) => Ok(Outcome::Done(protocol::Empty {})),
        command::Command::Retire(_) => Ok(Outcome::Retired(true)),
        _ => Err(Error::Invalid),
    }
}

/// Elsewhere no command waits.
#[cfg(not(windows))]
fn wake() {}

#[cfg(test)]
mod tests {
    use super::*;
    use protocol::{Descriptor, Empty, Event, MAX_ALGORITHMS, MAX_DESCRIPTORS, MAX_TEXT, User};

    fn command(command: command::Command) -> Vec<u8> {
        Command {
            command: Some(command),
        }
        .encode_to_vec()
    }

    /// The framed reply with the [`Outcome`].
    fn reply(outcome: Outcome) -> Vec<u8> {
        let mut bytes = vec![0];
        bytes.extend(
            Reply {
                outcome: Some(outcome),
            }
            .encode_to_vec(),
        );
        bytes
    }

    /// Runs the command as an operation of its own, as the Kotlin bridge does.
    fn run(bytes: &[u8]) -> Vec<u8> {
        let id = create();
        assert_ne!(id, 0);
        let reply = execute(id, bytes);
        close(id);
        reply
    }

    /// Elsewhere there is no provider; on Windows, tests run unpackaged and cannot start one.
    #[test]
    fn nothing_keeps_a_process_without_a_provider() {
        let retire = command(command::Command::Retire(Empty {}));
        assert_eq!(run(&retire), reply(Outcome::Retired(true)));
    }

    #[test]
    fn a_canceled_or_unknown_operation_runs_no_command() {
        let retire = command(command::Command::Retire(Empty {}));
        let id = create();
        cancel(id);
        assert_eq!(execute(id, &retire), [Transport::Canceled as u8]);
        close(id);
        // A closed handle is unknown.
        assert_eq!(execute(id, &retire), [Transport::InvalidArgument as u8]);
        assert_eq!(execute(0, &retire), [Transport::InvalidArgument as u8]);
    }

    #[test]
    fn limits_are_answered_without_a_provider() {
        let limits = command(command::Command::Limits(Empty {}));
        assert_eq!(
            run(&limits),
            reply(Outcome::Limits(protocol::Limits {
                credential_id_bytes: 1023,
                user_id_bytes: 64,
                text_bytes: 4096,
            }))
        );
    }

    #[test]
    fn malformed_commands_are_invalid() {
        // Not protobuf, no command, and a command this bridge does not know.
        for request in [&[0xff][..], &[], &[0x7a, 0x00]] {
            assert_eq!(run(request), reply(Outcome::Error(-1)));
        }
    }

    /// Every string Windows delivers is read up to `MAX_TEXT` UTF-16 units, each at most
    /// three UTF-8 bytes.
    #[test]
    fn the_largest_event_fits_a_reply() {
        let text = "\u{800}".repeat(MAX_TEXT);
        let request = protocol::Request {
            id: u64::MAX,
            create: true,
            window: i64::MIN,
            rp_id: "a".repeat(MAX_TEXT),
            client_data_hash: vec![0xff; 32],
            user_verification: protocol::UserVerification::Discouraged.into(),
            credentials: vec![
                Descriptor {
                    id: vec![0xff; protocol::MAX_CREDENTIAL_ID],
                    r#type: text.clone(),
                };
                MAX_DESCRIPTORS
            ],
            user: Some(User {
                id: vec![0xff; protocol::MAX_USER_ID],
                name: text.clone(),
                display_name: text.clone(),
            }),
            rp_name: Some(text),
            algorithms: vec![i32::MIN; MAX_ALGORITHMS],
            discoverable: true,
            expires_in_ms: u64::MAX,
        };
        let event = Reply {
            outcome: Some(Outcome::Event(Event {
                request: Some(request),
                active_id: Some(u64::MAX),
                closed: true,
                watching: true,
            })),
        };
        assert!(
            event.encoded_len() < MAX_RESPONSE,
            "{}",
            event.encoded_len()
        );
    }
}
