//! Assertion wire format shared with Kotlin. All fields are length bounded before allocation.
use crate::{Error, MAX_CREDENTIAL, MAX_PIN, MAX_REQUEST, Operation, Request};
use sha2::{Digest, Sha256};
use std::time::Duration;

#[derive(Clone)]
pub(crate) struct AssertionRequest {
    pub rp_id: String,
    #[cfg_attr(target_os = "windows", allow(dead_code))]
    pub origin: String,
    pub client_data: Vec<u8>,
    pub app_id: Option<String>,
    pub credentials: Vec<(Vec<u8>, u32)>,
    pub verification: u32,
    pub timeout: Duration,
}

pub(crate) fn parse(bytes: &[u8]) -> Result<Request<'_>, Error> {
    if bytes.len() >= MAX_REQUEST {
        return Err(Error::InvalidArgument);
    }
    let mut reader = Reader(bytes);
    let verification = reader.int()?;
    let timeout = reader.int()?;
    if verification > 2 || !(1..=120_000).contains(&timeout) {
        return Err(Error::InvalidArgument);
    }
    let rp_id = reader.text(1, 253)?.to_owned();
    let origin = reader.text(1, 2048)?.to_owned();
    let client_data = reader.bytes(1, 8192)?.to_vec();
    let app_id = reader.text(0, 2048)?;
    let app_id = (!app_id.is_empty()).then(|| app_id.to_owned());
    let count = reader.int()?;
    if !(1..=64).contains(&count) {
        return Err(Error::InvalidArgument);
    }
    let mut credentials = Vec::with_capacity(count as usize);
    for _ in 0..count {
        let id = reader.bytes(1, MAX_CREDENTIAL)?.to_vec();
        let transports = reader.int()?;
        if transports & !55 != 0 {
            return Err(Error::InvalidArgument);
        }
        credentials.push((id, transports));
    }
    let pin = reader.text(0, MAX_PIN)?;
    if !reader.0.is_empty() {
        return Err(Error::InvalidArgument);
    }
    Ok(Request {
        operation: Operation::Assert,
        assertion: Some(AssertionRequest {
            rp_id,
            origin,
            client_data,
            app_id,
            credentials,
            verification,
            timeout: Duration::from_millis(timeout.into()),
        }),
        challenge: [0; 32],
        input: [0; 32],
        pin,
    })
}

impl AssertionRequest {
    pub fn encode_result(
        &self,
        credential: &[u8],
        auth_data: &[u8],
        signature: &[u8],
        user_handle: &[u8],
        app_id_used: bool,
    ) -> Result<Vec<u8>, Error> {
        let rp = if app_id_used {
            self.app_id.as_deref().ok_or(Error::Protocol)?
        } else {
            &self.rp_id
        };
        let flags = if self.verification == 2 { 5 } else { 1 };
        if !(37..=65536).contains(&auth_data.len())
            || !(1..=4096).contains(&signature.len())
            || user_handle.len() > 64
            || !self.credentials.iter().any(|(id, _)| id == credential)
            || auth_data[..32] != Sha256::digest(rp.as_bytes())[..]
            || auth_data[32] & flags != flags
        {
            return Err(Error::Protocol);
        }
        let mut result = u32::from(app_id_used).to_be_bytes().to_vec();
        for bytes in [
            credential,
            auth_data,
            signature,
            user_handle,
            &self.client_data,
        ] {
            result.extend_from_slice(&(bytes.len() as u32).to_be_bytes());
            result.extend_from_slice(bytes);
        }
        Ok(result)
    }
}

struct Reader<'a>(&'a [u8]);
impl<'a> Reader<'a> {
    fn int(&mut self) -> Result<u32, Error> {
        let (number, rest) = self.0.split_at_checked(4).ok_or(Error::InvalidArgument)?;
        self.0 = rest;
        Ok(u32::from_be_bytes(
            number.try_into().map_err(|_| Error::InvalidArgument)?,
        ))
    }
    fn bytes(&mut self, min: usize, max: usize) -> Result<&'a [u8], Error> {
        let length = self.int()? as usize;
        if !(min..=max).contains(&length) {
            return Err(Error::InvalidArgument);
        }
        let (value, rest) = self
            .0
            .split_at_checked(length)
            .ok_or(Error::InvalidArgument)?;
        self.0 = rest;
        Ok(value)
    }
    fn text(&mut self, min: usize, max: usize) -> Result<&'a str, Error> {
        let value =
            std::str::from_utf8(self.bytes(min, max)?).map_err(|_| Error::InvalidArgument)?;
        if value.contains('\0') {
            return Err(Error::InvalidArgument);
        }
        Ok(value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn request() -> AssertionRequest {
        AssertionRequest {
            rp_id: "vault.example.com".into(),
            origin: "https://vault.example.com".into(),
            client_data: b"client data".to_vec(),
            app_id: Some("https://vault.example.com/app-id.json".into()),
            credentials: vec![(vec![1, 2], 1)],
            verification: 0,
            timeout: Duration::from_secs(60),
        }
    }

    fn wire() -> Vec<u8> {
        let a = request();
        let mut bytes = vec![];
        bytes.extend_from_slice(&a.verification.to_be_bytes());
        bytes.extend_from_slice(&60_000_u32.to_be_bytes());
        for value in [
            a.rp_id.as_bytes(),
            a.origin.as_bytes(),
            &a.client_data,
            a.app_id.as_ref().unwrap().as_bytes(),
        ] {
            bytes.extend_from_slice(&(value.len() as u32).to_be_bytes());
            bytes.extend_from_slice(value);
        }
        bytes.extend_from_slice(&1_u32.to_be_bytes());
        bytes.extend_from_slice(&2_u32.to_be_bytes());
        bytes.extend_from_slice(&[1, 2]);
        bytes.extend_from_slice(&1_u32.to_be_bytes());
        bytes.extend_from_slice(&0_u32.to_be_bytes());
        bytes
    }

    #[test]
    fn rejects_truncated_oversized_and_trailing_requests() {
        let bytes = wire();
        assert!(parse(&bytes).is_ok());
        for length in 0..bytes.len() {
            assert!(parse(&bytes[..length]).is_err());
        }
        let mut trailing = bytes.clone();
        trailing.push(0);
        assert!(parse(&trailing).is_err());
        let mut oversized = bytes;
        oversized[8..12].copy_from_slice(&u32::MAX.to_be_bytes());
        assert!(parse(&oversized).is_err());
    }

    #[test]
    fn requires_credential_rp_presence_and_requested_verification() {
        let mut a = request();
        let mut data = Sha256::digest(a.rp_id.as_bytes()).to_vec();
        data.extend_from_slice(&[1, 0, 0, 0, 1]);
        assert!(a.encode_result(&[1, 2], &data, &[1], &[], false).is_ok());
        assert!(a.encode_result(&[9], &data, &[1], &[], false).is_err());
        assert!(a.encode_result(&[1, 2], &data, &[1], &[], true).is_err());
        a.verification = 2;
        assert!(a.encode_result(&[1, 2], &data, &[1], &[], false).is_err());
        data[32] = 5;
        assert!(a.encode_result(&[1, 2], &data, &[1], &[], false).is_ok());
        data[0] ^= 1;
        assert!(a.encode_result(&[1, 2], &data, &[1], &[], false).is_err());
    }
}
