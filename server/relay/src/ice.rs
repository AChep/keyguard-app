//! `POST /v1/ice-servers`: STUN URLs and short-lived TURN credentials.
//!
//! Credentials follow the TURN REST API convention that coturn verifies with
//! `use-auth-secret`: `username = "<expiry unix seconds>:<tag>"` and
//! `credential = base64(HMAC-SHA1(secret, username))`.
//!
//! Each client address gets a TURN allowance, larger for requests that carry the
//! ticket of a live WebSocket. Past it, the answer still succeeds with STUN only.

use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use axum::Json;
use axum::body::Body;
use axum::extract::{ConnectInfo, State};
use axum::http::header::{CACHE_CONTROL, WWW_AUTHENTICATE};
use axum::http::{HeaderMap, HeaderValue, StatusCode, Uri};
use axum::response::{IntoResponse, Response};
use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use hmac::{Hmac, Mac};
use serde::{Deserialize, Serialize};
use sha1::Sha1;
use tokio::time::{Instant, timeout};

use crate::config::TurnConfig;
use crate::limits::{AuthOutcome, client_ip, client_key};
use crate::protocol::{ErrorCode, decode_hex, encode_hex};
use crate::ticket::{TICKET_LEN, Ticket};
use crate::{AppState, MAX_BODY_BYTES, auth, http_error};

/// `ttlSeconds` of an answer without TURN while TURN is configured: clients ask again
/// within a minute instead of going without TURN for a whole credential lifetime.
const STUN_ONLY_TTL_SECONDS: u64 = 60;

/// The TURN username for a credential that expires at `expires_at` (Unix seconds).
pub fn turn_username(expires_at: u64, tag: &str) -> String {
    format!("{expires_at}:{tag}")
}

/// The TURN password for `username`: `base64(HMAC-SHA1(secret, username))`.
pub fn turn_credential(secret: &str, username: &str) -> String {
    STANDARD.encode(hmac_sha1(secret.as_bytes(), username.as_bytes()))
}

fn hmac_sha1(key: &[u8], data: &[u8]) -> [u8; 20] {
    let mut mac = Hmac::<Sha1>::new_from_slice(key).expect("HMAC accepts keys of any length");
    mac.update(data);
    mac.finalize().into_bytes().into()
}

/// The user part of TURN usernames: one per client address, so coturn's `user-quota`
/// counts the allocations of an address across credential refreshes. It is a keyed hash
/// of the address, with a key drawn when the relay starts, so it doesn't reveal the
/// address.
pub(crate) struct TurnUsers {
    key: [u8; 32],
}

impl TurnUsers {
    pub(crate) fn new() -> Self {
        Self {
            key: rand::random(),
        }
    }

    /// 16 hex characters for the client key `client`.
    fn tag(&self, client: IpAddr) -> String {
        let mac = match client {
            IpAddr::V4(ip) => hmac_sha1(&self.key, &ip.octets()),
            IpAddr::V6(ip) => hmac_sha1(&self.key, &ip.octets()),
        };
        encode_hex(&mac[..8])
    }
}

/// The optional request body.
#[derive(Deserialize)]
struct IceServersRequest {
    ticket: Option<String>,
}

/// The ticket in a `{"ticket":"<32 hex>"}` body. Any other body, empty included, has none.
fn ticket_of(body: &[u8]) -> Option<Ticket> {
    let request = serde_json::from_slice::<IceServersRequest>(body).ok()?;
    decode_hex::<TICKET_LEN>(request.ticket.as_deref()?)
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct IceServersResponse {
    ice_servers: Vec<IceServer>,
    ttl_seconds: u64,
}

#[derive(Serialize)]
struct IceServer {
    urls: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    username: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    credential: Option<String>,
}

/// `POST /v1/ice-servers`.
pub(crate) async fn ice_servers(
    State(state): State<Arc<AppState>>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    uri: Uri,
    body: Body,
) -> Response {
    let config = &state.config;
    let key = client_key(client_ip(peer, &headers, &config.trust_proxy));
    // Before the body is read, so an unauthorized request can't hold the connection.
    match auth::check(&state, key, &headers, uri.query()) {
        AuthOutcome::Allowed => {}
        AuthOutcome::Unauthorized => {
            let mut response = http_error(StatusCode::UNAUTHORIZED, ErrorCode::Unauthorized);
            response
                .headers_mut()
                .insert(WWW_AUTHENTICATE, HeaderValue::from_static("Bearer"));
            return response;
        }
        AuthOutcome::Locked => return auth::locked(),
    }
    let body = match timeout(
        config.timeouts.http_body,
        axum::body::to_bytes(body, MAX_BODY_BYTES),
    )
    .await
    {
        Ok(Ok(body)) => body,
        Ok(Err(_)) => return StatusCode::BAD_REQUEST.into_response(),
        Err(_) => return StatusCode::REQUEST_TIMEOUT.into_response(),
    };

    let mut ice_servers = Vec::with_capacity(2);
    if !config.stun_urls.is_empty() {
        ice_servers.push(IceServer {
            urls: config.stun_urls.clone(),
            username: None,
            credential: None,
        });
    }
    let mut ttl_seconds = config.turn_ttl_seconds;
    if let Some(turn) = &config.turn {
        let now = Instant::now();
        let ticketed = ticket_of(&body).is_some_and(|ticket| state.tickets.is_live(&ticket, now));
        if state.turn_allowance.try_take(key, ticketed, now) {
            let tag = state.turn_users.tag(key);
            ice_servers.push(mint(turn, config.turn_ttl_seconds, &tag));
            state.metrics.turn_issued[usize::from(ticketed)].inc();
        } else {
            state.metrics.turn_limited[usize::from(ticketed)].inc();
            tracing::debug!(ticketed, "TURN allowance spent, answering with STUN only");
            ttl_seconds = STUN_ONLY_TTL_SECONDS;
        }
    }
    let body = IceServersResponse {
        ice_servers,
        ttl_seconds,
    };
    let mut response = Json(body).into_response();
    response
        .headers_mut()
        .insert(CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response
}

/// A fresh TURN credential for `turn` and the user `tag`, valid for `ttl_seconds`.
fn mint(turn: &TurnConfig, ttl_seconds: u64, tag: &str) -> IceServer {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |since| since.as_secs());
    let username = turn_username(now + ttl_seconds, tag);
    let credential = turn_credential(&turn.secret, &username);
    IceServer {
        urls: turn.urls.clone(),
        username: Some(username),
        credential: Some(credential),
    }
}
