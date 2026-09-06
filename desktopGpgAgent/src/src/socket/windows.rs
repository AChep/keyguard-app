//! Windows libassuan socket serving for the Keyguard GPG agent.

use crate::assuan;
use crate::ipc::client::IpcClient;
use crate::ipc::messages::{CallerAuthorization, CallerIdentity};
use anyhow::{Context, Result};
use keyguard_agent_identity::ConnectionFingerprint;
use std::ffi::{c_void, OsStr};
use std::fs;
use std::io::{ErrorKind, Write};
use std::os::windows::ffi::OsStrExt;
use std::os::windows::fs::{MetadataExt, OpenOptionsExt};
use std::os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt};
use tokio::net::TcpListener;
use tokio::sync::{oneshot, Semaphore};
use tokio::time::timeout;
use tracing::{debug, info, warn};
use windows_sys::Win32::Security::{GetTokenInformation, TokenUser, TOKEN_QUERY, TOKEN_USER};
use windows_sys::Win32::System::Threading::{GetCurrentProcess, OpenProcessToken};

const ASSUAN_NONCE_LEN: usize = 16;
const MAX_CONCURRENT_CONNECTIONS: usize = 32;
const NONCE_HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(2);
const WINDOWS_NAMED_PIPE_PREFIX: &str = r"\\.\pipe\";
const SDDL_REVISION_1: u32 = 1;
const SE_FILE_OBJECT: u32 = 1;
const OWNER_SECURITY_INFORMATION: u32 = 0x0000_0001;
const DACL_SECURITY_INFORMATION: u32 = 0x0000_0004;
const PROTECTED_DACL_SECURITY_INFORMATION: u32 = 0x8000_0000;
const READ_CONTROL: u32 = 0x0002_0000;
const WRITE_DAC: u32 = 0x0004_0000;
const WRITE_OWNER: u32 = 0x0008_0000;
const GENERIC_WRITE: u32 = 0x4000_0000;
const FILE_SHARE_READ: u32 = 0x0000_0001;
const FILE_SHARE_WRITE: u32 = 0x0000_0002;
const FILE_SHARE_DELETE: u32 = 0x0000_0004;
const FILE_ATTRIBUTE_REPARSE_POINT: u32 = 0x0000_0400;
const FILE_FLAG_BACKUP_SEMANTICS: u32 = 0x0200_0000;
const FILE_FLAG_OPEN_REPARSE_POINT: u32 = 0x0020_0000;
const ERROR_INSUFFICIENT_BUFFER: i32 = 122;
const MAX_TOKEN_INFORMATION_SIZE: usize = 1024 * 1024;
const MAX_SID_STRING_UTF16: usize = 256;

/// Serves the GPG agent over a native Windows libassuan socket.
///
/// Native GnuPG represents an Assuan socket on Windows as a marker file that
/// contains a loopback TCP port and a 16-byte nonce.
pub async fn serve<F>(
    ipc_client: IpcClient,
    socket_path: &Path,
    parent_stdin_closed: oneshot::Receiver<()>,
    on_ready: F,
) -> Result<()>
where
    F: FnOnce() -> Result<()>,
{
    require_libassuan_marker_path(socket_path)?;

    let listener = TcpListener::bind(("127.0.0.1", 0))
        .await
        .context("failed to bind Windows GPG agent loopback socket")?;
    let port = listener
        .local_addr()
        .context("failed to resolve Windows GPG agent loopback address")?
        .port();

    let mut nonce = [0u8; ASSUAN_NONCE_LEN];
    getrandom::getrandom(&mut nonce)
        .map_err(|e| anyhow::anyhow!("failed to generate Windows Assuan socket nonce: {e}"))?;
    // The marker guard stays alive for the rest of this function, so a broken
    // stdout pipe below removes the marker before startup fails.
    let _marker = AssuanSocketMarker::publish(socket_path, port, nonce)?;
    let socket_name = socket_path.to_string_lossy().into_owned();

    // The loopback listener is bound and the owner-only libassuan marker is
    // now visible.
    on_ready().context("failed to report GPG agent socket readiness")?;

    info!(
        path = %socket_path.display(),
        port,
        "GPG agent listening on Windows libassuan socket"
    );

    tokio::select! {
        result = accept_tcp_loop(listener, ipc_client, nonce, socket_name) => {
            result?;
        }
        _ = parent_stdin_closed => {
            info!("parent stdin closed, stopping GPG agent listener");
        }
    }

    Ok(())
}

fn require_libassuan_marker_path(path: &Path) -> Result<()> {
    if path
        .to_string_lossy()
        .replace('/', "\\")
        .to_ascii_lowercase()
        .starts_with(WINDOWS_NAMED_PIPE_PREFIX)
    {
        anyhow::bail!("Windows GPG socket must be a libassuan marker-file path");
    }
    Ok(())
}

async fn accept_tcp_loop(
    listener: TcpListener,
    ipc_client: IpcClient,
    nonce: [u8; ASSUAN_NONCE_LEN],
    socket_name: String,
) -> Result<()> {
    let connections = Arc::new(Semaphore::new(MAX_CONCURRENT_CONNECTIONS));

    loop {
        // Acquire before accepting so the number of accepted sockets and
        // spawned connection tasks stays bounded under connection floods.
        let permit = Arc::clone(&connections)
            .acquire_owned()
            .await
            .context("Windows GPG connection limiter closed")?;
        let (mut stream, peer) = listener
            .accept()
            .await
            .context("failed to accept Windows GPG agent connection")?;
        let ipc_client = ipc_client.clone();
        let socket_name = socket_name.clone();
        tokio::spawn(async move {
            // Keep the permit for the complete Assuan session. This bounds
            // authenticated clients that connect successfully but remain idle,
            // as well as clients that never complete the nonce handshake.
            let _permit = permit;

            match timeout(NONCE_HANDSHAKE_TIMEOUT, verify_nonce(&mut stream, &nonce)).await {
                Ok(Ok(true)) => {}
                Ok(Ok(false)) => {
                    debug!(%peer, "rejected Windows GPG agent connection with invalid nonce");
                    return;
                }
                Ok(Err(error)) => {
                    debug!(%peer, %error, "Windows GPG agent nonce read failed");
                    return;
                }
                Err(_) => {
                    debug!(%peer, "Windows GPG agent nonce handshake timed out");
                    return;
                }
            }

            let caller = connection_caller_identity();
            if let Err(e) = assuan::serve_connection(stream, ipc_client, caller, socket_name).await
            {
                warn!("GPG Assuan connection failed: {e}");
            }
        });
    }
}

fn connection_caller_identity() -> Option<CallerIdentity> {
    let connection = match ConnectionFingerprint::generate() {
        Ok(principal) => principal,
        Err(error) => {
            warn!(%error, "failed to generate Windows GPG connection authorization");
            return None;
        }
    };
    Some(CallerIdentity {
        pid: 0,
        uid: 0,
        gid: 0,
        process_name: String::new(),
        executable_path: String::new(),
        app_pid: 0,
        app_name: "Unverified caller".to_string(),
        app_bundle_path: String::new(),
        authorization: Some(CallerAuthorization {
            connection_fingerprint: connection.into_bytes().to_vec(),
            subjects: Vec::new(),
            authorization_context_fingerprint: Vec::new(),
        }),
    })
}

async fn verify_nonce<S>(stream: &mut S, expected: &[u8; ASSUAN_NONCE_LEN]) -> std::io::Result<bool>
where
    S: AsyncRead + Unpin,
{
    let mut actual = [0u8; ASSUAN_NONCE_LEN];
    stream.read_exact(&mut actual).await?;
    let difference = actual
        .iter()
        .zip(expected)
        .fold(0u8, |difference, (actual, expected)| {
            difference | (actual ^ expected)
        });
    Ok(difference == 0)
}

struct AssuanSocketMarker {
    path: PathBuf,
    contents: Vec<u8>,
}

impl AssuanSocketMarker {
    fn publish(path: &Path, port: u16, nonce: [u8; ASSUAN_NONCE_LEN]) -> Result<Self> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).with_context(|| {
                format!(
                    "failed to create Windows GPG socket directory: {}",
                    parent.display()
                )
            })?;
            harden_owner_only_directory(parent)?;
        }

        match fs::remove_file(path) {
            Ok(()) => warn!(path = %path.display(), "removed stale Windows GPG socket marker"),
            Err(e) if e.kind() == ErrorKind::NotFound => {}
            Err(e) => {
                return Err(e).with_context(|| {
                    format!(
                        "failed to remove stale Windows GPG socket marker: {}",
                        path.display()
                    )
                });
            }
        }

        let mut contents = format!("{port}\n").into_bytes();
        contents.extend_from_slice(&nonce);

        let temporary_path = temporary_marker_path(path);
        let publish_result = (|| -> Result<()> {
            match fs::remove_file(&temporary_path) {
                Ok(()) => {}
                Err(e) if e.kind() == ErrorKind::NotFound => {}
                Err(e) => {
                    return Err(e).with_context(|| {
                        format!(
                            "failed to remove stale temporary marker: {}",
                            temporary_path.display()
                        )
                    });
                }
            }

            let mut file = fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                // Keep the not-yet-hardened file exclusive, and never follow
                // a reparse point supplied at the temporary path.
                .share_mode(0)
                .access_mode(GENERIC_WRITE | READ_CONTROL | WRITE_DAC | WRITE_OWNER)
                .custom_flags(FILE_FLAG_OPEN_REPARSE_POINT)
                .open(&temporary_path)
                .with_context(|| {
                    format!(
                        "failed to create Windows GPG socket marker: {}",
                        temporary_path.display()
                    )
                })?;
            harden_owner_only_handle(&file).with_context(|| {
                format!(
                    "failed to restrict Windows GPG socket marker: {}",
                    temporary_path.display()
                )
            })?;
            file.write_all(&contents).with_context(|| {
                format!(
                    "failed to write Windows GPG socket marker: {}",
                    temporary_path.display()
                )
            })?;
            file.sync_all().with_context(|| {
                format!(
                    "failed to flush Windows GPG socket marker: {}",
                    temporary_path.display()
                )
            })?;
            drop(file);
            fs::rename(&temporary_path, path).with_context(|| {
                format!(
                    "failed to publish Windows GPG socket marker: {}",
                    path.display()
                )
            })?;
            Ok(())
        })();

        if publish_result.is_err() {
            let _ = fs::remove_file(&temporary_path);
        }
        publish_result?;

        Ok(Self {
            path: path.to_path_buf(),
            contents,
        })
    }
}

fn harden_owner_only_directory(path: &Path) -> Result<()> {
    let metadata = fs::symlink_metadata(path).with_context(|| {
        format!(
            "failed to inspect Windows GPG socket directory: {}",
            path.display()
        )
    })?;
    if metadata.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0 {
        anyhow::bail!(
            "Windows GPG socket directory must not be a reparse point: {}",
            path.display()
        );
    }

    let directory = fs::OpenOptions::new()
        .read(true)
        .access_mode(READ_CONTROL | WRITE_DAC | WRITE_OWNER)
        .share_mode(FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE)
        .custom_flags(FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT)
        .open(path)
        .with_context(|| {
            format!(
                "failed to open Windows GPG socket directory securely: {}",
                path.display()
            )
        })?;
    harden_owner_only_handle(&directory).with_context(|| {
        format!(
            "failed to restrict Windows GPG socket directory: {}",
            path.display()
        )
    })
}

fn harden_owner_only_handle(file: &fs::File) -> Result<()> {
    let descriptor = LocalSecurityDescriptor::owner_only()?;
    let mut owner_defaulted = 0;
    let mut owner = std::ptr::null_mut();
    // SAFETY: `descriptor` is a valid self-relative security descriptor and
    // each output argument points to writable storage owned by this frame.
    let owner_read =
        unsafe { GetSecurityDescriptorOwner(descriptor.0, &mut owner, &mut owner_defaulted) };
    if owner_read == 0 {
        return Err(std::io::Error::last_os_error()).context("GetSecurityDescriptorOwner failed");
    }
    if owner.is_null() {
        anyhow::bail!("owner-only security descriptor did not contain an owner SID");
    }

    let mut dacl_present = 0;
    let mut dacl = std::ptr::null_mut();
    let mut dacl_defaulted = 0;
    // SAFETY: `descriptor` is a valid self-relative security descriptor and
    // each output argument points to writable storage owned by this frame.
    let dacl_read = unsafe {
        GetSecurityDescriptorDacl(
            descriptor.0,
            &mut dacl_present,
            &mut dacl,
            &mut dacl_defaulted,
        )
    };
    if dacl_read == 0 {
        return Err(std::io::Error::last_os_error()).context("GetSecurityDescriptorDacl failed");
    }
    if dacl_present == 0 || dacl.is_null() {
        anyhow::bail!("owner-only security descriptor did not contain a DACL");
    }

    // SAFETY: `file` owns a valid filesystem handle with WRITE_OWNER and
    // WRITE_DAC access. The owner SID and DACL remain owned by `descriptor`
    // until this call returns.
    let status = unsafe {
        SetSecurityInfo(
            file.as_raw_handle().cast(),
            SE_FILE_OBJECT,
            OWNER_SECURITY_INFORMATION
                | DACL_SECURITY_INFORMATION
                | PROTECTED_DACL_SECURITY_INFORMATION,
            owner,
            std::ptr::null_mut(),
            dacl,
            std::ptr::null_mut(),
        )
    };
    if status != 0 {
        return Err(std::io::Error::from_raw_os_error(status as i32))
            .context("SetSecurityInfo failed");
    }
    Ok(())
}

struct LocalSecurityDescriptor(*mut c_void);

impl LocalSecurityDescriptor {
    fn owner_only() -> Result<Self> {
        let owner_only_sddl = owner_only_sddl()?;
        let sddl: Vec<u16> = OsStr::new(&owner_only_sddl)
            .encode_wide()
            .chain(std::iter::once(0))
            .collect();
        let mut descriptor = std::ptr::null_mut();
        // SAFETY: `sddl` is NUL-terminated and remains alive for the call;
        // `descriptor` points to writable pointer storage owned by this frame.
        let succeeded = unsafe {
            ConvertStringSecurityDescriptorToSecurityDescriptorW(
                sddl.as_ptr(),
                SDDL_REVISION_1,
                &mut descriptor,
                std::ptr::null_mut(),
            )
        };
        if succeeded == 0 {
            return Err(std::io::Error::last_os_error())
                .context("ConvertStringSecurityDescriptorToSecurityDescriptorW failed");
        }
        if descriptor.is_null() {
            anyhow::bail!("Windows returned a null owner-only security descriptor");
        }
        Ok(Self(descriptor))
    }
}

fn owner_only_sddl() -> Result<String> {
    let user_sid = current_process_user_sid_string()
        .context("failed to resolve the current Windows token-user SID")?;
    // Owner Rights (`OW`) is not a current-user alias. In particular, an
    // elevated token may default new objects to the Administrators group.
    // Name TokenUser explicitly as both object owner and sole DACL trustee.
    Ok(format!("O:{user_sid}D:P(A;;GA;;;{user_sid})"))
}

fn current_process_user_sid_string() -> std::io::Result<String> {
    let mut token = std::ptr::null_mut();
    // SAFETY: GetCurrentProcess returns a valid pseudo-handle, `token` is
    // writable HANDLE storage, and the returned handle is wrapped in RAII.
    let succeeded = unsafe { OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) };
    if succeeded == 0 {
        return Err(last_error("OpenProcessToken(current process)"));
    }
    if token.is_null() {
        return Err(std::io::Error::other(
            "OpenProcessToken returned a null token handle",
        ));
    }
    // SAFETY: OpenProcessToken returned a non-null owned kernel handle.
    let token = unsafe { OwnedHandle::from_raw_handle(token.cast()) };
    let information = token_user_information(token.as_raw_handle().cast())?;
    let token_user: TOKEN_USER = information.read()?;
    sid_pointer_to_string(token_user.User.Sid)
}

#[repr(C, align(16))]
#[derive(Clone, Copy)]
struct AlignedBlock([u8; 16]);

struct TokenInformationBuffer {
    blocks: Vec<AlignedBlock>,
    byte_length: usize,
}

impl TokenInformationBuffer {
    fn read<T: Copy>(&self) -> std::io::Result<T> {
        if std::mem::align_of::<T>() > std::mem::align_of::<AlignedBlock>() {
            return Err(std::io::Error::new(
                ErrorKind::InvalidData,
                format!(
                    "token information requires unsupported {}-byte alignment",
                    std::mem::align_of::<T>()
                ),
            ));
        }
        if self.byte_length < std::mem::size_of::<T>() {
            return Err(std::io::Error::new(
                ErrorKind::InvalidData,
                format!(
                    "token information is {} bytes; expected at least {}",
                    self.byte_length,
                    std::mem::size_of::<T>()
                ),
            ));
        }
        // SAFETY: the backing allocation is 16-byte aligned, the minimum size
        // was checked above, and T: Copy cannot outlive or borrow the buffer.
        Ok(unsafe { self.blocks.as_ptr().cast::<T>().read() })
    }
}

fn token_user_information(token: *mut c_void) -> std::io::Result<TokenInformationBuffer> {
    let mut required = 0u32;
    // SAFETY: this sizing call deliberately passes no output buffer and a valid
    // required-size pointer.
    let succeeded =
        unsafe { GetTokenInformation(token, TokenUser, std::ptr::null_mut(), 0, &mut required) };
    if succeeded == 0 {
        let error = std::io::Error::last_os_error();
        if error.raw_os_error() != Some(ERROR_INSUFFICIENT_BUFFER) {
            return Err(std::io::Error::new(
                error.kind(),
                format!("GetTokenInformation(size) failed: {error}"),
            ));
        }
    }
    let required = usize::try_from(required).map_err(|_| {
        std::io::Error::new(
            ErrorKind::InvalidData,
            "token information size does not fit usize",
        )
    })?;
    if required == 0 || required > MAX_TOKEN_INFORMATION_SIZE {
        return Err(std::io::Error::new(
            ErrorKind::InvalidData,
            format!("token information returned invalid required size {required}"),
        ));
    }

    let block_size = std::mem::size_of::<AlignedBlock>();
    let block_count = required
        .checked_add(block_size - 1)
        .ok_or_else(|| std::io::Error::new(ErrorKind::InvalidData, "token size overflow"))?
        / block_size;
    let mut blocks = vec![AlignedBlock([0; 16]); block_count];
    let mut returned = u32::try_from(required)
        .map_err(|_| std::io::Error::new(ErrorKind::InvalidData, "token size does not fit u32"))?;
    // SAFETY: `blocks` provides at least `required` aligned writable bytes.
    let succeeded = unsafe {
        GetTokenInformation(
            token,
            TokenUser,
            blocks.as_mut_ptr().cast::<c_void>(),
            returned,
            &mut returned,
        )
    };
    if succeeded == 0 {
        return Err(last_error("GetTokenInformation(TokenUser)"));
    }
    let returned = usize::try_from(returned).map_err(|_| {
        std::io::Error::new(
            ErrorKind::InvalidData,
            "token information output size does not fit usize",
        )
    })?;
    if returned == 0 || returned > required {
        return Err(std::io::Error::new(
            ErrorKind::InvalidData,
            format!("GetTokenInformation returned invalid output size {returned}"),
        ));
    }
    Ok(TokenInformationBuffer {
        blocks,
        byte_length: returned,
    })
}

fn sid_pointer_to_string(sid: *mut c_void) -> std::io::Result<String> {
    if sid.is_null() {
        return Err(std::io::Error::new(
            ErrorKind::InvalidData,
            "TokenUser returned a null SID",
        ));
    }
    let mut string_sid = std::ptr::null_mut();
    // SAFETY: `sid` points into a live TokenUser buffer and `string_sid` is
    // writable pointer storage. Windows allocates the output with LocalAlloc.
    let succeeded = unsafe { ConvertSidToStringSidW(sid, &mut string_sid) };
    if succeeded == 0 {
        return Err(last_error("ConvertSidToStringSidW"));
    }
    if string_sid.is_null() {
        return Err(std::io::Error::other(
            "ConvertSidToStringSidW returned a null string",
        ));
    }
    let string_sid = LocalSidString(string_sid);
    let length = (0..MAX_SID_STRING_UTF16)
        // SAFETY: ConvertSidToStringSidW returned a NUL-terminated LocalAlloc
        // string. The documented maximum SID string is below this hard bound.
        .find(|index| unsafe { *string_sid.0.add(*index) == 0 })
        .ok_or_else(|| {
            std::io::Error::new(ErrorKind::InvalidData, "Windows SID string is too long")
        })?;
    // SAFETY: the bounded scan above found the terminator, proving this prefix
    // is readable and contains exactly `length` UTF-16 code units.
    let value = unsafe { std::slice::from_raw_parts(string_sid.0, length) };
    String::from_utf16(value).map_err(|error| {
        std::io::Error::new(
            ErrorKind::InvalidData,
            format!("Windows SID string is invalid UTF-16: {error}"),
        )
    })
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

fn last_error(function: &str) -> std::io::Error {
    let source = std::io::Error::last_os_error();
    std::io::Error::new(source.kind(), format!("{function} failed: {source}"))
}

impl Drop for LocalSecurityDescriptor {
    fn drop(&mut self) {
        // SAFETY: the descriptor was allocated by LocalAlloc inside the SDDL
        // conversion API and is freed exactly once by this owner.
        unsafe {
            LocalFree(self.0);
        }
    }
}

#[link(name = "advapi32")]
unsafe extern "system" {
    fn ConvertStringSecurityDescriptorToSecurityDescriptorW(
        string_security_descriptor: *const u16,
        string_sd_revision: u32,
        security_descriptor: *mut *mut c_void,
        security_descriptor_size: *mut u32,
    ) -> i32;

    fn GetSecurityDescriptorDacl(
        security_descriptor: *mut c_void,
        dacl_present: *mut i32,
        dacl: *mut *mut c_void,
        dacl_defaulted: *mut i32,
    ) -> i32;

    fn GetSecurityDescriptorOwner(
        security_descriptor: *mut c_void,
        owner: *mut *mut c_void,
        owner_defaulted: *mut i32,
    ) -> i32;

    fn ConvertSidToStringSidW(sid: *mut c_void, string_sid: *mut *mut u16) -> i32;

    fn SetSecurityInfo(
        handle: *mut c_void,
        object_type: u32,
        security_information: u32,
        owner: *mut c_void,
        group: *mut c_void,
        dacl: *mut c_void,
        sacl: *mut c_void,
    ) -> u32;
}

#[link(name = "kernel32")]
unsafe extern "system" {
    fn LocalFree(memory: *mut c_void) -> *mut c_void;
}

impl Drop for AssuanSocketMarker {
    fn drop(&mut self) {
        match fs::read(&self.path) {
            Ok(contents) if contents == self.contents => match fs::remove_file(&self.path) {
                Ok(()) => info!(path = %self.path.display(), "removed Windows GPG socket marker"),
                Err(e) if e.kind() == ErrorKind::NotFound => {}
                Err(e) => warn!(
                    path = %self.path.display(),
                    error = %e,
                    "failed to remove Windows GPG socket marker"
                ),
            },
            Ok(_) => warn!(
                path = %self.path.display(),
                "left Windows GPG socket marker because it was replaced"
            ),
            Err(e) if e.kind() == ErrorKind::NotFound => {}
            Err(e) => warn!(
                path = %self.path.display(),
                error = %e,
                "failed to inspect Windows GPG socket marker during cleanup"
            ),
        }
    }
}

fn temporary_marker_path(path: &Path) -> PathBuf {
    let mut file_name = path
        .file_name()
        .unwrap_or_else(|| OsStr::new("S.gpg-agent"))
        .to_os_string();
    file_name.push(format!(".keyguard-{}.tmp", std::process::id()));
    path.with_file_name(file_name)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::AsyncWriteExt;

    #[test]
    fn owner_only_sddl_names_the_token_user_instead_of_owner_rights() -> Result<()> {
        let user_sid = current_process_user_sid_string()?;
        let sddl = owner_only_sddl()?;

        assert!(sddl.starts_with("O:S-"));
        assert!(!sddl.contains(";;;OW"));
        assert_eq!(sddl.matches(&user_sid).count(), 2);
        Ok(())
    }

    #[test]
    fn owner_only_descriptor_contains_the_exact_token_user_owner() -> Result<()> {
        let expected = current_process_user_sid_string()?;
        let descriptor = LocalSecurityDescriptor::owner_only()?;
        let mut owner = std::ptr::null_mut();
        let mut owner_defaulted = 0;
        // SAFETY: descriptor is live and both output pointers are writable.
        let succeeded =
            unsafe { GetSecurityDescriptorOwner(descriptor.0, &mut owner, &mut owner_defaulted) };

        if succeeded == 0 {
            return Err(std::io::Error::last_os_error())
                .context("GetSecurityDescriptorOwner test query failed");
        }
        assert!(!owner.is_null());
        assert_eq!(sid_pointer_to_string(owner)?, expected);
        Ok(())
    }

    #[test]
    fn marker_uses_libassuan_port_and_nonce_format() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("S.gpg-agent");
        let nonce = [0x5au8; ASSUAN_NONCE_LEN];
        let marker = AssuanSocketMarker::publish(&path, 43123, nonce).expect("publish marker");

        let mut expected = b"43123\n".to_vec();
        expected.extend_from_slice(&nonce);
        assert_eq!(fs::read(&path).expect("read marker"), expected);

        drop(marker);
        assert!(!path.exists());
    }

    #[test]
    fn named_pipe_is_rejected_as_a_gpg_socket() {
        let err = require_libassuan_marker_path(Path::new(r"\\.\pipe\keyguard-gpg-agent"))
            .expect_err("named pipe must be rejected")
            .to_string();
        assert!(err.contains("libassuan marker-file path"));
    }

    #[test]
    fn windows_connection_gets_non_reusable_authorization() {
        let caller = connection_caller_identity().expect("connection caller identity");
        assert_eq!(caller.app_name, "Unverified caller");
        let authorization = caller.authorization.expect("connection authorization");

        assert_eq!(
            authorization.connection_fingerprint.len(),
            keyguard_agent_identity::PRINCIPAL_FINGERPRINT_LEN
        );
        assert!(authorization.subjects.is_empty());
        assert!(authorization.authorization_context_fingerprint.is_empty());
    }

    #[tokio::test]
    async fn nonce_verification_accepts_only_the_published_nonce() {
        let nonce = [0x3cu8; ASSUAN_NONCE_LEN];
        let (mut client, mut server) = tokio::io::duplex(ASSUAN_NONCE_LEN);
        client.write_all(&nonce).await.expect("write nonce");
        assert!(verify_nonce(&mut server, &nonce)
            .await
            .expect("verify nonce"));

        let (mut client, mut server) = tokio::io::duplex(ASSUAN_NONCE_LEN);
        client
            .write_all(&[0u8; ASSUAN_NONCE_LEN])
            .await
            .expect("write invalid nonce");
        assert!(!verify_nonce(&mut server, &nonce)
            .await
            .expect("verify nonce"));
    }

    #[tokio::test(start_paused = true)]
    async fn silent_nonce_peer_times_out() {
        let nonce = [0x3cu8; ASSUAN_NONCE_LEN];
        let (_client, mut server) = tokio::io::duplex(ASSUAN_NONCE_LEN);

        let result = timeout(NONCE_HANDSHAKE_TIMEOUT, verify_nonce(&mut server, &nonce)).await;

        assert!(result.is_err(), "silent peer must time out");
    }

    #[tokio::test(start_paused = true)]
    async fn partial_nonce_peer_times_out() {
        let nonce = [0x3cu8; ASSUAN_NONCE_LEN];
        let (mut client, mut server) = tokio::io::duplex(ASSUAN_NONCE_LEN);
        client
            .write_all(&nonce[..ASSUAN_NONCE_LEN - 1])
            .await
            .expect("write partial nonce");

        let result = timeout(NONCE_HANDSHAKE_TIMEOUT, verify_nonce(&mut server, &nonce)).await;

        assert!(result.is_err(), "partial nonce must time out");
    }
}
