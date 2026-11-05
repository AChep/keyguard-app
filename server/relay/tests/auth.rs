//! `RELAY_TOKEN` on the WebSocket and ICE endpoints.

mod common;

use common::*;
use keyguard_relay::Config;
use serde_json::json;

const TOKEN: &str = "relay-token-0123456789";

async fn private_relay() -> Relay {
    Relay::start(Config {
        token: Some(TOKEN.to_owned()),
        stun_urls: vec!["stun:stun.example.com:3478".to_owned()],
        ..test_config()
    })
    .await
}

#[tokio::test]
async fn sockets_without_the_token_are_refused() {
    let relay = private_relay().await;
    for query in [
        "",
        "?token=relay-token-0123456788",
        "?token=",
        "?other=relay-token-0123456789",
    ] {
        let mut socket = relay.try_connect(query, None).await.unwrap();
        expect_error(&mut socket, "unauthorized").await;
        assert_eq!(expect_closed(&mut socket).await, Some(1008), "{query}");
    }
    let mut socket = relay
        .try_connect("", Some("relay-token-wrong"))
        .await
        .unwrap();
    expect_error(&mut socket, "unauthorized").await;
    assert_eq!(expect_closed(&mut socket).await, Some(1008));
}

#[tokio::test]
async fn sockets_with_the_token_are_accepted() {
    let relay = private_relay().await;

    let mut by_query = relay
        .try_connect(&format!("?token={TOKEN}"), None)
        .await
        .unwrap();
    assert!(
        join(&mut by_query, &room_id(1), &peer_id(1))
            .await
            .is_empty()
    );

    let mut by_header = relay.try_connect("", Some(TOKEN)).await.unwrap();
    assert_eq!(
        join(&mut by_header, &room_id(1), &peer_id(2)).await,
        [peer_id(1)]
    );
    assert_eq!(recv_json(&mut by_query).await, presence(2, "join"));
}

#[tokio::test]
async fn health_stays_open() {
    let relay = private_relay().await;
    assert_eq!(relay.health().await, json!({ "status": "ok" }));
}

#[tokio::test]
async fn ice_servers_need_the_token() {
    let relay = private_relay().await;

    let refused = relay.ice_servers(&[]).await;
    assert_eq!(refused.status, 401);
    assert_eq!(refused.json(), json!({ "error": "unauthorized" }));
    assert_eq!(refused.header("www-authenticate"), Some("Bearer"));

    let wrong = relay
        .ice_servers(&[("Authorization", "Bearer relay-token-wrong")])
        .await;
    assert_eq!(wrong.status, 401);

    let bearer = format!("Bearer {TOKEN}");
    let by_header = relay.ice_servers(&[("Authorization", &bearer)]).await;
    assert_eq!(by_header.status, 200);
    assert_eq!(
        by_header.json()["iceServers"][0]["urls"][0],
        "stun:stun.example.com:3478"
    );

    let by_query = relay
        .http("POST", &format!("/v1/ice-servers?token={TOKEN}"), &[])
        .await;
    assert_eq!(by_query.status, 200);
}

#[tokio::test]
async fn repeated_failures_lock_the_address_out() {
    let relay = Relay::start(Config {
        token: Some(TOKEN.to_owned()),
        trust_proxy: loopback_proxy(),
        ..test_config()
    })
    .await;
    let wrong = [("Authorization", "Bearer relay-token-wrong")];
    for _ in 0..5 {
        assert_eq!(relay.ice_servers(&wrong).await.status, 401);
    }
    // Failed WebSockets count as well.
    for _ in 0..5 {
        let mut socket = relay
            .try_connect("", Some("relay-token-wrong"))
            .await
            .unwrap();
        expect_error(&mut socket, "unauthorized").await;
    }
    let locked = relay.ice_servers(&wrong).await;
    assert_eq!(locked.status, 429);
    assert_eq!(locked.json(), json!({ "error": "rate_limited" }));
    assert_eq!(locked.header("retry-after"), Some("6"));

    // Even the right token waits, on both endpoints.
    let bearer = format!("Bearer {TOKEN}");
    let right = [("Authorization", bearer.as_str())];
    assert_eq!(relay.ice_servers(&right).await.status, 429);
    assert_eq!(
        refused_status(relay.try_connect("", Some(TOKEN)).await),
        429
    );

    // Other addresses are not affected.
    let elsewhere = [right[0], ("X-Forwarded-For", "203.0.113.9")];
    assert_eq!(relay.ice_servers(&elsewhere).await.status, 200);
    let metrics = relay.metrics().await;
    assert_eq!(
        metrics.metric("keyguard_relay_auth_rejections_total{reason=\"unauthorized\"}"),
        10.0
    );
    assert_eq!(
        metrics.metric("keyguard_relay_auth_rejections_total{reason=\"locked\"}"),
        3.0
    );
}

#[tokio::test]
async fn open_relay_needs_no_token() {
    let relay = Relay::start(test_config()).await;
    let mut socket = relay.connect().await;
    assert!(join(&mut socket, &room_id(1), &peer_id(1)).await.is_empty());
    assert_eq!(relay.ice_servers(&[]).await.status, 200);
}
