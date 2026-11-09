//! One connection in several rooms: routing, presence, leaves, limits and binary frames.

mod common;

use common::*;
use keyguard_relay::Config;
use serde_json::{Value, json};

/// A `pub` in `room` to `to`, a peer id or `"*"`.
fn publish(room: u8, to: &str, data: &str) -> Value {
    json!({ "t": "pub", "room": room_id(room), "to": to, "data": data })
}

fn leave(room: u8) -> Value {
    json!({ "t": "leave", "room": room_id(room) })
}

#[tokio::test]
async fn each_room_routes_its_own_messages() {
    let relay = Relay::start(test_config()).await;
    let mut a = relay.connect().await;
    let mut b = relay.connect().await;
    let mut c = relay.connect().await;

    // a is in rooms 1 and 2, b in room 1 and c in room 2.
    let first = join_frame(&mut a, &room_id(1)).await;
    let ticket = first["ticket"].clone();
    assert_eq!(
        first,
        json!({ "t": "joined", "room": room_id(1), "peers": [], "ticket": ticket })
    );
    // One ticket per connection.
    assert_eq!(
        join_frame(&mut a, &room_id(2)).await,
        json!({ "t": "joined", "room": room_id(2), "peers": [], "ticket": ticket })
    );
    assert_eq!(join(&mut b, &room_id(1)).await, [a.peer.clone()]);
    assert_eq!(join(&mut c, &room_id(2)).await, [a.peer.clone()]);
    assert_eq!(recv_json(&mut a).await, presence(1, &b.peer, "join"));
    assert_eq!(recv_json(&mut a).await, presence(2, &c.peer, "join"));

    send_json(&mut a, publish(1, &b.peer, "b25l")).await;
    assert_eq!(recv_json(&mut b).await, msg(1, &a.peer, "b25l"));
    // b isn't in room 2.
    send_json(&mut a, publish(2, &b.peer, "dHdv")).await;
    send_json(&mut c, publish(2, &a.peer, "dGhyZWU=")).await;
    assert_eq!(recv_json(&mut a).await, msg(2, &c.peer, "dGhyZWU="));
    for socket in [&mut a, &mut b, &mut c] {
        expect_silence(socket).await;
    }
}

#[tokio::test]
async fn broadcast_stays_in_its_room() {
    let relay = Relay::start(test_config()).await;
    let mut a = relay.connect().await;
    let mut b = relay.connect().await;
    let mut c = relay.connect().await;
    let mut d = relay.connect().await;
    join(&mut a, &room_id(1)).await;
    join(&mut a, &room_id(2)).await;
    join(&mut b, &room_id(1)).await;
    join(&mut c, &room_id(1)).await;
    join(&mut d, &room_id(2)).await;
    assert_eq!(recv_json(&mut a).await, presence(1, &b.peer, "join"));
    assert_eq!(recv_json(&mut a).await, presence(1, &c.peer, "join"));
    assert_eq!(recv_json(&mut a).await, presence(2, &d.peer, "join"));
    assert_eq!(recv_json(&mut b).await, presence(1, &c.peer, "join"));

    send_json(&mut a, publish(1, "*", "AAEC")).await;
    assert_eq!(recv_json(&mut b).await, msg(1, &a.peer, "AAEC"));
    assert_eq!(recv_json(&mut c).await, msg(1, &a.peer, "AAEC"));
    expect_silence(&mut d).await;

    send_json(&mut d, publish(2, "*", "AwQF")).await;
    assert_eq!(recv_json(&mut a).await, msg(2, &d.peer, "AwQF"));
    for socket in [&mut a, &mut b, &mut c, &mut d] {
        expect_silence(socket).await;
    }
}

#[tokio::test]
async fn a_second_join_of_the_same_room_closes_the_connection() {
    let relay = Relay::start(test_config()).await;
    let mut other = relay.connect().await;
    join(&mut other, &room_id(2)).await;

    let mut socket = relay.connect().await;
    join(&mut socket, &room_id(1)).await;
    send_join(&mut socket, &room_id(1)).await;
    expect_error(&mut socket, "bad_frame").await;
    assert_eq!(expect_closed(&mut socket).await, Some(1008));
    expect_silence(&mut other).await;

    // After a leave, the room can be joined again.
    let mut socket = relay.connect().await;
    assert_eq!(join(&mut socket, &room_id(2)).await, [other.peer.clone()]);
    assert_eq!(
        recv_json(&mut other).await,
        presence(2, &socket.peer, "join")
    );
    send_json(&mut socket, leave(2)).await;
    assert_eq!(
        recv_json(&mut other).await,
        presence(2, &socket.peer, "leave")
    );
    assert_eq!(join(&mut socket, &room_id(2)).await, [other.peer.clone()]);
    assert_eq!(
        recv_json(&mut other).await,
        presence(2, &socket.peer, "join")
    );
}

#[tokio::test]
async fn a_connection_has_a_room_limit() {
    let relay = Relay::start(Config {
        max_rooms_per_conn: 2,
        ..test_config()
    })
    .await;
    let mut a = relay.connect().await;
    join(&mut a, &room_id(1)).await;
    join(&mut a, &room_id(2)).await;

    send_join(&mut a, &room_id(3)).await;
    expect_join_error(&mut a, "too_many_rooms", &room_id(3)).await;
    // The connection stays open, and a leave makes space.
    send_json(&mut a, leave(1)).await;
    assert!(join(&mut a, &room_id(3)).await.is_empty());
    relay.wait_for_metrics(2, 1).await;
    assert_eq!(
        relay
            .metrics()
            .await
            .metric("keyguard_relay_protocol_errors_total{reason=\"too_many_rooms\"}"),
        1.0
    );
}

#[tokio::test]
async fn pub_names_its_room_on_a_connection_in_several() {
    let relay = Relay::start(test_config()).await;
    let mut a = relay.connect().await;
    let mut b = relay.connect().await;
    join(&mut a, &room_id(1)).await;
    join(&mut a, &room_id(2)).await;
    join(&mut b, &room_id(1)).await;
    assert_eq!(recv_json(&mut a).await, presence(1, &b.peer, "join"));

    send_json(&mut a, publish(3, &b.peer, "")).await;
    expect_error(&mut a, "not_joined").await;
    send_json(&mut a, json!({ "t": "pub", "to": b.peer, "data": "" })).await;
    expect_error(&mut a, "bad_frame").await;
    assert_eq!(expect_closed(&mut a).await, Some(1008));
    assert_eq!(recv_json(&mut b).await, presence(1, &a.peer, "leave"));
    expect_silence(&mut b).await;
}

#[tokio::test]
async fn leave_names_one_room_or_leaves_them_all() {
    let relay = Relay::start(test_config()).await;
    let mut a = relay.connect().await;
    let mut b = relay.connect().await;
    let mut c = relay.connect().await;
    let mut d = relay.connect().await;
    for room in 1..=3 {
        join(&mut a, &room_id(room)).await;
    }
    join(&mut b, &room_id(1)).await;
    join(&mut c, &room_id(2)).await;
    join(&mut d, &room_id(3)).await;
    for (room, peer) in [(1, &b.peer), (2, &c.peer), (3, &d.peer)] {
        assert_eq!(recv_json(&mut a).await, presence(room, peer, "join"));
    }

    send_json(&mut a, leave(1)).await;
    assert_eq!(recv_json(&mut b).await, presence(1, &a.peer, "leave"));
    send_json(&mut a, leave(1)).await;
    expect_error(&mut a, "not_joined").await;
    send_json(&mut b, json!({ "t": "pub", "to": a.peer, "data": "" })).await;
    expect_silence(&mut a).await;
    expect_silence(&mut c).await;
    expect_silence(&mut d).await;

    send_json(&mut a, json!({ "t": "leave" })).await;
    assert_eq!(recv_json(&mut c).await, presence(2, &a.peer, "leave"));
    assert_eq!(recv_json(&mut d).await, presence(3, &a.peer, "leave"));
    send_json(&mut a, json!({ "t": "leave" })).await;
    expect_error(&mut a, "not_joined").await;
    // A room the connection left: dropped without an error.
    send_binary(&mut a, forward_frame(2, &c.peer, &[])).await;
    expect_silence(&mut a).await;
    expect_silence(&mut c).await;
    relay.wait_for_metrics(3, 4).await;
}

#[tokio::test]
async fn closing_leaves_every_room_and_metrics_count_memberships() {
    let relay = Relay::start(test_config()).await;
    let mut a = relay.connect().await;
    let mut b = relay.connect().await;
    let mut c = relay.connect().await;
    join(&mut a, &room_id(1)).await;
    join(&mut a, &room_id(2)).await;
    join(&mut b, &room_id(1)).await;
    join(&mut c, &room_id(2)).await;
    let metrics = relay.metrics().await;
    assert_eq!(metrics.metric("keyguard_relay_rooms"), 2.0);
    assert_eq!(metrics.metric("keyguard_relay_joined_peers"), 4.0);

    let gone = a.peer.clone();
    drop(a);
    assert_eq!(recv_json(&mut b).await, presence(1, &gone, "leave"));
    assert_eq!(recv_json(&mut c).await, presence(2, &gone, "leave"));
    relay.wait_for_metrics(2, 2).await;
    assert_eq!(
        relay.metrics().await.metric("keyguard_relay_joined_peers"),
        2.0
    );
}

#[tokio::test]
async fn binary_frames_stay_in_the_room_they_name() {
    let relay = Relay::start(test_config()).await;
    let mut a = relay.connect().await;
    let mut b = relay.connect().await;
    let mut c = relay.connect().await;
    let mut d = relay.connect().await;
    // a and d share rooms 1 and 2, b is in room 1 and c in room 2.
    for socket in [&mut a, &mut d] {
        join(socket, &room_id(1)).await;
        join(socket, &room_id(2)).await;
    }
    join(&mut b, &room_id(1)).await;
    join(&mut c, &room_id(2)).await;
    for (room, peer) in [(1, &d.peer), (2, &d.peer), (1, &b.peer), (2, &c.peer)] {
        assert_eq!(recv_json(&mut a).await, presence(room, peer, "join"));
    }
    assert_eq!(recv_json(&mut d).await, presence(1, &b.peer, "join"));
    assert_eq!(recv_json(&mut d).await, presence(2, &c.peer, "join"));

    send_binary(&mut a, forward_frame(2, &c.peer, b"to c")).await;
    assert_eq!(
        recv_binary(&mut c).await,
        forward_frame(2, &a.peer, b"to c")
    );
    send_binary(&mut c, forward_frame(2, &a.peer, b"to a")).await;
    assert_eq!(
        recv_binary(&mut a).await,
        forward_frame(2, &c.peer, b"to a")
    );
    // A peer in two shared rooms gets the frame in the room it names.
    send_binary(&mut a, forward_frame(1, &d.peer, b"to d")).await;
    assert_eq!(
        recv_binary(&mut d).await,
        forward_frame(1, &a.peer, b"to d")
    );
    send_binary(&mut a, forward_frame(2, &d.peer, b"to d")).await;
    assert_eq!(
        recv_binary(&mut d).await,
        forward_frame(2, &a.peer, b"to d")
    );
    // c isn't in room 1, even though a shares room 2 with it.
    send_binary(&mut a, forward_frame(1, &c.peer, b"dropped")).await;
    // b isn't in room 2, and c isn't in room 1: no frame and no error.
    send_binary(&mut b, forward_frame(2, &c.peer, b"dropped")).await;
    send_binary(&mut c, forward_frame(1, &b.peer, b"dropped")).await;
    for socket in [&mut a, &mut b, &mut c, &mut d] {
        expect_silence(socket).await;
    }
}

#[tokio::test]
async fn leaving_a_room_discards_frames_queued_from_it() {
    let relay = Relay::start(Config {
        msgs_per_sec: 1000,
        ..test_config()
    })
    .await;
    let mut a = relay.connect().await;
    let mut b = relay.connect().await;
    let mut c = relay.connect().await;
    join(&mut a, &room_id(1)).await;
    join(&mut b, &room_id(1)).await;
    join(&mut b, &room_id(2)).await;
    join(&mut c, &room_id(2)).await;
    assert_eq!(recv_json(&mut a).await, presence(1, &b.peer, "join"));
    assert_eq!(recv_json(&mut b).await, presence(2, &c.peer, "join"));

    // Pipeline a burst without reading b, then leave room 1 while frames may still be
    // queued. Frames before the `pong` are valid.
    for _ in 0..32 {
        send_json(&mut a, publish(1, &b.peer, "b2xk")).await;
        send_binary(&mut a, forward_frame(1, &b.peer, b"old")).await;
    }
    send_json(&mut b, leave(1)).await;
    send_json(&mut b, json!({ "t": "ping" })).await;
    loop {
        match next_frame(&mut b).await {
            Frame::Json(frame) if frame["t"] == "pong" => break,
            Frame::Json(frame) => assert_eq!(frame, msg(1, &a.peer, "b2xk")),
            Frame::Binary(frame) => assert_eq!(frame, forward_frame(1, &a.peer, b"old")),
            frame => panic!("expected a frame before the pong, got {frame:?}"),
        }
    }
    assert_eq!(recv_json(&mut a).await, presence(1, &b.peer, "leave"));

    // Room 2 goes on.
    send_json(&mut c, publish(2, &b.peer, "bmV3")).await;
    send_binary(&mut c, forward_frame(2, &b.peer, b"new")).await;
    assert_eq!(recv_json(&mut b).await, msg(2, &c.peer, "bmV3"));
    assert_eq!(recv_binary(&mut b).await, forward_frame(2, &c.peer, b"new"));
}

#[tokio::test]
async fn a_single_room_client_needs_no_room_fields() {
    let relay = Relay::start(test_config()).await;
    let mut a = relay.connect().await;
    let mut b = relay.connect().await;
    join(&mut a, &room_id(1)).await;
    join(&mut b, &room_id(1)).await;
    assert_eq!(recv_json(&mut a).await, presence(1, &b.peer, "join"));

    send_json(&mut a, json!({ "t": "pub", "to": b.peer, "data": "AAEC" })).await;
    assert_eq!(recv_json(&mut b).await, msg(1, &a.peer, "AAEC"));
    send_json(&mut b, json!({ "t": "pub", "to": "*", "data": "AwQF" })).await;
    assert_eq!(recv_json(&mut a).await, msg(1, &b.peer, "AwQF"));
    send_binary(&mut a, forward_frame(1, &b.peer, b"bytes")).await;
    assert_eq!(
        recv_binary(&mut b).await,
        forward_frame(1, &a.peer, b"bytes")
    );

    send_json(&mut a, json!({ "t": "leave" })).await;
    assert_eq!(recv_json(&mut b).await, presence(1, &a.peer, "leave"));
    // The same peer id after the leave.
    assert_eq!(join(&mut a, &room_id(1)).await, [b.peer.clone()]);
    assert_eq!(recv_json(&mut b).await, presence(1, &a.peer, "join"));
}
