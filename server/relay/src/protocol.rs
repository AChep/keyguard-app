//! The wire format: ids, frames and error codes.

use axum::extract::ws::{Message, Utf8Bytes, close_code};
use serde::de::{self, Deserializer};
use serde::{Deserialize, Serialize, Serializer};

/// Length of a room id in bytes (64 hex characters on the wire).
pub(crate) const ROOM_ID_LEN: usize = 32;
/// Length of a peer id in bytes (32 hex characters on the wire).
pub(crate) const PEER_ID_LEN: usize = 16;

pub(crate) type RoomId = [u8; ROOM_ID_LEN];
pub(crate) type PeerId = [u8; PEER_ID_LEN];

/// First byte of a relay-forward binary frame:
/// `0x03 | room id (32 bytes) | peer id (16 bytes) | payload`.
pub(crate) const FORWARD_TAG: u8 = 0x03;
/// Where the peer id of a relay-forward frame starts, after the tag and the room id.
pub(crate) const FORWARD_PEER_OFFSET: usize = 1 + ROOM_ID_LEN;
pub(crate) const FORWARD_HEADER_LEN: usize = FORWARD_PEER_OFFSET + PEER_ID_LEN;

/// The largest header of a client's WebSocket frame: two bytes, an 8-byte length and a
/// 4-byte mask.
pub(crate) const MAX_CLIENT_FRAME_HEADER: u64 = 14;

/// Parses exactly `2 * N` lowercase hex characters.
pub(crate) fn decode_hex<const N: usize>(text: &str) -> Option<[u8; N]> {
    fn nibble(c: u8) -> Option<u8> {
        match c {
            b'0'..=b'9' => Some(c - b'0'),
            b'a'..=b'f' => Some(c - b'a' + 10),
            _ => None,
        }
    }
    let text = text.as_bytes();
    if text.len() != N * 2 {
        return None;
    }
    let mut out = [0u8; N];
    let (pairs, _) = text.as_chunks::<2>();
    for (byte, [high, low]) in out.iter_mut().zip(pairs) {
        *byte = (nibble(*high)? << 4) | nibble(*low)?;
    }
    Some(out)
}

/// Lowercase hex.
pub(crate) fn encode_hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push(char::from(DIGITS[usize::from(byte >> 4)]));
        out.push(char::from(DIGITS[usize::from(byte & 0x0f)]));
    }
    out
}

/// The size a message counts with against rate limits and the egress budget.
pub(crate) fn message_len(message: &Message) -> usize {
    match message {
        Message::Text(text) => text.len(),
        Message::Binary(data) | Message::Ping(data) | Message::Pong(data) => data.len(),
        Message::Close(_) => 0,
    }
}

/// `v` of the `welcome` frame. A change that old clients can't follow raises it;
/// additions come as new [`FEATURES`].
pub(crate) const PROTOCOL_VERSION: u32 = 1;

/// `features` of the `welcome` frame: what this relay supports beyond version 1.
/// `room-forward` is the binary relay-forward frame that names its room.
pub(crate) const FEATURES: &[&str] = &["room-forward"];

/// [`FEATURES`] of a relay with storage: `mailbox` is the HTTP mailbox API.
pub(crate) const FEATURES_WITH_MAILBOX: &[&str] = &["room-forward", "mailbox"];

/// `code` of the `err` frame and `error` of an HTTP error. `Unauthorized`,
/// `FrameTooBig` and `BadFrame` close the connection.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ErrorCode {
    RoomFull,
    RateLimited,
    FrameTooBig,
    Unauthorized,
    BadFrame,
    NotJoined,
    TooManyRooms,
    /// A frame of a type this relay doesn't know, perhaps from a newer client.
    UnknownType,
    // HTTP only, from the mailbox.
    NotFound,
    TicketRequired,
    BadRequest,
    ItemTooBig,
    TagMismatch,
    /// `PUT` with `If-None-Match: *` for an item that exists.
    ItemExists,
    StorageFull,
    StorageUnavailable,
    Timeout,
}

impl ErrorCode {
    /// The codes of `err` frames, in declaration order.
    pub(crate) const FRAME: [Self; 8] = [
        Self::RoomFull,
        Self::RateLimited,
        Self::FrameTooBig,
        Self::Unauthorized,
        Self::BadFrame,
        Self::NotJoined,
        Self::TooManyRooms,
        Self::UnknownType,
    ];

    /// The wire name: the only place it is spelled out.
    pub(crate) fn as_str(self) -> &'static str {
        match self {
            Self::RoomFull => "room_full",
            Self::RateLimited => "rate_limited",
            Self::FrameTooBig => "frame_too_big",
            Self::Unauthorized => "unauthorized",
            Self::BadFrame => "bad_frame",
            Self::NotJoined => "not_joined",
            Self::TooManyRooms => "too_many_rooms",
            Self::UnknownType => "unknown_type",
            Self::NotFound => "not_found",
            Self::TicketRequired => "ticket_required",
            Self::BadRequest => "bad_request",
            Self::ItemTooBig => "item_too_big",
            Self::TagMismatch => "tag_mismatch",
            Self::ItemExists => "item_exists",
            Self::StorageFull => "storage_full",
            Self::StorageUnavailable => "storage_unavailable",
            Self::Timeout => "timeout",
        }
    }

    pub(crate) fn close_code(self) -> Option<u16> {
        match self {
            Self::Unauthorized | Self::BadFrame => Some(close_code::POLICY),
            Self::FrameTooBig => Some(close_code::SIZE),
            Self::RoomFull
            | Self::RateLimited
            | Self::NotJoined
            | Self::TooManyRooms
            | Self::UnknownType
            | Self::NotFound
            | Self::TicketRequired
            | Self::BadRequest
            | Self::ItemTooBig
            | Self::TagMismatch
            | Self::ItemExists
            | Self::StorageFull
            | Self::StorageUnavailable
            | Self::Timeout => None,
        }
    }
}

impl Serialize for ErrorCode {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(self.as_str())
    }
}

/// Receivers of a published message: `"*"` or a peer id on the wire.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Target {
    Everyone,
    Peer(PeerId),
}

/// Frames a client sends. Unknown fields are ignored for forward compatibility.
#[derive(Debug, PartialEq, Eq, Deserialize)]
#[serde(tag = "t", rename_all = "lowercase")]
pub(crate) enum ClientFrame {
    /// The connection joins under the peer id the relay assigned it.
    Join {
        #[serde(deserialize_with = "hex")]
        room: RoomId,
    },
    /// Without `room`, goes to the connection's only room.
    Pub {
        #[serde(default, deserialize_with = "optional_hex")]
        room: Option<RoomId>,
        #[serde(deserialize_with = "target")]
        to: Target,
        /// Base64, forwarded as sent.
        #[serde(deserialize_with = "base64_text")]
        data: String,
    },
    /// Without `room`, leaves every room.
    Leave {
        #[serde(default, deserialize_with = "optional_hex")]
        room: Option<RoomId>,
    },
    Ping,
    /// Any other `t`, perhaps from a newer client: answered, not fatal.
    #[serde(other)]
    Unknown,
}

impl ClientFrame {
    /// Parses a text frame, which must be a JSON object: serde would also read an array
    /// that starts with the tag.
    pub(crate) fn parse(text: &str) -> Option<Self> {
        let object = text
            .trim_start_matches([' ', '\t', '\n', '\r'])
            .starts_with('{');
        object.then(|| serde_json::from_str(text).ok()).flatten()
    }
}

fn hex<'de, D: Deserializer<'de>, const N: usize>(deserializer: D) -> Result<[u8; N], D::Error> {
    let text = String::deserialize(deserializer)?;
    decode_hex(&text).ok_or_else(|| de::Error::custom("expected lowercase hex"))
}

/// A missing or `null` id is `None`.
fn optional_hex<'de, D: Deserializer<'de>, const N: usize>(
    deserializer: D,
) -> Result<Option<[u8; N]>, D::Error> {
    Option::<String>::deserialize(deserializer)?
        .map(|text| decode_hex(&text).ok_or_else(|| de::Error::custom("expected lowercase hex")))
        .transpose()
}

fn target<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Target, D::Error> {
    match String::deserialize(deserializer)?.as_str() {
        "*" => Ok(Target::Everyone),
        to => decode_hex(to)
            .map(Target::Peer)
            .ok_or_else(|| de::Error::custom("expected \"*\" or a peer id")),
    }
}

fn base64_text<'de, D: Deserializer<'de>>(deserializer: D) -> Result<String, D::Error> {
    let text = String::deserialize(deserializer)?;
    if is_base64(&text) {
        Ok(text)
    } else {
        Err(de::Error::custom("expected base64"))
    }
}

/// Whether `text` has the alphabet, length and padding of standard base64. The relay
/// forwards it as is, so it is checked without decoding it.
fn is_base64(text: &str) -> bool {
    let bytes = text.as_bytes();
    let body = bytes
        .strip_suffix(b"==")
        .or_else(|| bytes.strip_suffix(b"="))
        .unwrap_or(bytes);
    bytes.len().is_multiple_of(4)
        && body
            .iter()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'+' | b'/'))
}

/// `state` of the `presence` frame.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub(crate) enum PresenceState {
    Join,
    Leave,
}

/// `limits` of the `welcome` frame: what a client needs to pace itself.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Limits {
    /// The largest text or binary message, in bytes.
    pub(crate) max_message_bytes: usize,
    /// Messages per second the connection may send, with a one-second burst.
    pub(crate) messages_per_second: u32,
    /// Bytes per second the connection may send, with a one-second burst.
    pub(crate) bytes_per_second: u64,
    pub(crate) rooms_per_connection: usize,
    pub(crate) peers_per_room: usize,
    /// Frames from its rooms that may wait for a connection that doesn't read, before
    /// the relay closes it.
    pub(crate) queued_frames: usize,
    /// A connection that sends nothing for this long is closed.
    pub(crate) idle_seconds: u64,
    /// The largest mailbox state item, in bytes. Only with storage.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) mailbox_state_bytes: Option<u64>,
    /// The largest mailbox blob item, in bytes. Only with storage.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) mailbox_blob_bytes: Option<u64>,
    /// The longest lifetime of a mailbox item. Only with storage.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) mailbox_ttl_seconds: Option<u64>,
}

/// Frames the relay sends. `room` is a room id in hex.
#[derive(Serialize)]
#[serde(tag = "t", rename_all = "lowercase")]
pub(crate) enum ServerFrame<'a> {
    /// The first frame on an authorized connection. `peer` is the id the relay assigned
    /// the connection, in every room it joins.
    Welcome {
        v: u32,
        peer: &'a str,
        features: &'a [&'a str],
        limits: Limits,
    },
    /// `ticket` raises the TURN allowance of ICE requests while this connection is in a
    /// room and active. It is the same on every join of the connection.
    Joined {
        room: &'a str,
        peers: Vec<String>,
        ticket: &'a str,
    },
    Presence {
        room: &'a str,
        peer: &'a str,
        state: PresenceState,
    },
    Msg {
        room: &'a str,
        from: &'a str,
        data: &'a str,
    },
    Pong,
    /// `room` names the room of a refused join.
    Err {
        code: ErrorCode,
        #[serde(skip_serializing_if = "Option::is_none")]
        room: Option<&'a str>,
    },
}

impl ServerFrame<'_> {
    pub(crate) fn to_message(&self) -> Message {
        let json = serde_json::to_string(self).expect("server frames have no maps or floats");
        Message::Text(Utf8Bytes::from(json))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hex_round_trips_lowercase_only() {
        let id: PeerId = [
            0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef, 0, 0, 0, 0, 0, 0, 0xff, 0x10,
        ];
        let text = encode_hex(&id);
        assert_eq!(text, "0123456789abcdef000000000000ff10");
        assert_eq!(decode_hex::<PEER_ID_LEN>(&text), Some(id));
        assert_eq!(decode_hex::<PEER_ID_LEN>(&text.to_uppercase()), None);
        assert_eq!(decode_hex::<PEER_ID_LEN>("0123"), None);
        assert_eq!(decode_hex::<2>("zz00"), None);
        assert_eq!(decode_hex::<2>("ABCD"), None);
        assert_eq!(decode_hex::<2>("abcd"), Some([0xab, 0xcd]));
    }

    #[test]
    fn client_frames_parse_into_their_variants() {
        let peer = encode_hex(&[2; PEER_ID_LEN]);
        // A peer id from an old client is an unknown field: the relay assigns it.
        let join = format!(
            r#"{{"t":"join","room":"{}","peer":"{peer}"}}"#,
            encode_hex(&[1; ROOM_ID_LEN])
        );
        assert_eq!(
            ClientFrame::parse(&join),
            Some(ClientFrame::Join {
                room: [1; ROOM_ID_LEN],
            })
        );
        let unicast = format!(r#"{{"t":"pub","to":"{peer}","data":"AAEC"}}"#);
        assert_eq!(
            ClientFrame::parse(&unicast),
            Some(ClientFrame::Pub {
                room: None,
                to: Target::Peer([2; PEER_ID_LEN]),
                data: "AAEC".to_owned(),
            })
        );
        assert_eq!(
            ClientFrame::parse(r#" {"t":"pub","room":null,"to":"*","data":""}"#),
            Some(ClientFrame::Pub {
                room: None,
                to: Target::Everyone,
                data: String::new(),
            })
        );
        let room = encode_hex(&[3; ROOM_ID_LEN]);
        assert_eq!(
            ClientFrame::parse(&format!(
                r#"{{"t":"pub","room":"{room}","to":"*","data":""}}"#
            )),
            Some(ClientFrame::Pub {
                room: Some([3; ROOM_ID_LEN]),
                to: Target::Everyone,
                data: String::new(),
            })
        );
        // Unknown fields are ignored.
        assert_eq!(
            ClientFrame::parse(r#"{"pad":"x","t":"ping","v":2}"#),
            Some(ClientFrame::Ping)
        );
        assert_eq!(
            ClientFrame::parse(r#"{"t":"leave"}"#),
            Some(ClientFrame::Leave { room: None })
        );
        assert_eq!(
            ClientFrame::parse(&format!(r#"{{"t":"leave","room":"{room}"}}"#)),
            Some(ClientFrame::Leave {
                room: Some([3; ROOM_ID_LEN]),
            })
        );
    }

    #[test]
    fn unknown_types_parse_as_unknown() {
        for text in [
            r#"{"t":"subscribe"}"#,
            r#"{"t":"Ping"}"#,
            r#"{"t":"","room":7}"#,
            r#"{"v":2,"t":"hello","features":["x"]}"#,
        ] {
            assert_eq!(
                ClientFrame::parse(text),
                Some(ClientFrame::Unknown),
                "{text}"
            );
        }
    }

    #[test]
    fn malformed_client_frames_are_refused() {
        for text in [
            r#"["ping"]"#,
            r#"{"type":"ping"}"#,
            r#"{"t":3}"#,
            r#"{"t":null}"#,
            r#"{"t":["ping"]}"#,
            r#"{"t":"join"}"#,
            r#"{"t":"pub","to":"*"}"#,
            r#"{"t":"pub","to":"*","data":"not base64!"}"#,
            r#"{"t":"pub","to":"everyone","data":""}"#,
            r#"{"t":"join","room":"00"}"#,
            r#"{"t":"pub","room":"00","to":"*","data":""}"#,
            r#"{"t":"pub","room":7,"to":"*","data":""}"#,
            r#"{"t":"leave","room":"00"}"#,
            "{not json",
        ] {
            assert_eq!(ClientFrame::parse(text), None, "{text}");
        }
    }

    #[test]
    fn welcome_carries_the_peer_id_features_and_limits() {
        let limits = Limits {
            max_message_bytes: 65_536,
            messages_per_second: 50,
            bytes_per_second: 2_097_152,
            rooms_per_connection: 16,
            peers_per_room: 4,
            queued_frames: 128,
            idle_seconds: 60,
            mailbox_state_bytes: None,
            mailbox_blob_bytes: None,
            mailbox_ttl_seconds: None,
        };
        let peer = encode_hex(&[0xab; PEER_ID_LEN]);
        let welcome = |features, limits| {
            let frame = ServerFrame::Welcome {
                v: PROTOCOL_VERSION,
                peer: &peer,
                features,
                limits,
            };
            let Message::Text(text) = frame.to_message() else {
                panic!("welcome is a text frame");
            };
            serde_json::from_str::<serde_json::Value>(&text).unwrap()
        };
        assert_eq!(
            welcome(FEATURES, limits),
            serde_json::json!({
                "t": "welcome",
                "v": 1,
                "peer": "ab".repeat(PEER_ID_LEN),
                "features": ["room-forward"],
                "limits": {
                    "maxMessageBytes": 65_536,
                    "messagesPerSecond": 50,
                    "bytesPerSecond": 2_097_152,
                    "roomsPerConnection": 16,
                    "peersPerRoom": 4,
                    "queuedFrames": 128,
                    "idleSeconds": 60,
                },
            })
        );
        let with_mailbox = welcome(
            FEATURES_WITH_MAILBOX,
            Limits {
                mailbox_state_bytes: Some(1_048_576),
                mailbox_blob_bytes: Some(17_825_792),
                mailbox_ttl_seconds: Some(604_800),
                ..limits
            },
        );
        assert_eq!(
            with_mailbox["features"],
            serde_json::json!(["room-forward", "mailbox"])
        );
        assert_eq!(with_mailbox["limits"]["mailboxStateBytes"], 1_048_576);
        assert_eq!(with_mailbox["limits"]["mailboxBlobBytes"], 17_825_792);
        assert_eq!(with_mailbox["limits"]["mailboxTtlSeconds"], 604_800);
    }

    #[test]
    fn base64_is_checked_without_decoding() {
        for text in ["", "AAEC", "aGVsbG8=", "aGk=", "+/+/", "AA=="] {
            assert!(is_base64(text), "{text}");
        }
        for text in [
            "AAE", "AAEC=", "A===", "====", "AA=A", "AA-_", "AA E", "AAEC\n",
        ] {
            assert!(!is_base64(text), "{text}");
        }
    }
}
