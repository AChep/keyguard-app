//! The stream of a browser extension, relayed by the agent for the bundled
//! browser connector. A copy of this test harness stands in for the
//! connector: it is bundled next to a copy of the agent.
use super::*;

const ORIGIN: &str = "chrome-extension://abcdefghijklmnopabcdefghijklmnop/";
const MAX_FRAME: usize = 65_535;

async fn read_frame(stream: &mut (impl AsyncRead + Unpin)) -> Option<Vec<u8>> {
    let size = stream.read_u32().await.ok()? as usize;
    assert!((1..=MAX_FRAME).contains(&size), "frame of {size} bytes");
    let mut bytes = vec![0; size];
    stream.read_exact(&mut bytes).await.ok()?;
    Some(bytes)
}

async fn try_write_frame(
    stream: &mut (impl AsyncWrite + Unpin),
    bytes: &[u8],
) -> std::io::Result<()> {
    stream.write_u32(bytes.len() as u32).await?;
    stream.write_all(bytes).await
}

async fn write_frame(stream: &mut (impl AsyncWrite + Unpin), bytes: &[u8]) {
    try_write_frame(stream, bytes).await.expect("frame");
}

/// The app's end of an opened stream: it pushes the origin first, then
/// echoes each frame, or in `oversized` mode sends a frame that is too large.
/// Returns once the agent closes the stream.
pub(super) async fn serve_stream(stream: &mut UnixStream, origin: &str, oversized: bool) {
    let mut written = try_write_frame(stream, origin.as_bytes()).await;
    if oversized && written.is_ok() {
        written = stream.write_u32(MAX_FRAME as u32 + 1).await;
    }
    while written.is_ok()
        && let Some(frame) = read_frame(stream).await
    {
        written = try_write_frame(stream, &frame).await;
    }
}

/// The connector as a browser starts it: a copy of this test harness, next
/// to a copy of the agent, that relays between the agent and the test.
#[test]
fn browser_host_fixture() {
    use std::{net::Shutdown, os::unix::net::UnixStream};
    let (Ok(agent), Ok(test)) = (
        std::env::var("KEYGUARD_BROWSER_TEST_AGENT"),
        std::env::var("KEYGUARD_BROWSER_TEST_RELAY"),
    ) else {
        return;
    };
    let agent = UnixStream::connect(agent).unwrap();
    let test = UnixStream::connect(test).unwrap();
    let copy = |mut from: UnixStream, mut to: UnixStream| {
        std::thread::spawn(move || {
            let _ = std::io::copy(&mut from, &mut to);
            let _ = to.shutdown(Shutdown::Write);
        })
    };
    let to_test = copy(agent.try_clone().unwrap(), test.try_clone().unwrap());
    let to_agent = copy(test, agent);
    to_test.join().unwrap();
    to_agent.join().unwrap();
}

/// An app with a copy of the agent, bundled with a connector.
struct Bundle {
    app: App,
    agent: PathBuf,
    host: PathBuf,
    relay: UnixListener,
    relay_path: PathBuf,
}

impl Bundle {
    async fn new() -> Self {
        let app = App::new().await;
        let directory = app.directory.path().join("bundle");
        std::fs::create_dir(&directory).unwrap();
        let agent = directory.join("keyguard-cli-agent");
        std::fs::copy(env!("CARGO_BIN_EXE_keyguard-cli-agent"), &agent).unwrap();
        let host = directory.join("keyguard-browser-host");
        std::fs::copy(std::env::current_exe().unwrap(), &host).unwrap();
        let relay_path = app.directory.path().join("r");
        let relay = UnixListener::bind(&relay_path).unwrap();
        Self {
            app,
            agent,
            host,
            relay,
            relay_path,
        }
    }

    /// Starts the connector, and returns the test's end of it.
    async fn connect(&self) -> (UnixStream, Child) {
        let host = Command::new(&self.host)
            .args(["--exact", "browser_relay::browser_host_fixture"])
            .env("KEYGUARD_BROWSER_TEST_AGENT", &self.app.public)
            .env("KEYGUARD_BROWSER_TEST_RELAY", &self.relay_path)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .kill_on_drop(true)
            .spawn()
            .unwrap();
        let (stream, _) = timeout(self.relay.accept()).await.unwrap();
        (stream, host)
    }

    /// Starts the connector, and opens the stream of the extension.
    async fn open(&self) -> (UnixStream, Child) {
        let (mut stream, host) = self.connect().await;
        write_json(
            &mut stream,
            json!({"type":"browser","origin":ORIGIN,"hostRevision":1}),
        )
        .await;
        assert_eq!(timeout(read_json(&mut stream)).await, json!({"ok":true}));
        (stream, host)
    }
}

#[tokio::test]
async fn the_bundled_connector_streams_frames_both_ways() {
    let bundle = Bundle::new().await;
    let mut agent = bundle.app.launch_agent(&bundle.agent).await;
    let (mut stream, _host) = bundle.open().await;
    // The app pushes a frame of its own, then echoes.
    assert_eq!(
        timeout(read_frame(&mut stream)).await.unwrap(),
        ORIGIN.as_bytes()
    );
    let largest = vec![7; MAX_FRAME];
    write_frame(&mut stream, &largest).await;
    write_frame(&mut stream, b"second").await;
    assert_eq!(timeout(read_frame(&mut stream)).await.unwrap(), largest);
    assert_eq!(timeout(read_frame(&mut stream)).await.unwrap(), b"second");
    // Closing the connector closes the app's stream.
    drop(stream);
    timeout(bundle.app.canceled.notified()).await;
    drop(agent.stdin.take());
    assert!(timeout(agent.wait()).await.unwrap().success());
}

#[tokio::test]
async fn an_oversized_frame_closes_the_stream_on_both_sides() {
    let bundle = Bundle::new().await;
    let mut agent = bundle.app.launch_agent(&bundle.agent).await;
    // From the connector.
    let (mut stream, _host) = bundle.open().await;
    assert!(timeout(read_frame(&mut stream)).await.is_some());
    stream.write_u32(MAX_FRAME as u32 + 1).await.unwrap();
    timeout(bundle.app.canceled.notified()).await;
    assert!(timeout(stream.read_u8()).await.is_err());
    // From the app.
    bundle.app.mode.store(10, Ordering::SeqCst);
    let (mut stream, _host) = bundle.open().await;
    assert!(timeout(read_frame(&mut stream)).await.is_some());
    assert!(timeout(stream.read_u8()).await.is_err());
    timeout(bundle.app.canceled.notified()).await;
    drop(agent.stdin.take());
    assert!(timeout(agent.wait()).await.unwrap().success());
}

#[tokio::test]
async fn the_app_refusing_the_stream_is_passed_on() {
    let bundle = Bundle::new().await;
    bundle.app.mode.store(9, Ordering::SeqCst);
    let mut agent = bundle.app.launch_agent(&bundle.agent).await;
    let (mut stream, _host) = bundle.connect().await;
    write_json(
        &mut stream,
        json!({"type":"browser","origin":"keyguard@artemchep.com","hostRevision":1}),
    )
    .await;
    assert_eq!(
        timeout(read_json(&mut stream)).await,
        json!({"ok":false,"error":"integration_disabled"})
    );
    assert!(timeout(stream.read_u8()).await.is_err());
    drop(agent.stdin.take());
    assert!(timeout(agent.wait()).await.unwrap().success());
}

#[tokio::test]
async fn the_bundled_connector_gets_no_values_of_the_cli() {
    let bundle = Bundle::new().await;
    let mut agent = bundle.app.launch_agent(&bundle.agent).await;
    // The connector is bundled, but it is not the CLI.
    let (mut stream, _host) = bundle.connect().await;
    write_json(
        &mut stream,
        json!({"type":"cli","command":"run","request":{"environment":"env","command":["env"],"workingDirectory":"/"}}),
    )
    .await;
    assert_eq!(
        timeout(read_json(&mut stream)).await,
        json!({"ok":false,"error":"unverified_cli"})
    );
    drop(agent.stdin.take());
    assert!(timeout(agent.wait()).await.unwrap().success());
}

#[tokio::test]
async fn only_the_bundled_connector_opens_a_stream() {
    // Inside Flatpak, the agent forwards an unverified connector on purpose.
    if Path::new("/.flatpak-info").exists() {
        return;
    }
    let app = App::new().await;
    let mut agent = app.launch().await;
    // This test is not the connector.
    let mut stream = UnixStream::connect(&app.public).await.unwrap();
    write_json(
        &mut stream,
        json!({"type":"browser","origin":ORIGIN,"hostRevision":1}),
    )
    .await;
    assert_eq!(
        timeout(read_json(&mut stream)).await,
        json!({"ok":false,"error":"unverified_host"})
    );
    assert!(timeout(stream.read_u8()).await.is_err());
    // Malformed frames are not answered.
    for frame in [
        json!({"type":"browser","origin":"chrome-extension://x/","hostRevision":1}),
        json!({"type":"browser","origin":ORIGIN,"hostRevision":1,"caller":{"pid":1}}),
    ] {
        let mut stream = UnixStream::connect(&app.public).await.unwrap();
        write_json(&mut stream, frame).await;
        assert!(timeout(stream.read_u8()).await.is_err());
    }
    drop(agent.stdin.take());
    assert!(timeout(agent.wait()).await.unwrap().success());
}
