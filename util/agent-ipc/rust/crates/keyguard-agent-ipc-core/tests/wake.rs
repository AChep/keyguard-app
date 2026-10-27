//! Closing wakes every blocked call promptly: accept, read, write and observe,
//! including with a half-closed peer on Unix (plan D5).
//!
//! Run serially (`-- --test-threads=1`). Each bound is 100 ms, times
//! `KEYGUARD_AGENT_IPC_TIMING_SCALE`.

mod support;

use std::{
    sync::{
        Arc,
        mpsc::{self, TryRecvError},
    },
    thread,
    time::{Duration, Instant},
};

use keyguard_agent_ipc_core::{Error, Failure};
use support::{MIB, Parent, pair};

fn bound() -> Duration {
    Duration::from_millis(100) * support::timing_scale()
}

/// Starts `call`, lets it block, then runs `close`. Returns how long the call took
/// to return after `close` started, and its failure.
fn woken_by<T: Send + 'static>(
    call: impl FnOnce() -> Result<T, Failure> + Send + 'static,
    close: impl FnOnce(),
) -> (Duration, Option<Error>) {
    let (sender, receiver) = mpsc::channel();
    thread::spawn(move || {
        let failure = call().err().map(Failure::kind);
        let _ = sender.send((Instant::now(), failure));
    });
    thread::sleep(Duration::from_millis(200));
    assert!(
        matches!(receiver.try_recv(), Err(TryRecvError::Empty)),
        "the call returned before close"
    );
    let closed = Instant::now();
    close();
    let (returned, failure) = receiver
        .recv_timeout(Duration::from_secs(5))
        .expect("close did not wake the call");
    (returned.saturating_duration_since(closed), failure)
}

fn assert_woken((elapsed, failure): (Duration, Option<Error>)) {
    assert_eq!(failure, Some(Error::Closed));
    assert!(elapsed <= bound(), "woken after {elapsed:?}");
    eprintln!("woken after {elapsed:?}");
}

#[test]
fn close_wakes_accept() {
    let parent = Parent::new();
    let listener = Arc::new(parent.bind());
    let accepting = Arc::clone(&listener);
    assert_woken(woken_by(move || accepting.accept(), || listener.close()));
}

#[test]
fn close_wakes_read() {
    let parent = Parent::new();
    let listener = parent.bind();
    let (connection, _client) = pair(&listener);
    let connection = Arc::new(connection);
    let reading = Arc::clone(&connection);
    assert_woken(woken_by(
        move || reading.read(&mut [0; 16]),
        || connection.close(),
    ));
}

#[test]
fn close_wakes_write() {
    let parent = Parent::new();
    let listener = parent.bind();
    // The client never reads, so the write fills the buffers and blocks.
    let (connection, _client) = pair(&listener);
    let connection = Arc::new(connection);
    let writing = Arc::clone(&connection);
    assert_woken(woken_by(
        move || writing.write_all(&vec![0; 8 * MIB]),
        || connection.close(),
    ));
}

#[test]
fn close_wakes_observe() {
    let parent = Parent::new();
    let listener = parent.bind();
    let (connection, _client) = pair(&listener);
    let connection = Arc::new(connection);
    let observing = Arc::clone(&connection);
    assert_woken(woken_by(
        move || observing.await_input(),
        || connection.close(),
    ));
}

/// The peer shut its write side, so this side's read side is already shut: a
/// single `SHUT_RDWR` would leave the blocked writer asleep on XNU.
#[cfg(unix)]
#[test]
fn half_closed_peer_blocked_write_wakes() {
    let parent = Parent::new();
    let listener = parent.bind();
    let (connection, client) = pair(&listener);
    client.shutdown(std::net::Shutdown::Write).unwrap();
    let connection = Arc::new(connection);
    let writing = Arc::clone(&connection);
    assert_woken(woken_by(
        move || writing.write_all(&vec![0; 8 * MIB]),
        || connection.close(),
    ));
    drop(client);
}

/// The peer shut its read side, so this side's write side is already shut.
#[cfg(unix)]
#[test]
fn half_closed_peer_observe_wakes() {
    let parent = Parent::new();
    let listener = parent.bind();
    let (connection, client) = pair(&listener);
    client.shutdown(std::net::Shutdown::Read).unwrap();
    let connection = Arc::new(connection);
    let observing = Arc::clone(&connection);
    assert_woken(woken_by(
        move || observing.await_input(),
        || connection.close(),
    ));
    drop(client);
}
