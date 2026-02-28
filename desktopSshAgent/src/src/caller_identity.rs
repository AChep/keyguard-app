//! Best-effort caller identity extraction for SSH agent requests.
//!
//! The SSH agent protocol itself does not carry information about the
//! requesting process. On Unix we can derive the peer PID/UID/GID from
//! the connected Unix domain socket and enrich it with best-effort
//! process/app information.

use crate::ipc::messages::CallerIdentity;

use tokio::net::UnixStream;

pub(crate) fn caller_from_unix_stream(stream: &UnixStream) -> Option<CallerIdentity> {
    let cred = stream.peer_cred().ok()?;
    let pid = cred
        .pid()
        .and_then(|p| u32::try_from(p).ok())
        .unwrap_or(0);

    let mut identity = CallerIdentity {
        pid,
        uid: cred.uid() as u32,
        gid: cred.gid() as u32,
        process_name: String::new(),
        executable_path: String::new(),
        app_pid: 0,
        app_name: String::new(),
        app_bundle_path: String::new(),
    };

    if pid != 0 {
        #[cfg(target_os = "macos")]
        {
            populate_macos_details(&mut identity, pid);
        }
        #[cfg(target_os = "linux")]
        {
            populate_linux_details(&mut identity, pid);
        }
    }

    Some(identity)
}

// ================================================================
// macOS implementation
// ================================================================

#[cfg(target_os = "macos")]
fn populate_macos_details(identity: &mut CallerIdentity, pid: u32) {
    if let Some(exe) = macos_proc_pidpath(pid) {
        identity.executable_path = exe.clone();
        if identity.process_name.is_empty() {
            identity.process_name = exe
                .rsplit('/')
                .next()
                .unwrap_or_default()
                .to_string();
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
    let ret =
        unsafe { libc::proc_name(pid as c_int, buf.as_mut_ptr() as *mut c_void, buf.len() as u32) };
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

// ================================================================
// Linux implementation
// ================================================================

#[cfg(target_os = "linux")]
fn populate_linux_details(identity: &mut CallerIdentity, pid: u32) {
    let exe_path = linux_executable_path(pid);
    if let Some(exe) = &exe_path {
        identity.executable_path = exe.clone();
    }

    if let Some(name) = linux_process_name(pid).or_else(|| exe_path.as_deref().and_then(basename)) {
        identity.process_name = name;
    }
}

#[cfg(target_os = "linux")]
fn linux_executable_path(pid: u32) -> Option<String> {
    let link = std::path::PathBuf::from(format!("/proc/{pid}/exe"));
    let target = std::fs::read_link(link).ok()?;
    Some(target.to_string_lossy().to_string())
}

#[cfg(target_os = "linux")]
fn linux_process_name(pid: u32) -> Option<String> {
    let path = std::path::PathBuf::from(format!("/proc/{pid}/comm"));
    let s = std::fs::read_to_string(path).ok()?;
    let name = s.trim().to_string();
    if name.is_empty() { None } else { Some(name) }
}

#[cfg(target_os = "linux")]
fn basename(path: &str) -> Option<String> {
    path.rsplit('/')
        .next()
        .map(|s| s.to_string())
        .filter(|s| !s.is_empty())
}
