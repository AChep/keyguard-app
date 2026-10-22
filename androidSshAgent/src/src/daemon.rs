use anyhow::{Context, Result};
use common_ssh_agent_rust::messages::{IpcRequest, IpcResponse};
use std::ffi::OsString;
use std::path::Path;
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::net::{TcpStream, UnixListener, UnixStream};
use tokio::process::{Child, Command};
use tokio::task::JoinHandle;
use tokio::time::{timeout, Duration};

use crate::packet_io::{read_ssh_agent_packet, write_ssh_agent_packet};
use crate::process::{terminate_pid, AGENT_PID_ENV, AUTH_SOCK_ENV};
use crate::rpc::{
    build_failure_packet, read_ssh_string, read_u32, translate_client_packet,
    translate_server_response, RequestTranslation, SSH_AGENT_FAILURE, SSH_AGENT_IDENTITIES_ANSWER,
    SSH_AGENT_REQUEST_IDENTITIES, SSH_AGENT_SIGN_REQUEST, SSH_AGENT_SIGN_RESPONSE,
};
use crate::SSH_AGENT_CONTRACT;
use keyguard_android_agent_bridge::bridge::{
    exchange_message, open_bridge, BridgeConfig, OpenBridgeError,
};
use keyguard_android_agent_bridge::caller_identity::{caller_from_unix_stream, CallerIdentity};
use keyguard_android_agent_bridge::log_debug;
use keyguard_android_agent_bridge::secure_transport::{SecureTransport, MAX_FRAME_PAYLOAD_SIZE};
use keyguard_android_agent_bridge::server::{self, serve_clients, wait_for_shutdown_signal};

const CHILD_TERMINATION_TIMEOUT: Duration = Duration::from_secs(1);
const CLIENT_PACKET_TIMEOUT: Duration = Duration::from_secs(120);
const RESPONSE_WRITE_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Clone)]
pub(crate) struct Config {
    pub(crate) bridge: BridgeConfig,
    pub(crate) socket_path: std::path::PathBuf,
}

pub(crate) fn run_blocking(
    listener: std::os::unix::net::UnixListener,
    cmd: Option<Vec<OsString>>,
    config: Config,
) -> Result<i32> {
    server::run_blocking(run(listener, cmd, config))
}

async fn run(
    listener: std::os::unix::net::UnixListener,
    cmd: Option<Vec<OsString>>,
    config: Config,
) -> Result<i32> {
    let unix_listener =
        UnixListener::from_std(listener).context("Failed to create async Unix listener")?;
    let mut server = tokio::spawn(run_server(unix_listener, config.clone()));
    let mut shutdown = tokio::spawn(wait_for_shutdown_signal());

    if let Some(cmd) = cmd {
        run_command_with_supervision(cmd, &config.socket_path, &mut server, &mut shutdown).await
    } else {
        tokio::select! {
            result = &mut server => {
                shutdown.abort();
                result.context("SSH agent server task failed")??;
                Ok(0)
            }
            result = &mut shutdown => {
                server.abort();
                result.context("Signal task failed")??;
                Ok(1)
            }
        }
    }
}

async fn run_command_with_supervision(
    cmd: Vec<OsString>,
    socket_path: &Path,
    server: &mut JoinHandle<Result<()>>,
    shutdown: &mut JoinHandle<Result<()>>,
) -> Result<i32> {
    let mut child = spawn_child_command(&cmd, socket_path)?;

    tokio::select! {
        result = child.wait() => {
            server.abort();
            shutdown.abort();
            let status = result.context("Failed to run child command")?;
            Ok(status.code().unwrap_or(1))
        }
        result = &mut *server => {
            shutdown.abort();
            terminate_child(&mut child).await?;
            result.context("SSH agent server task failed")??;
            Ok(0)
        }
        result = &mut *shutdown => {
            server.abort();
            result.context("Signal task failed")??;
            terminate_child(&mut child).await?;
            Ok(1)
        }
    }
}

fn spawn_child_command(cmd: &[OsString], socket_path: &Path) -> Result<Child> {
    let Some((program, args)) = cmd.split_first() else {
        anyhow::bail!("Child command must not be empty");
    };

    let mut command = Command::new(program);
    command
        .args(args)
        .env(AUTH_SOCK_ENV, socket_path)
        .env(AGENT_PID_ENV, std::process::id().to_string())
        .kill_on_drop(true);

    command.spawn().context("Failed to spawn child command")
}

async fn terminate_child(child: &mut Child) -> Result<()> {
    let Some(pid) = child.id() else {
        return Ok(());
    };
    let pid = i32::try_from(pid).context("Child pid does not fit into i32")?;

    if let Err(err) = terminate_pid(pid) {
        let not_found = err.raw_os_error() == Some(libc::ESRCH);
        if !not_found {
            return Err(err).context("Failed to terminate child command");
        }
    }

    match timeout(CHILD_TERMINATION_TIMEOUT, child.wait()).await {
        Ok(result) => {
            result.context("Failed to wait for child command termination")?;
            Ok(())
        }
        Err(_) => {
            child.kill().await.context("Failed to kill child command")?;
            let _ = child.wait().await;
            Ok(())
        }
    }
}

async fn run_server(listener: UnixListener, config: Config) -> Result<()> {
    let debug = config.bridge.debug;
    serve_clients(listener, debug, move |client| {
        handle_client_connection(client, config.clone())
    })
    .await
}

async fn handle_client_connection(mut client: UnixStream, config: Config) -> Result<()> {
    let caller = caller_from_unix_stream(&client);
    log_debug(
        config.bridge.debug,
        &format!(
            "SSH client connected: caller={}",
            describe_caller(caller.as_ref())
        ),
    );

    // Do not allocate a TCP listener or execute `am broadcast` for a bare UDS
    // connect. Only a complete, bounded, forwardable SSH-agent request can
    // activate the Android bridge. Unsupported packets are answered locally.
    let Some(first_request) = read_next_forward_request(
        &mut client,
        1,
        caller.as_ref(),
        config.bridge.debug,
        CLIENT_PACKET_TIMEOUT,
        RESPONSE_WRITE_TIMEOUT,
    )
    .await?
    else {
        return Ok(());
    };

    let mut transport = match open_bridge(&config.bridge, &SSH_AGENT_CONTRACT).await {
        Ok(transport) => transport,
        Err(OpenBridgeError::Broadcast(err)) => {
            // Best effort only: the broadcast failure is the error worth
            // surfacing, not a write error to an already-gone client.
            let _ = write_client_failure(&mut client).await;
            return Err(err).context("Failed to send Android SSH agent broadcast");
        }
        Err(OpenBridgeError::Rejected(outcome)) => {
            log_debug(
                config.bridge.debug,
                &format!("Keyguard rejected Android bridge activation: {outcome:?}"),
            );
            write_client_failure(&mut client).await?;
            return Ok(());
        }
        Err(OpenBridgeError::Failed(err)) => return Err(err),
    };
    log_debug(config.bridge.debug, "SSH agent handshake established");

    let mut next_request_id = 1u64;
    forward_request(
        &mut client,
        &mut transport,
        first_request,
        config.bridge.debug,
    )
    .await?;
    next_request_id = next_request_id
        .checked_add(1)
        .context("SSH agent request id overflow")?;

    while let Some(request) = read_next_forward_request(
        &mut client,
        next_request_id,
        caller.as_ref(),
        config.bridge.debug,
        CLIENT_PACKET_TIMEOUT,
        RESPONSE_WRITE_TIMEOUT,
    )
    .await?
    {
        forward_request(&mut client, &mut transport, request, config.bridge.debug).await?;
        next_request_id = next_request_id
            .checked_add(1)
            .context("SSH agent request id overflow")?;
    }

    Ok(())
}

async fn read_next_forward_request<S>(
    client: &mut S,
    request_id: u64,
    caller: Option<&CallerIdentity>,
    debug: bool,
    packet_timeout: Duration,
    write_timeout: Duration,
) -> Result<Option<IpcRequest>>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    loop {
        let packet = timeout(
            packet_timeout,
            read_ssh_agent_packet(client, MAX_FRAME_PAYLOAD_SIZE),
        )
        .await
        .context("Timed out waiting for a complete SSH client packet")??;
        let Some(packet) = packet else {
            return Ok(None);
        };

        log_debug(
            debug,
            &format!("SSH client request: {}", describe_agent_packet(&packet)),
        );
        match translate_client_packet(&packet, request_id, caller) {
            RequestTranslation::Forward(request) => return Ok(Some(request)),
            RequestTranslation::Failure => {
                let response = build_failure_packet();
                log_debug(
                    debug,
                    &format!("SSH client response: {}", describe_agent_packet(&response)),
                );
                write_client_packet(client, &response, write_timeout).await?;
            }
        }
    }
}

async fn forward_request<S>(
    client: &mut S,
    transport: &mut SecureTransport<TcpStream>,
    request: IpcRequest,
    debug: bool,
) -> Result<()>
where
    S: AsyncWrite + Unpin,
{
    let request_id = request.id;
    let response: IpcResponse = exchange_message(transport, &request).await?;
    let ssh_response = translate_server_response(request_id, response)
        .context("Failed to translate protobuf response into SSH agent packet")?;
    log_debug(
        debug,
        &format!(
            "SSH client response: {}",
            describe_agent_packet(&ssh_response)
        ),
    );
    write_client_packet(client, &ssh_response, RESPONSE_WRITE_TIMEOUT).await
}

async fn write_client_packet<S>(
    client: &mut S,
    packet: &[u8],
    write_timeout: Duration,
) -> Result<()>
where
    S: AsyncWrite + Unpin,
{
    timeout(
        write_timeout,
        write_ssh_agent_packet(client, packet, MAX_FRAME_PAYLOAD_SIZE),
    )
    .await
    .context("Timed out writing an SSH client response")??;
    Ok(())
}

async fn write_client_failure<S>(client: &mut S) -> Result<()>
where
    S: AsyncWrite + Unpin,
{
    let response = build_failure_packet();
    write_client_packet(client, &response, RESPONSE_WRITE_TIMEOUT).await
}

fn describe_agent_packet(packet: &[u8]) -> String {
    let Some((&message_type, payload)) = packet.split_first() else {
        return "type=empty bytes=0".to_string();
    };

    let summary = match message_type {
        SSH_AGENT_REQUEST_IDENTITIES => "summary=request_identities".to_string(),
        SSH_AGENT_IDENTITIES_ANSWER => {
            format!("summary={}", inspect_identities_answer(payload))
        }
        SSH_AGENT_SIGN_REQUEST => format!("summary={}", inspect_sign_request(payload)),
        SSH_AGENT_SIGN_RESPONSE => format!("summary={}", inspect_sign_response(payload)),
        SSH_AGENT_FAILURE => "summary=failure".to_string(),
        _ => "summary=unparsed".to_string(),
    };

    format!(
        "type={}({}) bytes={} {}",
        message_type,
        ssh_agent_message_name(message_type),
        packet.len(),
        summary,
    )
}

fn ssh_agent_message_name(message_type: u8) -> &'static str {
    match message_type {
        SSH_AGENT_FAILURE => "failure",
        SSH_AGENT_REQUEST_IDENTITIES => "request_identities",
        SSH_AGENT_IDENTITIES_ANSWER => "identities_answer",
        SSH_AGENT_SIGN_REQUEST => "sign_request",
        SSH_AGENT_SIGN_RESPONSE => "sign_response",
        _ => "unknown",
    }
}

fn inspect_identities_answer(payload: &[u8]) -> String {
    let mut cursor = payload;
    let Ok(count) = read_u32(&mut cursor) else {
        return "identities_answer_parse_error=missing_count".to_string();
    };

    let mut parts = Vec::new();
    for index in 0..count {
        let Ok(blob) = read_ssh_string(&mut cursor) else {
            return format!(
                "identities_answer_parse_error=truncated_blob index={} count={count}",
                index
            );
        };
        let Ok(comment) = read_ssh_string(&mut cursor) else {
            return format!(
                "identities_answer_parse_error=truncated_comment index={} count={count}",
                index
            );
        };
        parts.push(format!(
            "#{index}:blobLen={},commentLen={},embeddedType={}",
            blob.len(),
            comment.len(),
            decode_embedded_key_type(blob).unwrap_or("unknown"),
        ));
    }

    if !cursor.is_empty() {
        parts.push(format!("trailingBytes={}", cursor.len()));
    }

    format!("identities={count} {}", parts.join(" "))
}

fn inspect_sign_request(payload: &[u8]) -> String {
    let mut cursor = payload;
    let Ok(blob) = read_ssh_string(&mut cursor) else {
        return "sign_request_parse_error=missing_blob".to_string();
    };
    let Ok(data) = read_ssh_string(&mut cursor) else {
        return "sign_request_parse_error=missing_data".to_string();
    };
    let Ok(flags) = read_u32(&mut cursor) else {
        return "sign_request_parse_error=missing_flags".to_string();
    };

    format!(
        "sign_request blobLen={} embeddedType={} dataLen={} flags={} trailingBytes={}",
        blob.len(),
        decode_embedded_key_type(blob).unwrap_or("unknown"),
        data.len(),
        flags,
        cursor.len(),
    )
}

fn inspect_sign_response(payload: &[u8]) -> String {
    let mut cursor = payload;
    let Ok(signature_blob) = read_ssh_string(&mut cursor) else {
        return "sign_response_parse_error=missing_signature_blob".to_string();
    };
    let mut signature_cursor = signature_blob;
    let algorithm = read_ssh_string(&mut signature_cursor)
        .ok()
        .and_then(|bytes| std::str::from_utf8(bytes).ok())
        .unwrap_or("unknown");
    let signature_len = read_ssh_string(&mut signature_cursor)
        .map(|bytes| bytes.len())
        .unwrap_or(0);

    format!(
        "sign_response signatureBlobLen={} algorithm={} signatureLen={} trailingBytes={}",
        signature_blob.len(),
        algorithm,
        signature_len,
        cursor.len(),
    )
}

fn decode_embedded_key_type(blob: &[u8]) -> Option<&str> {
    let mut cursor = blob;
    let key_type = read_ssh_string(&mut cursor).ok()?;
    std::str::from_utf8(key_type).ok()
}

fn describe_caller(caller: Option<&CallerIdentity>) -> String {
    let Some(caller) = caller else {
        return "none".to_string();
    };

    format!(
        "pid={},uid={},gid={},process={},exe={}",
        caller
            .pid
            .map(|value| value.to_string())
            .unwrap_or_else(|| "none".to_string()),
        caller.uid,
        caller.gid,
        caller
            .process_name
            .as_deref()
            .filter(|value| !value.is_empty())
            .unwrap_or("none"),
        caller
            .executable_path
            .as_deref()
            .filter(|value| !value.is_empty())
            .unwrap_or("none"),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use keyguard_android_agent_bridge::secure_transport;
    use std::fs;
    use std::time::{SystemTime, UNIX_EPOCH};
    use tokio::time::sleep;

    struct TestDir {
        path: std::path::PathBuf,
    }

    impl TestDir {
        fn new() -> Self {
            let unique = format!(
                "kg-{:x}-{:x}",
                std::process::id(),
                SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .unwrap()
                    .as_nanos()
            );
            let path = std::path::PathBuf::from("/tmp").join(unique);
            fs::create_dir_all(&path).unwrap();
            Self { path }
        }

        fn path(&self) -> &Path {
            &self.path
        }
    }

    impl Drop for TestDir {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.path);
        }
    }

    #[test]
    fn inspect_identities_answer_reports_identity_count_and_metadata() {
        let key_type = b"ssh-ed25519";
        let key_blob = encode_ssh_string(key_type);
        let comment = b"test-key";
        let payload = [
            1u32.to_be_bytes().as_slice(),
            encode_ssh_string(&key_blob).as_slice(),
            encode_ssh_string(comment).as_slice(),
        ]
        .concat();
        let packet = [[SSH_AGENT_IDENTITIES_ANSWER].as_slice(), payload.as_slice()].concat();

        let summary = describe_agent_packet(&packet);

        assert!(summary.contains("type=12(identities_answer)"));
        assert!(summary.contains("identities=1"));
        assert!(summary.contains("embeddedType=ssh-ed25519"));
        assert!(summary.contains("commentLen=8"));
    }

    #[test]
    fn inspect_sign_request_reports_lengths_and_algorithm() {
        let key_blob = encode_ssh_string(b"ssh-ed25519");
        let data = b"payload";
        let flags = 7u32.to_be_bytes();
        let payload = [
            encode_ssh_string(&key_blob).as_slice(),
            encode_ssh_string(data).as_slice(),
            flags.as_slice(),
        ]
        .concat();
        let packet = [[SSH_AGENT_SIGN_REQUEST].as_slice(), payload.as_slice()].concat();

        let summary = describe_agent_packet(&packet);

        assert!(summary.contains("type=13(sign_request)"));
        assert!(summary.contains("embeddedType=ssh-ed25519"));
        assert!(summary.contains("dataLen=7"));
        assert!(summary.contains("flags=7"));
    }

    #[tokio::test]
    async fn bridge_activation_waits_for_a_forwardable_complete_packet() {
        let (mut server, mut client) = tokio::io::duplex(128);
        let activation = tokio::spawn(async move {
            read_next_forward_request(
                &mut server,
                7,
                None,
                false,
                Duration::from_secs(1),
                Duration::from_secs(1),
            )
            .await
        });

        write_ssh_agent_packet(&mut client, &[0xff], MAX_FRAME_PAYLOAD_SIZE)
            .await
            .unwrap();
        let failure = read_ssh_agent_packet(&mut client, MAX_FRAME_PAYLOAD_SIZE)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(failure, build_failure_packet());
        assert!(!activation.is_finished());

        write_ssh_agent_packet(
            &mut client,
            &[SSH_AGENT_REQUEST_IDENTITIES],
            MAX_FRAME_PAYLOAD_SIZE,
        )
        .await
        .unwrap();
        let request = activation.await.unwrap().unwrap().unwrap();
        assert_eq!(request.id, 7);
    }

    #[tokio::test]
    async fn incomplete_client_packet_has_an_absolute_deadline() {
        let (mut server, mut client) = tokio::io::duplex(64);
        tokio::io::AsyncWriteExt::write_all(&mut client, &4u32.to_be_bytes())
            .await
            .unwrap();
        tokio::io::AsyncWriteExt::write_all(&mut client, &[SSH_AGENT_REQUEST_IDENTITIES])
            .await
            .unwrap();

        let err = read_next_forward_request(
            &mut server,
            1,
            None,
            false,
            Duration::from_millis(25),
            Duration::from_secs(1),
        )
        .await
        .unwrap_err();

        assert!(err
            .to_string()
            .contains("Timed out waiting for a complete SSH client packet"));
    }

    #[tokio::test]
    async fn blocked_client_response_write_has_an_absolute_deadline() {
        let (mut server, _client) = tokio::io::duplex(1);
        let packet = vec![0x11; 64];

        let err = write_client_packet(&mut server, &packet, Duration::from_millis(25))
            .await
            .unwrap_err();

        assert!(err
            .to_string()
            .contains("Timed out writing an SSH client response"));
    }

    #[tokio::test]
    async fn rejected_broadcast_returns_ssh_agent_failure() {
        let (mut server, mut client) = tokio::io::duplex(64);

        write_client_failure(&mut server).await.unwrap();
        let packet = read_ssh_agent_packet(&mut client, MAX_FRAME_PAYLOAD_SIZE)
            .await
            .unwrap()
            .unwrap();

        assert_eq!(packet, build_failure_packet());
    }

    #[tokio::test]
    async fn supervised_command_receives_termination_on_shutdown() {
        let temp = TestDir::new();
        let marker_path = temp.path().join("terminated.txt");
        let marker_dir_arg = temp.path().display().to_string();
        let command = vec![
            OsString::from("sh"),
            OsString::from("-c"),
            OsString::from(
                "trap 'printf terminated > \"$1\"/terminated.txt; exit 0' TERM; while :; do sleep 1; done",
            ),
            OsString::from("sh"),
            OsString::from(marker_dir_arg),
        ];
        let socket_path = temp.path().join("agent.sock");
        let mut server = tokio::spawn(async move { std::future::pending::<Result<()>>().await });
        let mut shutdown = tokio::spawn(async move {
            sleep(Duration::from_millis(100)).await;
            Ok(())
        });

        let exit_code =
            run_command_with_supervision(command, &socket_path, &mut server, &mut shutdown)
                .await
                .unwrap();

        assert_eq!(exit_code, 1);
        let marker = wait_for_test_condition(|| marker_path.exists(), Duration::from_secs(2)).await;
        assert!(marker);
    }

    #[tokio::test]
    async fn transport_errors_can_be_wrapped_with_context() {
        let transport_error = secure_transport::TransportError::Poisoned;
        let result: std::result::Result<(), secure_transport::TransportError> =
            Err(transport_error);
        let err = result.context("transport failed").unwrap_err();
        assert!(err.to_string().contains("transport failed"));
    }

    async fn wait_for_test_condition(check: impl Fn() -> bool, timeout_duration: Duration) -> bool {
        timeout(timeout_duration, async {
            loop {
                if check() {
                    return true;
                }
                sleep(Duration::from_millis(25)).await;
            }
        })
        .await
        .unwrap_or(false)
    }

    fn encode_ssh_string(value: &[u8]) -> Vec<u8> {
        let mut out = Vec::with_capacity(4 + value.len());
        out.extend_from_slice(&(value.len() as u32).to_be_bytes());
        out.extend_from_slice(value);
        out
    }
}
