//! Bounded, handle-based ZIP-to-age streams. Only ciphertext is ever stored on disk.
//! The caller drains writer output after each command. Readers spool ciphertext
//! into an unnamed private file, authenticate the complete age stream before
//! exposing ZIP entries, then seek within ciphertext.

mod reader;
mod spool;
mod writer;

use age::secrecy::ExposeSecret;
use keyguard_ffi::{OperationLimits, OperationStatus, SessionError, SessionTable, SessionTag};
use std::{
    io::Read,
    sync::{Mutex, PoisonError},
};
use zeroize::Zeroizing;

/// Native protocol version.
pub const ABI_VERSION: u32 = 1;
/// Maximum data crossing the bridge in one call.
pub const CHUNK: usize = 64 * 1024;
/// Password KDF cost: 128 MiB, independent of the creating device.
pub const LOG_N: u8 = 17;
/// Accept reference age files up to 256 MiB, never unbounded work from a header.
pub const MAX_LOG_N: u8 = 18;
/// Maximum number of ZIP members.
pub const MAX_ENTRIES: usize = 100_000;
/// Maximum UTF-8 entry name length.
pub const MAX_NAME: usize = 1024;
/// Request and response limits, including the status byte.
pub const LIMITS: OperationLimits = OperationLimits {
    min_request: 1,
    max_request: CHUNK + 1,
    max_response: CHUNK + 1,
};

/// Stable failure categories; diagnostics never include secrets or paths.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Error {
    /// Invalid command or session state.
    Invalid = 1,
    /// Invalid password or unauthentic header.
    Password = 2,
    /// Invalid ZIP or unauthentic ciphertext.
    Corrupt = 3,
    /// File I/O failed.
    Io = 4,
    /// Resource or KDF limit exceeded.
    Limit = 5,
    /// Native implementation failed.
    Internal = 6,
}
impl OperationStatus for Error {
    const INVALID_ARGUMENT: Self = Self::Invalid;
    const CANCELED: Self = Self::Invalid;
    const PROTOCOL: Self = Self::Corrupt;
    const BUSY: Self = Self::Limit;
    const INTERNAL: Self = Self::Internal;
    fn code(self) -> u8 {
        self as u8
    }
}

/// The secret an archive is encrypted to.
pub(crate) enum Key {
    /// scrypt at [`LOG_N`] for writing, at most [`MAX_LOG_N`] for reading.
    Password(Zeroizing<String>),
    /// An X25519 identity; involves no key derivation.
    Identity(age::x25519::Identity),
}
impl Key {
    fn parse(op: u8, bytes: &[u8]) -> Result<Self, Error> {
        if op == OP_CREATE_WRITER || op == OP_OPEN_READER {
            return password(bytes).map(Self::Password);
        }
        let identity = text(bytes)?.parse().map_err(|_| Error::Invalid)?;
        Ok(Self::Identity(identity))
    }
    /// Serializes scrypt derivations to bound their memory to one at a time.
    fn kdf_guard(&self) -> Option<std::sync::MutexGuard<'static, ()>> {
        matches!(self, Self::Password(_))
            .then(|| KDF.lock().unwrap_or_else(PoisonError::into_inner))
    }
}

const OP_CREATE_WRITER: u8 = 1;
const OP_OPEN_READER: u8 = 7;
const OP_CREATE_WRITER_KEY: u8 = 13;
const OP_OPEN_READER_KEY: u8 = 14;
const OP_GENERATE_IDENTITY: u8 = 15;

enum State {
    New,
    Writer(Box<writer::Writer>),
    Output(writer::Output),
    Spooling(spool::Spool),
    Reader(reader::Reader),
    Verifying(reader::Input),
    Failed,
}
// Sessions run in parallel; only the password KDF is serialized.
static SESSIONS: SessionTable<State> = SessionTable::new(SessionTag::new(1), 16);
// Bounds simultaneous scrypt memory to one derivation. Holds no state, so a
// panic during a derivation leaves nothing to repair.
static KDF: Mutex<()> = Mutex::new(());

/// Allocates an empty session; zero means the resource limit was reached.
pub fn create() -> u64 {
    SESSIONS.insert(State::New).unwrap_or(0)
}

/// Releases a session, including unfinished writers and their keys. A session
/// whose command panicked is released too.
pub fn close(id: u64) {
    let _ = SESSIONS.take(id);
}

/// Executes one bounded command. A failed session can only be closed.
pub fn execute(id: u64, request: &[u8]) -> Vec<u8> {
    let result = (|| {
        if request.is_empty() || request.len() > LIMITS.max_request {
            return Err(Error::Invalid);
        }
        SESSIONS
            .with(id, |state| {
                let result = command(state, request[0], &request[1..]);
                if result.is_err() {
                    *state = State::Failed;
                }
                result
            })
            .map_err(|error| match error {
                SessionError::Unknown => Error::Invalid,
                // An earlier command on this session panicked.
                _ => Error::Internal,
            })?
    })();
    match result {
        Ok(bytes) => {
            let bytes = Zeroizing::new(bytes);
            let mut out = Vec::with_capacity(bytes.len() + 1);
            out.push(0);
            out.extend_from_slice(&bytes);
            out
        }
        Err(error) => vec![error as u8],
    }
}

fn command(state: &mut State, op: u8, bytes: &[u8]) -> Result<Vec<u8>, Error> {
    match op {
        OP_CREATE_WRITER | OP_CREATE_WRITER_KEY if matches!(state, State::New) => {
            let key = Key::parse(op, bytes)?;
            let _kdf = key.kdf_guard();
            *state = State::Writer(Box::new(writer::Writer::new(&key)?));
        }
        OP_GENERATE_IDENTITY if bytes.is_empty() && matches!(state, State::New) => {
            // The session stays new; execute zeroizes the returned bytes.
            let identity = age::x25519::Identity::generate().to_string();
            return Ok(identity.expose_secret().as_bytes().to_vec());
        }
        2 => match state {
            State::Writer(w) => w.begin(text(bytes)?)?,
            _ => return Err(Error::Invalid),
        },
        3 => match state {
            State::Writer(w) => w.write(bytes)?,
            _ => return Err(Error::Invalid),
        },
        4 if bytes.is_empty() => match state {
            State::Writer(w) => w.end()?,
            _ => return Err(Error::Invalid),
        },
        5 if bytes.is_empty() => {
            let State::Writer(w) = std::mem::replace(state, State::Failed) else {
                return Err(Error::Invalid);
            };
            *state = State::Output(w.finish()?);
        }
        6 if bytes.is_empty() => {
            return match state {
                State::Writer(w) => w.output.drain(),
                State::Output(o) => o.drain(),
                _ => Err(Error::Invalid),
            };
        }
        11 if matches!(state, State::New) => {
            *state = State::Spooling(spool::Spool::create(text(bytes)?)?);
        }
        12 => match state {
            State::Spooling(s) => s.write(bytes)?,
            _ => return Err(Error::Invalid),
        },
        OP_OPEN_READER | OP_OPEN_READER_KEY if matches!(state, State::Spooling(_)) => {
            let key = Key::parse(op, bytes)?;
            let State::Spooling(spool) = std::mem::replace(state, State::Failed) else {
                return Err(Error::Invalid);
            };
            let file = spool.into_file()?;
            let _kdf = key.kdf_guard();
            *state = State::Verifying(reader::Reader::decrypt(file, key)?);
        }
        10 if bytes.is_empty() => {
            let State::Verifying(input) = state else {
                return Err(Error::Invalid);
            };
            let mut buffer = Zeroizing::new([0; CHUNK]);
            let count = input.read(&mut *buffer).map_err(|_| Error::Corrupt)?;
            if count == 0 {
                let State::Verifying(input) = std::mem::replace(state, State::Failed) else {
                    return Err(Error::Invalid);
                };
                *state = State::Reader(reader::Reader::from_verified(input)?);
                return Ok(vec![1]);
            }
            return Ok(vec![0]);
        }
        8 if bytes.is_empty() => {
            return match state {
                State::Reader(r) => r.next(),
                _ => Err(Error::Invalid),
            };
        }
        9 if bytes.is_empty() => {
            return match state {
                State::Reader(r) => r.read(),
                _ => Err(Error::Invalid),
            };
        }
        _ => return Err(Error::Invalid),
    }
    Ok(Vec::new())
}
fn text(bytes: &[u8]) -> Result<&str, Error> {
    std::str::from_utf8(bytes).map_err(|_| Error::Invalid)
}
fn password(bytes: &[u8]) -> Result<Zeroizing<String>, Error> {
    if bytes.is_empty() || bytes.len() > 4096 {
        return Err(Error::Invalid);
    }
    Ok(Zeroizing::new(text(bytes)?.to_owned()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::panic::{AssertUnwindSafe, catch_unwind};

    /// A panic used to poison one process-wide lock, failing every later
    /// backup, export and import until the app restarted.
    #[test]
    fn a_panicked_session_does_not_break_other_sessions() {
        let broken = create();
        let healthy = create();
        assert_ne!(broken, 0);
        assert_ne!(healthy, 0);

        let panic = catch_unwind(AssertUnwindSafe(|| {
            SESSIONS.with(broken, |_| panic!("simulated command failure"))
        }));
        assert!(panic.is_err());

        assert_eq!(execute(broken, &[6]), vec![Error::Internal as u8]);
        assert_eq!(execute(broken, &[6]), vec![Error::Internal as u8]);

        let mut request = vec![1];
        request.extend_from_slice(b"password");
        assert_eq!(execute(healthy, &request), vec![0]);
        assert_eq!(execute(healthy, &[6]).first(), Some(&0));

        close(broken);
        assert_eq!(execute(broken, &[6]), vec![Error::Invalid as u8]);
        let next = create();
        assert_ne!(next, 0, "new sessions are still accepted");
        close(next);
        close(healthy);
    }
}
