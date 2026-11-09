//! Peer events and their wire encoding.
//!
//! An encoded event is one kind byte followed by the kind's payload, with no
//! length prefix: the export returns the total length.
//!
//! | kind | event                  | payload                                     |
//! |------|------------------------|---------------------------------------------|
//! | 1    | `IceCandidate`         | UTF-8 JSON `{candidate, sdpMid, sdpMLineIndex}` |
//! | 2    | `IceGatheringComplete` | none                                        |
//! | 3    | `ConnectionState`      | one [`ConnectionState`] byte                |
//! | 4    | `ChannelOpen`          | none                                        |
//! | 5    | `ChannelMessage`       | the message bytes                           |
//! | 6    | `ChannelClose`         | none                                        |
//! | 7    | `BufferedLow`          | none                                        |
//! | 8    | `SelectedPair`         | UTF-8 JSON `{localType, remoteType, protocol}` |
//! | 9    | `Error`                | event error code, `u32` little-endian       |

/// Wire kind of [`Event::IceCandidate`].
pub const KIND_ICE_CANDIDATE: u8 = 1;
/// Wire kind of [`Event::IceGatheringComplete`].
pub const KIND_ICE_GATHERING_COMPLETE: u8 = 2;
/// Wire kind of [`Event::ConnectionState`].
pub const KIND_CONNECTION_STATE: u8 = 3;
/// Wire kind of [`Event::ChannelOpen`].
pub const KIND_CHANNEL_OPEN: u8 = 4;
/// Wire kind of [`Event::ChannelMessage`].
pub const KIND_CHANNEL_MESSAGE: u8 = 5;
/// Wire kind of [`Event::ChannelClose`].
pub const KIND_CHANNEL_CLOSE: u8 = 6;
/// Wire kind of [`Event::BufferedLow`].
pub const KIND_BUFFERED_LOW: u8 = 7;
/// Wire kind of [`Event::SelectedPair`].
pub const KIND_SELECTED_PAIR: u8 = 8;
/// Wire kind of [`Event::Error`].
pub const KIND_ERROR: u8 = 9;

/// [`Event::Error`] code: the stack rejected a remote ICE candidate.
pub const EVENT_ERROR_REMOTE_CANDIDATE: u32 = 1;
/// [`Event::Error`] code: a STUN or TURN server failed while gathering.
pub const EVENT_ERROR_ICE_SERVER: u32 = 2;
/// [`Event::Error`] code: the data channel reported an error.
pub const EVENT_ERROR_CHANNEL: u32 = 3;

/// Peer connection state, as carried by [`Event::ConnectionState`].
#[repr(u8)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ConnectionState {
    /// A state this ABI version does not know.
    Unknown = 0,
    /// Created, nothing negotiated yet.
    New = 1,
    /// ICE or DTLS is connecting.
    Connecting = 2,
    /// ICE and DTLS are connected.
    Connected = 3,
    /// Connectivity was lost; it may recover.
    Disconnected = 4,
    /// Connectivity failed; it will not recover without an ICE restart.
    Failed = 5,
    /// The connection was closed.
    Closed = 6,
}

impl ConnectionState {
    pub(crate) fn from_stack(state: webrtc::peer_connection::RTCPeerConnectionState) -> Self {
        use webrtc::peer_connection::RTCPeerConnectionState as State;
        match state {
            State::New => Self::New,
            State::Connecting => Self::Connecting,
            State::Connected => Self::Connected,
            State::Disconnected => Self::Disconnected,
            State::Failed => Self::Failed,
            State::Closed => Self::Closed,
            _ => Self::Unknown,
        }
    }
}

/// An event of one peer, in delivery order.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Event {
    /// A local ICE candidate to send to the remote peer, as JSON.
    IceCandidate(String),
    /// Local ICE gathering finished.
    IceGatheringComplete,
    /// The peer connection state changed.
    ConnectionState(ConnectionState),
    /// The data channel opened.
    ChannelOpen,
    /// The data channel received a message.
    ChannelMessage(Vec<u8>),
    /// The data channel closed.
    ChannelClose,
    /// The channel's buffered amount fell to the low threshold.
    BufferedLow,
    /// The selected ICE candidate pair, as JSON.
    SelectedPair(String),
    /// A failure outside any call, as an `EVENT_ERROR_*` code.
    Error(u32),
}

impl Event {
    /// Returns the wire kind byte.
    #[must_use]
    pub const fn kind(&self) -> u8 {
        match self {
            Self::IceCandidate(_) => KIND_ICE_CANDIDATE,
            Self::IceGatheringComplete => KIND_ICE_GATHERING_COMPLETE,
            Self::ConnectionState(_) => KIND_CONNECTION_STATE,
            Self::ChannelOpen => KIND_CHANNEL_OPEN,
            Self::ChannelMessage(_) => KIND_CHANNEL_MESSAGE,
            Self::ChannelClose => KIND_CHANNEL_CLOSE,
            Self::BufferedLow => KIND_BUFFERED_LOW,
            Self::SelectedPair(_) => KIND_SELECTED_PAIR,
            Self::Error(_) => KIND_ERROR,
        }
    }

    /// Calls `write` with the kind byte, then with the payload.
    ///
    /// Adapters write each part straight into the caller's buffer, so a
    /// message is copied once.
    pub fn write_parts<E>(
        &self,
        mut write: impl FnMut(usize, &[u8]) -> Result<(), E>,
    ) -> Result<usize, E> {
        let error_code;
        let state;
        let payload: &[u8] = match self {
            Self::IceCandidate(json) | Self::SelectedPair(json) => json.as_bytes(),
            Self::ConnectionState(value) => {
                state = [*value as u8];
                &state
            }
            Self::ChannelMessage(bytes) => bytes,
            Self::Error(code) => {
                error_code = code.to_le_bytes();
                &error_code
            }
            Self::IceGatheringComplete
            | Self::ChannelOpen
            | Self::ChannelClose
            | Self::BufferedLow => &[],
        };
        write(0, &[self.kind()])?;
        if !payload.is_empty() {
            write(1, payload)?;
        }
        Ok(1 + payload.len())
    }

    /// Returns the encoded length.
    #[must_use]
    pub fn encoded_len(&self) -> usize {
        // Writing into nothing only measures.
        self.write_parts(|_, _| Ok::<_, ()>(())).unwrap_or(0)
    }

    /// Encodes into `out`, which must hold [`Self::encoded_len`] bytes.
    ///
    /// # Errors
    ///
    /// Returns `None` when `out` is too small; `out` may then be partly written.
    pub fn encode_into(&self, out: &mut [u8]) -> Option<usize> {
        self.write_parts(|offset, bytes| {
            out.get_mut(offset..offset + bytes.len())
                .map(|target| target.copy_from_slice(bytes))
                .ok_or(())
        })
        .ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::MAX_EVENT_BYTES;

    fn encode(event: &Event) -> Vec<u8> {
        let mut out = vec![0; MAX_EVENT_BYTES];
        let length = event.encode_into(&mut out).expect("the event must fit");
        assert_eq!(length, event.encoded_len());
        out.truncate(length);
        out
    }

    #[test]
    fn events_encode_to_the_documented_wire_layout() {
        assert_eq!(encode(&Event::IceGatheringComplete), [2]);
        assert_eq!(
            encode(&Event::ConnectionState(ConnectionState::Connected)),
            [3, 3]
        );
        assert_eq!(encode(&Event::ChannelOpen), [4]);
        assert_eq!(encode(&Event::ChannelMessage(vec![1, 2, 3])), [5, 1, 2, 3]);
        assert_eq!(encode(&Event::ChannelMessage(Vec::new())), [5]);
        assert_eq!(encode(&Event::ChannelClose), [6]);
        assert_eq!(encode(&Event::BufferedLow), [7]);
        assert_eq!(encode(&Event::Error(0x0102_0304)), [9, 4, 3, 2, 1]);
        assert_eq!(encode(&Event::IceCandidate("{}".into())), [1, b'{', b'}']);
        assert_eq!(encode(&Event::SelectedPair("{}".into())), [8, b'{', b'}']);
    }

    #[test]
    fn the_largest_message_fits_the_event_buffer() {
        let event = Event::ChannelMessage(vec![7; crate::MAX_MESSAGE_BYTES]);
        assert_eq!(event.encoded_len(), MAX_EVENT_BYTES);
        let mut out = vec![0; MAX_EVENT_BYTES - 1];
        assert_eq!(event.encode_into(&mut out), None);
    }
}
