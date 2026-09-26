use crate::{Error, Events, Failure, Result, platform, protocol};
use std::io;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Condvar, Mutex};
use std::thread;
use std::time::{Duration, Instant};

const MAX_RECOVERY_ATTEMPTS: u32 = 3;

#[derive(Default)]
struct Shutdown {
    requested: Mutex<bool>,
    changed: Condvar,
}

impl Shutdown {
    fn request(&self) -> Result<()> {
        *self.requested.lock().map_err(|_| Error::Internal)? = true;
        self.changed.notify_all();
        Ok(())
    }

    fn is_requested(&self) -> bool {
        self.requested.lock().map_or(true, |requested| *requested)
    }

    fn wait(&self, delay: Duration) -> Result<bool> {
        let requested = self.requested.lock().map_err(|_| Error::Internal)?;
        let (requested, _) = self
            .changed
            .wait_timeout_while(requested, delay, |requested| !*requested)
            .map_err(|_| Error::Internal)?;
        Ok(*requested)
    }
}

/// Result of process arbitration.
pub enum Acquisition {
    /// This process holds ownership, and its activation listener is ready.
    Primary(Instance),
    /// The existing primary acknowledged and queued activation.
    Activated,
}

struct Resources {
    server: Option<platform::Server>,
    lease: Option<platform::Lease>,
    events: Arc<Events>,
    recovery_attempts: u32,
}

/// An owned primary instance. Closing the transport alone does not release ownership.
pub struct Instance {
    resources: Mutex<Resources>,
    metadata: PathBuf,
    runtime: PathBuf,
    shutdown: Shutdown,
}

impl Instance {
    /// Waits until an activation is accepted or shutdown starts.
    ///
    /// At most one pending activation is retained, including before the first wait.
    /// Returns `true` for activation and `false` once stopped. Multiple waiters are
    /// permitted, but only one consumes each coalesced activation.
    ///
    /// # Errors
    /// Recovers a failed listener up to three times, retaining ownership throughout.
    /// Returns the last failure if recovery is exhausted, or a synchronization failure.
    pub fn await_activation(&self) -> Result<bool> {
        loop {
            if self.shutdown.is_requested() {
                return Ok(false);
            }
            let events = self
                .resources
                .lock()
                .map_err(|_| Error::Internal)?
                .events
                .clone();
            match events.wait() {
                Ok(true) => {
                    // A delivered activation proves the replacement is healthy. Until then,
                    // repeated worker failures share one bounded recovery budget.
                    let mut resources = self.resources.lock().map_err(|_| Error::Internal)?;
                    if Arc::ptr_eq(&events, &resources.events) {
                        resources.recovery_attempts = 0;
                    }
                    return Ok(!self.shutdown.is_requested());
                }
                Ok(false) => return Ok(false),
                Err(error) => self.recover(&events, error)?,
            }
        }
    }

    fn recover(&self, failed_events: &Arc<Events>, mut failure: Failure) -> Result<()> {
        // Serialize restart/publication with stop/close and other receivers. Shutdown's
        // separate condition variable interrupts backoff without needing this lock.
        let mut resources = self.resources.lock().map_err(|_| Error::Internal)?;
        if self.shutdown.is_requested() || !Arc::ptr_eq(failed_events, &resources.events) {
            return Ok(());
        }
        Self::stop_server(&mut resources)?;
        resources.server.take();
        self.remove_metadata()?;
        while resources.recovery_attempts < MAX_RECOVERY_ATTEMPTS {
            let delay = Duration::from_millis(100 << resources.recovery_attempts);
            resources.recovery_attempts += 1;
            if self.shutdown.wait(delay)? {
                return Ok(());
            }
            // The lease pins the existing namespace on Windows. Revalidate it and
            // construct thread-local security descriptors on the recovery thread.
            let restarted =
                platform::prepare_directory(self.metadata.parent().ok_or(Error::Internal)?)
                    .map_err(|error| error.context("prepare_directory"))
                    .and_then(|directory| {
                        start_server(&directory, &self.runtime, &self.metadata, &self.shutdown)
                    });
            match restarted {
                Ok(Some((server, events))) => {
                    // Old failure state remains intact until a ready replacement is published.
                    resources.server = Some(server);
                    resources.events = events;
                    return Ok(());
                }
                Ok(None) => return Ok(()),
                Err(error) => failure = error,
            }
        }
        resources.events.fail(failure);
        if self.shutdown.is_requested() {
            Ok(())
        } else {
            Err(failure)
        }
    }

    /// Stops accepting activation and wakes receivers, retaining process ownership.
    ///
    /// # Errors
    /// Returns an operating-system or worker shutdown failure.
    pub fn stop(&self) -> Result<()> {
        self.shutdown.request()?;
        let mut resources = self.resources.lock().map_err(|_| Error::Internal)?;
        resources.events.stop();
        Self::stop_server(&mut resources)
    }

    fn stop_server(resources: &mut Resources) -> Result<()> {
        if let Some(server) = resources.server.as_mut() {
            server
                .stop()
                .map_err(|error| error.context("stop_listener"))?;
            server
                .join()
                .map_err(|error| error.context("join_listener"))?;
        }
        Ok(())
    }

    /// Stops transport work and removes its metadata before releasing ownership.
    ///
    /// Idempotent. Only call after services sharing the protected data have stopped.
    /// The permanent ownership file intentionally remains on disk.
    ///
    /// # Errors
    /// Returns an operating-system or worker shutdown failure. Ownership is retained
    /// if transport shutdown fails, until this instance is dropped.
    pub fn close(&self) -> Result<()> {
        self.shutdown.request()?;
        let mut resources = self.resources.lock().map_err(|_| Error::Internal)?;
        resources.events.stop();
        Self::stop_server(&mut resources)?;
        // All cleanup precedes releasing the lease: an arriving primary must not
        // have its endpoint removed by a previous owner's shutdown.
        resources.server.take();
        if resources.lease.is_some() {
            self.remove_metadata()?;
            resources.lease.take();
        }
        Ok(())
    }

    fn remove_metadata(&self) -> Result<()> {
        match std::fs::remove_file(&self.metadata) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(Failure::from(error).context("remove_endpoint")),
        }
    }
}

fn start_server(
    directory: &platform::Directory,
    runtime: &Path,
    metadata: &Path,
    shutdown: &Shutdown,
) -> Result<Option<(platform::Server, Arc<Events>)>> {
    let events = Arc::new(Events::default());
    let mut token = [0; protocol::TOKEN_LEN];
    getrandom::fill(&mut token)
        .map_err(|_| Failure::from(Error::Internal).context("generate_token"))?;
    let server = platform::Server::start(runtime, token, events.clone())
        .map_err(|error| error.context("start_listener"))?;
    if events.is_stopped() {
        // Preserve the worker's diagnostic if it failed during initialization.
        events.wait()?;
        return Err(Failure::from(Error::Unavailable).context("start_listener"));
    }
    let record = protocol::encode_endpoint(server.endpoint(), &token)
        .map_err(|error| error.context("encode_endpoint"))?;
    // Publication and shutdown have one linearization point. A shutdown request
    // observed here cannot be followed by publication of a replacement endpoint.
    let requested = shutdown.requested.lock().map_err(|_| Error::Internal)?;
    if *requested {
        return Ok(None);
    }
    directory
        .publish_private(metadata, &record)
        .map_err(|error| error.context("publish_endpoint"))?;
    Ok(Some((server, events)))
}

impl Drop for Instance {
    fn drop(&mut self) {
        // A failed best-effort close still drops Server before Lease, as declared
        // in Resources. OS handle closure releases ownership on process exit too.
        let _ = self.close();
    }
}

/// Acquires process ownership or delivers activation within one bounded deadline.
///
/// `coordination_dir` is a stable private local directory, shared by builds that
/// protect the same data. `runtime_dir` is an existing root for short-lived IPC
/// endpoints; it can be shared temporary storage. Identity is a stable ASCII name
/// (letters, digits, `.`, `_`, `-`, at most 64 characters), independent of version
/// and architecture. The timeout must be between 1 and 60,000 milliseconds.
///
/// # Errors
/// Returns invalid arguments, permission failures, incompatible metadata/protocol,
/// or a deadline failure. Failure to contact a primary never grants ownership.
pub fn acquire_or_activate(
    coordination_dir: &str,
    runtime_dir: &str,
    identity: &str,
    timeout_ms: u64,
) -> Result<Acquisition> {
    validate_path(coordination_dir)?;
    validate_path(runtime_dir)?;
    if identity.is_empty()
        || identity.len() > 64
        || matches!(identity, "." | "..")
        || !identity
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, b'.' | b'_' | b'-'))
        || !(1..=60_000).contains(&timeout_ms)
    {
        return Err(Failure::from(Error::InvalidArgument).context("validate_config"));
    }
    let deadline = Instant::now() + Duration::from_millis(timeout_ms);
    let coordination_path = Path::new(coordination_dir);
    let directory = platform::prepare_directory(coordination_path)
        .map_err(|error| error.context("prepare_directory"))?;
    let (lock_path, metadata) = coordination_files(coordination_path, identity);
    let mut delay = Duration::from_millis(25);
    let mut last_failure = None;
    loop {
        if Instant::now() >= deadline {
            return Err(acquisition_timeout(last_failure));
        }
        if let Some(lease) = directory
            .try_lock(&lock_path)
            .map_err(|error| error.context("acquire_lock"))?
        {
            // Only the elected owner may clean the previously published endpoint.
            // Broken or obsolete metadata must not prevent replacing a crashed
            // owner's state; cleanup is best effort and never touches the lease.
            if let Ok(record) = directory.read_private(&metadata, protocol::MAX_METADATA)
                && let Ok((endpoint, token)) = protocol::decode_endpoint(&record)
            {
                let _ = platform::cleanup_endpoint(endpoint, &token);
            }
            let shutdown = Shutdown::default();
            let (server, events) =
                start_server(&directory, Path::new(runtime_dir), &metadata, &shutdown)?
                    .ok_or(Error::Internal)?;
            return Ok(Acquisition::Primary(Instance {
                resources: Mutex::new(Resources {
                    server: Some(server),
                    lease: Some(lease),
                    events,
                    recovery_attempts: 0,
                }),
                metadata,
                runtime: PathBuf::from(runtime_dir),
                shutdown,
            }));
        }
        match directory
            .read_private(&metadata, protocol::MAX_METADATA)
            .map_err(|error| error.context("read_endpoint"))
        {
            Ok(record) => {
                let (endpoint, token) = protocol::decode_endpoint(&record)
                    .map_err(|error| error.context("decode_endpoint"))?;
                let attempt_deadline = deadline.min(Instant::now() + Duration::from_secs(1));
                match platform::activate(endpoint, &token, attempt_deadline)
                    .map_err(|error| error.context("activate"))
                {
                    Ok(()) => return Ok(Acquisition::Activated),
                    Err(error) if retry_activation(error) => {
                        last_failure = Some(error);
                    }
                    Err(error) => return Err(error),
                }
            }
            // Atomic publication can leave the endpoint briefly absent during startup.
            Err(error) if error.io_kind() == Some(io::ErrorKind::NotFound) => {
                last_failure = Some(error);
            }
            Err(error) => return Err(error),
        }
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Err(acquisition_timeout(last_failure));
        }
        thread::sleep(delay.min(remaining));
        delay = (delay * 2).min(Duration::from_millis(200));
    }
}

fn coordination_files(directory: &Path, identity: &str) -> (PathBuf, PathBuf) {
    let stem = format!("kgi-{identity}");
    (
        directory.join(format!("{stem}.lock")),
        directory.join(format!("{stem}.endpoint")),
    )
}

fn validate_path(path: &str) -> Result<()> {
    if path.is_empty()
        || path.len() > 32_768
        || path.contains('\0')
        || !Path::new(path).is_absolute()
    {
        return Err(Failure::from(Error::InvalidArgument).context("validate_config"));
    }
    Ok(())
}

fn acquisition_timeout(last_failure: Option<Failure>) -> Failure {
    last_failure
        .unwrap_or_else(|| Failure::from(Error::Timeout).context("acquire_lock"))
        .into_timeout()
}

fn retry_activation(error: Failure) -> bool {
    match error.kind() {
        Error::Timeout | Error::Unavailable => true,
        Error::Io => {
            #[cfg(windows)]
            if matches!(error.os_code(), Some(code)
                if code == windows_sys::Win32::Foundation::ERROR_PIPE_BUSY as i32
                    || code == windows_sys::Win32::Foundation::ERROR_NO_DATA as i32)
            {
                // A pipe instance may be busy or disconnect while the primary is restarting.
                return true;
            }
            matches!(
                error.io_kind(),
                Some(
                    io::ErrorKind::NotFound
                        | io::ErrorKind::ConnectionRefused
                        | io::ErrorKind::ConnectionAborted
                        | io::ErrorKind::ConnectionReset
                        | io::ErrorKind::BrokenPipe
                        | io::ErrorKind::NotConnected
                        | io::ErrorKind::UnexpectedEof
                        | io::ErrorKind::Interrupted
                )
            )
        }
        Error::InvalidArgument
        | Error::Protocol
        | Error::InvalidHandle
        | Error::Permission
        | Error::Internal => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Fixture {
        instance: Arc<Instance>,
        root: PathBuf,
        coordination: PathBuf,
        runtime: PathBuf,
    }

    impl Fixture {
        fn new() -> Self {
            let mut nonce = [0; 8];
            getrandom::fill(&mut nonce).unwrap();
            #[cfg(unix)]
            let temporary = PathBuf::from("/tmp");
            #[cfg(windows)]
            let temporary = std::env::temp_dir();
            let root = temporary.join(format!("kir-{}", protocol::hex(&nonce)));
            let runtime = root.join("runtime");
            std::fs::create_dir_all(&runtime).unwrap();
            let coordination = root.join("state");
            let Acquisition::Primary(instance) = acquire_or_activate(
                coordination.to_str().unwrap(),
                runtime.to_str().unwrap(),
                "test",
                2000,
            )
            .unwrap() else {
                panic!("expected ownership")
            };
            Self {
                instance: Arc::new(instance),
                root,
                coordination,
                runtime,
            }
        }

        fn fail_listener(&self) {
            self.instance
                .resources
                .lock()
                .unwrap()
                .events
                .fail(Failure::from(Error::Io).context("injected_listener_failure"));
        }

        fn receive_activation(&self) -> std::sync::mpsc::Receiver<Result<bool>> {
            let (send, receive) = std::sync::mpsc::channel();
            let instance = self.instance.clone();
            thread::spawn(move || {
                let _ = send.send(instance.await_activation());
            });
            receive
        }

        fn assert_owned(&self) {
            let directory = platform::prepare_directory(&self.coordination).unwrap();
            let (lock, _) = coordination_files(&self.coordination, "test");
            assert!(directory.try_lock(&lock).unwrap().is_none());
        }
    }

    impl Drop for Fixture {
        fn drop(&mut self) {
            self.instance.close().unwrap();
            let _ = std::fs::remove_dir_all(&self.root);
        }
    }

    #[test]
    fn listener_failure_recovers_without_releasing_ownership() {
        let fixture = Fixture::new();
        let previous = std::fs::read(&fixture.instance.metadata).unwrap();
        fixture.fail_listener();
        let receiver = fixture.receive_activation();
        let outcome = acquire_or_activate(
            fixture.coordination.to_str().unwrap(),
            fixture.runtime.to_str().unwrap(),
            "test",
            2000,
        )
        .unwrap();
        assert!(matches!(outcome, Acquisition::Activated));
        assert_eq!(
            receiver.recv_timeout(Duration::from_secs(5)).unwrap(),
            Ok(true)
        );
        assert_ne!(std::fs::read(&fixture.instance.metadata).unwrap(), previous);
        fixture.assert_owned();
    }

    #[cfg(unix)]
    #[test]
    fn exhausted_recovery_retains_lease_and_explicit_stop_wakes_receiver() {
        let fixture = Fixture::new();
        fixture.fail_listener();
        // The old server's private directory disappears when recovery joins it.
        // Renaming the runtime prevents every replacement from being created.
        let moved = fixture.root.join("unavailable-runtime");
        std::fs::rename(&fixture.runtime, &moved).unwrap();
        let started = Instant::now();
        assert!(fixture.instance.await_activation().is_err());
        assert!(started.elapsed() < Duration::from_secs(3));
        assert!(!fixture.instance.metadata.exists());
        fixture.assert_owned();
        fixture.instance.stop().unwrap();
        assert_eq!(fixture.instance.await_activation(), Ok(false));
    }

    #[test]
    fn stop_interrupts_recovery_and_prevents_republication() {
        let fixture = Fixture::new();
        fixture.fail_listener();
        let receiver = fixture.receive_activation();
        let deadline = Instant::now() + Duration::from_secs(2);
        while fixture.instance.metadata.exists() {
            assert!(Instant::now() < deadline, "recovery did not start");
            thread::sleep(Duration::from_millis(1));
        }
        let started = Instant::now();
        fixture.instance.stop().unwrap();
        assert_eq!(
            receiver.recv_timeout(Duration::from_secs(5)).unwrap(),
            Ok(false)
        );
        assert!(started.elapsed() < Duration::from_millis(500));
        assert!(!fixture.instance.metadata.exists());
        fixture.assert_owned();
    }

    #[test]
    fn repeated_worker_failures_share_a_budget_until_activation() {
        let fixture = Fixture::new();
        let receiver = fixture.receive_activation();
        for attempt in 0..=MAX_RECOVERY_ATTEMPTS {
            let events = fixture.instance.resources.lock().unwrap().events.clone();
            events.fail(Error::Io);
            if attempt == MAX_RECOVERY_ATTEMPTS {
                break;
            }
            let deadline = Instant::now() + Duration::from_secs(2);
            loop {
                let current = fixture.instance.resources.lock().unwrap().events.clone();
                if !Arc::ptr_eq(&events, &current) {
                    break;
                }
                assert!(Instant::now() < deadline);
                thread::sleep(Duration::from_millis(1));
            }
        }
        assert_eq!(
            receiver.recv_timeout(Duration::from_secs(5)).unwrap(),
            Err(Error::Io.into())
        );
        let started = Instant::now();
        assert_eq!(fixture.instance.await_activation(), Err(Error::Io.into()));
        assert!(started.elapsed() < Duration::from_millis(100));
        assert!(!fixture.instance.metadata.exists());
        fixture.assert_owned();
    }

    #[cfg(unix)]
    #[test]
    fn real_descriptor_exhaustion_recovers_after_descriptors_are_released() {
        use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};

        const CHILD_ENV: &str = "KEYGUARD_INSTANCE_RECOVERY_FD_TEST_CHILD";
        if std::env::var_os(CHILD_ENV).is_none() {
            let output = std::process::Command::new(std::env::current_exe().unwrap())
                .args(["--exact", "coordinator::tests::real_descriptor_exhaustion_recovers_after_descriptors_are_released", "--nocapture"])
                .env(CHILD_ENV, "1").output().unwrap();
            assert!(
                output.status.success(),
                "{}\n{}",
                String::from_utf8_lossy(&output.stdout),
                String::from_utf8_lossy(&output.stderr)
            );
            return;
        }

        let fixture = Fixture::new();
        let record = std::fs::read(&fixture.instance.metadata).unwrap();
        let (endpoint, _) = protocol::decode_endpoint(&record).unwrap();
        // Allocate the client descriptor before exhausting the descriptor table.
        // SAFETY: socket returns a uniquely owned descriptor, checked before wrapping.
        let raw = unsafe { libc::socket(libc::AF_UNIX, libc::SOCK_STREAM, 0) };
        assert!(raw >= 0);
        // SAFETY: raw is valid and ownership is transferred exactly once.
        let client = unsafe { OwnedFd::from_raw_fd(raw) };
        // SAFETY: Zero initializes sockaddr_un, filled completely below before use.
        let mut address: libc::sockaddr_un = unsafe { std::mem::zeroed() };
        address.sun_family = libc::AF_UNIX as libc::sa_family_t;
        for (target, byte) in address.sun_path.iter_mut().zip(endpoint.bytes()) {
            *target = byte as libc::c_char;
        }
        let length = std::mem::offset_of!(libc::sockaddr_un, sun_path) + endpoint.len() + 1;
        #[cfg(target_os = "macos")]
        {
            address.sun_len = length as u8;
        }
        let receiver = fixture.receive_activation();
        let limit = libc::rlimit {
            rlim_cur: 64,
            rlim_max: 64,
        };
        // SAFETY: Only this dedicated subprocess changes its limit; limit is initialized.
        assert_eq!(unsafe { libc::setrlimit(libc::RLIMIT_NOFILE, &limit) }, 0);
        let mut files = Vec::new();
        loop {
            match std::fs::File::open("/dev/null") {
                Ok(file) => files.push(file),
                Err(error) => {
                    assert_eq!(error.raw_os_error(), Some(libc::EMFILE));
                    break;
                }
            }
        }
        assert_eq!(
            // SAFETY: address and its actual size describe a valid local Unix socket path.
            unsafe {
                libc::connect(
                    client.as_raw_fd(),
                    (&address as *const libc::sockaddr_un).cast(),
                    length as libc::socklen_t,
                )
            },
            0
        );
        let deadline = Instant::now() + Duration::from_secs(2);
        while fixture.instance.metadata.exists() {
            assert!(Instant::now() < deadline, "accept did not trigger recovery");
            thread::sleep(Duration::from_millis(1));
        }
        drop(files);
        drop(client);
        assert!(matches!(
            acquire_or_activate(
                fixture.coordination.to_str().unwrap(),
                fixture.runtime.to_str().unwrap(),
                "test",
                2000,
            )
            .unwrap(),
            Acquisition::Activated
        ));
        assert_eq!(
            receiver.recv_timeout(Duration::from_secs(5)).unwrap(),
            Ok(true)
        );
        assert_ne!(std::fs::read(&fixture.instance.metadata).unwrap(), record);
        fixture.assert_owned();
    }

    #[test]
    fn coordination_files_namespace_windows_device_names() {
        let directory = Path::new("coordination");

        let (lock, metadata) = coordination_files(directory, "CON");

        assert_eq!(lock, directory.join("kgi-CON.lock"));
        assert_eq!(metadata, directory.join("kgi-CON.endpoint"));
    }

    #[test]
    fn transient_contact_errors_retry_but_configuration_and_resource_failures_do_not() {
        for kind in [
            io::ErrorKind::NotFound,
            io::ErrorKind::ConnectionRefused,
            io::ErrorKind::ConnectionReset,
            io::ErrorKind::BrokenPipe,
            io::ErrorKind::UnexpectedEof,
            io::ErrorKind::WouldBlock,
            io::ErrorKind::TimedOut,
        ] {
            assert!(retry_activation(io::Error::from(kind).into()), "{kind:?}");
        }
        for kind in [
            io::ErrorKind::InvalidInput,
            io::ErrorKind::PermissionDenied,
            io::ErrorKind::OutOfMemory,
            io::ErrorKind::WriteZero,
            io::ErrorKind::Other,
        ] {
            assert!(!retry_activation(io::Error::from(kind).into()), "{kind:?}");
        }
        assert!(!retry_activation(Error::InvalidArgument.into()));
        assert!(!retry_activation(Error::Internal.into()));
        assert!(!retry_activation(Error::Protocol.into()));
    }

    #[cfg(windows)]
    #[test]
    fn busy_pipe_retries_without_retrying_unrelated_win32_errors() {
        use windows_sys::Win32::Foundation::{ERROR_INVALID_HANDLE, ERROR_PIPE_BUSY};
        assert!(retry_activation(
            io::Error::from_raw_os_error(ERROR_PIPE_BUSY as i32).into()
        ));
        assert!(!retry_activation(
            io::Error::from_raw_os_error(ERROR_INVALID_HANDLE as i32).into()
        ));
    }
}
