//! Windows named pipe SSH agent server.

use crate::agent::{KeyProvider, KeyguardAgentFactory};
use crate::windows_identity::current_process_user_sid_string;
use anyhow::{Context, Result};
use async_trait::async_trait;
use ssh_agent_lib::agent::ListeningSocket;
use std::ffi::{c_void, OsStr, OsString};
use std::io;
use std::os::windows::ffi::OsStrExt;
use std::path::Path;
use tokio::net::windows::named_pipe::{NamedPipeServer, ServerOptions};
use tokio::sync::oneshot;
use tracing::info;

const SDDL_REVISION_1: u32 = 1;

/// Named-pipe listener that applies an owner-only DACL to every pipe instance.
///
/// `ssh-agent-lib` otherwise relies on the process's default named-pipe DACL,
/// which can grant access to principals other than the current desktop user.
#[derive(Debug)]
struct OwnerOnlyNamedPipeListener {
    server: NamedPipeServer,
    pipe_name: OsString,
}

impl OwnerOnlyNamedPipeListener {
    fn bind(pipe_name: impl Into<OsString>) -> io::Result<Self> {
        let pipe_name = pipe_name.into();
        let server = create_owner_only_pipe(&pipe_name, true)?;
        Ok(Self { server, pipe_name })
    }
}

#[async_trait]
impl ListeningSocket for OwnerOnlyNamedPipeListener {
    type Stream = NamedPipeServer;

    async fn accept(&mut self) -> io::Result<Self::Stream> {
        self.server.connect().await?;
        let next = create_owner_only_pipe(&self.pipe_name, false)?;
        Ok(std::mem::replace(&mut self.server, next))
    }
}

fn create_owner_only_pipe(pipe_name: &OsStr, first_instance: bool) -> io::Result<NamedPipeServer> {
    let descriptor = LocalSecurityDescriptor::owner_only()?;
    let mut attributes = SecurityAttributes {
        length: std::mem::size_of::<SecurityAttributes>() as u32,
        security_descriptor: descriptor.as_ptr(),
        inherit_handle: 0,
    };
    let mut options = ServerOptions::new();
    options
        .first_pipe_instance(first_instance)
        .reject_remote_clients(true);

    // SAFETY: `attributes` has the Windows SECURITY_ATTRIBUTES ABI, and its
    // security descriptor remains allocated until CreateNamedPipeW returns.
    // Windows copies the descriptor into the newly created pipe object.
    unsafe {
        options.create_with_security_attributes_raw(
            pipe_name,
            std::ptr::from_mut(&mut attributes).cast(),
        )
    }
}

#[repr(C)]
struct SecurityAttributes {
    length: u32,
    security_descriptor: *mut c_void,
    inherit_handle: i32,
}

struct LocalSecurityDescriptor(*mut c_void);

impl LocalSecurityDescriptor {
    fn owner_only() -> io::Result<Self> {
        let owner_only_sddl = owner_only_sddl()?;
        let sddl: Vec<u16> = OsStr::new(&owner_only_sddl)
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
            return Err(io::Error::last_os_error());
        }
        if descriptor.is_null() {
            return Err(io::Error::other(
                "Windows returned a null owner-only security descriptor",
            ));
        }
        Ok(Self(descriptor))
    }

    fn as_ptr(&self) -> *mut c_void {
        self.0
    }
}

fn owner_only_sddl() -> io::Result<String> {
    let user_sid = current_process_user_sid_string()?;
    // Name the token user explicitly as both owner and sole DACL trustee.
    // `OW` means Owner Rights, not current user; elevated processes can
    // otherwise create objects owned by the Administrators group.
    Ok(format!("O:{user_sid}D:P(A;;GA;;;{user_sid})"))
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

#[link(name = "advapi32")]
unsafe extern "system" {
    fn ConvertStringSecurityDescriptorToSecurityDescriptorW(
        string_security_descriptor: *const u16,
        string_sd_revision: u32,
        security_descriptor: *mut *mut c_void,
        security_descriptor_size: *mut u32,
    ) -> i32;
}

#[link(name = "kernel32")]
unsafe extern "system" {
    fn LocalFree(memory: *mut c_void) -> *mut c_void;
}

/// Serves the SSH agent protocol over a Windows named pipe.
///
/// The pipe name is expected to be in the format `\\.\pipe\keyguard-ssh-agent`.
pub async fn serve<K: KeyProvider>(
    agent: KeyguardAgentFactory<K>,
    pipe_path: &Path,
    parent_stdin_closed: oneshot::Receiver<()>,
) -> Result<()> {
    let pipe_name = pipe_path
        .to_str()
        .context("Invalid pipe name (not valid UTF-8)")?;

    info!(
        pipe = %pipe_name,
        "SSH agent listening on Windows named pipe"
    );

    // Keep the public pipe name compatible, while failing startup if another
    // pipe instance already owns it and restricting all instances to the
    // current owner. The local server retains bounded framing and deadlines.
    let listener = OwnerOnlyNamedPipeListener::bind(pipe_name)
        .with_context(|| format!("Failed to bind named pipe: {}", pipe_name))?;

    super::server::listen_until(listener, agent, async move {
        let _ = parent_stdin_closed.await;
    })
    .await
    .map_err(|e| anyhow::anyhow!("SSH agent server error: {}", e))?;
    info!("Parent stdin closed, stopping SSH agent listener");

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::time::Duration;
    use tokio::net::windows::named_pipe::ClientOptions;
    use tokio::time::timeout;

    static NEXT_PIPE_ID: AtomicU64 = AtomicU64::new(0);

    fn unique_pipe_name() -> OsString {
        format!(
            r"\\.\pipe\keyguard-ssh-agent-test-{}-{}",
            std::process::id(),
            NEXT_PIPE_ID.fetch_add(1, Ordering::Relaxed)
        )
        .into()
    }

    #[test]
    fn owner_only_security_descriptor_is_valid_sddl() -> io::Result<()> {
        let descriptor = LocalSecurityDescriptor::owner_only()?;

        assert!(!descriptor.as_ptr().is_null());
        Ok(())
    }

    #[test]
    fn owner_only_sddl_names_the_token_user_instead_of_owner_rights() -> io::Result<()> {
        let user_sid = current_process_user_sid_string()?;
        let sddl = owner_only_sddl()?;

        assert!(sddl.starts_with("O:S-"));
        assert!(!sddl.contains(";;;OW"));
        assert_eq!(sddl.matches(&user_sid).count(), 2);
        Ok(())
    }

    #[tokio::test]
    async fn listener_rejects_a_second_first_instance_and_accepts_local_client() -> io::Result<()> {
        let pipe_name = unique_pipe_name();
        let mut listener = OwnerOnlyNamedPipeListener::bind(pipe_name.clone())?;

        assert!(create_owner_only_pipe(&pipe_name, true).is_err());

        let client = ClientOptions::new().open(&pipe_name)?;
        let accepted = timeout(Duration::from_secs(2), listener.accept())
            .await
            .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "pipe accept timed out"))??;

        drop(accepted);
        drop(client);
        Ok(())
    }
}
