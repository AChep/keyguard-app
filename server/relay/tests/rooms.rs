//! Rooms, presence, fan-out, relay-forward frames and the health endpoint.

mod common;

use common::*;
use keyguard_relay::{Config, MAX_FRAME_BYTES};
use serde_json::json;

#[tokio::test]
async fn the_relay_welcomes_each_connection_with_its_own_peer_id() {
    let relay = Relay::start(Config {
        max_room_peers: 3,
        msgs_per_sec: 40,
        bytes_per_sec: 1_048_576,
        max_rooms_per_conn: 8,
        idle_timeout: std::time::Duration::from_secs(45),
        ..test_config()
    })
    .await;
    let a = relay.connect().await;
    assert_eq!(
        a.welcome,
        json!({
            "t": "welcome",
            "v": 1,
            "peer": a.peer,
            "features": ["room-forward"],
            "limits": {
                "maxMessageBytes": MAX_FRAME_BYTES,
                "messagesPerSecond": 40,
                "bytesPerSecond": 1_048_576,
                "roomsPerConnection": 8,
                "peersPerRoom": 3,
                "queuedFrames": 128,
                "idleSeconds": 45,
            },
        })
    );
    let b = relay.connect().await;
    assert_ne!(a.peer, b.peer);
    assert_eq!(a.welcome["limits"], b.welcome["limits"]);
}

#[tokio::test]
async fn the_assigned_peer_id_names_the_connection_in_every_room() {
    let relay = Relay::start(test_config()).await;
    let mut a = relay.connect().await;
    let mut b = relay.connect().await;
    assert!(join(&mut a, &room_id(1)).await.is_empty());
    assert!(join(&mut a, &room_id(2)).await.is_empty());
    assert_eq!(join(&mut b, &room_id(1)).await, [a.peer.clone()]);
    assert_eq!(join(&mut b, &room_id(2)).await, [a.peer.clone()]);
    assert_eq!(recv_json(&mut a).await, presence(1, &b.peer, "join"));
    assert_eq!(recv_json(&mut a).await, presence(2, &b.peer, "join"));

    let to_b = json!({ "t": "pub", "room": room_id(2), "to": b.peer, "data": "AAEC" });
    send_json(&mut a, to_b).await;
    assert_eq!(recv_json(&mut b).await, msg(2, &a.peer, "AAEC"));
    send_binary(&mut b, forward_frame(1, &a.peer, b"bytes")).await;
    assert_eq!(
        recv_binary(&mut a).await,
        forward_frame(1, &b.peer, b"bytes")
    );

    // The id lasts as long as the connection, across leaves.
    send_json(&mut b, json!({ "t": "leave" })).await;
    // The rooms are left in no particular order.
    let mut leaves = [recv_json(&mut a).await, recv_json(&mut a).await];
    leaves.sort_by_key(|leave| leave["room"].as_str().unwrap().to_owned());
    assert_eq!(
        leaves,
        [presence(1, &b.peer, "leave"), presence(2, &b.peer, "leave")]
    );
    assert_eq!(join(&mut b, &room_id(1)).await, [a.peer.clone()]);
    assert_eq!(recv_json(&mut a).await, presence(1, &b.peer, "join"));
}

#[tokio::test]
async fn join_lists_peers_and_announces_presence() {
    let relay = Relay::start(test_config()).await;
    let mut a = relay.connect().await;
    let mut b = relay.connect().await;
    let mut c = relay.connect().await;

    assert!(join(&mut a, &room_id(1)).await.is_empty());
    assert_eq!(join(&mut b, &room_id(1)).await, [a.peer.clone()]);
    assert_eq!(recv_json(&mut a).await, presence(1, &b.peer, "join"));
    assert_eq!(join(&mut c, &room_id(1)).await, peers(&[&a, &b]));
    assert_eq!(recv_json(&mut a).await, presence(1, &c.peer, "join"));
    assert_eq!(recv_json(&mut b).await, presence(1, &c.peer, "join"));

    // An explicit leave, then a fresh join on the same connection.
    send_json(&mut b, json!({ "t": "leave" })).await;
    assert_eq!(recv_json(&mut a).await, presence(1, &b.peer, "leave"));
    assert_eq!(recv_json(&mut c).await, presence(1, &b.peer, "leave"));
    assert_eq!(join(&mut b, &room_id(1)).await, peers(&[&a, &c]));
    assert_eq!(recv_json(&mut a).await, presence(1, &b.peer, "join"));
    assert_eq!(recv_json(&mut c).await, presence(1, &b.peer, "join"));

    // A dropped connection leaves too.
    let gone = b.peer.clone();
    drop(b);
    assert_eq!(recv_json(&mut a).await, presence(1, &gone, "leave"));
    assert_eq!(recv_json(&mut c).await, presence(1, &gone, "leave"));
    expect_silence(&mut a).await;
    expect_silence(&mut c).await;
}

#[tokio::test]
async fn joined_carries_a_ticket_per_connection() {
    let relay = Relay::start(test_config()).await;
    let mut a = relay.connect().await;
    let mut b = relay.connect().await;
    let first = join_frame(&mut a, &room_id(1)).await;
    let ticket = first["ticket"].as_str().unwrap();
    assert!(is_lower_hex(ticket, 16), "{ticket}");
    // The ticket is a secret for the ICE endpoint, unlike the peer id others see.
    assert_ne!(ticket, a.peer);
    assert_ne!(join_ticket(&mut b, &room_id(1)).await, ticket);
}

#[tokio::test]
async fn switching_rooms_discards_queued_frames_from_the_previous_room() {
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
    join(&mut c, &room_id(2)).await;
    assert_eq!(recv_json(&mut a).await, presence(1, &b.peer, "join"));

    // Pipeline a burst without reading b, then switch its room while frames
    // may still be queued. Frames sent before the new `joined` are valid.
    for _ in 0..32 {
        send_json(&mut a, json!({ "t": "pub", "to": b.peer, "data": "b2xk" })).await;
        send_binary(&mut a, forward_frame(1, &b.peer, b"old")).await;
    }
    send_json(&mut b, json!({ "t": "leave" })).await;
    send_join(&mut b, &room_id(2)).await;
    loop {
        match next_frame(&mut b).await {
            Frame::Json(frame) if frame["t"] == "joined" => {
                assert_eq!(frame["peers"], json!([c.peer]));
                break;
            }
            Frame::Json(frame) => assert_eq!(frame["t"], "msg", "{frame}"),
            Frame::Binary(frame) => assert_eq!(frame, forward_frame(1, &a.peer, b"old")),
            frame => panic!("expected a frame before joining the new room, got {frame:?}"),
        }
    }
    assert_eq!(recv_json(&mut c).await, presence(2, &b.peer, "join"));

    send_json(&mut c, json!({ "t": "pub", "to": b.peer, "data": "bmV3" })).await;
    send_binary(&mut c, forward_frame(2, &b.peer, b"new")).await;
    assert_eq!(recv_json(&mut b).await, msg(2, &c.peer, "bmV3"));
    assert_eq!(recv_binary(&mut b).await, forward_frame(2, &c.peer, b"new"));
}

/// Three peers in room 1 and one in room 2, with all presence frames consumed.
async fn three_peers_and_a_stranger(relay: &Relay) -> [Client; 4] {
    let mut a = relay.connect().await;
    let mut b = relay.connect().await;
    let mut c = relay.connect().await;
    let mut d = relay.connect().await;
    join(&mut a, &room_id(1)).await;
    join(&mut b, &room_id(1)).await;
    join(&mut c, &room_id(1)).await;
    join(&mut d, &room_id(2)).await;
    assert_eq!(recv_json(&mut a).await, presence(1, &b.peer, "join"));
    assert_eq!(recv_json(&mut a).await, presence(1, &c.peer, "join"));
    assert_eq!(recv_json(&mut b).await, presence(1, &c.peer, "join"));
    [a, b, c, d]
}

#[tokio::test]
async fn unicast_reaches_only_the_target() {
    let relay = Relay::start(test_config()).await;
    let [mut a, mut b, mut c, mut d] = three_peers_and_a_stranger(&relay).await;

    send_json(
        &mut a,
        json!({ "t": "pub", "to": b.peer, "data": "aGVsbG8=" }),
    )
    .await;
    assert_eq!(recv_json(&mut b).await, msg(1, &a.peer, "aGVsbG8="));
    for socket in [&mut a, &mut c, &mut d] {
        expect_silence(socket).await;
    }

    // Addressing yourself or someone outside the room delivers nothing.
    let own = a.peer.clone();
    send_json(&mut a, json!({ "t": "pub", "to": own, "data": "" })).await;
    send_json(&mut a, json!({ "t": "pub", "to": d.peer, "data": "" })).await;
    for socket in [&mut a, &mut b, &mut c, &mut d] {
        expect_silence(socket).await;
    }
}

#[tokio::test]
async fn broadcast_reaches_everyone_else_in_the_room() {
    let relay = Relay::start(test_config()).await;
    let [mut a, mut b, mut c, mut d] = three_peers_and_a_stranger(&relay).await;

    send_json(&mut a, json!({ "t": "pub", "to": "*", "data": "AAEC" })).await;
    let expected = msg(1, &a.peer, "AAEC");
    assert_eq!(recv_json(&mut b).await, expected);
    assert_eq!(recv_json(&mut c).await, expected);
    expect_silence(&mut a).await;
    expect_silence(&mut d).await;
}

#[tokio::test]
async fn binary_frames_are_forwarded_with_the_sender_id() {
    let relay = Relay::start(test_config()).await;
    let [mut a, mut b, mut c, mut d] = three_peers_and_a_stranger(&relay).await;

    let payload = b"\x00\x01noise ciphertext\xff";
    send_binary(&mut a, forward_frame(1, &b.peer, payload)).await;
    assert_eq!(
        recv_binary(&mut b).await,
        forward_frame(1, &a.peer, payload)
    );

    // An empty payload is still a frame.
    send_binary(&mut a, forward_frame(1, &c.peer, &[])).await;
    assert_eq!(recv_binary(&mut c).await, forward_frame(1, &a.peer, &[]));

    expect_silence(&mut a).await;
    expect_silence(&mut b).await;
    expect_silence(&mut d).await;
}

#[tokio::test]
async fn ping_is_answered_before_and_after_join() {
    let relay = Relay::start(test_config()).await;
    let mut a = relay.connect().await;
    send_json(&mut a, json!({ "t": "ping" })).await;
    assert_eq!(recv_json(&mut a).await, json!({ "t": "pong" }));
    join(&mut a, &room_id(1)).await;
    send_json(&mut a, json!({ "t": "ping" })).await;
    assert_eq!(recv_json(&mut a).await, json!({ "t": "pong" }));
}

#[tokio::test]
async fn publishing_before_join_is_refused_without_closing() {
    let relay = Relay::start(test_config()).await;
    let mut a = relay.connect().await;

    send_json(&mut a, json!({ "t": "pub", "to": "*", "data": "" })).await;
    expect_error(&mut a, "not_joined").await;
    // A binary frame names its room: one the connection isn't in drops it without an
    // error, so the next answer is the one to the leave.
    send_binary(&mut a, forward_frame(1, &peer_id(2), &[])).await;
    send_json(&mut a, json!({ "t": "leave" })).await;
    expect_error(&mut a, "not_joined").await;

    assert!(join(&mut a, &room_id(1)).await.is_empty());
}

#[tokio::test]
async fn malformed_frames_close_the_connection() {
    // Each malformed frame needs a fresh connection; isolate parsing from admission.
    let relay = Relay::start(Config {
        ws_requests_per_sec: 100,
        ..test_config()
    })
    .await;
    // A room the connection is in.
    let joined_first = [json!({ "t": "join", "room": room_id(1) })];
    let fresh = [
        json!({ "t": "join", "room": room_id(1).to_uppercase().replace('0', "A") }),
        json!({ "t": "join", "room": "1234" }),
        json!({ "t": "join" }),
        json!({ "type": "ping" }),
        json!({ "t": 7 }),
        json!(["ping"]),
    ];
    // Malformed is malformed whether the connection is in a room or not.
    let either = [
        json!({ "t": "pub", "to": "*", "data": "not base64!" }),
        json!({ "t": "pub", "to": "*" }),
        json!({ "t": "pub", "to": "everyone", "data": "" }),
        json!({ "t": "pub", "to": peer_id(2).to_uppercase().replace('0', "A"), "data": "" }),
    ];
    let cases = fresh
        .iter()
        .chain(&either)
        .map(|frame| (frame, false))
        .chain(
            joined_first
                .iter()
                .chain(&either)
                .map(|frame| (frame, true)),
        );
    for (frame, joined) in cases {
        let mut socket = relay.connect().await;
        if joined {
            join(&mut socket, &room_id(1)).await;
        }
        send_json(&mut socket, frame.clone()).await;
        expect_error(&mut socket, "bad_frame").await;
        assert_eq!(expect_closed(&mut socket).await, Some(1008), "{frame}");
    }

    let mut socket = relay.connect().await;
    send_text(&mut socket, "{not json").await;
    expect_error(&mut socket, "bad_frame").await;
    assert_eq!(expect_closed(&mut socket).await, Some(1008));

    // A forward frame shorter than its header.
    let mut socket = relay.connect().await;
    join(&mut socket, &room_id(1)).await;
    let mut short = forward_frame(1, &peer_id(2), &[]);
    short.pop();
    send_binary(&mut socket, short).await;
    expect_error(&mut socket, "bad_frame").await;
    assert_eq!(expect_closed(&mut socket).await, Some(1008));

    // A binary frame without a type.
    let mut socket = relay.connect().await;
    send_binary(&mut socket, Vec::new()).await;
    expect_error(&mut socket, "bad_frame").await;
    assert_eq!(expect_closed(&mut socket).await, Some(1008));
}

#[tokio::test]
async fn unknown_types_are_answered_without_closing() {
    let relay = Relay::start(test_config()).await;
    let mut a = relay.connect().await;
    let mut b = relay.connect().await;
    join(&mut a, &room_id(1)).await;
    join(&mut b, &room_id(1)).await;
    assert_eq!(recv_json(&mut a).await, presence(1, &b.peer, "join"));

    for frame in [
        json!({ "t": "subscribe" }),
        json!({ "t": "hello", "v": 2, "room": room_id(1) }),
        json!({ "t": "Ping" }),
    ] {
        send_json(&mut a, frame).await;
        expect_error(&mut a, "unknown_type").await;
    }
    // Any first byte but the forward tag, such as the old `0x02 | peer id | payload`.
    for tag in [0x00, 0x01, 0x02, 0x04, 0xff] {
        let mut frame = forward_frame(1, &b.peer, b"payload");
        frame[0] = tag;
        send_binary(&mut a, frame).await;
        expect_error(&mut a, "unknown_type").await;
    }
    send_binary(&mut a, vec![0x7f]).await;
    expect_error(&mut a, "unknown_type").await;
    expect_silence(&mut b).await;

    // The connection goes on.
    send_binary(&mut a, forward_frame(1, &b.peer, b"still here")).await;
    assert_eq!(
        recv_binary(&mut b).await,
        forward_frame(1, &a.peer, b"still here")
    );
    assert_eq!(
        relay
            .metrics()
            .await
            .metric("keyguard_relay_protocol_errors_total{reason=\"unknown_type\"}"),
        9.0
    );
}

#[tokio::test]
async fn full_room_refuses_more_peers() {
    let relay = Relay::start(Config {
        max_room_peers: 2,
        ..test_config()
    })
    .await;
    let mut a = relay.connect().await;
    let mut b = relay.connect().await;
    let mut c = relay.connect().await;
    join(&mut a, &room_id(1)).await;
    join(&mut b, &room_id(1)).await;

    send_join(&mut c, &room_id(1)).await;
    expect_join_error(&mut c, "room_full", &room_id(1)).await;
    // The refused peer was never announced and can join elsewhere.
    assert_eq!(recv_json(&mut a).await, presence(1, &b.peer, "join"));
    expect_silence(&mut a).await;
    assert!(join(&mut c, &room_id(2)).await.is_empty());
}

#[tokio::test]
async fn rooms_hold_four_peers_by_default() {
    let relay = Relay::start(test_config()).await;
    let mut clients = Vec::new();
    for _ in 0..4 {
        let mut client = relay.connect().await;
        join(&mut client, &room_id(1)).await;
        clients.push(client);
    }
    let mut fifth = relay.connect().await;
    send_join(&mut fifth, &room_id(1)).await;
    expect_join_error(&mut fifth, "room_full", &room_id(1)).await;
}

#[tokio::test]
async fn metrics_count_rooms_and_connections() {
    let relay = Relay::start(test_config()).await;
    relay.wait_for_metrics(0, 0).await;

    let mut a = relay.connect().await;
    let mut b = relay.connect().await;
    relay.wait_for_metrics(0, 2).await;
    join(&mut a, &room_id(1)).await;
    join(&mut b, &room_id(1)).await;
    relay.wait_for_metrics(1, 2).await;

    send_json(&mut a, json!({ "t": "leave" })).await;
    drop(b);
    // The empty room is dropped.
    relay.wait_for_metrics(0, 1).await;
    drop(a);
    relay.wait_for_metrics(0, 0).await;
}
