//! SIGPIPE stays suppressed per descriptor, in a process that never ignores it, as
//! a native host does not.
#![cfg(unix)]

use std::{
    io::Read, net::Shutdown, os::unix::net::UnixStream, process::Command, thread, time::Duration,
};

use keyguard_agent_ipc_core::{Error, Failure, Listener, Location, Readiness};

const CHILD: &str = "KEYGUARD_AGENT_IPC_SIGPIPE_CHILD";

#[test]
fn writes_to_a_gone_peer_never_raise_sigpipe() {
    if std::env::var_os(CHILD).is_none() {
        let output = Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "writes_to_a_gone_peer_never_raise_sigpipe",
                "--nocapture",
                "--test-threads=1",
            ])
            .env(CHILD, "1")
            .output()
            .unwrap();
        let stdout = String::from_utf8_lossy(&output.stdout);
        // A SIGPIPE ends the child with that signal instead of a test failure.
        assert!(
            output.status.success() && stdout.contains("1 passed"),
            "the child failed: {:?}\nstdout: {stdout}\nstderr: {}",
            output.status,
            String::from_utf8_lossy(&output.stderr)
        );
        return;
    }
    // SAFETY: this dedicated child process restores the default disposition, which
    // the Rust runtime replaced with SIG_IGN, as a native host has it.
    unsafe { libc::signal(libc::SIGPIPE, libc::SIG_DFL) };

    let parent = tempfile::tempdir().unwrap();
    let listener = Listener::bind(Location::Unix {
        parent: parent.path().to_str().unwrap(),
        prefix: "kg-",
        exact: None,
    })
    .unwrap();
    let connect = || {
        let client = UnixStream::connect(listener.endpoint()).unwrap();
        (client, listener.accept().unwrap())
    };

    // The peer closed its socket.
    let (client, connection) = connect();
    drop(client);
    assert_eq!(connection.await_input(), Ok(Readiness::Eof));
    for size in [1, 1 << 20] {
        let result = connection.write_all(&vec![0x5a; size]);
        assert_eq!(result.map_err(Failure::kind), Err(Error::PeerClosed));
    }
    connection.close();

    // The peer shut down its read side only. Linux refuses the write; XNU drops
    // data for a socket that receives no more.
    let (client, connection) = connect();
    client.shutdown(Shutdown::Read).unwrap();
    let result = connection.write_all(b"reply").map_err(Failure::kind);
    if cfg!(target_os = "macos") {
        assert!(
            matches!(result, Ok(()) | Err(Error::PeerClosed)),
            "{result:?}"
        );
    } else {
        assert_eq!(result, Err(Error::PeerClosed));
    }
    drop(client);
    connection.close();

    // Closed locally while a write is blocked on the peer's full buffer.
    let (mut client, connection) = connect();
    thread::scope(|scope| {
        let writer = scope.spawn(|| connection.write_all(&vec![0x5a; 8 << 20]));
        thread::sleep(Duration::from_millis(100));
        connection.close();
        let result = writer.join().unwrap().map_err(Failure::kind);
        assert_eq!(result, Err(Error::Closed));
    });
    let late = connection.write_all(b"late").map_err(Failure::kind);
    assert_eq!(late, Err(Error::Closed));
    // The peer drains what was sent before the close, then reaches the end.
    let mut drained = Vec::new();
    client.read_to_end(&mut drained).unwrap();
    assert!(drained.iter().all(|byte| *byte == 0x5a));

    // The wake byte of a listener close.
    listener.close();
    assert_eq!(
        listener.accept().err().map(Failure::kind),
        Some(Error::Closed)
    );
}
