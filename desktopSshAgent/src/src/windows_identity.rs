//! Windows helpers used to secure the public SSH named pipe.
//!
//! This module deliberately does not derive caller authorization from
//! `GetNamedPipeClientProcessId`: that value can be spoofed through handle
//! transfer and PID reuse. Windows caller approvals remain connection-scoped.

use std::ffi::c_void;
use std::io;
use std::os::windows::io::{FromRawHandle, OwnedHandle};
use windows_sys::Win32::Foundation::HANDLE;
use windows_sys::Win32::Security::{
    GetLengthSid, GetTokenInformation, IsValidSid, TokenUser, TOKEN_QUERY, TOKEN_USER,
};
use windows_sys::Win32::System::Threading::{GetCurrentProcess, OpenProcessToken};

const MAX_TOKEN_INFORMATION_SIZE: usize = 1024 * 1024;

/// Returns the SID of the account running this agent in canonical SDDL form.
///
/// Pipe ACLs must name this user explicitly. The `OW` (Owner Rights) well-known
/// SID is not equivalent: for an elevated token the default object owner can be
/// the Administrators group, unintentionally broadening access to other admins.
pub(crate) fn current_process_user_sid_string() -> io::Result<String> {
    let mut token = std::ptr::null_mut();
    // SAFETY: GetCurrentProcess returns a valid pseudo-handle for this process,
    // `token` is writable HANDLE storage, and the resulting real token handle
    // is immediately wrapped in RAII.
    let succeeded = unsafe { OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) };
    if succeeded == 0 {
        return Err(last_error("OpenProcessToken(current process)"));
    }
    let token = owned_handle(token, "OpenProcessToken(current process)")?;
    let user_buffer = token_information(raw_handle(&token), TokenUser)?;
    let token_user: TOKEN_USER = user_buffer.read()?;
    let user_sid = sid_bytes(token_user.User.Sid)?;
    sid_string(&user_sid)
}

fn owned_handle(handle: HANDLE, function: &str) -> io::Result<OwnedHandle> {
    if handle.is_null() {
        return Err(last_error(function));
    }
    // SAFETY: successful Windows handle-returning APIs transfer ownership to
    // the caller; `OwnedHandle` closes this non-null handle exactly once.
    Ok(unsafe { OwnedHandle::from_raw_handle(handle.cast()) })
}

fn raw_handle(handle: &OwnedHandle) -> HANDLE {
    use std::os::windows::io::AsRawHandle;
    handle.as_raw_handle().cast()
}

struct TokenInformationBuffer {
    // Explicit 16-byte alignment covers all token structures on supported
    // Windows architectures while the API receives raw writable bytes.
    blocks: Vec<AlignedBlock>,
    byte_length: usize,
}

#[repr(C, align(16))]
#[derive(Clone, Copy)]
struct AlignedBlock([u8; 16]);

impl TokenInformationBuffer {
    fn read<T: Copy>(&self) -> io::Result<T> {
        if std::mem::align_of::<T>() > std::mem::align_of::<AlignedBlock>() {
            return Err(invalid_data(format!(
                "token information requires unsupported {}-byte alignment",
                std::mem::align_of::<T>()
            )));
        }
        if self.byte_length < std::mem::size_of::<T>() {
            return Err(invalid_data(format!(
                "token information is {} bytes; expected at least {}",
                self.byte_length,
                std::mem::size_of::<T>()
            )));
        }
        // SAFETY: `blocks` is aligned for the token structures used here, the
        // size was checked above, and `T: Copy` does not borrow the buffer.
        Ok(unsafe { self.blocks.as_ptr().cast::<T>().read() })
    }
}

fn token_information(token: HANDLE, information_class: i32) -> io::Result<TokenInformationBuffer> {
    let mut required = 0u32;
    // SAFETY: this sizing call deliberately supplies a null output buffer and
    // a valid required-length pointer.
    unsafe {
        GetTokenInformation(
            token,
            information_class,
            std::ptr::null_mut(),
            0,
            &mut required,
        );
    }
    let required = usize::try_from(required)
        .map_err(|_| invalid_data("token information size does not fit usize"))?;
    if required == 0 || required > MAX_TOKEN_INFORMATION_SIZE {
        return Err(invalid_data(format!(
            "token information returned invalid required size {required}"
        )));
    }

    let block_size = std::mem::size_of::<AlignedBlock>();
    let block_count = required
        .checked_add(block_size - 1)
        .ok_or_else(|| invalid_data("token information size overflow"))?
        / block_size;
    let mut blocks = vec![AlignedBlock([0; 16]); block_count];
    let mut returned = u32::try_from(required)
        .map_err(|_| invalid_data("token information size does not fit u32"))?;
    // SAFETY: `blocks` provides at least `required` writable bytes and remains
    // alive for the returned structure and embedded SID pointer.
    let succeeded = unsafe {
        GetTokenInformation(
            token,
            information_class,
            blocks.as_mut_ptr().cast::<c_void>(),
            returned,
            &mut returned,
        )
    };
    if succeeded == 0 {
        return Err(last_error("GetTokenInformation"));
    }
    let returned = usize::try_from(returned)
        .map_err(|_| invalid_data("token information output size does not fit usize"))?;
    if returned == 0 || returned > required {
        return Err(invalid_data(format!(
            "GetTokenInformation returned invalid output size {returned}"
        )));
    }
    Ok(TokenInformationBuffer {
        blocks,
        byte_length: returned,
    })
}

fn sid_bytes(sid: *mut c_void) -> io::Result<Box<[u8]>> {
    if sid.is_null() {
        return Err(invalid_data("token information returned a null SID"));
    }
    // SAFETY: the SID pointer originates from a live token information buffer.
    if unsafe { IsValidSid(sid) } == 0 {
        return Err(invalid_data("token information returned an invalid SID"));
    }
    // SAFETY: IsValidSid succeeded for this pointer.
    let length = unsafe { GetLengthSid(sid) } as usize;
    if !(8..=MAX_TOKEN_INFORMATION_SIZE).contains(&length) {
        return Err(invalid_data(format!("SID has invalid length {length}")));
    }
    // SAFETY: GetLengthSid returned the readable byte length of this valid SID;
    // copy it before the containing token information buffer is released.
    let bytes = unsafe { std::slice::from_raw_parts(sid.cast::<u8>(), length) };
    Ok(bytes.to_vec().into_boxed_slice())
}

fn sid_string(sid: &[u8]) -> io::Result<String> {
    if sid.len() < 8 {
        return Err(invalid_data("SID is shorter than its fixed header"));
    }
    let sub_authority_count = usize::from(sid[1]);
    let expected = 8usize
        .checked_add(
            sub_authority_count
                .checked_mul(4)
                .ok_or_else(|| invalid_data("SID sub-authority size overflow"))?,
        )
        .ok_or_else(|| invalid_data("SID size overflow"))?;
    if sid.len() != expected {
        return Err(invalid_data(format!(
            "SID length mismatch: expected {expected}, got {}",
            sid.len()
        )));
    }

    let identifier_authority = sid[2..8]
        .iter()
        .fold(0u64, |value, byte| (value << 8) | u64::from(*byte));
    let mut value = format!("S-{}-{identifier_authority}", sid[0]);
    for index in 0..sub_authority_count {
        let start = 8 + index * 4;
        let sub_authority = u32::from_le_bytes(
            sid[start..start + 4]
                .try_into()
                .map_err(|_| invalid_data("SID sub-authority is truncated"))?,
        );
        use std::fmt::Write as _;
        write!(value, "-{sub_authority}")
            .map_err(|error| invalid_data(format!("failed to format SID: {error}")))?;
    }
    Ok(value)
}

fn last_error(function: &str) -> io::Error {
    let source = io::Error::last_os_error();
    io::Error::new(source.kind(), format!("{function} failed: {source}"))
}

fn invalid_data(message: impl Into<String>) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message.into())
}

#[cfg(test)]
mod tests {
    use super::sid_string;

    #[test]
    fn formats_canonical_sid() {
        let sid = [1, 2, 0, 0, 0, 0, 0, 5, 32, 0, 0, 0, 0x20, 0x02, 0, 0];
        assert_eq!(sid_string(&sid).expect("SID"), "S-1-5-32-544");
    }
}
