//! TURN credentials (coturn `use-auth-secret`), the ICE endpoint and its allowances.

mod common;

use common::*;
use keyguard_relay::Config;
use keyguard_relay::config::TurnConfig;
use keyguard_relay::ice::{turn_credential, turn_username};
use serde_json::json;

const SECRET: &str = "keyguard-relay-test-secret";

#[test]
fn credential_is_base64_hmac_sha1() {
    // RFC 2202, test case 2: HMAC-SHA1 = effcdf6ae5eb2fa2d27416d5f184df9c259a7c79.
    assert_eq!(
        turn_credential("Jefe", "what do ya want for nothing?"),
        "7/zfauXrL6LSdBbV8YTfnCWafHk="
    );
    // `printf '%s' '1767225600:0123456789abcdef' | openssl dgst -sha1 -hmac \
    //   'keyguard-relay-test-secret' -binary | base64`, which is what coturn checks.
    let username = turn_username(1_767_225_600, "0123456789abcdef");
    assert_eq!(username, "1767225600:0123456789abcdef");
    assert_eq!(
        turn_credential(SECRET, &username),
        "bASz32XsE/qo7SbZ+QQ2onxcTJA="
    );
}

fn turn_config() -> Config {
    Config {
        stun_urls: vec!["stun:turn.example.com:3478".to_owned()],
        turn: Some(TurnConfig {
            urls: vec![
                "turn:turn.example.com:3478?transport=udp".to_owned(),
                "turn:turn.example.com:3478?transport=tcp".to_owned(),
            ],
            secret: SECRET.to_owned(),
        }),
        turn_ttl_seconds: 600,
        // Most tests ask without a ticket.
        ice_ticketless_per_minute: 10,
        ..test_config()
    }
}

/// Checks the TURN entry and returns its username.
fn check_turn_entry(entry: &serde_json::Value) -> String {
    assert_eq!(
        entry["urls"],
        json!([
            "turn:turn.example.com:3478?transport=udp",
            "turn:turn.example.com:3478?transport=tcp"
        ])
    );
    let username = entry["username"].as_str().unwrap();
    let (expiry, tag) = username.split_once(':').unwrap();
    let expiry: u64 = expiry.parse().unwrap();
    let expected = unix_now() + 600;
    assert!(
        expiry.abs_diff(expected) <= 5,
        "expiry {expiry}, expected about {expected}"
    );
    assert!(is_lower_hex(tag, 8), "{tag}");
    assert_eq!(entry["credential"], turn_credential(SECRET, username));
    username.to_owned()
}

/// Whether the answer of `POST /v1/ice-servers` has a TURN credential.
fn has_turn(response: &HttpResponse) -> bool {
    assert_eq!(response.status, 200, "{}", response.body);
    let body = response.json();
    let servers = body["iceServers"].as_array().unwrap();
    let turn = servers
        .iter()
        .any(|server| server.get("username").is_some());
    if turn {
        assert_eq!(body["ttlSeconds"], 600);
    }
    turn
}

/// The answer once the allowance is spent: STUN only, asked again within a minute.
fn expect_stun_only(response: &HttpResponse) {
    assert_eq!(response.status, 200);
    assert_eq!(response.header("cache-control"), Some("no-store"));
    assert_eq!(
        response.json(),
        json!({
            "iceServers": [{ "urls": ["stun:turn.example.com:3478"] }],
            "ttlSeconds": 60
        })
    );
}

#[tokio::test]
async fn mints_turn_credentials() {
    let relay = Relay::start(turn_config()).await;
    let response = relay.ice_servers(&[]).await;
    assert_eq!(response.status, 200);
    assert_eq!(response.header("cache-control"), Some("no-store"));
    assert_eq!(response.header("content-type"), Some("application/json"));
    let body = response.json();
    assert_eq!(body["ttlSeconds"], 600);
    let servers = body["iceServers"].as_array().unwrap();
    assert_eq!(servers.len(), 2);
    assert_eq!(
        servers[0],
        json!({ "urls": ["stun:turn.example.com:3478"] })
    );
    check_turn_entry(&servers[1]);
}

/// The user part of the TURN username minted for a request from `ip`.
async fn turn_user(relay: &Relay, ip: &str) -> String {
    let body = relay.ice_servers(&[("X-Forwarded-For", ip)]).await.json();
    let username = check_turn_entry(&body["iceServers"][1]);
    username.split_once(':').unwrap().1.to_owned()
}

#[tokio::test]
async fn every_credential_of_an_address_has_the_same_user() {
    let relay = Relay::start(Config {
        trust_proxy: loopback_proxy(),
        ..turn_config()
    })
    .await;
    // coturn's user-quota then counts the allocations of an address, not of a credential.
    let user = turn_user(&relay, "203.0.113.1").await;
    assert_eq!(turn_user(&relay, "203.0.113.1").await, user);
    assert_ne!(turn_user(&relay, "203.0.113.2").await, user);
    // An IPv6 client is its /64.
    assert_eq!(
        turn_user(&relay, "2001:db8:1:2::1").await,
        turn_user(&relay, "2001:db8:1:2::2").await
    );

    // Each relay draws its own key.
    let other = Relay::start(Config {
        trust_proxy: loopback_proxy(),
        ..turn_config()
    })
    .await;
    assert_ne!(turn_user(&other, "203.0.113.1").await, user);
}

#[tokio::test]
async fn omits_turn_when_unconfigured() {
    let relay = Relay::start(Config {
        stun_urls: vec![
            "stun:stun.example.com:3478".to_owned(),
            "stuns:stun.example.com:5349".to_owned(),
        ],
        ..test_config()
    })
    .await;
    let body = relay.ice_servers(&[]).await.json();
    assert_eq!(
        body,
        json!({
            "iceServers": [{ "urls": ["stun:stun.example.com:3478", "stuns:stun.example.com:5349"] }],
            "ttlSeconds": 7_200
        })
    );

    // Without TURN there is no allowance to spend.
    let relay = Relay::start(Config {
        ice_requests_per_minute: 1,
        ice_ticketless_per_minute: 0,
        ..test_config()
    })
    .await;
    for _ in 0..3 {
        let body = relay.ice_servers(&[]).await.json();
        assert_eq!(body, json!({ "iceServers": [], "ttlSeconds": 7_200 }));
    }
}

#[tokio::test]
async fn a_spent_allowance_answers_with_stun_only() {
    let relay = Relay::start(Config {
        ice_requests_per_minute: 2,
        ..turn_config()
    })
    .await;
    assert!(has_turn(&relay.ice_servers(&[]).await));
    assert!(has_turn(&relay.ice_servers(&[]).await));
    expect_stun_only(&relay.ice_servers(&[]).await);
}

#[tokio::test]
async fn by_default_only_live_tickets_get_turn_for_two_hours() {
    let relay = Relay::start(Config {
        turn_ttl_seconds: Config::default().turn_ttl_seconds,
        ice_ticketless_per_minute: Config::default().ice_ticketless_per_minute,
        ..turn_config()
    })
    .await;
    expect_stun_only(&relay.ice_servers(&[]).await);

    let mut socket = relay.connect().await;
    let ticket = join_ticket(&mut socket, &room_id(1)).await;
    let body = relay.ice_servers_with_ticket(&ticket).await.json();
    assert_eq!(body["ttlSeconds"], 7_200);
    assert!(body["iceServers"][1]["username"].is_string(), "{body}");
}

#[tokio::test]
async fn live_tickets_get_the_larger_allowance() {
    let relay = Relay::start(Config {
        ice_requests_per_minute: 3,
        ice_ticketless_per_minute: 1,
        ..turn_config()
    })
    .await;
    assert!(has_turn(&relay.ice_servers(&[]).await));
    expect_stun_only(&relay.ice_servers(&[]).await);

    let mut socket = relay.connect().await;
    let ticket = join_ticket(&mut socket, &room_id(1)).await;
    assert!(has_turn(&relay.ice_servers_with_ticket(&ticket).await));
    assert!(has_turn(&relay.ice_servers_with_ticket(&ticket).await));
    // The ticket spends the same per-address allowance.
    expect_stun_only(&relay.ice_servers_with_ticket(&ticket).await);
}

#[tokio::test]
async fn tickets_live_only_while_their_socket_is_in_a_room() {
    let relay = Relay::start(Config {
        ice_requests_per_minute: 100,
        ice_ticketless_per_minute: 0,
        ..turn_config()
    })
    .await;
    expect_stun_only(&relay.ice_servers(&[]).await);

    let mut socket = relay.connect().await;
    let ticket = join_ticket(&mut socket, &room_id(1)).await;
    assert!(has_turn(&relay.ice_servers_with_ticket(&ticket).await));
    // Unknown, malformed and misplaced tickets count as none.
    expect_stun_only(&relay.ice_servers_with_ticket(&"0".repeat(32)).await);
    expect_stun_only(&relay.ice_servers_with_ticket(&ticket.to_uppercase()).await);
    for body in ["not json", "[]", "{\"ticket\":7}"] {
        let response = relay
            .http_with_body("POST", "/v1/ice-servers", &[], body)
            .await;
        expect_stun_only(&response);
    }
    let query = format!("/v1/ice-servers?ticket={ticket}");
    expect_stun_only(&relay.http("POST", &query, &[]).await);

    send_json(&mut socket, json!({ "t": "leave" })).await;
    relay.wait_for_metrics(0, 1).await;
    expect_stun_only(&relay.ice_servers_with_ticket(&ticket).await);

    // One ticket per connection, live again after a new join.
    assert_eq!(join_ticket(&mut socket, &room_id(2)).await, ticket);
    assert!(has_turn(&relay.ice_servers_with_ticket(&ticket).await));

    drop(socket);
    relay.wait_for_metrics(0, 0).await;
    expect_stun_only(&relay.ice_servers_with_ticket(&ticket).await);
}

#[tokio::test]
async fn a_ticket_lives_while_its_socket_is_in_any_room() {
    let relay = Relay::start(Config {
        ice_requests_per_minute: 100,
        ice_ticketless_per_minute: 0,
        ..turn_config()
    })
    .await;
    let mut socket = relay.connect().await;
    let ticket = join_ticket(&mut socket, &room_id(1)).await;
    assert_eq!(join_ticket(&mut socket, &room_id(2)).await, ticket);

    send_json(&mut socket, json!({ "t": "leave", "room": room_id(1) })).await;
    relay.wait_for_metrics(1, 1).await;
    assert!(has_turn(&relay.ice_servers_with_ticket(&ticket).await));

    send_json(&mut socket, json!({ "t": "leave", "room": room_id(2) })).await;
    relay.wait_for_metrics(0, 1).await;
    expect_stun_only(&relay.ice_servers_with_ticket(&ticket).await);
}

#[tokio::test]
async fn forwarded_for_counts_only_behind_a_trusted_proxy() {
    let behind_proxy = Relay::start(Config {
        ice_requests_per_minute: 1,
        trust_proxy: loopback_proxy(),
        ..turn_config()
    })
    .await;
    let from = |ip: &'static str| [("X-Forwarded-For", ip)];
    assert!(has_turn(
        &behind_proxy.ice_servers(&from("203.0.113.1")).await
    ));
    expect_stun_only(&behind_proxy.ice_servers(&from("203.0.113.1")).await);
    assert!(has_turn(
        &behind_proxy
            .ice_servers(&[("X-Forwarded-For", "198.51.100.7, 203.0.113.2")])
            .await
    ));

    let direct = Relay::start(Config {
        ice_requests_per_minute: 1,
        ..turn_config()
    })
    .await;
    assert!(has_turn(&direct.ice_servers(&from("203.0.113.1")).await));
    expect_stun_only(&direct.ice_servers(&from("203.0.113.2")).await);
}

#[tokio::test]
async fn the_relay_wide_allowance_keeps_half_for_tickets() {
    let relay = Relay::start(Config {
        ice_total_per_minute: 2,
        trust_proxy: loopback_proxy(),
        ..turn_config()
    })
    .await;
    let mut socket = relay.connect().await;
    let ticket = join_ticket(&mut socket, &room_id(1)).await;
    let ask = |ip: &'static str, ticket: Option<&str>| {
        let body = ticket.map_or_else(String::new, |ticket| {
            json!({ "ticket": ticket }).to_string()
        });
        let relay = &relay;
        async move {
            relay
                .http_with_body("POST", "/v1/ice-servers", &[("X-Forwarded-For", ip)], &body)
                .await
        }
    };
    // Every request comes from another address, under its own allowance.
    assert!(has_turn(&ask("203.0.113.1", None).await));
    expect_stun_only(&ask("203.0.113.2", None).await);
    assert!(has_turn(&ask("203.0.113.3", Some(&ticket)).await));
    expect_stun_only(&ask("203.0.113.4", Some(&ticket)).await);
}

#[tokio::test]
async fn unauthorized_requests_spend_no_allowance() {
    let relay = Relay::start(Config {
        token: Some("relay-token-0123456789".to_owned()),
        ice_requests_per_minute: 1,
        ..turn_config()
    })
    .await;
    for _ in 0..3 {
        assert_eq!(relay.ice_servers(&[]).await.status, 401);
    }
    let bearer = [("Authorization", "Bearer relay-token-0123456789")];
    assert!(has_turn(&relay.ice_servers(&bearer).await));
}

#[tokio::test]
async fn only_post_is_allowed() {
    let relay = Relay::start(turn_config()).await;
    assert_eq!(relay.http("GET", "/v1/ice-servers", &[]).await.status, 405);
}
