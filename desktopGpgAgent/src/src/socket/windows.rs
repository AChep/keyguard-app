//! Windows libassuan socket serving for the Keyguard GPG agent.

use crate::ipc::client::IpcClient;
use anyhow::{Context, Result};
use common_gpg_agent_rust::messages::{CallerAuthorization, CallerIdentity};
use keyguard_agent_identity::windows::LocalSecurityDescriptor;
use keyguard_agent_identity::ConnectionFingerprint;
use keyguard_gpg_agent_assuan as assuan;
use std::ffi::{c_void, OsStr};
use std::fs;
use std::io::{ErrorKind, Write};
use std::os::windows::fs::{MetadataExt, OpenOptionsExt};
use std::os::windows::io::AsRawHandle;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt};
use tokio::net::TcpListener;
use tokio::sync::{oneshot, Semaphore};
use tokio::time::timeout;
use tracing::{debug, info, warn};

const ASSUAN_NONCE_LEN: usize = 16;
const MAX_CONCURRENT_CONNECTIONS: usize = 32;
const NONCE_HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(2);
const WINDOWS_NAMED_PIPE_PREFIX: &str = r"\\.\pipe\";
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

/// Serves the GPG agent over a native Windows libassuan socket.
///
/// Native GnuPG represents an Assuan socket on Windows as a marker file that
/// contains a loopback TCP port and a 16-byte nonce.
pub async fn serve<F>(
    ipc_client: IpcClient,
    gnupg_version: String,
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
    let info = Arc::new(assuan::AgentInfo::new(
        socket_path.to_string_lossy().into_owned(),
        gnupg_version,
    ));

    // The loopback listener is bound and the owner-only libassuan marker is
    // now visible.
    on_ready().context("failed to report GPG agent socket readiness")?;

    info!(
        path = %socket_path.display(),
        port,
        "GPG agent listening on Windows libassuan socket"
    );

    tokio::select! {
        result = accept_tcp_loop(listener, ipc_client, info, nonce) => {
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
    info: Arc<assuan::AgentInfo>,
    nonce: [u8; ASSUAN_NONCE_LEN],
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
        let info = Arc::clone(&info);
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
            let caller_guard = keyguard_agent_identity::caller_guard::CallerGuard::default();
            if let Err(e) =
                assuan::serve_connection(stream, ipc_client, caller, caller_guard, info).await
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
    let descriptor = LocalSecurityDescriptor::owner_only()
        .context("failed to create the owner-only security descriptor")?;
    let mut owner_defaulted = 0;
    let mut owner = std::ptr::null_mut();
    // SAFETY: `descriptor` is a valid self-relative security descriptor and
    // each output argument points to writable storage owned by this frame.
    let owner_read = unsafe {
        GetSecurityDescriptorOwner(descriptor.as_ptr(), &mut owner, &mut owner_defaulted)
    };
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
            descriptor.as_ptr(),
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

#[link(name = "advapi32")]
unsafe extern "system" {
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
    fn owner_only_descriptor_contains_the_exact_token_user_owner() -> Result<()> {
        let expected = keyguard_agent_identity::windows::current_user_sid_string()?;
        let descriptor = LocalSecurityDescriptor::owner_only()?;
        let mut owner = std::ptr::null_mut();
        let mut owner_defaulted = 0;
        // SAFETY: descriptor is live and both output pointers are writable.
        let succeeded = unsafe {
            GetSecurityDescriptorOwner(descriptor.as_ptr(), &mut owner, &mut owner_defaulted)
        };

        if succeeded == 0 {
            return Err(std::io::Error::last_os_error())
                .context("GetSecurityDescriptorOwner test query failed");
        }
        assert!(!owner.is_null());
        // SAFETY: `owner` points into the live `descriptor`.
        let owner = unsafe { keyguard_agent_identity::windows::sid_string(owner) }?;
        assert_eq!(owner, expected);
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
