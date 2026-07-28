//! End-to-end Assuan connection tests.

use super::commands::MAX_CIPHERTEXT_LEN;
use super::transport::MAX_INQUIRE_LINE_LEN;
use super::*;
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt};

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
