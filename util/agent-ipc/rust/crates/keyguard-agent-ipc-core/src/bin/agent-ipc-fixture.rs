//! A child process for the cross-process tests: it connects to the endpoint in its
//! only argument as an agent helper does, so its pid is the peer the listener must
//! report. It then waits until the server closes the connection.
//!
//! Exit codes: 0 after the server closed, 1 when it cannot connect, 2 for bad
//! arguments.

use std::{io::Read, process::ExitCode};

fn main() -> ExitCode {
    let mut arguments = std::env::args().skip(1);
    let (Some(endpoint), None) = (arguments.next(), arguments.next()) else {
        return ExitCode::from(2);
    };
    let Some(mut stream) = connect(&endpoint) else {
        return ExitCode::from(1);
    };
    let mut byte = [0; 1];
    while matches!(stream.read(&mut byte), Ok(count) if count > 0) {}
    ExitCode::SUCCESS
}

#[cfg(unix)]
fn connect(endpoint: &str) -> Option<std::os::unix::net::UnixStream> {
    std::os::unix::net::UnixStream::connect(endpoint).ok()
}

/// Opens the pipe, waiting while every instance is busy, as helpers do.
#[cfg(windows)]
fn connect(endpoint: &str) -> Option<std::fs::File> {
    use std::time::{Duration, Instant};
    use windows_sys::Win32::Foundation::ERROR_PIPE_BUSY;

    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        match std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open(endpoint)
        {
            Ok(pipe) => return Some(pipe),
            Err(error)
                if error.raw_os_error() == Some(ERROR_PIPE_BUSY as i32)
                    && Instant::now() < deadline =>
            {
                std::thread::sleep(Duration::from_millis(10));
            }
            Err(_) => return None,
        }
    }
}
