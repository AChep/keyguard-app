//! Keyguard relay: WebSocket rooms that let Keyguard devices find each other and
//! exchange opaque, end-to-end encrypted frames, plus short-lived TURN credentials and
//! optional storage of sealed items that devices collect later.
//!
//! The relay keeps rooms in memory and stored items on disk. It sees room ids, the peer
//! ids it assigns to connections, item sizes, frame sizes and timing; never synced
//! content, device identities or keys.

mod auth;
pub mod config;
pub mod ice;
mod limits;
mod mailbox;
mod metrics;
mod protocol;
mod room;
mod store;
mod ticket;
mod ws;

use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::AtomicU64;
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use axum::body::Body;
use axum::extract::ConnectInfo;
use axum::http::{HeaderValue, Request, StatusCode, header::RETRY_AFTER};
use axum::middleware::{self, Next};
use axum::response::{IntoResponse, Response};
use axum::routing::{any, get, post};
use axum::{Json, Router, extract::State};
use hyper::body::Incoming;
use hyper::server::conn::http1;
use hyper::service::{Service as _, service_fn};
use hyper_util::rt::{TokioIo, TokioTimer};
use hyper_util::service::TowerToHyperService;
use serde::Serialize;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::watch;
use tokio::task::JoinSet;
use tokio::time::{Instant, timeout_at};
use tower_http::limit::RequestBodyLimitLayer;
use tower_http::trace::{DefaultOnFailure, TraceLayer};
use tracing::Level;

pub use config::{Config, ConfigError};

use crate::config::Timeouts;
use crate::ice::TurnUsers;
use crate::limits::{
    AuthGuard, ConnectionLimiter, ConnectionPermit, MeteredStream, RequestAllowance, TurnAllowance,
    client_ip, client_key, is_trusted_proxy,
};
use crate::mailbox::Mailbox;
use crate::metrics::Endpoint;
use crate::protocol::{ErrorCode, MAX_CLIENT_FRAME_HEADER};
use crate::room::Rooms;
use crate::ticket::Tickets;

/// The largest WebSocket message a client may send, in bytes. Part of the protocol, not a
/// setting: Keyguard sends messages up to this size (`RelayCodec.MAX_MESSAGE_BYTES`), so a
/// lower limit would break it.
pub const MAX_FRAME_BYTES: usize = 65_536;

/// The largest mailbox state item, in bytes. Part of the protocol, not a setting.
pub const MAILBOX_MAX_STATE_BYTES: u64 = 1_048_576;
/// The largest mailbox blob item, in bytes: a 16 MiB blob, sealed and padded. Part of
/// the protocol, not a setting.
pub const MAILBOX_MAX_BLOB_BYTES: u64 = 17_825_792;

/// The ICE endpoint reads a ticket at most. Mailbox uploads check their own size.
pub(crate) const MAX_BODY_BYTES: usize = 4096;
/// How long HTTP connections and open sockets get to finish on shutdown.
const SHUTDOWN_GRACE: Duration = Duration::from_secs(2);

pub(crate) struct AppState {
    pub(crate) config: Config,
    pub(crate) started: Instant,
    pub(crate) metrics: metrics::Metrics,
    pub(crate) rooms: Arc<Rooms>,
    /// WebSockets, including upgrades still pending.
    pub(crate) connections: Arc<ConnectionLimiter>,
    /// HTTP connections, until they become WebSockets.
    pub(crate) http_connections: Arc<ConnectionLimiter>,
    pub(crate) ws_requests: RequestAllowance,
    pub(crate) ice_requests: RequestAllowance,
    pub(crate) turn_allowance: TurnAllowance,
    pub(crate) turn_users: TurnUsers,
    pub(crate) tickets: Arc<Tickets>,
    pub(crate) auth_guard: AuthGuard,
    /// Relay storage, when configured.
    pub(crate) mailbox: Option<Mailbox>,
    pub(crate) next_connection_id: AtomicU64,
    pub(crate) shutdown: watch::Receiver<Option<Instant>>,
}

/// Locks `mutex`, ignoring poison.
pub(crate) fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

/// Unix seconds.
pub(crate) fn unix_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |since| since.as_secs())
}

/// Completes once shutdown starts.
pub(crate) async fn stopping(shutdown: &mut watch::Receiver<Option<Instant>>) {
    _ = shutdown.wait_for(Option::is_some).await;
}

/// Serves the relay on `listener` until `shutdown` completes.
///
/// Open sockets then receive a close frame (code 1001) and get a short grace
/// period to flush it. `metrics_listener`, when present, serves only `/metrics` and
/// must be restricted to trusted scrapers by the caller's network configuration.
pub async fn serve<F>(
    mut listener: TcpListener,
    mut metrics_listener: Option<TcpListener>,
    config: Config,
    shutdown: F,
) where
    F: Future<Output = ()> + Send + 'static,
{
    let (stop, stopped) = watch::channel(None);
    let now = Instant::now();
    let mailbox = config
        .mailbox
        .as_ref()
        .map(|mailbox| Mailbox::new(mailbox, now));
    let state = Arc::new(AppState {
        started: now,
        metrics: metrics::Metrics::default(),
        rooms: Arc::new(Rooms::new(
            config.max_rooms,
            config.max_room_peers,
            config.max_rooms_per_ip,
            config.egress_bytes_per_sec,
            now,
        )),
        connections: Arc::new(ConnectionLimiter::new(
            config.max_conns_per_ip,
            config.max_conns,
        )),
        http_connections: Arc::new(ConnectionLimiter::new(
            config.max_conns_per_ip,
            config.max_conns,
        )),
        ws_requests: RequestAllowance::new(
            config.ws_requests_per_sec,
            config.ws_total_per_sec,
            now,
        ),
        ice_requests: RequestAllowance::new(
            config.ice_requests_per_sec,
            config.ice_total_per_sec,
            now,
        ),
        turn_allowance: TurnAllowance::new(
            config.ice_requests_per_minute,
            config.ice_ticketless_per_minute,
            config.ice_total_per_minute,
            now,
        ),
        turn_users: TurnUsers::new(),
        tickets: Arc::new(Tickets::new(now)),
        auth_guard: AuthGuard::new(now),
        mailbox,
        next_connection_id: AtomicU64::new(0),
        shutdown: stopped,
        config,
    });
    let app = router(Arc::clone(&state));
    let metrics_app = metrics::router(Arc::clone(&state));
    // Monitoring has its own small cap so relay saturation cannot consume its slots.
    let metrics_connections = Arc::new(ConnectionLimiter::new(4, 16));
    // The message budget of a connection, with room for the framing of its messages.
    // Reading stops past it, even for bytes that never become a message.
    let read_bytes_per_sec =
        state.config.bytes_per_sec + u64::from(state.config.msgs_per_sec) * MAX_CLIENT_FRAME_HEADER;
    let mut connections = JoinSet::new();
    let maintenance = state.mailbox.as_ref().map(|mailbox| {
        tokio::spawn(mailbox::maintain(
            Arc::clone(&mailbox.store),
            state.shutdown.clone(),
        ))
    });
    tokio::pin!(shutdown);
    loop {
        tokio::select! {
            () = &mut shutdown => break,
            // Keep Axum's handling of transient accept errors.
            (stream, peer) = axum::serve::Listener::accept(&mut listener) => {
                // A proxy's connections carry many clients: they count toward the total only.
                let key = (!is_trusted_proxy(peer.ip(), &state.config.trust_proxy))
                    .then(|| client_key(peer.ip()));
                let permit = match state.http_connections.acquire(key) {
                    Ok(permit) => permit,
                    Err(reason) => {
                        state.metrics.http_refusals[reason as usize].inc();
                        tracing::debug!("HTTP connection limit reached, closing a new connection");
                        continue;
                    }
                };
                // Frames are small and often sent back to back: don't hold them for an ACK.
                _ = stream.set_nodelay(true);
                connections.spawn(serve_http(
                    stream,
                    peer,
                    app.clone(),
                    read_bytes_per_sec,
                    state.config.timeouts,
                    state.shutdown.clone(),
                    permit,
                ));
            }
            (stream, peer) = async {
                match &mut metrics_listener {
                    Some(listener) => axum::serve::Listener::accept(listener).await,
                    None => std::future::pending().await,
                }
            } => {
                let Ok(permit) = metrics_connections.acquire(Some(client_key(peer.ip()))) else {
                    continue;
                };
                // A scraper that stops reading can't hold its slot either.
                connections.spawn(serve_http(
                    stream,
                    peer,
                    metrics_app.clone(),
                    read_bytes_per_sec,
                    state.config.timeouts,
                    state.shutdown.clone(),
                    permit,
                ));
            }
            Some(_) = connections.join_next(), if !connections.is_empty() => {}
        }
    }
    drop(listener);
    drop(metrics_listener);

    let deadline = Instant::now() + SHUTDOWN_GRACE;
    _ = stop.send(Some(deadline));
    // The storage work in flight ends too, so a new relay on the same directory doesn't
    // race it. Nothing, a disk that hangs included, holds shutdown past the deadline.
    _ = timeout_at(deadline, async {
        let drained = async {
            while connections.join_next().await.is_some() {}
            state.connections.wait_until_empty().await;
        };
        let maintained = async {
            if let Some(maintenance) = maintenance {
                _ = maintenance.await;
            }
        };
        tokio::join!(drained, maintained);
    })
    .await;

    // A partial HTTP header or blocked response cannot hold the drain open.
    connections.abort_all();
    while connections.join_next().await.is_some() {}
    // Upgraded sockets enforce the same deadline, including blocked writes.
    state.connections.wait_until_empty().await;
}

/// Serves one connection from `peer`, metered to `read_bytes_per_sec`, until it closes
/// or becomes a WebSocket, which releases `_permit`.
async fn serve_http(
    stream: TcpStream,
    peer: SocketAddr,
    app: Router,
    read_bytes_per_sec: u64,
    timeouts: Timeouts,
    mut shutdown: watch::Receiver<Option<Instant>>,
    _permit: ConnectionPermit,
) {
    let stream = MeteredStream::new(
        stream,
        read_bytes_per_sec,
        timeouts.write_stall,
        Instant::now(),
    );
    let app = TowerToHyperService::new(app);
    let service = service_fn(move |mut request: Request<Incoming>| {
        request.extensions_mut().insert(ConnectInfo(peer));
        app.call(request)
    });
    let mut builder = http1::Builder::new();
    builder
        .timer(TokioTimer::new())
        .header_read_timeout(timeouts.http_header);
    let connection = builder
        .serve_connection(TokioIo::new(stream), service)
        .with_upgrades();
    tokio::pin!(connection);
    tokio::select! {
        _ = &mut connection => {}
        () = stopping(&mut shutdown) => {
            connection.as_mut().graceful_shutdown();
            _ = connection.await;
        }
    }
}

fn router(state: Arc<AppState>) -> Router {
    // Spans carry the method and path only: no query (it may hold the token) and no
    // client address.
    let trace = TraceLayer::new_for_http()
        .make_span_with(|request: &Request<Body>| {
            tracing::debug_span!("http", method = %request.method(), path = %request.uri().path())
        })
        // Capacity and admission refusals return 503: expected under load.
        .on_failure(DefaultOnFailure::new().level(Level::DEBUG));
    Router::new()
        .route("/healthz", get(healthz))
        .route("/v1/ws", get(ws::upgrade))
        .route("/v1/ice-servers", post(ice::ice_servers))
        .layer(RequestBodyLimitLayer::new(MAX_BODY_BYTES))
        // Added after the body limit: an upload checks its `Content-Length` against the
        // cap of its kind, and reads no more.
        .route("/v1/mailbox/", any(mailbox::handle))
        .route("/v1/mailbox/{*kind}", any(mailbox::handle))
        // Before body-size rejection and handler extractors, including malformed
        // WebSocket upgrade requests. The middleware leaves health probes unmetered.
        .route_layer(middleware::from_fn_with_state(
            Arc::clone(&state),
            admit_request,
        ))
        .layer(trace)
        .with_state(state)
}

/// The client key of a request to an endpoint, which [`admit_request`] works out once.
#[derive(Debug, Clone, Copy)]
pub(crate) struct ClientKey(pub(crate) IpAddr);

/// Bounds endpoint work before authentication, upgrade extraction or body reads, and
/// hands endpoints the [`ClientKey`]. The health probe and the separate metrics listener
/// do not use these allowances.
async fn admit_request(
    State(state): State<Arc<AppState>>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    mut request: Request<Body>,
    next: Next,
) -> Response {
    let metered = match request.uri().path() {
        "/v1/ws" => Some((&state.ws_requests, Endpoint::WebSocket)),
        "/v1/ice-servers" => Some((&state.ice_requests, Endpoint::Ice)),
        // Without storage, the mailbox answers `404` unmetered.
        path if path.starts_with(mailbox::PATH_PREFIX) => state
            .mailbox
            .as_ref()
            .map(|mailbox| (&mailbox.requests, Endpoint::Mailbox)),
        _ => return next.run(request).await,
    };
    let key = client_key(client_ip(
        peer,
        request.headers(),
        &state.config.trust_proxy,
    ));
    if let Some((allowance, endpoint)) = metered
        && let Err(reason) = allowance.try_take(key, Instant::now())
    {
        state.metrics.request_rate_limits[endpoint as usize][reason as usize].inc();
        let response = http_error(reason.status(), ErrorCode::RateLimited);
        return with_retry_after(response, allowance.retry_after());
    }
    request.extensions_mut().insert(ClientKey(key));
    next.run(request).await
}

#[derive(Serialize)]
struct Health {
    status: &'static str,
}

/// `GET /healthz`: always open, even when a token is configured.
async fn healthz() -> Json<Health> {
    Json(Health { status: "ok" })
}

#[derive(Serialize)]
struct ErrorBody {
    error: ErrorCode,
}

/// An HTTP error with the body `{"error":"<code>"}`.
pub(crate) fn http_error(status: StatusCode, code: ErrorCode) -> Response {
    (status, Json(ErrorBody { error: code })).into_response()
}

/// `response` with `Retry-After: <seconds>`, at least one.
pub(crate) fn with_retry_after(mut response: Response, seconds: u64) -> Response {
    response
        .headers_mut()
        .insert(RETRY_AFTER, HeaderValue::from(seconds.max(1)));
    response
}
