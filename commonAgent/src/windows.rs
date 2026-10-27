//! Windows token, SID, and owner-only pipe helpers shared by the agent helpers.

pub mod process;

use std::ffi::{c_void, OsStr};
use std::io;
use std::os::windows::ffi::OsStrExt;
use std::os::windows::io::{AsHandle, AsRawHandle, BorrowedHandle, FromRawHandle, OwnedHandle};
use tokio::net::windows::named_pipe::{NamedPipeServer, ServerOptions};
use windows_sys::Win32::Foundation::{
    LocalFree, ERROR_INSUFFICIENT_BUFFER, HANDLE, INVALID_HANDLE_VALUE,
};
use windows_sys::Win32::Security::Authorization::{
    ConvertSidToStringSidW, ConvertStringSecurityDescriptorToSecurityDescriptorW, SDDL_REVISION_1,
};
use windows_sys::Win32::Security::{
    GetLengthSid, GetTokenInformation, IsValidSid, TokenUser, PSECURITY_DESCRIPTOR, PSID,
    SECURITY_ATTRIBUTES, TOKEN_INFORMATION_CLASS, TOKEN_QUERY, TOKEN_USER,
};
use windows_sys::Win32::System::Threading::{
    GetCurrentProcess, OpenProcess, OpenProcessToken, PROCESS_ACCESS_RIGHTS,
};

const MAX_TOKEN_INFORMATION_SIZE: usize = 1024 * 1024;
// The documented maximum SID string is below this hard bound.
const MAX_SID_STRING_UTF16: usize = 256;

/// Returns the SID of the account running this process in canonical SDDL form.
///
/// Pipe and file ACLs must name this user explicitly. The `OW` (Owner Rights)
/// well-known SID is not equivalent: for an elevated token the default object
/// owner can be the Administrators group, unintentionally broadening access.
///
/// # Errors
///
/// Fails when the process token or its user SID cannot be read.
pub fn current_user_sid_string() -> io::Result<String> {
    let mut token = std::ptr::null_mut();
    // SAFETY: GetCurrentProcess returns a valid pseudo-handle for this process,
    // `token` is writable HANDLE storage, and the resulting real token handle
    // is immediately wrapped in RAII.
    let succeeded = unsafe { OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) };
    if succeeded == 0 {
        return Err(last_error("OpenProcessToken(current process)"));
    }
    // SAFETY: OpenProcessToken succeeded and returned a new owned token
    // handle, which is transferred to OwnedHandle and closed with CloseHandle.
    let token = unsafe { owned_handle(token, "OpenProcessToken(current process)") }?;
    token_user_sid_string(token.as_handle())
}

/// Returns the user SID of an access token in canonical SDDL form.
///
/// # Errors
///
/// Fails when `token` lacks `TOKEN_QUERY` access or holds an invalid SID.
pub fn token_user_sid_string(token: BorrowedHandle<'_>) -> io::Result<String> {
    let user_buffer = token_information(token, TokenUser)?;
    // SAFETY: TokenUser fills the buffer with a TOKEN_USER.
    let token_user: TOKEN_USER = unsafe { user_buffer.read() }?;
    // SAFETY: the SID points into `user_buffer`, which outlives this call.
    unsafe { sid_string(token_user.User.Sid) }
}

/// Fails unless `process` runs as the account that runs this process.
///
/// # Errors
///
/// Fails with `peer_unavailable` when the process token cannot be read, and
/// with `peer_owner_mismatch` when it belongs to another user.
pub fn ensure_current_user_process(process: BorrowedHandle<'_>) -> anyhow::Result<()> {
    let mut token = std::ptr::null_mut();
    // SAFETY: `process` is a live process handle for the duration of this
    // call, and `token` is writable HANDLE storage.
    let opened =
        unsafe { OpenProcessToken(process.as_raw_handle().cast(), TOKEN_QUERY, &mut token) };
    anyhow::ensure!(opened != 0, "peer_unavailable");
    // SAFETY: OpenProcessToken succeeded and transferred this owned handle.
    let token = unsafe { OwnedHandle::from_raw_handle(token.cast()) };
    anyhow::ensure!(
        token_user_sid_string(token.as_handle())? == current_user_sid_string()?,
        "peer_owner_mismatch"
    );
    Ok(())
}

/// Opens the process `pid` with `access`, and fails unless it runs as the
/// account that runs this process.
///
/// # Errors
///
/// Fails with `peer_unavailable` when the process cannot be opened, and as
/// [`ensure_current_user_process`] does otherwise.
pub fn open_current_user_process(
    pid: u32,
    access: PROCESS_ACCESS_RIGHTS,
) -> anyhow::Result<OwnedHandle> {
    // SAFETY: OpenProcess takes a PID value and returns either null or a new
    // owned process handle.
    let process = unsafe { OpenProcess(access, 0, pid) };
    anyhow::ensure!(!process.is_null(), "peer_unavailable");
    // SAFETY: OpenProcess returned this non-null handle, owned from here on.
    let process = unsafe { OwnedHandle::from_raw_handle(process.cast()) };
    ensure_current_user_process(process.as_handle())?;
    Ok(process)
}

/// The SDDL that grants the current user, and only that user, full access to
/// an object it owns.
///
/// # Errors
///
/// Fails when the current user SID cannot be read.
pub fn owner_only_sddl() -> io::Result<String> {
    let user_sid = current_user_sid_string()?;
    // Name the token user explicitly as both owner and sole DACL trustee.
    Ok(format!("O:{user_sid}D:P(A;;GA;;;{user_sid})"))
}

/// Self-relative security descriptor of [`owner_only_sddl`], freed on drop.
pub struct LocalSecurityDescriptor(PSECURITY_DESCRIPTOR);

impl LocalSecurityDescriptor {
    /// Converts [`owner_only_sddl`] into a security descriptor.
    ///
    /// # Errors
    ///
    /// Fails when the current user SID cannot be read or the SDDL conversion
    /// fails.
    pub fn owner_only() -> io::Result<Self> {
        let sddl: Vec<u16> = OsStr::new(&owner_only_sddl()?)
            .encode_wide()
            .chain(std::iter::once(0))
            .collect();
        let mut descriptor = std::ptr::null_mut();
        // SAFETY: `sddl` is NUL-terminated and remains alive for the call;
        // `descriptor` points to writable pointer storage in this frame.
        let succeeded = unsafe {
            ConvertStringSecurityDescriptorToSecurityDescriptorW(
                sddl.as_ptr(),
                SDDL_REVISION_1,
                &mut descriptor,
                std::ptr::null_mut(),
            )
        };
        if succeeded == 0 {
            return Err(last_error(
                "ConvertStringSecurityDescriptorToSecurityDescriptorW",
            ));
        }
        if descriptor.is_null() {
            return Err(io::Error::other(
                "Windows returned a null owner-only security descriptor",
            ));
        }
        Ok(Self(descriptor))
    }

    /// Returns the descriptor, valid while `self` is alive.
    #[must_use]
    pub fn as_ptr(&self) -> PSECURITY_DESCRIPTOR {
        self.0
    }
}

impl Drop for LocalSecurityDescriptor {
    fn drop(&mut self) {
        // SAFETY: the SDDL conversion API allocated this descriptor with
        // LocalAlloc, and this owner frees the non-null pointer exactly once.
        unsafe {
            LocalFree(self.0);
        }
    }
}

/// Creates a named-pipe instance that only the current user can open and
/// that rejects remote clients.
///
/// # Errors
///
/// Fails when the security descriptor cannot be built, when `first_instance`
/// is set and the pipe already exists, or when the pipe cannot be created.
pub fn create_owner_only_pipe(
    pipe_name: impl AsRef<OsStr>,
    first_instance: bool,
) -> io::Result<NamedPipeServer> {
    let descriptor = LocalSecurityDescriptor::owner_only()?;
    let mut attributes = SECURITY_ATTRIBUTES {
        nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
        lpSecurityDescriptor: descriptor.as_ptr(),
        bInheritHandle: 0,
    };
    // SAFETY: `attributes` is a SECURITY_ATTRIBUTES whose security descriptor
    // remains allocated until CreateNamedPipeW returns. Windows copies the
    // descriptor into the newly created pipe object.
    unsafe {
        ServerOptions::new()
            .first_pipe_instance(first_instance)
            .reject_remote_clients(true)
            .create_with_security_attributes_raw(
                pipe_name,
                std::ptr::from_mut(&mut attributes).cast(),
            )
    }
}

/// Formats a SID in canonical SDDL form.
///
/// # Safety
///
/// A non-null `sid` must point to memory that stays readable for the call,
/// such as a SID inside a live token information buffer or descriptor.
///
/// # Errors
///
/// Fails when `sid` is null or not a valid SID.
pub unsafe fn sid_string(sid: PSID) -> io::Result<String> {
    if sid.is_null() {
        return Err(invalid_data("cannot format a null SID"));
    }
    let mut string_sid = std::ptr::null_mut();
    // SAFETY: `sid` points to a live SID owned by the caller and `string_sid`
    // is writable pointer storage. Windows allocates the output with LocalAlloc.
    let succeeded = unsafe { ConvertSidToStringSidW(sid, &mut string_sid) };
    if succeeded == 0 {
        return Err(last_error("ConvertSidToStringSidW"));
    }
    if string_sid.is_null() {
        return Err(io::Error::other(
            "ConvertSidToStringSidW returned a null string",
        ));
    }
    let string_sid = LocalSidString(string_sid);
    let length = (0..MAX_SID_STRING_UTF16)
        // SAFETY: ConvertSidToStringSidW returned a NUL-terminated LocalAlloc
        // string; the scan stops at its terminator or the hard bound.
        .find(|index| unsafe { *string_sid.0.add(*index) == 0 })
        .ok_or_else(|| invalid_data("Windows SID string is too long"))?;
    // SAFETY: the bounded scan above found the terminator, proving this prefix
    // is readable and contains exactly `length` UTF-16 code units.
    let value = unsafe { std::slice::from_raw_parts(string_sid.0, length) };
    String::from_utf16(value)
        .map_err(|error| invalid_data(format!("Windows SID string is invalid UTF-16: {error}")))
}

struct LocalSidString(*mut u16);

impl Drop for LocalSidString {
    fn drop(&mut self) {
        // SAFETY: ConvertSidToStringSidW allocated this pointer with LocalAlloc.
        unsafe {
            LocalFree(self.0.cast());
        }
    }
}

/// Copies a validated SID out of a token information buffer.
///
/// # Safety
///
/// A non-null `sid` must point to memory that stays readable for the call,
/// such as a SID inside a live token information buffer.
///
/// # Errors
///
/// Fails when `sid` is null, invalid, or has an implausible length.
pub unsafe fn sid_bytes(sid: PSID) -> io::Result<Box<[u8]>> {
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

/// Owned, suitably aligned output of `GetTokenInformation`.
pub struct TokenInformationBuffer {
    // Explicit 16-byte alignment covers all token structures on supported
    // Windows architectures while the API receives raw writable bytes.
    blocks: Vec<AlignedBlock>,
    byte_length: usize,
}

#[repr(C, align(16))]
#[derive(Clone, Copy)]
struct AlignedBlock([u8; 16]);

impl TokenInformationBuffer {
    /// Reads the fixed-size structure at the start of the buffer. Pointers in
    /// the result point into this buffer and must not outlive it.
    ///
    /// # Safety
    ///
    /// `T` must be the plain-data structure that the queried information
    /// class writes, such as `TOKEN_USER` for `TokenUser`.
    ///
    /// # Errors
    ///
    /// Fails when `T` needs more alignment or bytes than the buffer holds.
    pub unsafe fn read<T: Copy>(&self) -> io::Result<T> {
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
        // size was checked above, the caller guarantees `T` matches the
        // written structure, and `T: Copy` does not borrow the buffer.
        Ok(unsafe { self.blocks.as_ptr().cast::<T>().read() })
    }
}

/// Queries one token information class into an owned buffer.
///
/// # Errors
///
/// Fails when the query fails or reports an implausible size.
pub fn token_information(
    token: BorrowedHandle<'_>,
    information_class: TOKEN_INFORMATION_CLASS,
) -> io::Result<TokenInformationBuffer> {
    let token: HANDLE = token.as_raw_handle().cast();
    let mut required = 0u32;
    // SAFETY: this sizing call deliberately supplies a null output buffer and
    // a valid required-length pointer. The reported size is validated below.
    let sized = unsafe {
        GetTokenInformation(
            token,
            information_class,
            std::ptr::null_mut(),
            0,
            &mut required,
        )
    };
    // Only the expected insufficient-buffer failure reports a size.
    if sized == 0 {
        let error = io::Error::last_os_error();
        if error.raw_os_error() != Some(ERROR_INSUFFICIENT_BUFFER as i32) {
            return Err(io::Error::new(
                error.kind(),
                format!("GetTokenInformation(size) failed: {error}"),
            ));
        }
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

/// Takes ownership of a handle returned by a Windows API, or reports the
/// last error of `function` when the handle is null or invalid.
///
/// # Safety
///
/// Unless `handle` is null or `INVALID_HANDLE_VALUE`, it must be a valid,
/// open handle that the caller exclusively owns and that can be closed with
/// `CloseHandle`. Ownership transfers to the returned `OwnedHandle`; the
/// caller must not close the handle or construct another owner from it.
///
/// # Errors
///
/// Fails when `handle` is null or `INVALID_HANDLE_VALUE`.
pub unsafe fn owned_handle(handle: HANDLE, function: &str) -> io::Result<OwnedHandle> {
    if handle.is_null() || handle == INVALID_HANDLE_VALUE {
        return Err(last_error(function));
    }
    // SAFETY: the caller guarantees exclusive ownership of a valid handle
    // compatible with CloseHandle, and the error sentinels were rejected above.
    Ok(unsafe { OwnedHandle::from_raw_handle(handle.cast()) })
}

/// Wraps the thread's last OS error with the name of the failed `function`.
#[must_use]
pub fn last_error(function: &str) -> io::Error {
    let source = io::Error::last_os_error();
    io::Error::new(source.kind(), format!("{function} failed: {source}"))
}

/// Escapes control characters of a display value, such as a process path.
/// Display data is never authorization evidence.
#[must_use]
pub fn escape_display_text(value: &str) -> String {
    let mut display = String::with_capacity(value.len());
    for c in value.chars() {
        if c.is_control() {
            display.extend(c.escape_default());
        } else {
            display.push(c);
        }
    }
    display
}

/// Builds an `InvalidData` error with `message`.
#[must_use]
pub fn invalid_data(message: impl Into<String>) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message.into())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn formats_canonical_sid() {
        // S-1-5-32-544 (BUILTIN\Administrators), as 4-byte aligned words.
        let mut sid = [0x0000_0201u32, 0x0500_0000, 0x0000_0020, 0x0000_0220];
        let sid = sid.as_mut_ptr().cast::<c_void>();

        // SAFETY: `sid` points to the live local SID above.
        assert_eq!(unsafe { sid_string(sid) }.expect("SID"), "S-1-5-32-544");
        // SAFETY: `sid` points to the live local SID above.
        assert_eq!(unsafe { sid_bytes(sid) }.expect("SID").len(), 16);
    }

    #[test]
    fn owner_only_sddl_names_the_token_user_instead_of_owner_rights() -> io::Result<()> {
        let user_sid = current_user_sid_string()?;
        let sddl = owner_only_sddl()?;

        assert!(sddl.starts_with("O:S-"));
        assert!(!sddl.contains(";;;OW"));
        assert_eq!(sddl.matches(&user_sid).count(), 2);
        Ok(())
    }

    #[test]
    fn owner_only_security_descriptor_is_valid_sddl() -> io::Result<()> {
        let descriptor = LocalSecurityDescriptor::owner_only()?;

        assert!(!descriptor.as_ptr().is_null());
        Ok(())
    }
}
