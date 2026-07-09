//! Windows libassuan socket serving for the Keyguard GPG agent.

use crate::assuan;
use crate::ipc::client::IpcClient;
use anyhow::{Context, Result};
use std::ffi::OsStr;
use std::fs;
use std::io::{ErrorKind, Write};
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

/// Serves the GPG agent over a native Windows libassuan socket.
///
/// Native GnuPG represents an Assuan socket on Windows as a marker file that
/// contains a loopback TCP port and a 16-byte nonce.
pub async fn serve(
    ipc_client: IpcClient,
    socket_path: &Path,
    parent_stdin_closed: oneshot::Receiver<()>,
) -> Result<()> {
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
    let _marker = AssuanSocketMarker::publish(socket_path, port, nonce)?;
    let socket_name = socket_path.to_string_lossy().into_owned();

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

            if let Err(e) = assuan::serve_connection(stream, ipc_client, None, socket_name).await {
                warn!("GPG Assuan connection failed: {e}");
            }
        });
    }
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
                .open(&temporary_path)
                .with_context(|| {
                    format!(
                        "failed to create Windows GPG socket marker: {}",
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
