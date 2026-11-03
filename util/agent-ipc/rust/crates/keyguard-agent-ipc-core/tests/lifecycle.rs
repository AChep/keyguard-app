//! The lifecycle of listeners and connections on every platform: binding,
//! accepting, the two lanes, end of stream, local close, delivery before close,
//! the peer snapshot of a child process, and the bridge.

mod support;

use std::{
    io::{Read, Write},
    process::{Child, Command, Stdio},
    sync::{Arc, mpsc},
    thread,
    time::{Duration, Instant},
};

use keyguard_agent_ipc_core::{Error, Failure, Listener, Readiness, bridge};
use support::{MIB, Parent, pair, pattern, read_exact, within};

const BOUND: Duration = Duration::from_secs(5);

fn kind<T>(result: Result<T, Failure>) -> Option<Error> {
    result.err().map(Failure::kind)
}

#[test]
fn connectable_on_return() {
    let parent = Parent::new();
    let listener = parent.bind();
    // One attempt, before any accept runs.
    let client = support::try_connect(listener.endpoint()).unwrap();
    let connection = listener.accept().unwrap();
    assert_eq!(connection.peer_pid(), Ok(std::process::id()));
    drop(client);
}

#[test]
fn taken_exact_name_is_permission() {
    let parent = Parent::new();
    let name = support::exact_name();
    let first = Listener::bind(parent.location(Some(&name))).unwrap();
    let failure = Listener::bind(parent.location(Some(&name))).err().unwrap();
    assert_eq!(failure.kind(), Error::Permission);
    assert_eq!(failure.operation(), "listen");
    // The first listener keeps serving.
    let (connection, mut client) = pair(&first);
    client.write_all(b"k").unwrap();
    let mut byte = [0];
    read_exact(&connection, &mut byte);
    assert_eq!(byte, *b"k");
}

#[test]
fn close_before_start_is_closed() {
    let parent = Parent::new();
    let listener = parent.bind();
    let (connection, _client) = pair(&listener);
    listener.close();
    listener.close();
    assert_eq!(kind(listener.accept()), Some(Error::Closed));
    connection.close();
    connection.close();
    assert_eq!(kind(connection.read(&mut [0; 4])), Some(Error::Closed));
    assert_eq!(kind(connection.write_all(b"x")), Some(Error::Closed));
    assert_eq!(kind(connection.await_input()), Some(Error::Closed));
}

#[test]
fn closed_on_local_close_and_the_peer_sees_end_of_stream() {
    let parent = Parent::new();
    let listener = parent.bind();
    let (connection, mut client) = pair(&listener);
    connection.close();
    assert_eq!(kind(connection.read(&mut [0; 4])), Some(Error::Closed));
    assert_eq!(kind(connection.await_input()), Some(Error::Closed));
    drop(connection);
    let mut rest = Vec::new();
    client.read_to_end(&mut rest).unwrap();
    assert!(rest.is_empty());
}

/// Input left unread never turns the end of the stream into a reset (Linux does
/// unless it is discarded): a client rejected after it wrote its first request
/// reads any reply, then the end of the stream. Closed or only dropped.
#[test]
fn unread_input_still_ends_the_peer_stream() {
    within(BOUND, || {
        let parent = Parent::new();
        let listener = parent.bind();
        for (reply, close) in [(&b""[..], true), (&b""[..], false), (&b"denied"[..], true)] {
            let (connection, mut client) = pair(&listener);
            client.write_all(b"request").unwrap();
            connection.write_all(reply).unwrap();
            if close {
                connection.close();
            }
            drop(connection);
            let mut rest = Vec::new();
            client
                .read_to_end(&mut rest)
                .unwrap_or_else(|error| panic!("reply {reply:?}, close {close}: {error}"));
            assert_eq!(rest, reply);
        }
    });
}

/// Runs two calls on one lane at once. Whichever comes second is `Busy`; closing
/// ends the other with `Closed`.
fn one_is_busy<A: Send + 'static, B: Send + 'static>(
    first: impl FnOnce() -> Result<A, Failure> + Send + 'static,
    second: impl FnOnce() -> Result<B, Failure> + Send + 'static,
    close: impl FnOnce(),
) {
    let (sender, receiver) = mpsc::channel();
    let other = sender.clone();
    thread::spawn(move || other.send(kind(first())));
    thread::spawn(move || sender.send(kind(second())));
    assert_eq!(receiver.recv_timeout(BOUND).unwrap(), Some(Error::Busy));
    close();
    assert_eq!(receiver.recv_timeout(BOUND).unwrap(), Some(Error::Closed));
}

#[test]
fn busy_per_lane() {
    let parent = Parent::new();
    let listener = Arc::new(parent.bind());
    let (first, second) = (Arc::clone(&listener), Arc::clone(&listener));
    one_is_busy(
        move || first.accept(),
        move || second.accept(),
        || {
            listener.close();
        },
    );

    let listener = parent.bind();
    let lanes: [(&str, &str); 4] = [
        ("read", "read"),
        ("read", "await_input"),
        ("await_input", "await_input"),
        ("write", "write"),
    ];
    for (first, second) in lanes {
        // The client neither writes nor reads, so every call blocks.
        let (connection, _client) = pair(&listener);
        let connection = Arc::new(connection);
        let call = |name: &'static str| {
            let connection = Arc::clone(&connection);
            move || match name {
                "read" => connection.read(&mut [0; 16]).map(drop),
                "await_input" => connection.await_input().map(drop),
                _ => connection.write_all(&vec![0; 8 * MIB]),
            }
        };
        one_is_busy(call(first), call(second), || connection.close());
    }
}

#[test]
fn zero_length_rules() {
    let parent = Parent::new();
    let listener = parent.bind();
    let (connection, mut client) = pair(&listener);
    let failure = connection.read(&mut []).err().unwrap();
    assert_eq!(failure.kind(), Error::InvalidArgument);
    assert_eq!(failure.to_string(), "read: empty_buffer");
    assert_eq!(connection.write_all(&[]), Ok(()));
    // A client's empty write is not the end of the stream.
    assert_eq!(client.write(&[]).unwrap(), 0);
    client.write_all(b"z").unwrap();
    let mut byte = [0];
    assert_eq!(connection.read(&mut byte), Ok(1));
    assert_eq!(byte, *b"z");
    // Empty data never reaches the system, not even after close.
    connection.close();
    assert_eq!(connection.write_all(&[]), Ok(()));
}

/// D-04: a pending observer never blocks a write on the same connection.
#[test]
fn write_1mib_while_await_input_pending() {
    within(BOUND, || {
        let parent = Parent::new();
        let listener = parent.bind();
        let (connection, mut client) = pair(&listener);
        let connection = Arc::new(connection);
        let observer = {
            let connection = Arc::clone(&connection);
            thread::spawn(move || connection.await_input())
        };
        // Let the observer pend before the write starts.
        thread::sleep(Duration::from_millis(50));
        let reader = thread::spawn(move || {
            let mut data = vec![0; MIB];
            client.read_exact(&mut data).unwrap();
            client.write_all(b"!").unwrap();
            (client, data)
        });
        let payload = pattern(MIB);
        connection.write_all(&payload).unwrap();
        let (client, data) = reader.join().unwrap();
        assert!(data == payload);
        assert_eq!(observer.join().unwrap(), Ok(Readiness::Input));
        let mut byte = [0];
        read_exact(&connection, &mut byte);
        assert_eq!(byte, *b"!");
        drop(client);
    });
}

#[test]
fn writes_complete_while_a_read_is_pending() {
    within(BOUND, || {
        let parent = Parent::new();
        let listener = parent.bind();
        let (connection, mut client) = pair(&listener);
        let connection = Arc::new(connection);
        let reader = {
            let connection = Arc::clone(&connection);
            thread::spawn(move || {
                let mut byte = [0];
                connection.read(&mut byte).map(|count| (count, byte))
            })
        };
        thread::sleep(Duration::from_millis(50));
        connection.write_all(b"ping").unwrap();
        let mut ping = [0; 4];
        client.read_exact(&mut ping).unwrap();
        assert_eq!(ping, *b"ping");
        client.write_all(b"p").unwrap();
        assert_eq!(reader.join().unwrap(), Ok((1, *b"p")));
    });
}

#[test]
fn observer_input_then_read_returns_byte() {
    within(BOUND, || {
        let parent = Parent::new();
        let listener = parent.bind();
        let (connection, mut client) = pair(&listener);
        let connection = Arc::new(connection);
        let observer = {
            let connection = Arc::clone(&connection);
            thread::spawn(move || connection.await_input())
        };
        thread::sleep(Duration::from_millis(50));
        client.write_all(&[7, 8]).unwrap();
        assert_eq!(observer.join().unwrap(), Ok(Readiness::Input));
        // Observing never consumes.
        assert_eq!(connection.await_input(), Ok(Readiness::Input));
        assert_eq!(connection.await_input(), Ok(Readiness::Input));
        let mut data = [0; 2];
        read_exact(&connection, &mut data);
        assert_eq!(data, [7, 8]);
    });
}

#[test]
fn eof_on_peer_close() {
    within(BOUND, || {
        let parent = Parent::new();
        let listener = parent.bind();
        let (connection, mut client) = pair(&listener);
        client.write_all(b"ab").unwrap();
        drop(client);
        assert_eq!(connection.await_input(), Ok(Readiness::Input));
        let mut data = [0; 2];
        read_exact(&connection, &mut data);
        assert_eq!(data, *b"ab");
        assert_eq!(connection.read(&mut [0; 4]), Ok(0));
        assert_eq!(connection.read(&mut [0; 4]), Ok(0));
        assert_eq!(connection.await_input(), Ok(Readiness::Eof));
        assert_eq!(kind(connection.write_all(b"x")), Some(Error::PeerClosed));

        // A pending observer sees the close too.
        let (connection, client) = pair(&listener);
        let connection = Arc::new(connection);
        let observer = {
            let connection = Arc::clone(&connection);
            thread::spawn(move || connection.await_input())
        };
        thread::sleep(Duration::from_millis(50));
        drop(client);
        assert_eq!(observer.join().unwrap(), Ok(Readiness::Eof));
    });
}

/// Reads to the end like a slow peer: it starts late and takes small pieces.
fn slow_read_to_end(mut client: support::Client) -> Vec<u8> {
    thread::sleep(Duration::from_millis(100));
    let mut data = Vec::new();
    let mut piece = [0; 16 * 1024];
    loop {
        match client.read(&mut piece) {
            Ok(0) => return data,
            Ok(count) => data.extend_from_slice(&piece[..count]),
            Err(error) => panic!("read failed after {} bytes: {error}", data.len()),
        }
        thread::sleep(Duration::from_millis(2));
    }
}

/// A reply written before close reaches a slow reader in full, then end of stream.
#[test]
fn final_reply_survives_server_close() {
    for size in [20, MIB] {
        within(Duration::from_secs(30), move || {
            let parent = Parent::new();
            let listener = parent.bind();
            let (connection, client) = pair(&listener);
            let reader = thread::spawn(move || slow_read_to_end(client));
            let reply = pattern(size);
            connection.write_all(&reply).unwrap();
            connection.close();
            drop(connection);
            drop(listener);
            let received = reader.join().unwrap();
            assert_eq!(received.len(), size);
            assert!(received == reply);
        });
    }
}

/// The CLI shape: an observer pends while the reply is written, then the
/// connection closes. The reply still arrives in full.
#[test]
fn final_reply_survives_close_with_pending_observer() {
    for size in [20, MIB] {
        within(Duration::from_secs(30), move || {
            let parent = Parent::new();
            let listener = parent.bind();
            let (connection, client) = pair(&listener);
            let connection = Arc::new(connection);
            let observer = {
                let connection = Arc::clone(&connection);
                thread::spawn(move || connection.await_input())
            };
            thread::sleep(Duration::from_millis(50));
            let reader = thread::spawn(move || slow_read_to_end(client));
            let reply = pattern(size);
            connection.write_all(&reply).unwrap();
            connection.close();
            assert_eq!(kind(observer.join().unwrap()), Some(Error::Closed));
            drop(connection);
            drop(listener);
            let received = reader.join().unwrap();
            assert_eq!(received.len(), size);
            assert!(received == reply);
        });
    }
}

/// Clients that connect and close before accept returns them never fail accept.
#[test]
fn per_connection_failures_never_reach_accept() {
    within(Duration::from_secs(30), || {
        let parent = Parent::new();
        let listener = parent.bind();
        let endpoint = listener.endpoint().to_owned();
        let clients = thread::spawn(move || {
            for _ in 0..50 {
                drop(support::connect(&endpoint));
            }
            let mut client = support::connect(&endpoint);
            client.write_all(b"K").unwrap();
            let mut byte = [0];
            client.read_exact(&mut byte).unwrap();
            byte
        });
        loop {
            let connection = listener
                .accept()
                .expect("a client's failure reached accept");
            let mut byte = [0];
            if connection.read(&mut byte) == Ok(1) {
                assert_eq!(byte, *b"K");
                connection.write_all(b"!").unwrap();
                break;
            }
        }
        assert_eq!(clients.join().unwrap(), *b"!");
    });
}

/// Kills the child if the test fails before it exits.
struct Fixture(Child);

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

#[test]
fn peer_pid_is_fixture_child() {
    let parent = Parent::new();
    let listener = Arc::new(parent.bind());
    let mut child = Fixture(
        Command::new(env!("CARGO_BIN_EXE_agent-ipc-fixture"))
            .arg(listener.endpoint())
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .spawn()
            .unwrap(),
    );
    let accepting = Arc::clone(&listener);
    let accepted = thread::spawn(move || accepting.accept());
    let deadline = Instant::now() + Duration::from_secs(10);
    while !accepted.is_finished() {
        assert!(Instant::now() < deadline, "the fixture never connected");
        thread::sleep(Duration::from_millis(5));
    }
    let connection = accepted.join().unwrap().unwrap();
    assert_eq!(connection.peer_pid(), Ok(child.0.id()));
    // Closing ends the fixture's read, and it exits.
    connection.close();
    drop(connection);
    let status = loop {
        if let Some(status) = child.0.try_wait().unwrap() {
            break status;
        }
        assert!(Instant::now() < deadline, "the fixture did not exit");
        thread::sleep(Duration::from_millis(5));
    };
    assert!(status.success(), "{status}");
}

#[test]
fn bridge_round_trip_never_names_the_endpoint() {
    let parent = Parent::new();
    let name = support::exact_name();
    let listener = bridge::listen(parent.bridge_parent(), "kg-", &name);
    assert!(listener > 0, "{:?}", bridge::last_error());
    let listener = listener as u64;
    let endpoint = bridge::endpoint(listener, str::to_owned).unwrap();
    assert!(endpoint.contains(&name));
    let no_names = || {
        let diagnostic = bridge::last_error().unwrap();
        assert!(!diagnostic.contains(&name), "{diagnostic}");
        assert!(!diagnostic.contains(&endpoint), "{diagnostic}");
        diagnostic
    };

    let mut client = support::connect(&endpoint);
    let connection = bridge::accept(listener);
    assert!(connection > listener as i64, "{:?}", bridge::last_error());
    let connection = connection as u64;
    assert_eq!(bridge::peer_pid(connection), i64::from(std::process::id()));
    client.write_all(b"hi").unwrap();
    let mut buffer = [0; 2];
    let mut filled = 0;
    while filled < buffer.len() {
        let count = bridge::read(connection, &mut buffer[filled..]);
        assert!(count > 0, "{:?}", bridge::last_error());
        filled += count as usize;
    }
    assert_eq!(buffer, *b"hi");
    assert_eq!(bridge::write(connection, b"ok"), 0);
    let mut reply = [0; 2];
    client.read_exact(&mut reply).unwrap();
    assert_eq!(reply, *b"ok");
    assert_eq!(bridge::write(connection, &[]), 0);
    assert_eq!(bridge::read(connection, &mut []), -1);
    assert_eq!(no_names(), "read: empty_buffer");

    // A taken name.
    assert_eq!(bridge::listen(parent.bridge_parent(), "kg-", &name), -7);
    assert!(no_names().starts_with("listen: "));

    // Handles are consumed once.
    assert_eq!(bridge::close(connection), 0);
    assert_eq!(bridge::close(connection), -5);
    assert_eq!(no_names(), "close: invalid_handle");
    assert_eq!(bridge::read(connection, &mut buffer), -5);
    assert_eq!(bridge::close_listener(listener), 0);
    assert_eq!(bridge::close_listener(listener), -5);
    assert_eq!(bridge::accept(listener), -5);
    assert_eq!(no_names(), "accept: invalid_handle");
    let mut rest = Vec::new();
    client.read_to_end(&mut rest).unwrap();
    assert!(rest.is_empty());

    // Handles are never reused.
    let next = bridge::listen(parent.bridge_parent(), "kg-", "");
    assert!(next > connection as i64, "{:?}", bridge::last_error());
    assert_eq!(bridge::close_listener(next as u64), 0);
}
