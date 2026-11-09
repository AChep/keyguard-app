//! Two in-process peers on 127.0.0.1 with no STUN, signaled directly through
//! the public API.

use std::{
    thread,
    time::{Duration, Instant},
};

use keyguard_webrtc_core::{
    BUFFERED_LOW_THRESHOLD, ConnectionState, Event, MAX_MESSAGE_BYTES, SDP_KIND_ANSWER,
    SDP_KIND_OFFER, WebrtcError, channel_buffered_amount, channel_send, peer_add_ice_candidate,
    peer_close, peer_create_answer, peer_create_channel, peer_create_offer, peer_new,
    peer_next_event, peer_set_remote, runtime_new, runtime_shutdown,
};

const LOOPBACK: &str = r#"{"bindAddresses":["127.0.0.1:0"]}"#;
const DEADLINE: Duration = Duration::from_secs(20);
const POLL: Duration = Duration::from_millis(20);
/// Sequence number of the message that ends a receiver.
const LAST: u32 = u32::MAX;

struct Pair {
    runtime: u64,
    offerer: u64,
    answerer: u64,
    /// Events other than candidates seen while connecting.
    offerer_events: Vec<Event>,
    answerer_events: Vec<Event>,
}

/// Forwards candidates until both sides report an open channel.
fn connect() -> Pair {
    let runtime = runtime_new().expect("a runtime starts");
    let offerer = peer_new(runtime, LOOPBACK).expect("the offerer builds");
    let answerer = peer_new(runtime, LOOPBACK).expect("the answerer builds");
    peer_create_channel(offerer, "keyguard").expect("the offerer creates the channel");
    let offer = peer_create_offer(offerer).expect("the offerer creates an offer");
    peer_set_remote(answerer, SDP_KIND_OFFER, &offer).expect("the answerer takes the offer");
    let answer = peer_create_answer(answerer).expect("the answerer creates an answer");
    peer_set_remote(offerer, SDP_KIND_ANSWER, &answer).expect("the offerer takes the answer");

    let mut pair = Pair {
        runtime,
        offerer,
        answerer,
        offerer_events: Vec::new(),
        answerer_events: Vec::new(),
    };
    let started = Instant::now();
    while !(pair.offerer_events.contains(&Event::ChannelOpen)
        && pair.answerer_events.contains(&Event::ChannelOpen))
    {
        assert!(started.elapsed() < DEADLINE, "the channel did not open");
        for (from, to) in [(offerer, answerer), (answerer, offerer)] {
            let Some(event) = peer_next_event(from, POLL).expect("polling succeeds") else {
                continue;
            };
            match event {
                Event::IceCandidate(json) => {
                    peer_add_ice_candidate(to, &json).expect("the candidate is accepted");
                }
                event if from == offerer => pair.offerer_events.push(event),
                event => pair.answerer_events.push(event),
            }
        }
    }
    pair
}

/// Waits for an event matching `accept`, dropping the others.
fn wait_for(peer: u64, mut accept: impl FnMut(&Event) -> bool) -> Event {
    let started = Instant::now();
    loop {
        assert!(started.elapsed() < DEADLINE, "the event did not arrive");
        if let Some(event) = peer_next_event(peer, POLL).expect("polling succeeds")
            && accept(&event)
        {
            return event;
        }
    }
}

fn message(sequence: u32, size: usize) -> Vec<u8> {
    let mut message = vec![(sequence % 251) as u8; size.max(4)];
    message[..4].copy_from_slice(&sequence.to_le_bytes());
    message
}

/// Sends, waiting for `BufferedLow` whenever the channel is busy.
fn send(peer: u64, data: &[u8]) {
    loop {
        match channel_send(peer, data) {
            Ok(()) => return,
            Err(WebrtcError::Busy) => {
                wait_for(peer, |event| *event == Event::BufferedLow);
            }
            Err(error) => panic!("send failed: {error:?}"),
        }
    }
}

/// Collects `(sequence, size)` of every message until [`LAST`].
fn receive_until_last(peer: u64) -> thread::JoinHandle<Vec<(u32, usize)>> {
    thread::spawn(move || {
        let mut received = Vec::new();
        let started = Instant::now();
        loop {
            assert!(
                started.elapsed() < DEADLINE * 3,
                "the last message did not arrive"
            );
            let Some(Event::ChannelMessage(data)) =
                peer_next_event(peer, POLL).expect("polling succeeds")
            else {
                continue;
            };
            let sequence = u32::from_le_bytes(data[..4].try_into().expect("4 bytes"));
            assert_eq!(
                data,
                message(sequence, data.len()),
                "message {sequence} was corrupted"
            );
            if sequence == LAST {
                return received;
            }
            received.push((sequence, data.len()));
        }
    })
}

#[test]
fn a_loopback_channel_delivers_ordered_messages_and_signals_buffered_low() {
    let pair = connect();
    assert!(
        pair.offerer_events
            .contains(&Event::ConnectionState(ConnectionState::Connected)),
        "{:?}",
        pair.offerer_events
    );
    assert!(pair.answerer_events.contains(&Event::IceGatheringComplete));

    // Raised on connect from a task of its own: seen while connecting, or soon after,
    // before anything below polls the events away.
    let selected = pair
        .offerer_events
        .iter()
        .find(|event| matches!(event, Event::SelectedPair(_)))
        .cloned()
        .unwrap_or_else(|| {
            wait_for(pair.offerer, |event| {
                matches!(event, Event::SelectedPair(_))
            })
        });
    assert_eq!(
        selected,
        Event::SelectedPair(r#"{"localType":"host","remoteType":"host","protocol":"udp"}"#.into())
    );

    let receiver = receive_until_last(pair.answerer);
    let mut expected = Vec::new();

    // 100 ordered messages, one of them the largest allowed.
    for sequence in 0..100 {
        let size = if sequence == 50 {
            MAX_MESSAGE_BYTES
        } else {
            16 + sequence as usize
        };
        send(pair.offerer, &message(sequence, size));
        expected.push((sequence, size));
    }

    // Fill the buffer past the limit, then wait for it to drain.
    while peer_next_event(pair.offerer, Duration::ZERO)
        .expect("polling succeeds")
        .is_some()
    {}
    let mut sequence = 100;
    let mut busy = false;
    for _ in 0..1000 {
        match channel_send(pair.offerer, &message(sequence, MAX_MESSAGE_BYTES)) {
            Ok(()) => {
                expected.push((sequence, MAX_MESSAGE_BYTES));
                sequence += 1;
            }
            Err(WebrtcError::Busy) => {
                busy = true;
                break;
            }
            Err(error) => panic!("send failed: {error:?}"),
        }
    }
    // The receiver drains meanwhile: a later amount says nothing of the refused send.
    assert!(busy, "the channel never reported busy");
    // A BufferedLow raised while filling may still be queued, so wait for one
    // that leaves the amount at or below the threshold.
    wait_for(pair.offerer, |event| {
        *event == Event::BufferedLow
            && channel_buffered_amount(pair.offerer).expect("the amount is readable")
                <= BUFFERED_LOW_THRESHOLD as usize
    });
    send(pair.offerer, &message(sequence, 32));
    expected.push((sequence, 32));
    send(pair.offerer, &message(LAST, 4));

    let received = receiver.join().expect("the receiver must not panic");
    assert_eq!(received, expected);

    peer_close(pair.offerer).expect("the offerer closes");
    wait_for(pair.answerer, |event| *event == Event::ChannelClose);
    peer_close(pair.answerer).expect("the answerer closes");
    runtime_shutdown(pair.runtime).expect("the runtime shuts down");
}

#[test]
fn close_from_another_thread_wakes_a_blocked_poll() {
    let runtime = runtime_new().expect("a runtime starts");
    let peer = peer_new(runtime, LOOPBACK).expect("the peer builds");
    let poller = thread::spawn(move || {
        let started = Instant::now();
        let result = peer_next_event(peer, Duration::from_secs(60));
        (result, started.elapsed())
    });
    thread::sleep(Duration::from_millis(200));
    peer_close(peer).expect("the peer closes");
    let (result, elapsed) = poller.join().expect("the poller must not panic");
    assert_eq!(result, Err(WebrtcError::Closed));
    assert!(elapsed < Duration::from_secs(5), "woken after {elapsed:?}");

    assert_eq!(peer_close(peer), Ok(()), "close is idempotent");
    assert_eq!(
        peer_next_event(peer, Duration::ZERO),
        Err(WebrtcError::Closed)
    );
    assert_eq!(channel_send(peer, b"x"), Err(WebrtcError::Closed));
    runtime_shutdown(runtime).expect("the runtime shuts down");
}

#[test]
fn shutdown_with_live_peers_completes_within_the_timeout() {
    let pair = connect();
    let poller = thread::spawn(move || {
        let started = Instant::now();
        loop {
            match peer_next_event(pair.answerer, Duration::from_secs(60)) {
                // Events of the connection may still be queued.
                Ok(_) if started.elapsed() < DEADLINE => continue,
                result => return result,
            }
        }
    });
    thread::sleep(Duration::from_millis(200));

    let started = Instant::now();
    runtime_shutdown(pair.runtime).expect("the runtime shuts down");
    let elapsed = started.elapsed();
    assert!(
        elapsed < Duration::from_secs(5),
        "shutdown took {elapsed:?}"
    );

    assert_eq!(
        poller.join().expect("the poller must not panic"),
        Err(WebrtcError::Closed)
    );
    for peer in [pair.offerer, pair.answerer] {
        assert_eq!(channel_send(peer, b"x"), Err(WebrtcError::Closed));
        assert_eq!(peer_create_offer(peer), Err(WebrtcError::Closed));
        assert_eq!(peer_close(peer), Ok(()));
    }
    assert_eq!(
        runtime_shutdown(pair.runtime),
        Ok(()),
        "shutdown is idempotent"
    );
    assert_eq!(
        peer_new(pair.runtime, LOOPBACK).err(),
        Some(WebrtcError::Closed)
    );
}

#[test]
fn invalid_handles_and_arguments_are_reported_with_stable_codes() {
    let runtime = runtime_new().expect("a runtime starts");
    let peer = peer_new(runtime, LOOPBACK).expect("the peer builds");

    // A runtime handle never names a peer, and the other way round.
    assert_eq!(peer_close(runtime), Err(WebrtcError::InvalidHandle));
    assert_eq!(
        peer_create_offer(runtime).err(),
        Some(WebrtcError::InvalidHandle)
    );
    assert_eq!(runtime_shutdown(peer), Err(WebrtcError::InvalidHandle));
    assert_eq!(
        peer_new(peer, LOOPBACK).err(),
        Some(WebrtcError::InvalidHandle)
    );
    for invalid in [0, 1, u64::MAX] {
        assert_eq!(peer_close(invalid), Err(WebrtcError::InvalidHandle));
        assert_eq!(runtime_shutdown(invalid), Err(WebrtcError::InvalidHandle));
        assert_eq!(
            peer_next_event(invalid, Duration::ZERO),
            Err(WebrtcError::InvalidHandle)
        );
    }

    assert_eq!(
        peer_new(runtime, "{").err(),
        Some(WebrtcError::InvalidArgument)
    );
    assert_eq!(
        peer_new(runtime, r#"{"iceTransportPolicy":"some"}"#).err(),
        Some(WebrtcError::InvalidArgument)
    );
    assert_eq!(
        peer_set_remote(peer, 2, "v=0"),
        Err(WebrtcError::InvalidArgument)
    );
    assert_eq!(
        peer_set_remote(peer, SDP_KIND_OFFER, "not an sdp"),
        Err(WebrtcError::InvalidArgument)
    );
    assert_eq!(
        peer_add_ice_candidate(peer, "not json"),
        Err(WebrtcError::InvalidArgument)
    );
    assert_eq!(
        peer_add_ice_candidate(peer, r#"{"candidate":"candidate:garbage"}"#),
        Err(WebrtcError::InvalidArgument)
    );
    assert_eq!(
        peer_add_ice_candidate(peer, r#"{"candidate":""}"#),
        Ok(()),
        "end of candidates is ignored"
    );
    assert_eq!(
        peer_add_ice_candidate(
            peer,
            r#"{"candidate":"candidate:1 1 udp 2130706431 127.0.0.1 9 typ host","sdpMid":"0","sdpMLineIndex":0}"#,
        ),
        Ok(()),
        "a candidate before the remote description is held"
    );
    assert_eq!(channel_send(peer, b"x"), Err(WebrtcError::InvalidState));
    assert_eq!(
        channel_buffered_amount(peer),
        Err(WebrtcError::InvalidState)
    );
    assert!(peer_create_answer(peer).is_err(), "no remote offer is set");

    peer_create_channel(peer, "keyguard").expect("the first channel is created");
    assert_eq!(
        peer_create_channel(peer, "again"),
        Err(WebrtcError::InvalidState)
    );
    assert_eq!(
        peer_create_channel(peer, &"l".repeat(256)),
        Err(WebrtcError::InputTooLong)
    );
    assert_eq!(
        channel_send(peer, &vec![0; MAX_MESSAGE_BYTES + 1]),
        Err(WebrtcError::InputTooLong)
    );
    assert_eq!(
        channel_send(peer, b"x"),
        Err(WebrtcError::InvalidState),
        "the channel is not open"
    );

    runtime_shutdown(runtime).expect("the runtime shuts down");
}
