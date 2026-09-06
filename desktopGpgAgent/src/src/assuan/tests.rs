//! End-to-end Assuan connection tests.

use super::commands::MAX_CIPHERTEXT_LEN;
use super::transport::MAX_INQUIRE_LINE_LEN;
use super::*;
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt};

#[cfg(target_os = "macos")]
#[tokio::test]
async fn exited_macos_peer_cannot_reach_sign_or_decrypt_ipc_on_repeated_requests() {
    use std::process::Stdio;
    use std::time::Duration;

    let directory = tempfile::tempdir().unwrap();
    let socket_path = directory.path().join("peer.sock");
    let listener = tokio::net::UnixListener::bind(&socket_path).unwrap();
    let mut peer = tokio::process::Command::new("/usr/bin/nc")
        .arg("-U")
        .arg(&socket_path)
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .kill_on_drop(true)
        .spawn()
        .unwrap();
    let (stream, _) = tokio::time::timeout(Duration::from_secs(5), listener.accept())
        .await
        .unwrap()
        .unwrap();
    let context = crate::caller_identity::caller_context_from_unix_stream(&stream)
        .expect("authenticated test peer");
    assert!(context.macos_guard.is_some());
    peer.kill().await.unwrap();

    let (ipc_stream, mut ipc_peer) = tokio::io::duplex(1024);
    let ipc_client = IpcClient::from_test_stream(ipc_stream);
    let (client, server) = tokio::io::duplex(4096);
    let server_task = tokio::spawn(serve_connection_with_macos_guard(
        server,
        ipc_client,
        Some(context.caller),
        context.macos_guard,
        "test".to_string(),
    ));
    let (mut read, mut write) = tokio::io::split(client);
    let keygrip = "A".repeat(40);
    let commands = format!(
        "SIGKEY {keygrip}\nSETHASH 8 {}\nPKSIGN\nSETKEY {keygrip}\nPKDECRYPT\nD test-ciphertext\nEND\n",
        "00".repeat(32),
    );
    for _ in 0..2 {
        write.write_all(commands.as_bytes()).await.unwrap();
    }
    write.write_all(b"BYE\n").await.unwrap();

    tokio::time::timeout(Duration::from_secs(5), async {
        let mut response = String::new();
        read.read_to_string(&mut response).await.unwrap();
        server_task.await.unwrap().unwrap();
        assert_eq!(response.matches("ERR 1 key listing failed\n").count(), 4);
        let mut ipc_bytes = Vec::new();
        ipc_peer.read_to_end(&mut ipc_bytes).await.unwrap();
        assert!(
            ipc_bytes.is_empty(),
            "invalid identity must fail before IPC"
        );
    })
    .await
    .expect("invalid peer must be rejected without awaiting IPC");
}

#[tokio::test]
async fn pkdecrypt_oversized_inquiry_reports_error_and_preserves_framing() {
    let mut overlong_line = b"D ".to_vec();
    overlong_line.extend_from_slice(&vec![b'A'; MAX_INQUIRE_LINE_LEN]);
    overlong_line.extend_from_slice(b"\nEND\nNOP\n");

    let max_payload_per_line = MAX_INQUIRE_LINE_LEN - b"D \n".len();
    let mut too_much_data = Vec::new();
    let mut remaining = MAX_CIPHERTEXT_LEN + 1;
    while remaining > 0 {
        let chunk_len = remaining.min(max_payload_per_line);
        too_much_data.extend_from_slice(b"D ");
        too_much_data.extend_from_slice(&vec![b'A'; chunk_len]);
        too_much_data.push(b'\n');
        remaining -= chunk_len;
    }
    too_much_data.extend_from_slice(b"END\nBYE\n");

    for (inquiry, expected_response) in [
        (overlong_line, b"ERR 263 line too long\n".as_slice()),
        (
            too_much_data,
            b"ERR 273 too much data\nOK closing connection\n".as_slice(),
        ),
    ] {
        let response = run_oversized_pkdecrypt_inquiry(&inquiry).await;

        assert_eq!(response, expected_response);
    }
}

async fn run_oversized_pkdecrypt_inquiry(inquiry: &[u8]) -> Vec<u8> {
    let (ipc_stream, _ipc_peer) = tokio::io::duplex(1);
    let ipc_client = IpcClient::from_test_stream(ipc_stream);
    let (client, server) = tokio::io::duplex(inquiry.len() + 128);
    let server_task = tokio::spawn(serve_connection(
        server,
        ipc_client,
        None,
        "test".to_string(),
    ));
    let (client_read, mut client_write) = tokio::io::split(client);
    let mut client_read = BufReader::new(client_read);
    let mut line = Vec::new();

    client_read.read_until(b'\n', &mut line).await.unwrap();
    assert_eq!(line, b"OK Keyguard GPG agent ready\n");

    client_write.write_all(b"PKDECRYPT\n").await.unwrap();
    line.clear();
    client_read.read_until(b'\n', &mut line).await.unwrap();
    assert_eq!(
        line,
        format!("S INQUIRE_MAXLEN {MAX_CIPHERTEXT_LEN}\n").as_bytes(),
    );
    line.clear();
    client_read.read_until(b'\n', &mut line).await.unwrap();
    assert_eq!(line, b"INQUIRE CIPHERTEXT\n");

    client_write.write_all(inquiry).await.unwrap();
    client_write.shutdown().await.unwrap();
    let mut response = Vec::new();
    client_read.read_to_end(&mut response).await.unwrap();
    server_task.await.unwrap().unwrap();
    response
}
