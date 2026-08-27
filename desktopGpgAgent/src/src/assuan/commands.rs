//! Assuan command dispatch and Keyguard IPC operations.

use crate::ipc::client::{IpcClient, IpcError};
use crate::ipc::messages::{CallerIdentity, ErrorCode, GpgKey};
use anyhow::Result;
use tokio::io::{AsyncBufRead, AsyncWriteExt};
use tracing::warn;

use super::syntax::*;
use super::transport::*;
use super::{CallerGuard, SessionState};

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
pub(super) const ERR_ASS_SYNTAX: u32 = 276;
const ERR_ASS_CANCELED: u32 = 277;
const ERR_ASS_PARAMETER: u32 = 280;
const ERR_NO_AUTH: u32 = 314;

/// Maximum size of the ciphertext S-expression accumulated across INQUIRE D
/// lines during PKDECRYPT, matching gpg-agent's MAXLEN_CIPHERTEXT.
pub(super) const MAX_CIPHERTEXT_LEN: usize = 4096;

// Keeping the protocol state, guarded caller, bounded reader, and writer as
// explicit borrows makes the security boundaries visible at this dispatcher.
#[allow(clippy::too_many_arguments)]
pub(super) async fn handle_command<R: AsyncBufRead + Unpin, W: AsyncWriteExt + Unpin>(
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
            log_ipc_failure("LIST_KEYS", &e);
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
            log_ipc_failure("LIST_KEYS", &e);
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
            log_ipc_failure("LIST_KEYS", &e);
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
            log_ipc_failure("PKSIGN", &e);
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
        // A line that exceeded the protocol limit cannot be framed reliably.
        // Close after the error response rather than allowing its tail to be
        // interpreted as another Assuan command.
        InquireResult::LineTooLong => {
            write_error(write, ERR_ASS_LINE_TOO_LONG, "line too long").await?;
            clear_decrypt_state(state);
            return Ok(true);
        }
        InquireResult::TooMuchData => {
            write_error(write, ERR_ASS_TOO_MUCH_DATA, "too much data").await?;
            clear_decrypt_state(state);
            // Unlike an overlong line, the inquiry has been fully framed: the
            // remaining D-lines were discarded through END. Keep the session
            // alive so a pipelined BYE (or the next client command) cannot
            // turn an otherwise valid error response into a transport reset.
            return Ok(false);
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
            log_ipc_failure("LIST_KEYS", &e);
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
            log_ipc_failure("PKDECRYPT", &e);
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
    let mut too_much_data = false;
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
            if too_much_data {
                // libassuan continues reading after MAXLEN has been reached so
                // the client's remaining D-lines cannot be mistaken for new
                // top-level Assuan commands. Discard them without growing the
                // bounded ciphertext buffer.
                continue;
            }
            let chunk = assuan_unescape(&line[2..]);
            if ciphertext.len() + chunk.len() > MAX_CIPHERTEXT_LEN {
                too_much_data = true;
                continue;
            }
            ciphertext.extend_from_slice(&chunk);
        } else if line == b"END" {
            return Ok(if too_much_data {
                InquireResult::TooMuchData
            } else {
                InquireResult::Data(ciphertext)
            });
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

fn log_ipc_failure(operation: &'static str, error: &anyhow::Error) {
    // The app's free-form error message may contain vault data. Log only the
    // operation and protocol error code, including when verbose logging is on.
    let error_code = error.downcast_ref::<IpcError>().map(IpcError::code);
    warn!(operation, ?error_code, "Keyguard operation failed");
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

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::BufReader;

    #[test]
    fn ipc_failure_logs_exclude_backend_payloads() {
        let output = tempfile::NamedTempFile::new().unwrap();
        let subscriber = tracing_subscriber::fmt()
            .with_max_level(tracing::Level::TRACE)
            .with_ansi(false)
            .with_writer(output.reopen().unwrap())
            .finish();
        let error = anyhow::Error::new(IpcError::new(
            ErrorCode::UserDenied,
            "private-backend-detail",
        ))
        .context("private-error-context");

        tracing::subscriber::with_default(subscriber, || {
            log_ipc_failure("PKSIGN", &error);
            log_ipc_failure("LIST_KEYS", &anyhow::anyhow!("private-transport-detail"));
        });

        let logs = std::fs::read_to_string(output.path()).unwrap();
        assert!(logs.contains("PKSIGN"), "{logs}");
        assert!(logs.contains("UserDenied"), "{logs}");
        assert!(logs.contains("LIST_KEYS"), "{logs}");
        assert!(!logs.contains("private-"), "{logs}");
    }

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
}
