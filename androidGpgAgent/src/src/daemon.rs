//! The agent's Unix socket server.

use anyhow::{Context, Result};
use keyguard_agent_identity::caller_guard::CallerGuard;
use keyguard_android_agent_bridge::bridge::BridgeConfig;
use keyguard_android_agent_bridge::caller_identity::caller_from_unix_stream;
use keyguard_android_agent_bridge::log_debug;
use keyguard_android_agent_bridge::server::{self, serve_clients, wait_for_shutdown_signal};
use keyguard_gpg_agent_assuan::ipc::IpcClient;
use keyguard_gpg_agent_assuan::{serve_connection, AgentInfo};
use std::sync::Arc;
use tokio::net::{UnixListener, UnixStream};

use crate::transport::BridgeTransport;

pub(crate) fn run_blocking(
    listener: std::os::unix::net::UnixListener,
    config: BridgeConfig,
    info: AgentInfo,
) -> Result<i32> {
    server::run_blocking(run(listener, Arc::new(config), Arc::new(info)))
}

async fn run(
    listener: std::os::unix::net::UnixListener,
    config: Arc<BridgeConfig>,
    info: Arc<AgentInfo>,
) -> Result<i32> {
    let listener =
        UnixListener::from_std(listener).context("Failed to create async Unix listener")?;
    let debug = config.debug;
    let server = serve_clients(listener, debug, move |client| {
        handle_client_connection(client, config.clone(), info.clone())
    });
    tokio::select! {
        result = server => {
            result.context("GPG agent server failed")?;
            Ok(0)
        }
        result = wait_for_shutdown_signal() => {
            result?;
            Ok(1)
        }
    }
}

async fn handle_client_connection(
    client: UnixStream,
    config: Arc<BridgeConfig>,
    info: Arc<AgentInfo>,
) -> Result<()> {
    let caller = caller_from_unix_stream(&client);
    log_debug(
        config.debug,
        &format!(
            "GPG client connected: pid={:?} executable={:?}",
            caller.as_ref().and_then(|caller| caller.pid),
            caller
                .as_ref()
                .and_then(|caller| caller.executable_path.as_deref()),
        ),
    );
    let ipc_client = IpcClient::new(BridgeTransport::new(config));
    // Termux runs every client under its own UID, so there is no process
    // identity to revalidate; the app attributes requests to Termux instead.
    serve_connection(
        client,
        ipc_client,
        caller.as_ref().map(Into::into),
        CallerGuard::default(),
        info,
    )
    .await
}

#[cfg(test)]
mod tests {
    use super::*;
    use keyguard_android_agent_bridge::broadcast::BroadcastCommandSpec;
    use std::ffi::OsString;
    use std::path::Path;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::time::Duration;

    /// Records each broadcast in [marker], then runs [answer] as `am` would.
    fn config_recording_broadcasts(marker: &Path, answer: &str) -> Arc<BridgeConfig> {
        let script = format!("printf x >> '{}'; {answer}", marker.display());
        Arc::new(BridgeConfig {
            android_component: "component".to_string(),
            connect_timeout: Duration::from_secs(60),
            debug: false,
            broadcast_command: BroadcastCommandSpec::new(
                OsString::from("sh"),
                vec![
                    OsString::from("-c"),
                    OsString::from(script),
                    OsString::from("sh"),
                ],
            ),
        })
    }

    async fn transcript(config: Arc<BridgeConfig>, commands: &str) -> String {
        let (mut client, server) = UnixStream::pair().unwrap();
        let info = Arc::new(AgentInfo::new("socket".to_string(), "2.5.24".to_string()));
        let session = tokio::spawn(handle_client_connection(server, config, info));
        client.write_all(commands.as_bytes()).await.unwrap();
        let mut response = String::new();
        client.read_to_string(&mut response).await.unwrap();
        session.await.unwrap().unwrap();
        response
    }

    #[tokio::test]
    async fn protocol_commands_do_not_wake_the_app() {
        let directory = tempfile::tempdir().unwrap();
        let marker = directory.path().join("broadcast");

        let response = transcript(
            config_recording_broadcasts(&marker, "exit 1"),
            "OPTION ttyname=/dev/pts/0\nGETINFO version\nSIGKEY AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\nBYE\n",
        )
        .await;

        assert!(response.contains("D 2.5.24\nOK\n"), "{response}");
        assert!(!marker.exists());
    }

    #[tokio::test]
    async fn key_operations_wake_the_app_once_per_connection() {
        let directory = tempfile::tempdir().unwrap();
        let marker = directory.path().join("broadcast");

        let response = transcript(
            config_recording_broadcasts(&marker, "exit 1"),
            "HAVEKEY --list\nKEYINFO --list\nBYE\n",
        )
        .await;

        // The failed bridge stays closed instead of broadcasting again.
        assert_eq!(std::fs::read_to_string(&marker).unwrap(), "x");
        assert_eq!(response.matches("ERR 1 key listing failed\n").count(), 2);
    }

    #[tokio::test]
    async fn an_unanswered_broadcast_fails_without_waiting_for_the_app() {
        let directory = tempfile::tempdir().unwrap();
        let marker = directory.path().join("broadcast");

        // What `am` prints when no receiver handles the broadcast.
        let config = config_recording_broadcasts(&marker, "echo 'Broadcast completed: result=0'");
        let response = tokio::time::timeout(
            Duration::from_secs(10),
            transcript(config, "HAVEKEY --list\nKEYINFO --list\nBYE\n"),
        )
        .await
        .expect("waited for an app that never answered");

        assert_eq!(std::fs::read_to_string(&marker).unwrap(), "x");
        assert_eq!(response.matches("ERR 1 key listing failed\n").count(), 2);
    }
}
