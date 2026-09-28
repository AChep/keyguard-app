//! YubiOTP operations shared by JVM desktop and native macOS.
//! The wire boundary contains no diagnostics or secret-bearing log messages.
mod protocol;
#[cfg(any(target_os = "linux", target_os = "windows", target_os = "macos"))]
mod transport;

use std::collections::BTreeMap;
use std::sync::{
    Arc, Mutex,
    atomic::{AtomicBool, AtomicU64, Ordering},
};
use zeroize::Zeroizing;

pub const ABI_VERSION: u32 = 1;
pub const HEADER_LENGTH: usize = 4;
// HMAC_LT64 reserves the final byte of the 64-byte payload for padding.
const MAX_CHALLENGE_LENGTH: usize = 63;
const SECRET_LENGTH: usize = 20;
const RESPONSE_LENGTH: usize = 20;
pub const MAX_REQUEST: usize = HEADER_LENGTH + MAX_CHALLENGE_LENGTH + SECRET_LENGTH;
pub const MAX_RESPONSE: usize = 1 + RESPONSE_LENGTH;

/// Stable error codes shared by C, JNI and Kotlin. Never reorder these values.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Error {
    InvalidArgument = 1,
    Unsupported = 2,
    NoDevice = 3,
    MultipleDevices = 4,
    Io = 5,
    NotConfigured = 6,
    ConfirmationRequired = 7,
    Rejected = 8,
    Timeout = 9,
    Canceled = 10,
    Protocol = 11,
    Busy = 12,
    Internal = 13,
}

static NEXT_ID: AtomicU64 = AtomicU64::new(1);
static OPERATIONS: Mutex<BTreeMap<u64, Arc<AtomicBool>>> = Mutex::new(BTreeMap::new());
static DEVICE_LOCK: Mutex<()> = Mutex::new(());

/// Allocates a cancellation token, not a device. Returns zero on failure.
pub fn create() -> u64 {
    let Ok(mut operations) = OPERATIONS.lock() else {
        return 0;
    };
    if operations.len() >= 64 {
        return 0;
    }
    let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
    if id == 0 || id > i64::MAX as u64 {
        return 0;
    }
    operations.insert(id, Arc::new(AtomicBool::new(false)));
    id
}

/// Can run concurrently with execute; never touches the HID handle.
pub fn cancel(id: u64) {
    if let Ok(operations) = OPERATIONS.lock()
        && let Some(token) = operations.get(&id)
    {
        token.store(true, Ordering::Release);
    }
}

/// Removes the token; an in-flight operation retains its own Arc and is canceled.
pub fn close(id: u64) {
    if let Ok(mut operations) = OPERATIONS.lock()
        && let Some(token) = operations.remove(&id)
    {
        token.store(true, Ordering::Release);
    }
}

/// Executes a bounded binary request. The first response byte is zero or an Error code.
pub fn execute(id: u64, bytes: &[u8]) -> Vec<u8> {
    match run(id, bytes) {
        Ok(body) => {
            let mut result = vec![0];
            result.extend_from_slice(&Zeroizing::new(body));
            result
        }
        Err(error) => vec![error as u8],
    }
}

fn run(id: u64, bytes: &[u8]) -> Result<Vec<u8>, Error> {
    let request = Request::parse(bytes)?;
    let token = OPERATIONS
        .lock()
        .map_err(|_| Error::Internal)?
        .get(&id)
        .cloned()
        .ok_or(Error::InvalidArgument)?;
    if token.load(Ordering::Acquire) {
        return Err(Error::Canceled);
    }
    let _guard = DEVICE_LOCK.try_lock().map_err(|_| Error::Busy)?;
    #[cfg(any(target_os = "linux", target_os = "windows", target_os = "macos"))]
    {
        let mut device = transport::open()?;
        protocol::execute(&mut device, &request, &token)
    }
    #[cfg(not(any(target_os = "linux", target_os = "windows", target_os = "macos")))]
    {
        let _ = (request, token);
        Err(Error::Unsupported)
    }
}

enum Operation {
    Inspect,
    ChallengeResponse,
    Provision {
        secret: Zeroizing<Vec<u8>>,
        overwrite: bool,
        require_touch: bool,
    },
}

struct Request {
    slot: u8,
    challenge: Vec<u8>,
    operation: Operation,
}
impl Request {
    fn parse(bytes: &[u8]) -> Result<Self, Error> {
        if !(HEADER_LENGTH..=MAX_REQUEST).contains(&bytes.len()) {
            return Err(Error::InvalidArgument);
        }
        let (header, body) = bytes.split_at(HEADER_LENGTH);
        let (tag, slot, flags, length) = (header[0], header[1], header[2], usize::from(header[3]));
        if !(1..=2).contains(&slot) || length > MAX_CHALLENGE_LENGTH {
            return Err(Error::InvalidArgument);
        }
        let (challenge, secret) = body
            .split_at_checked(length)
            .ok_or(Error::InvalidArgument)?;
        let operation = match (tag, flags, length, secret.len()) {
            (1, 0, 0, 0) => Operation::Inspect,
            (2, 0, 1.., 0) => Operation::ChallengeResponse,
            (3, 0..=3, 1.., SECRET_LENGTH) => Operation::Provision {
                secret: Zeroizing::new(secret.to_vec()),
                overwrite: flags & 1 != 0,
                require_touch: flags & 2 != 0,
            },
            _ => return Err(Error::InvalidArgument),
        };
        Ok(Self {
            slot,
            challenge: challenge.to_vec(),
            operation,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn challenge_lengths_leave_room_for_hmac_lt64_padding() {
        for tag in [2, 3] {
            for length in [0, 1, 63, 64, 65] {
                let mut wire = vec![tag, 2, 0, length];
                wire.extend(vec![1; usize::from(length)]);
                if tag == 3 {
                    wire.extend([9; SECRET_LENGTH]);
                }
                let result = Request::parse(&wire);
                if length == 1 || length == 63 {
                    assert!(result.is_ok(), "tag={tag}, length={length}");
                } else {
                    assert!(
                        matches!(result, Err(Error::InvalidArgument)),
                        "tag={tag}, length={length}"
                    );
                }
            }
        }
    }

    #[test]
    fn malformed_requests_fail_before_device_access() {
        for request in [
            vec![],
            vec![1, 0, 0, 0],
            vec![1, 3, 0, 0],
            vec![2, 2, 0, 65],
            vec![3, 2, 0, 0],
            vec![1, 2, 1, 0],
            vec![4, 2, 0, 0],
        ] {
            assert_eq!(execute(0, &request), vec![Error::InvalidArgument as u8]);
        }
    }
    #[test]
    fn cancellation_and_close_are_idempotent() {
        let id = create();
        assert_ne!(id, 0);
        cancel(id);
        cancel(id);
        assert_eq!(execute(id, &[1, 2, 0, 0]), vec![Error::Canceled as u8]);
        close(id);
        close(id);
        assert_eq!(
            execute(id, &[1, 2, 0, 0]),
            vec![Error::InvalidArgument as u8]
        );
    }
}
