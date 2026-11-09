//! Endpoint admission survives reconnects and precedes authentication and body reads.

mod common;

use common::*;
use keyguard_relay::Config;
use serde_json::json;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

#[tokio::test]
async fn websocket_reconnects_share_an_address_budget() {
    let relay = Relay::start(Config {
        ws_requests_per_sec: 1,
        ..test_config()
    })
    .await;
    drop(relay.connect().await);
    let response = relay.http("GET", "/v1/ws", &[]).await;
    assert_eq!(response.status, 429);
    assert_eq!(response.header("retry-after"), Some("1"));
    assert_eq!(response.json(), json!({ "error": "rate_limited" }));
    assert_eq!(refused_status(relay.try_connect("", None).await), 429);
    // Rejected attempts hold no WebSocket permit. Existing probes remain usable.
    relay.wait_for_metrics(0, 0).await;
    relay.health().await;
    assert_eq!(relay.ice_servers(&[]).await.status, 200);
}

#[tokio::test]
async fn malformed_upgrades_spend_admission_before_extraction() {
    let relay = Relay::start(Config {
        ws_requests_per_sec: 1,
        ..test_config()
    })
    .await;
    assert_ne!(relay.http("GET", "/v1/ws", &[]).await.status, 429);
    assert_eq!(refused_status(relay.try_connect("", None).await), 429);
    assert_eq!(
        relay.metrics().await.metric(
            "keyguard_relay_http_rate_limits_total{endpoint=\"websocket\",reason=\"per_address\"}"
        ),
        1.0
    );
}

#[tokio::test]
async fn oversized_bodies_spend_admission_before_size_rejection() {
    let relay = Relay::start(Config {
        ws_requests_per_sec: 1,
        ice_requests_per_sec: 1,
        ..test_config()
    })
    .await;
    let body = "x".repeat(4097);
    for (method, path) in [("GET", "/v1/ws"), ("POST", "/v1/ice-servers")] {
        assert_eq!(
            relay.http_with_body(method, path, &[], &body).await.status,
            413
        );
        assert_eq!(
            relay.http_with_body(method, path, &[], &body).await.status,
            429
        );
        // The initial size refusal consumed the same budget as an ordinary request.
        assert_eq!(relay.http(method, path, &[]).await.status, 429);
    }
    relay.health().await;
}

#[tokio::test]
async fn websocket_admission_uses_trusted_addresses_and_ipv6_networks() {
    let relay = Relay::start(Config {
        ws_requests_per_sec: 1,
        trust_proxy: loopback_proxy(),
        ..test_config()
    })
    .await;
    let _a = relay.connect_from("2001:db8:1:2::1").await;
    let refused = relay
        .try_connect_with("", None, &[("x-forwarded-for", "2001:db8:1:2::2")])
        .await;
    assert_eq!(refused_status(refused), 429);
    let _b = relay.connect_from("2001:db8:1:3::1").await;
}

#[tokio::test]
async fn untrusted_forwarded_addresses_cannot_reset_admission() {
    let relay = Relay::start(Config {
        ws_requests_per_sec: 1,
        ..test_config()
    })
    .await;
    let _a = relay.connect_from("203.0.113.1").await;
    assert_eq!(
        refused_status(
            relay
                .try_connect_with("", None, &[("x-forwarded-for", "203.0.113.2")])
                .await
        ),
        429
    );
}

#[tokio::test]
async fn global_websocket_admission_bounds_rotating_addresses() {
    let relay = Relay::start(Config {
        ws_total_per_sec: 1,
        trust_proxy: loopback_proxy(),
        ..test_config()
    })
    .await;
    let _a = relay.connect_from("203.0.113.1").await;
    let refused = relay
        .try_connect_with("", None, &[("x-forwarded-for", "203.0.113.2")])
        .await;
    assert_eq!(refused_status(refused), 503);
    assert_eq!(
        relay.metrics().await.metric(
            "keyguard_relay_http_rate_limits_total{endpoint=\"websocket\",reason=\"total\"}"
        ),
        1.0
    );
}

#[tokio::test]
async fn ice_requests_are_limited_without_turn_and_independently_of_websockets() {
    let relay = Relay::start(Config {
        ice_requests_per_sec: 1,
        ..test_config()
    })
    .await;
    assert_eq!(relay.ice_servers(&[]).await.status, 200);
    let response = relay.ice_servers(&[]).await;
    assert_eq!(response.status, 429);
    assert_eq!(response.header("retry-after"), Some("1"));
    let _socket = relay.connect().await;
    assert_eq!(
        relay.metrics().await.metric(
            "keyguard_relay_http_rate_limits_total{endpoint=\"ice\",reason=\"per_address\"}"
        ),
        1.0
    );
}

#[tokio::test]
async fn global_ice_admission_bounds_rotating_addresses() {
    let relay = Relay::start(Config {
        ice_total_per_sec: 1,
        trust_proxy: loopback_proxy(),
        ..test_config()
    })
    .await;
    assert_eq!(
        relay
            .ice_servers(&[("x-forwarded-for", "203.0.113.1")])
            .await
            .status,
        200
    );
    assert_eq!(
        relay
            .ice_servers(&[("x-forwarded-for", "203.0.113.2")])
            .await
            .status,
        503
    );
    let _socket = relay.connect().await;
    assert_eq!(
        relay
            .metrics()
            .await
            .metric("keyguard_relay_http_rate_limits_total{endpoint=\"ice\",reason=\"total\"}"),
        1.0
    );
}

#[tokio::test]
async fn ice_rejection_does_not_wait_for_a_body() {
    let relay = Relay::start(Config {
        ice_requests_per_sec: 1,
        ..test_config()
    })
    .await;
    assert_eq!(relay.ice_servers(&[]).await.status, 200);
    let mut socket = TcpStream::connect(relay.addr).await.unwrap();
    socket.write_all(b"POST /v1/ice-servers HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: 128\r\n\r\n").await.unwrap();
    let mut response = [0; 1024];
    let count = tokio::time::timeout(
        std::time::Duration::from_secs(1),
        socket.read(&mut response),
    )
    .await
    .unwrap()
    .unwrap();
    assert!(response[..count].starts_with(b"HTTP/1.1 429"));
}

#[tokio::test]
async fn unauthorized_requests_spend_http_admission() {
    let relay = Relay::start(Config {
        token: Some("private-relay-token".to_owned()),
        ice_requests_per_sec: 1,
        ws_requests_per_sec: 1,
        ..test_config()
    })
    .await;
    assert_eq!(relay.ice_servers(&[]).await.status, 401);
    assert_eq!(relay.ice_servers(&[]).await.status, 429);
    let mut rejected = relay.try_connect("", None).await.unwrap();
    expect_error(&mut rejected, "unauthorized").await;
    assert_eq!(refused_status(relay.try_connect("", None).await), 429);
}
