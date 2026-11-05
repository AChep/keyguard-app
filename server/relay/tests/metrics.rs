//! Private monitoring, traffic accounting and capacity isolation.

mod common;

use common::*;
use keyguard_relay::Config;
use keyguard_relay::config::{Timeouts, TurnConfig};
use serde_json::json;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::time::timeout;

#[tokio::test]
async fn metrics_are_separate_from_public_routes_and_health_is_minimal() {
    let relay = Relay::start(Config {
        token: Some("private-relay-token".to_owned()),
        ..test_config()
    })
    .await;
    let mut socket = relay
        .try_connect("", Some("private-relay-token"))
        .await
        .unwrap();
    let ticket = join_ticket(&mut socket, &room_id(1), &peer_id(1)).await;
    assert_eq!(relay.health().await, json!({"status": "ok"}));
    for headers in [
        vec![],
        vec![("Authorization", "Bearer private-relay-token")],
    ] {
        assert_eq!(relay.http("GET", "/metrics", &headers).await.status, 404);
    }
    let metrics = relay.metrics().await;
    assert_eq!(metrics.status, 200);
    assert_eq!(
        metrics.header("content-type"),
        Some("text/plain; version=0.0.4; charset=utf-8")
    );
    assert_eq!(metrics.header("cache-control"), Some("no-store"));
    assert_eq!(metrics.metric("keyguard_relay_rooms"), 1.0);
    assert_eq!(metrics.metric("keyguard_relay_joined_peers"), 1.0);
    for private in [
        "private-relay-token",
        &room_id(1),
        &peer_id(1),
        &ticket,
        "127.0.0.1",
    ] {
        assert!(!metrics.body.contains(private), "metrics contain {private}");
    }
    let addr = relay.metrics_addr.unwrap();
    for path in ["/healthz", "/v1/ws", "/v1/ice-servers"] {
        assert_eq!(Relay::http_at(addr, "GET", path, &[], "").await.status, 404);
    }
    assert_eq!(
        Relay::http_at(addr, "POST", "/metrics", &[], "")
            .await
            .status,
        405
    );
    relay.shutdown().await;
    assert!(TcpStream::connect(addr).await.is_err());
}

#[tokio::test]
async fn metrics_are_disabled_without_a_listener() {
    let relay = Relay::start(Config {
        metrics_bind: None,
        ..test_config()
    })
    .await;
    assert!(relay.metrics_addr.is_none());
    assert_eq!(relay.http("GET", "/metrics", &[]).await.status, 404);
    assert_eq!(relay.health().await, json!({"status": "ok"}));
}

#[tokio::test]
async fn traffic_counts_writes_per_recipient_and_excludes_missing_targets() {
    let relay = Relay::start(test_config()).await;
    let mut a = relay.connect().await;
    let mut b = relay.connect().await;
    let mut c = relay.connect().await;
    join(&mut a, &room_id(1), &peer_id(1)).await;
    join(&mut b, &room_id(1), &peer_id(2)).await;
    join(&mut c, &room_id(1), &peer_id(3)).await;
    recv_json(&mut a).await;
    recv_json(&mut a).await;
    recv_json(&mut b).await;
    let before = relay.metrics().await;
    let publication = json!({"t":"pub", "to":"*", "data":"aGVsbG8="});
    send_json(&mut a, publication.clone()).await;
    let forwarded = recv_json(&mut b).await;
    assert_eq!(recv_json(&mut c).await, forwarded);
    let after = relay.metrics().await;
    let sent = "keyguard_relay_websocket_sent_bytes_total{format=\"text\"}";
    let received = "keyguard_relay_websocket_received_bytes_total{format=\"text\"}";
    assert_eq!(
        after.metric(sent) - before.metric(sent),
        (2 * forwarded.to_string().len()) as f64
    );
    assert_eq!(
        after.metric(received) - before.metric(received),
        publication.to_string().len() as f64
    );

    let frame = forward_frame(2, b"encrypted bytes");
    send_binary(&mut a, frame.clone()).await;
    assert_eq!(
        recv_binary(&mut b).await,
        forward_frame(1, b"encrypted bytes")
    );
    // An absent destination and the sender itself must not count as outgoing bytes.
    send_binary(&mut a, forward_frame(9, b"missing")).await;
    send_binary(&mut a, forward_frame(1, b"self")).await;
    send_json(&mut a, json!({"t":"ping"})).await;
    assert_eq!(recv_json(&mut a).await, json!({"t":"pong"}));
    let metrics = relay.metrics().await;
    assert_eq!(
        metrics.metric("keyguard_relay_websocket_sent_bytes_total{format=\"binary\"}"),
        frame.len() as f64
    );
    assert_eq!(
        metrics.metric("keyguard_relay_websocket_sent_frames_total{format=\"binary\"}"),
        1.0
    );
    assert_eq!(
        metrics.metric("keyguard_relay_websocket_received_frames_total{format=\"binary\"}"),
        3.0
    );
}

#[tokio::test]
async fn refusals_protocol_errors_and_rate_limits_are_counted() {
    let relay = Relay::start(Config {
        max_conns: 1,
        msgs_per_sec: 1,
        ..test_config()
    })
    .await;
    let mut socket = relay.connect().await;
    assert_eq!(refused_status(relay.try_connect("", None).await), 503);
    send_json(&mut socket, json!({"t":"ping"})).await;
    recv_json(&mut socket).await;
    // Over the budget, the second ping waits instead of failing.
    send_json(&mut socket, json!({"t":"ping"})).await;
    assert_eq!(recv_json(&mut socket).await, json!({"t":"pong"}));
    send_json(&mut socket, json!({"t":"leave"})).await;
    assert_eq!(
        recv_json(&mut socket).await,
        json!({"t":"err","code":"not_joined"})
    );
    let metrics = relay.metrics().await;
    assert_eq!(
        metrics.metric(
            "keyguard_relay_connection_refusals_total{transport=\"websocket\",reason=\"total\"}"
        ),
        1.0
    );
    // The second ping and the leave each waited once.
    assert_eq!(
        metrics.metric("keyguard_relay_rate_limits_total{reason=\"connection\"}"),
        2.0
    );
    assert_eq!(
        metrics.metric("keyguard_relay_protocol_errors_total{reason=\"not_joined\"}"),
        1.0
    );
}

#[tokio::test]
async fn credential_metrics_distinguish_allowance_and_live_tickets() {
    let relay = Relay::start(Config {
        turn: Some(TurnConfig {
            urls: vec!["turn:turn.example.com".to_owned()],
            secret: "test-turn-secret".to_owned(),
        }),
        ..test_config()
    })
    .await;
    relay.ice_servers(&[]).await;
    relay.ice_servers(&[]).await;
    let mut socket = relay.connect().await;
    let ticket = join_ticket(&mut socket, &room_id(1), &peer_id(1)).await;
    relay.ice_servers_with_ticket(&ticket).await;
    let metrics = relay.metrics().await;
    for (result, ticketed) in [
        ("issued", "false"),
        ("limited", "false"),
        ("issued", "true"),
    ] {
        assert_eq!(metrics.metric(&format!("keyguard_relay_turn_credentials_total{{result=\"{result}\",ticketed=\"{ticketed}\"}}")), 1.0);
    }
    assert!(!metrics.body.contains("test-turn-secret"));
}

#[tokio::test]
async fn public_http_saturation_does_not_block_metrics_and_monitoring_drains_on_shutdown() {
    let relay = Relay::start(Config {
        max_conns: 1,
        timeouts: Timeouts {
            http_header: Duration::from_secs(30),
            ..Timeouts::default()
        },
        ..test_config()
    })
    .await;
    let _public = relay.open_http_connection().await;
    let addr = relay.metrics_addr.unwrap();
    let mut partial = TcpStream::connect(addr).await.unwrap();
    partial
        .write_all(b"GET /metrics HTTP/1.1\r\nHost: metrics\r\n")
        .await
        .unwrap();
    assert_eq!(relay.metrics().await.status, 200);
    relay.shutdown().await;
    _ = timeout(TIMEOUT, partial.read_to_end(&mut Vec::new()))
        .await
        .expect("partial metrics request cannot hold shutdown open");
}
