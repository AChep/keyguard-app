//! The Unix endpoint: its private run directory, its descriptors and the peer
//! snapshot.
#![cfg(unix)]

use std::{
    collections::BTreeSet,
    fs,
    io::{Read, Write},
    os::{
        fd::RawFd,
        unix::{
            fs::{FileTypeExt, MetadataExt, symlink},
            net::{UnixListener, UnixStream},
        },
    },
    path::{Path, PathBuf},
    process::Command,
    sync::{Mutex, MutexGuard, PoisonError},
};

use keyguard_agent_ipc_core::{Connection, Error, Failure, Listener, Location, Readiness};

/// The longest socket path `sun_path` holds with its NUL.
#[cfg(target_os = "macos")]
const MAX_SOCKET_PATH: usize = 103;
#[cfg(not(target_os = "macos"))]
const MAX_SOCKET_PATH: usize = 107;

/// `/` + the prefix `p` + 12 hex + `/i`, after the parent.
const RANDOM_NAME_OVERHEAD: usize = 1 + 1 + 12 + 2;

/// The descriptors the probes look at.
const DESCRIPTOR_PROBE_LIMIT: RawFd = 1024;

const DESCRIPTOR_LIMIT_CHILD: &str = "KEYGUARD_AGENT_IPC_DESCRIPTOR_LIMIT_CHILD";

static SERIAL: Mutex<()> = Mutex::new(());

/// The tests count descriptors and change process-wide state, so they run one at
/// a time.
fn serial() -> MutexGuard<'static, ()> {
    SERIAL.lock().unwrap_or_else(PoisonError::into_inner)
}

fn text(path: &Path) -> &str {
    path.to_str().unwrap()
}

fn bind(parent: &Path, prefix: &str, exact: Option<&str>) -> Result<Listener, Failure> {
    Listener::bind(Location::Unix {
        parent: text(parent),
        prefix,
        exact,
    })
}

fn assert_name_in_use(result: Result<Listener, Failure>) {
    let failure = result.err().expect("the bind must fail");
    assert_eq!(failure.kind(), Error::Permission, "{failure}");
    assert_eq!(failure.reason(), "name_in_use");
}

fn effective_uid() -> u32 {
    // SAFETY: geteuid has no arguments and cannot fail.
    unsafe { libc::geteuid() }
}

fn entries(directory: &Path) -> Vec<String> {
    let mut names = fs::read_dir(directory)
        .unwrap()
        .map(|entry| entry.unwrap().file_name().into_string().unwrap())
        .collect::<Vec<_>>();
    names.sort();
    names
}

fn open_descriptors() -> BTreeSet<RawFd> {
    (0..DESCRIPTOR_PROBE_LIMIT)
        .filter(|fd| {
            // SAFETY: F_GETFD only reads a descriptor's flags; a closed one is EBADF.
            unsafe { libc::fcntl(*fd, libc::F_GETFD) >= 0 }
        })
        .collect()
}

fn is_close_on_exec(fd: RawFd) -> bool {
    // SAFETY: F_GETFD takes no argument.
    let flags = unsafe { libc::fcntl(fd, libc::F_GETFD) };
    flags >= 0 && flags & libc::FD_CLOEXEC != 0
}

fn file_type(fd: RawFd) -> libc::mode_t {
    let mut status = std::mem::MaybeUninit::<libc::stat>::uninit();
    // SAFETY: status is writable storage for one stat.
    assert_eq!(unsafe { libc::fstat(fd, status.as_mut_ptr()) }, 0);
    // SAFETY: a successful fstat initialized the whole structure.
    let status = unsafe { status.assume_init() };
    status.st_mode & libc::S_IFMT
}

fn read_exact(connection: &Connection, buffer: &mut [u8]) {
    let mut filled = 0;
    while filled < buffer.len() {
        let count = connection.read(&mut buffer[filled..]).unwrap();
        assert_ne!(count, 0, "end of stream");
        filled += count;
    }
}

/// Runs `test` again in a child process with `variable` set, for a test that
/// changes process-wide state.
fn run_child(test: &str, variable: &str) {
    let output = Command::new(std::env::current_exe().unwrap())
        .args(["--exact", test, "--nocapture", "--test-threads=1"])
        .env(variable, "1")
        .output()
        .unwrap();
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(
        output.status.success() && stdout.contains("1 passed"),
        "the child failed: {:?}\nstdout: {stdout}\nstderr: {}",
        output.status,
        String::from_utf8_lossy(&output.stderr)
    );
}

/// Binds and closes a random name once, so a descriptor the random source may keep
/// open is already counted.
fn warm_up(parent: &Path) {
    bind(parent, "kg-", None).unwrap().close();
}

/// Creates `<base>/dd…d`, a directory whose path is `length` bytes long.
fn directory_of_length(base: &Path, length: usize) -> PathBuf {
    let base_length = text(base).len();
    assert!(
        base_length + 2 <= length,
        "the temporary directory is too long for this test: {}",
        base.display()
    );
    let directory = base.join("d".repeat(length - base_length - 1));
    fs::create_dir(&directory).unwrap();
    assert_eq!(text(&directory).len(), length);
    directory
}

#[test]
fn run_directory_is_0700_and_socket_0600_whatever_the_umask() {
    let _serial = serial();
    let parent = tempfile::tempdir().unwrap();
    // SAFETY: umask swaps this process's mask; the other tests wait on the lock.
    let previous = unsafe { libc::umask(0) };
    let listener = bind(parent.path(), "kg-", None);
    // SAFETY: as above, restoring the mask.
    unsafe { libc::umask(previous) };
    let listener = listener.unwrap();
    let endpoint = Path::new(listener.endpoint());

    let directory = fs::symlink_metadata(endpoint.parent().unwrap()).unwrap();
    assert!(directory.file_type().is_dir());
    assert_eq!(directory.mode() & 0o777, 0o700);
    assert_eq!(directory.uid(), effective_uid());

    let socket = fs::symlink_metadata(endpoint).unwrap();
    assert!(socket.file_type().is_socket());
    assert_eq!(socket.mode() & 0o777, 0o600);
    assert_eq!(socket.uid(), effective_uid());
    listener.close();
}

#[test]
fn exact_name_taken_by_a_link_a_file_or_a_directory_is_permission() {
    let _serial = serial();
    let parent = tempfile::tempdir().unwrap();
    let target = parent.path().join("target");
    fs::create_dir(&target).unwrap();
    symlink(&target, parent.path().join("link")).unwrap();
    symlink(
        parent.path().join("missing"),
        parent.path().join("dangling"),
    )
    .unwrap();
    fs::write(parent.path().join("file"), b"keep").unwrap();
    fs::create_dir(parent.path().join("directory")).unwrap();

    for name in ["link", "dangling", "file", "directory"] {
        assert_name_in_use(bind(parent.path(), "", Some(name)));
    }
    assert!(entries(&target).is_empty());
    let link = fs::symlink_metadata(parent.path().join("link")).unwrap();
    assert!(link.file_type().is_symlink());
    assert!(fs::symlink_metadata(parent.path().join("missing")).is_err());
    assert_eq!(fs::read(parent.path().join("file")).unwrap(), b"keep");
    assert!(entries(&parent.path().join("directory")).is_empty());
    assert_eq!(
        entries(parent.path()),
        ["dangling", "directory", "file", "link", "target"]
    );
}

#[test]
fn a_live_exact_name_is_permission_until_closed() {
    let _serial = serial();
    let parent = tempfile::tempdir().unwrap();
    let first = bind(parent.path(), "", Some("ipc")).unwrap();
    assert_name_in_use(bind(parent.path(), "", Some("ipc")));
    let _client = UnixStream::connect(first.endpoint()).unwrap();
    assert_eq!(first.accept().unwrap().peer_pid(), Ok(std::process::id()));
    first.close();
    let second = bind(parent.path(), "", Some("ipc")).unwrap();
    assert_eq!(second.endpoint(), first.endpoint());
    second.close();
}

#[test]
fn too_long_paths_are_invalid_and_create_nothing() {
    let _serial = serial();
    let base = tempfile::tempdir().unwrap();
    let fits = directory_of_length(base.path(), MAX_SOCKET_PATH - RANDOM_NAME_OVERHEAD);
    let over = directory_of_length(base.path(), MAX_SOCKET_PATH - RANDOM_NAME_OVERHEAD + 1);
    let longest = "e".repeat(13);
    let longer = "e".repeat(14);

    for (parent, prefix, exact) in [
        (&over, "p", None),
        (&fits, "pp", None),
        (&fits, "", Some(longer.as_str())),
    ] {
        let failure = bind(parent, prefix, exact).err().unwrap();
        assert_eq!(failure.kind(), Error::InvalidArgument, "{failure}");
        assert_eq!(failure.reason(), "path_too_long");
        assert!(entries(parent).is_empty());
    }

    // The longest paths bind and connect.
    for exact in [None, Some(longest.as_str())] {
        let prefix = if exact.is_some() { "" } else { "p" };
        let listener = bind(&fits, prefix, exact).unwrap();
        assert_eq!(listener.endpoint().len(), MAX_SOCKET_PATH);
        let _client = UnixStream::connect(listener.endpoint()).unwrap();
        assert!(listener.accept().is_ok());
        listener.close();
    }
    assert!(entries(&fits).is_empty());
}

#[test]
fn invalid_arguments_and_unusable_parents_create_nothing() {
    let _serial = serial();
    let parent = tempfile::tempdir().unwrap();
    for (prefix, exact, reason) in [
        ("a/b", None, "invalid_prefix"),
        ("..", None, "invalid_prefix"),
        ("", Some(".."), "invalid_name"),
        ("", Some("a/b"), "invalid_name"),
    ] {
        let failure = bind(parent.path(), prefix, exact).err().unwrap();
        assert_eq!(failure.kind(), Error::InvalidArgument);
        assert_eq!(failure.reason(), reason);
    }
    let relative = Listener::bind(Location::Unix {
        parent: "relative",
        prefix: "",
        exact: None,
    });
    assert_eq!(relative.err().unwrap().reason(), "invalid_parent");

    let failure = bind(&parent.path().join("missing"), "kg-", None)
        .err()
        .unwrap();
    assert_eq!(failure.kind(), Error::Io);
    assert_eq!(failure.os_code(), Some(libc::ENOENT));
    let file = parent.path().join("file");
    fs::write(&file, b"").unwrap();
    let failure = bind(&file, "kg-", None).err().unwrap();
    assert_eq!(failure.kind(), Error::Io);
    assert_eq!(failure.os_code(), Some(libc::ENOTDIR));
    assert_eq!(entries(parent.path()), ["file"]);
}

#[test]
fn bind_lands_in_the_run_directory() {
    let _serial = serial();
    let parent = tempfile::tempdir().unwrap();
    let listener = bind(parent.path(), "kg-", None).unwrap();
    let endpoint = Path::new(listener.endpoint());
    assert_eq!(endpoint.file_name().unwrap(), "i");
    let run = endpoint.parent().unwrap();
    assert_eq!(run.parent().unwrap(), parent.path());
    let name = run.file_name().unwrap().to_str().unwrap();
    let random = name.strip_prefix("kg-").unwrap();
    assert_eq!(random.len(), 12);
    assert!(random.bytes().all(|byte| byte.is_ascii_hexdigit()));
    assert_eq!(entries(run), ["i"]);
    assert_eq!(entries(parent.path()), [name]);

    let mut client = UnixStream::connect(endpoint).unwrap();
    let connection = listener.accept().unwrap();
    client.write_all(b"ping").unwrap();
    let mut buffer = [0; 4];
    read_exact(&connection, &mut buffer);
    assert_eq!(&buffer, b"ping");
    connection.write_all(b"pong").unwrap();
    client.read_exact(&mut buffer).unwrap();
    assert_eq!(&buffer, b"pong");
    listener.close();

    // A parent behind a symbolic link is followed: macOS `/tmp` is one.
    let target = parent.path().join("target");
    fs::create_dir(&target).unwrap();
    let alias = parent.path().join("alias");
    symlink(&target, &alias).unwrap();
    let listener = bind(&alias, "kg-", None).unwrap();
    assert!(listener.endpoint().starts_with(text(&alias)));
    assert_eq!(entries(&target).len(), 1);
    let _client = UnixStream::connect(listener.endpoint()).unwrap();
    assert!(listener.accept().is_ok());
    listener.close();
    assert!(entries(&target).is_empty());
}

#[test]
fn close_removes_the_socket_and_the_run_directory() {
    let _serial = serial();
    let parent = tempfile::tempdir().unwrap();
    let listener = bind(parent.path(), "kg-", None).unwrap();
    let endpoint = PathBuf::from(listener.endpoint());
    listener.close();
    assert!(fs::symlink_metadata(&endpoint).is_err());
    assert!(entries(parent.path()).is_empty());
    assert!(UnixStream::connect(&endpoint).is_err());
    listener.close();
    assert_eq!(listener.accept().err().unwrap().kind(), Error::Closed);

    // A listener dropped without a close removes its endpoint too.
    drop(bind(parent.path(), "kg-", None).unwrap());
    assert!(entries(parent.path()).is_empty());
}

#[test]
fn close_leaves_a_swapped_socket_and_directory() {
    let _serial = serial();
    let parent = tempfile::tempdir().unwrap();

    // The run directory was replaced. It is renamed away first, so the new one
    // cannot reuse its inode.
    let listener = bind(parent.path(), "", Some("run")).unwrap();
    let run = parent.path().join("run");
    let moved = parent.path().join("moved");
    fs::rename(&run, &moved).unwrap();
    fs::create_dir(&run).unwrap();
    let replacement = UnixListener::bind(run.join("i")).unwrap();
    listener.close();
    assert!(
        fs::symlink_metadata(run.join("i"))
            .unwrap()
            .file_type()
            .is_socket()
    );
    assert!(UnixStream::connect(run.join("i")).is_ok());
    // The listener's own socket went, through the directory it holds open.
    assert!(entries(&moved).is_empty());
    drop(replacement);

    // Only the socket was replaced. The new one is bound beside it and renamed
    // over it, so it cannot reuse its inode.
    let listener = bind(parent.path(), "", Some("run2")).unwrap();
    let run = parent.path().join("run2");
    let replacement = UnixListener::bind(run.join("next")).unwrap();
    fs::rename(run.join("next"), run.join("i")).unwrap();
    listener.close();
    assert_eq!(entries(&run), ["i"]);
    assert!(UnixStream::connect(run.join("i")).is_ok());
    drop(replacement);
}

#[test]
fn fds_are_cloexec() {
    let _serial = serial();
    let parent = tempfile::tempdir().unwrap();
    warm_up(parent.path());
    let before = open_descriptors();
    let listener = bind(parent.path(), "kg-", None).unwrap();
    let listening = &open_descriptors() - &before;
    // The socket, both ends of the wake pipe, `parentfd` and `dirfd`.
    let mut kinds = listening
        .iter()
        .map(|fd| file_type(*fd))
        .collect::<Vec<_>>();
    kinds.sort_unstable();
    let mut expected = vec![
        libc::S_IFSOCK,
        libc::S_IFIFO,
        libc::S_IFIFO,
        libc::S_IFDIR,
        libc::S_IFDIR,
    ];
    expected.sort_unstable();
    assert_eq!(kinds, expected);
    for fd in &listening {
        assert!(is_close_on_exec(*fd), "listener fd {fd}");
    }

    let client = UnixStream::connect(listener.endpoint()).unwrap();
    let before_accept = open_descriptors();
    let connection = listener.accept().unwrap();
    // Only the accepted socket: a Linux pidfd closes within accept.
    let accepted = &open_descriptors() - &before_accept;
    assert_eq!(accepted.len(), 1);
    for fd in &accepted {
        assert_eq!(file_type(*fd), libc::S_IFSOCK);
        assert!(is_close_on_exec(*fd), "accepted fd {fd}");
    }

    drop(connection);
    drop(client);
    listener.close();
    drop(listener);
    assert_eq!(open_descriptors(), before);
}

#[test]
fn a_thousand_connections_leave_the_descriptor_count_steady() {
    let _serial = serial();
    let parent = tempfile::tempdir().unwrap();
    let listener = bind(parent.path(), "kg-", None).unwrap();
    let before = open_descriptors();
    for round in 0..1_000_u32 {
        let mut client = UnixStream::connect(listener.endpoint()).unwrap();
        let connection = listener.accept().unwrap();
        client.write_all(&round.to_le_bytes()).unwrap();
        let mut buffer = [0; 4];
        read_exact(&connection, &mut buffer);
        assert_eq!(u32::from_le_bytes(buffer), round);
        if round % 2 == 0 {
            connection.close();
            drop(client);
        } else {
            drop(client);
            assert_eq!(connection.await_input(), Ok(Readiness::Eof));
        }
        drop(connection);
    }
    assert_eq!(open_descriptors(), before);
    listener.close();
}

#[test]
fn listener_cycles_leave_no_descriptors_or_entries() {
    let _serial = serial();
    let parent = tempfile::tempdir().unwrap();
    warm_up(parent.path());
    let before = open_descriptors();
    for _ in 0..100 {
        let listener = bind(parent.path(), "kg-", None).unwrap();
        let _client = UnixStream::connect(listener.endpoint()).unwrap();
        assert!(listener.accept().is_ok());
        listener.close();
    }
    assert_eq!(open_descriptors(), before);
    assert!(entries(parent.path()).is_empty());
}

#[test]
fn own_process_snapshot() {
    let _serial = serial();
    let parent = tempfile::tempdir().unwrap();
    let listener = bind(parent.path(), "kg-", None).unwrap();
    let _client = UnixStream::connect(listener.endpoint()).unwrap();
    let connection = listener.accept().unwrap();
    assert_eq!(connection.peer_pid(), Ok(std::process::id()));
    listener.close();
}

/// The values `unix/peer.rs` defines for itself.
#[cfg(target_os = "macos")]
#[test]
fn peer_constants_match_xnu() {
    assert_eq!(libc::SOL_LOCAL, 0);
    assert_eq!(libc::LOCAL_PEEREPID, 3);
    assert_eq!(size_of::<libc::pid_t>(), 4);
}

/// The values `unix/peer.rs` defines or relies on.
#[cfg(target_os = "linux")]
#[test]
fn peer_constants_match_linux() {
    assert_eq!(libc::SO_PEERCRED, 17);
    assert_eq!(libc::SO_PEERPIDFD, 77);
    assert_eq!(size_of::<libc::ucred>(), 12);
}

/// A client that connected, exited and was reaped before accept is not
/// verifiable: its pid may belong to another process by then.
#[cfg(target_os = "linux")]
#[test]
fn peer_that_exited_before_accept_is_permission() {
    let _serial = serial();
    if !kernel_has_peer_pidfd() {
        eprintln!("skipped: this kernel has no SO_PEERPIDFD");
        return;
    }
    let parent = tempfile::tempdir().unwrap();
    let listener = bind(parent.path(), "kg-", None).unwrap();
    let child = connect_from_exited_child(listener.endpoint());
    assert_ne!(child, std::process::id());
    let connection = listener.accept().unwrap();
    let failure = connection.peer_pid().unwrap_err();
    assert_eq!(failure.kind(), Error::Permission);
    assert_eq!(failure.operation(), "peer_pid");
    assert_eq!(failure.reason(), "peer_exited");

    // It stays the connection's failure: the next, live peer is verified.
    let _client = UnixStream::connect(listener.endpoint()).unwrap();
    assert_eq!(
        listener.accept().unwrap().peer_pid(),
        Ok(std::process::id())
    );
    listener.close();
}

/// Whether `getsockopt(SO_PEERPIDFD)` works: Linux 6.5 or later.
#[cfg(target_os = "linux")]
fn kernel_has_peer_pidfd() -> bool {
    use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};

    let (socket, _peer) = UnixStream::pair().unwrap();
    let mut pidfd: libc::c_int = -1;
    let mut length = size_of::<libc::c_int>() as libc::socklen_t;
    // SAFETY: socket is live, and pidfd is writable storage of `length` bytes.
    let result = unsafe {
        libc::getsockopt(
            socket.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_PEERPIDFD,
            (&raw mut pidfd).cast(),
            &mut length,
        )
    };
    if result != 0 {
        return false;
    }
    // SAFETY: a successful SO_PEERPIDFD installed a new descriptor nothing owns.
    drop(unsafe { OwnedFd::from_raw_fd(pidfd) });
    true
}

/// Forks a child that connects to `path` and exits, reaps it, and returns its
/// pid. Its connection waits in the listener's queue.
#[cfg(target_os = "linux")]
fn connect_from_exited_child(path: &str) -> u32 {
    // SAFETY: all-zero bytes are a valid sockaddr_un.
    let mut address: libc::sockaddr_un = unsafe { std::mem::zeroed() };
    address.sun_family = libc::AF_UNIX as libc::sa_family_t;
    for (slot, byte) in address.sun_path.iter_mut().zip(path.as_bytes()) {
        *slot = libc::c_char::from_ne_bytes([*byte]);
    }
    let length = std::mem::offset_of!(libc::sockaddr_un, sun_path) + path.len() + 1;
    let length = length as libc::socklen_t;
    // SAFETY: the child calls only async-signal-safe functions (socket, connect
    // and _exit), so no lock another thread held at the fork can block it.
    let pid = unsafe { libc::fork() };
    assert!(pid >= 0, "fork failed");
    if pid == 0 {
        // SAFETY: as above; address is initialized and length is its size.
        unsafe {
            let socket = libc::socket(libc::AF_UNIX, libc::SOCK_STREAM, 0);
            let connected = libc::connect(socket, (&raw const address).cast(), length);
            libc::_exit(i32::from(socket < 0 || connected != 0));
        }
    }
    let mut status = 0;
    // SAFETY: pid is this process's child and status is writable.
    assert_eq!(unsafe { libc::waitpid(pid, &mut status, 0) }, pid);
    assert!(
        libc::WIFEXITED(status) && libc::WEXITSTATUS(status) == 0,
        "the child could not connect"
    );
    u32::try_from(pid).unwrap()
}

#[test]
fn low_descriptor_limit_is_io_without_crashing() {
    if std::env::var_os(DESCRIPTOR_LIMIT_CHILD).is_none() {
        let _serial = serial();
        run_child(
            "low_descriptor_limit_is_io_without_crashing",
            DESCRIPTOR_LIMIT_CHILD,
        );
        return;
    }
    let parent = tempfile::tempdir().unwrap();
    let ready = bind(parent.path(), "", Some("ready")).unwrap();
    let _queued = UnixStream::connect(ready.endpoint()).unwrap();
    let run = parent.path().join("ipc");

    let limit = libc::rlimit {
        rlim_cur: 64,
        rlim_max: 64,
    };
    // SAFETY: this child process lowers only its own limit; limit is a live rlimit.
    assert_eq!(unsafe { libc::setrlimit(libc::RLIMIT_NOFILE, &limit) }, 0);
    let mut spare = exhaust_descriptors();
    assert!(spare.len() >= 8, "only {} spare descriptors", spare.len());

    // Each descriptor bind takes runs out in turn: the parent, the run directory,
    // the socket, then the two ends of the wake pipe. Nothing is left behind.
    for free in 0..5 {
        let failure = bind(parent.path(), "", Some("ipc")).err().unwrap();
        assert_eq!(failure.kind(), Error::Io, "{free} free: {failure}");
        assert_eq!(
            failure.os_code(),
            Some(libc::EMFILE),
            "{free} free: {failure}"
        );
        assert!(
            fs::symlink_metadata(&run).is_err(),
            "{free} free: left behind"
        );
        drop(spare.pop());
    }
    let listener = bind(parent.path(), "", Some("ipc")).unwrap();
    listener.close();
    drop(listener);
    assert!(fs::symlink_metadata(&run).is_err());

    // Accept reports the exhaustion and serves the next client once descriptors
    // are free. Linux keeps the queued client; XNU drops it, so another one comes.
    spare.extend(exhaust_descriptors());
    let failure = ready.accept().err().unwrap();
    assert_eq!(failure.kind(), Error::Io, "{failure}");
    assert_eq!(failure.os_code(), Some(libc::EMFILE));
    // The new client, the accepted socket, and the pidfd Linux opens for the peer.
    spare.truncate(spare.len() - 3);
    let _client = UnixStream::connect(ready.endpoint()).unwrap();
    let connection = ready.accept().unwrap();
    assert_eq!(connection.peer_pid(), Ok(std::process::id()));
    drop(spare);
    ready.close();
}

/// Opens `/dev/null` until the descriptor limit is reached.
fn exhaust_descriptors() -> Vec<fs::File> {
    let mut files = Vec::new();
    loop {
        match fs::File::open("/dev/null") {
            Ok(file) => files.push(file),
            Err(error) => {
                assert_eq!(error.raw_os_error(), Some(libc::EMFILE), "{error}");
                return files;
            }
        }
    }
}
