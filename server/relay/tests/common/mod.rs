//! Starts a relay in-process on an ephemeral port and talks to it.

// Every test file uses a different subset of these helpers.
#![allow(dead_code)]

use std::net::SocketAddr;
use std::ops::{Deref, DerefMut};
use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use keyguard_relay::Config;
use keyguard_relay::config::{IpNet, MailboxConfig};
use serde_json::{Value, json};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::oneshot;
use tokio::time::{Instant, timeout};
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::http::HeaderValue;
use tokio_tungstenite::tungstenite::{self, Message};
use tokio_tungstenite::{MaybeTlsStream, WebSocketStream};

pub type Socket = WebSocketStream<MaybeTlsStream<TcpStream>>;

/// A connection the relay welcomed, and the peer id it assigned. Derefs to its socket.
pub struct Client {
    pub socket: Socket,
    /// The assigned peer id, in hex.
    pub peer: String,
    /// The `welcome` frame.
    pub welcome: Value,
}

impl Client {
    /// Reads the `welcome` frame, which comes first on an authorized connection.
    pub async fn welcomed(mut socket: Socket) -> Self {
        let welcome = recv_json(&mut socket).await;
        assert_eq!(welcome["t"], "welcome", "{welcome}");
        let peer = welcome["peer"].as_str().expect("welcome names the peer id");
        assert!(is_lower_hex(peer, 16), "{peer}");
        Self {
            socket,
            peer: peer.to_owned(),
            welcome,
        }
    }
}

impl Deref for Client {
    type Target = Socket;

    fn deref(&self) -> &Socket {
        &self.socket
    }
}

impl DerefMut for Client {
    fn deref_mut(&mut self) -> &mut Socket {
        &mut self.socket
    }
}

/// Whether `text` is `len` bytes in lowercase hex.
pub fn is_lower_hex(text: &str, len: usize) -> bool {
    text.len() == len * 2 && text.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

/// The peer ids of `clients`, sorted like [`join`] sorts them.
pub fn peers(clients: &[&Client]) -> Vec<String> {
    let mut peers: Vec<String> = clients.iter().map(|client| client.peer.clone()).collect();
    peers.sort();
    peers
}

/// Upper bound for anything the tests wait for.
pub const TIMEOUT: Duration = Duration::from_secs(5);
/// How long a socket must stay quiet to count as not having received a frame.
pub const QUIET: Duration = Duration::from_millis(300);

/// A relay running in this process; stops when dropped.
pub struct Relay {
    pub addr: SocketAddr,
    pub metrics_addr: Option<SocketAddr>,
    _stop: oneshot::Sender<()>,
    task: tokio::task::JoinHandle<()>,
}

/// Defaults with a loopback bind address.
pub fn test_config() -> Config {
    Config {
        bind: SocketAddr::from(([127, 0, 0, 1], 0)),
        metrics_bind: Some(SocketAddr::from(([127, 0, 0, 1], 0))),
        ..Config::default()
    }
}

/// [`test_config`] with relay storage in `dir`, which may not exist yet. Free space on
/// the volume isn't held back.
pub fn mailbox_config(dir: &Path) -> Config {
    Config {
        mailbox: Some(MailboxConfig {
            min_free_bytes: 0,
            ..MailboxConfig::new(dir)
        }),
        ..test_config()
    }
}

/// `trust_proxy` for tests that name the client with `X-Forwarded-For`, as
/// [`Relay::connect_from`] does: the test itself is the proxy.
pub fn loopback_proxy() -> Vec<IpNet> {
    vec!["127.0.0.1".parse().unwrap()]
}

impl Relay {
    pub async fn start(config: Config) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let metrics_listener = if let Some(bind) = config.metrics_bind {
            Some(TcpListener::bind(bind).await.unwrap())
        } else {
            None
        };
        let metrics_addr = metrics_listener
            .as_ref()
            .map(|listener| listener.local_addr().unwrap());
        let (stop, stopped) = oneshot::channel::<()>();
        let task = tokio::spawn(keyguard_relay::serve(
            listener,
            metrics_listener,
            config,
            async move {
                _ = stopped.await;
            },
        ));
        Self {
            addr,
            metrics_addr,
            _stop: stop,
            task,
        }
    }

    pub async fn shutdown(self) {
        self._stop.send(()).unwrap();
        timeout(TIMEOUT, self.task)
            .await
            .expect("relay shuts down in time")
            .unwrap();
    }

    /// Connects and reads the `welcome` frame.
    pub async fn connect(&self) -> Client {
        let socket = self
            .try_connect("", None)
            .await
            .expect("WebSocket connects");
        Client::welcomed(socket).await
    }

    pub async fn try_connect(
        &self,
        query: &str,
        bearer: Option<&str>,
    ) -> Result<Socket, tungstenite::Error> {
        self.try_connect_with(query, bearer, &[]).await
    }

    /// Connects as `ip`, through a relay that trusts loopback as its proxy, and reads the
    /// `welcome` frame.
    pub async fn connect_from(&self, ip: &str) -> Client {
        let socket = self
            .try_connect_with("", None, &[("x-forwarded-for", ip)])
            .await
            .expect("WebSocket connects");
        Client::welcomed(socket).await
    }

    pub async fn try_connect_with(
        &self,
        query: &str,
        bearer: Option<&str>,
        headers: &[(&'static str, &str)],
    ) -> Result<Socket, tungstenite::Error> {
        let mut request = format!("ws://{}/v1/ws{query}", self.addr).into_client_request()?;
        if let Some(token) = bearer {
            let value = HeaderValue::from_str(&format!("Bearer {token}")).unwrap();
            request.headers_mut().insert("authorization", value);
        }
        for (name, value) in headers {
            request
                .headers_mut()
                .insert(*name, HeaderValue::from_str(value).unwrap());
        }
        let (socket, _) = timeout(TIMEOUT, tokio_tungstenite::connect_async(request))
            .await
            .expect("handshake in time")?;
        Ok(socket)
    }

    /// Sends an incomplete request, which holds an HTTP connection open.
    pub async fn open_http_connection(&self) -> TcpStream {
        let mut stream = TcpStream::connect(self.addr).await.unwrap();
        // A refused connection may be gone before the request is written.
        _ = stream
            .write_all(b"GET /healthz HTTP/1.1\r\nHost: relay\r\n")
            .await;
        stream
    }

    /// A minimal HTTP/1.1 exchange, enough for the JSON endpoints.
    pub async fn http(&self, method: &str, path: &str, headers: &[(&str, &str)]) -> HttpResponse {
        self.http_with_body(method, path, headers, "").await
    }

    pub async fn http_with_body(
        &self,
        method: &str,
        path: &str,
        headers: &[(&str, &str)],
        body: &str,
    ) -> HttpResponse {
        Self::http_at(self.addr, method, path, headers, body).await
    }

    pub async fn http_at(
        addr: SocketAddr,
        method: &str,
        path: &str,
        headers: &[(&str, &str)],
        body: &str,
    ) -> HttpResponse {
        let mut request = format!(
            "{method} {path} HTTP/1.1\r\nHost: {}\r\nConnection: close\r\nContent-Length: {}\r\n",
            addr,
            body.len()
        );
        for (name, value) in headers {
            request += &format!("{name}: {value}\r\n");
        }
        request += "\r\n";
        request += body;
        Self::raw_http_at(addr, &request).await
    }

    /// Sends `request` as is, which must ask to close the connection, and reads the
    /// answer.
    pub async fn raw_http(&self, request: &str) -> HttpResponse {
        Self::raw_http_at(self.addr, request).await
    }

    async fn raw_http_at(addr: SocketAddr, request: &str) -> HttpResponse {
        let mut stream = TcpStream::connect(addr).await.unwrap();
        stream.write_all(request.as_bytes()).await.unwrap();
        let mut raw = Vec::new();
        timeout(TIMEOUT, stream.read_to_end(&mut raw))
            .await
            .unwrap()
            .unwrap();
        let text = String::from_utf8(raw).unwrap();
        let (head, body) = text.split_once("\r\n\r\n").unwrap();
        let mut lines = head.split("\r\n");
        let status = lines
            .next()
            .unwrap()
            .split(' ')
            .nth(1)
            .unwrap()
            .parse()
            .unwrap();
        let headers = lines
            .filter_map(|line| line.split_once(':'))
            .map(|(name, value)| (name.trim().to_ascii_lowercase(), value.trim().to_owned()))
            .collect();
        HttpResponse {
            status,
            headers,
            body: body.to_owned(),
        }
    }

    /// `POST /v1/ice-servers`.
    pub async fn ice_servers(&self, headers: &[(&str, &str)]) -> HttpResponse {
        self.http("POST", "/v1/ice-servers", headers).await
    }

    /// `POST /v1/ice-servers` with `{"ticket":"<ticket>"}`.
    pub async fn ice_servers_with_ticket(&self, ticket: &str) -> HttpResponse {
        let body = json!({ "ticket": ticket }).to_string();
        self.http_with_body(
            "POST",
            "/v1/ice-servers",
            &[("Content-Type", "application/json")],
            &body,
        )
        .await
    }

    pub async fn health(&self) -> Value {
        let response = self.http("GET", "/healthz", &[]).await;
        assert_eq!(response.status, 200);
        response.json()
    }

    pub async fn metrics(&self) -> HttpResponse {
        Self::http_at(
            self.metrics_addr.expect("metrics enabled"),
            "GET",
            "/metrics",
            &[],
            "",
        )
        .await
    }

    /// Polls private metrics until the sample `name` is `value`.
    pub async fn wait_for_metric(&self, name: &str, value: f64) {
        let deadline = Instant::now() + TIMEOUT;
        loop {
            let actual = self.metrics().await.metric(name);
            if actual == value {
                return;
            }
            assert!(
                Instant::now() < deadline,
                "{name} is {actual}, expected {value}"
            );
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }

    /// Polls private metrics until it reports `rooms` and `connections`.
    pub async fn wait_for_metrics(&self, rooms: u64, connections: u64) {
        let deadline = Instant::now() + TIMEOUT;
        loop {
            let response = self.metrics().await;
            let actual_rooms = response.metric("keyguard_relay_rooms");
            let actual_connections = response.metric("keyguard_relay_websocket_connections");
            if actual_rooms == rooms as f64 && actual_connections == connections as f64 {
                return;
            }
            assert!(
                Instant::now() < deadline,
                "metrics report {actual_rooms} rooms and {actual_connections} connections; expected {rooms} and {connections}"
            );
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }
}

/// Whether the relay keeps `stream` open for [`QUIET`] without answering it.
pub async fn is_open(stream: &mut TcpStream) -> bool {
    timeout(QUIET, stream.read(&mut [0; 1])).await.is_err()
}

/// The status of a WebSocket handshake the relay refused.
pub fn refused_status(result: Result<Socket, tungstenite::Error>) -> u16 {
    match result {
        Err(tungstenite::Error::Http(response)) => response.status().as_u16(),
        other => panic!("expected an HTTP refusal, got {:?}", other.map(|_| ())),
    }
}

pub struct HttpResponse {
    pub status: u16,
    pub headers: Vec<(String, String)>,
    pub body: String,
}

impl HttpResponse {
    pub fn metric(&self, name: &str) -> f64 {
        self.body
            .lines()
            .find_map(|line| {
                let (key, value) = line.split_once(' ')?;
                (key == name).then(|| value.parse().unwrap())
            })
            .unwrap_or_else(|| panic!("missing metric {name}: {}", self.body))
    }

    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(key, _)| key == name)
            .map(|(_, value)| value.as_str())
    }

    pub fn json(&self) -> Value {
        serde_json::from_str(&self.body)
            .unwrap_or_else(|_| panic!("JSON body, got {:?}", self.body))
    }
}

/// Fills a client's unread reply buffers with a bounded burst of control pings.
/// The returned reader stays unread until the caller is ready to check closure.
pub async fn flood_non_reading_client(
    socket: Socket,
) -> (
    futures_util::stream::SplitStream<Socket>,
    tokio::task::JoinHandle<()>,
) {
    const FRAMES: usize = 65_536;
    let (mut writer, reader) = socket.split();
    let sent = Arc::new(AtomicUsize::new(0));
    let progress = Arc::clone(&sent);
    let task = tokio::spawn(async move {
        let payload = vec![0x55; 125];
        for count in 1..=FRAMES {
            if writer
                .send(Message::Ping(payload.clone().into()))
                .await
                .is_err()
            {
                break;
            }
            progress.store(count, Ordering::Relaxed);
        }
    });
    timeout(TIMEOUT, async {
        let mut previous = 0;
        loop {
            tokio::time::sleep(Duration::from_millis(100)).await;
            let current = sent.load(Ordering::Relaxed);
            if current == FRAMES || (current > 0 && current == previous) {
                break;
            }
            previous = current;
        }
    })
    .await
    .expect("control burst fills the buffers or backpressures the sender");
    (reader, task)
}

/// Unix seconds.
pub fn unix_now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs()
}

/// 64 hex characters.
pub fn room_id(n: u8) -> String {
    format!("{n:02x}").repeat(32)
}

/// 32 hex characters: a peer id the relay never assigned.
pub fn peer_id(n: u8) -> String {
    format!("{n:02x}").repeat(16)
}

/// A relay-forward binary frame in `room`: `0x03 | room id | peer id | payload`, with
/// `peer` in hex.
pub fn forward_frame(room: u8, peer: &str, payload: &[u8]) -> Vec<u8> {
    assert!(is_lower_hex(peer, 16), "{peer}");
    let mut frame = vec![0x03];
    frame.extend_from_slice(&[room; 32]);
    for pair in peer.as_bytes().chunks(2) {
        let pair = std::str::from_utf8(pair).unwrap();
        frame.push(u8::from_str_radix(pair, 16).unwrap());
    }
    frame.extend_from_slice(payload);
    frame
}

/// A `presence` frame for `peer` in `room`.
pub fn presence(room: u8, peer: &str, state: &str) -> Value {
    json!({ "t": "presence", "room": room_id(room), "peer": peer, "state": state })
}

/// A `msg` frame from `from` in `room`.
pub fn msg(room: u8, from: &str, data: &str) -> Value {
    json!({ "t": "msg", "room": room_id(room), "from": from, "data": data })
}

/// A received data frame, or the end of the connection with its close code.
#[derive(Debug, PartialEq)]
pub enum Frame {
    Json(Value),
    Binary(Vec<u8>),
    Closed(Option<u16>),
}

/// The next data frame; pings and pongs are skipped (the codec answers pings).
pub async fn next_frame(socket: &mut Socket) -> Frame {
    loop {
        let message = timeout(TIMEOUT, socket.next())
            .await
            .expect("a frame in time");
        match message {
            Some(Ok(Message::Text(text))) => {
                return Frame::Json(serde_json::from_str(&text).unwrap());
            }
            Some(Ok(Message::Binary(data))) => return Frame::Binary(data.to_vec()),
            Some(Ok(Message::Close(frame))) => return Frame::Closed(frame.map(|f| f.code.into())),
            Some(Ok(_)) => {}
            Some(Err(_)) | None => return Frame::Closed(None),
        }
    }
}

pub async fn recv_json(socket: &mut Socket) -> Value {
    match next_frame(socket).await {
        Frame::Json(value) => value,
        other => panic!("expected JSON, got {other:?}"),
    }
}

pub async fn recv_binary(socket: &mut Socket) -> Vec<u8> {
    match next_frame(socket).await {
        Frame::Binary(data) => data,
        other => panic!("expected binary, got {other:?}"),
    }
}

pub async fn expect_error(socket: &mut Socket, code: &str) {
    assert_eq!(recv_json(socket).await, json!({ "t": "err", "code": code }));
}

/// An error that refused a join of `room`.
pub async fn expect_join_error(socket: &mut Socket, code: &str, room: &str) {
    assert_eq!(
        recv_json(socket).await,
        json!({ "t": "err", "code": code, "room": room })
    );
}

/// The connection ends; returns the close code if a close frame came.
pub async fn expect_closed(socket: &mut Socket) -> Option<u16> {
    match next_frame(socket).await {
        Frame::Closed(code) => code,
        other => panic!("expected the connection to close, got {other:?}"),
    }
}

/// No data frame arrives for `QUIET`.
pub async fn expect_silence(socket: &mut Socket) {
    let deadline = Instant::now() + QUIET;
    while let Ok(message) = tokio::time::timeout_at(deadline, socket.next()).await {
        match message {
            Some(Ok(Message::Ping(_) | Message::Pong(_))) => {}
            other => panic!("expected silence, got {other:?}"),
        }
    }
}

pub async fn send_json(socket: &mut Socket, value: Value) {
    socket.send(Message::text(value.to_string())).await.unwrap();
}

pub async fn send_text(socket: &mut Socket, text: &str) {
    socket.send(Message::text(text)).await.unwrap();
}

pub async fn send_binary(socket: &mut Socket, data: Vec<u8>) {
    socket.send(Message::binary(data)).await.unwrap();
}

/// Sends a `join` frame without reading the answer.
pub async fn send_join(socket: &mut Socket, room: &str) {
    send_json(socket, json!({ "t": "join", "room": room })).await;
}

/// Joins and returns the `joined` answer.
pub async fn join_frame(socket: &mut Socket, room: &str) -> Value {
    send_join(socket, room).await;
    let joined = recv_json(socket).await;
    assert_eq!(joined["t"], "joined", "{joined}");
    joined
}

/// Joins and returns the connection's ticket.
pub async fn join_ticket(socket: &mut Socket, room: &str) -> String {
    let joined = join_frame(socket, room).await;
    joined["ticket"].as_str().unwrap().to_owned()
}

/// Joins and returns the `peers` of the `joined` answer, sorted.
pub async fn join(socket: &mut Socket, room: &str) -> Vec<String> {
    let joined = join_frame(socket, room).await;
    let mut peers: Vec<String> = joined["peers"]
        .as_array()
        .unwrap()
        .iter()
        .map(|peer| peer.as_str().unwrap().to_owned())
        .collect();
    peers.sort();
    peers
}
