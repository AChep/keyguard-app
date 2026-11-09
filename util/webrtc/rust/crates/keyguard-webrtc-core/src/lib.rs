//! WebRTC data channels for the Keyguard JNI and C bridges.
//!
//! A thin, blocking, handle-based layer over the `webrtc` crate. A runtime
//! handle owns one multi-thread tokio runtime; a peer handle owns one peer
//! connection with at most one data channel and belongs to one runtime. The
//! library holds no process-wide runtime.
//!
//! Rust never calls back into the caller. Events queue per peer and are
//! taken by [`peer_next_event`], which blocks the calling thread; closing the
//! peer from another thread wakes it. Requests that wait for the stack block
//! for at most [`REQUEST_TIMEOUT`]; adding a remote candidate and closing a
//! peer return at once and report later failures as [`Event::Error`].
//!
//! This crate is not the panic boundary: each bridge installs its own.

mod config;
mod error;
mod event;
mod peer;
mod runtime;
mod udp;

use std::time::Duration;

pub use error::{
    CODE_BUSY, CODE_CLOSED, CODE_INPUT_TOO_LONG, CODE_INTERNAL, CODE_INVALID_ARGUMENT,
    CODE_INVALID_HANDLE, CODE_INVALID_STATE, CODE_PANIC, CODE_STACK, CODE_TIMEOUT, ErrorDomain,
    FailureKind, WebrtcError,
};
pub use event::{
    ConnectionState, EVENT_ERROR_CHANNEL, EVENT_ERROR_ICE_SERVER, EVENT_ERROR_REMOTE_CANDIDATE,
    Event, KIND_BUFFERED_LOW, KIND_CHANNEL_CLOSE, KIND_CHANNEL_MESSAGE, KIND_CHANNEL_OPEN,
    KIND_CONNECTION_STATE, KIND_ERROR, KIND_ICE_CANDIDATE, KIND_ICE_GATHERING_COMPLETE,
    KIND_SELECTED_PAIR,
};
pub use peer::{
    channel_buffered_amount, channel_send, peer_add_ice_candidate, peer_close, peer_create_answer,
    peer_create_channel, peer_create_offer, peer_new, peer_next_event, peer_set_remote,
};
pub use runtime::{runtime_new, runtime_shutdown};

/// Version of the direct native function ABI.
pub const ABI_VERSION: u32 = 1;

/// Largest data channel message, in bytes.
pub const MAX_MESSAGE_BYTES: usize = 64 * 1024;
/// Smallest output buffer `peer_next_event` accepts: a kind byte and the largest message.
pub const MAX_EVENT_BYTES: usize = 1 + MAX_MESSAGE_BYTES;
/// Largest session description in either direction; also the smallest output
/// buffer `peer_create_offer` and `peer_create_answer` accept.
pub const MAX_SDP_BYTES: usize = 64 * 1024;
/// Largest peer configuration JSON.
pub const MAX_CONFIG_BYTES: usize = 16 * 1024;
/// Largest remote ICE candidate JSON.
pub const MAX_CANDIDATE_BYTES: usize = 4 * 1024;
/// Largest data channel label.
pub const MAX_LABEL_BYTES: usize = 255;

/// `peer_set_remote` kind of an offer.
pub const SDP_KIND_OFFER: u32 = 0;
/// `peer_set_remote` kind of an answer.
pub const SDP_KIND_ANSWER: u32 = 1;

/// Buffered bytes above which `channel_send` answers [`WebrtcError::Busy`].
pub const SEND_BUFFER_LIMIT: usize = 1024 * 1024;
/// Buffered bytes at which [`Event::BufferedLow`] fires.
pub const BUFFERED_LOW_THRESHOLD: u32 = 256 * 1024;
/// Events queued per peer before the stack waits for the caller.
pub const EVENT_QUEUE_CAPACITY: usize = 1024;
/// Most runtimes live at once; `runtime_new` answers [`WebrtcError::Busy`] beyond.
pub const MAX_RUNTIMES: usize = 64;
/// Most peers live at once, of every runtime; `peer_new` answers
/// [`WebrtcError::Busy`] beyond.
pub const MAX_PEERS: usize = 1024;
/// How long a request waits for the stack.
pub const REQUEST_TIMEOUT: Duration = Duration::from_secs(10);

/// Converts a handle received over the ABI.
///
/// # Errors
///
/// Returns [`WebrtcError::InvalidHandle`] for a negative value; the
/// handle tables reject every other value they did not issue.
pub const fn handle_from_abi(value: i64) -> Result<u64, WebrtcError> {
    if value < 0 {
        Err(WebrtcError::InvalidHandle)
    } else {
        Ok(value as u64)
    }
}

/// Returns a handle as its positive scalar, or the packed failure.
#[must_use]
pub fn handle_to_abi(result: Result<u64, WebrtcError>) -> i64 {
    // Issued handles are positive `i64`s, so the conversion never fails.
    result.map_or_else(WebrtcError::pack, |handle| {
        i64::try_from(handle).unwrap_or_else(|_| WebrtcError::Internal.pack())
    })
}

/// Returns zero, or the packed failure.
#[must_use]
pub fn unit_to_abi(result: Result<(), WebrtcError>) -> i64 {
    result.map_or_else(WebrtcError::pack, |()| 0)
}

/// Returns a non-negative length or amount, or the packed failure.
#[must_use]
pub fn length_to_abi(result: Result<usize, WebrtcError>) -> i64 {
    result.map_or_else(WebrtcError::pack, |length| {
        i64::try_from(length).unwrap_or_else(|_| WebrtcError::Internal.pack())
    })
}

/// Rejects an output buffer smaller than `minimum` before any side effect.
///
/// # Errors
///
/// Returns [`WebrtcError::InvalidArgument`] when `capacity < minimum`.
pub const fn check_capacity(capacity: usize, minimum: usize) -> Result<(), WebrtcError> {
    if capacity < minimum {
        Err(WebrtcError::InvalidArgument)
    } else {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn abi_scalars_keep_successes_positive_and_failures_packed() {
        assert_eq!(handle_from_abi(-1), Err(WebrtcError::InvalidHandle));
        assert_eq!(handle_from_abi(7), Ok(7));
        assert_eq!(handle_to_abi(Ok(7)), 7);
        assert_eq!(
            handle_to_abi(Err(WebrtcError::Closed)),
            WebrtcError::Closed.pack()
        );
        assert_eq!(unit_to_abi(Ok(())), 0);
        assert_eq!(length_to_abi(Ok(12)), 12);
        assert_eq!(check_capacity(1, 2), Err(WebrtcError::InvalidArgument));
        assert_eq!(check_capacity(2, 2), Ok(()));
    }
}
