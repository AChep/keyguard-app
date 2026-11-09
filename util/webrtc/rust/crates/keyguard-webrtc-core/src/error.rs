//! Stable, project-owned failure taxonomy of the WebRTC bridge.
//!
//! Every failure crosses the ABI as a `keyguard-ffi` packed scalar with the
//! bridge operation, the [`ErrorDomain::Bridge`] domain and one of the codes
//! below. The codes never carry a message, an SDP or a payload.

use keyguard_ffi::SharedError;
pub use keyguard_ffi::{ErrorDomain, FailureKind};

/// Bridge code of an argument that violated the ABI contract.
pub const CODE_INVALID_ARGUMENT: u32 = keyguard_ffi::BRIDGE_ERROR_INVALID_ARGUMENT;
/// Bridge code of a panic contained at an ABI boundary.
pub const CODE_PANIC: u32 = keyguard_ffi::BRIDGE_ERROR_PANIC;
/// Bridge code of an internal adapter failure.
pub const CODE_INTERNAL: u32 = keyguard_ffi::BRIDGE_ERROR_INTERNAL;
/// Bridge code of an input over its byte limit.
pub const CODE_INPUT_TOO_LONG: u32 = 4;
/// Bridge code of a handle that was never issued, or was issued for another kind.
pub const CODE_INVALID_HANDLE: u32 = 5;
/// Bridge code of a peer or runtime that is closed, or closed during the call.
pub const CODE_CLOSED: u32 = 6;
/// Bridge code of a send refused because the channel buffers more than the
/// limit, or of a runtime or peer refused because too many are live.
pub const CODE_BUSY: u32 = 7;
/// Bridge code of a request that the WebRTC stack did not answer in time.
pub const CODE_TIMEOUT: u32 = 8;
/// Bridge code of a call made in the wrong state, such as a send with no channel.
pub const CODE_INVALID_STATE: u32 = 9;
/// Bridge code of a request the WebRTC stack rejected, such as a malformed SDP.
pub const CODE_STACK: u32 = 10;

/// A contained failure of the WebRTC bridge.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum WebrtcError {
    /// An argument violated the ABI contract.
    InvalidArgument,
    /// A panic was contained at an ABI boundary.
    Panic,
    /// An ABI adapter or the bridge failed internally.
    Internal,
    /// An input exceeded its byte limit.
    InputTooLong,
    /// The handle was never issued, or names another kind of object.
    InvalidHandle,
    /// The peer or its runtime is closed.
    Closed,
    /// The channel buffers more than the send limit, retry after
    /// `BufferedLow`; or too many runtimes or peers are live.
    Busy,
    /// The WebRTC stack did not answer within the request timeout.
    Timeout,
    /// The call is not valid in the current state.
    InvalidState,
    /// The WebRTC stack rejected the request.
    Stack,
}

impl WebrtcError {
    /// Returns the stable failure kind, error domain and raw bridge code.
    #[must_use]
    pub const fn wire_parts(self) -> (FailureKind, ErrorDomain, u32) {
        let (kind, code) = match self {
            Self::InvalidArgument => (FailureKind::InvalidInput, CODE_INVALID_ARGUMENT),
            Self::Panic => (FailureKind::Internal, CODE_PANIC),
            Self::Internal => (FailureKind::Internal, CODE_INTERNAL),
            Self::InputTooLong => (FailureKind::InvalidInput, CODE_INPUT_TOO_LONG),
            Self::InvalidHandle => (FailureKind::InvalidInput, CODE_INVALID_HANDLE),
            Self::Closed => (FailureKind::Interrupted, CODE_CLOSED),
            Self::Busy => (FailureKind::ResourceBusy, CODE_BUSY),
            Self::Timeout => (FailureKind::Other, CODE_TIMEOUT),
            Self::InvalidState => (FailureKind::Other, CODE_INVALID_STATE),
            Self::Stack => (FailureKind::Other, CODE_STACK),
        };
        (kind, ErrorDomain::Bridge, code)
    }

    /// Packs the failure into the negative scalar every export returns.
    #[must_use]
    pub const fn pack(self) -> i64 {
        let (kind, domain, code) = self.wire_parts();
        keyguard_ffi::pack_failure(keyguard_ffi::OPERATION_BRIDGE, kind, domain, code)
    }

    /// Maps a failure reported by the WebRTC stack.
    pub(crate) fn from_stack(error: &webrtc::error::Error) -> Self {
        use webrtc::error::Error;
        match error {
            Error::ErrSendBufferFull => Self::Busy,
            Error::ErrConnectionClosed | Error::ErrDataChannelClosed | Error::ErrClosedPipe => {
                Self::Closed
            }
            Error::ErrDataChannelNotOpen | Error::ErrSCTPNotEstablished => Self::InvalidState,
            _ => Self::Stack,
        }
    }
}

impl From<SharedError> for WebrtcError {
    fn from(error: SharedError) -> Self {
        match error {
            SharedError::Invalid => Self::InvalidHandle,
            SharedError::Closed => Self::Closed,
            SharedError::Full => Self::Busy,
            SharedError::Exhausted => Self::Internal,
        }
    }
}

/// Golden scalars asserted by the Kotlin wire test; changing one is an ABI break.
#[cfg(test)]
mod golden {
    pub const INVALID_ARGUMENT: i64 = 0x8000_0000_0103_0800_u64 as i64;
    pub const PANIC: i64 = 0x8000_0000_0203_0C00_u64 as i64;
    pub const INTERNAL: i64 = 0x8000_0000_0303_0C00_u64 as i64;
    pub const INPUT_TOO_LONG: i64 = 0x8000_0000_0403_0800_u64 as i64;
    pub const INVALID_HANDLE: i64 = 0x8000_0000_0503_0800_u64 as i64;
    pub const CLOSED: i64 = 0x8000_0000_0603_0900_u64 as i64;
    pub const BUSY: i64 = 0x8000_0000_0703_0700_u64 as i64;
    pub const TIMEOUT: i64 = 0x8000_0000_0803_0B00_u64 as i64;
    pub const INVALID_STATE: i64 = 0x8000_0000_0903_0B00_u64 as i64;
    pub const STACK: i64 = 0x8000_0000_0A03_0B00_u64 as i64;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn packed_failures_match_the_golden_vectors() {
        assert_eq!(
            WebrtcError::InvalidArgument.pack(),
            golden::INVALID_ARGUMENT
        );
        assert_eq!(WebrtcError::Panic.pack(), golden::PANIC);
        assert_eq!(WebrtcError::Internal.pack(), golden::INTERNAL);
        assert_eq!(WebrtcError::InputTooLong.pack(), golden::INPUT_TOO_LONG);
        assert_eq!(WebrtcError::InvalidHandle.pack(), golden::INVALID_HANDLE);
        assert_eq!(WebrtcError::Closed.pack(), golden::CLOSED);
        assert_eq!(WebrtcError::Busy.pack(), golden::BUSY);
        assert_eq!(WebrtcError::Timeout.pack(), golden::TIMEOUT);
        assert_eq!(WebrtcError::InvalidState.pack(), golden::INVALID_STATE);
        assert_eq!(WebrtcError::Stack.pack(), golden::STACK);
        // The shared panic boundary and raw readers return these words.
        assert_eq!(
            WebrtcError::InvalidArgument.pack(),
            keyguard_ffi::BRIDGE_INVALID_ARGUMENT
        );
        assert_eq!(WebrtcError::Panic.pack(), keyguard_ffi::BRIDGE_PANIC);
    }

    #[test]
    fn stack_errors_map_to_stable_codes() {
        use webrtc::error::Error;
        assert_eq!(
            WebrtcError::from_stack(&Error::ErrSendBufferFull),
            WebrtcError::Busy
        );
        assert_eq!(
            WebrtcError::from_stack(&Error::ErrDataChannelClosed),
            WebrtcError::Closed
        );
        assert_eq!(
            WebrtcError::from_stack(&Error::ErrDataChannelNotOpen),
            WebrtcError::InvalidState
        );
        assert_eq!(
            WebrtcError::from_stack(&Error::ErrMaxDataChannelID),
            WebrtcError::Stack
        );
    }
}
