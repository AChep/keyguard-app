//! Stable C ABI for the Keyguard agent IPC transport.
//!
//! `include/keyguard_agent_ipc.h` declares ABI v1. Each export unmarshals its raw
//! arguments and delegates to [`keyguard_agent_ipc_core::bridge`]. Panics never
//! cross the boundary.
//!
//! Every export except `abi_version`, `last_error` and `clear_error` runs in
//! [`bridge::export`], which clears this thread's diagnostic first. Strings are
//! strict UTF-8; a null pointer is allowed only with length 0. No pointer is
//! retained after a call.

use keyguard_agent_ipc_core::bridge;
use keyguard_ffi::{
    BRIDGE_INTERNAL, PanicHook, bytes_from_raw, bytes_from_raw_mut, string_from_raw,
};
use zeroize::Zeroizing;

/// Unit tests keep Rust's default hook so a caught assertion still reports
/// its payload and source location in CI logs.
const PANIC_HOOK: PanicHook = if cfg!(test) {
    PanicHook::Keep
} else {
    PanicHook::Redact
};

/// Returns the ABI version.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_agent_ipc_abi_version() -> u32 {
    keyguard_ffi::contained(PANIC_HOOK, || keyguard_agent_ipc_core::ABI_VERSION).unwrap_or(0)
}

/// Copies this thread's diagnostic without a NUL terminator. Returns the
/// required size and copies only if `capacity` holds it; 0 means none. Reading
/// does not clear it.
///
/// # Safety
///
/// A non-null `buffer` must be valid for `capacity` writable bytes for the
/// duration of the call. A null `buffer` with capacity 0 queries the size.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_agent_ipc_last_error(buffer: *mut u8, capacity: usize) -> usize {
    keyguard_ffi::contained(PANIC_HOOK, || {
        let Some(diagnostic) = bridge::last_error() else {
            return 0;
        };
        if capacity >= diagnostic.len() {
            // SAFETY: Forwarded from this function's own contract.
            if let Ok(out) = unsafe { bytes_from_raw_mut(buffer, capacity) } {
                out[..diagnostic.len()].copy_from_slice(diagnostic.as_bytes());
            }
        }
        diagnostic.len()
    })
    .unwrap_or(0)
}

/// Clears this thread's diagnostic.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_agent_ipc_clear_error() {
    let _ = keyguard_ffi::contained(PANIC_HOOK, bridge::clear_error);
}

/// Creates and secures an endpoint and returns its listener handle. It is
/// connectable on return. An empty `parent` is none (Windows); an empty
/// `exact_name` asks for a random name.
///
/// # Safety
///
/// Each non-null pointer must be valid for its length in readable bytes for the
/// duration of the call. A null pointer is allowed only with length 0.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_agent_ipc_listen(
    parent: *const u8,
    parent_length: usize,
    prefix: *const u8,
    prefix_length: usize,
    exact_name: *const u8,
    exact_length: usize,
) -> i64 {
    bridge::export(PANIC_HOOK, "listen", || {
        // SAFETY: Forwarded from this function's own contract.
        let parent = unsafe { string_from_raw(parent, parent_length) }?;
        // SAFETY: Forwarded from this function's own contract.
        let prefix = unsafe { string_from_raw(prefix, prefix_length) }?;
        // SAFETY: Forwarded from this function's own contract.
        let exact_name = unsafe { string_from_raw(exact_name, exact_length) }?;
        Ok(bridge::listen(parent, prefix, exact_name))
    })
}

/// Copies the listener's socket path or pipe name, UTF-8 without a NUL. Returns
/// the required size and copies only if `capacity` holds it.
///
/// # Safety
///
/// A non-null `buffer` must be valid for `capacity` writable bytes for the
/// duration of the call. A null `buffer` with capacity 0 queries the size.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_agent_ipc_endpoint(
    listener: u64,
    buffer: *mut u8,
    capacity: usize,
) -> i64 {
    bridge::export(PANIC_HOOK, "endpoint", || {
        // SAFETY: Forwarded from this function's own contract.
        let out = unsafe { bytes_from_raw_mut(buffer, capacity) }?;
        let size = bridge::endpoint(listener, |endpoint| {
            let bytes = endpoint.as_bytes();
            if let Some(target) = out.get_mut(..bytes.len()) {
                target.copy_from_slice(bytes);
            }
            bytes.len()
        });
        match size {
            Ok(size) => i64::try_from(size).map_err(|_| BRIDGE_INTERNAL),
            Err(code) => Ok(code),
        }
    })
}

/// Blocks until a client connects and returns the connection's handle.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_agent_ipc_accept(listener: u64) -> i64 {
    bridge::export(PANIC_HOOK, "accept", || Ok(bridge::accept(listener)))
}

/// Closes a listener and consumes its handle; wakes a blocked accept.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_agent_ipc_close_listener(listener: u64) -> i64 {
    bridge::export(PANIC_HOOK, "close_listener", || {
        Ok(bridge::close_listener(listener))
    })
}

/// The peer's process ID from the accept snapshot; `-7` when not verifiable.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_agent_ipc_peer_pid(connection: u64) -> i64 {
    bridge::export(PANIC_HOOK, "peer_pid", || Ok(bridge::peer_pid(connection)))
}

/// Reads up to `min(capacity, 1 MiB)` bytes: the count, or 0 at end of stream.
/// Capacity 0 is `-1`. The bytes go through a zeroized scratch buffer.
///
/// # Safety
///
/// A non-null `buffer` must be valid for `capacity` writable bytes for the
/// duration of the call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_agent_ipc_read(
    connection: u64,
    buffer: *mut u8,
    capacity: usize,
) -> i64 {
    bridge::export(PANIC_HOOK, "read", || {
        // SAFETY: Forwarded from this function's own contract.
        let out = unsafe { bytes_from_raw_mut(buffer, capacity) }?;
        let mut scratch = Zeroizing::new(vec![0_u8; capacity.min(bridge::MAX_READ_BYTES)]);
        let count = bridge::read(connection, &mut scratch);
        if count > 0 {
            let filled = usize::try_from(count)
                .ok()
                .and_then(|count| scratch.get(..count))
                .ok_or(BRIDGE_INTERNAL)?;
            out.get_mut(..filled.len())
                .ok_or(BRIDGE_INTERNAL)?
                .copy_from_slice(filled);
        }
        Ok(count)
    })
}

/// Writes all `length` bytes, at most 32 MiB, and returns 0. Length 0 returns 0
/// without a system call.
///
/// # Safety
///
/// A non-null `buffer` must be valid for `length` readable bytes for the duration
/// of the call. A null pointer is allowed only with length 0.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_agent_ipc_write(
    connection: u64,
    buffer: *const u8,
    length: usize,
) -> i64 {
    bridge::export(PANIC_HOOK, "write", || {
        // SAFETY: Forwarded from this function's own contract.
        let data = unsafe { bytes_from_raw(buffer, length) }?;
        Ok(bridge::write(connection, data))
    })
}

/// Waits without consuming input: 1 when input is readable, 0 at end of stream.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_agent_ipc_await_input(connection: u64) -> i64 {
    bridge::export(PANIC_HOOK, "await_input", || {
        Ok(bridge::await_input(connection))
    })
}

/// Closes a connection and consumes its handle; wakes every blocked call on it.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_agent_ipc_close(connection: u64) -> i64 {
    bridge::export(PANIC_HOOK, "close", || Ok(bridge::close(connection)))
}

#[cfg(test)]
mod tests {
    use std::{
        io::{Read, Write},
        ptr,
    };

    use super::*;

    fn last() -> String {
        let mut buffer = [0_u8; 256];
        // SAFETY: `buffer` is writable for its exact length.
        let size = unsafe { keyguard_agent_ipc_last_error(buffer.as_mut_ptr(), buffer.len()) };
        String::from_utf8(buffer[..size].to_vec()).unwrap()
    }

    #[cfg(unix)]
    const PARENT: &str = "/tmp";
    #[cfg(windows)]
    const PARENT: &str = "";

    fn listen(parent: &str, prefix: &str, exact: &str) -> i64 {
        // SAFETY: Every pointer refers to a live UTF-8 buffer of its length.
        unsafe {
            keyguard_agent_ipc_listen(
                parent.as_ptr(),
                parent.len(),
                prefix.as_ptr(),
                prefix.len(),
                exact.as_ptr(),
                exact.len(),
            )
        }
    }

    fn endpoint(listener: u64) -> String {
        // SAFETY: A zero-capacity query never touches its null buffer.
        let size = unsafe { keyguard_agent_ipc_endpoint(listener, ptr::null_mut(), 0) };
        assert!(size > 0, "endpoint failed: {size}");
        let mut buffer = vec![0_u8; size as usize];
        // SAFETY: `buffer` is writable for its exact length.
        let copied =
            unsafe { keyguard_agent_ipc_endpoint(listener, buffer.as_mut_ptr(), buffer.len()) };
        assert_eq!(copied, size);
        String::from_utf8(buffer).unwrap()
    }

    #[cfg(unix)]
    fn connect(endpoint: &str) -> std::os::unix::net::UnixStream {
        std::os::unix::net::UnixStream::connect(endpoint).unwrap()
    }

    #[cfg(windows)]
    fn connect(endpoint: &str) -> std::fs::File {
        use std::time::{Duration, Instant};

        let deadline = Instant::now() + Duration::from_secs(10);
        loop {
            match std::fs::OpenOptions::new()
                .read(true)
                .write(true)
                .open(endpoint)
            {
                Ok(client) => return client,
                // ERROR_PIPE_BUSY: every instance is connecting.
                Err(error) if error.raw_os_error() == Some(231) && Instant::now() < deadline => {
                    std::thread::sleep(Duration::from_millis(2));
                }
                Err(error) => panic!("connect failed: {error}"),
            }
        }
    }

    fn read(connection: u64, buffer: &mut [u8]) -> i64 {
        // SAFETY: `buffer` is writable for its exact length.
        unsafe { keyguard_agent_ipc_read(connection, buffer.as_mut_ptr(), buffer.len()) }
    }

    fn write(connection: u64, data: &[u8]) -> i64 {
        // SAFETY: `data` is readable for its exact length.
        unsafe { keyguard_agent_ipc_write(connection, data.as_ptr(), data.len()) }
    }

    #[test]
    fn the_abi_version_is_pinned_to_one() {
        assert_eq!(keyguard_agent_ipc_abi_version(), 1);
    }

    #[test]
    fn the_diagnostic_is_copied_only_when_it_fits() {
        assert_eq!(bridge::close(0), -5);
        let expected = b"close: invalid_handle";
        // SAFETY: A zero-capacity query never touches its null buffer.
        let required = unsafe { keyguard_agent_ipc_last_error(ptr::null_mut(), 0) };
        assert_eq!(required, expected.len());
        let mut small = [42_u8; 4];
        // SAFETY: `small` is writable for its exact length.
        let size = unsafe { keyguard_agent_ipc_last_error(small.as_mut_ptr(), small.len()) };
        assert_eq!(size, required);
        assert_eq!(small, [42; 4]);
        // SAFETY: A null buffer with a non-zero capacity is rejected before any access.
        let size = unsafe { keyguard_agent_ipc_last_error(ptr::null_mut(), 64) };
        assert_eq!(size, required);
        let mut buffer = [0_u8; 64];
        // SAFETY: `buffer` is writable for its exact length.
        let size = unsafe { keyguard_agent_ipc_last_error(buffer.as_mut_ptr(), buffer.len()) };
        assert_eq!(&buffer[..size], expected);
        // Reading does not clear it; clearing does.
        // SAFETY: Another zero-capacity query.
        let size = unsafe { keyguard_agent_ipc_last_error(ptr::null_mut(), 0) };
        assert_eq!(size, required);
        keyguard_agent_ipc_clear_error();
        // SAFETY: Another zero-capacity query.
        let size = unsafe { keyguard_agent_ipc_last_error(ptr::null_mut(), 0) };
        assert_eq!(size, 0);
    }

    #[test]
    fn strings_must_be_utf8_with_valid_pointers() {
        let invalid = [b'k', 0xff];
        // SAFETY: Every pointer refers to a live buffer of its length.
        let code = unsafe {
            keyguard_agent_ipc_listen(
                PARENT.as_ptr(),
                PARENT.len(),
                invalid.as_ptr(),
                invalid.len(),
                ptr::null(),
                0,
            )
        };
        assert_eq!(code, -1);
        assert_eq!(last(), "listen: invalid_argument");
        // SAFETY: A null pointer with a non-zero length is rejected before any access.
        let code =
            unsafe { keyguard_agent_ipc_listen(ptr::null(), 0, ptr::null(), 3, ptr::null(), 0) };
        assert_eq!(code, -1);
        assert_eq!(last(), "listen: invalid_argument");
        // Names are validated by the core before anything is created.
        assert_eq!(listen(PARENT, "kg/", ""), -1);
        assert_eq!(last(), "listen: invalid_prefix");
        assert_eq!(listen(PARENT, "kg-", ".."), -1);
        assert_eq!(last(), "listen: invalid_name");
    }

    #[test]
    fn buffers_are_checked_before_any_lookup() {
        let mut buffer = [0_u8; 4];
        assert_eq!(read(0, &mut []), -1);
        assert_eq!(last(), "read: empty_buffer");
        // SAFETY: A null buffer with a non-zero capacity is rejected before any access.
        let code = unsafe { keyguard_agent_ipc_read(0, ptr::null_mut(), 4) };
        assert_eq!(code, -1);
        assert_eq!(last(), "read: invalid_argument");
        assert_eq!(read(0, &mut buffer), -5);
        assert_eq!(last(), "read: invalid_handle");
        // SAFETY: A null buffer with a non-zero length is rejected before any access.
        assert_eq!(unsafe { keyguard_agent_ipc_write(0, ptr::null(), 4) }, -1);
        assert_eq!(last(), "write: invalid_argument");
        let too_long = vec![0_u8; bridge::MAX_WRITE_BYTES + 1];
        assert_eq!(write(0, &too_long), -1);
        assert_eq!(last(), "write: too_long");
        // SAFETY: An empty write may use a null pointer.
        assert_eq!(unsafe { keyguard_agent_ipc_write(0, ptr::null(), 0) }, -5);
        // SAFETY: A null buffer with a non-zero capacity is rejected before any access.
        let code = unsafe { keyguard_agent_ipc_endpoint(0, ptr::null_mut(), 8) };
        assert_eq!(code, -1);
        assert_eq!(last(), "endpoint: invalid_argument");
        // SAFETY: A zero-capacity query never touches its null buffer.
        let code = unsafe { keyguard_agent_ipc_endpoint(0, ptr::null_mut(), 0) };
        assert_eq!(code, -5);
        assert_eq!(last(), "endpoint: invalid_handle");
    }

    #[test]
    fn unknown_handles_are_rejected() {
        let calls: [(extern "C" fn(u64) -> i64, &str); 5] = [
            (keyguard_agent_ipc_accept, "accept"),
            (keyguard_agent_ipc_close_listener, "close_listener"),
            (keyguard_agent_ipc_peer_pid, "peer_pid"),
            (keyguard_agent_ipc_await_input, "await_input"),
            (keyguard_agent_ipc_close, "close"),
        ];
        for (call, operation) in calls {
            assert_eq!(call(0), -5, "{operation}");
            assert_eq!(last(), format!("{operation}: invalid_handle"));
            assert_eq!(call(u64::MAX), -5, "{operation}");
        }
    }

    #[test]
    fn panics_are_contained_as_internal_failures() {
        let code = bridge::export(PANIC_HOOK, "write", || -> Result<i64, i64> {
            panic!("fixture")
        });
        assert_eq!(code, -8);
        assert_eq!(last(), "write: panic");
    }

    #[test]
    fn a_connection_round_trips_through_the_c_abi() {
        let listener = listen(PARENT, "kg-c-test-", "");
        assert!(listener > 0, "listen failed: {listener}");
        let listener = listener as u64;
        let endpoint = endpoint(listener);
        // A buffer one byte short copies nothing.
        let mut short = vec![42_u8; endpoint.len() - 1];
        // SAFETY: `short` is writable for its exact length.
        let size =
            unsafe { keyguard_agent_ipc_endpoint(listener, short.as_mut_ptr(), short.len()) };
        assert_eq!(size, endpoint.len() as i64);
        assert!(short.iter().all(|byte| *byte == 42));

        let mut client = connect(&endpoint);
        let connection = keyguard_agent_ipc_accept(listener);
        assert!(connection > 0, "accept failed: {connection}");
        let connection = connection as u64;
        assert_eq!(
            keyguard_agent_ipc_peer_pid(connection),
            i64::from(std::process::id())
        );

        client.write_all(b"ping").unwrap();
        assert_eq!(keyguard_agent_ipc_await_input(connection), 1);
        let mut ping = [0_u8; 8];
        let mut received = 0;
        while received < 4 {
            let count = read(connection, &mut ping[received..]);
            assert!(count > 0, "read failed: {count}");
            received += count as usize;
        }
        assert_eq!(&ping[..4], b"ping");
        assert_eq!(write(connection, b"pong"), 0);
        assert_eq!(write(connection, &[]), 0);
        let mut pong = [0_u8; 4];
        client.read_exact(&mut pong).unwrap();
        assert_eq!(&pong, b"pong");

        drop(client);
        assert_eq!(keyguard_agent_ipc_await_input(connection), 0);
        assert_eq!(read(connection, &mut ping), 0);
        assert_eq!(keyguard_agent_ipc_close(connection), 0);
        assert_eq!(keyguard_agent_ipc_close(connection), -5);
        assert_eq!(keyguard_agent_ipc_close_listener(listener), 0);
        assert_eq!(keyguard_agent_ipc_accept(listener), -5);
        assert_eq!(keyguard_agent_ipc_close_listener(listener), -5);
    }
}
