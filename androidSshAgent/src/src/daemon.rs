use anyhow::{Context, Result};
use std::ffi::OsString;
use std::io::{self, ErrorKind};
use std::path::Path;
use tokio::net::{TcpListener, UnixListener, UnixStream};
use tokio::process::{Child, Command};
use tokio::signal::unix::{signal, SignalKind};
use tokio::task::JoinHandle;
use tokio::time::{sleep, timeout, timeout_at, Duration, Instant};

use crate::broadcast::{AndroidBroadcastLauncher, BroadcastCommandSpec};
use crate::caller_identity::{caller_from_unix_stream, CallerIdentity};
use crate::packet_io::{read_ssh_agent_packet, write_ssh_agent_packet};
use crate::process::{terminate_pid, AGENT_PID_ENV, AUTH_SOCK_ENV};
use crate::rpc::{
    build_failure_packet, decode_protobuf_response, encode_protobuf_request,
    translate_client_packet, translate_server_response, RequestTranslation, SSH_AGENT_FAILURE,
    SSH_AGENT_IDENTITIES_ANSWER, SSH_AGENT_REQUEST_IDENTITIES, SSH_AGENT_SIGN_REQUEST,
    SSH_AGENT_SIGN_RESPONSE,
};
use crate::secure_transport::{
    SecureTransport, MAX_FRAME_PAYLOAD_SIZE, SESSION_ID_LEN, SESSION_SECRET_LEN,
};

const ACCEPT_RETRY_DELAY: Duration = Duration::from_millis(100);
const CHILD_TERMINATION_TIMEOUT: Duration = Duration::from_secs(1);

#[derive(Clone)]
pub(crate) struct Config {
    pub(crate) android_component: String,
    pub(crate) connect_timeout: Duration,
    pub(crate) debug: bool,
    pub(crate) socket_path: std::path::PathBuf,
    pub(crate) broadcast_command: BroadcastCommandSpec,
}

pub(crate) fn run_blocking(
    listener: std::os::unix::net::UnixListener,
    cmd: Option<Vec<OsString>>,
    config: Config,
) -> Result<i32> {
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .context("Failed to create Tokio runtime")?;
    runtime.block_on(async move { run(listener, cmd, config).await })
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
    loop {
        match listener.accept().await {
            Ok((client, _)) => {
                let config = config.clone();
                tokio::spawn(async move {
                    let debug = config.debug;
                    if let Err(err) = handle_client_connection(client, config).await {
                        log_debug(debug, &format!("SSH client session failed: {err:#}"));
                    }
                });
            }
            Err(err) if is_retryable_accept_error(&err) => {
                log_debug(
                    config.debug,
                    &format!("Transient SSH client accept failure: {err}"),
                );
                sleep(ACCEPT_RETRY_DELAY).await;
            }
            Err(err) => {
                return Err(err).context("Failed to accept SSH client connection");
            }
        }
    }
}

fn is_retryable_accept_error(err: &io::Error) -> bool {
    matches!(
        err.kind(),
        ErrorKind::Interrupted
            | ErrorKind::WouldBlock
            | ErrorKind::TimedOut
            | ErrorKind::ConnectionAborted
            | ErrorKind::ConnectionReset
    )
}

async fn handle_client_connection(mut client: UnixStream, config: Config) -> Result<()> {
    let caller = caller_from_unix_stream(&client);
    let app_listener = TcpListener::bind(("127.0.0.1", 0))
        .await
        .context("Failed to bind localhost proxy listener")?;
    let proxy_port = app_listener
        .local_addr()
        .context("Failed to read proxy listener address")?
        .port();
    log_debug(
        config.debug,
        &format!(
            "SSH client connected: caller={}, proxy_port={proxy_port}",
            describe_caller(caller.as_ref())
        ),
    );

    let deadline = Instant::now() + config.connect_timeout;
    let session_id = random_array::<SESSION_ID_LEN>();
    let session_secret = random_array::<SESSION_SECRET_LEN>();
    AndroidBroadcastLauncher::new(config.broadcast_command.clone())
        .launch(
            &config.android_component,
            proxy_port,
            &session_id,
            &session_secret,
            deadline,
        )
        .await
        .context("Failed to send Android SSH agent broadcast")?;

    let (app_stream, _) = timeout_at(deadline, app_listener.accept())
        .await
        .context("Timed out waiting for the Keyguard app to connect")?
        .context("Failed to accept the Keyguard app connection")?;
    log_debug(
        config.debug,
        &format!("Keyguard app connected to proxy port {proxy_port}"),
    );

    let mut transport = timeout_at(
        deadline,
        SecureTransport::connect_as_tool(app_stream, &session_id, &session_secret),
    )
    .await
    .context("Timed out during SSH agent handshake")?
    .context("SSH agent handshake failed")?;
    log_debug(config.debug, "SSH agent handshake established");

    let mut next_request_id = 1u64;
    while let Some(packet) = read_ssh_agent_packet(&mut client, MAX_FRAME_PAYLOAD_SIZE).await? {
        log_debug(
            config.debug,
            &format!("SSH client request: {}", describe_agent_packet(&packet)),
        );
        match translate_client_packet(&packet, next_request_id, caller.as_ref()) {
            RequestTranslation::Failure => {
                let response = build_failure_packet();
                log_debug(
                    config.debug,
                    &format!("SSH client response: {}", describe_agent_packet(&response)),
                );
                write_ssh_agent_packet(&mut client, &response, MAX_FRAME_PAYLOAD_SIZE).await?;
                continue;
            }
            RequestTranslation::Forward(request) => {
                let request_id = request.id;
                let request_bytes = encode_protobuf_request(&request);
                transport
                    .write_packet(&request_bytes)
                    .await
                    .context("Failed to send protobuf request to the Keyguard app")?;
                let response_bytes = transport
                    .read_packet()
                    .await
                    .context("Failed to read protobuf response from the Keyguard app")?
                    .ok_or_else(|| anyhow::anyhow!("Keyguard app disconnected before replying"))?;
                let response = decode_protobuf_response(&response_bytes)
                    .context("Failed to decode protobuf response from the Keyguard app")?;
                let ssh_response = translate_server_response(request_id, response)
                    .context("Failed to translate protobuf response into SSH agent packet")?;
                log_debug(
                    config.debug,
                    &format!(
                        "SSH client response: {}",
                        describe_agent_packet(&ssh_response)
                    ),
                );
                write_ssh_agent_packet(&mut client, &ssh_response, MAX_FRAME_PAYLOAD_SIZE).await?;
                next_request_id = next_request_id
                    .checked_add(1)
                    .context("SSH agent request id overflow")?;
            }
        }
    }

    Ok(())
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

fn read_u32(cursor: &mut &[u8]) -> std::result::Result<u32, &'static str> {
    if cursor.len() < 4 {
        return Err("missing_u32");
    }

    let mut len_bytes = [0u8; 4];
    len_bytes.copy_from_slice(&cursor[..4]);
    *cursor = &cursor[4..];
    Ok(u32::from_be_bytes(len_bytes))
}

fn read_ssh_string<'a>(cursor: &mut &'a [u8]) -> std::result::Result<&'a [u8], &'static str> {
    let len = read_u32(cursor)? as usize;
    if cursor.len() < len {
        return Err("truncated_string");
    }
    let (value, rest) = cursor.split_at(len);
    *cursor = rest;
    Ok(value)
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

async fn wait_for_shutdown_signal() -> Result<()> {
    let mut sighup = signal(SignalKind::hangup()).context("Failed to subscribe to SIGHUP")?;
    let mut sigint = signal(SignalKind::interrupt()).context("Failed to subscribe to SIGINT")?;
    let mut sigterm = signal(SignalKind::terminate()).context("Failed to subscribe to SIGTERM")?;
    tokio::select! {
        _ = sighup.recv() => {}
        _ = sigint.recv() => {}
        _ = sigterm.recv() => {}
    }
    Ok(())
}

fn random_array<const N: usize>() -> [u8; N] {
    let mut out = [0u8; N];
    rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut out);
    out
}

fn log_debug(enabled: bool, message: &str) {
    if enabled {
        eprintln!("{message}");
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::secure_transport;
    use std::fs;
    use std::time::{SystemTime, UNIX_EPOCH};

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

    #[test]
    fn accept_error_classification_keeps_retryable_errors_local() {
        assert!(is_retryable_accept_error(&io::Error::from(
            ErrorKind::Interrupted
        )));
        assert!(is_retryable_accept_error(&io::Error::from(
            ErrorKind::ConnectionAborted
        )));
        assert!(!is_retryable_accept_error(&io::Error::from(
            ErrorKind::PermissionDenied
        )));
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
