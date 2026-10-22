//! A persistent client of an agent's private channel to the app. One
//! connection serves every request in turn; a broken one is replaced and
//! authenticated again with the launch token.

use crate::error::IpcError;
use crate::frame::{MAX_AGENT_FRAME, check_size, read_frame, write_frame};
use crate::handshake::{authenticate_request, check_authenticate_response};
use crate::launch::AuthToken;
use crate::stream::{self, BoxStream};
use anyhow::{Context, Result, anyhow, bail};
use keyguard_agent_proto::{AuthenticateRequest, AuthenticateResponse, ErrorResponse};
use prost::Message;
use std::future::Future;
use std::marker::PhantomData;
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::sync::Mutex;
use tracing::{info, trace, warn};
use zeroize::Zeroizing;

const RECONNECT_MAX_ATTEMPTS: u32 = 6;
const RECONNECT_INITIAL_DELAY_MS: u64 = 100;
const RECONNECT_MAX_DELAY_MS: u64 = 3_000;
const RECONNECT_MAX_JITTER_MS: u64 = 250;

/// How long one request may take, including the wait for the shared connection.
pub const REQUEST_TIMEOUT: Duration = Duration::from_secs(5 * 60);

/// How long connecting to the app or authenticating to it may take.
pub const AUTHENTICATION_TIMEOUT: Duration = Duration::from_secs(30);

/// A boxed, sendable future.
pub type BoxFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

/// The messages of one agent: an envelope with `id` = 1, and a oneof with
/// `authenticate` = 2 and `error` = 15.
pub trait Protocol: Send + Sync + 'static {
    /// The request envelope.
    type Request: Message;
    /// The response envelope.
    type Response: Message + Default;
    /// The oneof of a request.
    type Payload: Clone + Send + Sync + 'static;
    /// The oneof of a response.
    type Reply: Send + 'static;

    /// The request `id` that carries `payload`.
    fn request(id: u64, payload: Self::Payload) -> Self::Request;

    /// The payload that authenticates the agent.
    fn authenticate(request: AuthenticateRequest) -> Self::Payload;

    /// The id and payload of `response`.
    fn response(response: Self::Response) -> (u64, Option<Self::Reply>);

    /// The error that `reply` reports, or `reply` itself.
    fn error(reply: Self::Reply) -> Result<Self::Reply, ErrorResponse>;

    /// The answer to authentication, if `reply` is one.
    fn authenticated(reply: &Self::Reply) -> Option<&AuthenticateResponse>;

    /// Whether a request whose answer was lost can be sent again: it changes
    /// nothing and prompts no one.
    fn is_retry_safe(payload: &Self::Payload) -> bool;

    /// Zeroes the token of `request`, if it authenticates, once it is sent.
    fn wipe(request: &mut Self::Request);
}

/// Declares the unit struct `$name` and implements [`Protocol`] for it over
/// the prost messages in the module `$messages`: the `IpcRequest` and
/// `IpcResponse` envelopes, whose oneofs `ipc_request::Request` and
/// `ipc_response::Response` hold `Authenticate` and `Error`. Requests of the
/// `$retry_safe` variants may be sent again.
#[macro_export]
macro_rules! envelope_protocol {
    (
        $(#[$meta:meta])*
        $vis:vis struct $name:ident;
        messages = $($messages:ident)::+;
        retry_safe = $($retry_safe:ident)|+;
    ) => {
        $(#[$meta])*
        $vis struct $name;

        impl $crate::client::Protocol for $name {
            type Request = $($messages)::+::IpcRequest;
            type Response = $($messages)::+::IpcResponse;
            type Payload = $($messages)::+::ipc_request::Request;
            type Reply = $($messages)::+::ipc_response::Response;

            fn request(id: u64, payload: Self::Payload) -> Self::Request {
                $($messages)::+::IpcRequest {
                    id,
                    request: Some(payload),
                }
            }

            fn authenticate(request: $($messages)::+::AuthenticateRequest) -> Self::Payload {
                $($messages)::+::ipc_request::Request::Authenticate(request)
            }

            fn response(response: Self::Response) -> (u64, Option<Self::Reply>) {
                (response.id, response.response)
            }

            fn error(
                reply: Self::Reply,
            ) -> ::std::result::Result<Self::Reply, $($messages)::+::ErrorResponse> {
                match reply {
                    $($messages)::+::ipc_response::Response::Error(error) => Err(error),
                    reply => Ok(reply),
                }
            }

            fn authenticated(
                reply: &Self::Reply,
            ) -> Option<&$($messages)::+::AuthenticateResponse> {
                match reply {
                    $($messages)::+::ipc_response::Response::Authenticate(authenticated) => {
                        Some(authenticated)
                    }
                    _ => None,
                }
            }

            fn is_retry_safe(payload: &Self::Payload) -> bool {
                use $($messages)::+::ipc_request::Request;
                matches!(payload, $(Request::$retry_safe(_))|+)
            }

            fn wipe(request: &mut Self::Request) {
                if let Some($($messages)::+::ipc_request::Request::Authenticate(authenticate)) =
                    &mut request.request
                {
                    $crate::handshake::wipe(authenticate);
                }
            }
        }
    };
}

/// Opens a new connection to the app.
pub trait Connect: Send + Sync + 'static {
    /// Connects, and verifies the server.
    fn connect(&self) -> BoxFuture<'_, Result<BoxStream>>;
}

/// The private IPC server of the app process that launched the agent.
pub struct AppEndpoint {
    path: PathBuf,
    parent_pid: u32,
}

impl AppEndpoint {
    /// The server at `path`, which the process `parent_pid` must run.
    ///
    /// # Errors
    ///
    /// Fails for a zero PID.
    pub fn new(path: &Path, parent_pid: u32) -> Result<Self> {
        if parent_pid == 0 {
            bail!("Expected parent PID must be non-zero");
        }
        Ok(Self {
            path: path.to_path_buf(),
            parent_pid,
        })
    }
}

impl Connect for AppEndpoint {
    fn connect(&self) -> BoxFuture<'_, Result<BoxStream>> {
        Box::pin(async move {
            // Bounds the wait for a busy Windows pipe.
            tokio::time::timeout(
                AUTHENTICATION_TIMEOUT,
                stream::connect(&self.path, Some(self.parent_pid)),
            )
            .await
            .map_err(|_| {
                anyhow!(
                    "Timed out connecting to IPC socket at {}",
                    self.path.display()
                )
            })?
        })
    }
}

/// A client of the app over one shared connection.
///
/// This is safe to clone and share across tasks -- all access to the
/// underlying connection is serialized via a mutex.
pub struct Client<P: Protocol> {
    inner: Arc<Inner<P>>,
}

impl<P: Protocol> Clone for Client<P> {
    fn clone(&self) -> Self {
        Self {
            inner: self.inner.clone(),
        }
    }
}

struct Inner<P> {
    // `None` means the previous transport is unusable. In particular, an
    // async read or write cancelled by a timeout may have consumed only part
    // of a frame, so that transport must never be used again.
    stream: Mutex<Option<BoxStream>>,
    next_id: AtomicU64,
    reconnect: Option<Reconnect>,
    protocol: PhantomData<fn() -> P>,
}

struct Reconnect {
    connector: Box<dyn Connect>,
    token: AuthToken,
}

impl<P: Protocol> Client<P> {
    /// Connects to the app and authenticates with `token`, which the client
    /// keeps to authenticate again after a reconnect.
    ///
    /// # Errors
    ///
    /// Fails when the connection or the authentication fails.
    pub async fn connect(connector: impl Connect, token: AuthToken) -> Result<Self> {
        let stream = connector.connect().await?;
        let client = Self {
            inner: Arc::new(Inner {
                stream: Mutex::new(Some(stream)),
                next_id: AtomicU64::new(1),
                reconnect: Some(Reconnect {
                    connector: Box::new(connector),
                    token: token.clone(),
                }),
                protocol: PhantomData,
            }),
        };

        // Authenticate immediately.
        client.authenticate(&token).await?;

        Ok(client)
    }

    async fn request_once(stream: &mut BoxStream, request: &P::Request) -> Result<P::Response> {
        let encoded = Zeroizing::new(request.encode_to_vec());
        trace!(len = encoded.len(), "Sending IPC message");
        write_frame(stream, &encoded, MAX_AGENT_FRAME).await?;
        let bytes = read_frame(stream, MAX_AGENT_FRAME).await?;
        Ok(P::Response::decode(&bytes[..])?)
    }

    fn reply(reply: Option<P::Reply>) -> Result<P::Reply> {
        match reply {
            Some(reply) => P::error(reply).map_err(|error| IpcError::from(&error).into()),
            None => bail!("IPC response has no payload"),
        }
    }

    async fn authenticate_stream(
        stream: &mut BoxStream,
        next_id: &AtomicU64,
        token: &AuthToken,
    ) -> Result<()> {
        let id = next_id.fetch_add(1, Ordering::Relaxed);
        let mut request = P::request(id, P::authenticate(authenticate_request(token)));

        let response = Self::request_once(stream, &request).await;
        P::wipe(&mut request);
        let response = response.context("IPC transport failed during authenticate")?;
        let (response_id, reply) = P::response(response);
        if response_id != id {
            bail!(
                "IPC response ID mismatch: expected {}, got {}",
                id,
                response_id
            );
        }

        match P::authenticated(&Self::reply(reply)?) {
            Some(authenticated) => check_authenticate_response(authenticated),
            None => bail!("Unexpected response to authenticate request"),
        }
    }

    async fn authenticate_stream_with_timeout(
        stream: &mut BoxStream,
        next_id: &AtomicU64,
        token: &AuthToken,
    ) -> Result<()> {
        tokio::time::timeout(
            AUTHENTICATION_TIMEOUT,
            Self::authenticate_stream(stream, next_id, token),
        )
        .await
        .map_err(|_| {
            anyhow!(
                "IPC authentication timed out after {} seconds",
                AUTHENTICATION_TIMEOUT.as_secs()
            )
        })?
    }

    async fn reconnect_locked(&self, stream: &mut Option<BoxStream>) -> Result<()> {
        // A reconnect is only ever allowed to replace an invalidated
        // transport. Keep the slot empty until the new stream has completed
        // authentication so a failed attempt cannot restore stale state.
        stream.take();
        let reconnect = self
            .inner
            .reconnect
            .as_ref()
            .context("Reconnect is not configured for this IPC client")?;

        let mut new_stream = reconnect.connector.connect().await?;
        Self::authenticate_stream_with_timeout(
            &mut new_stream,
            &self.inner.next_id,
            &reconnect.token,
        )
        .await?;
        *stream = Some(new_stream);
        Ok(())
    }

    async fn reconnect_with_backoff_locked(
        &self,
        stream: &mut Option<BoxStream>,
        reconnect_attempts: &mut u32,
        mut last_error: anyhow::Error,
    ) -> Result<()> {
        while *reconnect_attempts < RECONNECT_MAX_ATTEMPTS {
            *reconnect_attempts += 1;
            let reconnect_attempt = *reconnect_attempts;
            let delay = reconnect_delay_with_jitter(reconnect_attempt);
            warn!(
                attempt = reconnect_attempt,
                max_attempts = RECONNECT_MAX_ATTEMPTS,
                delay_ms = delay.as_millis() as u64,
                error = %last_error,
                "IPC transport failed; reconnecting and re-authenticating"
            );
            tokio::time::sleep(delay).await;

            match self.reconnect_locked(stream).await {
                Ok(()) => {
                    info!(
                        attempt = reconnect_attempt,
                        "IPC reconnect and re-authentication succeeded"
                    );
                    return Ok(());
                }
                Err(reconnect_error) => {
                    warn!(
                        attempt = reconnect_attempt,
                        max_attempts = RECONNECT_MAX_ATTEMPTS,
                        error = %reconnect_error,
                        "IPC reconnect attempt failed"
                    );
                    last_error = reconnect_error;
                }
            }
        }

        Err(last_error).context(format!(
            "Unable to restore IPC connection after {} attempts",
            RECONNECT_MAX_ATTEMPTS
        ))
    }

    async fn repair_connection_locked(
        &self,
        stream: &mut Option<BoxStream>,
        request_deadline: tokio::time::Instant,
    ) -> Result<()> {
        stream.take();
        let recovery_deadline =
            request_deadline.min(tokio::time::Instant::now() + AUTHENTICATION_TIMEOUT);
        tokio::time::timeout_at(recovery_deadline, self.reconnect_locked(stream))
            .await
            .map_err(|_| anyhow!("IPC connection recovery did not complete before its deadline"))?
    }

    fn request_timeout_error_locked(
        stream: &mut Option<BoxStream>,
        timeout_message: String,
    ) -> anyhow::Error {
        // Keep the slot empty so the next request must reconnect. Eager
        // recovery here would extend the request beyond its absolute deadline
        // while continuing to hold the shared mutex and the caller's permit.
        stream.take();
        anyhow!(timeout_message)
    }

    /// Sends `payload` and waits for the matching answer. An error that the
    /// app answers with is an [`IpcError`]; any other answer is returned.
    ///
    /// # Errors
    ///
    /// Fails on an error answer, a broken or timed-out connection, or an
    /// answer to another request.
    pub async fn request(&self, payload: P::Payload) -> Result<P::Reply> {
        let deadline = tokio::time::Instant::now() + REQUEST_TIMEOUT;
        let mut stream = tokio::time::timeout_at(deadline, self.inner.stream.lock())
            .await
            .map_err(|_| {
                anyhow!(
                    "IPC request timed out after {} seconds while waiting for the shared connection; operation was not sent",
                    REQUEST_TIMEOUT.as_secs()
                )
            })?;
        let mut reconnect_attempts = 0;
        // Only read-only requests are safe to replay after an ordinary
        // transport failure. A failed signature or decryption is ambiguous:
        // the server may have completed it before the channel broke.
        let is_retry_safe = P::is_retry_safe(&payload);

        loop {
            if stream.is_none() {
                if self.inner.reconnect.is_none() {
                    bail!("IPC connection is unavailable");
                }
                match tokio::time::timeout_at(
                    deadline,
                    self.reconnect_with_backoff_locked(
                        &mut stream,
                        &mut reconnect_attempts,
                        anyhow!("IPC connection is unavailable"),
                    ),
                )
                .await
                {
                    Ok(result) => result?,
                    Err(_) => {
                        let timeout_message = format!(
                            "IPC request timed out after {} seconds while restoring the connection; operation was not sent",
                            REQUEST_TIMEOUT.as_secs()
                        );
                        return Err(Self::request_timeout_error_locked(
                            &mut stream,
                            timeout_message,
                        ));
                    }
                }
            }

            let id = self.inner.next_id.fetch_add(1, Ordering::Relaxed);
            let request = P::request(id, payload.clone());
            // Nothing is sent: the connection stays usable, and a retry
            // would fail the same way.
            check_size(request.encoded_len(), MAX_AGENT_FRAME)
                .context("IPC request was not sent")?;

            // Move the transport out of shared state for the entire
            // transaction. If this future is cancelled externally, the local
            // stream is dropped and the shared slot remains `None`, so a
            // partially written/read frame can never be reused.
            let mut active_stream = stream
                .take()
                .context("IPC connection unexpectedly unavailable")?;
            match tokio::time::timeout_at(
                deadline,
                Self::request_once(&mut active_stream, &request),
            )
            .await
            {
                Ok(Ok(response)) => {
                    let (response_id, reply) = P::response(response);
                    if response_id != id {
                        drop(active_stream);
                        let mismatch = anyhow!(
                            "IPC response ID mismatch: expected {}, got {}",
                            id,
                            response_id
                        );
                        if self.inner.reconnect.is_none() {
                            return Err(mismatch);
                        }

                        return match self.repair_connection_locked(&mut stream, deadline).await {
                            Ok(()) => Err(mismatch),
                            Err(reconnect_error) => Err(reconnect_error)
                                .context(format!("{}; connection recovery failed", mismatch)),
                        };
                    }

                    *stream = Some(active_stream);
                    return Self::reply(reply);
                }
                Ok(Err(err)) => {
                    // A transport error can leave framing state ambiguous.
                    // Invalidate it before attempting the existing bounded
                    // reconnect-and-retry behavior.
                    drop(active_stream);
                    if self.inner.reconnect.is_none() {
                        return Err(err).context("IPC transport failure");
                    }

                    if !is_retry_safe {
                        let result = self.repair_connection_locked(&mut stream, deadline).await;
                        return match result {
                            Ok(()) => Err(err).context(
                                "IPC transport failed during a non-idempotent request; operation was not retried because completion is unknown",
                            ),
                            Err(reconnect_error) => Err(reconnect_error).context(format!(
                                "IPC transport failed during a non-idempotent request and connection recovery failed; operation was not retried because completion is unknown: {err}"
                            )),
                        };
                    }

                    match tokio::time::timeout_at(
                        deadline,
                        self.reconnect_with_backoff_locked(
                            &mut stream,
                            &mut reconnect_attempts,
                            err,
                        ),
                    )
                    .await
                    {
                        Ok(result) => result?,
                        Err(_) => {
                            let timeout_message = format!(
                                "IPC request timed out after {} seconds while restoring the connection; operation was not retried",
                                REQUEST_TIMEOUT.as_secs()
                            );
                            return Err(Self::request_timeout_error_locked(
                                &mut stream,
                                timeout_message,
                            ));
                        }
                    }
                }
                Err(_) => {
                    // Cancellation may interrupt a prefix, body, or response
                    // read. Drop the stream before reconnecting; retrying this
                    // request could duplicate an operation whose result was
                    // merely delayed.
                    drop(active_stream);
                    let timeout_message = format!(
                        "IPC request timed out after {} seconds; operation was not retried",
                        REQUEST_TIMEOUT.as_secs()
                    );
                    return Err(Self::request_timeout_error_locked(
                        &mut stream,
                        timeout_message,
                    ));
                }
            }
        }
    }

    /// Authenticates with the IPC server using the shared token.
    async fn authenticate(&self, token: &AuthToken) -> Result<()> {
        let deadline = tokio::time::Instant::now() + AUTHENTICATION_TIMEOUT;
        let mut stream = tokio::time::timeout_at(deadline, self.inner.stream.lock())
            .await
            .map_err(|_| {
                anyhow!(
                    "IPC authentication timed out after {} seconds while waiting for the shared connection",
                    AUTHENTICATION_TIMEOUT.as_secs()
                )
            })?;
        let mut candidate = stream.take().context("IPC connection is unavailable")?;
        let result = tokio::time::timeout_at(
            deadline,
            Self::authenticate_stream(&mut candidate, &self.inner.next_id, token),
        )
        .await
        .map_err(|_| {
            anyhow!(
                "IPC authentication timed out after {} seconds",
                AUTHENTICATION_TIMEOUT.as_secs()
            )
        })?;

        if result.is_ok() {
            *stream = Some(candidate);
        }
        result
    }
}

fn reconnect_delay_with_jitter(attempt: u32) -> Duration {
    let exponent = attempt.saturating_sub(1).min(10);
    let base = RECONNECT_INITIAL_DELAY_MS
        .saturating_mul(1u64 << exponent)
        .min(RECONNECT_MAX_DELAY_MS);

    let jitter_span = (base / 4).clamp(1, RECONNECT_MAX_JITTER_MS);
    let now_nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.subsec_nanos() as u64)
        .unwrap_or(0);
    let jitter_seed = now_nanos ^ (attempt as u64).wrapping_mul(0x9E37_79B9_7F4A_7C15);
    let jitter = jitter_seed % (jitter_span + 1);

    Duration::from_millis(base.saturating_add(jitter).min(RECONNECT_MAX_DELAY_MS))
}

#[cfg(test)]
mod tests;
