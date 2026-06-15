//! Best-effort caller identity extraction for SSH agent requests.
//!
//! The SSH agent protocol itself does not carry information about the
//! requesting process. On Unix we can derive the peer PID/UID/GID from
//! the connected Unix domain socket and enrich it with best-effort
//! process/app information.

use crate::ipc::messages::CallerIdentity;
use common_ssh_agent_rust::unix_caller_identity::{
    caller_from_unix_stream as shared_caller_from_unix_stream, UnixCallerIdentity,
};
use tokio::net::UnixStream;

pub(crate) fn caller_from_unix_stream(stream: &UnixStream) -> Option<CallerIdentity> {
    let shared_identity = shared_caller_from_unix_stream(stream)?;
    let mut identity = proto_identity_from_shared(shared_identity);

    if identity.pid != 0 {
        #[cfg(target_os = "macos")]
        {
            let pid = identity.pid;
            populate_macos_details(&mut identity, pid);
        }
    }

    Some(identity)
}

fn proto_identity_from_shared(shared_identity: UnixCallerIdentity) -> CallerIdentity {
    CallerIdentity {
        pid: shared_identity.pid.unwrap_or(0),
        uid: shared_identity.uid,
        gid: shared_identity.gid,
        process_name: shared_identity.process_name.unwrap_or_default(),
        executable_path: shared_identity.executable_path.unwrap_or_default(),
        app_pid: 0,
        app_name: String::new(),
        app_bundle_path: String::new(),
    }
}

// ================================================================
// macOS implementation
// ================================================================

#[cfg(target_os = "macos")]
fn populate_macos_details(identity: &mut CallerIdentity, pid: u32) {
    if let Some(exe) = macos_proc_pidpath(pid) {
        identity.executable_path = exe.clone();
        if identity.process_name.is_empty() {
            identity.process_name = exe.rsplit('/').next().unwrap_or_default().to_string();
        }
    }

    if identity.process_name.is_empty() {
        if let Some(name) = macos_proc_name(pid) {
            identity.process_name = name;
        }
    }

    if let Some((app_pid, app_name, app_bundle_path)) = resolve_macos_app(pid, 8) {
        identity.app_pid = app_pid;
        identity.app_name = app_name;
        identity.app_bundle_path = app_bundle_path;
    }
}

#[cfg(target_os = "macos")]
fn macos_proc_pidpath(pid: u32) -> Option<String> {
    use libc::{c_int, c_void};

    let mut buf = vec![0u8; libc::PROC_PIDPATHINFO_MAXSIZE as usize];
    let ret = unsafe {
        libc::proc_pidpath(
            pid as c_int,
            buf.as_mut_ptr() as *mut c_void,
            buf.len() as u32,
        )
    };
    if ret <= 0 {
        return None;
    }

    // proc_pidpath returns a C string, but `ret` is the buffer size written.
    let len = buf.iter().position(|&b| b == 0).unwrap_or(buf.len());
    Some(String::from_utf8_lossy(&buf[..len]).to_string())
}

#[cfg(target_os = "macos")]
fn macos_proc_name(pid: u32) -> Option<String> {
    use libc::{c_int, c_void};

    let mut buf = vec![0u8; 256];
    let ret = unsafe {
        libc::proc_name(
            pid as c_int,
            buf.as_mut_ptr() as *mut c_void,
            buf.len() as u32,
        )
    };
    if ret <= 0 {
        return None;
    }
    let len = buf.iter().position(|&b| b == 0).unwrap_or(buf.len());
    Some(String::from_utf8_lossy(&buf[..len]).to_string())
}

#[cfg(target_os = "macos")]
fn macos_parent_pid(pid: u32) -> Option<u32> {
    use libc::{c_int, c_void, proc_bsdinfo};

    let mut info = proc_bsdinfo {
        pbi_flags: 0,
        pbi_status: 0,
        pbi_xstatus: 0,
        pbi_pid: 0,
        pbi_ppid: 0,
        pbi_uid: 0,
        pbi_gid: 0,
        pbi_ruid: 0,
        pbi_rgid: 0,
        pbi_svuid: 0,
        pbi_svgid: 0,
        rfu_1: 0,
        pbi_comm: [0; libc::MAXCOMLEN as usize],
        pbi_name: [0; 32],
        pbi_nfiles: 0,
        pbi_pgid: 0,
        pbi_pjobc: 0,
        e_tdev: 0,
        e_tpgid: 0,
        pbi_nice: 0,
        pbi_start_tvsec: 0,
        pbi_start_tvusec: 0,
    };

    let ret = unsafe {
        libc::proc_pidinfo(
            pid as c_int,
            libc::PROC_PIDTBSDINFO,
            0,
            &mut info as *mut proc_bsdinfo as *mut c_void,
            std::mem::size_of::<proc_bsdinfo>() as c_int,
        )
    };
    if ret <= 0 {
        return None;
    }
    Some(info.pbi_ppid)
}

#[cfg(target_os = "macos")]
fn resolve_macos_app(pid: u32, max_depth: usize) -> Option<(u32, String, String)> {
    use std::path::Path;

    let mut current_pid = pid;
    for _ in 0..max_depth {
        let exe = macos_proc_pidpath(current_pid)?;
        if let Some(bundle) = find_app_bundle_path(&exe) {
            let name = Path::new(&bundle)
                .file_stem()
                .and_then(|s| s.to_str())
                .unwrap_or_default()
                .to_string();
            return Some((current_pid, name, bundle));
        }

        let parent = macos_parent_pid(current_pid)?;
        if parent == 0 || parent == current_pid {
            break;
        }
        current_pid = parent;
    }
    None
}

#[cfg(target_os = "macos")]
fn find_app_bundle_path(executable_path: &str) -> Option<String> {
    use std::path::Path;

    let path = Path::new(executable_path);
    for ancestor in path.ancestors() {
        let Some(name) = ancestor.file_name().and_then(|s| s.to_str()) else {
            continue;
        };
        if name.ends_with(".app") {
            return Some(ancestor.to_string_lossy().to_string());
        }
    }
    None
}

#[cfg(all(test, target_os = "macos"))]
mod tests {
    use super::caller_from_unix_stream;
    use tempfile::tempdir;
    use tokio::net::{UnixListener, UnixStream};

    #[tokio::test(flavor = "current_thread")]
    async fn caller_identity_recovers_macos_peer_pid_and_details() {
        let tempdir = tempdir().expect("tempdir");
        let socket_path = tempdir.path().join("caller-identity.sock");
        let listener = UnixListener::bind(&socket_path).expect("bind listener");

        let connect_task = tokio::spawn({
            let socket_path = socket_path.clone();
            async move {
                UnixStream::connect(socket_path)
                    .await
                    .expect("connect client")
            }
        });

        let (server_stream, _) = listener.accept().await.expect("accept client");
        let client_stream = connect_task.await.expect("join connect task");
        let identity = caller_from_unix_stream(&server_stream).expect("caller identity");

        assert_eq!(identity.pid, std::process::id());
        assert!(
            !identity.process_name.is_empty() || !identity.executable_path.is_empty(),
            "expected process_name or executable_path to be populated"
        );

        drop(client_stream);
    }
}
