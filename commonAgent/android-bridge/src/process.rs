//! Shell export rendering and daemon startup.

use anyhow::{bail, Context, Result};
use std::ffi::CString;
use std::io;

/// Exits with the code of `result`, or prints its error and exits with 1.
pub fn exit_with(result: Result<i32>) -> ! {
    std::process::exit(match result {
        Ok(code) => code,
        Err(err) => {
            eprintln!("{err:#}");
            1
        }
    })
}

/// Points `fd` at `/dev/null`, for a daemon detached from its terminal.
pub fn redirect_null(fd: i32, write: bool) -> Result<()> {
    let path = CString::new("/dev/null").context("Invalid /dev/null path")?;
    let null_fd = open_null_fd(&path, write)?;
    if null_fd == fd {
        return Ok(());
    }

    if let Err(err) = duplicate_fd(null_fd, fd) {
        close_fd(null_fd);
        return Err(err).context("Failed to redirect file descriptor");
    }
    close_fd(null_fd);
    Ok(())
}

/// Detaches a freshly forked daemon from the terminal session that started it,
/// so closing that terminal does not hang it up, and points its standard
/// streams at `/dev/null`, so `eval "$(helper)"` does not wait for it.
pub fn detach_daemon() -> Result<()> {
    // SAFETY: `setsid(2)` takes no arguments and only changes the calling
    // process's session; a forked child is never a process group leader.
    if unsafe { libc::setsid() } == -1 {
        bail!(
            "Failed to detach the background agent: {}",
            io::Error::last_os_error()
        );
    }
    redirect_null(libc::STDIN_FILENO, false)?;
    redirect_null(libc::STDOUT_FILENO, true)?;
    redirect_null(libc::STDERR_FILENO, true)?;
    Ok(())
}

/// Forks the background agent: returns `None` in the child, detached with
/// [`detach_daemon`], and the child's pid in the parent.
///
/// # Safety
///
/// Call during startup, before the process creates any other thread.
pub unsafe fn fork_daemon() -> Result<Option<libc::pid_t>> {
    // SAFETY: the caller guarantees that no other thread exists yet.
    match unsafe { libc::fork() } {
        -1 => bail!(
            "Failed to fork background agent: {}",
            io::Error::last_os_error()
        ),
        0 => {
            detach_daemon()?;
            Ok(None)
        }
        pid => Ok(Some(pid)),
    }
}

/// Whether to print csh exports: `-c`, else a csh `$SHELL` unless `-s`.
pub fn prefers_csh(csh: bool, bash: bool) -> bool {
    csh || (!bash && std::env::var("SHELL").is_ok_and(|shell| shell.ends_with("csh")))
}

/// Renders `name=value` as a POSIX or csh export of an already quoted value.
pub fn render_export(is_csh: bool, name: &str, quoted_value: &str) -> String {
    if is_csh {
        format!("setenv {name} {quoted_value};")
    } else {
        format!("{name}={quoted_value}; export {name};")
    }
}

/// Single-quotes `value` for POSIX and csh shells.
pub fn shell_quote(value: &str) -> String {
    if value.is_empty() {
        return "''".to_string();
    }

    let mut quoted = String::with_capacity(value.len() + 2);
    quoted.push('\'');
    for ch in value.chars() {
        if ch == '\'' {
            quoted.push_str("'\"'\"'");
        } else {
            quoted.push(ch);
        }
    }
    quoted.push('\'');
    quoted
}

fn open_null_fd(path: &CString, write: bool) -> Result<i32> {
    // SAFETY: `path` is a valid NUL-terminated C string for the lifetime of the call, and the
    // flags are valid `open(2)` flags selecting read-only or read-write access to `/dev/null`.
    let fd = unsafe {
        libc::open(
            path.as_ptr(),
            if write { libc::O_RDWR } else { libc::O_RDONLY },
        )
    };
    if fd == -1 {
        bail!("Failed to open /dev/null: {}", io::Error::last_os_error());
    }
    Ok(fd)
}

fn duplicate_fd(src: i32, dst: i32) -> io::Result<()> {
    // SAFETY: `src` is an open file descriptor returned by `open(2)` and `dst` is one of the
    // standard file descriptor numbers being redirected. `dup2` performs the atomic replacement.
    let result = unsafe { libc::dup2(src, dst) };
    if result == -1 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

/// Best-effort `close(2)`.
pub fn close_fd(fd: i32) {
    // SAFETY: `fd` is either an owned descriptor opened by this module or a best-effort close on
    // teardown. `close(2)` does not require additional invariants beyond passing an integer fd.
    let _ = unsafe { libc::close(fd) };
}
