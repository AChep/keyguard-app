//! Bounded shutdown for HTTP connections and upgraded WebSockets.

mod common;

use std::time::Duration;

use common::*;
use futures_util::StreamExt;
use keyguard_relay::Config;
use keyguard_relay::config::Timeouts;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::time::timeout;

#[tokio::test]
async fn incomplete_http_headers_time_out_while_serving() {
    let relay = Relay::start(Config {
        timeouts: Timeouts {
            http_header: Duration::from_millis(500),
            ..Timeouts::default()
        },
        ..test_config()
    })
    .await;
    let mut partial = relay.open_http_connection().await;
    let mut response = Vec::new();
    timeout(TIMEOUT, partial.read_to_end(&mut response))
        .await
        .expect("HTTP header read has a deadline")
        .unwrap();
    // Timing out one client must not terminate the relay.
    relay.health().await;
}

#[tokio::test]
async fn partial_http_headers_cannot_hold_shutdown_open() {
    let relay = Relay::start(test_config()).await;
    let mut socket = relay.connect().await;
    join(&mut socket, &room_id(1), &peer_id(1)).await;
    let mut partial = relay.open_http_connection().await;
    // A later request establishes that the accept loop has passed this client.
    relay.health().await;

    let shutdown = tokio::spawn(relay.shutdown());
    assert_eq!(expect_closed(&mut socket).await, Some(1001));
    timeout(TIMEOUT, shutdown).await.unwrap().unwrap();
    let mut response = Vec::new();
    _ = timeout(TIMEOUT, partial.read_to_end(&mut response))
        .await
        .expect("unfinished HTTP connection is closed");
}

#[tokio::test]
async fn pending_upgrades_are_closed_before_shutdown_returns() {
    let relay = Relay::start(test_config()).await;
    let mut pending = Vec::new();
    for _ in 0..8 {
        let mut stream = TcpStream::connect(relay.addr).await.unwrap();
        stream
            .write_all(
                b"GET /v1/ws HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: MDEyMzQ1Njc4OWFiY2RlZg==\r\n\r\n",
            )
            .await
            .unwrap();
        pending.push(stream);
    }
    relay.shutdown().await;
    for mut stream in pending {
        let mut response = Vec::new();
        _ = timeout(TIMEOUT, stream.read_to_end(&mut response))
            .await
            .expect("pending upgrade is closed");
    }
}

#[tokio::test]
async fn shutdown_cancels_a_blocked_control_reply() {
    let relay = Relay::start(Config {
        msgs_per_sec: 100_000,
        bytes_per_sec: 1 << 30,
        ..test_config()
    })
    .await;
    let socket = relay.connect().await;
    let (mut reader, writer) = flood_non_reading_client(socket).await;
    relay.shutdown().await;
    writer.abort();
    _ = writer.await;
    // The socket must actually be released while this runtime is still running.
    timeout(TIMEOUT, async {
        while let Some(Ok(_)) = reader.next().await {}
    })
    .await
    .expect("blocked WebSocket is dropped before serve returns");
}
