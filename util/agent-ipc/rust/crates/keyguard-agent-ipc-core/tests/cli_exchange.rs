//! Replays the CLI agent's app client (`desktopCliAgent/src/src/app_client.rs`)
//! against this listener (plan D13): the helper's own connect and 1 MiB frames
//! inside the client's 70 s budget, with the fixture encodings of both sides. The
//! server observes the connection while it writes the response, as the app's
//! CLI session does, and then sees the client leave.

mod support;

use std::{path::PathBuf, sync::Arc, thread, time::Duration};

use keyguard_agent_ipc::{
    frame::{read_frame, write_frame},
    stream,
};
use keyguard_agent_ipc_core::{Connection, Listener, Readiness};
use support::{Parent, read_exact};
use zeroize::Zeroizing;

const REQUESTS: &str =
    include_str!("../../../../../../desktopCliAgent/proto/fixtures/requests.pbhex");
const RESPONSES: &str =
    include_str!("../../../../../../desktopCliAgent/proto/fixtures/responses.pbhex");

/// `commonCli/src/frame.rs`.
const MAX_FRAME: usize = 1024 * 1024;

/// The fixture `name` of a `.pbhex` file: one `<name> <hex>` per line.
fn fixture(file: &str, name: &str) -> Vec<u8> {
    let hex = file
        .lines()
        .find_map(|line| line.strip_prefix(name)?.strip_prefix(' '))
        .unwrap_or_else(|| panic!("no fixture {name}"))
        .trim();
    assert_eq!(hex.len() % 2, 0);
    (0..hex.len())
        .step_by(2)
        .map(|index| u8::from_str_radix(&hex[index..index + 2], 16).unwrap())
        .collect()
}

fn read_frame_from(connection: &Connection) -> Vec<u8> {
    let mut size = [0; 4];
    read_exact(connection, &mut size);
    let size = u32::from_be_bytes(size) as usize;
    assert!((1..=MAX_FRAME).contains(&size));
    let mut body = vec![0; size];
    read_exact(connection, &mut body);
    body
}

fn write_frame_to(connection: &Connection, body: &[u8]) {
    let size = u32::try_from(body.len()).unwrap();
    connection.write_all(&size.to_be_bytes()).unwrap();
    connection.write_all(body).unwrap();
}

/// The app's side: authenticate, then exactly one request, answered while an
/// observer waits for input or the end of the stream.
fn serve(listener: &Listener) -> Readiness {
    let connection = Arc::new(listener.accept().unwrap());
    assert_eq!(connection.peer_pid(), Ok(std::process::id()));
    assert_eq!(
        read_frame_from(&connection),
        fixture(REQUESTS, "authenticate")
    );
    write_frame_to(&connection, &fixture(RESPONSES, "authenticate"));
    assert_eq!(read_frame_from(&connection), fixture(REQUESTS, "cli_run"));
    let observer = {
        let connection = Arc::clone(&connection);
        thread::spawn(move || connection.await_input())
    };
    // The observer pends while the response is written.
    thread::sleep(Duration::from_millis(50));
    write_frame_to(&connection, &fixture(RESPONSES, "cli_run"));
    let readiness = observer.join().unwrap().unwrap();
    connection.close();
    readiness
}

#[tokio::test]
async fn the_cli_agent_app_client_exchange() {
    let parent = Parent::new();
    let listener = Arc::new(parent.bind());
    let endpoint = PathBuf::from(listener.endpoint());
    let server = {
        let listener = Arc::clone(&listener);
        thread::spawn(move || serve(&listener))
    };
    tokio::time::timeout(Duration::from_secs(70), async {
        let mut stream = stream::connect(&endpoint, Some(std::process::id()))
            .await
            .unwrap();
        let auth = Zeroizing::new(fixture(REQUESTS, "authenticate"));
        write_frame(&mut *stream, &auth, MAX_FRAME).await.unwrap();
        let response = read_frame(&mut *stream, MAX_FRAME).await.unwrap();
        assert_eq!(*response, fixture(RESPONSES, "authenticate"));
        let request = Zeroizing::new(fixture(REQUESTS, "cli_run"));
        write_frame(&mut *stream, &request, MAX_FRAME)
            .await
            .unwrap();
        let response = read_frame(&mut *stream, MAX_FRAME).await.unwrap();
        assert_eq!(*response, fixture(RESPONSES, "cli_run"));
        // The stream drops here, as the agent's does after one request.
    })
    .await
    .expect("request_timeout");
    assert_eq!(support::join(server).await, Readiness::Eof);
}
