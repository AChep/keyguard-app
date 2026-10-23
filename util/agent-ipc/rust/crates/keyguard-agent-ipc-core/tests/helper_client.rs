//! The helpers' own client accepts this listener: `keyguard_agent_ipc::stream::connect`
//! verifies that the server is the expected process and runs as this user (plan
//! §5.1 item 14).

mod support;

use std::{path::PathBuf, sync::Arc, thread, time::Duration};

use keyguard_agent_ipc::stream;
use keyguard_agent_ipc_core::{Error, Readiness};
use support::{Parent, read_exact};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    time::timeout,
};

const BOUND: Duration = Duration::from_secs(10);

#[tokio::test]
async fn the_helper_client_verifies_this_process() {
    let parent = Parent::new();
    let listener = Arc::new(parent.bind());
    let endpoint = PathBuf::from(listener.endpoint());
    let server = {
        let listener = Arc::clone(&listener);
        thread::spawn(move || {
            let connection = listener.accept().unwrap();
            assert_eq!(connection.peer_pid(), Ok(std::process::id()));
            let mut byte = [0];
            read_exact(&connection, &mut byte);
            connection.write_all(&[byte[0] + 1]).unwrap();
            connection.await_input()
        })
    };
    let mut stream = timeout(BOUND, stream::connect(&endpoint, Some(std::process::id())))
        .await
        .unwrap()
        .unwrap();
    stream.write_all(&[41]).await.unwrap();
    let mut byte = [0];
    stream.read_exact(&mut byte).await.unwrap();
    assert_eq!(byte, [42]);
    drop(stream);
    assert_eq!(support::join(server).await, Ok(Readiness::Eof));
}

#[tokio::test]
async fn the_helper_client_rejects_another_server_pid() {
    let parent = Parent::new();
    let listener = Arc::new(parent.bind());
    let endpoint = PathBuf::from(listener.endpoint());
    let server = {
        let listener = Arc::clone(&listener);
        thread::spawn(move || {
            loop {
                if let Err(failure) = listener.accept() {
                    return failure.kind();
                }
            }
        })
    };
    let other = std::process::id().wrapping_add(1).max(1);
    let error = timeout(BOUND, stream::connect(&endpoint, Some(other)))
        .await
        .unwrap()
        .err()
        .expect("another pid must fail");
    assert!(format!("{error:#}").contains("PID mismatch"), "{error:#}");
    listener.close();
    assert_eq!(support::join(server).await, Error::Closed);
}
