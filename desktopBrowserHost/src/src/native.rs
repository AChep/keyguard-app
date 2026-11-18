//! Native messaging: each message is a native-endian `u32` length, then
//! UTF-8 JSON. The extension sends Noise frames; the host sends Noise frames
//! and the status of its connection to the app.
use crate::status::Status;
use anyhow::{Context, Result, ensure};
use base64::{Engine, engine::general_purpose::STANDARD};
use keyguard_cli_protocol::frame::BROWSER_MAX_FRAME;
use serde::Serialize;
use serde_json::Value;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

/// The largest message the host accepts from the extension.
pub(crate) const MAX_INCOMING: usize = 128 * 1024;

/// Browsers refuse a message of 1 MB or more from the host.
pub(crate) const MAX_OUTGOING: usize = 1024 * 1024 - 1;

/// A message of the extension.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum Incoming {
    /// A Noise frame for the app.
    Noise(Vec<u8>),
    /// A message of a type this host does not know.
    Ignored,
}

/// A message to the extension.
#[derive(Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub(crate) enum Outgoing {
    Noise { data: String },
    Status { status: Status },
}

impl Outgoing {
    pub(crate) fn noise(frame: &[u8]) -> Self {
        Self::Noise {
            data: STANDARD.encode(frame),
        }
    }
}

/// Reads the next message, or `None` once the browser closes the stream.
/// A message that is too large fails before it is read.
pub(crate) async fn read_message<R: AsyncRead + Unpin>(stream: &mut R) -> Result<Option<Vec<u8>>> {
    let mut length = [0; 4];
    match stream.read_exact(&mut length).await {
        Ok(_) => {}
        Err(error) if error.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(error) => return Err(error.into()),
    }
    let length = u32::from_ne_bytes(length) as usize;
    ensure!(
        length <= MAX_INCOMING,
        "a message from the browser is too large"
    );
    let mut bytes = vec![0; length];
    match stream.read_exact(&mut bytes).await {
        Ok(_) => Ok(Some(bytes)),
        Err(error) if error.kind() == std::io::ErrorKind::UnexpectedEof => Ok(None),
        Err(error) => Err(error.into()),
    }
}

/// Parses a message of the extension. Fails on one that is not a JSON
/// object, or on a Noise frame that is not 1 to 65,535 bytes of Base64.
/// Errors never quote the message.
pub(crate) fn parse_message(bytes: &[u8]) -> Result<Incoming> {
    let value = serde_json::from_slice::<Value>(bytes)
        .ok()
        .context("a message from the browser is not JSON")?;
    let message = value
        .as_object()
        .context("a message from the browser is not a JSON object")?;
    if message.get("type").and_then(Value::as_str) != Some("noise") {
        return Ok(Incoming::Ignored);
    }
    let frame = message
        .get("data")
        .and_then(Value::as_str)
        .and_then(|data| STANDARD.decode(data).ok())
        .context("a Noise frame from the browser is not Base64")?;
    ensure!(
        (1..=BROWSER_MAX_FRAME).contains(&frame.len()),
        "a Noise frame from the browser has an invalid size"
    );
    Ok(Incoming::Noise(frame))
}

/// Writes one message and flushes it, so that the browser reads it at once.
pub(crate) async fn write_message<W: AsyncWrite + Unpin>(
    stream: &mut W,
    message: &Outgoing,
) -> Result<()> {
    // The length goes in front of the JSON, so that the message is one write.
    let mut bytes = vec![0; size_of::<u32>()];
    serde_json::to_writer(&mut bytes, message)?;
    let (length, json) = bytes.split_at_mut(size_of::<u32>());
    ensure!(
        json.len() <= MAX_OUTGOING,
        "a message to the browser is too large"
    );
    length.copy_from_slice(&(json.len() as u32).to_ne_bytes());
    stream.write_all(&bytes).await?;
    stream.flush().await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn encoded(value: &Value) -> Vec<u8> {
        let json = serde_json::to_vec(value).unwrap();
        let mut bytes = (json.len() as u32).to_ne_bytes().to_vec();
        bytes.extend_from_slice(&json);
        bytes
    }

    async fn round_trip(message: &Outgoing) -> Value {
        let (mut reader, mut writer) = tokio::io::duplex(2 * MAX_OUTGOING);
        write_message(&mut writer, message).await.unwrap();
        drop(writer);
        let mut bytes = Vec::new();
        reader.read_to_end(&mut bytes).await.unwrap();
        let length = u32::from_ne_bytes(bytes[..4].try_into().unwrap()) as usize;
        assert_eq!(length, bytes.len() - 4);
        serde_json::from_slice(&bytes[4..]).unwrap()
    }

    #[tokio::test]
    async fn messages_are_native_endian_json() {
        assert_eq!(
            round_trip(&Outgoing::Status {
                status: Status::Ready
            })
            .await,
            json!({"type":"status","status":"ready"})
        );
        assert_eq!(
            round_trip(&Outgoing::noise(&[0, 1, 2, 0xff])).await,
            json!({"type":"noise","data":"AAEC/w=="})
        );
    }

    #[tokio::test]
    async fn noise_frames_round_trip() {
        let frame = (0..=255)
            .cycle()
            .take(BROWSER_MAX_FRAME)
            .collect::<Vec<u8>>();
        let message = round_trip(&Outgoing::noise(&frame)).await;
        let bytes = serde_json::to_vec(&message).unwrap();
        assert_eq!(parse_message(&bytes).unwrap(), Incoming::Noise(frame));
    }

    #[tokio::test]
    async fn the_largest_noise_frame_fits_a_message_to_the_browser() {
        let mut largest = Vec::new();
        write_message(&mut largest, &Outgoing::noise(&[0xff; BROWSER_MAX_FRAME]))
            .await
            .unwrap();
        // 87,380 Base64 characters and the JSON around them.
        assert!(largest.len() < 88 * 1024, "{}", largest.len());
        assert!(largest.len() <= MAX_INCOMING);
    }

    #[tokio::test]
    async fn incoming_messages_are_read_until_the_browser_closes() {
        let first = encoded(&json!({"type":"noise","data":"AQ=="}));
        let second = encoded(&json!({"type":"future"}));
        let mut stream = [first.clone(), second.clone()].concat();
        let mut reader = stream.as_slice();
        assert_eq!(
            read_message(&mut reader).await.unwrap().unwrap(),
            first[4..]
        );
        assert_eq!(
            read_message(&mut reader).await.unwrap().unwrap(),
            second[4..]
        );
        assert_eq!(read_message(&mut reader).await.unwrap(), None);
        // A browser that closes in the middle of a message closed the stream.
        stream.truncate(first.len() + 2);
        let mut reader = stream.as_slice();
        read_message(&mut reader).await.unwrap().unwrap();
        assert_eq!(read_message(&mut reader).await.unwrap(), None);
    }

    #[tokio::test]
    async fn incoming_messages_are_capped_before_they_are_read() {
        let largest = vec![b' '; MAX_INCOMING];
        let mut stream = (MAX_INCOMING as u32).to_ne_bytes().to_vec();
        stream.extend_from_slice(&largest);
        let mut reader = stream.as_slice();
        assert_eq!(read_message(&mut reader).await.unwrap().unwrap(), largest);
        // Only the length of an oversized message is there: it fails at once.
        let oversized = (MAX_INCOMING as u32 + 1).to_ne_bytes();
        let mut reader = oversized.as_slice();
        assert!(read_message(&mut reader).await.is_err());
    }

    #[test]
    fn unknown_messages_are_ignored() {
        for value in [
            json!({"type":"future","data":"AQ=="}),
            json!({"type":"status","status":"ready"}),
            json!({"type":1}),
            json!({}),
        ] {
            let bytes = serde_json::to_vec(&value).unwrap();
            assert_eq!(parse_message(&bytes).unwrap(), Incoming::Ignored, "{value}");
        }
    }

    #[test]
    fn invalid_messages_fail() {
        let too_large = STANDARD.encode(vec![0; BROWSER_MAX_FRAME + 1]);
        for bytes in [
            b"".to_vec(),
            b"not json".to_vec(),
            b"[]".to_vec(),
            b"\"noise\"".to_vec(),
            br#"{"type":"noise"}"#.to_vec(),
            br#"{"type":"noise","data":1}"#.to_vec(),
            br#"{"type":"noise","data":""}"#.to_vec(),
            // Base64url with or without its padding, and Base64 without it.
            br#"{"type":"noise","data":"-_8="}"#.to_vec(),
            br#"{"type":"noise","data":"-_8"}"#.to_vec(),
            br#"{"type":"noise","data":"AQ"}"#.to_vec(),
            serde_json::to_vec(&json!({"type":"noise","data":too_large})).unwrap(),
        ] {
            let error = parse_message(&bytes).unwrap_err().to_string();
            assert!(error.starts_with("a "), "{error}");
        }
    }
}
