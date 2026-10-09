//! Bounded local-vault FIDO2 client. No account data or secret-bearing diagnostics cross the ABI.
// Targets without a backend only validate requests.
#![cfg_attr(
    not(any(target_os = "linux", target_os = "macos", target_os = "windows")),
    allow(dead_code)
)]
#[cfg(any(target_os = "linux", target_os = "macos"))]
mod ctap;
#[cfg(target_os = "windows")]
mod windows;

mod assertion;

use keyguard_ffi::{OperationLimits, OperationRegistry, OperationStatus, respond};
use sha2::{Digest, Sha256};
use std::time::Duration;

pub const ABI_VERSION: u32 = 2;
pub(crate) const INPUT_LENGTH: usize = 32;
pub(crate) const SECRET_LENGTH: usize = 32;
pub(crate) const MAX_CREDENTIAL: usize = 1024;
const MAX_PIN: usize = 63;
const CHALLENGE_OFFSET: usize = 4;
const INPUT_OFFSET: usize = CHALLENGE_OFFSET + INPUT_LENGTH;
pub const HEADER_LENGTH: usize = INPUT_OFFSET + INPUT_LENGTH;
pub const MAX_REQUEST: usize = 131072;
pub const MAX_RESPONSE: usize = 131072;
pub(crate) const RP_ID: &str = "keyguard.dev";
pub(crate) const TIMEOUT: Duration = Duration::from_secs(60);
pub(crate) const POLL_INTERVAL: Duration = Duration::from_millis(100);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Error {
    InvalidArgument = 1,
    Unsupported = 2,
    PinRequired = 3,
    InvalidPin = 4,
    PinBlocked = 5,
    PinNotSet = 6,
    Timeout = 7,
    Canceled = 8,
    Protocol = 9,
    Busy = 10,
    Internal = 11,
    Rejected = 12,
}

impl OperationStatus for Error {
    const INVALID_ARGUMENT: Self = Self::InvalidArgument;
    const CANCELED: Self = Self::Canceled;
    const PROTOCOL: Self = Self::Protocol;
    const BUSY: Self = Self::Busy;
    const INTERNAL: Self = Self::Internal;

    fn code(self) -> u8 {
        self as u8
    }
}

/// Request and response bounds of the C and JNI adapters.
pub const LIMITS: OperationLimits = OperationLimits {
    min_request: 1,
    max_request: MAX_REQUEST,
    max_response: MAX_RESPONSE,
};

static OPERATIONS: OperationRegistry = OperationRegistry::new();

pub fn create() -> u64 {
    OPERATIONS.create()
}

pub fn cancel(id: u64) {
    OPERATIONS.cancel(id);
}

pub fn close(id: u64) {
    OPERATIONS.close(id);
}

pub fn execute(id: u64, bytes: &[u8]) -> Vec<u8> {
    respond(run(id, bytes), MAX_RESPONSE)
}

fn run(id: u64, bytes: &[u8]) -> Result<Vec<u8>, Error> {
    let request = Request::parse(bytes)?;
    let operation = OPERATIONS.begin::<Error>(id)?;
    #[cfg(any(target_os = "linux", target_os = "macos"))]
    {
        ctap::execute(&request, operation.canceled())
    }
    #[cfg(target_os = "windows")]
    {
        windows::execute(&request, operation.canceled())
    }
    #[cfg(not(any(target_os = "linux", target_os = "macos", target_os = "windows")))]
    {
        let _ = (request, operation);
        Err(Error::Unsupported)
    }
}

#[derive(Clone, Copy)]
pub(crate) enum Operation<'a> {
    Register,
    Derive { credential: &'a [u8] },
    Assert,
}

/// Borrows the caller's request buffer, which the caller clears after execute.
pub(crate) struct Request<'a> {
    operation: Operation<'a>,
    assertion: Option<assertion::AssertionRequest>,
    challenge: [u8; INPUT_LENGTH],
    /// The user ID when registering, or the PRF salt when deriving.
    input: [u8; INPUT_LENGTH],
    #[cfg_attr(not(any(target_os = "linux", target_os = "macos")), allow(dead_code))]
    pin: &'a str,
}

impl<'a> Request<'a> {
    fn parse(bytes: &'a [u8]) -> Result<Self, Error> {
        if bytes.first() == Some(&3) {
            return assertion::parse(&bytes[1..]);
        }
        if !(HEADER_LENGTH..=MAX_REQUEST).contains(&bytes.len()) {
            return Err(Error::InvalidArgument);
        }
        let credential_len = usize::from(u16::from_be_bytes([bytes[1], bytes[2]]));
        let pin_len = usize::from(bytes[3]);
        if pin_len > MAX_PIN || bytes.len() != HEADER_LENGTH + credential_len + pin_len {
            return Err(Error::InvalidArgument);
        }
        let (credential, pin) = bytes[HEADER_LENGTH..].split_at(credential_len);
        let operation = match (bytes[0], credential_len) {
            (1, 0) => Operation::Register,
            (2, 1..=MAX_CREDENTIAL) => Operation::Derive { credential },
            _ => return Err(Error::InvalidArgument),
        };
        let pin = std::str::from_utf8(pin).map_err(|_| Error::InvalidArgument)?;
        if pin.contains('\0') {
            return Err(Error::InvalidArgument);
        }
        Ok(Self {
            operation,
            assertion: None,
            challenge: bytes[CHALLENGE_OFFSET..INPUT_OFFSET]
                .try_into()
                .map_err(|_| Error::InvalidArgument)?,
            input: bytes[INPUT_OFFSET..HEADER_LENGTH]
                .try_into()
                .map_err(|_| Error::InvalidArgument)?,
            pin,
        })
    }
}

/// Requires the local RP and both user presence and user verification.
pub(crate) fn validate_auth_data(rp_id_hash: &[u8], flags: u8) -> Result<(), Error> {
    const PRESENT_AND_VERIFIED: u8 = 0x01 | 0x04;
    if rp_id_hash != Sha256::digest(RP_ID.as_bytes()).as_slice()
        || flags & PRESENT_AND_VERIFIED != PRESENT_AND_VERIFIED
    {
        return Err(Error::Protocol);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn malformed_requests_fail_before_device_access() {
        let mut register = vec![0; 68];
        register[0] = 1;
        assert!(Request::parse(&register).is_ok());
        for size in 0..68 {
            assert!(Request::parse(&register[..size]).is_err());
        }
        register[0] = 2;
        assert!(Request::parse(&register).is_err());
        register[2] = 1;
        register.push(42);
        assert!(Request::parse(&register).is_ok());
        register[3] = 1;
        register.push(0);
        assert!(Request::parse(&register).is_err());
        *register.last_mut().unwrap() = 0xff;
        assert!(Request::parse(&register).is_err());
    }
    #[test]
    fn requires_expected_rp_and_both_presence_and_verification() {
        let mut rp_id_hash = Sha256::digest(RP_ID.as_bytes()).to_vec();
        assert!(validate_auth_data(&rp_id_hash, 5).is_ok());
        for flags in [0, 1, 4] {
            assert_eq!(validate_auth_data(&rp_id_hash, flags), Err(Error::Protocol));
        }
        assert_eq!(
            validate_auth_data(&rp_id_hash[..31], 5),
            Err(Error::Protocol)
        );
        rp_id_hash[0] ^= 1;
        assert_eq!(validate_auth_data(&rp_id_hash, 5), Err(Error::Protocol));
    }
    #[test]
    fn canceled_handles_cannot_access_devices() {
        let id = create();
        let mut request = vec![0; 68];
        request[0] = 1;
        cancel(id);
        assert_eq!(execute(id, &request), vec![Error::Canceled as u8]);
        close(id);
        close(id);
        assert_eq!(execute(id, &request), vec![Error::InvalidArgument as u8]);
    }
}
