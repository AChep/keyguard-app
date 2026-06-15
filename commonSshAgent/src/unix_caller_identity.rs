use tokio::net::UnixStream;

#[cfg(any(target_os = "linux", target_os = "android"))]
use std::fs;
#[cfg(any(target_os = "linux", target_os = "android"))]
use std::io;
#[cfg(any(target_os = "linux", target_os = "android"))]
use std::mem::{size_of, MaybeUninit};
#[cfg(not(target_os = "macos"))]
use std::os::fd::AsRawFd;
#[cfg(any(target_os = "linux", target_os = "android"))]
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UnixCallerIdentity {
    pub pid: Option<u32>,
    pub uid: u32,
    pub gid: u32,
    pub process_name: Option<String>,
    pub executable_path: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct PeerCredentials {
    pid: Option<u32>,
    uid: u32,
    gid: u32,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ProcessDetails {
    pub process_name: Option<String>,
    pub executable_path: Option<String>,
}

#[cfg(target_os = "macos")]
fn peer_credentials_from_stream(stream: &UnixStream) -> Option<PeerCredentials> {
    let cred = stream.peer_cred().ok()?;
    Some(PeerCredentials {
        pid: cred.pid().and_then(|pid| u32::try_from(pid).ok()),
        uid: cred.uid(),
        gid: cred.gid(),
    })
}

#[cfg(not(target_os = "macos"))]
fn peer_credentials_from_stream(stream: &UnixStream) -> Option<PeerCredentials> {
    peer_credentials_from_fd(stream.as_raw_fd())
}

pub fn caller_from_unix_stream(stream: &UnixStream) -> Option<UnixCallerIdentity> {
    let peer = peer_credentials_from_stream(stream)?;
    #[cfg(any(target_os = "linux", target_os = "android"))]
    let mut caller = UnixCallerIdentity {
        pid: peer.pid,
        uid: peer.uid,
        gid: peer.gid,
        process_name: None,
        executable_path: None,
    };
    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    let caller = UnixCallerIdentity {
        pid: peer.pid,
        uid: peer.uid,
        gid: peer.gid,
        process_name: None,
        executable_path: None,
    };
    #[cfg(any(target_os = "linux", target_os = "android"))]
    if let Some(pid) = caller.pid {
        let details = process_details_from_pid(pid);
        if caller.process_name.is_none() {
            caller.process_name = details.process_name;
        }
        if caller.executable_path.is_none() {
            caller.executable_path = details.executable_path;
        }
    }
    Some(caller)
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn process_details_from_pid(pid: u32) -> ProcessDetails {
    process_details_from_pid_with(
        pid,
        |path| fs::read_to_string(path),
        |path| fs::read_link(path),
    )
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn process_details_from_pid(_pid: u32) -> ProcessDetails {
    ProcessDetails::default()
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn process_details_from_pid_with(
    pid: u32,
    mut read_comm: impl FnMut(&Path) -> io::Result<String>,
    mut read_link: impl FnMut(&Path) -> io::Result<PathBuf>,
) -> ProcessDetails {
    let comm_path = PathBuf::from(format!("/proc/{pid}/comm"));
    let exe_path = PathBuf::from(format!("/proc/{pid}/exe"));

    let executable_path = read_link(&exe_path)
        .ok()
        .map(|path| path.to_string_lossy().to_string());
    let process_name = read_comm(&comm_path)
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
        .or_else(|| executable_path.as_deref().and_then(basename));

    ProcessDetails {
        process_name,
        executable_path,
    }
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn basename(path: &str) -> Option<String> {
    Path::new(path)
        .file_name()
        .and_then(|name| name.to_str())
        .map(|name| name.to_string())
        .filter(|name| !name.is_empty())
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn peer_credentials_from_fd(fd: std::os::fd::RawFd) -> Option<PeerCredentials> {
    let mut cred = MaybeUninit::<libc::ucred>::uninit();
    let mut len = size_of::<libc::ucred>() as libc::socklen_t;
    // SAFETY: `cred` points to enough uninitialized memory for `libc::ucred`, and `len`
    // is initialized to that exact size before calling `getsockopt`.
    let result = unsafe {
        libc::getsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            cred.as_mut_ptr() as *mut libc::c_void,
            &mut len,
        )
    };
    if result != 0 || len as usize != size_of::<libc::ucred>() {
        return None;
    }

    // SAFETY: `getsockopt` succeeded and reported writing exactly `size_of::<libc::ucred>()`
    // bytes into `cred`, so the value is fully initialized here.
    let cred = unsafe { cred.assume_init() };
    Some(PeerCredentials {
        pid: u32::try_from(cred.pid).ok(),
        uid: cred.uid,
        gid: cred.gid,
    })
}

#[cfg(any(
    target_os = "ios",
    target_os = "freebsd",
    target_os = "openbsd",
    target_os = "netbsd",
    target_os = "dragonfly"
))]
fn peer_credentials_from_fd(fd: std::os::fd::RawFd) -> Option<PeerCredentials> {
    let mut uid: libc::uid_t = 0;
    let mut gid: libc::gid_t = 0;
    // SAFETY: `uid` and `gid` are valid mutable pointers for `getpeereid` to populate.
    let result = unsafe { libc::getpeereid(fd, &mut uid, &mut gid) };
    if result != 0 {
        return None;
    }

    Some(PeerCredentials {
        pid: None,
        uid: uid as u32,
        gid: gid as u32,
    })
}

#[cfg(not(any(
    target_os = "linux",
    target_os = "android",
    target_os = "macos",
    target_os = "ios",
    target_os = "freebsd",
    target_os = "openbsd",
    target_os = "netbsd",
    target_os = "dragonfly"
)))]
fn peer_credentials_from_fd(_fd: std::os::fd::RawFd) -> Option<PeerCredentials> {
    None
}

#[cfg(all(test, any(target_os = "linux", target_os = "android")))]
mod tests {
    use super::*;
    use std::cell::RefCell;
    use std::io::Error;
    use std::rc::Rc;

    #[test]
    fn process_details_reads_comm_and_exe_paths() {
        let seen_comm = Rc::new(RefCell::new(None::<String>));
        let seen_exe = Rc::new(RefCell::new(None::<String>));
        let pid = 4321;

        let details = process_details_from_pid_with(
            pid,
            {
                let seen_comm = Rc::clone(&seen_comm);
                move |path| {
                    seen_comm.replace(Some(path.display().to_string()));
                    Ok("ssh\n".to_string())
                }
            },
            {
                let seen_exe = Rc::clone(&seen_exe);
                move |path| {
                    seen_exe.replace(Some(path.display().to_string()));
                    Ok(PathBuf::from("/usr/bin/ssh"))
                }
            },
        );

        assert_eq!(details.process_name, Some("ssh".to_string()));
        assert_eq!(details.executable_path, Some("/usr/bin/ssh".to_string()));
        assert_eq!(seen_comm.borrow().as_deref(), Some("/proc/4321/comm"));
        assert_eq!(seen_exe.borrow().as_deref(), Some("/proc/4321/exe"));
    }

    #[test]
    fn process_details_falls_back_to_exe_basename_when_comm_is_missing() {
        let details = process_details_from_pid_with(
            7,
            |_path| Err(Error::new(std::io::ErrorKind::NotFound, "missing")),
            |_path| Ok(PathBuf::from("/usr/local/bin/git")),
        );

        assert_eq!(details.process_name, Some("git".to_string()));
        assert_eq!(
            details.executable_path,
            Some("/usr/local/bin/git".to_string())
        );
    }

    #[test]
    fn process_details_survives_lookup_failure() {
        let details = process_details_from_pid_with(
            1,
            |_path| Err(Error::new(std::io::ErrorKind::NotFound, "missing")),
            |_path| Err(Error::new(std::io::ErrorKind::NotFound, "missing")),
        );

        assert_eq!(details.process_name, None);
        assert_eq!(details.executable_path, None);
    }
}
