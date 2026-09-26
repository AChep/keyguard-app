use crate::{Error, Events, Failure, Result, protocol};
use std::fs::{self, DirBuilder, File, OpenOptions};
use std::io::{self, Read, Write};
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};
use std::os::unix::fs::{DirBuilderExt, MetadataExt, OpenOptionsExt, PermissionsExt};
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

const MAX_CLIENTS: usize = 4;
const CLIENT_TIMEOUT: Duration = Duration::from_secs(1);

fn uid() -> u32 {
    // SAFETY: geteuid takes no arguments and has no failure or lifetime requirements.
    unsafe { libc::geteuid() }
}

#[derive(Debug)]
pub(crate) struct Directory;

impl Directory {
    pub(crate) fn try_lock(&self, path: &Path) -> Result<Option<Lease>> {
        try_lock(path).map_err(|error| error.context("acquire_instance_lock"))
    }

    pub(crate) fn read_private(&self, path: &Path, max: usize) -> Result<Vec<u8>> {
        read_private(path, max).map_err(|error| error.context("read_endpoint_metadata"))
    }

    pub(crate) fn publish_private(&self, path: &Path, data: &[u8]) -> Result<()> {
        publish_private(path, data).map_err(|error| error.context("publish_endpoint_metadata"))
    }
}

pub(crate) fn prepare_directory(path: &Path) -> Result<Directory> {
    let mut ancestor = path;
    loop {
        match fs::symlink_metadata(ancestor) {
            Ok(_) => break,
            Err(error) if error.kind() == io::ErrorKind::NotFound => {
                ancestor = ancestor.parent().ok_or(Error::InvalidArgument)?;
            }
            Err(error) => return Err(error.into()),
        }
    }
    validate_ancestors(ancestor)?;
    create_private_directory(path)?;
    Ok(Directory)
}

fn create_private_directory(path: &Path) -> Result<()> {
    DirBuilder::new().recursive(true).mode(0o700).create(path)?;
    // A missing intermediate may have appeared after the nearest existing
    // ancestor was checked, so validate the complete established chain.
    validate_ancestors(path)?;
    let metadata = fs::symlink_metadata(path)?;
    if !metadata.is_dir() || metadata.uid() != uid() || metadata.mode() & 0o077 != 0 {
        return Err(Error::Permission.into());
    }
    Ok(())
}

fn validate_ancestors(path: &Path) -> Result<()> {
    validate_aliases(path, 0)?;
    // Resolve established system aliases such as /tmp and /var on macOS. Every
    // physical ancestor must prevent another user from replacing its children;
    // a sticky shared temp directory is safe for our user-owned private child.
    let resolved = path.canonicalize()?;
    for ancestor in resolved.ancestors() {
        let metadata = fs::metadata(ancestor)?;
        if !metadata.is_dir()
            || (metadata.uid() != 0 && metadata.uid() != uid())
            || (metadata.mode() & 0o022 != 0 && metadata.mode() & 0o1000 == 0)
        {
            return Err(Error::Permission.into());
        }
    }
    Ok(())
}

fn validate_aliases(path: &Path, depth: usize) -> Result<()> {
    if depth > 40 {
        return Err(Error::Permission.into());
    }
    for ancestor in path.ancestors() {
        let metadata = fs::symlink_metadata(ancestor)?;
        if metadata.uid() != 0 && metadata.uid() != uid() {
            return Err(Error::Permission.into());
        }
        if metadata.file_type().is_symlink() {
            // Checking only canonical ancestors would hide a symlink owned by
            // another user, who could redirect later launches to another lock.
            let target = fs::read_link(ancestor)?;
            let target = if target.is_absolute() {
                target
            } else {
                ancestor.parent().ok_or(Error::Permission)?.join(target)
            };
            validate_aliases(&target, depth + 1)?;
        } else if !metadata.is_dir()
            || (metadata.mode() & 0o022 != 0 && metadata.mode() & 0o1000 == 0)
        {
            return Err(Error::Permission.into());
        }
    }
    Ok(())
}

fn validate_file_metadata(metadata: &fs::Metadata) -> Result<()> {
    if !metadata.is_file() || metadata.uid() != uid() || metadata.mode() & 0o077 != 0 {
        return Err(Error::Permission.into());
    }
    Ok(())
}

fn validate_file(file: &File) -> Result<()> {
    let metadata = file.metadata()?;
    validate_file_metadata(&metadata)?;
    if metadata.nlink() != 1 {
        return Err(Error::Permission.into());
    }
    Ok(())
}

/// The descriptor owns the kernel lock. Its pathname must never be removed.
pub(crate) struct Lease {
    _file: File,
}

fn try_lock(path: &Path) -> Result<Option<Lease>> {
    let file = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK)
        .open(path)?;
    validate_file(&file)?;
    loop {
        // SAFETY: file holds a valid descriptor throughout this call.
        if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } == 0 {
            return Ok(Some(Lease { _file: file }));
        }
        let error = io::Error::last_os_error();
        match error.kind() {
            io::ErrorKind::WouldBlock => return Ok(None),
            io::ErrorKind::Interrupted => continue,
            _ => return Err(error.into()),
        }
    }
}

fn read_private(path: &Path, max: usize) -> Result<Vec<u8>> {
    let file = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK)
        .open(path)?;
    read_private_file(file, max)
}

fn read_private_file(file: File, max: usize) -> Result<Vec<u8>> {
    let metadata = file.metadata()?;
    validate_file_metadata(&metadata)?;
    match metadata.nlink() {
        // Recovery can remove or replace the endpoint after open. Retry through
        // the same bounded acquisition loop used when open finds no endpoint.
        0 => return Err(io::Error::from(io::ErrorKind::NotFound).into()),
        1 => {}
        _ => return Err(Error::Permission.into()),
    }
    let mut data = Vec::with_capacity(max.min(1024));
    file.take(max as u64 + 1).read_to_end(&mut data)?;
    if data.len() > max {
        return Err(Error::Protocol.into());
    }
    Ok(data)
}

fn publish_private(path: &Path, data: &[u8]) -> Result<()> {
    let mut random = [0; 8];
    getrandom::fill(&mut random).map_err(|_| Error::Internal)?;
    let staged = path.with_extension(format!("stage-{}", protocol::hex(&random)));
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC)
        .open(&staged)?;
    let result = (|| {
        file.write_all(data)?;
        fs::rename(&staged, path)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(staged);
    }
    result
}

fn endpoint_directory_name(token: &[u8; protocol::TOKEN_LEN]) -> String {
    format!("kgi-{}", protocol::hex(&token[..12]))
}

pub(crate) fn cleanup_endpoint(endpoint: &str, token: &[u8; protocol::TOKEN_LEN]) -> Result<()> {
    let path = Path::new(endpoint);
    let parent = path.parent().ok_or(Error::Protocol)?;
    if !path.is_absolute()
        || path.file_name().and_then(|s| s.to_str()) != Some("s")
        || parent.file_name().and_then(|s| s.to_str()) != Some(&endpoint_directory_name(token))
    {
        return Err(Error::Protocol.into());
    }
    let metadata = match fs::symlink_metadata(parent) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error.into()),
    };
    if !metadata.is_dir() || metadata.uid() != uid() || metadata.mode() & 0o077 != 0 {
        return Err(Error::Permission.into());
    }
    match fs::remove_file(path) {
        Ok(()) => {}
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(error) => return Err(error.into()),
    }
    match fs::remove_dir(parent) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error.into()),
    }
}

/// Owns only the private endpoint directory created by this run.
struct EndpointDirectory {
    endpoint: String,
    path: PathBuf,
}

impl EndpointDirectory {
    fn create(runtime_dir: &Path, token: &[u8; protocol::TOKEN_LEN]) -> Result<Self> {
        validate_ancestors(runtime_dir)
            .map_err(|error| error.context("validate_runtime_directory"))?;
        let path = runtime_dir.join(endpoint_directory_name(token));
        let socket_path = path.join("s");
        let endpoint = socket_path
            .to_str()
            .ok_or(Error::InvalidArgument)?
            .to_owned();
        validate_socket_path(&endpoint)
            .map_err(|error| error.context("validate_activation_socket_path"))?;
        DirBuilder::new()
            .mode(0o700)
            .create(&path)
            .map_err(|error| Failure::from(error).context("create_endpoint_directory"))?;
        Ok(Self { endpoint, path })
    }
}

impl Drop for EndpointDirectory {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.endpoint);
        let _ = fs::remove_dir(&self.path);
    }
}

pub(crate) struct Server {
    directory: EndpointDirectory,
    wake: UnixStream,
    worker: Option<JoinHandle<()>>,
}

impl Server {
    pub(crate) fn start(
        runtime_dir: &Path,
        token: [u8; protocol::TOKEN_LEN],
        events: Arc<Events>,
    ) -> Result<Self> {
        let directory = EndpointDirectory::create(runtime_dir, &token)?;
        let listener = UnixListener::bind(&directory.endpoint)
            .map_err(|error| Failure::from(error).context("bind_activation_socket"))?;
        fs::set_permissions(&directory.endpoint, fs::Permissions::from_mode(0o600))
            .map_err(|error| Failure::from(error).context("protect_activation_socket"))?;
        listener
            .set_nonblocking(true)
            .map_err(|error| Failure::from(error).context("configure_activation_listener"))?;
        let (wake, wake_receiver) = UnixStream::pair()
            .map_err(|error| Failure::from(error).context("create_activation_wake_socket"))?;
        configure_stream(&wake)?;
        configure_stream(&wake_receiver)?;
        let worker = thread::Builder::new()
            .name("instance-activation".into())
            .spawn(move || {
                let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    serve(listener, wake_receiver, &token, &events)
                }));
                match result {
                    Ok(Ok(())) => {}
                    Ok(Err(error)) => events.fail(error),
                    Err(_) => events.fail(Error::Internal),
                }
            })
            .map_err(|error| Failure::from(error).context("start_activation_worker"))?;
        Ok(Self {
            directory,
            wake,
            worker: Some(worker),
        })
    }

    pub(crate) fn endpoint(&self) -> &str {
        &self.directory.endpoint
    }

    pub(crate) fn stop(&self) -> Result<()> {
        if self.worker.is_none() {
            return Ok(());
        }
        loop {
            match (&self.wake).write(&[1]) {
                Ok(_) => return Ok(()),
                Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
                Err(error)
                    if matches!(
                        error.kind(),
                        io::ErrorKind::WouldBlock | io::ErrorKind::BrokenPipe
                    ) =>
                {
                    return Ok(());
                }
                Err(error) => return Err(Failure::from(error).context("wake_activation_worker")),
            }
        }
    }

    pub(crate) fn join(&mut self) -> Result<()> {
        if let Some(worker) = self.worker.take() {
            worker.join().map_err(|_| Error::Internal)?;
        }
        Ok(())
    }
}

impl Drop for Server {
    fn drop(&mut self) {
        let _ = self.stop();
        let _ = self.join();
        // The endpoint directory is dropped only after the worker has stopped.
    }
}

struct Client {
    stream: UnixStream,
    frame: [u8; protocol::REQUEST_LEN],
    received: usize,
    acknowledged: usize,
    deadline: Instant,
}

impl Client {
    fn progress(&mut self, token: &[u8; protocol::TOKEN_LEN], events: &Events) -> io::Result<bool> {
        while self.received < self.frame.len() {
            match self.stream.read(&mut self.frame[self.received..]) {
                Ok(0) => return Ok(false),
                Ok(count) => self.received += count,
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => return Ok(true),
                Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
                Err(error) => return Err(error),
            }
            if self.received == self.frame.len()
                && (!protocol::valid_request(&self.frame, token) || !events.activate())
            {
                return Ok(false);
            }
        }
        while self.acknowledged < protocol::ACK_LEN {
            match self.stream.write(&protocol::ACK[self.acknowledged..]) {
                Ok(0) => return Ok(false),
                Ok(count) => self.acknowledged += count,
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => return Ok(true),
                Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
                Err(error) => return Err(error),
            }
        }
        Ok(false)
    }
}

fn serve(
    listener: UnixListener,
    wake: UnixStream,
    token: &[u8; protocol::TOKEN_LEN],
    events: &Events,
) -> Result<()> {
    let mut clients: Vec<Client> = Vec::with_capacity(MAX_CLIENTS);
    let mut descriptors = Vec::with_capacity(MAX_CLIENTS + 2);
    loop {
        descriptors.clear();
        descriptors.push(poll_fd(wake.as_raw_fd(), libc::POLLIN));
        descriptors.push(poll_fd(
            if clients.len() < MAX_CLIENTS {
                listener.as_raw_fd()
            } else {
                -1
            },
            libc::POLLIN,
        ));
        for client in &clients {
            let interest = if client.received < protocol::REQUEST_LEN {
                libc::POLLIN
            } else {
                libc::POLLOUT
            };
            descriptors.push(poll_fd(client.stream.as_raw_fd(), interest));
        }
        let deadline = clients.iter().map(|client| client.deadline).min();
        poll(&mut descriptors, deadline)
            .map_err(|error| error.context("poll_activation_listener"))?;
        if descriptors[0].revents != 0 || events.is_stopped() {
            return Ok(());
        }
        let now = Instant::now();
        for index in (0..clients.len()).rev() {
            let revents = descriptors[index + 2].revents;
            let keep = clients[index].deadline > now
                && (revents == 0 || clients[index].progress(token, events).unwrap_or(false));
            if !keep {
                clients.swap_remove(index);
            }
        }
        if descriptors[1].revents & (libc::POLLERR | libc::POLLNVAL | libc::POLLHUP) != 0 {
            return Err(Failure::from(Error::Io).context("poll_activation_listener"));
        }
        if descriptors[1].revents & libc::POLLIN != 0 {
            accept_clients(
                &mut clients,
                || listener.accept().map(|(stream, _)| stream),
                |stream| {
                    configure_stream(&stream)?;
                    if !peer_is_current_user(&stream)? {
                        return Ok(None);
                    }
                    Ok(Some(Client {
                        stream,
                        frame: [0; protocol::REQUEST_LEN],
                        received: 0,
                        acknowledged: 0,
                        deadline: Instant::now() + CLIENT_TIMEOUT,
                    }))
                },
            )?;
        }
    }
}

// Bound accepts per wake so failed/hostile clients cannot postpone shutdown.
fn accept_clients(
    clients: &mut Vec<Client>,
    mut accept: impl FnMut() -> io::Result<UnixStream>,
    mut prepare: impl FnMut(UnixStream) -> Result<Option<Client>>,
) -> Result<()> {
    for _ in 0..MAX_CLIENTS {
        if clients.len() == MAX_CLIENTS {
            break;
        }
        match accept() {
            Ok(stream) => {
                // Configuration and credential failures reject only this connection.
                // They must never authorize a client or disable the listener.
                if let Ok(Some(client)) = prepare(stream) {
                    clients.push(client);
                }
            }
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => break,
            Err(error)
                if matches!(
                    error.kind(),
                    io::ErrorKind::Interrupted
                        | io::ErrorKind::ConnectionAborted
                        | io::ErrorKind::ConnectionReset
                ) =>
            {
                continue;
            }
            // Resource exhaustion and broken listeners go through the coordinator's
            // bounded, interruptible recovery, rather than spinning in poll/accept.
            Err(error) => return Err(Failure::from(error).context("accept_activation_client")),
        }
    }
    Ok(())
}

fn poll_fd(fd: RawFd, events: i16) -> libc::pollfd {
    libc::pollfd {
        fd,
        events,
        revents: 0,
    }
}

fn poll(descriptors: &mut [libc::pollfd], deadline: Option<Instant>) -> Result<()> {
    loop {
        let timeout = deadline.map_or(-1, |deadline| {
            deadline
                .saturating_duration_since(Instant::now())
                .as_millis()
                .saturating_add(1)
                .min(i32::MAX as u128) as i32
        });
        // SAFETY: descriptors is a live mutable array of exactly len pollfd values.
        let result = unsafe {
            libc::poll(
                descriptors.as_mut_ptr(),
                descriptors.len() as libc::nfds_t,
                timeout,
            )
        };
        if result >= 0 {
            return Ok(());
        }
        let error = io::Error::last_os_error();
        if error.kind() != io::ErrorKind::Interrupted {
            return Err(error.into());
        }
    }
}

fn wait_ready(stream: &UnixStream, interest: i16, deadline: Instant) -> Result<()> {
    if Instant::now() >= deadline {
        return Err(Error::Timeout.into());
    }
    let mut descriptors = [poll_fd(stream.as_raw_fd(), interest)];
    poll(&mut descriptors, Some(deadline))?;
    if descriptors[0].revents == 0 {
        return Err(Error::Timeout.into());
    }
    if descriptors[0].revents & libc::POLLNVAL != 0 {
        return Err(Error::Io.into());
    }
    Ok(())
}

fn validate_socket_path(path: &str) -> Result<()> {
    // SAFETY: zero is a valid initialized sockaddr_un representation.
    let address: libc::sockaddr_un = unsafe { std::mem::zeroed() };
    if !Path::new(path).is_absolute() || path.contains('\0') || path.len() >= address.sun_path.len()
    {
        return Err(Error::InvalidArgument.into());
    }
    Ok(())
}

fn configure_stream(stream: &UnixStream) -> Result<()> {
    stream
        .set_nonblocking(true)
        .map_err(|error| Failure::from(error).context("configure_activation_stream"))?;
    #[cfg(target_os = "macos")]
    {
        // Native hosts need not ignore SIGPIPE. This also covers socketpair and
        // accepted descriptors, whose standard-library constructors differ from
        // UnixStream::connect in their socket initialization.
        let enabled: libc::c_int = 1;
        // SAFETY: the descriptor is live and the option pointer/length match c_int.
        if unsafe {
            libc::setsockopt(
                stream.as_raw_fd(),
                libc::SOL_SOCKET,
                libc::SO_NOSIGPIPE,
                (&enabled as *const libc::c_int).cast(),
                size_of_val(&enabled) as libc::socklen_t,
            )
        } != 0
        {
            return Err(Failure::from(io::Error::last_os_error())
                .context("disable_activation_stream_sigpipe"));
        }
    }
    Ok(())
}

fn connect(path: &str, deadline: Instant) -> Result<UnixStream> {
    validate_socket_path(path)?;
    #[cfg(target_os = "linux")]
    let socket_flags = libc::SOCK_STREAM | libc::SOCK_CLOEXEC;
    #[cfg(not(target_os = "linux"))]
    let socket_flags = libc::SOCK_STREAM;
    // SAFETY: socket takes plain integer constants and returns an owned descriptor.
    let descriptor = unsafe { libc::socket(libc::AF_UNIX, socket_flags, 0) };
    if descriptor < 0 {
        return Err(
            Failure::from(io::Error::last_os_error()).context("create_activation_client_socket")
        );
    }
    // SAFETY: descriptor is a newly created descriptor exclusively owned here.
    let descriptor = unsafe { OwnedFd::from_raw_fd(descriptor) };
    #[cfg(not(target_os = "linux"))]
    // SAFETY: descriptor is valid; setting FD_CLOEXEC uses the integer third argument.
    if unsafe { libc::fcntl(descriptor.as_raw_fd(), libc::F_SETFD, libc::FD_CLOEXEC) } < 0 {
        return Err(Failure::from(io::Error::last_os_error())
            .context("configure_activation_client_inheritance"));
    }
    let stream = UnixStream::from(descriptor);
    configure_stream(&stream)?;
    // SAFETY: zero is a valid initialized sockaddr_un representation.
    let mut address: libc::sockaddr_un = unsafe { std::mem::zeroed() };
    address.sun_family = libc::AF_UNIX as libc::sa_family_t;
    for (output, byte) in address.sun_path.iter_mut().zip(path.as_bytes()) {
        *output = *byte as libc::c_char;
    }
    let length = std::mem::offset_of!(libc::sockaddr_un, sun_path) + path.len() + 1;
    #[cfg(target_os = "macos")]
    {
        address.sun_len = length as u8;
    }
    // SAFETY: address is initialized, has its actual length, and remains live for connect.
    let result = unsafe {
        libc::connect(
            stream.as_raw_fd(),
            (&address as *const libc::sockaddr_un).cast(),
            length as libc::socklen_t,
        )
    };
    if result != 0 {
        let error = io::Error::last_os_error();
        if error.raw_os_error() != Some(libc::EINPROGRESS) {
            return Err(Failure::from(error).context("connect_activation_socket"));
        }
        wait_ready(&stream, libc::POLLOUT, deadline)?;
        if let Some(error) = stream.take_error()? {
            return Err(Failure::from(error).context("connect_activation_socket"));
        }
    }
    if !peer_is_current_user(&stream)? {
        return Err(Error::Permission.into());
    }
    Ok(stream)
}

pub(crate) fn activate(
    endpoint: &str,
    token: &[u8; protocol::TOKEN_LEN],
    deadline: Instant,
) -> Result<()> {
    let mut stream =
        connect(endpoint, deadline).map_err(|error| error.context("connect_activation_socket"))?;
    let request = protocol::request(token);
    let mut written = 0;
    while written < request.len() {
        if Instant::now() >= deadline {
            return Err(Failure::from(Error::Timeout).context("send_activation_request"));
        }
        match stream.write(&request[written..]) {
            Ok(0) => {
                return Err(Failure::from(io::Error::from(io::ErrorKind::WriteZero))
                    .context("send_activation_request"));
            }
            Ok(count) => written += count,
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                wait_ready(&stream, libc::POLLOUT, deadline)
                    .map_err(|error| error.context("send_activation_request"))?
            }
            Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
            Err(error) => {
                return Err(Failure::from(error).context("send_activation_request"));
            }
        }
    }
    let mut ack = [0; protocol::ACK_LEN];
    let mut received = 0;
    while received < ack.len() {
        if Instant::now() >= deadline {
            return Err(Failure::from(Error::Timeout).context("read_activation_acknowledgement"));
        }
        match stream.read(&mut ack[received..]) {
            Ok(0) => {
                return Err(Failure::from(io::Error::from(io::ErrorKind::UnexpectedEof))
                    .context("read_activation_acknowledgement"));
            }
            Ok(count) => received += count,
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                wait_ready(&stream, libc::POLLIN, deadline)
                    .map_err(|error| error.context("read_activation_acknowledgement"))?
            }
            Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
            Err(error) => {
                return Err(Failure::from(error).context("read_activation_acknowledgement"));
            }
        }
    }
    if ack != protocol::ACK {
        return Err(Failure::from(Error::Protocol).context("validate_activation_acknowledgement"));
    }
    Ok(())
}

#[cfg(any(target_os = "android", target_os = "linux"))]
fn peer_is_current_user(stream: &UnixStream) -> Result<bool> {
    // SAFETY: zero is a valid initial ucred value for getsockopt to overwrite.
    let mut credentials: libc::ucred = unsafe { std::mem::zeroed() };
    let mut length = size_of_val(&credentials) as libc::socklen_t;
    // SAFETY: both output pointers are valid and describe the credential buffer.
    let result = unsafe {
        libc::getsockopt(
            stream.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            (&mut credentials as *mut libc::ucred).cast(),
            &mut length,
        )
    };
    if result != 0 {
        return Err(
            Failure::from(io::Error::last_os_error()).context("read_activation_peer_credentials")
        );
    }
    Ok(length as usize == size_of_val(&credentials) && credentials.uid == uid())
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
fn peer_is_current_user(stream: &UnixStream) -> Result<bool> {
    let mut user = 0;
    let mut group = 0;
    // SAFETY: descriptor is a connected Unix socket and output uid/gid pointers are valid.
    if unsafe { libc::getpeereid(stream.as_raw_fd(), &mut user, &mut group) } != 0 {
        return Err(
            Failure::from(io::Error::last_os_error()).context("read_activation_peer_credentials")
        );
    }
    Ok(user == uid())
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Directory(PathBuf);

    impl Directory {
        fn new() -> Self {
            let mut bytes = [0; 8];
            getrandom::fill(&mut bytes).unwrap();
            let path = Path::new("/tmp").join(format!("kgi-unit-{}", protocol::hex(&bytes)));
            prepare_directory(&path).unwrap();
            Self(path)
        }
    }

    impl Drop for Directory {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    fn server() -> (Server, Arc<Events>, [u8; protocol::TOKEN_LEN]) {
        let mut token = [0; protocol::TOKEN_LEN];
        getrandom::fill(&mut token).unwrap();
        let events = Arc::new(Events::default());
        let server = Server::start(Path::new("/tmp"), token, events.clone()).unwrap();
        (server, events, token)
    }

    #[test]
    fn aborted_accept_and_failed_client_setup_do_not_discard_the_next_client() {
        let (bad, _bad_peer) = UnixStream::pair().unwrap();
        let (good, _good_peer) = UnixStream::pair().unwrap();
        let mut incoming = [
            Err(io::Error::from_raw_os_error(libc::ECONNABORTED)),
            Ok(bad),
            Ok(good),
            Err(io::Error::from(io::ErrorKind::WouldBlock)),
        ]
        .into_iter();
        let mut prepared = 0;
        let mut clients = Vec::new();
        accept_clients(
            &mut clients,
            || incoming.next().unwrap(),
            |stream| {
                prepared += 1;
                if prepared == 1 {
                    return Err(Failure::from(Error::Permission)
                        .context("read_activation_peer_credentials"));
                }
                Ok(Some(Client {
                    stream,
                    frame: [0; protocol::REQUEST_LEN],
                    received: 0,
                    acknowledged: 0,
                    deadline: Instant::now() + CLIENT_TIMEOUT,
                }))
            },
        )
        .unwrap();
        assert_eq!(prepared, 2);
        assert_eq!(clients.len(), 1);
    }

    #[test]
    fn descriptor_exhaustion_leaves_accept_loop_for_bounded_recovery() {
        let mut calls = 0;
        let failure = accept_clients(
            &mut Vec::new(),
            || {
                calls += 1;
                Err(io::Error::from_raw_os_error(libc::EMFILE))
            },
            |_| unreachable!(),
        )
        .unwrap_err();
        assert_eq!(calls, 1);
        assert_eq!(failure.os_code(), Some(libc::EMFILE));
        assert_eq!(failure.operation(), Some("accept_activation_client"));
    }

    #[test]
    fn private_paths_reject_symlinks_hardlinks_and_shared_permissions() {
        let directory = Directory::new();
        let target = directory.0.join("private");
        prepare_directory(&target).unwrap();
        let alias = directory.0.join("alias");
        std::os::unix::fs::symlink(&target, &alias).unwrap();
        assert_eq!(
            prepare_directory(&alias).unwrap_err().kind(),
            Error::Permission
        );
        // Trusted user-owned aliases in ancestors can be used, like system aliases.
        prepare_directory(&alias.join("nested")).unwrap();
        fs::set_permissions(&target, fs::Permissions::from_mode(0o755)).unwrap();
        assert_eq!(
            prepare_directory(&target).unwrap_err().kind(),
            Error::Permission
        );
        let shared = directory.0.join("shared");
        fs::create_dir(&shared).unwrap();
        fs::set_permissions(&shared, fs::Permissions::from_mode(0o777)).unwrap();
        assert_eq!(
            prepare_directory(&shared.join("child")).unwrap_err().kind(),
            Error::Permission
        );
        assert!(!shared.join("child").exists());

        let lock = directory.0.join("app.lock");
        drop(try_lock(&lock).unwrap().unwrap());
        let link = directory.0.join("lock-link");
        fs::hard_link(&lock, &link).unwrap();
        assert_eq!(try_lock(&lock).err().unwrap().kind(), Error::Permission);
        assert_eq!(try_lock(&link).err().unwrap().kind(), Error::Permission);
        fs::remove_file(link).unwrap();
        let symlink = directory.0.join("lock-symlink");
        std::os::unix::fs::symlink(lock, &symlink).unwrap();
        assert!(try_lock(&symlink).is_err());
    }

    #[test]
    fn recursive_creation_revalidates_intermediate_directories() {
        let directory = Directory::new();
        validate_ancestors(&directory.0).unwrap();

        // Simulate an unsafe component appearing after the existing ancestor
        // passed validation but before recursive creation starts.
        let shared = directory.0.join("shared");
        fs::create_dir(&shared).unwrap();
        fs::set_permissions(&shared, fs::Permissions::from_mode(0o777)).unwrap();
        let target = shared.join("nested").join("coordination");

        let error = create_private_directory(&target).unwrap_err();

        assert_eq!(error.kind(), Error::Permission);
        assert!(target.is_dir());
    }

    #[test]
    fn lease_is_exclusive_and_does_not_replace_or_remove_its_file() {
        let directory = Directory::new();
        let lock = directory.0.join("app.lock");
        let lease = try_lock(&lock).unwrap().unwrap();
        let inode = fs::metadata(&lock).unwrap().ino();
        assert!(try_lock(&lock).unwrap().is_none());
        drop(lease);
        assert_eq!(fs::metadata(&lock).unwrap().ino(), inode);
        // Other tests spawn subprocesses. Between fork and exec they can briefly
        // inherit even CLOEXEC descriptors, retaining the shared flock after drop.
        let deadline = Instant::now() + Duration::from_secs(2);
        let replacement = loop {
            if let Some(lease) = try_lock(&lock).unwrap() {
                break lease;
            }
            assert!(Instant::now() < deadline, "lease was not released");
            thread::sleep(Duration::from_millis(1));
        };
        assert_eq!(fs::metadata(&lock).unwrap().ino(), inode);
        drop(replacement);
    }

    #[test]
    fn private_metadata_is_bounded_and_atomic_replacement_remains_private() {
        let directory = Directory::new();
        let path = directory.0.join("app.endpoint");
        publish_private(&path, b"first").unwrap();
        let previous = File::open(&path).unwrap();
        publish_private(&path, b"second").unwrap();
        assert_eq!(
            read_private_file(previous, 6).unwrap_err().io_kind(),
            Some(io::ErrorKind::NotFound)
        );
        assert_eq!(read_private(&path, 6).unwrap(), b"second");
        assert_eq!(read_private(&path, 5).unwrap_err().kind(), Error::Protocol);
        assert_eq!(fs::metadata(path).unwrap().mode() & 0o777, 0o600);
    }

    #[test]
    fn private_metadata_unlinked_after_open_is_retryable() {
        let directory = Directory::new();
        let path = directory.0.join("app.endpoint");
        publish_private(&path, b"first").unwrap();
        let file = File::open(&path).unwrap();
        fs::remove_file(&path).unwrap();

        assert_eq!(
            read_private_file(file, 6).unwrap_err().io_kind(),
            Some(io::ErrorKind::NotFound)
        );
        publish_private(&path, b"second").unwrap();
        assert_eq!(read_private(&path, 6).unwrap(), b"second");
    }

    #[test]
    fn ownership_file_validation_rejects_unlinked_handles() {
        let directory = Directory::new();
        let path = directory.0.join("app.lock");
        let lease = try_lock(&path).unwrap().unwrap();
        fs::remove_file(path).unwrap();

        assert_eq!(
            validate_file(&lease._file).unwrap_err().kind(),
            Error::Permission
        );
    }

    #[test]
    fn private_metadata_rejects_hardlinks() {
        let directory = Directory::new();
        let path = directory.0.join("app.endpoint");
        publish_private(&path, b"first").unwrap();
        let link = directory.0.join("endpoint-link");
        fs::hard_link(&path, &link).unwrap();

        assert_eq!(
            read_private(&path, 6).unwrap_err().kind(),
            Error::Permission
        );
        assert_eq!(
            read_private(&link, 6).unwrap_err().kind(),
            Error::Permission
        );
    }

    #[test]
    fn private_metadata_rejects_shared_permissions_even_after_unlink() {
        let directory = Directory::new();
        let path = directory.0.join("app.endpoint");
        publish_private(&path, b"first").unwrap();
        fs::set_permissions(&path, fs::Permissions::from_mode(0o644)).unwrap();
        let file = File::open(&path).unwrap();

        assert_eq!(
            read_private(&path, 6).unwrap_err().kind(),
            Error::Permission
        );
        fs::remove_file(path).unwrap();
        assert_eq!(
            read_private_file(file, 6).unwrap_err().kind(),
            Error::Permission
        );
    }

    #[test]
    fn invalid_token_does_not_activate_and_listener_stays_healthy() {
        let (server, events, token) = server();
        let mut client = UnixStream::connect(server.endpoint()).unwrap();
        client
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        client
            .write_all(&protocol::request(&[0; protocol::TOKEN_LEN]))
            .unwrap();
        let mut response = [0; protocol::ACK_LEN];
        assert_eq!(client.read(&mut response).unwrap(), 0);
        assert!(!events.state.lock().unwrap().pending);
        activate(
            server.endpoint(),
            &token,
            Instant::now() + Duration::from_secs(2),
        )
        .unwrap();
        assert!(events.wait().unwrap());
    }

    #[test]
    fn disconnect_before_ack_preserves_retryable_failure() {
        let directory = Directory::new();
        let endpoint = directory.0.join("socket");
        let listener = UnixListener::bind(&endpoint).unwrap();
        let peer = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(Duration::from_secs(2)))
                .unwrap();
            stream.read_exact(&mut [0; protocol::REQUEST_LEN]).unwrap();
        });
        let error = activate(
            endpoint.to_str().unwrap(),
            &[42; protocol::TOKEN_LEN],
            Instant::now() + Duration::from_secs(2),
        )
        .unwrap_err();
        peer.join().unwrap();
        assert_eq!(error.io_kind(), Some(io::ErrorKind::UnexpectedEof));
        assert_eq!(error.operation(), Some("read_activation_acknowledgement"));
    }

    #[test]
    fn endpoint_collision_preserves_owner_and_drop_removes_owned_directory() {
        let runtime = Directory::new();
        let token = [42; protocol::TOKEN_LEN];
        let events = Arc::new(Events::default());
        let server = Server::start(&runtime.0, token, events.clone()).unwrap();
        let endpoint = PathBuf::from(server.endpoint());
        let directory = endpoint.parent().unwrap().to_path_buf();

        let error = Server::start(&runtime.0, token, Arc::new(Events::default()))
            .err()
            .unwrap();
        assert_eq!(error.io_kind(), Some(io::ErrorKind::AlreadyExists));
        assert!(endpoint.exists());
        activate(
            server.endpoint(),
            &token,
            Instant::now() + Duration::from_secs(2),
        )
        .unwrap();
        assert!(events.wait().unwrap());

        drop(server);
        assert!(!endpoint.exists());
        assert!(!directory.exists());
        assert!(runtime.0.exists());
    }

    #[test]
    fn socket_creation_failure_removes_owned_endpoint_directory() {
        const CHILD_ENV: &str = "KEYGUARD_INSTANCE_FD_LIMIT_TEST_CHILD";
        if std::env::var_os(CHILD_ENV).is_none() {
            let output = std::process::Command::new(std::env::current_exe().unwrap())
                .args([
                    "--exact",
                    "platform::tests::socket_creation_failure_removes_owned_endpoint_directory",
                    "--nocapture",
                ])
                .env(CHILD_ENV, "1")
                .output()
                .unwrap();
            assert!(
                output.status.success(),
                "child failed: {:?}, stdout={}, stderr={}",
                output.status,
                String::from_utf8_lossy(&output.stdout),
                String::from_utf8_lossy(&output.stderr)
            );
            return;
        }

        let runtime = Directory::new();
        let token = [43; protocol::TOKEN_LEN];
        let directory = runtime.0.join(endpoint_directory_name(&token));
        let limit = libc::rlimit {
            rlim_cur: 64,
            rlim_max: 64,
        };
        // SAFETY: this dedicated subprocess changes only its own descriptor limit;
        // limit is a live, initialized rlimit value for the duration of the call.
        assert_eq!(unsafe { libc::setrlimit(libc::RLIMIT_NOFILE, &limit) }, 0);
        let mut descriptors = Vec::new();
        loop {
            match File::open("/dev/null") {
                Ok(file) => descriptors.push(file),
                Err(error) => {
                    assert_eq!(error.raw_os_error(), Some(libc::EMFILE));
                    break;
                }
            }
        }
        let result = Server::start(&runtime.0, token, Arc::new(Events::default()));
        drop(descriptors);
        let error = result.err().unwrap();
        assert_eq!(error.operation(), Some("bind_activation_socket"));
        assert_eq!(error.os_code(), Some(libc::EMFILE));
        assert!(!directory.exists());

        let replacement = Server::start(&runtime.0, token, Arc::new(Events::default())).unwrap();
        assert!(Path::new(replacement.endpoint()).exists());
        drop(replacement);
        assert!(!directory.exists());
    }

    #[test]
    fn stop_wakes_idle_worker_and_partial_clients_without_waiting_for_timeout() {
        for stalled in [false, true] {
            let (mut server, events, _token) = server();
            let mut clients = Vec::new();
            if stalled {
                for _ in 0..MAX_CLIENTS {
                    let mut client = UnixStream::connect(server.endpoint()).unwrap();
                    client.write_all(b"K").unwrap();
                    clients.push(client);
                }
            }
            let started = Instant::now();
            events.stop();
            server.stop().unwrap();
            server.join().unwrap();
            assert!(started.elapsed() < Duration::from_millis(500));
            assert!(!events.wait().unwrap());
            drop(clients);
        }
    }

    #[test]
    fn raw_client_socket_suppresses_sigpipe_in_a_native_host() {
        const CHILD_ENV: &str = "KEYGUARD_INSTANCE_SIGPIPE_TEST_CHILD";
        if std::env::var_os(CHILD_ENV).is_none() {
            let output = std::process::Command::new(std::env::current_exe().unwrap())
                .args([
                    "--exact",
                    "platform::tests::raw_client_socket_suppresses_sigpipe_in_a_native_host",
                    "--nocapture",
                ])
                .env(CHILD_ENV, "1")
                .output()
                .unwrap();
            assert!(
                output.status.success(),
                "child failed: {:?}, stdout={}, stderr={}",
                output.status,
                String::from_utf8_lossy(&output.stdout),
                String::from_utf8_lossy(&output.stderr)
            );
            return;
        }
        let directory = Directory::new();
        let path = directory.0.join("socket");
        let listener = UnixListener::bind(&path).unwrap();
        let mut client = connect(
            path.to_str().unwrap(),
            Instant::now() + Duration::from_secs(2),
        )
        .unwrap();
        let (peer, _) = listener.accept().unwrap();
        drop(peer);
        wait_ready(
            &client,
            libc::POLLIN,
            Instant::now() + Duration::from_secs(2),
        )
        .unwrap();
        assert_eq!(client.read(&mut [0]).unwrap(), 0);
        // SAFETY: this is a dedicated subprocess. Restoring the default process
        // signal disposition deliberately models a native host, without affecting
        // other test threads or relying on Rust/JVM ignoring SIGPIPE globally.
        unsafe {
            libc::signal(libc::SIGPIPE, libc::SIG_DFL);
        }
        assert_eq!(
            client.write(b"request").unwrap_err().kind(),
            io::ErrorKind::BrokenPipe
        );
        // Wake socketpair writes on stop/drop and accepted-socket ACK writes
        // must be safe with the same native process signal disposition.
        let (mut server, events, token) = server();
        activate(
            server.endpoint(),
            &token,
            Instant::now() + Duration::from_secs(2),
        )
        .unwrap();
        assert!(events.wait().unwrap());
        events.stop();
        server.stop().unwrap();
        let deadline = Instant::now() + Duration::from_secs(2);
        while !server.worker.as_ref().unwrap().is_finished() {
            assert!(Instant::now() < deadline);
            thread::sleep(Duration::from_millis(1));
        }
        // The receiver has closed. This wake must return harmlessly with SIG_DFL.
        server.stop().unwrap();
        server.join().unwrap();
        drop(server);
    }
}
