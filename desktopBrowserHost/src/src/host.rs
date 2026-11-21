//! The connection to the app. The host opens a browser stream on the CLI
//! agent's socket, relays Noise frames both ways while it is open, and
//! reconnects for as long as the browser keeps the host running.
use crate::{
    native::{Incoming, Outgoing, parse_message, read_message, write_message},
    status::{self, Status},
};
use anyhow::Result;
use keyguard_cli_protocol::{
    browser::BrowserRequest,
    frame::{read_browser_frame, read_frame, write_browser_frame, write_frame},
    public::PublicFrameRef,
    socket::client_socket,
    transport::{self, BoxStream},
};
use std::{
    path::{Path, PathBuf},
    sync::{
        Arc,
        atomic::{AtomicU64, Ordering},
    },
    time::Duration,
};
use tokio::{
    io::{AsyncRead, AsyncWrite},
    sync::mpsc,
};

/// The revision of this host, so that the app can ask for an update.
const HOST_REVISION: u32 = 1;

/// Bounds the wait for the agent's socket and for the app's answer.
const OPEN_TIMEOUT: Duration = transport::CONNECT_TIMEOUT;

/// Bounds the wait for a socket that the agent is about to bind. It looks
/// for new copies of the connector every 2 seconds.
const SOCKET_WAIT: Duration = Duration::from_secs(3);

pub(crate) struct Host {
    /// The extension, as its browser names it.
    pub(crate) origin: String,
    /// The CLI agent's socket. Without one, the host finds it as the CLI
    /// does: `KEYGUARD_CLI_SOCKET`, then the platform's default.
    pub(crate) socket: Option<PathBuf>,
    /// Whether to wait up to [`SOCKET_WAIT`] for `socket`, rather than
    /// report the app as unavailable at once.
    pub(crate) wait_for_socket: bool,
    /// The wait before the next attempt to connect.
    pub(crate) retry: Duration,
}

impl Host {
    pub(crate) fn new(origin: String) -> Self {
        // A copy in a Flatpak browser's sandbox reaches the agent only next
        // to itself. The agent listens there soon after the copy appears.
        #[cfg(target_os = "linux")]
        let socket = crate::flatpak::current_flatpak_browser_socket();
        #[cfg(not(target_os = "linux"))]
        let socket = None;
        Self {
            origin,
            wait_for_socket: socket.is_some(),
            socket,
            retry: Duration::from_secs(2),
        }
    }
}

/// Serves the extension until the browser closes `stdin`. Fails on a message
/// that the host can not read, or once it can not write to `stdout` or to the
/// app.
pub(crate) async fn run<R, W>(host: &Host, stdin: R, mut stdout: W) -> Result<()>
where
    R: AsyncRead + Unpin + Send + 'static,
    W: AsyncWrite + Unpin,
{
    let mut extension = Extension::spawn(stdin);
    let mut reporter = Reporter::default();
    loop {
        let Some(opened) = extension.drop_while(open(host)).await? else {
            return Ok(());
        };
        match opened {
            Ok(app) => {
                // Before `ready`: the frames sent in reply belong to the new session.
                extension.start_session();
                reporter.report(&mut stdout, Status::Ready).await?;
                if relay(app, &mut extension, &mut stdout).await? == Closed::Browser {
                    return Ok(());
                }
                // The frames that come from now on are dropped.
                extension.end_session();
                reporter.report(&mut stdout, Status::AppUnavailable).await?;
            }
            Err(status) => reporter.report(&mut stdout, status).await?,
        }
        let retry = tokio::time::sleep(host.retry);
        if extension.drop_while(retry).await?.is_none() {
            return Ok(());
        }
    }
}

/// Connects to the agent and opens the stream of the extension.
async fn open(host: &Host) -> Result<BoxStream, Status> {
    let open = async {
        let socket = client_socket(host.socket.clone()).ok()?;
        let mut app = connect(host, &socket).await?;
        let request = BrowserRequest {
            origin: host.origin.clone(),
            host_revision: HOST_REVISION,
        };
        let frame = serde_json::to_vec(&PublicFrameRef::Browser(&request)).ok()?;
        write_frame(&mut *app, &frame).await.ok()?;
        // The agent closes the stream when a frame comes before its answer.
        let answer = read_frame(&mut *app).await.ok()?;
        Some(status::opened(&answer).map(|()| app))
    };
    match tokio::time::timeout(OPEN_TIMEOUT, open).await {
        Ok(Some(opened)) => opened,
        _ => Err(Status::AppUnavailable),
    }
}

/// Connects to the agent at `socket`, trying again for a while if the host
/// waits for it.
async fn connect(host: &Host, socket: &Path) -> Option<BoxStream> {
    let deadline = tokio::time::Instant::now() + SOCKET_WAIT;
    loop {
        match transport::connect(socket, None).await {
            Ok(app) => return Some(app),
            Err(_) if host.wait_for_socket && tokio::time::Instant::now() < deadline => {
                tokio::time::sleep(Duration::from_millis(250)).await;
            }
            Err(_) => return None,
        }
    }
}

#[derive(PartialEq, Eq)]
enum Closed {
    App,
    Browser,
}

/// Relays frames both ways until either side closes. The app closing also
/// covers a frame that is not one Noise message. A write to the app that fails
/// fails the relay: ending it may cut a message to `stdout` short, after which
/// the host must write nothing.
async fn relay<W: AsyncWrite + Unpin>(
    app: BoxStream,
    extension: &mut Extension,
    stdout: &mut W,
) -> Result<Closed> {
    let (mut app_read, mut app_write) = tokio::io::split(app);
    // A frame read in part is lost when its read is canceled, so the app's
    // frames are read in a loop of their own that only ends with the relay.
    let to_extension = async {
        while let Ok(frame) = read_browser_frame(&mut app_read).await {
            write_message(stdout, &Outgoing::noise(&frame)).await?;
        }
        anyhow::Ok(Closed::App)
    };
    let to_app = async {
        while let Some(frame) = extension.next().await? {
            write_browser_frame(&mut app_write, &frame).await?;
        }
        Ok(Closed::Browser)
    };
    tokio::select! {
        closed = to_extension => closed,
        closed = to_app => closed,
    }
}

/// The extension's Noise frames. A task of their own reads them, so that a
/// read of the browser's stream is never canceled in the middle of a message.
///
/// A frame belongs to the session that was open when the host read it; frames
/// read while none is open are dropped.
struct Extension {
    /// Each frame with the number of its session.
    frames: mpsc::Receiver<Result<(u64, Vec<u8>)>>,
    /// The number of the open session, or 0 while none is open.
    open: Arc<AtomicU64>,
    /// How many sessions have started. They are numbered from 1.
    sessions: u64,
}

impl Extension {
    /// Starts the reader, with no session open.
    fn spawn<R: AsyncRead + Unpin + Send + 'static>(mut stdin: R) -> Self {
        let (sender, frames) = mpsc::channel(4);
        let open = Arc::new(AtomicU64::new(0));
        let current = open.clone();
        tokio::spawn(async move {
            loop {
                let message = match read_message(&mut stdin).await {
                    Ok(None) => return,
                    Ok(Some(message)) => parse_message(&message),
                    Err(error) => Err(error),
                };
                let frame = match message {
                    // Dropped here while no session is open, so that stale
                    // frames never stop the reads. A message written partly
                    // before and partly after a whole retry interval would
                    // take the next session's number: browsers write each
                    // message at once, and sessions are at least 2 s apart.
                    Ok(Incoming::Noise(frame)) => match current.load(Ordering::SeqCst) {
                        0 => continue,
                        session => (session, frame),
                    },
                    Ok(Incoming::Ignored) => continue,
                    // A message the host can not read stops the reads.
                    Err(error) => {
                        let _ = sender.send(Err(error)).await;
                        return;
                    }
                };
                if sender.send(Ok(frame)).await.is_err() {
                    return;
                }
            }
        });
        Self {
            frames,
            open,
            sessions: 0,
        }
    }

    /// Opens a new session. The frames read from now on belong to it.
    fn start_session(&mut self) {
        self.sessions += 1;
        self.open.store(self.sessions, Ordering::SeqCst);
    }

    /// Ends the open session. The frames read from now on are dropped.
    fn end_session(&self) {
        self.open.store(0, Ordering::SeqCst);
    }

    /// The next Noise frame of the open session, or `None` once the browser
    /// closes the stream. Frames of other sessions are skipped.
    async fn next(&mut self) -> Result<Option<Vec<u8>>> {
        let open = self.open.load(Ordering::SeqCst);
        while let Some((session, frame)) = self.frames.recv().await.transpose()? {
            if session == open {
                return Ok(Some(frame));
            }
        }
        Ok(None)
    }

    /// Runs `future` while no session is open. The reader drops the frames
    /// that arrive meanwhile, so this only skips those the last session left,
    /// and notices the browser closing the stream, or a message the host can
    /// not read. `None` once the browser closes the stream.
    async fn drop_while<T>(&mut self, future: impl Future<Output = T>) -> Result<Option<T>> {
        tokio::pin!(future);
        loop {
            tokio::select! {
                frame = self.next() => if frame?.is_none() {
                    return Ok(None);
                },
                output = &mut future => return Ok(Some(output)),
            }
        }
    }
}

/// Sends `ready` for every new connection, and any other status only when it
/// changes.
#[derive(Default)]
struct Reporter(Option<Status>);

impl Reporter {
    async fn report<W: AsyncWrite + Unpin>(
        &mut self,
        stdout: &mut W,
        status: Status,
    ) -> Result<()> {
        if status != Status::Ready && self.0 == Some(status) {
            return Ok(());
        }
        self.0 = Some(status);
        crate::log(status.as_str());
        write_message(stdout, &Outgoing::Status { status }).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::native::MAX_INCOMING;
    use base64::{Engine, engine::general_purpose::STANDARD};
    use keyguard_cli_protocol::frame::BROWSER_MAX_FRAME;
    use serde_json::{Value, json};
    use std::{
        io,
        pin::Pin,
        task::{Context, Poll},
    };
    use tokio::{
        io::{AsyncReadExt, AsyncWriteExt, DuplexStream, ReadBuf, duplex},
        task::JoinHandle,
    };

    const ORIGIN: &str = "chrome-extension://abcdefghijklmnopabcdefghijklmnop/";

    async fn timeout<T>(future: impl Future<Output = T>) -> T {
        tokio::time::timeout(Duration::from_secs(10), future)
            .await
            .expect("timed out")
    }

    /// The browser's ends of a running host.
    struct Browser {
        stdin: DuplexStream,
        stdout: DuplexStream,
        host: JoinHandle<Result<()>>,
    }

    impl Browser {
        /// Starts a host that retries at once.
        fn start(socket: PathBuf) -> Self {
            Self::spawn(Host {
                origin: ORIGIN.into(),
                socket: Some(socket),
                wait_for_socket: false,
                retry: Duration::from_millis(10),
            })
        }

        fn spawn(host: Host) -> Self {
            let (stdin, host_stdin) = duplex(4 * MAX_INCOMING);
            let (host_stdout, stdout) = duplex(4 * MAX_INCOMING);
            let host = tokio::spawn(async move { run(&host, host_stdin, host_stdout).await });
            Self {
                stdin,
                stdout,
                host,
            }
        }

        #[cfg(unix)]
        async fn send(&mut self, value: Value) {
            write_native(&mut self.stdin, &value).await;
        }

        #[cfg(unix)]
        async fn send_noise(&mut self, frame: &[u8]) {
            write_noise(&mut self.stdin, frame).await;
        }

        async fn receive(&mut self) -> Value {
            timeout(read_native(&mut self.stdout)).await
        }

        async fn receive_status(&mut self) -> Value {
            let message = self.receive().await;
            assert_eq!(message["type"], "status", "{message}");
            message["status"].clone()
        }

        /// Closes stdin, as the browser does, and returns how the host ended.
        async fn close(self) -> Result<()> {
            drop(self.stdin);
            timeout(self.host).await.unwrap()
        }
    }

    /// Writes a message as the browser frames it.
    async fn write_native(stdin: &mut DuplexStream, value: &Value) {
        let json = serde_json::to_vec(value).unwrap();
        stdin
            .write_all(&(json.len() as u32).to_ne_bytes())
            .await
            .unwrap();
        stdin.write_all(&json).await.unwrap();
    }

    /// Reads a message as the browser does.
    async fn read_native(stdout: &mut DuplexStream) -> Value {
        let mut length = [0; 4];
        stdout.read_exact(&mut length).await.unwrap();
        let mut json = vec![0; u32::from_ne_bytes(length) as usize];
        stdout.read_exact(&mut json).await.unwrap();
        serde_json::from_slice(&json).unwrap()
    }

    /// A connection to the app that reads nothing and fails every write.
    struct Unwritable;

    impl AsyncRead for Unwritable {
        fn poll_read(
            self: Pin<&mut Self>,
            _: &mut Context<'_>,
            _: &mut ReadBuf<'_>,
        ) -> Poll<io::Result<()>> {
            Poll::Pending
        }
    }

    impl AsyncWrite for Unwritable {
        fn poll_write(
            self: Pin<&mut Self>,
            _: &mut Context<'_>,
            _: &[u8],
        ) -> Poll<io::Result<usize>> {
            Poll::Ready(Err(io::ErrorKind::BrokenPipe.into()))
        }

        fn poll_flush(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<io::Result<()>> {
            Poll::Ready(Ok(()))
        }

        fn poll_shutdown(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<io::Result<()>> {
            Poll::Ready(Ok(()))
        }
    }

    #[tokio::test]
    async fn a_failed_write_to_the_app_stops_the_host() {
        let (mut stdin, host_stdin) = duplex(4 * MAX_INCOMING);
        let mut extension = Extension::spawn(host_stdin);
        extension.start_session();
        write_noise(&mut stdin, &[1]).await;
        let mut stdout = Vec::new();
        // The relay fails rather than report the app as closed: the host
        // never writes after a message it may have cut short.
        let relayed = timeout(relay(Box::new(Unwritable), &mut extension, &mut stdout)).await;
        assert!(relayed.is_err());
        assert!(stdout.is_empty());
    }

    #[tokio::test]
    async fn a_frame_of_the_app_read_in_part_survives_frames_of_the_extension() {
        let frame = b"partial";
        let mut bytes = (frame.len() as u32).to_be_bytes().to_vec();
        bytes.extend_from_slice(frame);
        // Part of the length, then the length and part of the frame.
        for split in [2, 4 + 3] {
            let (mut stdin, host_stdin) = duplex(4 * MAX_INCOMING);
            let (mut host_stdout, mut stdout) = duplex(4 * MAX_INCOMING);
            let (mut app, host_app) = duplex(4 * BROWSER_MAX_FRAME);
            let mut extension = Extension::spawn(host_stdin);
            extension.start_session();
            let session = tokio::spawn(async move {
                relay(Box::new(host_app), &mut extension, &mut host_stdout).await
            });
            app.write_all(&bytes[..split]).await.unwrap();
            // The extension's frames reach the app meanwhile. However a relay
            // polls its two directions, the second frame comes while the
            // app's frame is read in part.
            for noise in [[1_u8], [2]] {
                write_noise(&mut stdin, &noise).await;
                assert_eq!(
                    &timeout(read_browser_frame(&mut app)).await.unwrap()[..],
                    noise
                );
            }
            app.write_all(&bytes[split..]).await.unwrap();
            write_browser_frame(&mut app, b"next").await.unwrap();
            for frame in [&frame[..], b"next"] {
                assert_eq!(
                    timeout(read_native(&mut stdout)).await,
                    json!({"type":"noise","data":STANDARD.encode(frame)}),
                    "{split}"
                );
            }
            drop(app);
            assert!(timeout(session).await.unwrap().unwrap() == Closed::App);
        }
    }

    /// A session reads `stale` frames and ends before they are taken, then
    /// the next one starts. Only the next session's frame reaches it.
    async fn assert_only_session_frames_are_relayed(stale: u8) {
        let (mut stdin, host_stdin) = duplex(4 * MAX_INCOMING);
        let mut extension = Extension::spawn(host_stdin);
        extension.start_session();
        for frame in 1..=stale {
            write_noise(&mut stdin, &[frame]).await;
        }
        // The reader queues the frames that fit, then holds the next one
        // until there is room.
        let queued = usize::from(stale).min(extension.frames.max_capacity());
        wait_until_queued(&extension, queued).await;
        extension.end_session();
        // The next connection opens while the frames are still there.
        let opened = extension.drop_while(std::future::ready(())).await;
        assert!(matches!(opened, Ok(Some(()))));
        extension.start_session();
        write_noise(&mut stdin, &[0]).await;
        assert_eq!(timeout(extension.next()).await.unwrap(), Some(vec![0]));
        drop(stdin);
        assert_eq!(timeout(extension.next()).await.unwrap(), None);
    }

    async fn wait_until_queued(extension: &Extension, queued: usize) {
        timeout(async {
            while extension.frames.len() < queued {
                tokio::task::yield_now().await;
            }
        })
        .await;
    }

    async fn write_noise(stdin: &mut DuplexStream, frame: &[u8]) {
        write_native(
            stdin,
            &json!({"type":"noise","data":STANDARD.encode(frame)}),
        )
        .await;
    }

    #[tokio::test]
    async fn frames_queued_before_a_session_are_dropped() {
        assert_only_session_frames_are_relayed(1).await;
    }

    #[tokio::test]
    async fn a_frame_waiting_for_room_before_a_session_is_dropped() {
        // Four frames fill the queue, and the fifth waits in the reader.
        assert_only_session_frames_are_relayed(5).await;
    }

    #[tokio::test]
    async fn frames_left_by_a_session_are_not_relayed_to_the_next() {
        let (mut stdin, host_stdin) = duplex(4 * MAX_INCOMING);
        let mut extension = Extension::spawn(host_stdin);
        extension.start_session();
        for frame in 1..=2 {
            write_noise(&mut stdin, &[frame]).await;
        }
        wait_until_queued(&extension, 2).await;
        // The session ends, and the next one starts before anything takes
        // the frames.
        extension.end_session();
        extension.start_session();
        write_noise(&mut stdin, &[0]).await;
        assert_eq!(timeout(extension.next()).await.unwrap(), Some(vec![0]));
        drop(stdin);
        assert_eq!(timeout(extension.next()).await.unwrap(), None);
    }

    #[tokio::test]
    async fn a_backlog_read_while_no_session_is_open_is_dropped() {
        // Smaller than two messages: a write ends only once the reader has
        // read most of what came before it.
        const BUFFER: usize = 64;
        let (mut stdin, host_stdin) = duplex(BUFFER);
        let mut extension = Extension::spawn(host_stdin);
        // More frames than the queue holds, and nothing takes them from it.
        timeout(async {
            for frame in 1..=8 {
                write_noise(&mut stdin, &[frame]).await;
            }
            // Larger than the buffer, so its write ends only after the reader
            // has read all of the last frame, and dropped it.
            let padding = "x".repeat(2 * BUFFER);
            write_native(&mut stdin, &json!({"type":"padding","data":padding})).await;
        })
        .await;
        extension.start_session();
        write_noise(&mut stdin, &[0]).await;
        assert_eq!(timeout(extension.next()).await.unwrap(), Some(vec![0]));
        drop(stdin);
        assert_eq!(timeout(extension.next()).await.unwrap(), None);
    }

    #[tokio::test]
    async fn a_missing_socket_is_an_absent_app() {
        let directory = tempfile::tempdir().unwrap();
        let mut browser = Browser::start(directory.path().join("absent"));
        assert_eq!(browser.receive_status().await, "app_unavailable");
        browser.close().await.unwrap();
    }

    /// Sessions on the CLI agent's socket, which the tests serve as a Unix
    /// socket.
    #[cfg(unix)]
    mod socket {
        use super::*;
        use tokio::{
            net::{UnixListener, UnixStream},
            time::Instant,
        };

        /// The CLI agent's socket, served by the test.
        struct Agent {
            listener: UnixListener,
            socket: PathBuf,
            _directory: tempfile::TempDir,
        }

        impl Agent {
            fn new() -> Self {
                let directory = tempfile::tempdir().unwrap();
                let socket = directory.path().join("s");
                Self {
                    listener: UnixListener::bind(&socket).unwrap(),
                    socket,
                    _directory: directory,
                }
            }

            /// Accepts the host and reads its `browser` frame.
            async fn accept(&self) -> UnixStream {
                let (mut stream, _) = timeout(self.listener.accept()).await.unwrap();
                let frame = timeout(read_frame(&mut stream)).await.unwrap();
                assert_eq!(
                    serde_json::from_slice::<Value>(&frame).unwrap(),
                    json!({"type":"browser","origin":ORIGIN,"hostRevision":1})
                );
                stream
            }

            /// Accepts the host and answers its `browser` frame.
            async fn answer(&self, answer: Value) -> UnixStream {
                let mut stream = self.accept().await;
                write_json(&mut stream, &answer).await;
                stream
            }
        }

        async fn write_json(stream: &mut UnixStream, value: &Value) {
            write_frame(stream, &serde_json::to_vec(value).unwrap())
                .await
                .unwrap();
        }

        #[tokio::test]
        async fn a_host_that_waits_for_its_socket_connects_once_it_appears() {
            let directory = tempfile::tempdir().unwrap();
            let socket = directory.path().join("s");
            let mut browser = Browser::spawn(Host {
                origin: ORIGIN.into(),
                socket: Some(socket.clone()),
                wait_for_socket: true,
                retry: Duration::from_secs(60),
            });
            tokio::time::sleep(Duration::from_millis(600)).await;
            let agent = Agent {
                listener: UnixListener::bind(&socket).unwrap(),
                socket,
                _directory: directory,
            };
            let _app = agent.answer(json!({"ok":true})).await;
            // Not `app_unavailable` first.
            assert_eq!(browser.receive_status().await, "ready");
            browser.close().await.unwrap();
        }

        #[tokio::test]
        async fn frames_are_relayed_once_the_app_answers() {
            let agent = Agent::new();
            let mut browser = Browser::start(agent.socket.clone());
            let mut app = agent.accept().await;
            // Before the answer the host drops frames, and never writes.
            browser.send_noise(&[1]).await;
            let early = tokio::time::timeout(Duration::from_millis(100), app.read_u8()).await;
            assert!(early.is_err(), "{early:?}");
            write_json(&mut app, &json!({"ok":true})).await;
            assert_eq!(browser.receive_status().await, "ready");

            browser.send_noise(&[2]).await;
            assert_eq!(
                &timeout(read_browser_frame(&mut app)).await.unwrap()[..],
                [2]
            );
            let largest = vec![7; BROWSER_MAX_FRAME];
            write_browser_frame(&mut app, &largest).await.unwrap();
            assert_eq!(
                browser.receive().await,
                json!({"type":"noise","data":STANDARD.encode(&largest)})
            );
            // Messages of an unknown type are ignored.
            browser.send(json!({"type":"future"})).await;
            browser.send_noise(&largest).await;
            assert_eq!(
                timeout(read_browser_frame(&mut app)).await.unwrap()[..],
                largest
            );

            // The app closing ends the session; the next connection starts one.
            drop(app);
            assert_eq!(browser.receive_status().await, "app_unavailable");
            let mut app = agent.answer(json!({"ok":true})).await;
            assert_eq!(browser.receive_status().await, "ready");
            browser.send_noise(&[3]).await;
            assert_eq!(
                &timeout(read_browser_frame(&mut app)).await.unwrap()[..],
                [3]
            );

            // The browser closing stops the host, and closes the stream.
            browser.close().await.unwrap();
            assert!(timeout(app.read_u8()).await.is_err());
        }

        #[tokio::test]
        async fn refusals_are_reported_when_they_change() {
            let agent = Agent::new();
            let mut browser = Browser::start(agent.socket.clone());
            for (answer, status) in [
                ("integration_disabled", Some("integration_disabled")),
                ("integration_disabled", None),
                ("too_many_sessions", Some("too_many_sessions")),
                ("unsupported_command", Some("update_required")),
                ("unverified_host", Some("unverified_host")),
                ("unavailable", Some("app_unavailable")),
                ("request_failed", None),
            ] {
                agent.answer(json!({"ok":false,"error":answer})).await;
                if let Some(status) = status {
                    assert_eq!(browser.receive_status().await, status, "{answer}");
                }
            }
            // Each connection is ready, even after a session that just ended.
            drop(agent.answer(json!({"ok":true})).await);
            assert_eq!(browser.receive_status().await, "ready");
            assert_eq!(browser.receive_status().await, "app_unavailable");
            let _app = agent.answer(json!({"ok":true})).await;
            assert_eq!(browser.receive_status().await, "ready");
            browser.close().await.unwrap();
        }

        #[tokio::test]
        async fn an_invalid_frame_of_the_app_ends_only_the_session() {
            let agent = Agent::new();
            let mut browser = Browser::start(agent.socket.clone());
            for length in [0, BROWSER_MAX_FRAME as u32 + 1] {
                let mut app = agent.answer(json!({"ok":true})).await;
                assert_eq!(browser.receive_status().await, "ready");
                // Not one Noise message.
                app.write_u32(length).await.unwrap();
                assert_eq!(
                    browser.receive_status().await,
                    "app_unavailable",
                    "{length}"
                );
                // The host closed the stream, and connects again.
                assert!(timeout(app.read_u8()).await.is_err());
            }
            let _app = agent.answer(json!({"ok":true})).await;
            assert_eq!(browser.receive_status().await, "ready");
            browser.close().await.unwrap();
        }

        #[tokio::test]
        async fn an_absent_app_is_reported_once() {
            let agent = Agent::new();
            let mut browser = Browser::start(agent.socket.clone());
            // An agent that closes the connection is an absent app.
            drop(agent.accept().await);
            assert_eq!(browser.receive_status().await, "app_unavailable");
            // Each connection is a retry, and none repeats the status.
            for _ in 0..3 {
                drop(agent.accept().await);
            }
            let _app = agent.answer(json!({"ok":true})).await;
            assert_eq!(browser.receive_status().await, "ready");
            browser.close().await.unwrap();
        }

        /// The protocol's timing: an app that does not answer in 30 seconds
        /// is unavailable, and the host retries every 2 seconds. The paused
        /// clock only advances to the next timer, so each wait ends on a
        /// timer of the host or of the test.
        #[tokio::test(start_paused = true)]
        async fn an_app_that_does_not_answer_times_out_and_is_retried() {
            let (deadline, retry) = (Duration::from_secs(30), Duration::from_secs(2));
            // Longer than the host's timers, which fire first.
            let wait = deadline + retry;
            let agent = Agent::new();
            let started = Instant::now();
            let mut browser = Browser::spawn(Host {
                socket: Some(agent.socket.clone()),
                ..Host::new(ORIGIN.into())
            });
            // The app never answers.
            let (_app, _) = tokio::time::timeout(wait, agent.listener.accept())
                .await
                .unwrap()
                .unwrap();
            let status = tokio::time::timeout(wait, read_native(&mut browser.stdout)).await;
            assert_eq!(
                status.unwrap(),
                json!({"type":"status","status":"app_unavailable"})
            );
            let elapsed = started.elapsed();
            assert!(
                elapsed >= deadline && elapsed < deadline + retry,
                "{elapsed:?}"
            );

            // The next connection comes on time: not before the retry, nor
            // after it.
            let millisecond = Duration::from_millis(1);
            let early = tokio::time::timeout(retry - millisecond, agent.listener.accept()).await;
            assert!(early.is_err(), "{early:?}");
            let _app = tokio::time::timeout(2 * millisecond, agent.listener.accept())
                .await
                .expect("no retry")
                .unwrap();
            browser.close().await.unwrap();
        }

        #[tokio::test]
        async fn messages_that_the_host_can_not_read_stop_it() {
            let directory = tempfile::tempdir().unwrap();
            let socket = directory.path().join("absent");
            let mut browser = Browser::start(socket.clone());
            browser
                .stdin
                .write_all(&(MAX_INCOMING as u32 + 1).to_ne_bytes())
                .await
                .unwrap();
            assert!(timeout(browser.host).await.unwrap().is_err());

            let mut browser = Browser::start(socket);
            browser.send(json!([])).await;
            assert!(timeout(browser.host).await.unwrap().is_err());

            // While a session is open, too.
            let agent = Agent::new();
            let mut browser = Browser::start(agent.socket.clone());
            let _app = agent.answer(json!({"ok":true})).await;
            assert_eq!(browser.receive_status().await, "ready");
            browser.send(json!({"type":"noise","data":""})).await;
            assert!(timeout(browser.host).await.unwrap().is_err());
        }
    }
}
