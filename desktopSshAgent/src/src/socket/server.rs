//! Bounded SSH agent protocol server shared by Unix sockets and Windows pipes.

use ssh_agent_lib::agent::{Agent, ListeningSocket, Session};
use ssh_agent_lib::error::AgentError;
use ssh_agent_lib::proto::{ProtoError, Request, Response};
use ssh_encoding::{Decode, Encode};
use std::fmt;
use std::future::Future;
use std::io;
use std::sync::Arc;
use std::time::Duration;
use thiserror::Error;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::sync::{OwnedSemaphorePermit, Semaphore};
use tokio::task::{JoinError, JoinSet};
use tokio::time::{timeout, timeout_at, Instant};
use tracing::{debug, warn};

// Match OpenSSH's 256 KiB maximum accepted agent message.
const MAX_AGENT_FRAME_LEN: usize = 256 * 1024;
// Keep per-client memory and task use bounded even through agent forwarding.
const MAX_CONCURRENT_CONNECTIONS: usize = 32;
#[cfg(unix)]
// Leave room for the listener, IPC, logging, runtime, and transient files.
const NON_AGENT_FD_RESERVE: usize = 16;
#[cfg(all(unix, target_os = "macos"))]
// Include the public socket and peak retained/transient identity descriptors.
const AGENT_FDS_PER_CONNECTION: usize =
    1 + keyguard_agent_identity::macos::MacosPeerIdentity::MAX_ADDITIONAL_FD_COUNT;
#[cfg(all(unix, target_os = "linux"))]
// Linux sessions retain both a pidfd and an O_PATH executable handle.
const AGENT_FDS_PER_CONNECTION: usize =
    1 + keyguard_agent_identity::linux_identity::LinuxProcessIdentity::RETAINED_FD_COUNT;
#[cfg(all(unix, not(any(target_os = "linux", target_os = "macos"))))]
const AGENT_FDS_PER_CONNECTION: usize = 1;
const CONNECTION_IDLE_TIMEOUT: Duration = Duration::from_secs(5 * 60);
const FRAME_COMPLETION_TIMEOUT: Duration = Duration::from_secs(30);
const RESPONSE_WRITE_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Clone, Copy, Debug)]
struct ServerLimits {
    max_frame_len: usize,
    max_connections: usize,
    idle_timeout: Duration,
    frame_timeout: Duration,
    write_timeout: Duration,
}

impl Default for ServerLimits {
    fn default() -> Self {
        Self {
            max_frame_len: MAX_AGENT_FRAME_LEN,
            max_connections: default_max_connections(),
            idle_timeout: CONNECTION_IDLE_TIMEOUT,
            frame_timeout: FRAME_COMPLETION_TIMEOUT,
            write_timeout: RESPONSE_WRITE_TIMEOUT,
        }
    }
}

#[cfg(not(unix))]
fn default_max_connections() -> usize {
    MAX_CONCURRENT_CONNECTIONS
}

#[cfg(unix)]
fn default_max_connections() -> usize {
    let mut file_limit = std::mem::MaybeUninit::<libc::rlimit>::uninit();
    // SAFETY: `getrlimit` initializes the supplied `rlimit` on success. The
    // pointer is valid for writes and checked before `assume_init`.
    if unsafe { libc::getrlimit(libc::RLIMIT_NOFILE, file_limit.as_mut_ptr()) } != 0 {
        return MAX_CONCURRENT_CONNECTIONS;
    }

    // SAFETY: the successful `getrlimit` call above initialized `file_limit`.
    let soft_limit = unsafe { file_limit.assume_init() }.rlim_cur;
    if soft_limit == libc::RLIM_INFINITY {
        return MAX_CONCURRENT_CONNECTIONS;
    }

    let soft_limit = usize::try_from(soft_limit).unwrap_or(usize::MAX);
    max_connections_for_soft_limit(soft_limit)
}

#[cfg(unix)]
fn max_connections_for_soft_limit(soft_limit: usize) -> usize {
    MAX_CONCURRENT_CONNECTIONS
        .min(soft_limit.saturating_sub(NON_AGENT_FD_RESERVE) / AGENT_FDS_PER_CONNECTION)
        .max(1)
}

#[derive(Debug, Error)]
enum ConnectionError {
    #[error("client was idle for longer than {0:?}")]
    IdleTimeout(Duration),

    #[error("client did not complete a frame within {0:?}")]
    FrameTimeout(Duration),

    #[error("client did not read a response within {0:?}")]
    WriteTimeout(Duration),

    #[error("invalid SSH agent frame length {length}; expected 1..={max}")]
    InvalidFrameLength { length: usize, max: usize },

    #[error("SSH agent request has {remaining} trailing bytes")]
    TrailingRequestData { remaining: usize },

    #[error("SSH agent response is too large: {length} bytes (maximum {max})")]
    ResponseTooLarge { length: usize, max: usize },

    #[error(
        "SSH agent response encoded length mismatch: expected {expected} bytes, encoded {actual}"
    )]
    ResponseLengthMismatch { expected: usize, actual: usize },

    #[error("SSH agent protocol error: {0}")]
    Protocol(#[from] ProtoError),

    #[error("SSH agent encoding error: {0}")]
    Encoding(#[from] ssh_encoding::Error),

    #[error("SSH agent I/O error: {0}")]
    Io(#[from] io::Error),
}

/// Runs a bounded listener until the supplied shutdown future completes.
pub(super) async fn listen_until<L, A, F, R>(
    listener: L,
    agent: A,
    shutdown: F,
) -> Result<R, AgentError>
where
    L: ListeningSocket + fmt::Debug + Send,
    A: Agent<L>,
    F: Future<Output = R>,
{
    listen_until_with_limits(listener, agent, shutdown, ServerLimits::default()).await
}

async fn listen_until_with_limits<L, A, F, R>(
    mut listener: L,
    mut agent: A,
    shutdown: F,
    limits: ServerLimits,
) -> Result<R, AgentError>
where
    L: ListeningSocket + fmt::Debug + Send,
    A: Agent<L>,
    F: Future<Output = R>,
{
    if limits.max_connections == 0 {
        return Err(AgentError::other(io::Error::new(
            io::ErrorKind::InvalidInput,
            "maximum SSH agent connections must be greater than zero",
        )));
    }

    let connection_limit = Arc::new(Semaphore::new(limits.max_connections));
    let mut tasks = JoinSet::new();
    tokio::pin!(shutdown);

    let outcome = loop {
        reap_completed(&mut tasks);

        // Acquire before accept so excess clients remain in the bounded OS
        // backlog instead of consuming application sockets and Tokio tasks.
        let permit = tokio::select! {
            reason = &mut shutdown => break Ok(reason),
            permit = Arc::clone(&connection_limit).acquire_owned() => {
                match permit {
                    Ok(permit) => permit,
                    Err(_) => {
                        break Err(AgentError::other(io::Error::other(
                            "SSH agent connection limiter closed unexpectedly",
                        )));
                    }
                }
            }
        };

        let stream = tokio::select! {
            reason = &mut shutdown => {
                drop(permit);
                break Ok(reason);
            }
            accepted = listener.accept() => {
                match accepted {
                    Ok(stream) => stream,
                    Err(error) => {
                        drop(permit);
                        break Err(AgentError::IO(error));
                    }
                }
            }
        };

        let session = agent.new_session(&stream);
        tasks.spawn(serve_connection(stream, session, permit, limits));
    };

    tasks.abort_all();
    while let Some(result) = tasks.join_next().await {
        log_task_result(result);
    }

    outcome
}

async fn serve_connection<S, T>(
    mut stream: S,
    mut session: T,
    _permit: OwnedSemaphorePermit,
    limits: ServerLimits,
) -> Result<(), ConnectionError>
where
    S: AsyncRead + AsyncWrite + Send + Unpin + 'static,
    T: Session,
{
    loop {
        let Some(frame) = read_frame(&mut stream, limits).await? else {
            return Ok(());
        };

        let message_id = frame[0];
        let frame_len = frame.len();
        debug!(message_id, frame_len, "Received SSH agent request");

        // Keyguard intentionally implements only identity listing, signing,
        // and extensions. Refuse all other well-framed commands without
        // decoding private-key or passphrase-bearing request bodies.
        let response = if is_supported_request(message_id) {
            let request = decode_request(&frame)?;
            drop(frame);
            // Do not wrap the handler in an outer timeout. Keyguard uses a
            // shared request/response IPC stream; cancelling between its
            // write and read would desynchronize that stream. Handler
            // deadlines therefore live in the IPC layer and force reconnect.
            handle_request(&mut session, request).await
        } else {
            debug!(message_id, "Refusing unsupported SSH agent request");
            Response::Failure
        };

        let encoded = match encode_response_frame(&response, limits.max_frame_len) {
            Ok(encoded) => encoded,
            Err(error) => {
                // The client sent a valid request, so return a valid failure
                // response if the backend produced an unusable response.
                warn!(
                    message_id,
                    error_kind = response_error_kind(&error),
                    "SSH agent response could not be encoded within protocol limits"
                );
                encode_response_frame(&Response::Failure, limits.max_frame_len)?
            }
        };

        write_frame(&mut stream, &encoded, limits.write_timeout).await?;
    }
}

async fn read_frame<S>(
    stream: &mut S,
    limits: ServerLimits,
) -> Result<Option<Vec<u8>>, ConnectionError>
where
    S: AsyncRead + Unpin,
{
    let mut header = [0u8; 4];
    let first_byte_count = timeout(limits.idle_timeout, stream.read(&mut header[..1]))
        .await
        .map_err(|_| ConnectionError::IdleTimeout(limits.idle_timeout))??;

    if first_byte_count == 0 {
        return Ok(None);
    }

    let deadline = Instant::now() + limits.frame_timeout;
    timeout_at(deadline, stream.read_exact(&mut header[1..]))
        .await
        .map_err(|_| ConnectionError::FrameTimeout(limits.frame_timeout))??;

    let length = u32::from_be_bytes(header) as usize;
    if length == 0 || length > limits.max_frame_len {
        return Err(ConnectionError::InvalidFrameLength {
            length,
            max: limits.max_frame_len,
        });
    }

    let mut frame = vec![0u8; length];
    timeout_at(deadline, stream.read_exact(&mut frame))
        .await
        .map_err(|_| ConnectionError::FrameTimeout(limits.frame_timeout))??;

    Ok(Some(frame))
}

fn decode_request(frame: &[u8]) -> Result<Request, ConnectionError> {
    let mut reader = frame;
    let request = Request::decode(&mut reader)?;
    if !reader.is_empty() {
        return Err(ConnectionError::TrailingRequestData {
            remaining: reader.len(),
        });
    }
    Ok(request)
}

async fn handle_request<T: Session>(session: &mut T, request: Request) -> Response {
    match session.handle(request).await {
        Ok(response) => response,
        Err(AgentError::ExtensionFailure) => Response::ExtensionFailure,
        Err(error) => {
            debug!(error = %error, "SSH agent request failed");
            Response::Failure
        }
    }
}

fn encode_response_frame(
    response: &Response,
    max_frame_len: usize,
) -> Result<Vec<u8>, ConnectionError> {
    let expected_len = response.encoded_len()?;
    if expected_len == 0 || expected_len > max_frame_len {
        return Err(ConnectionError::ResponseTooLarge {
            length: expected_len,
            max: max_frame_len,
        });
    }

    let wire_len = u32::try_from(expected_len).map_err(|_| ConnectionError::ResponseTooLarge {
        length: expected_len,
        max: max_frame_len,
    })?;
    let mut frame = Vec::with_capacity(4 + expected_len);
    frame.extend_from_slice(&wire_len.to_be_bytes());
    response.encode(&mut frame)?;

    let actual_len = frame.len() - 4;
    if actual_len != expected_len {
        return Err(ConnectionError::ResponseLengthMismatch {
            expected: expected_len,
            actual: actual_len,
        });
    }
    if actual_len > max_frame_len {
        return Err(ConnectionError::ResponseTooLarge {
            length: actual_len,
            max: max_frame_len,
        });
    }

    Ok(frame)
}

async fn write_frame<S>(
    stream: &mut S,
    frame: &[u8],
    write_timeout: Duration,
) -> Result<(), ConnectionError>
where
    S: AsyncWrite + Unpin,
{
    timeout(write_timeout, async {
        stream.write_all(frame).await?;
        stream.flush().await
    })
    .await
    .map_err(|_| ConnectionError::WriteTimeout(write_timeout))??;
    Ok(())
}

fn is_supported_request(message_id: u8) -> bool {
    matches!(message_id, 11 | 13 | 27)
}

fn reap_completed(tasks: &mut JoinSet<Result<(), ConnectionError>>) {
    while let Some(result) = tasks.try_join_next() {
        log_task_result(result);
    }
}

fn log_task_result(result: Result<Result<(), ConnectionError>, JoinError>) {
    match result {
        Ok(Ok(())) => {}
        Ok(Err(error)) => {
            debug!(
                error_kind = connection_error_kind(&error),
                "SSH agent client connection closed"
            );
        }
        Err(error) if error.is_cancelled() => {}
        Err(error) => warn!(error = %error, "SSH agent client task failed"),
    }
}

fn connection_error_kind(error: &ConnectionError) -> &'static str {
    match error {
        ConnectionError::IdleTimeout(_) => "idle_timeout",
        ConnectionError::FrameTimeout(_) => "frame_timeout",
        ConnectionError::WriteTimeout(_) => "write_timeout",
        ConnectionError::InvalidFrameLength { .. } => "invalid_frame_length",
        ConnectionError::TrailingRequestData { .. } => "trailing_request_data",
        ConnectionError::ResponseTooLarge { .. } => "response_too_large",
        ConnectionError::ResponseLengthMismatch { .. } => "response_length_mismatch",
        ConnectionError::Protocol(_) => "protocol_error",
        ConnectionError::Encoding(_) => "encoding_error",
        ConnectionError::Io(_) => "io_error",
    }
}

fn response_error_kind(error: &ConnectionError) -> &'static str {
    match error {
        ConnectionError::ResponseTooLarge { .. } => "response_too_large",
        ConnectionError::ResponseLengthMismatch { .. } => "response_length_mismatch",
        ConnectionError::Encoding(_) => "encoding_error",
        _ => "unexpected_error",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ssh_agent_lib::proto::Extension;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use tokio::io::{duplex, DuplexStream};
    use tokio::sync::{mpsc, oneshot};
    use tokio::task::yield_now;
    use tokio::time::advance;

    fn test_limits(max_frame_len: usize) -> ServerLimits {
        ServerLimits {
            max_frame_len,
            max_connections: 4,
            idle_timeout: Duration::from_secs(60),
            frame_timeout: Duration::from_secs(10),
            write_timeout: Duration::from_secs(10),
        }
    }

    async fn test_permit() -> OwnedSemaphorePermit {
        Arc::new(Semaphore::new(1))
            .acquire_owned()
            .await
            .expect("test semaphore must remain open")
    }

    async fn write_test_frame(stream: &mut DuplexStream, payload: &[u8]) {
        let length = u32::try_from(payload.len()).expect("test payload length must fit in u32");
        stream
            .write_all(&length.to_be_bytes())
            .await
            .expect("test frame header must be writable");
        stream
            .write_all(payload)
            .await
            .expect("test frame payload must be writable");
    }

    async fn read_test_frame(stream: &mut DuplexStream) -> Vec<u8> {
        let mut header = [0u8; 4];
        stream
            .read_exact(&mut header)
            .await
            .expect("test response header must be readable");
        let length = u32::from_be_bytes(header) as usize;
        let mut payload = vec![0u8; length];
        stream
            .read_exact(&mut payload)
            .await
            .expect("test response payload must be readable");
        payload
    }

    struct ShortOpaqueKeyReader {
        prefix_read: bool,
        largest_read: usize,
    }

    impl ssh_encoding::Reader for ShortOpaqueKeyReader {
        fn read<'o>(&mut self, out: &'o mut [u8]) -> ssh_encoding::Result<&'o [u8]> {
            self.largest_read = self.largest_read.max(out.len());
            if !self.prefix_read && out.len() == 4 {
                out.copy_from_slice(&[0, 0x0f, 0xff, 0xff]);
                self.prefix_read = true;
                Ok(out)
            } else {
                Err(ssh_encoding::Error::Length)
            }
        }

        fn remaining_len(&self) -> usize {
            if self.prefix_read {
                1
            } else {
                5
            }
        }
    }

    #[tokio::test]
    async fn frame_length_boundaries_are_enforced_before_body_read() {
        let limits = test_limits(8);

        for length in [0, 9, u32::MAX] {
            let (mut client, mut server) = duplex(8);
            client
                .write_all(&length.to_be_bytes())
                .await
                .expect("test header must be writable");

            let error = read_frame(&mut server, limits)
                .await
                .expect_err("invalid length must be rejected from the header alone");
            assert!(matches!(
                error,
                ConnectionError::InvalidFrameLength {
                    length: actual,
                    max: 8
                } if actual == length as usize
            ));
        }

        let (mut client, mut server) = duplex(16);
        write_test_frame(&mut client, &[11; 8]).await;
        assert_eq!(
            read_frame(&mut server, limits).await.unwrap(),
            Some(vec![11; 8])
        );
    }

    #[test]
    fn request_decode_requires_exact_frame_contents() {
        assert_eq!(decode_request(&[11]).unwrap(), Request::RequestIdentities);

        let error = decode_request(&[11, 0])
            .expect_err("data after a complete request must not bleed into another frame");
        assert!(matches!(
            error,
            ConnectionError::TrailingRequestData { remaining: 1 }
        ));
    }

    #[test]
    fn nested_length_cannot_exceed_its_enclosing_field() {
        // The extension-name field claims a 1 MiB string inside a six-byte
        // frame. Reject it before Vec::decode reaches its allocation.
        let malicious_extension = [27, 0, 0x0f, 0xff, 0xff, 0xaa];
        let error = decode_request(&malicious_extension)
            .expect_err("nested length must be bounded by its enclosing frame");
        assert!(
            matches!(
                error,
                ConnectionError::Protocol(ProtoError::SshEncoding(ssh_encoding::Error::Length))
            ),
            "unexpected error: {error:?}"
        );
    }

    #[test]
    fn opaque_key_length_is_checked_before_body_allocation() {
        let mut reader = ShortOpaqueKeyReader {
            prefix_read: false,
            largest_read: 0,
        };

        let result = ssh_key::public::OpaquePublicKeyBytes::decode(&mut reader);
        assert!(result.is_err());
        assert_eq!(reader.largest_read, 4);
    }

    #[cfg(unix)]
    #[test]
    fn connection_cap_preserves_file_descriptor_reserve() {
        assert_eq!(max_connections_for_soft_limit(1_024), 32);
        assert_eq!(
            max_connections_for_soft_limit(32),
            16 / AGENT_FDS_PER_CONNECTION,
        );
        assert_eq!(
            max_connections_for_soft_limit(NON_AGENT_FD_RESERVE + 2),
            (2 / AGENT_FDS_PER_CONNECTION).max(1),
        );
        assert_eq!(max_connections_for_soft_limit(16), 1);
        assert_eq!(max_connections_for_soft_limit(0), 1);
        assert_eq!(
            max_connections_for_soft_limit(NON_AGENT_FD_RESERVE + AGENT_FDS_PER_CONNECTION * 2,),
            2,
        );
    }

    #[tokio::test]
    async fn fragmented_and_coalesced_frames_are_read_exactly() {
        let limits = test_limits(4);
        let (mut fragmented_client, mut fragmented_server) = duplex(16);
        let fragmented = tokio::spawn(async move {
            read_frame(&mut fragmented_server, limits)
                .await
                .expect("fragmented frame must parse")
        });

        fragmented_client.write_all(&[0, 0]).await.unwrap();
        yield_now().await;
        fragmented_client.write_all(&[0, 4, 11, 1]).await.unwrap();
        yield_now().await;
        fragmented_client.write_all(&[2, 3]).await.unwrap();
        assert_eq!(fragmented.await.unwrap(), Some(vec![11, 1, 2, 3]));

        let (mut coalesced_client, mut coalesced_server) = duplex(16);
        coalesced_client
            .write_all(&[0, 0, 0, 1, 11, 0, 0, 0, 1, 11])
            .await
            .unwrap();
        assert_eq!(
            read_frame(&mut coalesced_server, limits).await.unwrap(),
            Some(vec![11])
        );
        assert_eq!(
            read_frame(&mut coalesced_server, limits).await.unwrap(),
            Some(vec![11])
        );
    }

    #[tokio::test(start_paused = true)]
    async fn partial_frame_timeout_is_absolute() {
        let limits = test_limits(8);
        let (mut client, mut server) = duplex(16);
        let reader = tokio::spawn(async move { read_frame(&mut server, limits).await });

        client.write_all(&[0]).await.unwrap();
        yield_now().await;
        advance(Duration::from_secs(6)).await;

        // Complete the header and only part of the body. The body read keeps
        // the original deadline rather than receiving another full timeout.
        client.write_all(&[0, 0, 4, 11]).await.unwrap();
        yield_now().await;
        advance(Duration::from_secs(5)).await;

        let error = reader
            .await
            .unwrap()
            .expect_err("partial frame must expire at the original deadline");
        assert!(matches!(
            error,
            ConnectionError::FrameTimeout(duration) if duration == limits.frame_timeout
        ));
    }

    #[tokio::test(start_paused = true)]
    async fn idle_and_write_deadlines_close_stalled_connections() {
        let limits = test_limits(8);
        let (_idle_client, mut idle_server) = duplex(8);
        let idle_reader = tokio::spawn(async move { read_frame(&mut idle_server, limits).await });
        yield_now().await;
        advance(limits.idle_timeout + Duration::from_secs(1)).await;
        assert!(matches!(
            idle_reader.await.unwrap().unwrap_err(),
            ConnectionError::IdleTimeout(duration) if duration == limits.idle_timeout
        ));

        let (mut blocked_server, _blocked_client) = duplex(1);
        let writer = tokio::spawn(async move {
            write_frame(&mut blocked_server, &[1, 2], limits.write_timeout).await
        });
        yield_now().await;
        advance(limits.write_timeout + Duration::from_secs(1)).await;
        assert!(matches!(
            writer.await.unwrap().unwrap_err(),
            ConnectionError::WriteTimeout(duration) if duration == limits.write_timeout
        ));
    }

    struct ExtensionFailureSession;

    #[ssh_agent_lib::async_trait]
    impl Session for ExtensionFailureSession {
        async fn handle(&mut self, _message: Request) -> Result<Response, AgentError> {
            Err(AgentError::ExtensionFailure)
        }
    }

    struct GenericFailureSession;

    #[ssh_agent_lib::async_trait]
    impl Session for GenericFailureSession {
        async fn handle(&mut self, _message: Request) -> Result<Response, AgentError> {
            Err(AgentError::Failure)
        }
    }

    #[tokio::test]
    async fn handler_errors_map_to_protocol_failure_types() {
        assert_eq!(
            handle_request(&mut ExtensionFailureSession, Request::RequestIdentities).await,
            Response::ExtensionFailure
        );
        assert_eq!(
            handle_request(&mut GenericFailureSession, Request::RequestIdentities).await,
            Response::Failure
        );
    }

    struct OversizedResponseSession;

    #[ssh_agent_lib::async_trait]
    impl Session for OversizedResponseSession {
        async fn handle(&mut self, _message: Request) -> Result<Response, AgentError> {
            Ok(Response::ExtensionResponse(Extension {
                name: "oversized.test".to_owned(),
                details: vec![0u8; 32].into(),
            }))
        }
    }

    #[tokio::test]
    async fn oversized_backend_response_falls_back_to_failure() {
        let limits = test_limits(8);
        let (mut client, server) = duplex(64);
        let connection = tokio::spawn(serve_connection(
            server,
            OversizedResponseSession,
            test_permit().await,
            limits,
        ));

        write_test_frame(&mut client, &[11]).await;
        assert_eq!(read_test_frame(&mut client).await, vec![5]);

        drop(client);
        assert!(connection.await.unwrap().is_ok());
    }

    struct CountingSession {
        calls: Arc<AtomicUsize>,
    }

    #[ssh_agent_lib::async_trait]
    impl Session for CountingSession {
        async fn request_identities(
            &mut self,
        ) -> Result<Vec<ssh_agent_lib::proto::Identity>, AgentError> {
            self.calls.fetch_add(1, Ordering::SeqCst);
            Ok(Vec::new())
        }
    }

    #[tokio::test]
    async fn supported_coalesced_requests_each_receive_one_response() {
        let calls = Arc::new(AtomicUsize::new(0));
        let session = CountingSession {
            calls: Arc::clone(&calls),
        };
        let limits = test_limits(8);
        let (mut client, server) = duplex(64);
        let connection = tokio::spawn(serve_connection(
            server,
            session,
            test_permit().await,
            limits,
        ));

        client
            .write_all(&[0, 0, 0, 1, 11, 0, 0, 0, 1, 11])
            .await
            .unwrap();
        assert_eq!(read_test_frame(&mut client).await, vec![12, 0, 0, 0, 0]);
        assert_eq!(read_test_frame(&mut client).await, vec![12, 0, 0, 0, 0]);
        assert_eq!(calls.load(Ordering::SeqCst), 2);

        drop(client);
        assert!(connection.await.unwrap().is_ok());
    }

    struct RawCountingSession {
        calls: Arc<AtomicUsize>,
    }

    #[ssh_agent_lib::async_trait]
    impl Session for RawCountingSession {
        async fn handle(&mut self, _message: Request) -> Result<Response, AgentError> {
            self.calls.fetch_add(1, Ordering::SeqCst);
            Ok(Response::Success)
        }
    }

    #[tokio::test]
    async fn unsupported_commands_are_refused_without_body_decode_or_handler_call() {
        let calls = Arc::new(AtomicUsize::new(0));
        let session = RawCountingSession {
            calls: Arc::clone(&calls),
        };
        let limits = test_limits(8);
        let (mut client, server) = duplex(64);
        let connection = tokio::spawn(serve_connection(
            server,
            session,
            test_permit().await,
            limits,
        ));

        // Command 22 is SSH_AGENTC_LOCK. Its body is intentionally malformed;
        // Keyguard rejects the command ID before parsing passphrase data.
        write_test_frame(&mut client, &[22, 0xff]).await;
        assert_eq!(read_test_frame(&mut client).await, vec![5]);
        assert_eq!(calls.load(Ordering::SeqCst), 0);

        drop(client);
        assert!(connection.await.unwrap().is_ok());
    }

    struct TestListener {
        streams: mpsc::Receiver<DuplexStream>,
        accepted: Arc<AtomicUsize>,
    }

    impl fmt::Debug for TestListener {
        fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
            formatter
                .debug_struct("TestListener")
                .finish_non_exhaustive()
        }
    }

    #[ssh_agent_lib::async_trait]
    impl ListeningSocket for TestListener {
        type Stream = DuplexStream;

        async fn accept(&mut self) -> io::Result<Self::Stream> {
            let stream =
                self.streams.recv().await.ok_or_else(|| {
                    io::Error::new(io::ErrorKind::BrokenPipe, "test listener closed")
                })?;
            self.accepted.fetch_add(1, Ordering::SeqCst);
            Ok(stream)
        }
    }

    struct AdmissionSession {
        active: Arc<AtomicUsize>,
    }

    impl Drop for AdmissionSession {
        fn drop(&mut self) {
            self.active.fetch_sub(1, Ordering::SeqCst);
        }
    }

    #[ssh_agent_lib::async_trait]
    impl Session for AdmissionSession {}

    struct TestAgent {
        created: Arc<AtomicUsize>,
        active: Arc<AtomicUsize>,
        max_active: Arc<AtomicUsize>,
    }

    impl Agent<TestListener> for TestAgent {
        fn new_session(&mut self, _socket: &DuplexStream) -> impl Session {
            self.created.fetch_add(1, Ordering::SeqCst);
            let active = self.active.fetch_add(1, Ordering::SeqCst) + 1;
            self.max_active.fetch_max(active, Ordering::SeqCst);
            AdmissionSession {
                active: Arc::clone(&self.active),
            }
        }
    }

    async fn wait_for_count(counter: &AtomicUsize, expected: usize) {
        timeout(Duration::from_secs(1), async {
            while counter.load(Ordering::SeqCst) < expected {
                yield_now().await;
            }
        })
        .await
        .expect("counter did not reach its expected value");
    }

    #[tokio::test]
    async fn connection_permit_is_acquired_before_accept_and_released_on_error() {
        let (stream_tx, stream_rx) = mpsc::channel(2);
        let accepted = Arc::new(AtomicUsize::new(0));
        let created = Arc::new(AtomicUsize::new(0));
        let active = Arc::new(AtomicUsize::new(0));
        let max_active = Arc::new(AtomicUsize::new(0));
        let listener = TestListener {
            streams: stream_rx,
            accepted: Arc::clone(&accepted),
        };
        let agent = TestAgent {
            created: Arc::clone(&created),
            active: Arc::clone(&active),
            max_active: Arc::clone(&max_active),
        };
        let mut limits = test_limits(8);
        limits.max_connections = 1;

        let (mut first_client, first_server) = duplex(16);
        let (_second_client, second_server) = duplex(16);
        stream_tx.send(first_server).await.unwrap();
        stream_tx.send(second_server).await.unwrap();

        let (shutdown_tx, shutdown_rx) = oneshot::channel();
        let listener_task = tokio::spawn(async move {
            listen_until_with_limits(
                listener,
                agent,
                async move {
                    let _ = shutdown_rx.await;
                },
                limits,
            )
            .await
        });

        wait_for_count(&accepted, 1).await;
        for _ in 0..20 {
            yield_now().await;
        }
        assert_eq!(accepted.load(Ordering::SeqCst), 1);
        assert_eq!(created.load(Ordering::SeqCst), 1);
        assert_eq!(active.load(Ordering::SeqCst), 1);

        // A protocol error ends the first task and releases its owned permit.
        first_client.write_all(&0u32.to_be_bytes()).await.unwrap();
        wait_for_count(&accepted, 2).await;
        wait_for_count(&created, 2).await;
        assert_eq!(max_active.load(Ordering::SeqCst), 1);

        shutdown_tx.send(()).unwrap();
        listener_task.await.unwrap().unwrap();
        assert_eq!(active.load(Ordering::SeqCst), 0);
    }
}
