//! Windows named-pipe specifics (plan §4.1, §5.1 items 9, 10, 12, 13 and 17): the
//! owner-only DACL, a name another creator holds, vanished clients, a name that
//! never lapses, the synchronous outcome paths under 5 s bounds, and stress
//! around close.

#![cfg(windows)]

mod support;

use std::{
    io::{Read, Write},
    os::windows::io::AsRawHandle,
    path::Path,
    ptr,
    sync::{
        Arc,
        atomic::{AtomicBool, AtomicUsize, Ordering},
    },
    thread,
    time::{Duration, Instant},
};

use keyguard_agent_ipc::stream;
use keyguard_agent_ipc_core::{Error, Listener, Readiness};
use support::{MIB, Parent, pair, read_exact, within};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use windows_sys::Win32::{
    Foundation::{
        CloseHandle, ERROR_FILE_NOT_FOUND, ERROR_INSUFFICIENT_BUFFER, GetLastError,
        INVALID_HANDLE_VALUE, LocalFree,
    },
    Security::{
        ACCESS_ALLOWED_ACE, ACL,
        Authorization::{GetSecurityInfo, SE_FILE_OBJECT},
        DACL_SECURITY_INFORMATION, EqualSid, GetAce, GetSecurityDescriptorControl,
        GetTokenInformation, OWNER_SECURITY_INFORMATION, SE_DACL_PROTECTED, TOKEN_QUERY,
        TOKEN_USER, TokenUser,
    },
    Storage::FileSystem::{FILE_ALL_ACCESS, PIPE_ACCESS_DUPLEX, WriteFile},
    System::{
        Pipes::{CreateNamedPipeW, PIPE_READMODE_BYTE, PIPE_TYPE_BYTE, PIPE_WAIT},
        Threading::{GetCurrentProcess, OpenProcessToken},
    },
};

const BOUND: Duration = Duration::from_secs(5);

/// The `TokenUser` answer of this process, in 8-byte aligned storage.
fn token_user() -> Vec<u64> {
    let mut token = ptr::null_mut();
    // SAFETY: A pseudo-handle and a writable output.
    let opened = unsafe { OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) };
    assert_ne!(opened, 0);
    let mut required = 0;
    // SAFETY: A sizing call with a null buffer.
    let sized = unsafe { GetTokenInformation(token, TokenUser, ptr::null_mut(), 0, &mut required) };
    // SAFETY: No preconditions.
    assert!(sized != 0 || unsafe { GetLastError() } == ERROR_INSUFFICIENT_BUFFER);
    let mut buffer = vec![0_u64; (required as usize).div_ceil(8)];
    // SAFETY: `buffer` is aligned and has `required` writable bytes.
    let queried = unsafe {
        GetTokenInformation(
            token,
            TokenUser,
            buffer.as_mut_ptr().cast(),
            required,
            &mut required,
        )
    };
    assert_ne!(queried, 0);
    // SAFETY: OpenProcessToken returned this handle.
    unsafe { CloseHandle(token) };
    buffer
}

/// The owner and the DACL are this user's alone: one protected ACE that grants
/// the user full access, inherited by nothing.
#[test]
fn dacl_readback() {
    let parent = Parent::new();
    let listener = parent.bind();
    let client = support::connect(listener.endpoint());
    let user = token_user();
    // SAFETY: TokenUser wrote a TOKEN_USER at the start of the aligned buffer.
    let user_sid = unsafe { (*user.as_ptr().cast::<TOKEN_USER>()).User.Sid };
    let (mut owner, mut dacl, mut descriptor) = (ptr::null_mut(), ptr::null_mut(), ptr::null_mut());
    // SAFETY: The client handle has READ_CONTROL, and every output is writable.
    let status = unsafe {
        GetSecurityInfo(
            client.as_raw_handle(),
            SE_FILE_OBJECT,
            OWNER_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION,
            &mut owner,
            ptr::null_mut(),
            &mut dacl,
            ptr::null_mut(),
            &mut descriptor,
        )
    };
    assert_eq!(status, 0);
    let (mut control, mut revision) = (0, 0);
    // SAFETY: GetSecurityInfo returned a valid descriptor; outputs are writable.
    let read = unsafe { GetSecurityDescriptorControl(descriptor, &mut control, &mut revision) };
    assert_ne!(read, 0);
    assert_ne!(control & SE_DACL_PROTECTED, 0, "the DACL inherits");
    // SAFETY: Both SIDs are valid and alive.
    assert_ne!(unsafe { EqualSid(owner, user_sid) }, 0, "another owner");
    assert!(!dacl.is_null());
    // SAFETY: The DACL lives in `descriptor`.
    let acl: &ACL = unsafe { &*dacl };
    assert_eq!(acl.AceCount, 1);
    let mut entry = ptr::null_mut();
    // SAFETY: The ACL holds one ACE, and `entry` is writable.
    assert_ne!(unsafe { GetAce(dacl, 0, &mut entry) }, 0);
    // SAFETY: GetAce returned the ACE inside the live descriptor; its type is
    // checked before the access-allowed fields are trusted.
    let ace = unsafe { &*entry.cast::<ACCESS_ALLOWED_ACE>() };
    // ACCESS_ALLOWED_ACE_TYPE, with no inheritance flags.
    assert_eq!(ace.Header.AceType, 0);
    assert_eq!(ace.Header.AceFlags, 0);
    // GENERIC_ALL, mapped for a pipe.
    assert_eq!(ace.Mask, FILE_ALL_ACCESS);
    let trustee = ptr::from_ref(&ace.SidStart).cast_mut().cast();
    // SAFETY: The SID starts inside the ACE, which lives in `descriptor`.
    assert_ne!(unsafe { EqualSid(trustee, user_sid) }, 0, "another trustee");
    // SAFETY: GetSecurityInfo allocated the descriptor with LocalAlloc.
    unsafe { LocalFree(descriptor) };
}

#[test]
fn second_bind_same_name_is_permission() {
    let parent = Parent::new();
    let name = support::exact_name();
    let first = Listener::bind(parent.location(Some(&name))).unwrap();
    let failure = Listener::bind(parent.location(Some(&name))).err().unwrap();
    assert_eq!(failure.kind(), Error::Permission);
    assert_eq!(failure.to_string(), "listen: name_in_use (os 5)");
    drop(first);

    // A pipe that another creator made first, with its own security, allowing one
    // instance (the next is busy) or any number (the first-instance flag is denied).
    for (instances, code) in [(1, 231), (255, 5)] {
        let other = support::exact_name();
        let wide: Vec<u16> = format!(r"\\.\pipe\{other}")
            .encode_utf16()
            .chain([0])
            .collect();
        // SAFETY: `wide` is NUL-terminated; NULL attributes give the default descriptor.
        let pipe = unsafe {
            CreateNamedPipeW(
                wide.as_ptr(),
                PIPE_ACCESS_DUPLEX,
                PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT,
                instances,
                512,
                512,
                0,
                ptr::null(),
            )
        };
        assert_ne!(pipe, INVALID_HANDLE_VALUE);
        let failure = Listener::bind(parent.location(Some(&other))).err().unwrap();
        assert_eq!(
            failure.to_string(),
            format!("listen: name_in_use (os {code})")
        );
        assert_eq!(failure.kind(), Error::Permission);
        // SAFETY: CreateNamedPipeW returned this handle.
        unsafe { CloseHandle(pipe) };
    }
}

#[test]
fn vanished_client_then_real_client() {
    within(BOUND, || {
        let parent = Parent::new();
        let listener = Arc::new(parent.bind());
        // Connects and closes before accept runs.
        drop(support::try_connect(listener.endpoint()).unwrap());
        let accepting = Arc::clone(&listener);
        let accepted = thread::spawn(move || accepting.accept());
        let mut client = support::connect(listener.endpoint());
        let connection = accepted.join().unwrap().unwrap();
        client.write_all(b"real").unwrap();
        let mut data = [0; 4];
        read_exact(&connection, &mut data);
        assert_eq!(data, *b"real");
    });
}

/// A client connected before `ConnectNamedPipe`: it returns `ERROR_PIPE_CONNECTED`.
#[test]
fn pipe_connected_before_connect_path() {
    within(BOUND, || {
        let parent = Parent::new();
        let listener = parent.bind();
        let mut client = support::try_connect(listener.endpoint()).unwrap();
        client.write_all(b"early").unwrap();
        let connection = listener.accept().unwrap();
        let mut data = [0; 5];
        read_exact(&connection, &mut data);
        assert_eq!(data, *b"early");
        connection.write_all(b"ok").unwrap();
        let mut reply = [0; 2];
        client.read_exact(&mut reply).unwrap();
        assert_eq!(reply, *b"ok");
        // The next instance already listens.
        drop(support::try_connect(listener.endpoint()).unwrap());
    });
}

/// Every synchronous outcome returns at once: a call that waited for an event
/// that never fires would outlive the bound.
#[test]
fn immediate_failure_paths() {
    within(BOUND, || {
        let parent = Parent::new();
        let listener = parent.bind();
        let (connection, mut client) = pair(&listener);
        let mut out = [0; 8];
        // Data already buffered: the read and the observer complete synchronously.
        client.write_all(b"abc").unwrap();
        assert_eq!(connection.read(&mut out), Ok(3));
        client.write_all(b"d").unwrap();
        assert_eq!(connection.await_input(), Ok(Readiness::Input));
        assert_eq!(connection.read(&mut out), Ok(1));
        assert_eq!(out[0], b'd');
        // A small write fits the buffer.
        connection.write_all(b"e").unwrap();
        client.read_exact(&mut out[..1]).unwrap();
        assert_eq!(out[0], b'e');
        // The client is gone: reads and the observer end, writes fail, at once.
        drop(client);
        assert_eq!(connection.read(&mut out), Ok(0));
        assert_eq!(connection.await_input(), Ok(Readiness::Eof));
        let failure = connection.write_all(b"f").err().unwrap();
        assert_eq!(failure.kind(), Error::PeerClosed);
        assert_eq!(failure.operation(), "write");
    });
}

/// A client's zero-length write completes a pending read with 0 bytes; the read is
/// issued again instead of reporting the end of the stream.
#[test]
fn zero_length_client_write_is_not_end_of_stream() {
    within(BOUND, || {
        let parent = Parent::new();
        let listener = parent.bind();
        let (connection, mut client) = pair(&listener);
        let connection = Arc::new(connection);
        let empty_write = |client: &std::fs::File| {
            let mut written = 1;
            let byte = [0_u8];
            // SAFETY: A synchronous handle and a valid buffer; zero bytes are written.
            let wrote = unsafe {
                WriteFile(
                    client.as_raw_handle(),
                    byte.as_ptr(),
                    0,
                    &mut written,
                    ptr::null_mut(),
                )
            };
            assert_ne!(wrote, 0);
            assert_eq!(written, 0);
        };

        let observing = Arc::clone(&connection);
        let observer = thread::spawn(move || observing.await_input());
        thread::sleep(Duration::from_millis(50));
        empty_write(&client);
        thread::sleep(Duration::from_millis(50));
        assert!(!observer.is_finished());
        client.write_all(b"x").unwrap();
        assert_eq!(observer.join().unwrap(), Ok(Readiness::Input));
        let mut byte = [0];
        assert_eq!(connection.read(&mut byte), Ok(1));
        assert_eq!(byte, *b"x");

        let reading = Arc::clone(&connection);
        let reader = thread::spawn(move || {
            let mut byte = [0];
            reading.read(&mut byte).map(|count| (count, byte))
        });
        thread::sleep(Duration::from_millis(50));
        empty_write(&client);
        thread::sleep(Duration::from_millis(50));
        assert!(!reader.is_finished());
        client.write_all(b"y").unwrap();
        assert_eq!(reader.join().unwrap(), Ok((1, *b"y")));
    });
}

/// Clients that connect and vanish at once, with no live connection left, never
/// find the name missing; the helper's own client, which retries only
/// `ERROR_PIPE_BUSY`, connects every time.
#[test]
fn name_never_lapses_during_vanished_storm() {
    const HELPER_CONNECTS: usize = 200;
    within(Duration::from_secs(120), || {
        let parent = Parent::new();
        let listener = Arc::new(parent.bind());
        let endpoint = listener.endpoint().to_owned();
        let accepted = Arc::new(AtomicUsize::new(0));
        let acceptor = {
            let listener = Arc::clone(&listener);
            let accepted = Arc::clone(&accepted);
            thread::spawn(move || {
                loop {
                    match listener.accept() {
                        // Closed at once: no connection stays alive.
                        Ok(connection) => {
                            accepted.fetch_add(1, Ordering::Relaxed);
                            drop(connection);
                        }
                        Err(failure) => return failure,
                    }
                }
            })
        };
        let stop = Arc::new(AtomicBool::new(false));
        let lapses = Arc::new(AtomicUsize::new(0));
        let storm: Vec<_> = (0..4)
            .map(|_| {
                let (endpoint, stop, lapses) =
                    (endpoint.clone(), Arc::clone(&stop), Arc::clone(&lapses));
                thread::spawn(move || {
                    let mut vanished = 0;
                    while !stop.load(Ordering::Relaxed) {
                        match support::try_connect(&endpoint) {
                            Ok(client) => {
                                drop(client);
                                vanished += 1;
                            }
                            Err(error) if support::is_busy(&error) => {}
                            Err(error)
                                if error.raw_os_error() == Some(ERROR_FILE_NOT_FOUND as i32) =>
                            {
                                lapses.fetch_add(1, Ordering::Relaxed);
                            }
                            Err(error) => panic!("connect failed: {error}"),
                        }
                        // Paced, or the helper's 50 ms retries never find the
                        // instance free.
                        thread::sleep(Duration::from_millis(1));
                    }
                    vanished
                })
            })
            .collect();
        let started = Instant::now();
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let slowest = runtime.block_on(async {
            let mut slowest = Duration::ZERO;
            for _ in 0..HELPER_CONNECTS {
                let attempt = Instant::now();
                let connected = tokio::time::timeout(
                    Duration::from_secs(30),
                    stream::connect(Path::new(&endpoint), Some(std::process::id())),
                )
                .await
                .expect("busy for 30 s");
                drop(connected.expect("the helper client failed to connect"));
                slowest = slowest.max(attempt.elapsed());
            }
            slowest
        });
        stop.store(true, Ordering::Relaxed);
        let vanished: usize = storm.into_iter().map(|t| t.join().unwrap()).sum();
        listener.close();
        assert_eq!(acceptor.join().unwrap().kind(), Error::Closed);
        assert_eq!(lapses.load(Ordering::Relaxed), 0, "the name lapsed");
        eprintln!(
            "{HELPER_CONNECTS} helper connects (slowest {slowest:?}) and {vanished} vanished \
             clients in {:?}; {} accepted",
            started.elapsed(),
            accepted.load(Ordering::Relaxed)
        );
    });
}

#[test]
fn concurrent_32_helper_clients_never_file_not_found() {
    within(Duration::from_secs(60), || {
        let parent = Parent::new();
        let listener = Arc::new(parent.bind());
        let endpoint = listener.endpoint().to_owned();
        let acceptor = {
            let listener = Arc::clone(&listener);
            thread::spawn(move || {
                let mut served = Vec::new();
                while let Ok(connection) = listener.accept() {
                    // Echoes one byte, then waits for the client to leave.
                    served.push(thread::spawn(move || {
                        let mut byte = [0];
                        read_exact(&connection, &mut byte);
                        connection.write_all(&byte).unwrap();
                        connection.await_input()
                    }));
                }
                served
                    .into_iter()
                    .map(|served| served.join().unwrap())
                    .collect::<Vec<_>>()
            })
        };
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let served = runtime.block_on(async {
            let clients: Vec<_> = (0..32_u8)
                .map(|index| {
                    let endpoint = endpoint.clone();
                    tokio::spawn(async move {
                        let mut stream = tokio::time::timeout(
                            Duration::from_secs(30),
                            stream::connect(Path::new(&endpoint), Some(std::process::id())),
                        )
                        .await
                        .expect("busy for 30 s")
                        .expect("the helper client failed to connect");
                        stream.write_all(&[index]).await.unwrap();
                        let mut byte = [0];
                        stream.read_exact(&mut byte).await.unwrap();
                        assert_eq!(byte, [index]);
                        stream
                    })
                })
                .collect();
            // All 32 stay connected until every one has its echo.
            let mut streams = Vec::new();
            for client in clients {
                streams.push(client.await.unwrap());
            }
            drop(streams);
            listener.close();
            support::join(acceptor).await
        });
        assert_eq!(served.len(), 32);
        assert!(served.iter().all(|result| *result == Ok(Readiness::Eof)));
    });
}

/// Closing races every in-flight operation; each returns, and the client sees the
/// end of the stream once the last reference drops.
#[test]
fn close_with_ops_in_flight_stress() {
    const ROUNDS: usize = 1_000;
    within(Duration::from_secs(300), || {
        let parent = Parent::new();
        let listener = parent.bind();
        let started = Instant::now();
        for round in 0..ROUNDS {
            let (connection, client) = pair(&listener);
            let connection = Arc::new(connection);
            let inbound = {
                let connection = Arc::clone(&connection);
                thread::spawn(move || {
                    let _ = connection.await_input();
                    let mut buffer = [0; 4096];
                    while matches!(connection.read(&mut buffer), Ok(count) if count > 0) {}
                })
            };
            let outbound = {
                let connection = Arc::clone(&connection);
                thread::spawn(move || {
                    let data = vec![0x5a; 256 * 1024];
                    while connection.write_all(&data).is_ok() {}
                })
            };
            let peer = thread::spawn(move || {
                let mut client = client;
                let _ = client.write_all(&[1; 512]);
                let mut buffer = vec![0; 64 * 1024];
                while matches!(client.read(&mut buffer), Ok(count) if count > 0) {}
            });
            support::spin(Duration::from_micros((round % 50) as u64 * 10));
            connection.close();
            inbound.join().unwrap();
            outbound.join().unwrap();
            drop(connection);
            peer.join().unwrap();
        }
        eprintln!("{ROUNDS} rounds in {:?}", started.elapsed());
    });
}

/// Closing races accept, with and without a client arriving; the listener's
/// security descriptor outlives every instance that accept creates.
#[test]
fn accept_close_stress() {
    const ROUNDS: usize = 10_000;
    within(Duration::from_secs(300), || {
        let parent = Parent::new();
        let started = Instant::now();
        let mut accepted = 0;
        for round in 0..ROUNDS {
            let listener = Arc::new(parent.bind());
            let acceptor = {
                let listener = Arc::clone(&listener);
                thread::spawn(move || listener.accept().map(drop))
            };
            if round % 4 == 0 {
                drop(support::try_connect(listener.endpoint()));
            }
            if round % 2 == 1 {
                thread::yield_now();
            }
            listener.close();
            match acceptor.join().unwrap() {
                Ok(()) => accepted += 1,
                Err(failure) => assert_eq!(failure.kind(), Error::Closed),
            }
        }
        eprintln!(
            "{ROUNDS} rounds in {:?}; {accepted} accepted before close",
            started.elapsed()
        );
    });
}

/// Writing while the peer reads slowly and an observer pends never deadlocks, even
/// for 32 MiB.
#[test]
fn large_write_with_pending_observer() {
    within(Duration::from_secs(60), || {
        let parent = Parent::new();
        let listener = parent.bind();
        let (connection, mut client) = pair(&listener);
        let connection = Arc::new(connection);
        let observing = Arc::clone(&connection);
        let observer = thread::spawn(move || observing.await_input());
        let size = 32 * MIB;
        let reader = thread::spawn(move || {
            let mut data = vec![0; size];
            client.read_exact(&mut data).unwrap();
            drop(client);
            data
        });
        let payload = support::pattern(size);
        connection.write_all(&payload).unwrap();
        assert!(reader.join().unwrap() == payload);
        assert_eq!(observer.join().unwrap(), Ok(Readiness::Eof));
    });
}
