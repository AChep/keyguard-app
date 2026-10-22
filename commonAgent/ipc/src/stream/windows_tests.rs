use super::*;
use keyguard_agent_identity::windows::create_owner_only_pipe;
use std::time::Duration;
use tokio::net::windows::named_pipe::{ClientOptions, NamedPipeClient, NamedPipeServer};
use windows_sys::Win32::Foundation::{ERROR_FILE_NOT_FOUND, ERROR_PIPE_BUSY};

const WAIT: Duration = Duration::from_secs(5);
const BUSY_WAIT: Duration = Duration::from_millis(150);

fn pipe_path() -> std::path::PathBuf {
    let mut random = [0u8; 16];
    getrandom::fill(&mut random).unwrap();
    format!(r"\\.\pipe\keyguard-transport-test-{}", hex::encode(random)).into()
}

struct BusyPipe {
    path: std::path::PathBuf,
    _server: NamedPipeServer,
    _client: NamedPipeClient,
}

impl BusyPipe {
    async fn new() -> Self {
        let path = pipe_path();
        let server = create_owner_only_pipe(&path, true).unwrap();
        let client = ClientOptions::new().open(&path).unwrap();
        tokio::time::timeout(WAIT, server.connect())
            .await
            .unwrap()
            .unwrap();
        // Establish the precondition synchronously, before exercising our retry loop.
        assert_eq!(
            ClientOptions::new().open(&path).unwrap_err().raw_os_error(),
            Some(ERROR_PIPE_BUSY as i32)
        );
        Self {
            path,
            _server: server,
            _client: client,
        }
    }
}

#[tokio::test]
async fn busy_pipe_connects_when_the_next_instance_is_created() {
    let pipe = BusyPipe::new().await;
    let connection = connect(&pipe.path, Some(std::process::id()));
    tokio::pin!(connection);
    // Keep this same future alive across several retries before publishing an instance.
    assert!(
        tokio::time::timeout(BUSY_WAIT, &mut connection)
            .await
            .is_err()
    );
    let next = create_owner_only_pipe(&pipe.path, false).unwrap();
    let (client, server) = tokio::time::timeout(WAIT, async {
        tokio::join!(&mut connection, next.connect())
    })
    .await
    .unwrap();
    let _client = client.unwrap();
    server.unwrap();
}

#[tokio::test]
async fn busy_pipe_deadline_cancels_connection_attempts() {
    let pipe = BusyPipe::new().await;
    assert!(
        tokio::time::timeout(BUSY_WAIT, connect(&pipe.path, None))
            .await
            .is_err()
    );
    let next = create_owner_only_pipe(&pipe.path, false).unwrap();
    // Dropping the timed-out future must not leave a background retry consuming it.
    assert!(
        tokio::time::timeout(BUSY_WAIT, next.connect())
            .await
            .is_err()
    );
    let (client, server) = tokio::time::timeout(WAIT, async {
        tokio::join!(connect(&pipe.path, None), next.connect())
    })
    .await
    .unwrap();
    let _client = client.unwrap();
    server.unwrap();
}

#[tokio::test]
async fn missing_pipe_fails_without_busy_retries() {
    let result = tokio::time::timeout(WAIT, connect(&pipe_path(), None))
        .await
        .unwrap();
    let error = result.err().expect("missing pipe must fail");
    assert_eq!(
        error
            .downcast_ref::<std::io::Error>()
            .unwrap()
            .raw_os_error(),
        Some(ERROR_FILE_NOT_FOUND as i32)
    );
}

#[tokio::test]
async fn server_identity_is_checked_after_a_busy_retry() {
    let pipe = BusyPipe::new().await;
    // PID zero cannot be the process that owns this pipe.
    let connection = connect(&pipe.path, Some(0));
    tokio::pin!(connection);
    assert!(
        tokio::time::timeout(BUSY_WAIT, &mut connection)
            .await
            .is_err()
    );
    let _next = create_owner_only_pipe(&pipe.path, false).unwrap();
    // Rejection closes the client immediately, so the server may observe a
    // disconnect instead of a successful accept. Only the client result matters.
    let client = tokio::time::timeout(WAIT, &mut connection).await.unwrap();
    assert!(
        client
            .err()
            .expect("wrong PID must fail")
            .to_string()
            .contains("PID mismatch")
    );
}
