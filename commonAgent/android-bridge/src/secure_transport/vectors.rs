//! The bytes both ends of the Termux bridge agree on.
//! `tests/fixtures/secure_transport.hex` holds them, and the Kotlin
//! `SshAgentTcpProtocolVectorsTest` checks the same file against the app's
//! `SshAgentTcpProtocol`.
use super::*;
use keyguard_agent_proto::fixtures::{check_bytes, fixtures};
use tokio::io::duplex;

const VECTORS: &str = include_str!("../../tests/fixtures/secure_transport.hex");

const SESSION_ID: [u8; SESSION_ID_LEN] = [0x11; SESSION_ID_LEN];
const SESSION_SECRET: [u8; SESSION_SECRET_LEN] = [0x22; SESSION_SECRET_LEN];
const TOOL_CHALLENGE: [u8; CHALLENGE_LEN] = [0x33; CHALLENGE_LEN];
const APP_CHALLENGE: [u8; CHALLENGE_LEN] = [0x44; CHALLENGE_LEN];
const TOOL_PACKET_1: &[u8] = b"keyguard-request-1";
const TOOL_PACKET_2: &[u8] = b"keyguard-request-2";
const APP_PACKET_1: &[u8] = b"keyguard-response-1";

fn key(direction: &str) -> [u8; SESSION_SECRET_LEN] {
    let label = format!("keyguard-android-ssh-agent:{direction}:key");
    derive_bytes(&SESSION_SECRET, &SESSION_ID, &label).unwrap()
}

fn nonce_prefix(direction: &str) -> [u8; 4] {
    let label = format!("keyguard-android-ssh-agent:{direction}:nonce");
    derive_bytes(&SESSION_SECRET, &SESSION_ID, &label).unwrap()
}

/// A frame as the sender of `direction` writes it.
fn frame(direction: &str, frame_type: u8, counter: u64, payload: &[u8]) -> Vec<u8> {
    let header = build_header(frame_type, counter, payload.len() + TAG_LEN).unwrap();
    let ciphertext = encrypt_payload(
        &key(direction),
        &nonce_prefix(direction),
        counter,
        &header,
        payload,
    )
    .unwrap();
    [header.as_slice(), &ciphertext].concat()
}

/// Every value of the fixture, in file order.
fn vectors() -> Vec<(&'static str, Vec<u8>)> {
    let tool_hello = [SESSION_ID.as_slice(), &TOOL_CHALLENGE].concat();
    let app_hello = [SESSION_ID.as_slice(), &TOOL_CHALLENGE, &APP_CHALLENGE].concat();
    vec![
        ("session_id", SESSION_ID.to_vec()),
        ("session_secret", SESSION_SECRET.to_vec()),
        ("tool_challenge", TOOL_CHALLENGE.to_vec()),
        ("app_challenge", APP_CHALLENGE.to_vec()),
        ("tool_to_app_key", key("tool-to-app").to_vec()),
        (
            "tool_to_app_nonce_prefix",
            nonce_prefix("tool-to-app").to_vec(),
        ),
        ("app_to_tool_key", key("app-to-tool").to_vec()),
        (
            "app_to_tool_nonce_prefix",
            nonce_prefix("app-to-tool").to_vec(),
        ),
        (
            "tool_hello",
            frame("tool-to-app", FRAME_TYPE_TOOL_HELLO, 0, &tool_hello),
        ),
        (
            "app_hello",
            frame("app-to-tool", FRAME_TYPE_APP_HELLO, 0, &app_hello),
        ),
        ("tool_packet_1_payload", TOOL_PACKET_1.to_vec()),
        (
            "tool_packet_1",
            frame("tool-to-app", FRAME_TYPE_PACKET, 1, TOOL_PACKET_1),
        ),
        ("tool_packet_2_payload", TOOL_PACKET_2.to_vec()),
        (
            "tool_packet_2",
            frame("tool-to-app", FRAME_TYPE_PACKET, 2, TOOL_PACKET_2),
        ),
        ("app_packet_1_payload", APP_PACKET_1.to_vec()),
        (
            "app_packet_1",
            frame("app-to-tool", FRAME_TYPE_PACKET, 1, APP_PACKET_1),
        ),
    ]
}

#[test]
fn vectors_match_the_transport() {
    check_bytes(VECTORS, &vectors());
}

/// The tool's handshake and packets write exactly the fixture's tool frames,
/// and it reads the fixture's app frames.
#[tokio::test]
async fn tool_writes_and_reads_the_vectors() {
    let vectors = fixtures(VECTORS);
    let vector = |name: &str| {
        let (_, bytes) = vectors
            .iter()
            .find(|(fixture, _)| *fixture == name)
            .unwrap();
        bytes.clone()
    };
    let (tool_stream, mut app_stream) = duplex(64 * 1024);
    let tool = tokio::spawn(async move {
        let mut transport = SecureTransport::connect_as_tool_with_challenge(
            tool_stream,
            &SESSION_ID,
            &SESSION_SECRET,
            &TOOL_CHALLENGE,
        )
        .await
        .unwrap();
        transport.write_packet(TOOL_PACKET_1).await.unwrap();
        transport.write_packet(TOOL_PACKET_2).await.unwrap();
        transport.read_packet().await.unwrap().unwrap()
    });

    expect_written(&mut app_stream, "tool_hello", &vector("tool_hello")).await;
    app_stream.write_all(&vector("app_hello")).await.unwrap();
    expect_written(&mut app_stream, "tool_packet_1", &vector("tool_packet_1")).await;
    expect_written(&mut app_stream, "tool_packet_2", &vector("tool_packet_2")).await;
    app_stream.write_all(&vector("app_packet_1")).await.unwrap();

    assert_eq!(tool.await.unwrap(), vector("app_packet_1_payload"));
}

async fn expect_written<S: AsyncRead + Unpin>(stream: &mut S, name: &str, expected: &[u8]) {
    let mut written = vec![0u8; expected.len()];
    stream.read_exact(&mut written).await.unwrap();
    assert_eq!(written, expected, "{name}");
}
