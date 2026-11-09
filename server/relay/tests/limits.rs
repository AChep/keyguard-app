//! Frame size, rate, idle, connection and room limits.

mod common;

use std::time::Duration;

use common::*;
use futures_util::{SinkExt, StreamExt};
use keyguard_relay::config::Timeouts;
use keyguard_relay::{Config, MAX_FRAME_BYTES};
use serde_json::json;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::time::Instant;
use tokio_tungstenite::tungstenite;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::tungstenite::protocol::frame::Frame;
use tokio_tungstenite::tungstenite::protocol::frame::coding::{Data as OpData, OpCode};

async fn next_control(socket: &mut Socket) -> Message {
    tokio::time::timeout(TIMEOUT, socket.next())
        .await
        .unwrap()
        .unwrap()
        .unwrap()
}

#[tokio::test]
async fn unfinished_fragments_spend_the_byte_budget() {
    let bytes_per_sec = 65_536;
    let relay = Relay::start(Config {
        bytes_per_sec,
        ..test_config()
    })
    .await;
    let mut client = relay.connect().await;
    let started = Instant::now();
    // Empty continuation frames never complete a message: only the bytes read from the
    // socket can slow them down. Each is 6 bytes on the wire with its mask.
    let fragment = |opcode| Message::Frame(Frame::message(Vec::new(), opcode, false));
    client
        .feed(fragment(OpCode::Data(OpData::Binary)))
        .await
        .unwrap();
    for _ in 0..3 * bytes_per_sec / 6 {
        client
            .feed(fragment(OpCode::Data(OpData::Continue)))
            .await
            .unwrap();
    }
    client.send(Message::Ping(Vec::new().into())).await.unwrap();
    assert!(matches!(next_control(&mut client).await, Message::Pong(_)));
    // Three seconds' worth of bytes, one of them a burst.
    let elapsed = started.elapsed();
    assert!(elapsed >= Duration::from_millis(1500), "{elapsed:?}");
}

#[tokio::test]
async fn control_frames_share_the_message_budget() {
    let relay = Relay::start(Config {
        msgs_per_sec: 3,
        ..test_config()
    })
    .await;
    let mut socket = relay.connect().await;
    let started = Instant::now();
    let payload = b"client ping".as_slice();
    socket.send(Message::Ping(payload.into())).await.unwrap();
    assert_eq!(
        next_control(&mut socket).await,
        Message::Pong(payload.into())
    );
    // An unsolicited pong and the client ping both spend messages.
    socket
        .send(Message::Pong(b"unsolicited".as_slice().into()))
        .await
        .unwrap();
    for _ in 0..3 {
        send_json(&mut socket, json!({ "t": "ping" })).await;
    }
    for _ in 0..3 {
        assert_eq!(recv_json(&mut socket).await, json!({ "t": "pong" }));
    }
    assert!(started.elapsed() >= Duration::from_millis(600));
}

#[tokio::test]
async fn control_frames_share_the_byte_budget() {
    let relay = Relay::start(Config {
        bytes_per_sec: 100,
        ..test_config()
    })
    .await;
    let mut socket = relay.connect().await;
    let started = Instant::now();
    socket
        .send(Message::Ping(vec![1; 60].into()))
        .await
        .unwrap();
    assert_eq!(
        next_control(&mut socket).await,
        Message::Pong(vec![1; 60].into())
    );
    // The ping took 60 of the 100 bytes, so this waits for 20 more.
    send_text(&mut socket, &padded_ping(60)).await;
    assert_eq!(recv_json(&mut socket).await, json!({ "t": "pong" }));
    assert!(started.elapsed() >= Duration::from_millis(150));
}

#[tokio::test]
async fn control_frames_between_data_fragments_share_the_budget() {
    use tungstenite::protocol::frame::Frame as WireFrame;
    use tungstenite::protocol::frame::coding::{Data, OpCode};

    let relay = Relay::start(Config {
        msgs_per_sec: 1,
        ..test_config()
    })
    .await;
    let mut socket = relay.connect().await;
    let started = Instant::now();
    socket
        .send(Message::Frame(WireFrame::message(
            b"{\"t\":".as_slice(),
            OpCode::Data(Data::Text),
            false,
        )))
        .await
        .unwrap();
    socket.send(Message::Ping(vec![1].into())).await.unwrap();
    assert_eq!(
        next_control(&mut socket).await,
        Message::Pong(vec![1].into())
    );
    socket
        .send(Message::Frame(WireFrame::message(
            b"\"ping\"}".as_slice(),
            OpCode::Data(Data::Continue),
            true,
        )))
        .await
        .unwrap();
    // The ping took the one message of the second.
    assert_eq!(recv_json(&mut socket).await, json!({ "t": "pong" }));
    assert!(started.elapsed() >= Duration::from_millis(900));
}

#[tokio::test]
async fn unsolicited_pongs_share_the_message_budget_and_are_counted_once() {
    let relay = Relay::start(Config {
        msgs_per_sec: 1,
        ..test_config()
    })
    .await;
    let mut socket = relay.connect().await;
    let started = Instant::now();
    for _ in 0..2 {
        socket
            .send(Message::Pong(b"late".as_slice().into()))
            .await
            .unwrap();
    }
    send_json(&mut socket, json!({ "t": "ping" })).await;
    assert_eq!(recv_json(&mut socket).await, json!({ "t": "pong" }));
    assert!(started.elapsed() >= Duration::from_millis(1800));
    let metrics = relay.metrics().await;
    assert_eq!(
        metrics.metric("keyguard_relay_websocket_received_control_frames_total{type=\"pong\"}"),
        2.0
    );
    assert_eq!(
        metrics.metric("keyguard_relay_websocket_received_control_bytes_total{type=\"pong\"}"),
        8.0
    );
    assert_eq!(
        metrics.metric("keyguard_relay_websocket_heartbeat_replies_total"),
        0.0
    );
}

#[tokio::test]
async fn unsolicited_pongs_share_the_byte_budget() {
    let relay = Relay::start(Config {
        bytes_per_sec: 100,
        ..test_config()
    })
    .await;
    let mut socket = relay.connect().await;
    let started = Instant::now();
    socket
        .send(Message::Pong(vec![1; 60].into()))
        .await
        .unwrap();
    send_text(&mut socket, &padded_ping(60)).await;
    assert_eq!(recv_json(&mut socket).await, json!({ "t": "pong" }));
    assert!(started.elapsed() >= Duration::from_millis(150));
}

#[tokio::test]
async fn a_heartbeat_reply_is_free_once_and_its_replay_is_charged() {
    let relay = Relay::start(Config {
        msgs_per_sec: 1,
        idle_timeout: Duration::from_secs(2),
        ..test_config()
    })
    .await;
    let mut socket = relay.connect().await;
    let Message::Ping(token) = next_control(&mut socket).await else {
        panic!("expected heartbeat")
    };
    // Replaces the codec's automatic reply, then replays that exact token.
    socket.send(Message::Pong(token.clone())).await.unwrap();
    socket.send(Message::Pong(token)).await.unwrap();
    let started = Instant::now();
    send_json(&mut socket, json!({ "t": "ping" })).await;
    assert_eq!(recv_json(&mut socket).await, json!({ "t": "pong" }));
    assert!(started.elapsed() >= Duration::from_millis(900));
    assert!(
        relay
            .metrics()
            .await
            .metric("keyguard_relay_websocket_heartbeat_replies_total")
            >= 1.0
    );
}

#[tokio::test]
async fn a_mismatched_heartbeat_reply_is_charged() {
    let relay = Relay::start(Config {
        msgs_per_sec: 1,
        idle_timeout: Duration::from_secs(2),
        ..test_config()
    })
    .await;
    let mut socket = relay.connect().await;
    assert!(matches!(next_control(&mut socket).await, Message::Ping(_)));
    socket
        .send(Message::Pong(b"wrong".as_slice().into()))
        .await
        .unwrap();
    let started = Instant::now();
    send_json(&mut socket, json!({ "t": "ping" })).await;
    assert_eq!(recv_json(&mut socket).await, json!({ "t": "pong" }));
    assert!(started.elapsed() >= Duration::from_millis(900));
}

#[tokio::test]
async fn unsolicited_pongs_between_data_fragments_share_the_budget() {
    use tungstenite::protocol::frame::Frame as WireFrame;
    use tungstenite::protocol::frame::coding::{Data, OpCode};
    let relay = Relay::start(Config {
        msgs_per_sec: 1,
        ..test_config()
    })
    .await;
    let mut socket = relay.connect().await;
    let started = Instant::now();
    socket
        .send(Message::Frame(WireFrame::message(
            b"{\"t\":".as_slice(),
            OpCode::Data(Data::Text),
            false,
        )))
        .await
        .unwrap();
    socket
        .send(Message::Pong(b"noise".as_slice().into()))
        .await
        .unwrap();
    socket
        .send(Message::Frame(WireFrame::message(
            b"\"ping\"}".as_slice(),
            OpCode::Data(Data::Continue),
            true,
        )))
        .await
        .unwrap();
    assert_eq!(recv_json(&mut socket).await, json!({ "t": "pong" }));
    assert!(started.elapsed() >= Duration::from_millis(900));
}

/// A `ping` frame padded to exactly `len` bytes; unknown fields are ignored.
fn padded_ping(len: usize) -> String {
    let empty = json!({ "t": "ping", "pad": "" }).to_string();
    json!({ "t": "ping", "pad": "x".repeat(len - empty.len()) }).to_string()
}

#[tokio::test]
async fn oversized_frames_are_refused_and_close_the_connection() {
    let relay = Relay::start(test_config()).await;

    // Exactly at the limit is fine.
    let mut socket = relay.connect().await;
    send_text(&mut socket, &padded_ping(MAX_FRAME_BYTES)).await;
    assert_eq!(recv_json(&mut socket).await, json!({ "t": "pong" }));

    send_text(&mut socket, &padded_ping(MAX_FRAME_BYTES + 1)).await;
    expect_error(&mut socket, "frame_too_big").await;
    assert_eq!(expect_closed(&mut socket).await, Some(1009));

    let mut socket = relay.connect().await;
    join(&mut socket, &room_id(1)).await;
    let mut frame = forward_frame(1, &peer_id(2), &[]);
    frame.resize(MAX_FRAME_BYTES + 1, 0xaa);
    send_binary(&mut socket, frame).await;
    expect_error(&mut socket, "frame_too_big").await;
    assert_eq!(expect_closed(&mut socket).await, Some(1009));

    // Far above the limit, the codec drops the connection without reading it all.
    let mut socket = relay.connect().await;
    _ = socket
        .send(Message::binary(vec![0x03; 3 * MAX_FRAME_BYTES]))
        .await;
    expect_closed(&mut socket).await;
}

#[tokio::test]
async fn forward_frames_of_the_largest_size_are_relayed() {
    let relay = Relay::start(test_config()).await;
    let mut a = relay.connect().await;
    join(&mut a, &room_id(1)).await;
    let mut b = relay.connect().await;
    join(&mut b, &room_id(1)).await;
    assert_eq!(recv_json(&mut a).await, presence(1, &b.peer, "join"));

    let payload = vec![0xaa; MAX_FRAME_BYTES - forward_frame(1, &b.peer, &[]).len()];
    send_binary(&mut a, forward_frame(1, &b.peer, &payload)).await;
    assert_eq!(
        recv_binary(&mut b).await,
        forward_frame(1, &a.peer, &payload)
    );
}

#[tokio::test]
async fn message_rate_limit_slows_the_sender() {
    let relay = Relay::start(Config {
        msgs_per_sec: 3,
        ..test_config()
    })
    .await;
    let mut socket = relay.connect().await;
    let started = Instant::now();
    for _ in 0..6 {
        send_json(&mut socket, json!({ "t": "ping" })).await;
    }
    // Every frame is answered, none dropped: three in the burst, then one per third of
    // a second.
    for _ in 0..6 {
        assert_eq!(recv_json(&mut socket).await, json!({ "t": "pong" }));
    }
    assert!(started.elapsed() >= Duration::from_millis(900));
}

#[tokio::test]
async fn byte_rate_limit_slows_the_sender() {
    let relay = Relay::start(Config {
        bytes_per_sec: 1024,
        ..test_config()
    })
    .await;
    let mut socket = relay.connect().await;
    let started = Instant::now();
    send_text(&mut socket, &padded_ping(700)).await;
    assert_eq!(recv_json(&mut socket).await, json!({ "t": "pong" }));
    // 376 bytes short at 1024 a second.
    send_text(&mut socket, &padded_ping(700)).await;
    assert_eq!(recv_json(&mut socket).await, json!({ "t": "pong" }));
    assert!(started.elapsed() >= Duration::from_millis(300));
}

#[tokio::test]
async fn silent_sockets_are_closed_when_idle() {
    let relay = Relay::start(Config {
        idle_timeout: Duration::from_secs(1),
        ..test_config()
    })
    .await;
    let mut socket = relay.connect().await;
    // Not reading means the server's pings stay unanswered.
    tokio::time::sleep(Duration::from_millis(1500)).await;
    assert_eq!(expect_closed(&mut socket).await, Some(1000));
    relay.wait_for_metrics(0, 0).await;
    assert_eq!(
        relay
            .metrics()
            .await
            .metric("keyguard_relay_websocket_closes_total{reason=\"idle\"}"),
        1.0
    );
}

#[tokio::test]
async fn answered_pings_keep_a_socket_open() {
    let relay = Relay::start(Config {
        msgs_per_sec: 1,
        idle_timeout: Duration::from_secs(1),
        ..test_config()
    })
    .await;
    let mut socket = relay.connect().await;
    // Reading lets the client codec answer each server ping with a pong.
    for _ in 0..8 {
        expect_silence(&mut socket).await;
    }
    send_json(&mut socket, json!({ "t": "ping" })).await;
    assert_eq!(recv_json(&mut socket).await, json!({ "t": "pong" }));
}

/// With room for two sockets in `config`, a third is refused with `status` until one
/// closes.
async fn assert_two_sockets_fit(config: Config, status: u16) {
    let relay = Relay::start(config).await;
    let first = relay.connect().await;
    let _second = relay.connect().await;
    assert_eq!(refused_status(relay.try_connect("", None).await), status);

    // Closing a connection frees its slot.
    drop(first);
    relay.wait_for_metrics(0, 1).await;
    let _third = relay.connect().await;
}

#[tokio::test]
async fn connections_per_address_are_capped() {
    let config = Config {
        max_conns_per_ip: 2,
        ..test_config()
    };
    assert_two_sockets_fit(config, 429).await;
}

#[tokio::test]
async fn connections_on_the_relay_are_capped() {
    let config = Config {
        max_conns: 2,
        ..test_config()
    };
    assert_two_sockets_fit(config, 503).await;
}

#[tokio::test]
async fn frames_over_the_egress_budget_wait_for_it() {
    let relay = Relay::start(Config {
        egress_bytes_per_sec: 65_536,
        ..test_config()
    })
    .await;
    let mut a = relay.connect().await;
    join(&mut a, &room_id(1)).await;
    let mut b = relay.connect().await;
    join(&mut b, &room_id(1)).await;
    assert_eq!(recv_json(&mut a).await, presence(1, &b.peer, "join"));

    // The second frame overdraws the one-second budget by about a second, so the third
    // waits for the refill. Meanwhile the relay doesn't read the sender: the ping
    // behind the third frame is answered only after it.
    let payload = vec![0xaa; 65_000 - forward_frame(1, &b.peer, &[]).len()];
    let sent = Instant::now();
    for _ in 0..3 {
        send_binary(&mut a, forward_frame(1, &b.peer, &payload)).await;
    }
    send_json(&mut a, json!({ "t": "ping" })).await;
    for _ in 0..3 {
        assert_eq!(
            recv_binary(&mut b).await,
            forward_frame(1, &a.peer, &payload)
        );
    }
    assert_eq!(recv_json(&mut a).await, json!({ "t": "pong" }));
    assert!(
        sent.elapsed() >= Duration::from_millis(500),
        "the third frame waited"
    );
    assert_eq!(
        relay
            .metrics()
            .await
            .metric("keyguard_relay_rate_limits_total{reason=\"egress\"}"),
        1.0
    );
}

#[tokio::test]
async fn frames_that_wait_too_long_are_dropped() {
    let relay = Relay::start(Config {
        // Below the configurable minimum, so a frame overdraws the budget by 9 seconds.
        egress_bytes_per_sec: 6_000,
        ..test_config()
    })
    .await;
    let mut a = relay.connect().await;
    join(&mut a, &room_id(1)).await;
    let mut b = relay.connect().await;
    join(&mut b, &room_id(1)).await;
    assert_eq!(recv_json(&mut a).await, presence(1, &b.peer, "join"));

    let payload = vec![0xaa; 60_000];
    send_binary(&mut a, forward_frame(1, &b.peer, &payload)).await;
    assert_eq!(
        recv_binary(&mut b).await,
        forward_frame(1, &a.peer, &payload)
    );
    let sent = Instant::now();
    send_binary(&mut a, forward_frame(1, &b.peer, b"late")).await;

    // While the frame waits, frames to its sender still arrive.
    let mut c = relay.connect().await;
    join(&mut c, &room_id(1)).await;
    assert_eq!(recv_json(&mut a).await, presence(1, &c.peer, "join"));
    assert_eq!(recv_json(&mut b).await, presence(1, &c.peer, "join"));

    // After five seconds the frame is dropped, and the relay reads the sender again.
    tokio::time::sleep(Duration::from_secs(4)).await;
    expect_error(&mut a, "rate_limited").await;
    assert!(sent.elapsed() >= Duration::from_secs(5));
    expect_silence(&mut b).await;
    send_json(&mut a, json!({ "t": "ping" })).await;
    assert_eq!(recv_json(&mut a).await, json!({ "t": "pong" }));
    let metrics = relay.metrics().await;
    assert_eq!(
        metrics.metric("keyguard_relay_rate_limits_total{reason=\"egress\"}"),
        1.0
    );
    assert_eq!(
        metrics.metric("keyguard_relay_websocket_sent_bytes_total{format=\"binary\"}"),
        forward_frame(1, &b.peer, &payload).len() as f64
    );
}

#[tokio::test]
async fn heavy_senders_wait_for_light_ones() {
    let relay = Relay::start(Config {
        egress_bytes_per_sec: 65_536,
        trust_proxy: loopback_proxy(),
        ..test_config()
    })
    .await;
    let mut heavy = relay.connect_from("203.0.113.1").await;
    join(&mut heavy, &room_id(1)).await;
    let mut light = relay.connect_from("203.0.113.2").await;
    join(&mut light, &room_id(1)).await;
    assert_eq!(
        recv_json(&mut heavy).await,
        presence(1, &light.peer, "join")
    );

    let payload = vec![0xaa; 60_000 - forward_frame(1, &light.peer, &[]).len()];
    send_binary(&mut heavy, forward_frame(1, &light.peer, &payload)).await;
    assert_eq!(
        recv_binary(&mut light).await,
        forward_frame(1, &heavy.peer, &payload)
    );
    send_binary(&mut light, forward_frame(1, &heavy.peer, b"small")).await;
    assert_eq!(
        recv_binary(&mut heavy).await,
        forward_frame(1, &light.peer, b"small")
    );

    // Under half of the budget is left: the sender above its fair share waits until the
    // budget is half full again, the one under it goes on.
    let sent = Instant::now();
    send_binary(&mut heavy, forward_frame(1, &light.peer, b"more")).await;
    for _ in 0..3 {
        send_binary(&mut light, forward_frame(1, &heavy.peer, b"small")).await;
        assert_eq!(
            recv_binary(&mut heavy).await,
            forward_frame(1, &light.peer, b"small")
        );
    }
    assert_eq!(
        recv_binary(&mut light).await,
        forward_frame(1, &heavy.peer, b"more")
    );
    assert!(
        sent.elapsed() >= Duration::from_millis(300),
        "the heavy sender waited"
    );
    expect_silence(&mut heavy).await;
}

/// Repeats `attempt` until it succeeds, while the relay releases a connection slot.
async fn retry<T, E, F: Future<Output = Result<T, E>>>(mut attempt: impl FnMut() -> F) -> T {
    let deadline = Instant::now() + TIMEOUT;
    loop {
        if let Ok(value) = attempt().await {
            return value;
        }
        assert!(Instant::now() < deadline, "the relay keeps refusing");
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
}

/// `GET /healthz`, or an error if the relay closes the connection without an answer.
async fn try_health(relay: &Relay) -> Result<(), ()> {
    let mut stream = TcpStream::connect(relay.addr).await.unwrap();
    let request = b"GET /healthz HTTP/1.1\r\nHost: relay\r\nConnection: close\r\n\r\n";
    _ = stream.write_all(request).await;
    let mut raw = Vec::new();
    _ = tokio::time::timeout(TIMEOUT, stream.read_to_end(&mut raw))
        .await
        .expect("the relay answers or closes in time");
    raw.starts_with(b"HTTP/1.1 200 ").then_some(()).ok_or(())
}

#[tokio::test]
async fn http_connections_per_address_are_capped() {
    let relay = Relay::start(Config {
        max_conns_per_ip: 1,
        ..test_config()
    })
    .await;
    let mut held = relay.open_http_connection().await;
    assert!(is_open(&mut held).await);
    assert!(!is_open(&mut relay.open_http_connection().await).await);

    // Closing the connection frees its slot, and a WebSocket gives it back once
    // upgraded: the HTTP request still goes through.
    drop(held);
    let _socket = retry(|| relay.try_connect("", None)).await;
    retry(|| try_health(&relay)).await;
}

#[tokio::test]
async fn http_connections_from_a_trusted_proxy_count_toward_the_total_only() {
    let relay = Relay::start(Config {
        max_conns_per_ip: 1,
        max_conns: 2,
        trust_proxy: loopback_proxy(),
        ..test_config()
    })
    .await;
    let mut first = relay.open_http_connection().await;
    let mut second = relay.open_http_connection().await;
    assert!(is_open(&mut first).await);
    assert!(is_open(&mut second).await);
    assert!(!is_open(&mut relay.open_http_connection().await).await);
}

/// Sends the header of an ICE request whose body never comes, and returns the answer.
async fn ice_request_without_body(relay: &Relay, wait: Duration) -> String {
    let mut stream = TcpStream::connect(relay.addr).await.unwrap();
    stream
        .write_all(b"POST /v1/ice-servers HTTP/1.1\r\nHost: relay\r\nContent-Length: 64\r\n\r\n")
        .await
        .unwrap();
    let mut raw = Vec::new();
    tokio::time::timeout(wait, stream.read_to_end(&mut raw))
        .await
        .expect("the relay answers and closes in time")
        .unwrap();
    String::from_utf8(raw).unwrap()
}

#[tokio::test]
async fn unauthorized_ice_requests_are_answered_before_their_body() {
    let relay = Relay::start(Config {
        token: Some("abcdefghijklmnop".to_owned()),
        ..test_config()
    })
    .await;
    let answer = ice_request_without_body(&relay, TIMEOUT).await;
    assert!(answer.starts_with("HTTP/1.1 401 "), "{answer}");
}

#[tokio::test]
async fn ice_request_bodies_have_a_deadline() {
    let deadline = Duration::from_millis(500);
    let relay = Relay::start(Config {
        timeouts: Timeouts {
            http_body: deadline,
            ..Timeouts::default()
        },
        ..test_config()
    })
    .await;
    let started = Instant::now();
    let answer = ice_request_without_body(&relay, TIMEOUT).await;
    assert!(answer.starts_with("HTTP/1.1 408 "), "{answer}");
    assert!(started.elapsed() >= deadline);
}

#[tokio::test]
async fn room_count_is_capped() {
    let relay = Relay::start(Config {
        max_rooms: 1,
        ..test_config()
    })
    .await;
    let mut a = relay.connect().await;
    let mut b = relay.connect().await;
    join(&mut a, &room_id(1)).await;

    send_join(&mut b, &room_id(2)).await;
    expect_join_error(&mut b, "rate_limited", &room_id(2)).await;
    // Joining an existing room needs no new room.
    assert_eq!(join(&mut b, &room_id(1)).await, [a.peer.clone()]);
    send_json(&mut b, json!({ "t": "leave" })).await;

    send_json(&mut a, json!({ "t": "leave" })).await;
    relay.wait_for_metrics(0, 2).await;
    assert!(join(&mut b, &room_id(2)).await.is_empty());
    assert_eq!(
        relay
            .metrics()
            .await
            .metric("keyguard_relay_rate_limits_total{reason=\"rooms\"}"),
        1.0
    );
}

#[tokio::test]
async fn rooms_per_address_are_capped() {
    let relay = Relay::start(Config {
        max_rooms_per_ip: 2,
        trust_proxy: loopback_proxy(),
        ..test_config()
    })
    .await;
    let mut a = relay.connect_from("203.0.113.1").await;
    let mut b = relay.connect_from("203.0.113.1").await;
    let mut other = relay.connect_from("203.0.113.2").await;
    join(&mut a, &room_id(1)).await;
    join(&mut a, &room_id(2)).await;

    // The address is in two rooms, whichever of its connections asks.
    send_join(&mut b, &room_id(3)).await;
    expect_join_error(&mut b, "rate_limited", &room_id(3)).await;
    // A room it is in already counts once, and other addresses have their own rooms.
    assert_eq!(join(&mut b, &room_id(1)).await, [a.peer.clone()]);
    assert_eq!(recv_json(&mut a).await, presence(1, &b.peer, "join"));
    assert!(join(&mut other, &room_id(3)).await.is_empty());
    send_join(&mut a, &room_id(3)).await;
    expect_join_error(&mut a, "rate_limited", &room_id(3)).await;

    // Room 1 counts until the last of the address's connections leaves it.
    send_json(&mut a, json!({ "t": "leave", "room": room_id(1) })).await;
    send_join(&mut a, &room_id(3)).await;
    expect_join_error(&mut a, "rate_limited", &room_id(3)).await;
    drop(b);
    relay.wait_for_metrics(2, 2).await;
    assert_eq!(join(&mut a, &room_id(3)).await, [other.peer.clone()]);
    assert_eq!(
        relay
            .metrics()
            .await
            .metric("keyguard_relay_rate_limits_total{reason=\"address_rooms\"}"),
        3.0
    );
}

/// The /64s of one IPv6 /48 share eight times each per-address limit.
const KEYS_PER_NETWORK: u8 = 8;

#[tokio::test]
async fn connections_per_ipv6_network_are_capped() {
    let relay = Relay::start(Config {
        max_conns_per_ip: 1,
        trust_proxy: loopback_proxy(),
        ..test_config()
    })
    .await;
    let mut held = Vec::new();
    for subnet in 0..KEYS_PER_NETWORK {
        held.push(relay.connect_from(&format!("2001:db8:1:{subnet}::1")).await);
    }
    let refused = relay
        .try_connect_with("", None, &[("x-forwarded-for", "2001:db8:1:ff::1")])
        .await;
    assert_eq!(refused_status(refused), 429);
    // Other networks have their own connections.
    let _other = relay.connect_from("2001:db8:2::1").await;
    assert_eq!(
        relay.metrics().await.metric(
            "keyguard_relay_connection_refusals_total{transport=\"websocket\",reason=\"per_network\"}"
        ),
        1.0
    );
    // Closing one frees its slot for any /64 of the network.
    drop(held.pop());
    relay.wait_for_metrics(0, u64::from(KEYS_PER_NETWORK)).await;
    let _again = relay.connect_from("2001:db8:1:ff::1").await;
}

#[tokio::test]
async fn rooms_per_ipv6_network_are_capped() {
    let relay = Relay::start(Config {
        max_rooms_per_ip: 1,
        trust_proxy: loopback_proxy(),
        ..test_config()
    })
    .await;
    let mut clients = Vec::new();
    for subnet in 0..=KEYS_PER_NETWORK {
        clients.push(relay.connect_from(&format!("2001:db8:1:{subnet}::1")).await);
    }
    for (room, client) in (0..KEYS_PER_NETWORK).zip(&mut clients) {
        assert!(join(client, &room_id(room)).await.is_empty());
    }
    let last = &mut clients[usize::from(KEYS_PER_NETWORK)];
    send_join(last, &room_id(KEYS_PER_NETWORK)).await;
    expect_join_error(last, "rate_limited", &room_id(KEYS_PER_NETWORK)).await;
    // A room the network is in already counts once, and other networks have their own.
    assert_eq!(join(last, &room_id(0)).await.len(), 1);
    let mut other = relay.connect_from("2001:db8:2::1").await;
    assert!(
        join(&mut other, &room_id(KEYS_PER_NETWORK))
            .await
            .is_empty()
    );
    assert_eq!(
        relay
            .metrics()
            .await
            .metric("keyguard_relay_rate_limits_total{reason=\"network_rooms\"}"),
        1.0
    );
}

#[tokio::test]
async fn a_non_reading_ping_client_is_disconnected() {
    let relay = Relay::start(Config {
        // Exercise backpressure on admitted pings, without hitting either budget.
        msgs_per_sec: 100_000,
        bytes_per_sec: 1 << 30,
        timeouts: Timeouts {
            send: Duration::from_millis(500),
            ..Timeouts::default()
        },
        ..test_config()
    })
    .await;
    let client = relay.connect().await;
    let (_reader, writer) = flood_non_reading_client(client.socket).await;
    // The writer timeout must act before the next server heartbeat.
    relay
        .wait_for_metric("keyguard_relay_websocket_connections", 0.0)
        .await;
    assert_eq!(
        relay
            .metrics()
            .await
            .metric("keyguard_relay_websocket_send_failures_total{reason=\"timeout\"}"),
        1.0
    );
    writer.abort();
    _ = writer.await;
}
