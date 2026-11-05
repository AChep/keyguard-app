//! Rooms, presence, fan-out, relay-forward frames and the health endpoint.

mod common;

use common::*;
use keyguard_relay::Config;
use serde_json::json;

#[tokio::test]
async fn join_lists_peers_and_announces_presence() {
    let relay = Relay::start(test_config()).await;
    let mut a = relay.connect().await;
    let mut b = relay.connect().await;
    let mut c = relay.connect().await;

    assert!(join(&mut a, &room_id(1), &peer_id(1)).await.is_empty());
    assert_eq!(join(&mut b, &room_id(1), &peer_id(2)).await, [peer_id(1)]);
    assert_eq!(recv_json(&mut a).await, presence(2, "join"));
    assert_eq!(
        join(&mut c, &room_id(1), &peer_id(3)).await,
        [peer_id(1), peer_id(2)]
    );
    assert_eq!(recv_json(&mut a).await, presence(3, "join"));
    assert_eq!(recv_json(&mut b).await, presence(3, "join"));

    // An explicit leave, then a fresh join on the same connection.
    send_json(&mut b, json!({ "t": "leave" })).await;
    assert_eq!(recv_json(&mut a).await, presence(2, "leave"));
    assert_eq!(recv_json(&mut c).await, presence(2, "leave"));
    assert_eq!(
        join(&mut b, &room_id(1), &peer_id(4)).await,
        [peer_id(1), peer_id(3)]
    );
    assert_eq!(recv_json(&mut a).await, presence(4, "join"));
    assert_eq!(recv_json(&mut c).await, presence(4, "join"));

    // A dropped connection leaves too.
    drop(b);
    assert_eq!(recv_json(&mut a).await, presence(4, "leave"));
    assert_eq!(recv_json(&mut c).await, presence(4, "leave"));
    expect_silence(&mut a).await;
    expect_silence(&mut c).await;
}

#[tokio::test]
async fn joined_carries_a_ticket_per_connection() {
    let relay = Relay::start(test_config()).await;
    let mut a = relay.connect().await;
    let mut b = relay.connect().await;
    let first = join_frame(&mut a, &room_id(1), &peer_id(1)).await;
    let ticket = first["ticket"].as_str().unwrap();
    assert_eq!(ticket.len(), 32);
    assert!(
        ticket
            .bytes()
            .all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f')),
        "{ticket}"
    );
    assert_ne!(join_ticket(&mut b, &room_id(1), &peer_id(2)).await, ticket);
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
    join(&mut a, &room_id(1), &peer_id(1)).await;
    join(&mut b, &room_id(1), &peer_id(2)).await;
    join(&mut c, &room_id(2), &peer_id(3)).await;
    assert_eq!(recv_json(&mut a).await, presence(2, "join"));

    // Pipeline a burst without reading b, then switch its room while frames
    // may still be queued. Frames sent before the new `joined` are valid.
    for _ in 0..32 {
        send_json(
            &mut a,
            json!({ "t": "pub", "to": peer_id(2), "data": "b2xk" }),
        )
        .await;
        send_binary(&mut a, forward_frame(2, b"old")).await;
    }
    send_json(&mut b, json!({ "t": "leave" })).await;
    send_join(&mut b, &room_id(2), &peer_id(4)).await;
    loop {
        match next_frame(&mut b).await {
            Frame::Json(frame) if frame["t"] == "joined" => {
                assert_eq!(frame["peers"], json!([peer_id(3)]));
                break;
            }
            Frame::Json(frame) => assert_eq!(frame["t"], "msg", "{frame}"),
            Frame::Binary(frame) => assert_eq!(frame, forward_frame(1, b"old")),
            frame => panic!("expected a frame before joining the new room, got {frame:?}"),
        }
    }
    assert_eq!(recv_json(&mut c).await, presence(4, "join"));

    send_json(
        &mut c,
        json!({ "t": "pub", "to": peer_id(4), "data": "bmV3" }),
    )
    .await;
    send_binary(&mut c, forward_frame(4, b"new")).await;
    assert_eq!(
        recv_json(&mut b).await,
        json!({ "t": "msg", "from": peer_id(3), "data": "bmV3" })
    );
    assert_eq!(recv_binary(&mut b).await, forward_frame(3, b"new"));
}

/// Three peers in room 1 and one in room 2, with all presence frames consumed.
async fn three_peers_and_a_stranger(relay: &Relay) -> [Socket; 4] {
    let mut a = relay.connect().await;
    let mut b = relay.connect().await;
    let mut c = relay.connect().await;
    let mut d = relay.connect().await;
    join(&mut a, &room_id(1), &peer_id(1)).await;
    join(&mut b, &room_id(1), &peer_id(2)).await;
    join(&mut c, &room_id(1), &peer_id(3)).await;
    join(&mut d, &room_id(2), &peer_id(4)).await;
    assert_eq!(recv_json(&mut a).await, presence(2, "join"));
    assert_eq!(recv_json(&mut a).await, presence(3, "join"));
    assert_eq!(recv_json(&mut b).await, presence(3, "join"));
    [a, b, c, d]
}

#[tokio::test]
async fn unicast_reaches_only_the_target() {
    let relay = Relay::start(test_config()).await;
    let [mut a, mut b, mut c, mut d] = three_peers_and_a_stranger(&relay).await;

    send_json(
        &mut a,
        json!({ "t": "pub", "to": peer_id(2), "data": "aGVsbG8=" }),
    )
    .await;
    assert_eq!(
        recv_json(&mut b).await,
        json!({ "t": "msg", "from": peer_id(1), "data": "aGVsbG8=" })
    );
    for socket in [&mut a, &mut c, &mut d] {
        expect_silence(socket).await;
    }

    // Addressing yourself or someone outside the room delivers nothing.
    send_json(&mut a, json!({ "t": "pub", "to": peer_id(1), "data": "" })).await;
    send_json(&mut a, json!({ "t": "pub", "to": peer_id(4), "data": "" })).await;
    for socket in [&mut a, &mut b, &mut c, &mut d] {
        expect_silence(socket).await;
    }
}

#[tokio::test]
async fn broadcast_reaches_everyone_else_in_the_room() {
    let relay = Relay::start(test_config()).await;
    let [mut a, mut b, mut c, mut d] = three_peers_and_a_stranger(&relay).await;

    send_json(&mut a, json!({ "t": "pub", "to": "*", "data": "AAEC" })).await;
    let expected = json!({ "t": "msg", "from": peer_id(1), "data": "AAEC" });
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
    send_binary(&mut a, forward_frame(2, payload)).await;
    assert_eq!(recv_binary(&mut b).await, forward_frame(1, payload));

    // An empty payload is still a frame.
    send_binary(&mut a, forward_frame(3, &[])).await;
    assert_eq!(recv_binary(&mut c).await, forward_frame(1, &[]));

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
    join(&mut a, &room_id(1), &peer_id(1)).await;
    send_json(&mut a, json!({ "t": "ping" })).await;
    assert_eq!(recv_json(&mut a).await, json!({ "t": "pong" }));
}

#[tokio::test]
async fn publishing_before_join_is_refused_without_closing() {
    let relay = Relay::start(test_config()).await;
    let mut a = relay.connect().await;

    send_json(&mut a, json!({ "t": "pub", "to": "*", "data": "" })).await;
    expect_error(&mut a, "not_joined").await;
    send_binary(&mut a, forward_frame(2, &[])).await;
    expect_error(&mut a, "not_joined").await;
    send_json(&mut a, json!({ "t": "leave" })).await;
    expect_error(&mut a, "not_joined").await;

    assert!(join(&mut a, &room_id(1), &peer_id(1)).await.is_empty());
}

#[tokio::test]
async fn malformed_frames_close_the_connection() {
    let relay = Relay::start(test_config()).await;
    let joined_first = [json!({ "t": "join", "room": room_id(2), "peer": peer_id(1) })];
    let fresh = [
        json!({ "t": "join", "room": room_id(1).to_uppercase().replace('0', "A"), "peer": peer_id(1) }),
        json!({ "t": "join", "room": room_id(1), "peer": "1234" }),
        json!({ "t": "join", "room": room_id(1) }),
        json!({ "t": "subscribe" }),
        json!({ "type": "ping" }),
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
            join(&mut socket, &room_id(1), &peer_id(1)).await;
        }
        send_json(&mut socket, frame.clone()).await;
        expect_error(&mut socket, "bad_frame").await;
        assert_eq!(expect_closed(&mut socket).await, Some(1008), "{frame}");
    }

    let mut socket = relay.connect().await;
    send_text(&mut socket, "{not json").await;
    expect_error(&mut socket, "bad_frame").await;
    assert_eq!(expect_closed(&mut socket).await, Some(1008));

    let mut socket = relay.connect().await;
    join(&mut socket, &room_id(1), &peer_id(1)).await;
    let mut wrong_tag = forward_frame(2, &[]);
    wrong_tag[0] = 0x01;
    send_binary(&mut socket, wrong_tag).await;
    expect_error(&mut socket, "bad_frame").await;
    assert_eq!(expect_closed(&mut socket).await, Some(1008));

    let mut socket = relay.connect().await;
    send_binary(&mut socket, vec![0x02, 1, 2, 3]).await;
    expect_error(&mut socket, "bad_frame").await;
    assert_eq!(expect_closed(&mut socket).await, Some(1008));
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
    join(&mut a, &room_id(1), &peer_id(1)).await;
    join(&mut b, &room_id(1), &peer_id(2)).await;

    send_join(&mut c, &room_id(1), &peer_id(3)).await;
    expect_error(&mut c, "room_full").await;
    // The refused peer was never announced and can join elsewhere.
    assert_eq!(recv_json(&mut a).await, presence(2, "join"));
    expect_silence(&mut a).await;
    assert!(join(&mut c, &room_id(2), &peer_id(3)).await.is_empty());
}

#[tokio::test]
async fn a_peer_id_is_unique_within_a_room() {
    let relay = Relay::start(test_config()).await;
    let mut a = relay.connect().await;
    let mut b = relay.connect().await;
    join(&mut a, &room_id(1), &peer_id(1)).await;

    send_join(&mut b, &room_id(1), &peer_id(1)).await;
    expect_error(&mut b, "peer_taken").await;
    assert_eq!(join(&mut b, &room_id(1), &peer_id(2)).await, [peer_id(1)]);
}

#[tokio::test]
async fn metrics_count_rooms_and_connections() {
    let relay = Relay::start(test_config()).await;
    relay.wait_for_metrics(0, 0).await;

    let mut a = relay.connect().await;
    let mut b = relay.connect().await;
    relay.wait_for_metrics(0, 2).await;
    join(&mut a, &room_id(1), &peer_id(1)).await;
    join(&mut b, &room_id(1), &peer_id(2)).await;
    relay.wait_for_metrics(1, 2).await;

    send_json(&mut a, json!({ "t": "leave" })).await;
    drop(b);
    // The empty room is dropped.
    relay.wait_for_metrics(0, 1).await;
    drop(a);
    relay.wait_for_metrics(0, 0).await;
}
