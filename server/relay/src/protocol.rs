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

/// First byte of a relay-forward binary frame: `0x02 | peer id (16 bytes) | payload`.
pub(crate) const FORWARD_TAG: u8 = 0x02;
pub(crate) const FORWARD_HEADER_LEN: usize = 1 + PEER_ID_LEN;

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
    PeerTaken,
}

impl ErrorCode {
    pub(crate) const ALL: [Self; 7] = [
        Self::RoomFull,
        Self::RateLimited,
        Self::FrameTooBig,
        Self::Unauthorized,
        Self::BadFrame,
        Self::NotJoined,
        Self::PeerTaken,
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
            Self::PeerTaken => "peer_taken",
        }
    }

    pub(crate) fn close_code(self) -> Option<u16> {
        match self {
            Self::Unauthorized | Self::BadFrame => Some(close_code::POLICY),
            Self::FrameTooBig => Some(close_code::SIZE),
            Self::RoomFull | Self::RateLimited | Self::NotJoined | Self::PeerTaken => None,
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
    Join {
        #[serde(deserialize_with = "hex")]
        room: RoomId,
        #[serde(deserialize_with = "hex")]
        peer: PeerId,
    },
    Pub {
        #[serde(deserialize_with = "target")]
        to: Target,
        /// Base64, forwarded as sent.
        #[serde(deserialize_with = "base64_text")]
        data: String,
    },
    Leave,
    Ping,
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

/// Frames the relay sends.
#[derive(Serialize)]
#[serde(tag = "t", rename_all = "lowercase")]
pub(crate) enum ServerFrame<'a> {
    /// `ticket` raises the TURN allowance of ICE requests while this connection is in a
    /// room and active.
    Joined {
        peers: Vec<String>,
        ticket: &'a str,
    },
    Presence {
        peer: &'a str,
        state: PresenceState,
    },
    Msg {
        from: &'a str,
        data: &'a str,
    },
    Pong,
    Err {
        code: ErrorCode,
    },
}

impl ServerFrame<'_> {
    pub(crate) fn to_message(&self) -> Message {
        let json = serde_json::to_string(self).expect("server frames have only string fields");
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
        let join = format!(
            r#"{{"t":"join","room":"{}","peer":"{peer}"}}"#,
            encode_hex(&[1; ROOM_ID_LEN])
        );
        assert_eq!(
            ClientFrame::parse(&join),
            Some(ClientFrame::Join {
                room: [1; ROOM_ID_LEN],
                peer: [2; PEER_ID_LEN],
            })
        );
        let unicast = format!(r#"{{"t":"pub","to":"{peer}","data":"AAEC"}}"#);
        assert_eq!(
            ClientFrame::parse(&unicast),
            Some(ClientFrame::Pub {
                to: Target::Peer([2; PEER_ID_LEN]),
                data: "AAEC".to_owned(),
            })
        );
        assert_eq!(
            ClientFrame::parse(r#" {"t":"pub","to":"*","data":""}"#),
            Some(ClientFrame::Pub {
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
            Some(ClientFrame::Leave)
        );
    }

    #[test]
    fn malformed_client_frames_are_refused() {
        for text in [
            r#"["ping"]"#,
            r#"{"type":"ping"}"#,
            r#"{"t":"subscribe"}"#,
            r#"{"t":"pub","to":"*"}"#,
            r#"{"t":"pub","to":"*","data":"not base64!"}"#,
            r#"{"t":"pub","to":"everyone","data":""}"#,
            r#"{"t":"join","room":"00"}"#,
            "{not json",
        ] {
            assert_eq!(ClientFrame::parse(text), None, "{text}");
        }
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
