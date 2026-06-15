use crate::packet_io::read_exact_or_eof;
use chacha20poly1305::aead::{AeadInPlace, KeyInit};
use chacha20poly1305::{ChaCha20Poly1305, Key, Nonce, Tag};
use hkdf::Hkdf;
use rand::RngCore;
use sha2::Sha256;
use std::io;
use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt};
use zeroize::Zeroize;

pub const PROTOCOL_VERSION: u8 = 2;
pub const SESSION_ID_LEN: usize = 16;
pub const SESSION_SECRET_LEN: usize = 32;
pub const CHALLENGE_LEN: usize = 32;
pub const MAX_FRAME_PAYLOAD_SIZE: usize = 1024 * 1024;

const MAGIC: [u8; 4] = *b"KSAG";
const FRAME_TYPE_TOOL_HELLO: u8 = 1;
const FRAME_TYPE_APP_HELLO: u8 = 2;
const FRAME_TYPE_PACKET: u8 = 3;
const TAG_LEN: usize = 16;
const HEADER_LEN: usize = 4 + 1 + 1 + 8 + 4;

pub type TransportResult<T> = std::result::Result<T, TransportError>;

#[derive(Debug)]
pub enum TransportError {
    Io(io::Error),
    Protocol(String),
    Authentication(String),
    Poisoned,
}

impl TransportError {
    fn protocol(message: impl Into<String>) -> Self {
        Self::Protocol(message.into())
    }

    fn authentication(message: impl Into<String>) -> Self {
        Self::Authentication(message.into())
    }
}

impl std::fmt::Display for TransportError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(err) => write!(f, "{err}"),
            Self::Protocol(message) | Self::Authentication(message) => f.write_str(message),
            Self::Poisoned => f.write_str("SSH agent transport is poisoned"),
        }
    }
}

impl std::error::Error for TransportError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io(err) => Some(err),
            _ => None,
        }
    }
}

pub struct SecureTransport<S> {
    stream: S,
    send_key: [u8; SESSION_SECRET_LEN],
    receive_key: [u8; SESSION_SECRET_LEN],
    send_nonce_prefix: [u8; 4],
    receive_nonce_prefix: [u8; 4],
    send_counter: u64,
    receive_counter: u64,
    poisoned: bool,
}

impl<S> SecureTransport<S>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    pub async fn connect_as_tool(
        stream: S,
        session_id: &[u8],
        session_secret: &[u8],
    ) -> TransportResult<Self> {
        validate_session(session_id, session_secret)?;
        let mut transport = Self::new_tool(stream, session_id, session_secret)?;

        let tool_challenge = random_bytes(CHALLENGE_LEN);
        let mut hello_payload = Vec::with_capacity(SESSION_ID_LEN + CHALLENGE_LEN);
        hello_payload.extend_from_slice(session_id);
        hello_payload.extend_from_slice(&tool_challenge);
        transport
            .write_frame(FRAME_TYPE_TOOL_HELLO, &hello_payload)
            .await?;

        let app_hello = transport.read_frame(FRAME_TYPE_APP_HELLO).await?;
        if app_hello.len() != SESSION_ID_LEN + CHALLENGE_LEN + CHALLENGE_LEN {
            return transport.poison(TransportError::protocol(format!(
                "Invalid app hello payload size={}",
                app_hello.len()
            )));
        }
        if &app_hello[..SESSION_ID_LEN] != session_id {
            return transport.poison(TransportError::protocol("App hello session id mismatch"));
        }
        if &app_hello[SESSION_ID_LEN..SESSION_ID_LEN + CHALLENGE_LEN] != tool_challenge.as_slice() {
            return transport.poison(TransportError::protocol("App hello challenge mismatch"));
        }

        Ok(transport)
    }

    pub async fn read_packet(&mut self) -> TransportResult<Option<Vec<u8>>> {
        self.read_frame_if_present(FRAME_TYPE_PACKET).await
    }

    pub async fn write_packet(&mut self, packet: &[u8]) -> TransportResult<()> {
        self.ensure_available()?;
        if packet.len() > MAX_FRAME_PAYLOAD_SIZE {
            return Err(TransportError::protocol(format!(
                "SSH agent packet too large: {} bytes",
                packet.len()
            )));
        }
        self.write_frame(FRAME_TYPE_PACKET, packet).await
    }

    async fn write_frame(&mut self, frame_type: u8, payload: &[u8]) -> TransportResult<()> {
        self.ensure_available()?;
        if payload.len() > MAX_FRAME_PAYLOAD_SIZE {
            return Err(TransportError::protocol(format!(
                "SSH agent payload too large: {} bytes",
                payload.len()
            )));
        }

        let counter = self.send_counter;
        let header = match build_header(frame_type, counter, payload.len() + TAG_LEN) {
            Ok(header) => header,
            Err(err) => return self.poison(err),
        };
        let ciphertext = match encrypt_payload(
            &self.send_key,
            &self.send_nonce_prefix,
            counter,
            &header,
            payload,
        ) {
            Ok(ciphertext) => ciphertext,
            Err(err) => return self.poison(err),
        };

        if let Err(err) = self.stream.write_all(&header).await {
            return self.poison(TransportError::Io(err));
        }
        if let Err(err) = self.stream.write_all(&ciphertext).await {
            return self.poison(TransportError::Io(err));
        }
        if let Err(err) = self.stream.flush().await {
            return self.poison(TransportError::Io(err));
        }
        if let Err(err) = increment_counter(&mut self.send_counter) {
            return self.poison(err);
        }
        Ok(())
    }

    async fn read_frame(&mut self, expected_type: u8) -> TransportResult<Vec<u8>> {
        self.read_frame_if_present(expected_type)
            .await?
            .ok_or_else(|| {
                TransportError::protocol(format!(
                    "SSH agent stream closed before frame type={expected_type}"
                ))
            })
    }

    async fn read_frame_if_present(
        &mut self,
        expected_type: u8,
    ) -> TransportResult<Option<Vec<u8>>> {
        self.ensure_available()?;
        let header = match read_header(&mut self.stream).await {
            Ok(Some(header)) => header,
            Ok(None) => return Ok(None),
            Err(err) => return self.poison(err),
        };
        if header.frame_type != expected_type {
            return self.poison(TransportError::protocol(format!(
                "Unexpected SSH agent frame type={} expected={}",
                header.frame_type, expected_type
            )));
        }
        if header.version != PROTOCOL_VERSION {
            return self.poison(TransportError::protocol(format!(
                "Unsupported secure SSH agent version={}",
                header.version
            )));
        }
        if header.counter != self.receive_counter {
            return self.poison(TransportError::protocol(format!(
                "Invalid SSH agent counter={} expected={}",
                header.counter, self.receive_counter
            )));
        }
        if !(TAG_LEN..=(MAX_FRAME_PAYLOAD_SIZE + TAG_LEN)).contains(&header.payload_len) {
            return self.poison(TransportError::protocol(format!(
                "Invalid SSH agent payload length={}",
                header.payload_len
            )));
        }

        let mut ciphertext = vec![0u8; header.payload_len];
        let Some(()) = (match read_exact_or_eof(
            &mut self.stream,
            &mut ciphertext,
            "Truncated SSH agent frame payload",
        )
        .await
        {
            Ok(result) => result,
            Err(err) => return self.poison(TransportError::Io(err)),
        }) else {
            return self.poison(TransportError::protocol(
                "Truncated SSH agent frame payload",
            ));
        };

        let plaintext = match decrypt_payload(
            &self.receive_key,
            &self.receive_nonce_prefix,
            header.counter,
            &header.raw,
            &ciphertext,
        ) {
            Ok(plaintext) => plaintext,
            Err(err) => return self.poison(err),
        };
        if let Err(err) = increment_counter(&mut self.receive_counter) {
            return self.poison(err);
        }
        Ok(Some(plaintext))
    }

    fn ensure_available(&self) -> TransportResult<()> {
        if self.poisoned {
            return Err(TransportError::Poisoned);
        }
        Ok(())
    }

    fn poison<T>(&mut self, err: TransportError) -> TransportResult<T> {
        self.poisoned = true;
        Err(err)
    }

    fn new_tool(stream: S, session_id: &[u8], session_secret: &[u8]) -> TransportResult<Self> {
        Self::new_with_labels(
            stream,
            session_id,
            session_secret,
            "tool-to-app",
            "app-to-tool",
        )
    }

    #[cfg(test)]
    fn new_app(stream: S, session_id: &[u8], session_secret: &[u8]) -> TransportResult<Self> {
        Self::new_with_labels(
            stream,
            session_id,
            session_secret,
            "app-to-tool",
            "tool-to-app",
        )
    }

    fn new_with_labels(
        stream: S,
        session_id: &[u8],
        session_secret: &[u8],
        send_label: &str,
        receive_label: &str,
    ) -> TransportResult<Self> {
        Ok(Self {
            stream,
            send_key: derive_bytes(
                session_secret,
                session_id,
                &format!("keyguard-android-ssh-agent:{send_label}:key"),
            )?,
            receive_key: derive_bytes(
                session_secret,
                session_id,
                &format!("keyguard-android-ssh-agent:{receive_label}:key"),
            )?,
            send_nonce_prefix: derive_bytes(
                session_secret,
                session_id,
                &format!("keyguard-android-ssh-agent:{send_label}:nonce"),
            )?,
            receive_nonce_prefix: derive_bytes(
                session_secret,
                session_id,
                &format!("keyguard-android-ssh-agent:{receive_label}:nonce"),
            )?,
            send_counter: 0,
            receive_counter: 0,
            poisoned: false,
        })
    }
}

impl<S> Drop for SecureTransport<S> {
    fn drop(&mut self) {
        self.send_key.zeroize();
        self.receive_key.zeroize();
        self.send_nonce_prefix.zeroize();
        self.receive_nonce_prefix.zeroize();
    }
}

struct FrameHeader {
    raw: [u8; HEADER_LEN],
    version: u8,
    frame_type: u8,
    counter: u64,
    payload_len: usize,
}

fn validate_session(session_id: &[u8], session_secret: &[u8]) -> TransportResult<()> {
    if session_id.len() != SESSION_ID_LEN {
        return Err(TransportError::protocol(format!(
            "Session id must be exactly {} bytes, got {}",
            SESSION_ID_LEN,
            session_id.len()
        )));
    }
    if session_secret.len() != SESSION_SECRET_LEN {
        return Err(TransportError::protocol(format!(
            "Session secret must be exactly {} bytes, got {}",
            SESSION_SECRET_LEN,
            session_secret.len()
        )));
    }
    Ok(())
}

fn build_header(
    frame_type: u8,
    counter: u64,
    payload_len: usize,
) -> TransportResult<[u8; HEADER_LEN]> {
    let payload_len = u32::try_from(payload_len)
        .map_err(|_| TransportError::protocol("SSH agent frame too large"))?;
    let mut header = [0u8; HEADER_LEN];
    header[..4].copy_from_slice(&MAGIC);
    header[4] = PROTOCOL_VERSION;
    header[5] = frame_type;
    header[6..14].copy_from_slice(&counter.to_be_bytes());
    header[14..18].copy_from_slice(&payload_len.to_be_bytes());
    Ok(header)
}

async fn read_header<S>(stream: &mut S) -> TransportResult<Option<FrameHeader>>
where
    S: AsyncRead + Unpin,
{
    let mut header = [0u8; HEADER_LEN];
    let Some(()) = read_exact_or_eof(stream, &mut header, "Truncated SSH agent frame header")
        .await
        .map_err(TransportError::Io)?
    else {
        return Ok(None);
    };
    if header[..4] != MAGIC {
        return Err(TransportError::protocol("Invalid SSH agent frame magic"));
    }

    Ok(Some(FrameHeader {
        raw: header,
        version: header[4],
        frame_type: header[5],
        counter: {
            let mut counter = [0u8; 8];
            counter.copy_from_slice(&header[6..14]);
            u64::from_be_bytes(counter)
        },
        payload_len: {
            let mut payload_len = [0u8; 4];
            payload_len.copy_from_slice(&header[14..18]);
            u32::from_be_bytes(payload_len) as usize
        },
    }))
}

fn encrypt_payload(
    key_bytes: &[u8; SESSION_SECRET_LEN],
    nonce_prefix: &[u8; 4],
    counter: u64,
    header: &[u8; HEADER_LEN],
    payload: &[u8],
) -> TransportResult<Vec<u8>> {
    let key = Key::from_slice(key_bytes);
    let nonce = build_nonce(nonce_prefix, counter);
    let cipher = ChaCha20Poly1305::new(key);
    let mut buffer = payload.to_vec();
    let tag = cipher
        .encrypt_in_place_detached(Nonce::from_slice(&nonce), header, &mut buffer)
        .map_err(|_| TransportError::authentication("Failed to encrypt SSH agent payload"))?;
    buffer.extend_from_slice(&tag);
    Ok(buffer)
}

fn decrypt_payload(
    key_bytes: &[u8; SESSION_SECRET_LEN],
    nonce_prefix: &[u8; 4],
    counter: u64,
    header: &[u8; HEADER_LEN],
    payload: &[u8],
) -> TransportResult<Vec<u8>> {
    if payload.len() < TAG_LEN {
        return Err(TransportError::protocol(
            "SSH agent payload missing authentication tag",
        ));
    }
    let split = payload.len() - TAG_LEN;
    let mut buffer = payload[..split].to_vec();
    let tag = Tag::from_slice(&payload[split..]);
    let nonce = build_nonce(nonce_prefix, counter);
    let key = Key::from_slice(key_bytes);
    let cipher = ChaCha20Poly1305::new(key);
    cipher
        .decrypt_in_place_detached(Nonce::from_slice(&nonce), header, &mut buffer, tag)
        .map_err(|_| TransportError::authentication("SSH agent payload authentication failed"))?;
    Ok(buffer)
}

fn build_nonce(prefix: &[u8; 4], counter: u64) -> [u8; 12] {
    let mut nonce = [0u8; 12];
    nonce[..4].copy_from_slice(prefix);
    nonce[4..].copy_from_slice(&counter.to_be_bytes());
    nonce
}

fn derive_bytes<const N: usize>(
    session_secret: &[u8],
    session_id: &[u8],
    label: &str,
) -> TransportResult<[u8; N]> {
    let hkdf = Hkdf::<Sha256>::new(Some(session_id), session_secret);
    let mut out = [0u8; N];
    hkdf.expand(label.as_bytes(), &mut out)
        .map_err(|_| TransportError::protocol("Failed to derive SSH agent key material"))?;
    Ok(out)
}

fn increment_counter(counter: &mut u64) -> TransportResult<()> {
    *counter = counter
        .checked_add(1)
        .ok_or_else(|| TransportError::protocol("SSH agent counter overflow"))?;
    Ok(())
}

fn random_bytes(size: usize) -> Vec<u8> {
    let mut bytes = vec![0u8; size];
    rand::thread_rng().fill_bytes(&mut bytes);
    bytes
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::pin::Pin;
    use std::task::{Context, Poll};
    use tokio::io::{duplex, AsyncRead, ReadBuf};

    #[tokio::test]
    async fn tool_handshake_and_packet_round_trip() {
        let (tool_stream, app_stream) = duplex(64 * 1024);
        let session_id = [0x11; SESSION_ID_LEN];
        let session_secret = [0x22; SESSION_SECRET_LEN];

        let tool = tokio::spawn(async move {
            let mut transport =
                SecureTransport::connect_as_tool(tool_stream, &session_id, &session_secret)
                    .await
                    .unwrap();
            transport.write_packet(b"hello").await.unwrap();
            transport.read_packet().await.unwrap().unwrap()
        });

        let mut app = super_test_open_as_app(app_stream, &session_id, &session_secret)
            .await
            .unwrap();
        let packet = app.read_packet().await.unwrap().unwrap();
        assert_eq!(packet, b"hello");
        app.write_packet(b"world").await.unwrap();

        let response = tool.await.unwrap();
        assert_eq!(response, b"world");
    }

    #[tokio::test]
    async fn tool_handshake_rejects_bad_session_id() {
        let (tool_stream, app_stream) = duplex(64 * 1024);
        let session_id = [0x11; SESSION_ID_LEN];
        let session_secret = [0x22; SESSION_SECRET_LEN];

        let tool = tokio::spawn(async move {
            match SecureTransport::connect_as_tool(tool_stream, &session_id, &session_secret).await
            {
                Ok(_) => panic!("tool handshake unexpectedly succeeded"),
                Err(err) => err.to_string(),
            }
        });

        let app_result =
            super_test_open_as_app(app_stream, &[0x33; SESSION_ID_LEN], &session_secret).await;

        let message = tool.await.unwrap();
        assert!(
            app_result.is_err()
                || message.contains("authentication failed")
                || message.contains("session id mismatch"),
            "unexpected handshake failure: tool={message}",
        );
    }

    #[tokio::test]
    async fn read_packet_returns_none_on_clean_frame_boundary_eof() {
        let (tool_stream, app_stream) = duplex(64 * 1024);
        let session_id = [0x11; SESSION_ID_LEN];
        let session_secret = [0x22; SESSION_SECRET_LEN];

        let mut transport =
            SecureTransport::new_tool(tool_stream, &session_id, &session_secret).unwrap();
        drop(app_stream);

        assert_eq!(transport.read_packet().await.unwrap(), None);
    }

    #[tokio::test]
    async fn read_packet_rejects_truncated_header() {
        let (tool_stream, mut app_stream) = duplex(64 * 1024);
        let session_id = [0x11; SESSION_ID_LEN];
        let session_secret = [0x22; SESSION_SECRET_LEN];

        let mut transport =
            SecureTransport::new_tool(tool_stream, &session_id, &session_secret).unwrap();
        app_stream.write_all(&[0xAA]).await.unwrap();
        drop(app_stream);

        let err = transport.read_packet().await.unwrap_err();
        assert!(err.to_string().contains("Truncated SSH agent frame header"));
    }

    #[tokio::test]
    async fn read_packet_rejects_truncated_payload() {
        let (tool_stream, mut app_stream) = duplex(64 * 1024);
        let session_id = [0x11; SESSION_ID_LEN];
        let session_secret = [0x22; SESSION_SECRET_LEN];

        let mut transport =
            SecureTransport::new_tool(tool_stream, &session_id, &session_secret).unwrap();
        let frame = super_test_build_app_frame(
            &session_id,
            &session_secret,
            0,
            FRAME_TYPE_PACKET,
            b"hello",
        )
        .unwrap();
        let truncated_len = frame.len() - 3;
        app_stream.write_all(&frame[..truncated_len]).await.unwrap();
        drop(app_stream);

        let err = transport.read_packet().await.unwrap_err();
        assert!(err
            .to_string()
            .contains("Truncated SSH agent frame payload"));
    }

    #[tokio::test]
    async fn read_packet_rejects_wrong_frame_type() {
        let (tool_stream, mut app_stream) = duplex(64 * 1024);
        let session_id = [0x11; SESSION_ID_LEN];
        let session_secret = [0x22; SESSION_SECRET_LEN];

        let mut transport =
            SecureTransport::new_tool(tool_stream, &session_id, &session_secret).unwrap();
        let frame = super_test_build_app_frame(
            &session_id,
            &session_secret,
            0,
            FRAME_TYPE_APP_HELLO,
            b"hello",
        )
        .unwrap();
        app_stream.write_all(&frame).await.unwrap();

        let err = transport.read_packet().await.unwrap_err();
        assert!(err.to_string().contains("Unexpected SSH agent frame type"));
        assert!(matches!(
            transport.read_packet().await.unwrap_err(),
            TransportError::Poisoned
        ));
    }

    #[tokio::test]
    async fn read_packet_rejects_wrong_version() {
        let (tool_stream, mut app_stream) = duplex(64 * 1024);
        let session_id = [0x11; SESSION_ID_LEN];
        let session_secret = [0x22; SESSION_SECRET_LEN];

        let mut transport =
            SecureTransport::new_tool(tool_stream, &session_id, &session_secret).unwrap();
        let mut frame = super_test_build_app_frame(
            &session_id,
            &session_secret,
            0,
            FRAME_TYPE_PACKET,
            b"hello",
        )
        .unwrap();
        frame[4] = PROTOCOL_VERSION.wrapping_add(1);
        app_stream.write_all(&frame).await.unwrap();

        let err = transport.read_packet().await.unwrap_err();
        assert!(err
            .to_string()
            .contains("Unsupported secure SSH agent version"));
    }

    #[tokio::test]
    async fn read_packet_rejects_wrong_counter() {
        let (tool_stream, mut app_stream) = duplex(64 * 1024);
        let session_id = [0x11; SESSION_ID_LEN];
        let session_secret = [0x22; SESSION_SECRET_LEN];

        let mut transport =
            SecureTransport::new_tool(tool_stream, &session_id, &session_secret).unwrap();
        let frame = super_test_build_app_frame(
            &session_id,
            &session_secret,
            1,
            FRAME_TYPE_PACKET,
            b"hello",
        )
        .unwrap();
        app_stream.write_all(&frame).await.unwrap();

        let err = transport.read_packet().await.unwrap_err();
        assert!(err.to_string().contains("Invalid SSH agent counter"));
    }

    #[tokio::test]
    async fn read_packet_rejects_oversize_payload_length() {
        let (tool_stream, mut app_stream) = duplex(64 * 1024);
        let session_id = [0x11; SESSION_ID_LEN];
        let session_secret = [0x22; SESSION_SECRET_LEN];

        let mut transport =
            SecureTransport::new_tool(tool_stream, &session_id, &session_secret).unwrap();
        let mut frame = super_test_build_app_frame(
            &session_id,
            &session_secret,
            0,
            FRAME_TYPE_PACKET,
            b"hello",
        )
        .unwrap();
        let invalid_len = (MAX_FRAME_PAYLOAD_SIZE + TAG_LEN + 1) as u32;
        frame[14..18].copy_from_slice(&invalid_len.to_be_bytes());
        app_stream.write_all(&frame).await.unwrap();

        let err = transport.read_packet().await.unwrap_err();
        assert!(err.to_string().contains("Invalid SSH agent payload length"));
    }

    #[tokio::test]
    async fn read_packet_poisoned_after_authentication_failure() {
        let (tool_stream, mut app_stream) = duplex(64 * 1024);
        let session_id = [0x11; SESSION_ID_LEN];
        let session_secret = [0x22; SESSION_SECRET_LEN];

        let mut transport =
            SecureTransport::new_tool(tool_stream, &session_id, &session_secret).unwrap();
        let mut frame = super_test_build_app_frame(
            &session_id,
            &session_secret,
            0,
            FRAME_TYPE_PACKET,
            b"hello",
        )
        .unwrap();
        let last = frame.len() - 1;
        frame[last] ^= 0x01;
        app_stream.write_all(&frame).await.unwrap();

        let err = transport.read_packet().await.unwrap_err();
        assert!(err.to_string().contains("authentication failed"));
        assert!(matches!(
            transport.read_packet().await.unwrap_err(),
            TransportError::Poisoned
        ));
    }

    #[tokio::test]
    async fn write_packet_poisoned_after_write_failure() {
        let session_id = [0x11; SESSION_ID_LEN];
        let session_secret = [0x22; SESSION_SECRET_LEN];
        let mut transport =
            SecureTransport::new_tool(FailingWriteStream, &session_id, &session_secret).unwrap();

        let err = transport.write_packet(b"hello").await.unwrap_err();
        assert!(matches!(err, TransportError::Io(_)));
        assert!(matches!(
            transport.write_packet(b"world").await.unwrap_err(),
            TransportError::Poisoned
        ));
    }

    #[tokio::test]
    async fn write_packet_rejects_counter_overflow_and_poisoned_afterward() {
        let (tool_stream, _app_stream) = duplex(64 * 1024);
        let session_id = [0x11; SESSION_ID_LEN];
        let session_secret = [0x22; SESSION_SECRET_LEN];
        let mut transport =
            SecureTransport::new_tool(tool_stream, &session_id, &session_secret).unwrap();
        transport.send_counter = u64::MAX;

        let err = transport.write_packet(b"hello").await.unwrap_err();
        assert!(err.to_string().contains("counter overflow"));
        assert!(matches!(
            transport.write_packet(b"world").await.unwrap_err(),
            TransportError::Poisoned
        ));
    }

    #[tokio::test]
    async fn read_packet_rejects_receive_counter_overflow() {
        let (tool_stream, mut app_stream) = duplex(64 * 1024);
        let session_id = [0x11; SESSION_ID_LEN];
        let session_secret = [0x22; SESSION_SECRET_LEN];

        let mut transport =
            SecureTransport::new_tool(tool_stream, &session_id, &session_secret).unwrap();
        transport.receive_counter = u64::MAX;
        let frame = super_test_build_app_frame(
            &session_id,
            &session_secret,
            u64::MAX,
            FRAME_TYPE_PACKET,
            b"hello",
        )
        .unwrap();
        app_stream.write_all(&frame).await.unwrap();

        let err = transport.read_packet().await.unwrap_err();
        assert!(err.to_string().contains("counter overflow"));
        assert!(matches!(
            transport.read_packet().await.unwrap_err(),
            TransportError::Poisoned
        ));
    }

    async fn super_test_open_as_app<S>(
        stream: S,
        session_id: &[u8],
        session_secret: &[u8],
    ) -> TransportResult<SecureTransport<S>>
    where
        S: AsyncRead + AsyncWrite + Unpin,
    {
        validate_session(session_id, session_secret)?;
        let mut transport = SecureTransport::new_app(stream, session_id, session_secret)?;
        let hello = transport.read_frame(FRAME_TYPE_TOOL_HELLO).await?;
        if hello.len() != SESSION_ID_LEN + CHALLENGE_LEN {
            return Err(TransportError::protocol("invalid tool hello"));
        }
        let tool_challenge = &hello[SESSION_ID_LEN..];
        let mut payload = Vec::with_capacity(SESSION_ID_LEN + CHALLENGE_LEN + CHALLENGE_LEN);
        payload.extend_from_slice(session_id);
        payload.extend_from_slice(tool_challenge);
        payload.extend_from_slice(&random_bytes(CHALLENGE_LEN));
        transport
            .write_frame(FRAME_TYPE_APP_HELLO, &payload)
            .await?;
        Ok(transport)
    }

    fn super_test_build_app_frame(
        session_id: &[u8],
        session_secret: &[u8],
        counter: u64,
        frame_type: u8,
        payload: &[u8],
    ) -> TransportResult<Vec<u8>> {
        let key = derive_bytes(
            session_secret,
            session_id,
            "keyguard-android-ssh-agent:app-to-tool:key",
        )?;
        let nonce_prefix = derive_bytes(
            session_secret,
            session_id,
            "keyguard-android-ssh-agent:app-to-tool:nonce",
        )?;
        let header = build_header(frame_type, counter, payload.len() + TAG_LEN)?;
        let ciphertext = encrypt_payload(&key, &nonce_prefix, counter, &header, payload)?;

        let mut frame = Vec::with_capacity(header.len() + ciphertext.len());
        frame.extend_from_slice(&header);
        frame.extend_from_slice(&ciphertext);
        Ok(frame)
    }

    struct FailingWriteStream;

    impl AsyncRead for FailingWriteStream {
        fn poll_read(
            self: Pin<&mut Self>,
            _cx: &mut Context<'_>,
            _buf: &mut ReadBuf<'_>,
        ) -> Poll<io::Result<()>> {
            Poll::Ready(Ok(()))
        }
    }

    impl AsyncWrite for FailingWriteStream {
        fn poll_write(
            self: Pin<&mut Self>,
            _cx: &mut Context<'_>,
            _buf: &[u8],
        ) -> Poll<io::Result<usize>> {
            Poll::Ready(Err(io::Error::new(
                io::ErrorKind::BrokenPipe,
                "write failure",
            )))
        }

        fn poll_flush(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
            Poll::Ready(Ok(()))
        }

        fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
            Poll::Ready(Ok(()))
        }
    }
}
