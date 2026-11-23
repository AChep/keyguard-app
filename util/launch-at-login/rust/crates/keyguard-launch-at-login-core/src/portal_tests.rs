//! Actual D-Bus messages against a private fake portal, never the desktop portal.
use super::*;
use std::sync::{
    Arc, Mutex,
    atomic::{AtomicBool, AtomicUsize, Ordering},
};
use zbus::runtime::traits::TaskHandle;
use zbus::{
    Connection, connection::Builder, message::Header, object_server::ResponseDispatchNotifier,
};

#[derive(Clone, Copy, Debug)]
enum Scenario {
    /// Success with this `autostart` result; `false` is what xdg-desktop-portal
    /// sends when it allowed the request but failed to save the autostart file.
    Early(bool),
    Late,
    Cancel,
    /// What xdg-desktop-portal sends for "Don't allow" or a revoked permission.
    Refuse,
    Deny,
    MissingResult,
    Malformed,
    WrongHandle,
    SilentMethod,
    SilentResponse,
    SilentClose,
    FailedClose,
    AccessDenied,
    MissingInterface,
    SilentVersion,
}

#[derive(Default)]
struct Observed {
    requests: AtomicUsize,
    closes: AtomicUsize,
    options: Mutex<Option<HashMap<String, OwnedVariant>>>,
}

struct Background {
    scenario: Scenario,
    observed: Arc<Observed>,
}
struct Request {
    scenario: Scenario,
    observed: Arc<Observed>,
}

#[zbus::interface(interface = "org.freedesktop.portal.Request")]
impl Request {
    async fn close(&self) -> zbus::fdo::Result<()> {
        self.observed.closes.fetch_add(1, Ordering::SeqCst);
        match self.scenario {
            Scenario::SilentClose => std::future::pending().await,
            Scenario::FailedClose => Err(zbus::fdo::Error::Failed("fixture close failure".into())),
            _ => Ok(()),
        }
    }
}

#[zbus::interface(interface = "org.freedesktop.portal.Background")]
impl Background {
    #[zbus(property, name = "version")]
    async fn version(&self) -> zbus::fdo::Result<u32> {
        if matches!(self.scenario, Scenario::SilentVersion) {
            return std::future::pending().await;
        }
        if matches!(self.scenario, Scenario::MissingInterface) {
            Err(zbus::fdo::Error::UnknownInterface(
                "fixture missing interface".into(),
            ))
        } else {
            Ok(2)
        }
    }

    async fn request_background(
        &self,
        parent: &str,
        options: HashMap<String, OwnedVariant>,
        #[zbus(header)] header: Header<'_>,
        #[zbus(connection)] connection: &Connection,
    ) -> zbus::fdo::Result<ResponseDispatchNotifier<OwnedObjectPath>> {
        assert_eq!(parent, "");
        self.observed.requests.fetch_add(1, Ordering::SeqCst);
        let token = <&str>::try_from(options.get("handle_token").unwrap()).unwrap();
        let sender = header
            .sender()
            .unwrap()
            .as_str()
            .trim_start_matches(':')
            .replace('.', "_");
        let predicted = format!("/org/freedesktop/portal/desktop/request/{sender}/{token}");
        *self.observed.options.lock().unwrap() = Some(options);
        let path = if matches!(self.scenario, Scenario::WrongHandle) {
            format!("{predicted}_wrong")
        } else {
            predicted
        };
        connection
            .object_server()
            .at(
                path.as_str(),
                Request {
                    scenario: self.scenario,
                    observed: self.observed.clone(),
                },
            )
            .await
            .map_err(|error| zbus::fdo::Error::Failed(error.to_string()))?;
        if matches!(self.scenario, Scenario::SilentMethod) {
            return std::future::pending().await;
        }
        if matches!(self.scenario, Scenario::AccessDenied) {
            return Err(zbus::fdo::Error::AccessDenied("fixture denied".into()));
        }
        let (reply, dispatched) =
            ResponseDispatchNotifier::new(OwnedObjectPath::try_from(path.as_str()).unwrap());
        if matches!(self.scenario, Scenario::Late) {
            let connection_copy = connection.clone();
            connection
                .spawn("fixture delayed response", async move {
                    dispatched.await;
                    emit(&connection_copy, &path, Scenario::Early(true)).await;
                })
                .detach();
        } else if !matches!(
            self.scenario,
            Scenario::SilentResponse
                | Scenario::SilentClose
                | Scenario::FailedClose
                | Scenario::WrongHandle
        ) {
            // Intentionally emits before RequestBackground returns its handle.
            emit(connection, &path, self.scenario).await;
        }
        Ok(reply)
    }
}

async fn emit(connection: &Connection, path: &str, scenario: Scenario) {
    if matches!(scenario, Scenario::Malformed) {
        connection
            .emit_signal(
                None::<&str>,
                path,
                "org.freedesktop.portal.Request",
                "Response",
                &"invalid body",
            )
            .await
            .unwrap();
        return;
    }
    let code = match scenario {
        Scenario::Cancel | Scenario::Refuse => 1u32,
        Scenario::Deny => 2,
        _ => 0,
    };
    let mut values = HashMap::<String, OwnedVariant>::new();
    if matches!(scenario, Scenario::Refuse) {
        values.insert("background".into(), OwnedVariant::from(false));
    }
    if !matches!(scenario, Scenario::MissingResult) {
        values.insert(
            "autostart".into(),
            OwnedVariant::from(!matches!(
                scenario,
                Scenario::Early(false) | Scenario::Refuse
            )),
        );
    }
    connection
        .emit_signal(
            None::<&str>,
            path,
            "org.freedesktop.portal.Request",
            "Response",
            &(code, values),
        )
        .await
        .unwrap();
}

struct Fixture {
    client: Connection,
    server: Connection,
    observed: Arc<Observed>,
    destination: &'static str,
    #[cfg(target_os = "linux")]
    _bus: PrivateBus,
}

impl Fixture {
    async fn new(scenario: Scenario) -> Self {
        let observed = Arc::new(Observed::default());
        #[cfg(target_os = "linux")]
        let bus = PrivateBus::start();
        #[cfg(target_os = "linux")]
        let (server, client, destination) = (
            Builder::address(bus.address.as_str()).name(DESTINATION),
            Builder::address(bus.address.as_str()),
            DESTINATION,
        );
        // macOS has no system dbus-daemon. A private authenticated socket pair
        // still exercises the same wire codec, signal subscription, and request code.
        #[cfg(not(target_os = "linux"))]
        let (server, client, destination) = {
            let (server, client) = std::os::unix::net::UnixStream::pair().unwrap();
            (
                Builder::unix_stream(server)
                    .server(zbus::Guid::generate())
                    .p2p()
                    .unique_name(":1.2"),
                Builder::unix_stream(client).p2p().unique_name(":1.1"),
                ":1.2",
            )
        };
        let server = server.serve_at(
            "/org/freedesktop/portal/desktop",
            Background {
                scenario,
                observed: observed.clone(),
            },
        );
        let (server, client) = futures::try_join!(server.build(), client.build()).unwrap();
        #[cfg(not(target_os = "linux"))]
        client.set_unique_name(":1.1").unwrap();
        Self {
            client,
            server,
            observed,
            destination,
            #[cfg(target_os = "linux")]
            _bus: bus,
        }
    }

    async fn run(&self, operation: Operation) -> Result<Status, Failure> {
        let target = Target {
            kind: "flatpak",
            id: "",
            name: "Fixture reason",
            executable: "",
            task_id: "",
            arguments: vec!["Keyguard", "--launch-at-login", "literal $; value"],
        };
        request_on(
            &self.client,
            self.destination,
            operation == Operation::Enable,
            &target,
            Deadlines {
                method: Duration::from_millis(300),
                response: Duration::from_millis(100),
                close: Duration::from_millis(100),
            },
        )
        .await
    }
}

#[cfg(target_os = "linux")]
struct PrivateBus {
    child: std::process::Child,
    address: String,
    _directory: tempfile::TempDir,
}

#[cfg(target_os = "linux")]
impl PrivateBus {
    fn start() -> Self {
        use std::io::BufRead;
        let directory = tempfile::tempdir().unwrap();
        let config = directory.path().join("bus.conf");
        // No service directories: this bus cannot activate a real desktop portal.
        fs::write(&config, format!(
            "<busconfig><type>session</type><listen>unix:tmpdir={}</listen><auth>EXTERNAL</auth><policy context=\"default\"><allow own=\"*\"/><allow send_destination=\"*\"/><allow eavesdrop=\"true\"/></policy></busconfig>",
            directory.path().display(),
        )).unwrap();
        let mut child = std::process::Command::new("dbus-daemon")
            .arg(format!("--config-file={}", config.display()))
            .args(["--nofork", "--print-address=1"])
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::inherit())
            .spawn()
            .expect("portal fixtures require dbus-daemon (install the dbus package)");
        let stdout = child.stdout.take().unwrap();
        let (sender, receiver) = std::sync::mpsc::channel();
        let reader = std::thread::spawn(move || {
            let mut address = String::new();
            let result = std::io::BufReader::new(stdout)
                .read_line(&mut address)
                .map(|_| address);
            let _ = sender.send(result);
        });
        let result = receiver.recv_timeout(Duration::from_secs(5));
        if !matches!(result, Ok(Ok(_))) {
            let _ = child.kill();
            let _ = child.wait();
        }
        let address = result
            .expect("private bus did not start")
            .expect("private bus address read failed");
        reader.join().unwrap();
        assert!(
            address.starts_with("unix:"),
            "private bus returned no Unix address"
        );
        Self {
            child,
            address: address.trim().into(),
            _directory: directory,
        }
    }
}
#[cfg(target_os = "linux")]
impl Drop for PrivateBus {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

fn runtime() -> tokio::runtime::Runtime {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
}

#[test]
fn receives_early_and_late_response_and_preserves_argument_arrays() {
    runtime().block_on(async {
        for scenario in [Scenario::Early(true), Scenario::Late] {
            let fixture = Fixture::new(scenario).await;
            assert_eq!(
                fixture.run(Operation::Enable).await.unwrap(),
                Status::Enabled
            );
            let options = fixture.observed.options.lock().unwrap();
            let options = options.as_ref().unwrap();
            assert!(bool::try_from(options.get("autostart").unwrap()).unwrap());
            assert!(!bool::try_from(options.get("dbus-activatable").unwrap()).unwrap());
            assert_eq!(
                Vec::<String>::try_from(options.get("commandline").unwrap().try_clone().unwrap())
                    .unwrap(),
                ["Keyguard", "--launch-at-login", "literal $; value"]
            );
            assert_eq!(fixture.observed.closes.load(Ordering::SeqCst), 0);
        }
    });
}

#[test]
fn response_failures_never_report_success() {
    runtime().block_on(async {
        for (scenario, expected) in [
            (Scenario::Cancel, Error::Cancelled),
            (Scenario::Refuse, Error::Denied),
            (Scenario::Deny, Error::Denied),
            // Allowed, but the portal failed to save the autostart file.
            (Scenario::Early(false), Error::System),
            (Scenario::MissingResult, Error::System),
            (Scenario::Malformed, Error::System),
            (Scenario::AccessDenied, Error::Denied),
            (Scenario::MissingInterface, Error::Unavailable),
        ] {
            let fixture = Fixture::new(scenario).await;
            assert_eq!(
                fixture.run(Operation::Enable).await.unwrap_err().code,
                expected,
                "{scenario:?}"
            );
        }
    });
}

#[test]
fn timeout_and_wrong_handle_cleanup_are_bounded_even_when_close_hangs() {
    runtime().block_on(async {
        for scenario in [
            Scenario::WrongHandle,
            Scenario::SilentMethod,
            Scenario::SilentResponse,
            Scenario::SilentClose,
            Scenario::FailedClose,
        ] {
            let fixture = Fixture::new(scenario).await;
            let result =
                tokio::time::timeout(Duration::from_secs(3), fixture.run(Operation::Enable))
                    .await
                    .expect("request cleanup exceeded fixture deadline");
            let expected = if matches!(scenario, Scenario::WrongHandle) {
                Error::Unavailable
            } else {
                Error::Cancelled
            };
            assert_eq!(result.unwrap_err().code, expected, "{scenario:?}");
            assert_eq!(
                fixture.observed.closes.load(Ordering::SeqCst),
                1,
                "{scenario:?}"
            );
        }
    });
}

#[test]
fn disable_requires_actual_response() {
    runtime().block_on(async {
        // A refused permission has no autostart entry either.
        for scenario in [Scenario::Early(false), Scenario::Refuse] {
            let fixture = Fixture::new(scenario).await;
            assert_eq!(
                fixture.run(Operation::Disable).await.unwrap(),
                Status::Disabled,
                "{scenario:?}"
            );
            let options = fixture.observed.options.lock().unwrap();
            let requested = options.as_ref().unwrap().get("autostart").unwrap();
            assert!(!bool::try_from(requested).unwrap(), "{scenario:?}");
        }
    });
}

#[test]
fn disconnected_portal_fails_within_deadline() {
    runtime().block_on(async {
        let fixture = Fixture::new(Scenario::Early(true)).await;
        fixture.server.clone().close().await.unwrap();
        assert!(
            tokio::time::timeout(Duration::from_secs(3), fixture.run(Operation::Enable))
                .await
                .unwrap()
                .is_err()
        );
    });
}

#[test]
fn unavailable_properties_are_bounded_without_starting_a_permission_request() {
    runtime().block_on(async {
        let fixture = Fixture::new(Scenario::SilentVersion).await;
        let result = tokio::time::timeout(Duration::from_secs(3), fixture.run(Operation::Enable))
            .await
            .unwrap();
        assert_eq!(result.unwrap_err().code, Error::Cancelled);
        assert_eq!(fixture.observed.requests.load(Ordering::SeqCst), 0);
        assert_eq!(fixture.observed.closes.load(Ordering::SeqCst), 0);
    });
}

struct PendingOperation(Arc<AtomicBool>);
impl Drop for PendingOperation {
    fn drop(&mut self) {
        self.0.store(true, Ordering::SeqCst);
    }
}

#[test]
fn deadline_cancels_a_silent_operation_and_releases_its_resources() {
    let dropped = Arc::new(AtomicBool::new(false));
    let guard = PendingOperation(dropped.clone());
    let result = runtime().block_on(bounded(Duration::from_millis(1), async move {
        let _guard = guard;
        std::future::pending::<()>().await;
    }));
    assert_eq!(result.unwrap_err().code, Error::Cancelled);
    assert!(dropped.load(Ordering::SeqCst));
}

#[test]
fn deadline_preserves_completed_results_and_platform_failures() {
    let runtime = runtime();
    let result = runtime.block_on(bounded(Duration::from_secs(1), async {
        Err::<(), _>(Error::Denied)
    }));
    assert_eq!(result.unwrap(), Err(Error::Denied));
    assert_eq!(
        runtime
            .block_on(bounded(Duration::from_secs(1), async { 42 }))
            .unwrap(),
        42
    );
}

struct Marker {
    root: tempfile::TempDir,
    path: std::path::PathBuf,
}
impl Marker {
    fn new() -> Self {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("keyguard/launch-at-login");
        Self { root, path }
    }

    /// Runs `operation`; the portal answers with `answer` and records each request.
    fn run(
        &self,
        operation: Operation,
        recheck: bool,
        answer: Result<Status, Error>,
        requests: &mut Vec<bool>,
    ) -> Result<Status, Error> {
        with_marker(&self.path, operation, recheck, |autostart| {
            requests.push(autostart);
            answer.map_err(|code| Failure::new(code, "fixture"))
        })
        .map_err(|failure| failure.code)
    }
}

#[test]
fn marker_records_the_granted_request_and_later_reads_skip_the_portal() {
    let marker = Marker::new();
    let mut requests = vec![];
    assert_eq!(
        marker.run(Operation::Status, true, Ok(Status::Enabled), &mut requests),
        Ok(Status::Disabled)
    );
    assert_eq!(
        marker.run(Operation::Enable, false, Ok(Status::Enabled), &mut requests),
        Ok(Status::Enabled)
    );
    assert!(marker.path.is_file());
    assert_eq!(
        marker.run(Operation::Status, false, Err(Error::Denied), &mut requests),
        Ok(Status::Enabled)
    );
    assert_eq!(
        marker.run(Operation::Disable, false, Err(Error::System), &mut requests),
        Err(Error::System)
    );
    assert!(marker.path.is_file());
    assert_eq!(
        marker.run(
            Operation::Disable,
            false,
            Ok(Status::Disabled),
            &mut requests
        ),
        Ok(Status::Disabled)
    );
    assert!(!marker.path.exists());
    assert_eq!(requests, [true, false, false]);
}

#[test]
fn first_read_repeats_the_request_and_a_denial_clears_the_marker() {
    let marker = Marker::new();
    let mut requests = vec![];
    marker
        .run(Operation::Enable, false, Ok(Status::Enabled), &mut requests)
        .unwrap();
    for failure in [Error::Unavailable, Error::Cancelled, Error::System] {
        assert_eq!(
            marker.run(Operation::Status, true, Err(failure), &mut requests),
            Ok(Status::Enabled)
        );
        assert!(marker.path.is_file());
    }
    assert_eq!(
        marker.run(Operation::Status, true, Ok(Status::Enabled), &mut requests),
        Ok(Status::Enabled)
    );
    assert_eq!(
        marker.run(Operation::Status, true, Err(Error::Denied), &mut requests),
        Ok(Status::Disabled)
    );
    assert!(!marker.path.exists());
    assert_eq!(requests, [true; 6]);
}

#[test]
fn denied_enable_clears_the_marker_but_other_failures_keep_it() {
    let marker = Marker::new();
    let mut requests = vec![];
    marker
        .run(Operation::Enable, false, Ok(Status::Enabled), &mut requests)
        .unwrap();
    assert_eq!(
        marker.run(
            Operation::Enable,
            false,
            Err(Error::Cancelled),
            &mut requests
        ),
        Err(Error::Cancelled)
    );
    assert!(marker.path.is_file());
    assert_eq!(
        marker.run(Operation::Enable, false, Err(Error::Denied), &mut requests),
        Err(Error::Denied)
    );
    assert!(!marker.path.exists());
}

#[test]
fn portal_save_failure_is_a_system_error_that_keeps_the_marker() {
    runtime().block_on(async {
        let marker = Marker::new();
        write_marker(&marker.path).unwrap();
        for (operation, expected) in [
            (Operation::Enable, Err(Error::System)),
            // The re-check keeps the recorded answer, as for any other failure.
            (Operation::Status, Ok(Status::Enabled)),
        ] {
            let fixture = Fixture::new(Scenario::Early(false)).await;
            let answer = fixture.run(Operation::Enable).await;
            let result = with_marker(&marker.path, operation, true, |autostart| {
                assert!(autostart, "{operation:?}");
                answer
            });
            assert_eq!(result.map_err(|failure| failure.code), expected);
            assert!(marker.path.is_file(), "{operation:?}");
        }
        // Disabling expects no autostart file.
        let fixture = Fixture::new(Scenario::Early(false)).await;
        let answer = fixture.run(Operation::Disable).await;
        let result = with_marker(&marker.path, Operation::Disable, false, |autostart| {
            assert!(!autostart);
            answer
        });
        assert_eq!(result.unwrap(), Status::Disabled);
        assert!(!marker.path.exists());
    });
}

#[test]
fn enable_replaces_a_linked_marker_without_writing_through_it() {
    let marker = Marker::new();
    let mut requests = vec![];
    let target = marker.root.path().join("unrelated");
    fs::write(&target, "sentinel").unwrap();
    fs::create_dir_all(marker.path.parent().unwrap()).unwrap();
    std::os::unix::fs::symlink(&target, &marker.path).unwrap();
    assert_eq!(
        marker.run(Operation::Enable, false, Ok(Status::Enabled), &mut requests),
        Ok(Status::Enabled)
    );
    assert_eq!(fs::read_to_string(&target).unwrap(), "sentinel");
    assert!(fs::symlink_metadata(&marker.path).unwrap().is_file());
    // An existing marker file is kept as is.
    fs::write(&marker.path, "kept").unwrap();
    marker
        .run(Operation::Enable, false, Ok(Status::Enabled), &mut requests)
        .unwrap();
    assert_eq!(fs::read_to_string(&marker.path).unwrap(), "kept");
}
