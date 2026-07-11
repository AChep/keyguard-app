//! Minimal Assuan server implementation for the GPG agent protocol.

use crate::ipc::client::{IpcClient, IpcError};
use crate::ipc::messages::{CallerIdentity, ErrorCode, GpgKey};
use anyhow::{bail, Context, Result};
use std::future::Future;
use std::io;
use std::pin::Pin;
use std::task::{Context as TaskContext, Poll};
use std::time::Duration;
use tokio::io::{AsyncBufRead, AsyncBufReadExt, AsyncRead, AsyncWrite, AsyncWriteExt, BufReader};
use tokio::time::{timeout, timeout_at, Instant, Sleep};
use tracing::{debug, warn};

const ERR_GENERAL: u32 = 1;
const ERR_INV_VALUE: u32 = 5;
const ERR_NO_SECKEY: u32 = 17;
const ERR_NOT_FOUND: u32 = 27;
const ERR_NO_DATA: u32 = 58;
const ERR_NOT_SUPPORTED: u32 = 60;
const ERR_TRUNCATED: u32 = 74;
const ERR_INV_DATA: u32 = 79;
const ERR_INV_SEXP: u32 = 83;
const ERR_UNSUPPORTED_ALGORITHM: u32 = 84;
const ERR_CANCELED: u32 = 99;
const ERR_ASS_LINE_TOO_LONG: u32 = 263;
const ERR_ASS_TOO_MUCH_DATA: u32 = 273;
const ERR_ASS_UNEXPECTED_CMD: u32 = 274;
const ERR_ASS_UNKNOWN_CMD: u32 = 275;
const ERR_ASS_SYNTAX: u32 = 276;
const ERR_ASS_CANCELED: u32 = 277;
const ERR_ASS_PARAMETER: u32 = 280;
const ERR_NO_AUTH: u32 = 314;

const KEYGRIP_LEN: usize = 20;
const KEYGRIP_HEX_LEN: usize = KEYGRIP_LEN * 2;

/// Maximum size of the ciphertext S-expression accumulated across INQUIRE D
/// lines during PKDECRYPT, matching gpg-agent's MAXLEN_CIPHERTEXT.
const MAX_CIPHERTEXT_LEN: usize = 4096;

/// libassuan's fixed protocol line buffer size, including `[CR,]LF`.
const ASSUAN_LINE_LEN: usize = 1002;

/// Maximum length of a single inbound Assuan line, including the trailing
/// newline. The cap is enforced incrementally so a client cannot exhaust memory
/// with a single never-terminated line.
const MAX_LINE_LEN: usize = ASSUAN_LINE_LEN;

/// Per-line cap for the INQUIRE CIPHERTEXT data lines.
const MAX_INQUIRE_LINE_LEN: usize = ASSUAN_LINE_LEN;

/// Maximum Assuan protocol line payload before the trailing line ending.
/// libassuan exposes this as 1000 + `[CR,]LF`; keeping our written line content
/// at or below 1000 leaves room for the LF we append below.
const ASSUAN_MAX_LINE_CONTENT_LEN: usize = ASSUAN_LINE_LEN - 2;

// Keep authenticated clients from occupying a connection indefinitely without
// sending a command, completing a line, or reading the response. Operation
// deadlines deliberately remain in the IPC layer: cancelling an IPC exchange
// between its write and read would desynchronize the shared framed stream.
const CONNECTION_IDLE_TIMEOUT: Duration = Duration::from_secs(5 * 60);
const LINE_COMPLETION_TIMEOUT: Duration = Duration::from_secs(30);
const RESPONSE_WRITE_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Clone, Copy, Debug)]
struct AssuanTimeouts {
    idle: Duration,
    line: Duration,
    write: Duration,
}

impl Default for AssuanTimeouts {
    fn default() -> Self {
        Self {
            idle: CONNECTION_IDLE_TIMEOUT,
            line: LINE_COMPLETION_TIMEOUT,
            write: RESPONSE_WRITE_TIMEOUT,
        }
    }
}

#[derive(Default)]
struct SessionState {
    sigkey: Option<String>,
    sigkey_another: Option<String>,
    hash_algorithm: Option<String>,
    hash: Option<Vec<u8>>,
    hash_pss: bool,
    setkey: Option<String>,
    setkey_another: Option<String>,
}

#[derive(Default)]
struct CallerGuard {
    #[cfg(target_os = "macos")]
    macos: Option<keyguard_agent_identity::macos::MacosPeerIdentity>,
    #[cfg(target_os = "linux")]
    linux: Option<keyguard_agent_identity::linux_identity::LinuxProcessIdentity>,
}

impl CallerGuard {
    fn revalidate(&self) -> Result<()> {
        #[cfg(target_os = "macos")]
        if let Some(identity) = self.macos.as_ref() {
            identity
                .revalidate()
                .map_err(|error| anyhow::anyhow!("macOS caller identity changed: {error}"))?;
        }
        #[cfg(target_os = "linux")]
        if let Some(identity) = self.linux.as_ref() {
            identity
                .revalidate()
                .map_err(|error| anyhow::anyhow!("Linux caller identity changed: {error}"))?;
        }
        Ok(())
    }
}

#[cfg_attr(any(target_os = "linux", target_os = "macos"), allow(dead_code))]
pub async fn serve_connection<S>(
    stream: S,
    ipc_client: IpcClient,
    caller: Option<CallerIdentity>,
    socket_name: String,
) -> Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    serve_connection_with_timeouts_and_guard(
        stream,
        ipc_client,
        caller,
        socket_name,
        AssuanTimeouts::default(),
        CallerGuard::default(),
    )
    .await
}

#[cfg(target_os = "linux")]
pub async fn serve_connection_with_linux_guard<S>(
    stream: S,
    ipc_client: IpcClient,
    caller: Option<CallerIdentity>,
    linux_guard: Option<keyguard_agent_identity::linux_identity::LinuxProcessIdentity>,
    socket_name: String,
) -> Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    serve_connection_with_timeouts_and_guard(
        stream,
        ipc_client,
        caller,
        socket_name,
        AssuanTimeouts::default(),
        CallerGuard { linux: linux_guard },
    )
    .await
}

#[cfg(target_os = "macos")]
pub async fn serve_connection_with_macos_guard<S>(
    stream: S,
    ipc_client: IpcClient,
    caller: Option<CallerIdentity>,
    macos_guard: Option<keyguard_agent_identity::macos::MacosPeerIdentity>,
    socket_name: String,
) -> Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    serve_connection_with_timeouts_and_guard(
        stream,
        ipc_client,
        caller,
        socket_name,
        AssuanTimeouts::default(),
        CallerGuard { macos: macos_guard },
    )
    .await
}

async fn serve_connection_with_timeouts_and_guard<S>(
    stream: S,
    ipc_client: IpcClient,
    caller: Option<CallerIdentity>,
    socket_name: String,
    timeouts: AssuanTimeouts,
    caller_guard: CallerGuard,
) -> Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let (read_half, write_half) = tokio::io::split(stream);
    let mut reader = DeadlineReader::new(BufReader::new(read_half), timeouts);
    let mut write_half = DeadlineWriter::new(write_half, timeouts.write);
    let mut state = SessionState::default();

    write_response(&mut write_half, "OK Keyguard GPG agent ready").await?;

    loop {
        let line = match reader.read_until_limited(b'\n', MAX_LINE_LEN).await? {
            BoundedRead::Eof => break,
            BoundedRead::TooLong => {
                // A single command line exceeded the cap. The stream can no
                // longer be framed reliably, matching libassuan's behavior:
                // close the connection without trying to write a response.
                break;
            }
            BoundedRead::Chunk(bytes) => bytes,
        };

        // Assuan command lines are ASCII text; reject non-UTF-8 input rather than
        // guessing at its meaning.
        let Ok(text) = std::str::from_utf8(&line) else {
            write_error(&mut write_half, ERR_ASS_SYNTAX, "invalid encoding").await?;
            break;
        };

        let request = text.trim_end_matches(['\r', '\n']);
        if request.is_empty() || request.starts_with('#') {
            continue;
        }
        if request.starts_with(' ') || request.starts_with('\t') {
            write_error(&mut write_half, ERR_ASS_SYNTAX, "invalid command").await?;
            continue;
        }

        let command = parse_command(request);
        // Log only the command verb, never the arguments: SETHASH carries the
        // message digest and SIGKEY/SETKEY carry keygrips, so keep those out of
        // the logs (which may be inherited into the parent app's output).
        debug!(command = %command.name, "Assuan command");
        let should_close = handle_command(
            command,
            &mut state,
            &ipc_client,
            &caller_guard,
            caller.clone(),
            &socket_name,
            &mut reader,
            &mut write_half,
        )
        .await?;
        if should_close {
            break;
        }
    }

    Ok(())
}

/// Buffered reader with separate idle and absolute line-completion deadlines.
struct DeadlineReader<R> {
    inner: R,
    idle_timeout: Duration,
    line_timeout: Duration,
}

impl<R> DeadlineReader<R> {
    fn new(inner: R, timeouts: AssuanTimeouts) -> Self {
        Self {
            inner,
            idle_timeout: timeouts.idle,
            line_timeout: timeouts.line,
        }
    }
}

impl<R: AsyncBufRead + Unpin> DeadlineReader<R> {
    /// Reads one line with a strict memory cap. The completion deadline starts
    /// only after the first byte is available, so an idle client and a
    /// slow-drip partial line are reported independently.
    async fn read_until_limited(&mut self, delim: u8, max_len: usize) -> Result<BoundedRead> {
        let mut buf = Vec::new();
        let mut line_deadline = None;
        loop {
            let available = match line_deadline {
                Some(deadline) => {
                    timeout_at(deadline, self.inner.fill_buf())
                        .await
                        .map_err(|_| {
                            anyhow::anyhow!(
                                "Assuan client did not complete a line within {:?}",
                                self.line_timeout
                            )
                        })??
                }
                None => timeout(self.idle_timeout, self.inner.fill_buf())
                    .await
                    .map_err(|_| {
                        anyhow::anyhow!(
                            "Assuan client was idle for longer than {:?}",
                            self.idle_timeout
                        )
                    })??,
            };
            if available.is_empty() {
                return Ok(if buf.is_empty() {
                    BoundedRead::Eof
                } else {
                    BoundedRead::Chunk(buf)
                });
            }
            if line_deadline.is_none() {
                line_deadline = Some(Instant::now() + self.line_timeout);
            }

            let (end, found) = match available.iter().position(|&byte| byte == delim) {
                Some(position) => (position + 1, true),
                None => (available.len(), false),
            };

            if buf.len() + end > max_len {
                let take = max_len - buf.len();
                self.inner.consume(take);
                return Ok(BoundedRead::TooLong);
            }

            buf.extend_from_slice(&available[..end]);
            self.inner.consume(end);
            if found {
                return Ok(BoundedRead::Chunk(buf));
            }
        }
    }
}

/// Async writer that applies one absolute deadline from the first byte of a
/// response through its flush. All Assuan response helpers flush before
/// returning, so a peer cannot extend the deadline by accepting one small
/// partial write at a time.
struct DeadlineWriter<W> {
    inner: W,
    write_timeout: Duration,
    deadline: Option<Pin<Box<Sleep>>>,
}

impl<W> DeadlineWriter<W> {
    fn new(inner: W, write_timeout: Duration) -> Self {
        Self {
            inner,
            write_timeout,
            deadline: None,
        }
    }

    fn ensure_deadline(&mut self) {
        if self.deadline.is_none() {
            self.deadline = Some(Box::pin(tokio::time::sleep(self.write_timeout)));
        }
    }

    fn poll_deadline(&mut self, cx: &mut TaskContext<'_>) -> Option<io::Error> {
        self.ensure_deadline();
        let expired = self
            .deadline
            .as_mut()
            .expect("deadline was initialized above")
            .as_mut()
            .poll(cx)
            .is_ready();
        if expired {
            self.deadline = None;
            Some(io::Error::new(
                io::ErrorKind::TimedOut,
                format!(
                    "Assuan client did not read response within {:?}",
                    self.write_timeout
                ),
            ))
        } else {
            None
        }
    }

    fn finish_response(&mut self) {
        self.deadline = None;
    }
}

impl<W: AsyncWrite + Unpin> AsyncWrite for DeadlineWriter<W> {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut TaskContext<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        let this = self.get_mut();
        if let Some(error) = this.poll_deadline(cx) {
            return Poll::Ready(Err(error));
        }
        match Pin::new(&mut this.inner).poll_write(cx, buf) {
            Poll::Ready(Err(error)) => {
                this.finish_response();
                Poll::Ready(Err(error))
            }
            result => result,
        }
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut TaskContext<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        if let Some(error) = this.poll_deadline(cx) {
            return Poll::Ready(Err(error));
        }
        match Pin::new(&mut this.inner).poll_flush(cx) {
            Poll::Ready(result) => {
                this.finish_response();
                Poll::Ready(result)
            }
            Poll::Pending => Poll::Pending,
        }
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut TaskContext<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        if let Some(error) = this.poll_deadline(cx) {
            return Poll::Ready(Err(error));
        }
        match Pin::new(&mut this.inner).poll_shutdown(cx) {
            Poll::Ready(result) => {
                this.finish_response();
                Poll::Ready(result)
            }
            Poll::Pending => Poll::Pending,
        }
    }
}

/// Outcome of a length-bounded read up to a delimiter byte.
#[derive(Debug)]
enum BoundedRead {
    /// A complete chunk: either terminated by the delimiter, or the trailing
    /// bytes that preceded EOF.
    Chunk(Vec<u8>),
    /// A clean EOF with no buffered bytes.
    Eof,
    /// The byte cap was hit before the delimiter. The stream can no longer be
    /// framed reliably, so the caller must close the connection.
    TooLong,
}

// Keeping the protocol state, guarded caller, bounded reader, and writer as
// explicit borrows makes the security boundaries visible at this dispatcher.
#[allow(clippy::too_many_arguments)]
async fn handle_command<R: AsyncBufRead + Unpin, W: AsyncWriteExt + Unpin>(
    command: ParsedCommand<'_>,
    state: &mut SessionState,
    ipc_client: &IpcClient,
    caller_guard: &CallerGuard,
    caller: Option<CallerIdentity>,
    socket_name: &str,
    reader: &mut DeadlineReader<R>,
    write: &mut W,
) -> Result<bool> {
    match command.name.as_str() {
        "BYE" => {
            write_ok(write, "closing connection").await?;
            Ok(true)
        }
        "RESET" => {
            *state = SessionState::default();
            write_ok(write, "").await?;
            Ok(false)
        }
        "NOP" => {
            write_ok(write, "").await?;
            Ok(false)
        }
        "OPTION" => {
            write_ok(write, "").await?;
            Ok(false)
        }
        "GETINFO" => {
            handle_getinfo(command.args, socket_name, write).await?;
            Ok(false)
        }
        "HAVEKEY" => {
            handle_havekey(command.args, ipc_client, caller_guard, caller, write).await?;
            Ok(false)
        }
        "KEYINFO" => {
            handle_keyinfo(command.args, ipc_client, caller_guard, caller, write).await?;
            Ok(false)
        }
        "SIGKEY" => {
            handle_sigkey(command.args, state, write).await?;
            Ok(false)
        }
        "SETKEY" => {
            handle_setkey(command.args, state, write).await?;
            Ok(false)
        }
        "SETKEYDESC" => {
            // Real gpg sends SETKEYDESC to set the pinentry prompt. We delegate
            // approval to the Keyguard app and have no pinentry, so accept and
            // ignore it; replying with an error would abort signing in libassuan.
            write_ok(write, "").await?;
            Ok(false)
        }
        "SETHASH" => {
            handle_sethash(command.args, state, write).await?;
            Ok(false)
        }
        "PKSIGN" => {
            handle_pksign(state, ipc_client, caller_guard, caller, write).await?;
            Ok(false)
        }
        "PKDECRYPT" => {
            let should_close = handle_pkdecrypt(
                command.args,
                state,
                ipc_client,
                caller_guard,
                caller,
                reader,
                write,
            )
            .await?;
            Ok(should_close)
        }
        _ => {
            write_error(write, ERR_ASS_UNKNOWN_CMD, "unsupported command").await?;
            Ok(false)
        }
    }
}

async fn handle_getinfo<W: AsyncWriteExt + Unpin>(
    args: &str,
    socket_name: &str,
    write: &mut W,
) -> Result<()> {
    match args.trim() {
        "version" => {
            write_data(write, env!("CARGO_PKG_VERSION").as_bytes()).await?;
            write_ok(write, "").await
        }
        "pid" => {
            write_data(write, std::process::id().to_string().as_bytes()).await?;
            write_ok(write, "").await
        }
        "socket_name" => {
            write_data(write, socket_name.as_bytes()).await?;
            write_ok(write, "").await
        }
        "ssh_socket_name" => write_error(write, ERR_NO_DATA, "no SSH socket").await,
        _ => write_error(write, ERR_NO_DATA, "unknown info item").await,
    }
}

async fn handle_havekey<W: AsyncWriteExt + Unpin>(
    args: &str,
    ipc_client: &IpcClient,
    caller_guard: &CallerGuard,
    caller: Option<CallerIdentity>,
    write: &mut W,
) -> Result<()> {
    let parsed = match parse_havekey_args(args) {
        Ok(parsed) => parsed,
        Err(e) => {
            warn!("invalid HAVEKEY request: {e}");
            write_error(write, ERR_ASS_PARAMETER, "invalid keygrip").await?;
            return Ok(());
        }
    };

    let keys = match list_keys(ipc_client, caller_guard, caller).await {
        Ok(keys) => keys,
        Err(e) => {
            warn!("LIST_KEYS failed: {e}");
            write_ipc_or_general_error(write, &e, "key listing failed").await?;
            return Ok(());
        }
    };
    match parsed {
        HaveKeyArgs::List { limit } => {
            let mut counter = 0usize;
            for key in keys.iter().filter(|key| key_usable(key)) {
                let Ok(grip) = keygrip_bytes(&key.keygrip) else {
                    continue;
                };
                if let Some(limit) = limit {
                    counter += 1;
                    if counter > limit {
                        write_error(write, ERR_TRUNCATED, "result truncated").await?;
                        return Ok(());
                    }
                }
                write_data(write, &grip).await?;
            }
            write_ok(write, "").await
        }
        HaveKeyArgs::Query(requested) => {
            if requested.iter().any(|keygrip| {
                keys.iter()
                    .any(|key| key_matches(key, keygrip) && key_usable(key))
            }) {
                write_ok(write, "").await
            } else {
                write_error(write, ERR_NO_SECKEY, "no secret key").await
            }
        }
    }
}

async fn handle_keyinfo<W: AsyncWriteExt + Unpin>(
    args: &str,
    ipc_client: &IpcClient,
    caller_guard: &CallerGuard,
    caller: Option<CallerIdentity>,
    write: &mut W,
) -> Result<()> {
    let (list, requested_keygrip) = match parse_keyinfo_args(args) {
        Ok(parsed) => parsed,
        Err(e) => {
            warn!("invalid KEYINFO request: {e}");
            write_error(write, ERR_ASS_PARAMETER, "invalid keygrip").await?;
            return Ok(());
        }
    };
    let keys = match list_keys(ipc_client, caller_guard, caller).await {
        Ok(keys) => keys,
        Err(e) => {
            warn!("LIST_KEYS failed: {e}");
            write_ipc_or_general_error(write, &e, "key listing failed").await?;
            return Ok(());
        }
    };
    if list {
        for key in keys.iter().filter(|key| key_usable(key)) {
            write_status(write, "KEYINFO", &format_keyinfo(key)).await?;
        }
        write_ok(write, "").await?;
        return Ok(());
    }

    let Some(requested_keygrip) = requested_keygrip else {
        write_error(write, ERR_ASS_PARAMETER, "missing keygrip").await?;
        return Ok(());
    };
    match keys.iter().find(|key| key_matches(key, &requested_keygrip)) {
        Some(key) if key_usable(key) => {
            write_status(write, "KEYINFO", &format_keyinfo(key)).await?;
            write_ok(write, "").await
        }
        _ => write_error(write, ERR_NOT_FOUND, "not found").await,
    }
}

async fn handle_sigkey<W: AsyncWriteExt + Unpin>(
    args: &str,
    state: &mut SessionState,
    write: &mut W,
) -> Result<()> {
    let parsed = match parse_keygrip_command_args(args) {
        Ok(parsed) => parsed,
        Err(e) => {
            warn!("invalid SIGKEY request: {e}");
            write_error(write, ERR_ASS_PARAMETER, "invalid keygrip").await?;
            return Ok(());
        }
    };

    if parsed.another {
        state.sigkey_another = Some(parsed.keygrip);
    } else {
        state.sigkey = Some(parsed.keygrip);
    }
    write_ok(write, "").await
}

async fn handle_setkey<W: AsyncWriteExt + Unpin>(
    args: &str,
    state: &mut SessionState,
    write: &mut W,
) -> Result<()> {
    let parsed = match parse_keygrip_command_args(args) {
        Ok(parsed) => parsed,
        Err(e) => {
            warn!("invalid SETKEY request: {e}");
            write_error(write, ERR_ASS_PARAMETER, "invalid keygrip").await?;
            return Ok(());
        }
    };

    if parsed.another {
        state.setkey_another = Some(parsed.keygrip);
    } else {
        state.setkey = Some(parsed.keygrip);
    }
    write_ok(write, "").await
}

async fn handle_sethash<W: AsyncWriteExt + Unpin>(
    args: &str,
    state: &mut SessionState,
    write: &mut W,
) -> Result<()> {
    match parse_sethash(args) {
        Ok((hash_algorithm, hash, pss)) => {
            state.hash_algorithm = Some(hash_algorithm);
            state.hash = Some(hash);
            state.hash_pss = pss;
            write_ok(write, "").await
        }
        Err(SethashParseError::UnsupportedAlgorithm) => {
            warn!("unsupported SETHASH algorithm");
            write_error(write, ERR_UNSUPPORTED_ALGORITHM, "unsupported algorithm").await
        }
        Err(SethashParseError::Parameter(e)) => {
            warn!("invalid SETHASH request: {e}");
            write_error(write, ERR_ASS_PARAMETER, "invalid SETHASH").await
        }
    }
}

async fn handle_pksign<W: AsyncWriteExt + Unpin>(
    state: &mut SessionState,
    ipc_client: &IpcClient,
    caller_guard: &CallerGuard,
    caller: Option<CallerIdentity>,
    write: &mut W,
) -> Result<()> {
    let Some(keygrip) = state.sigkey.clone() else {
        write_error(write, ERR_NO_SECKEY, "missing SIGKEY").await?;
        return Ok(());
    };

    let keys = match list_keys(ipc_client, caller_guard, caller.clone()).await {
        Ok(keys) => keys,
        Err(e) => {
            warn!("LIST_KEYS failed: {e}");
            write_ipc_or_general_error(write, &e, "key listing failed").await?;
            clear_sign_state(state);
            return Ok(());
        }
    };
    if !keys
        .iter()
        .any(|key| key_matches(key, &keygrip) && key.can_sign)
    {
        write_error(write, ERR_NO_SECKEY, "no secret key").await?;
        clear_sign_state(state);
        return Ok(());
    }

    let Some(hash_algorithm) = state.hash_algorithm.clone() else {
        write_error(write, ERR_INV_VALUE, "invalid digest algorithm").await?;
        clear_sign_state(state);
        return Ok(());
    };
    let Some(hash) = state.hash.clone() else {
        write_error(write, ERR_INV_VALUE, "invalid digest algorithm").await?;
        clear_sign_state(state);
        return Ok(());
    };
    if state.hash_pss {
        write_error(write, ERR_NOT_SUPPORTED, "not supported").await?;
        clear_sign_state(state);
        return Ok(());
    }

    if let Err(error) = caller_guard.revalidate() {
        warn!(%error, "Refusing PKSIGN after caller identity changed");
        write_error(write, ERR_GENERAL, "caller identity changed").await?;
        clear_sign_state(state);
        return Ok(());
    }

    match ipc_client
        .sign_hash(&keygrip, &hash_algorithm, &hash, caller)
        .await
    {
        Ok(response) => {
            write_data(write, response.sexp.as_bytes()).await?;
            write_ok(write, "").await?;
        }
        Err(e) => {
            warn!("PKSIGN failed: {e}");
            write_ipc_or_general_error(write, &e, "signing failed").await?;
        }
    }

    clear_sign_state(state);
    Ok(())
}

async fn handle_pkdecrypt<R: AsyncBufRead + Unpin, W: AsyncWriteExt + Unpin>(
    args: &str,
    state: &mut SessionState,
    ipc_client: &IpcClient,
    caller_guard: &CallerGuard,
    caller: Option<CallerIdentity>,
    reader: &mut DeadlineReader<R>,
    write: &mut W,
) -> Result<bool> {
    let pkdecrypt_args = match parse_pkdecrypt_args(args) {
        Ok(args) => args,
        Err(e) => {
            warn!("invalid PKDECRYPT request: {e}");
            write_error(write, ERR_ASS_PARAMETER, "invalid PKDECRYPT").await?;
            return Ok(false);
        }
    };

    // gpg-agent still performs the ciphertext inquiry first; the missing
    // keygrip is reported only after the client has completed or canceled it.
    let keygrip = state.setkey.clone();

    // gpg supplies the ciphertext via an Assuan INQUIRE round-trip rather than a
    // dedicated SET* command, so ask for it now and read the response inline.
    let ciphertext = match inquire_ciphertext(reader, write).await? {
        InquireResult::Data(ciphertext) => ciphertext,
        InquireResult::Canceled => {
            write_error(write, ERR_ASS_CANCELED, "canceled").await?;
            clear_decrypt_state(state);
            return Ok(false);
        }
        // Both oversized outcomes leave the inquiry incomplete. Close after
        // the error response so its remaining bytes cannot become commands.
        InquireResult::LineTooLong => {
            write_error(write, ERR_ASS_LINE_TOO_LONG, "line too long").await?;
            clear_decrypt_state(state);
            return Ok(true);
        }
        InquireResult::TooMuchData => {
            write_error(write, ERR_ASS_TOO_MUCH_DATA, "too much data").await?;
            clear_decrypt_state(state);
            return Ok(true);
        }
        InquireResult::Unexpected => {
            write_error(write, ERR_ASS_UNEXPECTED_CMD, "unexpected command").await?;
            clear_decrypt_state(state);
            return Ok(false);
        }
        InquireResult::BadData => {
            write_error(write, ERR_INV_DATA, "bad ciphertext").await?;
            clear_decrypt_state(state);
            return Ok(false);
        }
    };

    let Some(keygrip) = keygrip else {
        write_error(write, ERR_NO_SECKEY, "missing SETKEY").await?;
        clear_decrypt_state(state);
        return Ok(false);
    };

    // SETKEY only stores the selected keygrip; the real availability check
    // happens when the operation is attempted so gpg can probe recipients.
    let keys = match list_keys(ipc_client, caller_guard, caller.clone()).await {
        Ok(keys) => keys,
        Err(e) => {
            warn!("LIST_KEYS failed: {e}");
            write_ipc_or_general_error(write, &e, "key listing failed").await?;
            clear_decrypt_state(state);
            return Ok(false);
        }
    };
    if !keys
        .iter()
        .any(|key| key_matches(key, &keygrip) && key.can_decrypt)
    {
        write_error(write, ERR_NO_SECKEY, "no secret key").await?;
        clear_decrypt_state(state);
        return Ok(false);
    }

    if let Err(error) = caller_guard.revalidate() {
        warn!(%error, "Refusing PKDECRYPT after caller identity changed");
        write_error(write, ERR_GENERAL, "caller identity changed").await?;
        clear_decrypt_state(state);
        return Ok(false);
    }

    match ipc_client
        .pkdecrypt(&keygrip, &ciphertext, pkdecrypt_args.unwrap_ecdh, caller)
        .await
    {
        Ok(response) => {
            // Note: deliberately no `S PADDING` status line. For RSA and legacy
            // ECDH, gpg performs the final unpadding / unwrap work. For
            // `PKDECRYPT --kem=...`, the app returns the already-unwrapped
            // ECDH session-key block.
            //
            // The Keyguard processor hands us the value in libgcrypt's
            // advanced text form `(value #HEX#)`, but gpg's PKDECRYPT result
            // parser requires a CANONICAL S-expression — `(5:value<N>:<raw>)`
            // — and rejects the advanced form with GPG_ERR_INV_SEXP. Convert
            // before relaying.
            match advanced_value_to_canonical(&response.value_sexp) {
                Ok(canonical) => {
                    write_data(write, &canonical).await?;
                    write_ok(write, "").await?;
                }
                Err(e) => {
                    warn!("PKDECRYPT response had an unexpected value S-expression: {e}");
                    write_error(write, ERR_INV_SEXP, "invalid S-expression").await?;
                }
            }
        }
        Err(e) => {
            warn!("PKDECRYPT failed: {e}");
            write_ipc_or_general_error(write, &e, "decryption failed").await?;
        }
    }

    clear_decrypt_state(state);
    Ok(false)
}

enum InquireResult {
    Data(Vec<u8>),
    Canceled,
    LineTooLong,
    TooMuchData,
    Unexpected,
    BadData,
}

/// Sends `INQUIRE CIPHERTEXT` and reads the client's reply directly from the
/// connection as raw bytes. The ciphertext is a binary canonical S-expression,
/// so D-line payloads are read byte-wise and percent-unescaped rather than
/// going through the UTF-8 command line buffer.
async fn inquire_ciphertext<R: AsyncBufRead + Unpin, W: AsyncWriteExt + Unpin>(
    reader: &mut DeadlineReader<R>,
    write: &mut W,
) -> Result<InquireResult> {
    write_status(write, "INQUIRE_MAXLEN", &MAX_CIPHERTEXT_LEN.to_string()).await?;
    write.write_all(b"INQUIRE CIPHERTEXT\n").await?;
    write.flush().await?;

    let mut ciphertext: Vec<u8> = Vec::new();
    loop {
        let buf = match reader
            .read_until_limited(b'\n', MAX_INQUIRE_LINE_LEN)
            .await?
        {
            BoundedRead::Eof => return Ok(InquireResult::BadData),
            BoundedRead::TooLong => return Ok(InquireResult::LineTooLong),
            BoundedRead::Chunk(buf) => buf,
        };

        // Strip a trailing CRLF / LF; the payload is everything before it.
        let mut line = &buf[..];
        if line.last() == Some(&b'\n') {
            line = &line[..line.len() - 1];
        }
        if line.last() == Some(&b'\r') {
            line = &line[..line.len() - 1];
        }

        if line.starts_with(b"D ") {
            let chunk = assuan_unescape(&line[2..]);
            if ciphertext.len() + chunk.len() > MAX_CIPHERTEXT_LEN {
                return Ok(InquireResult::TooMuchData);
            }
            ciphertext.extend_from_slice(&chunk);
        } else if line == b"END" {
            return Ok(InquireResult::Data(ciphertext));
        } else if line.starts_with(b"CAN") {
            return Ok(InquireResult::Canceled);
        } else {
            return Ok(InquireResult::Unexpected);
        }
    }
}

async fn list_keys(
    ipc_client: &IpcClient,
    caller_guard: &CallerGuard,
    caller: Option<CallerIdentity>,
) -> Result<Vec<GpgKey>> {
    caller_guard.revalidate()?;
    ipc_client
        .list_keys(caller)
        .await
        .map(|response| response.keys)
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct AssuanError {
    code: u32,
    message: &'static str,
}

async fn write_ipc_or_general_error<W: AsyncWriteExt + Unpin>(
    write: &mut W,
    error: &anyhow::Error,
    fallback_message: &'static str,
) -> Result<()> {
    let assuan_error = error
        .downcast_ref::<IpcError>()
        .map(|ipc_error| assuan_error_for_ipc_error(ipc_error, fallback_message))
        .unwrap_or(AssuanError {
            code: ERR_GENERAL,
            message: fallback_message,
        });
    write_error(write, assuan_error.code, assuan_error.message).await
}

fn assuan_error_for_ipc_error(error: &IpcError, fallback_message: &'static str) -> AssuanError {
    match error.code() {
        ErrorCode::VaultLocked => AssuanError {
            code: ERR_CANCELED,
            message: "vault locked",
        },
        ErrorCode::UserDenied => AssuanError {
            code: ERR_CANCELED,
            message: "canceled",
        },
        ErrorCode::KeyNotFound => AssuanError {
            code: ERR_NO_SECKEY,
            message: "no secret key",
        },
        ErrorCode::Unsupported => AssuanError {
            code: ERR_UNSUPPORTED_ALGORITHM,
            message: "unsupported algorithm",
        },
        ErrorCode::AuthFailed | ErrorCode::NotAuthenticated => AssuanError {
            code: ERR_NO_AUTH,
            message: "not authenticated",
        },
        ErrorCode::Unspecified => AssuanError {
            code: ERR_GENERAL,
            message: fallback_message,
        },
    }
}

fn parse_command(line: &str) -> ParsedCommand<'_> {
    let (name, args) = line
        .split_once(char::is_whitespace)
        .map(|(name, args)| (name, args.trim_start()))
        .unwrap_or((line, ""));
    ParsedCommand {
        name: name.to_ascii_uppercase(),
        args,
    }
}

struct ParsedCommand<'a> {
    name: String,
    args: &'a str,
}

enum HaveKeyArgs {
    List { limit: Option<usize> },
    Query(Vec<String>),
}

struct KeygripCommandArgs {
    another: bool,
    keygrip: String,
}

fn parse_havekey_args(args: &str) -> Result<HaveKeyArgs> {
    let mut list = None;
    let mut requested = Vec::new();

    for arg in args.split_whitespace() {
        match arg {
            "--list" => {
                list = Some(None);
            }
            "--info" => bail!("HAVEKEY --info is not supported"),
            other if other.starts_with("--list=") => {
                let value = other.trim_start_matches("--list=");
                let limit = value
                    .parse::<usize>()
                    .context("invalid HAVEKEY --list limit")?;
                if limit == 0 {
                    bail!("invalid HAVEKEY --list limit");
                }
                list = Some(Some(limit));
            }
            other if other.starts_with("--") => {}
            other => requested.push(other),
        }
    }

    if let Some(limit) = list {
        Ok(HaveKeyArgs::List { limit })
    } else if requested.is_empty() {
        bail!("missing keygrip")
    } else {
        Ok(HaveKeyArgs::Query(
            requested
                .into_iter()
                .map(normalize_keygrip)
                .collect::<Result<Vec<_>>>()?,
        ))
    }
}

fn parse_keyinfo_args(args: &str) -> Result<(bool, Option<String>)> {
    let mut list = false;
    let mut keygrip_arg = None;
    for arg in args.split_whitespace() {
        match arg {
            "--list" | "--list=1000" | "--data" => {
                if arg.starts_with("--list") {
                    list = true;
                }
            }
            other if !other.starts_with("--") => {
                if keygrip_arg.is_none() {
                    keygrip_arg = Some(other);
                }
            }
            _ => {}
        }
    }
    let keygrip = if list {
        None
    } else {
        keygrip_arg.map(normalize_keygrip).transpose()?
    };
    Ok((list, keygrip))
}

fn parse_keygrip_command_args(args: &str) -> Result<KeygripCommandArgs> {
    let mut another = false;
    let mut keygrip = None;
    for arg in args.split_whitespace() {
        match arg {
            "--another" => another = true,
            other if other.starts_with("--") => {}
            other => {
                if keygrip.is_none() {
                    keygrip = Some(normalize_keygrip(other)?);
                }
            }
        }
    }

    Ok(KeygripCommandArgs {
        another,
        keygrip: keygrip.context("missing keygrip")?,
    })
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct PkdecryptArgs {
    unwrap_ecdh: bool,
}

fn parse_pkdecrypt_args(args: &str) -> Result<PkdecryptArgs> {
    let mut unwrap_ecdh = false;
    let mut args = args.split_whitespace().peekable();
    while let Some(arg) = args.next() {
        if arg == "--kem" {
            unwrap_ecdh = true;
            if let Some(next) = args.next_if(|next| !next.starts_with("--")) {
                validate_pkdecrypt_kem(next)?;
            }
            continue;
        }
        if let Some(kem) = arg.strip_prefix("--kem=") {
            unwrap_ecdh = true;
            validate_pkdecrypt_kem(kem)?;
        }
    }
    Ok(PkdecryptArgs { unwrap_ecdh })
}

fn validate_pkdecrypt_kem(kem: &str) -> Result<()> {
    match kem {
        "PQC-PGP" | "PGP" | "CMS" => Ok(()),
        _ => bail!("invalid KEM algorithm"),
    }
}

fn normalize_keygrip(input: &str) -> Result<String> {
    let keygrip = input.trim();
    if keygrip.len() != KEYGRIP_HEX_LEN {
        bail!("invalid keygrip length");
    }
    if !keygrip.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        bail!("invalid keygrip hex");
    }
    Ok(keygrip.to_ascii_uppercase())
}

fn keygrip_bytes(input: &str) -> Result<Vec<u8>> {
    hex::decode(normalize_keygrip(input)?).context("invalid keygrip hex")
}

fn clear_sign_state(state: &mut SessionState) {
    state.sigkey = None;
    state.sigkey_another = None;
    state.hash_algorithm = None;
    state.hash = None;
    state.hash_pss = false;
}

fn clear_decrypt_state(state: &mut SessionState) {
    state.setkey = None;
    state.setkey_another = None;
}

/// Converts libgcrypt's advanced-format value S-expression `(value #HEX#)` (what
/// the Keyguard processor returns) into the CANONICAL transport encoding that gpg's
/// PKDECRYPT result parser expects: `(5:value<N>:<N raw bytes>)`.
///
/// Whitespace inside the advanced form is tolerated; the hex payload must decode to
/// raw bytes. gpg performs the PKCS#1 unpadding / RFC 6637 unwrap itself from these
/// raw bytes, so we relay them verbatim.
fn advanced_value_to_canonical(value_sexp: &str) -> Result<Vec<u8>> {
    let trimmed = value_sexp.trim();
    let inner = trimmed
        .strip_prefix('(')
        .and_then(|s| s.strip_suffix(')'))
        .context("value S-expression is not a parenthesized list")?
        .trim();
    let rest = inner
        .strip_prefix("value")
        .context("value S-expression does not start with `value`")?
        .trim();
    let hex = rest
        .strip_prefix('#')
        .and_then(|s| s.strip_suffix('#'))
        .context("value payload is not in #HEX# form")?;
    // Tolerate internal whitespace/newlines in the hex run.
    let hex: String = hex.chars().filter(|c| !c.is_whitespace()).collect();
    let raw = hex::decode(&hex).context("value payload is not valid hex")?;

    let mut out = Vec::with_capacity(raw.len() + 16);
    out.extend_from_slice(b"(5:value");
    out.extend_from_slice(raw.len().to_string().as_bytes());
    out.push(b':');
    out.extend_from_slice(&raw);
    out.push(b')');
    Ok(out)
}

#[derive(Debug)]
enum SethashParseError {
    UnsupportedAlgorithm,
    Parameter(anyhow::Error),
}

impl From<anyhow::Error> for SethashParseError {
    fn from(value: anyhow::Error) -> Self {
        Self::Parameter(value)
    }
}

fn parse_sethash(args: &str) -> std::result::Result<(String, Vec<u8>, bool), SethashParseError> {
    // gpg-agent's SETHASH grammar is:
    //   SETHASH (--hash=<name> | <algo-number>) <hexdigest>
    // i.e. the algorithm is either the `--hash=` option or the FIRST positional
    // argument (a numeric algorithm id), and the hex digest is the remaining
    // positional. Real gpg uses the positional form, e.g. `SETHASH 8 <hex>`.
    let mut hash_algorithm = None;
    let mut pss = false;
    let mut positionals = Vec::new();
    for arg in args.split_whitespace() {
        if let Some(name) = arg.strip_prefix("--hash=") {
            hash_algorithm = Some(normalize_hash_algorithm_option(name)?);
        } else if arg == "--pss" {
            pss = true;
        } else if arg == "--inquire" {
            return Err(SethashParseError::Parameter(anyhow::anyhow!(
                "unsupported SETHASH option: {arg}"
            )));
        } else if !arg.starts_with("--") {
            positionals.push(arg);
        }
    }

    let hash_hex = match (hash_algorithm.is_some(), positionals.as_slice()) {
        // `--hash=<name> <hexdigest>`
        (true, [hex]) => *hex,
        // `<algo-number> <hexdigest>`
        (false, [algo, hex]) => {
            hash_algorithm = Some(normalize_hash_algorithm(algo)?);
            *hex
        }
        (false, [hex]) => {
            // No algorithm at all; reject rather than guessing.
            let _ = hex;
            return Err(SethashParseError::UnsupportedAlgorithm);
        }
        (false, []) => return Err(SethashParseError::UnsupportedAlgorithm),
        _ => {
            return Err(SethashParseError::Parameter(anyhow::anyhow!(
                "malformed SETHASH arguments"
            )));
        }
    };

    let hash = hex::decode(hash_hex).context("invalid hash hex")?;
    validate_hash_length(hash_algorithm.as_deref(), &hash)?;
    let hash_algorithm = hash_algorithm.context("missing hash algorithm")?;
    Ok((hash_algorithm, hash, pss))
}

fn validate_hash_length(hash_algorithm: Option<&str>, hash: &[u8]) -> Result<()> {
    if hash_algorithm == Some("tls-md5sha1") && hash.len() == 36
        || matches!(hash.len(), 16 | 20 | 24 | 28 | 32 | 48 | 64)
    {
        Ok(())
    } else {
        bail!("unsupported length of hash")
    }
}

fn normalize_hash_algorithm(input: &str) -> std::result::Result<String, SethashParseError> {
    let normalized = match input.to_ascii_lowercase().as_str() {
        "2" | "sha1" => "sha1",
        "8" | "sha256" => "sha256",
        "9" | "sha384" => "sha384",
        "10" | "sha512" => "sha512",
        "11" | "sha224" => "sha224",
        "3" | "rmd160" | "ripemd160" => "rmd160",
        "1" | "md5" => "md5",
        _ => return Err(SethashParseError::UnsupportedAlgorithm),
    };
    Ok(normalized.to_string())
}

fn normalize_hash_algorithm_option(input: &str) -> std::result::Result<String, SethashParseError> {
    let normalized = match input.to_ascii_lowercase().as_str() {
        "sha1" => "sha1",
        "sha224" => "sha224",
        "sha256" => "sha256",
        "sha384" => "sha384",
        "sha512" => "sha512",
        "rmd160" | "ripemd160" => "rmd160",
        "md5" => "md5",
        "tls-md5sha1" => "tls-md5sha1",
        "none" => return Err(SethashParseError::UnsupportedAlgorithm),
        _ => {
            return Err(SethashParseError::Parameter(anyhow::anyhow!(
                "invalid hash algorithm"
            )));
        }
    };
    Ok(normalized.to_string())
}

fn key_matches(key: &GpgKey, requested_keygrip: &str) -> bool {
    key.keygrip.eq_ignore_ascii_case(requested_keygrip)
}

/// Whether a key is usable by this agent: sign-capable (for PKSIGN) or
/// decrypt-capable (for PKDECRYPT). gpg probes both kinds via HAVEKEY/KEYINFO.
fn key_usable(key: &GpgKey) -> bool {
    key.can_sign || key.can_decrypt
}

fn format_keyinfo(key: &GpgKey) -> String {
    let keygrip = key.keygrip.to_ascii_uppercase();
    let fingerprint = if key.fingerprint.is_empty() {
        "-"
    } else {
        &key.fingerprint
    };
    let flags = if key.can_sign { "S" } else { "-" };
    // Fields follow gpg-agent's KEYINFO status shape:
    // keygrip type serialno idstr cached protection fpr ttl flags.
    format!("{keygrip} D - - - P {fingerprint} - {flags}")
}

async fn write_ok<W: AsyncWriteExt + Unpin>(write: &mut W, message: &str) -> Result<()> {
    if message.is_empty() {
        write_response(write, "OK").await
    } else {
        write_response(write, &format!("OK {message}")).await
    }
}

async fn write_error<W: AsyncWriteExt + Unpin>(
    write: &mut W,
    code: u32,
    message: &str,
) -> Result<()> {
    write_response(write, &format!("ERR {code} {message}")).await
}

async fn write_status<W: AsyncWriteExt + Unpin>(
    write: &mut W,
    keyword: &str,
    value: &str,
) -> Result<()> {
    write_response(write, &format!("S {keyword} {value}")).await
}

async fn write_data<W: AsyncWriteExt + Unpin>(write: &mut W, data: &[u8]) -> Result<()> {
    // A D-line carries raw (escaped) bytes, NOT UTF-8 text: a PKDECRYPT result is
    // a binary canonical S-expression containing arbitrary bytes >= 0x80. Build
    // and write lines as bytes so those high bytes are not silently widened into
    // 2-byte UTF-8 sequences. Assuan data is one stream across multiple D-lines
    // until the following OK/ERR, so chunk long responses to libassuan's line cap.
    let mut line = Vec::with_capacity(ASSUAN_MAX_LINE_CONTENT_LEN + 1);
    line.extend_from_slice(b"D ");

    for &byte in data {
        let escaped_len = assuan_escaped_len(byte);
        if line.len() + escaped_len > ASSUAN_MAX_LINE_CONTENT_LEN {
            line.push(b'\n');
            write.write_all(&line).await?;
            line.clear();
            line.extend_from_slice(b"D ");
        }
        assuan_push_escaped_byte(&mut line, byte);
    }

    line.push(b'\n');
    write.write_all(&line).await?;
    write.flush().await?;
    Ok(())
}

async fn write_response<W: AsyncWriteExt + Unpin>(write: &mut W, line: &str) -> Result<()> {
    write.write_all(line.as_bytes()).await?;
    write.write_all(b"\n").await?;
    write.flush().await?;
    Ok(())
}

/// Percent-escapes the bytes that are not allowed verbatim on an Assuan D-line
/// (`%`, CR, LF). Returns raw bytes — every other byte, including high bytes of a
/// binary S-expression, is passed through untouched and MUST be written as bytes
/// (not via a UTF-8 `String`, which would corrupt bytes >= 0x80).
#[cfg(test)]
fn assuan_escape(data: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(data.len());
    for &byte in data {
        assuan_push_escaped_byte(&mut out, byte);
    }
    out
}

fn assuan_escaped_len(byte: u8) -> usize {
    match byte {
        b'%' | b'\r' | b'\n' => 3,
        _ => 1,
    }
}

fn assuan_push_escaped_byte(out: &mut Vec<u8>, byte: u8) {
    match byte {
        b'%' => out.extend_from_slice(b"%25"),
        b'\r' => out.extend_from_slice(b"%0D"),
        b'\n' => out.extend_from_slice(b"%0A"),
        _ => out.push(byte),
    }
}

/// Reverses Assuan's percent-encoding on an inbound D-line payload. `%XX` (two
/// hex digits) becomes the byte 0xXX; every other byte is taken literally. A
/// trailing or malformed `%` sequence is passed through verbatim. This is
/// byte-accurate so binary canonical S-expressions survive the round-trip.
fn assuan_unescape(data: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(data.len());
    let mut i = 0;
    while i < data.len() {
        if data[i] == b'%' {
            if let (Some(hi), Some(lo)) = (
                data.get(i + 1).and_then(|b| hex_value(*b)),
                data.get(i + 2).and_then(|b| hex_value(*b)),
            ) {
                out.push((hi << 4) | lo);
                i += 3;
                continue;
            }
        }
        out.push(data[i]);
        i += 1;
    }
    out
}

fn hex_value(byte: u8) -> Option<u8> {
    match byte {
        b'0'..=b'9' => Some(byte - b'0'),
        b'a'..=b'f' => Some(byte - b'a' + 10),
        b'A'..=b'F' => Some(byte - b'A' + 10),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::AsyncReadExt;

    const KEYGRIP_LOWER: &str = "0123456789abcdef0123456789abcdef01234567";
    const KEYGRIP_UPPER: &str = "0123456789ABCDEF0123456789ABCDEF01234567";

    #[test]
    fn ipc_error_mapping_preserves_specific_assuan_codes() {
        let cases = [
            (
                ErrorCode::VaultLocked,
                AssuanError {
                    code: ERR_CANCELED,
                    message: "vault locked",
                },
            ),
            (
                ErrorCode::UserDenied,
                AssuanError {
                    code: ERR_CANCELED,
                    message: "canceled",
                },
            ),
            (
                ErrorCode::KeyNotFound,
                AssuanError {
                    code: ERR_NO_SECKEY,
                    message: "no secret key",
                },
            ),
            (
                ErrorCode::Unsupported,
                AssuanError {
                    code: ERR_UNSUPPORTED_ALGORITHM,
                    message: "unsupported algorithm",
                },
            ),
            (
                ErrorCode::AuthFailed,
                AssuanError {
                    code: ERR_NO_AUTH,
                    message: "not authenticated",
                },
            ),
            (
                ErrorCode::NotAuthenticated,
                AssuanError {
                    code: ERR_NO_AUTH,
                    message: "not authenticated",
                },
            ),
            (
                ErrorCode::Unspecified,
                AssuanError {
                    code: ERR_GENERAL,
                    message: "operation failed",
                },
            ),
        ];

        for (code, expected) in cases {
            assert_eq!(
                assuan_error_for_ipc_error(&IpcError::new(code, "from app"), "operation failed"),
                expected,
            );
        }
    }

    #[tokio::test]
    async fn write_ipc_or_general_error_uses_typed_ipc_code() {
        let error = anyhow::Error::new(IpcError::new(ErrorCode::UserDenied, "from app"));
        let mut output = Vec::new();

        write_ipc_or_general_error(&mut output, &error, "operation failed")
            .await
            .unwrap();

        assert_eq!(output, b"ERR 99 canceled\n");
    }

    #[tokio::test]
    async fn write_ipc_or_general_error_falls_back_for_transport_errors() {
        let error = anyhow::anyhow!("socket closed");
        let mut output = Vec::new();

        write_ipc_or_general_error(&mut output, &error, "operation failed")
            .await
            .unwrap();

        assert_eq!(output, b"ERR 1 operation failed\n");
    }

    #[test]
    fn assuan_escape_escapes_required_bytes() {
        assert_eq!(assuan_escape(b"a%b\r\nc"), b"a%25b%0D%0Ac".to_vec());
    }

    #[test]
    fn assuan_escape_preserves_high_bytes_verbatim() {
        // High bytes (>= 0x80) of a binary S-expression must pass through as single
        // raw bytes, NOT be widened into 2-byte UTF-8 sequences.
        let input = [0x00u8, 0x80, 0xff, b'(', b')'];
        assert_eq!(assuan_escape(&input), input.to_vec());
        // Round-trips through the inbound unescaper too.
        assert_eq!(assuan_unescape(&assuan_escape(&input)), input.to_vec());
    }

    #[tokio::test]
    async fn write_data_chunks_long_d_lines() {
        let input = vec![b'A'; ASSUAN_MAX_LINE_CONTENT_LEN * 2];
        let mut output = Vec::new();

        write_data(&mut output, &input).await.unwrap();

        let (decoded, line_count) = decode_data_lines(&output);
        assert_eq!(decoded, input);
        assert!(line_count > 1, "expected chunked D-lines: {output:?}");
    }

    #[tokio::test]
    async fn write_data_does_not_split_percent_escapes() {
        let mut input = vec![b'A'; ASSUAN_MAX_LINE_CONTENT_LEN - b"D ".len() - 1];
        input.extend_from_slice(b"%\r\n");
        let mut output = Vec::new();

        write_data(&mut output, &input).await.unwrap();

        let (decoded, line_count) = decode_data_lines(&output);
        assert_eq!(decoded, input);
        assert_eq!(
            line_count, 2,
            "escape boundary should force a second D-line"
        );
    }

    #[tokio::test]
    async fn write_data_preserves_binary_high_bytes_across_chunks() {
        let mut input = vec![0xff; ASSUAN_MAX_LINE_CONTENT_LEN - b"D ".len()];
        input.extend_from_slice(&[0x00, 0x80, b'%', b'\n', b'\r']);
        let mut output = Vec::new();

        write_data(&mut output, &input).await.unwrap();

        let (decoded, line_count) = decode_data_lines(&output);
        assert_eq!(decoded, input);
        assert_eq!(
            line_count, 2,
            "boundary payload should force a second D-line"
        );
    }

    #[test]
    fn sethash_parses_hash_name_and_hex() {
        let hash_hex = "AA".repeat(32);
        let (algorithm, hash, pss) = parse_sethash(&format!("--hash=sha256 {hash_hex}")).unwrap();
        assert_eq!(algorithm, "sha256");
        assert_eq!(hash, vec![0xaa; 32]);
        assert!(!pss);
    }

    #[test]
    fn sethash_parses_positional_algo_and_hex() {
        // Real gpg sends `SETHASH <algo-number> <hexdigest>`, e.g. `SETHASH 8 ...`.
        let hash_hex = "AA".repeat(32);
        let (algorithm, hash, pss) = parse_sethash(&format!("8 {hash_hex}")).unwrap();
        assert_eq!(algorithm, "sha256");
        assert_eq!(hash, vec![0xaa; 32]);
        assert!(!pss);
    }

    #[test]
    fn sethash_rejects_missing_algorithm() {
        let hash_hex = "AA".repeat(32);
        assert!(parse_sethash(&hash_hex).is_err());
    }

    #[test]
    fn sethash_rejects_unsupported_digest_length() {
        assert!(parse_sethash("8 AABB").is_err());
    }

    #[test]
    fn sethash_rejects_unsupported_options() {
        let hash_hex = "AA".repeat(32);
        assert!(parse_sethash(&format!("--inquire 8 {hash_hex}")).is_err());
        assert!(parse_sethash(&format!("--hash=none {hash_hex}")).is_err());
    }

    #[test]
    fn sethash_parses_pss_and_tls_md5sha1() {
        let sha256_hex = "AA".repeat(32);
        let (_, _, pss) = parse_sethash(&format!("--pss 8 {sha256_hex}")).unwrap();
        assert!(pss);

        let tls_hex = "AA".repeat(36);
        let (algorithm, hash, pss) =
            parse_sethash(&format!("--hash=tls-md5sha1 {tls_hex}")).unwrap();
        assert_eq!(algorithm, "tls-md5sha1");
        assert_eq!(hash, vec![0xaa; 36]);
        assert!(!pss);
    }

    #[test]
    fn pkdecrypt_args_accept_valid_kem_options() {
        assert!(!parse_pkdecrypt_args("").unwrap().unwrap_ecdh);
        assert!(parse_pkdecrypt_args("--kem").unwrap().unwrap_ecdh);
        assert!(parse_pkdecrypt_args("--kem PGP").unwrap().unwrap_ecdh);
        assert!(parse_pkdecrypt_args("--kem=PQC-PGP").unwrap().unwrap_ecdh);
        assert!(parse_pkdecrypt_args("--kem=PGP").unwrap().unwrap_ecdh);
        assert!(parse_pkdecrypt_args("--kem=CMS").unwrap().unwrap_ecdh);
        assert!(parse_pkdecrypt_args("--kem=BAD").is_err());
    }

    #[test]
    fn advanced_value_becomes_canonical() {
        // (value #DEADBEEF#) -> (5:value4:<raw>)
        let canonical = advanced_value_to_canonical("(value #DEADBEEF#)").unwrap();
        let mut expected = Vec::new();
        expected.extend_from_slice(b"(5:value4:");
        expected.extend_from_slice(&[0xde, 0xad, 0xbe, 0xef]);
        expected.push(b')');
        assert_eq!(canonical, expected);
    }

    #[test]
    fn advanced_value_tolerates_whitespace() {
        let canonical = advanced_value_to_canonical("( value #DE AD\nBEEF# )").unwrap();
        assert!(canonical.starts_with(b"(5:value4:"));
        assert_eq!(
            &canonical[canonical.len() - 5..],
            &[0xde, 0xad, 0xbe, 0xef, b')']
        );
    }

    #[test]
    fn advanced_value_rejects_garbage() {
        assert!(advanced_value_to_canonical("(sig-val ...)").is_err());
    }

    #[test]
    fn keyinfo_list_args_are_detected() {
        let (list, keygrip) = parse_keyinfo_args("--list --data").unwrap();
        assert!(list);
        assert_eq!(keygrip, None);
    }

    #[test]
    fn keyinfo_args_validate_and_normalize_keygrip() {
        let (list, keygrip) = parse_keyinfo_args(KEYGRIP_LOWER).unwrap();
        assert!(!list);
        assert_eq!(keygrip.as_deref(), Some(KEYGRIP_UPPER));
        assert!(parse_keyinfo_args("abcd").is_err());
    }

    #[test]
    fn havekey_args_parse_queries_and_list_limit() {
        match parse_havekey_args(KEYGRIP_LOWER).unwrap() {
            HaveKeyArgs::Query(keygrips) => assert_eq!(keygrips, vec![KEYGRIP_UPPER]),
            HaveKeyArgs::List { .. } => panic!("expected query mode"),
        }

        match parse_havekey_args("--list=2").unwrap() {
            HaveKeyArgs::List { limit } => assert_eq!(limit, Some(2)),
            HaveKeyArgs::Query(_) => panic!("expected list mode"),
        }

        match parse_havekey_args("--list").unwrap() {
            HaveKeyArgs::List { limit } => assert_eq!(limit, None),
            HaveKeyArgs::Query(_) => panic!("expected list mode"),
        }

        assert!(parse_havekey_args("abcd").is_err());
    }

    #[test]
    fn keygrip_command_args_detect_another() {
        let parsed = parse_keygrip_command_args(&format!("--another {KEYGRIP_LOWER}")).unwrap();
        assert!(parsed.another);
        assert_eq!(parsed.keygrip, KEYGRIP_UPPER);
    }

    #[test]
    fn keyinfo_status_contains_keygrip_and_flags() {
        let status = format_keyinfo(&GpgKey {
            name: "Test".to_string(),
            keygrip: "abcd".to_string(),
            fingerprint: "FFFF".to_string(),
            algorithm: "rsa".to_string(),
            can_sign: true,
            can_decrypt: false,
        });
        assert_eq!(status, "ABCD D - - - P FFFF - S");
    }

    #[test]
    fn assuan_unescape_decodes_hex_and_literals() {
        // %XX hex pairs decode to bytes; %% decodes to a literal '%'; other
        // bytes pass through unchanged.
        assert_eq!(assuan_unescape(b"a%25b%0D%0Ac"), b"a%b\r\nc");
        assert_eq!(assuan_unescape(b"%00%FF%ff"), &[0x00, 0xff, 0xff]);
        assert_eq!(assuan_unescape(b"hello"), b"hello");
    }

    #[test]
    fn assuan_unescape_passes_through_malformed_sequences() {
        // A lone or truncated '%' is not a valid escape and is kept verbatim.
        assert_eq!(assuan_unescape(b"%"), b"%");
        assert_eq!(assuan_unescape(b"%2"), b"%2");
        assert_eq!(assuan_unescape(b"%zz"), b"%zz");
    }

    #[test]
    fn assuan_unescape_decodes_all_byte_values() {
        // gpg escapes binary D-line payloads as explicit %XX pairs; verify every
        // byte value round-trips through the unescaper.
        for b in 0u16..=255 {
            let escaped = format!("%{:02X}", b);
            assert_eq!(assuan_unescape(escaped.as_bytes()), vec![b as u8]);
        }
    }

    #[test]
    fn key_usable_accepts_sign_or_decrypt() {
        let mut key = GpgKey {
            name: "k".to_string(),
            keygrip: "AB".to_string(),
            fingerprint: String::new(),
            algorithm: "rsa".to_string(),
            can_sign: false,
            can_decrypt: false,
        };
        assert!(!key_usable(&key));
        key.can_decrypt = true;
        assert!(key_usable(&key));
        key.can_decrypt = false;
        key.can_sign = true;
        assert!(key_usable(&key));
    }

    #[tokio::test]
    async fn inquire_ciphertext_advertises_maxlen_and_decodes_data() {
        let input = b"D abc%25\nEND\n";
        let mut reader = DeadlineReader::new(BufReader::new(&input[..]), AssuanTimeouts::default());
        let mut output = Vec::new();

        match inquire_ciphertext(&mut reader, &mut output).await.unwrap() {
            InquireResult::Data(data) => assert_eq!(data, b"abc%"),
            _ => panic!("expected ciphertext data"),
        }
        assert_eq!(
            output,
            format!("S INQUIRE_MAXLEN {MAX_CIPHERTEXT_LEN}\nINQUIRE CIPHERTEXT\n").into_bytes(),
        );
    }

    #[tokio::test]
    async fn pkdecrypt_oversized_inquiry_closes_connection() {
        let mut overlong_line = b"D ".to_vec();
        overlong_line.extend_from_slice(&vec![b'A'; MAX_INQUIRE_LINE_LEN]);
        overlong_line.extend_from_slice(b"\nEND\nNOP\n");

        let max_payload_per_line = MAX_INQUIRE_LINE_LEN - b"D \n".len();
        let mut too_much_data = Vec::new();
        let mut remaining = MAX_CIPHERTEXT_LEN + 1;
        while remaining > 0 {
            let chunk_len = remaining.min(max_payload_per_line);
            too_much_data.extend_from_slice(b"D ");
            too_much_data.extend_from_slice(&vec![b'A'; chunk_len]);
            too_much_data.push(b'\n');
            remaining -= chunk_len;
        }
        too_much_data.extend_from_slice(b"END\nNOP\n");

        for (inquiry, expected_response) in [
            (overlong_line, b"ERR 263 line too long\n".as_slice()),
            (too_much_data, b"ERR 273 too much data\n".as_slice()),
        ] {
            let response = run_oversized_pkdecrypt_inquiry(&inquiry).await;

            assert_eq!(response, expected_response);
        }
    }

    async fn run_oversized_pkdecrypt_inquiry(inquiry: &[u8]) -> Vec<u8> {
        let (ipc_stream, _ipc_peer) = tokio::io::duplex(1);
        let ipc_client = IpcClient::from_test_stream(ipc_stream);
        let (client, server) = tokio::io::duplex(inquiry.len() + 128);
        let server_task = tokio::spawn(serve_connection(
            server,
            ipc_client,
            None,
            "test".to_string(),
        ));
        let (client_read, mut client_write) = tokio::io::split(client);
        let mut client_read = BufReader::new(client_read);
        let mut line = Vec::new();

        client_read.read_until(b'\n', &mut line).await.unwrap();
        assert_eq!(line, b"OK Keyguard GPG agent ready\n");

        client_write.write_all(b"PKDECRYPT\n").await.unwrap();
        line.clear();
        client_read.read_until(b'\n', &mut line).await.unwrap();
        assert_eq!(
            line,
            format!("S INQUIRE_MAXLEN {MAX_CIPHERTEXT_LEN}\n").as_bytes(),
        );
        line.clear();
        client_read.read_until(b'\n', &mut line).await.unwrap();
        assert_eq!(line, b"INQUIRE CIPHERTEXT\n");

        client_write.write_all(inquiry).await.unwrap();
        client_write.shutdown().await.unwrap();
        let mut response = Vec::new();
        client_read.read_to_end(&mut response).await.unwrap();
        server_task.await.unwrap().unwrap();
        response
    }

    #[tokio::test]
    async fn read_until_limited_splits_lines_and_reports_eof() {
        let data = b"hello\nworld";
        let mut reader = DeadlineReader::new(BufReader::new(&data[..]), AssuanTimeouts::default());
        match reader.read_until_limited(b'\n', 64).await.unwrap() {
            BoundedRead::Chunk(b) => assert_eq!(b, b"hello\n"),
            _ => panic!("expected first line"),
        }
        match reader.read_until_limited(b'\n', 64).await.unwrap() {
            BoundedRead::Chunk(b) => assert_eq!(b, b"world"),
            _ => panic!("expected trailing chunk"),
        }
        assert!(matches!(
            reader.read_until_limited(b'\n', 64).await.unwrap(),
            BoundedRead::Eof
        ));
    }

    #[tokio::test]
    async fn read_until_limited_rejects_overlong_line() {
        // A 100-byte unterminated line must be rejected against a 16-byte cap
        // rather than being buffered in full.
        let data = [b'A'; 100];
        let mut reader = DeadlineReader::new(BufReader::new(&data[..]), AssuanTimeouts::default());
        assert!(matches!(
            reader.read_until_limited(b'\n', 16).await.unwrap(),
            BoundedRead::TooLong
        ));
    }

    #[tokio::test(start_paused = true)]
    async fn authenticated_session_idle_read_times_out() {
        let (_client, server) = tokio::io::duplex(16);
        let idle_timeout = Duration::from_secs(60);
        let mut reader = DeadlineReader::new(
            BufReader::new(server),
            AssuanTimeouts {
                idle: idle_timeout,
                line: Duration::from_secs(10),
                write: Duration::from_secs(10),
            },
        );

        let error = reader
            .read_until_limited(b'\n', MAX_LINE_LEN)
            .await
            .expect_err("silent authenticated client must time out");

        assert!(error.to_string().contains("idle"));
        assert!(error.to_string().contains("60s"));
    }

    #[tokio::test(start_paused = true)]
    async fn partial_assuan_line_has_an_absolute_completion_deadline() {
        let (mut client, server) = tokio::io::duplex(16);
        client.write_all(b"N").await.unwrap();
        let line_timeout = Duration::from_secs(10);
        let mut reader = DeadlineReader::new(
            BufReader::new(server),
            AssuanTimeouts {
                idle: Duration::from_secs(60),
                line: line_timeout,
                write: Duration::from_secs(10),
            },
        );

        let error = reader
            .read_until_limited(b'\n', MAX_LINE_LEN)
            .await
            .expect_err("partial Assuan line must time out");

        assert!(error.to_string().contains("complete a line"));
        assert!(error.to_string().contains("10s"));
    }

    #[tokio::test(start_paused = true)]
    async fn blocked_assuan_response_write_times_out() {
        let (_client, server) = tokio::io::duplex(1);
        let mut writer = DeadlineWriter::new(server, Duration::from_secs(10));

        let error = write_response(&mut writer, "response that cannot fit")
            .await
            .expect_err("client that does not read must time out");
        let io_error = error
            .downcast_ref::<io::Error>()
            .expect("write timeout should retain its I/O error");

        assert_eq!(io_error.kind(), io::ErrorKind::TimedOut);
    }

    fn decode_data_lines(output: &[u8]) -> (Vec<u8>, usize) {
        let mut decoded = Vec::new();
        let mut line_count = 0;
        for line in output.split(|&byte| byte == b'\n') {
            if line.is_empty() {
                continue;
            }
            line_count += 1;
            assert!(
                line.starts_with(b"D "),
                "expected D-line, got {:?}",
                String::from_utf8_lossy(line),
            );
            assert!(
                line.len() <= ASSUAN_MAX_LINE_CONTENT_LEN,
                "D-line is too long: {} bytes",
                line.len(),
            );
            decoded.extend_from_slice(&assuan_unescape(&line[2..]));
        }
        assert!(line_count > 0, "expected at least one D-line");
        (decoded, line_count)
    }
}
