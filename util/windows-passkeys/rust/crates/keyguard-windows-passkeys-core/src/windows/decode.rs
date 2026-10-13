//! Reads OS-decoded requests. Every borrow is tied to the structure that retains it, and
//! every field is copied out before the decoder's allocation is freed.
use super::{
    Allocation, MAX_TEXT, Provider,
    abi::{Free, GetRequest, MakeRequest, Options},
    len_u32,
};
use crate::{
    error::Error,
    protocol::{
        Descriptor, ES256, MAX_ALGORITHMS, MAX_CREDENTIAL_ID, MAX_DESCRIPTORS, MAX_USER_ID,
        Request, User, UserVerification,
    },
};
use std::{ptr, slice};
use windows::{
    Win32::Networking::WindowsWebServices::WEBAUTHN_CREDENTIAL_LIST,
    core::{HRESULT, PCWSTR},
};

const CLIENT_DATA_HASH: usize = 32;

/// Borrows the `T` at `pointer` for as long as `owner`; null reads as `None`.
///
/// # Safety
/// A non-null `pointer` must address an initialized `T` that `owner` retains.
pub(super) unsafe fn read_optional<O: ?Sized, T>(
    _owner: &O,
    pointer: *const T,
) -> Result<Option<&T>, Error> {
    if pointer.is_null() {
        return Ok(None);
    }
    if !pointer.is_aligned() {
        return Err(Error::Invalid);
    }
    // SAFETY: Non-null and aligned; the caller guarantees a value that `owner` retains.
    Ok(Some(unsafe { &*pointer }))
}

/// Like [`read_optional`], but null is invalid.
///
/// # Safety
/// Same contract as [`read_optional`].
pub(super) unsafe fn read_ref<O: ?Sized, T>(owner: &O, pointer: *const T) -> Result<&T, Error> {
    // SAFETY: The caller upholds the same contract.
    unsafe { read_optional(owner, pointer) }?.ok_or(Error::Invalid)
}

/// Borrows `count` elements at `pointer` for as long as `owner`, rejecting more than `max`.
///
/// # Safety
/// For a nonzero `count`, `pointer` must address `count` initialized elements that `owner`
/// retains.
pub(super) unsafe fn read_slice<O: ?Sized, T>(
    _owner: &O,
    pointer: *const T,
    count: u32,
    max: usize,
) -> Result<&[T], Error> {
    if count == 0 {
        return Ok(&[]);
    }
    if pointer.is_null() || !pointer.is_aligned() || count as usize > max {
        return Err(Error::Invalid);
    }
    // SAFETY: Non-null, aligned, and bounded; the caller guarantees the elements.
    Ok(unsafe { slice::from_raw_parts(pointer, count as usize) })
}

/// Copies a NUL-terminated UTF-16 string of at most [`MAX_TEXT`] units; null reads as empty.
///
/// # Safety
/// A non-null `pointer` must address a NUL-terminated UTF-16 string.
pub(super) unsafe fn read_string(pointer: PCWSTR) -> Result<String, Error> {
    if pointer.is_null() {
        return Ok(String::new());
    }
    if !pointer.0.is_aligned() {
        return Err(Error::Invalid);
    }
    // Include index MAX_TEXT: a maximal string written by `wide` has its NUL there.
    for length in 0..=MAX_TEXT {
        // SAFETY: No terminator precedes `length`, so this unit is still inside the string.
        if unsafe { pointer.0.wrapping_add(length).read() } == 0 {
            // SAFETY: The units before the terminator are initialized string storage.
            let units = unsafe { slice::from_raw_parts(pointer.0, length) };
            return String::from_utf16(units).map_err(|_| Error::Invalid);
        }
    }
    Err(Error::Invalid)
}

/// # Safety
/// `list` must be an OS-decoded credential list, which retains its descriptors.
unsafe fn descriptors(list: &WEBAUTHN_CREDENTIAL_LIST) -> Result<Vec<Descriptor>, Error> {
    // SAFETY: The list owns `cCredentials` descriptor pointers.
    let pointers =
        unsafe { read_slice(list, list.ppCredentials, list.cCredentials, MAX_DESCRIPTORS) }?;
    pointers
        .iter()
        .map(|&pointer| {
            // SAFETY: Each pointer addresses a descriptor that the list retains.
            let value = unsafe { read_ref(list, pointer) }?;
            // SAFETY: The descriptor owns `cbId` id bytes.
            let id = unsafe { read_slice(value, value.pbId, value.cbId, MAX_CREDENTIAL_ID) }?;
            if id.is_empty() {
                return Err(Error::Invalid);
            }
            Ok(Descriptor {
                id: id.to_vec(),
                // SAFETY: The descriptor owns its NUL-terminated credential type.
                r#type: unsafe { read_string(value.pwszCredentialType) }?,
            })
        })
        .collect()
}

/// Accepts any decoded version. Later versions append fields, and only the version-1 prefix
/// is read.
fn check_version(version: u32) -> Result<(), Error> {
    if version < 1 {
        return Err(Error::Invalid);
    }
    Ok(())
}

/// The user-verification requirement and whether a discoverable credential is requested.
fn options(value: Option<&Options>) -> Result<(UserVerification, bool), Error> {
    let Some(value) = value else {
        return Ok((UserVerification::Preferred, true));
    };
    check_version(value.version)?;
    // Silent assertions are not supported: signing always requires user presence.
    if value.up == -1 {
        return Err(Error::Invalid);
    }
    let uv = match value.uv {
        1 => UserVerification::Required,
        -1 => UserVerification::Discouraged,
        0 => UserVerification::Preferred,
        _ => return Err(Error::Invalid),
    };
    Ok((uv, value.rk != -1))
}

/// The fields that both decoded requests share, read from the decoder's allocation.
struct Shared<'a> {
    rp: &'a [u8],
    hash: &'a [u8],
    credentials: Vec<Descriptor>,
    options: Option<&'a Options>,
}

/// Reads the [`Shared`] fields, which `MakeRequest` and `GetRequest` name alike.
macro_rules! shared {
    ($data:expr) => {{
        let data = $data;
        Shared {
            // SAFETY: The decoded request owns `rp_len` RP ID bytes.
            rp: unsafe { read_slice(data, data.rp, data.rp_len, MAX_TEXT) }?,
            // SAFETY: The decoded request owns `hash_len` client-data hash bytes.
            hash: unsafe { read_slice(data, data.hash, data.hash_len, CLIENT_DATA_HASH) }?,
            // SAFETY: The decoded request owns its credential list and descriptors.
            credentials: unsafe { descriptors(&data.credentials) }?,
            // SAFETY: The decoded request owns its optional options.
            options: unsafe { read_optional(data, data.options) }?,
        }
    }};
}

impl Shared<'_> {
    fn into_request(self, create: bool, window: i64) -> Result<Request, Error> {
        // Hash the exact UTF-8 RP bytes, without case folding or URL normalization.
        let rp_id = std::str::from_utf8(self.rp).map_err(|_| Error::Invalid)?;
        if rp_id.is_empty() || rp_id.contains('\0') || self.hash.len() != CLIENT_DATA_HASH {
            return Err(Error::Invalid);
        }
        let (user_verification, discoverable) = options(self.options)?;
        Ok(Request {
            create,
            window,
            rp_id: rp_id.to_owned(),
            client_data_hash: self.hash.to_vec(),
            user_verification: user_verification.into(),
            credentials: self.credentials,
            discoverable,
            ..Request::default()
        })
    }
}

impl Provider {
    pub(super) fn decode(&self, create: bool, bytes: &[u8], window: i64) -> Result<Request, Error> {
        if create {
            self.decode_make(bytes, window)
        } else {
            self.decode_get(bytes, window)
        }
    }

    fn decode_make(&self, bytes: &[u8], window: i64) -> Result<Request, Error> {
        let allocation = decoded(self.api.decode_make, self.api.free_make, bytes)?;
        // SAFETY: A successful decode returns a MakeRequest.
        let data = unsafe { allocation.get::<MakeRequest>() }?;
        check_version(data.version)?;
        // SAFETY: The decoded request owns its user entity.
        let user = unsafe { read_ref(data, data.user) }?;
        // SAFETY: The user entity owns `cbId` id bytes.
        let user_id = unsafe { read_slice(user, user.pbId, user.cbId, MAX_USER_ID) }?;
        if user_id.is_empty() {
            return Err(Error::Invalid);
        }
        let user = User {
            id: user_id.to_vec(),
            // SAFETY: The user entity owns its NUL-terminated name.
            name: unsafe { read_string(user.pwszName) }?,
            // SAFETY: The user entity owns its NUL-terminated display name.
            display_name: unsafe { read_string(user.pwszDisplayName) }?,
        };
        // SAFETY: The decoded request owns its optional RP entity.
        let rp_name = match unsafe { read_optional(data, data.rp_info) }? {
            // SAFETY: The RP entity owns its NUL-terminated name.
            Some(rp) => Some(unsafe { read_string(rp.pwszName) }?),
            None => None,
        };
        let parameters = &data.algorithms;
        // SAFETY: The decoded request owns its credential parameters.
        let parameters = unsafe {
            read_slice(
                data,
                parameters.pCredentialParameters,
                parameters.cCredentialParameters,
                MAX_ALGORITHMS,
            )
        }?;
        let algorithms: Vec<_> = parameters
            .iter()
            .filter(|parameter| {
                // SAFETY: Each parameter owns its NUL-terminated credential type.
                unsafe { read_string(parameter.pwszCredentialType) }
                    .is_ok_and(|kind| kind == "public-key")
            })
            .map(|parameter| parameter.lAlg)
            .collect();
        if !algorithms.contains(&ES256) {
            return Err(Error::Invalid);
        }
        Ok(Request {
            user: Some(user),
            rp_name,
            algorithms,
            ..shared!(data).into_request(true, window)?
        })
    }

    fn decode_get(&self, bytes: &[u8], window: i64) -> Result<Request, Error> {
        let allocation = decoded(self.api.decode_get, self.api.free_get, bytes)?;
        // SAFETY: A successful decode returns a GetRequest.
        let data = unsafe { allocation.get::<GetRequest>() }?;
        check_version(data.version)?;
        shared!(data).into_request(false, window)
    }
}

/// Decodes the signed CTAP request with an OS decoder; the allocation frees its result.
fn decoded<T>(
    decode: unsafe extern "system" fn(u32, *const u8, *mut *mut T) -> HRESULT,
    free: Free,
    bytes: &[u8],
) -> Result<Allocation, Error> {
    let len = len_u32(bytes.len())?;
    let mut decoded = ptr::null_mut();
    // SAFETY: The request bytes stay live during this synchronous decode.
    let result = unsafe { decode(len, bytes.as_ptr(), &raw mut decoded) };
    let allocation = Allocation {
        pointer: decoded.cast(),
        free,
    };
    result.ok()?;
    Ok(allocation)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn later_versions_are_read_by_their_version_one_prefix() {
        let options = |version| Options {
            version,
            up: 0,
            uv: 1,
            rk: 0,
        };
        for version in [1, 2] {
            let value = options(version);
            assert_eq!(
                super::options(Some(&value)),
                Ok((UserVerification::Required, true))
            );
        }
        assert_eq!(super::options(Some(&options(0))), Err(Error::Invalid));
    }
}
