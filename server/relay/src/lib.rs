//! Keyguard relay: WebSocket rooms that let Keyguard devices find each other and
//! exchange opaque, end-to-end encrypted frames, plus short-lived TURN credentials.
//!
//! The relay keeps everything in memory. It sees room ids, per-connection peer ids,
//! frame sizes and timing; never synced content, device identities or keys.

mod auth;
pub mod config;
pub mod ice;
mod limits;
mod metrics;
mod protocol;
mod room;
mod ticket;
mod ws;

use std::net::SocketAddr;
use std::sync::atomic::AtomicU64;
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::Duration;

use axum::body::Body;
use axum::extract::ConnectInfo;
use axum::http::{Request, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
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

use crate::ice::TurnUsers;
use crate::limits::{
    AuthGuard, ConnectionLimiter, ConnectionPermit, TurnAllowance, client_key, is_trusted_proxy,
};
use crate::protocol::ErrorCode;
use crate::room::Rooms;
use crate::ticket::Tickets;

/// The largest WebSocket message a client may send, in bytes. Part of the protocol, not a
/// setting: Keyguard sends messages up to this size (`RelayCodec.MAX_MESSAGE_BYTES`), so a
/// lower limit would break it.
pub const MAX_FRAME_BYTES: usize = 65_536;

/// Only the ICE endpoint reads a body, and only a ticket.
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
    pub(crate) turn_allowance: TurnAllowance,
    pub(crate) turn_users: TurnUsers,
    pub(crate) tickets: Arc<Tickets>,
    pub(crate) auth_guard: AuthGuard,
    pub(crate) next_connection_id: AtomicU64,
    pub(crate) shutdown: watch::Receiver<Option<Instant>>,
}

/// Locks `mutex`, ignoring poison.
pub(crate) fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
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
    let state = Arc::new(AppState {
        started: now,
        metrics: metrics::Metrics::default(),
        rooms: Arc::new(Rooms::new(
            config.max_rooms,
            config.max_room_peers,
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
        turn_allowance: TurnAllowance::new(
            config.ice_requests_per_minute,
            config.ice_ticketless_per_minute,
            config.ice_total_per_minute,
            now,
        ),
        turn_users: TurnUsers::new(),
        tickets: Arc::new(Tickets::new(now)),
        auth_guard: AuthGuard::new(now),
        next_connection_id: AtomicU64::new(0),
        shutdown: stopped,
        config,
    });
    let app = router(Arc::clone(&state));
    let metrics_app = metrics::router(Arc::clone(&state));
    // Monitoring has its own small cap so relay saturation cannot consume its slots.
    let metrics_connections = Arc::new(ConnectionLimiter::new(4, 16));
    let mut connections = JoinSet::new();
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
                    state.config.timeouts.http_header,
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
                connections.spawn(serve_http(
                    stream,
                    peer,
                    metrics_app.clone(),
                    state.config.timeouts.http_header,
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
    _ = timeout_at(deadline, async {
        while connections.join_next().await.is_some() {}
        state.connections.wait_until_empty().await;
    })
    .await;

    // A partial HTTP header or blocked response cannot hold the drain open.
    connections.abort_all();
    while connections.join_next().await.is_some() {}
    // Upgraded sockets enforce the same deadline, including blocked writes.
    state.connections.wait_until_empty().await;
}

/// Serves one connection from `peer` until it closes or becomes a WebSocket, which
/// releases `_permit`.
async fn serve_http(
    stream: TcpStream,
    peer: SocketAddr,
    app: Router,
    header_timeout: Duration,
    mut shutdown: watch::Receiver<Option<Instant>>,
    _permit: ConnectionPermit,
) {
    let app = TowerToHyperService::new(app);
    let service = service_fn(move |mut request: Request<Incoming>| {
        request.extensions_mut().insert(ConnectInfo(peer));
        app.call(request)
    });
    let mut builder = http1::Builder::new();
    builder
        .timer(TokioTimer::new())
        .header_read_timeout(header_timeout);
    let connection = builder
        .serve_connection(TokioIo::new(stream), service)
        .with_upgrades();
    tokio::pin!(connection);
    tokio::select! {
        _ = &mut connection => {}
        () = async { _ = shutdown.wait_for(Option::is_some).await; } => {
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
        // The only 5xx is the 503 at `RELAY_MAX_CONNS`: expected under load, not an error.
        .on_failure(DefaultOnFailure::new().level(Level::DEBUG));
    Router::new()
        .route("/healthz", get(healthz))
        .route("/v1/ws", get(ws::upgrade))
        .route("/v1/ice-servers", post(ice::ice_servers))
        .layer(RequestBodyLimitLayer::new(MAX_BODY_BYTES))
        .layer(trace)
        .with_state(state)
}

#[derive(Serialize)]
struct Health {
    status: &'static str,
}

/// `GET /healthz`: always open, even when a token is configured.
async fn healthz() -> Json<Health> {
    Json(Health { status: "ok" })
}

/// An HTTP error with the body `{"error":"<code>"}`.
pub(crate) fn http_error(status: StatusCode, code: ErrorCode) -> Response {
    (status, Json(serde_json::json!({ "error": code }))).into_response()
}
