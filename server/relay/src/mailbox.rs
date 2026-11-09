//! `/v1/mailbox/{state,blob}`: sealed items that devices leave on the relay for each
//! other, with quotas per client and a background task for expiry and trimming.
//!
//! An item id is a capability, so it travels in `X-Keyguard-Item`, never in the URL,
//! and never reaches logs or metrics. Requests also need the live ticket of a joined
//! WebSocket.

use std::future::poll_fn;
use std::io::{self, SeekFrom};
use std::net::IpAddr;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll, ready};
use std::time::Duration;

use axum::Extension;
use axum::body::{Body, Bytes};
use axum::extract::State;
use axum::http::header::{
    ACCEPT_RANGES, ALLOW, CACHE_CONTROL, CONTENT_LENGTH, CONTENT_RANGE, CONTENT_TYPE, ETAG,
    IF_MATCH, IF_NONE_MATCH, IF_RANGE, RANGE, TRANSFER_ENCODING,
};
use axum::http::{HeaderMap, HeaderName, HeaderValue, Method, StatusCode, Uri};
use axum::response::{IntoResponse, Response};
use bytes::BytesMut;
use hyper::body::{Body as _, Frame};
use sha2::{Digest, Sha256};
use tokio::fs::File;
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt};
use tokio::sync::{mpsc, watch};
use tokio::time::{self, Instant, MissedTickBehavior, timeout_at};

use crate::config::MailboxConfig;
use crate::limits::{AuthOutcome, ByteAllowance, RequestAllowance};
use crate::metrics::Counter;
use crate::protocol::{ErrorCode, decode_hex, encode_hex};
use crate::store::{
    CommitError, Deleted, FileId, HEADER_LEN, Header, Kind, Lookup, Refusal, Store, TAG_LEN, Tag,
    open_item,
};
use crate::ticket::TICKET_LEN;
use crate::{AppState, ClientKey, auth, http_error, stopping, with_retry_after};

/// Paths of the mailbox routes start with this.
pub(crate) const PATH_PREFIX: &str = "/v1/mailbox/";

const TICKET: HeaderName = HeaderName::from_static("x-keyguard-ticket");
const ITEM: HeaderName = HeaderName::from_static("x-keyguard-item");
const TAG: HeaderName = HeaderName::from_static("x-keyguard-tag");
const TTL: HeaderName = HeaderName::from_static("x-keyguard-ttl");
const EXPIRES: HeaderName = HeaderName::from_static("x-keyguard-expires");

/// Length of an item id in bytes (64 hex characters on the wire).
const ITEM_ID_LEN: usize = 32;
/// The shortest lifetime a client can ask for.
const MIN_TTL_SECONDS: u64 = 60;
/// `Retry-After` of `storage_full`: trimming and expiry need a while to make room.
const FULL_RETRY_SECONDS: u64 = 300;
/// `Retry-After` of `storage_unavailable`, the interval of the retries.
const UNAVAILABLE_RETRY_SECONDS: u64 = 60;
/// Mailbox requests per minute on the whole relay, in multiples of the per-address
/// allowance.
const TOTAL_REQUESTS_PER_ADDRESS: u32 = 100;
/// Bytes read from a file per body chunk.
const READ_CHUNK: usize = 64 * 1024;

/// Operations, which label metrics.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Op {
    Put,
    Get,
    Head,
    Delete,
}

pub(crate) const OPS: [&str; 4] = ["put", "get", "head", "delete"];

/// Results of a request, which label metrics: `ok`, the other answers without an error
/// code, and the error codes.
pub(crate) const RESULTS: [&str; 15] = [
    "ok",
    "not_modified",
    "partial_content",
    "range_not_satisfiable",
    "unauthorized",
    "rate_limited",
    "ticket_required",
    "bad_request",
    "not_found",
    "item_too_big",
    "tag_mismatch",
    "item_exists",
    "storage_full",
    "storage_unavailable",
    "timeout",
];

const OK: usize = 0;
const NOT_MODIFIED: usize = 1;
const PARTIAL_CONTENT: usize = 2;
const RANGE_NOT_SATISFIABLE: usize = 3;

fn result_of(code: ErrorCode) -> usize {
    let name = code.as_str();
    RESULTS
        .iter()
        .position(|result| *result == name)
        .expect("the mailbox only answers with codes in RESULTS")
}

/// Relay storage: the store, its allowances and its request counters.
pub(crate) struct Mailbox {
    pub(crate) store: Arc<Store>,
    pub(crate) requests: RequestAllowance,
    uploads: ByteAllowance,
    ttl_seconds: u64,
    pub(crate) results: [[Counter; RESULTS.len()]; OPS.len()],
}

impl Mailbox {
    /// Storage that answers `503` until [`maintain`] has read the data directory.
    pub(crate) fn new(config: &MailboxConfig, now: Instant) -> Self {
        Self {
            store: Arc::new(Store::new(config)),
            requests: RequestAllowance::per_minute(
                config.requests_per_minute,
                config
                    .requests_per_minute
                    .saturating_mul(TOTAL_REQUESTS_PER_ADDRESS),
                now,
            ),
            uploads: ByteAllowance::new(config.upload_bytes_per_minute, now),
            ttl_seconds: config.ttl_seconds,
            results: Default::default(),
        }
    }
}

/// Reads the data directory, then deletes expired items, follows free disk space and
/// retries an unusable directory, until `shutdown` fires. The relay serves meanwhile.
pub(crate) async fn maintain(store: Arc<Store>, mut shutdown: watch::Receiver<Option<Instant>>) {
    let hooks = &store.config.hooks;
    let every = |first: Duration, period: Duration| {
        let mut interval = time::interval_at(Instant::now() + first, period);
        interval.set_missed_tick_behavior(MissedTickBehavior::Delay);
        interval
    };
    let mut sweep = every(hooks.sweep_every, hooks.sweep_every);
    let mut probe = every(hooks.probe_every, hooks.probe_every);
    let mut retry = every(Duration::ZERO, hooks.retry_every);
    let stopping = stopping(&mut shutdown);
    tokio::pin!(stopping);
    loop {
        let job: fn(&Store) = tokio::select! {
            () = &mut stopping => break,
            _ = sweep.tick() => Store::sweep,
            _ = probe.tick() => Store::probe,
            _ = retry.tick() => Store::retry,
        };
        let store = Arc::clone(&store);
        blocking(move || job(&store)).await;
    }
}

/// What a request did: a [`RESULTS`] index and the response.
struct Reply {
    result: usize,
    response: Response,
}

impl Reply {
    fn ok(response: Response) -> Self {
        Self {
            result: OK,
            response,
        }
    }

    fn error(status: StatusCode, code: ErrorCode) -> Self {
        Self {
            result: result_of(code),
            response: http_error(status, code),
        }
    }

    fn retry_after(self, seconds: u64) -> Self {
        Self {
            result: self.result,
            response: with_retry_after(self.response, seconds),
        }
    }

    fn not_found() -> Self {
        Self::error(StatusCode::NOT_FOUND, ErrorCode::NotFound)
    }

    fn bad_request() -> Self {
        Self::error(StatusCode::BAD_REQUEST, ErrorCode::BadRequest)
    }

    fn refused(refusal: Refusal) -> Self {
        match refusal {
            Refusal::Full => Self::error(StatusCode::INSUFFICIENT_STORAGE, ErrorCode::StorageFull)
                .retry_after(FULL_RETRY_SECONDS),
            Refusal::Unavailable => Self::error(
                StatusCode::SERVICE_UNAVAILABLE,
                ErrorCode::StorageUnavailable,
            )
            .retry_after(UNAVAILABLE_RETRY_SECONDS),
        }
    }
}

/// `PUT`, `GET`, `HEAD` and `DELETE` on `/v1/mailbox/{kind}`. Without storage, every
/// mailbox path is missing.
pub(crate) async fn handle(
    State(state): State<Arc<AppState>>,
    Extension(ClientKey(key)): Extension<ClientKey>,
    method: Method,
    headers: HeaderMap,
    uri: Uri,
    body: Body,
) -> Response {
    let Some(mailbox) = &state.mailbox else {
        return Reply::not_found().response;
    };
    let Some(kind) = uri.path().strip_prefix(PATH_PREFIX).and_then(Kind::parse) else {
        return Reply::not_found().response;
    };
    let op = match method {
        Method::PUT => Op::Put,
        Method::GET => Op::Get,
        Method::HEAD => Op::Head,
        Method::DELETE => Op::Delete,
        _ => {
            let mut response = http_error(StatusCode::METHOD_NOT_ALLOWED, ErrorCode::BadRequest);
            response
                .headers_mut()
                .insert(ALLOW, HeaderValue::from_static("GET, HEAD, PUT, DELETE"));
            return response;
        }
    };
    // For the whole request, so neither a slow client nor a disk that hangs holds it.
    let deadline = Instant::now() + state.config.timeouts.mailbox_body;
    let reply = match admit(&state, key, &headers, &uri) {
        Ok(file) => {
            let work = async {
                match op {
                    Op::Put => put(mailbox, key, kind, file, &headers, body).await,
                    Op::Get => get(mailbox, kind, file, &headers, false, deadline).await,
                    Op::Head => get(mailbox, kind, file, &headers, true, deadline).await,
                    Op::Delete => delete(mailbox, kind, file, &headers).await,
                }
            };
            timeout_at(deadline, work)
                .await
                .unwrap_or_else(|_| match op {
                    Op::Put => Reply::error(StatusCode::REQUEST_TIMEOUT, ErrorCode::Timeout),
                    Op::Get | Op::Head | Op::Delete => Reply::refused(Refusal::Unavailable),
                })
        }
        Err(reply) => *reply,
    };
    mailbox.results[op as usize][reply.result].inc();
    let mut response = reply.response;
    response
        .headers_mut()
        .insert(CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response
}

/// Checks the token, the ticket and the item id. Returns the item's file.
fn admit(
    state: &AppState,
    key: IpAddr,
    headers: &HeaderMap,
    uri: &Uri,
) -> Result<FileId, Box<Reply>> {
    // Before the body is read, so an unauthorized request can't hold the connection.
    let refused = match auth::check(state, key, headers, uri.query()) {
        AuthOutcome::Allowed => None,
        AuthOutcome::Unauthorized => Some((ErrorCode::Unauthorized, auth::unauthorized())),
        AuthOutcome::Locked => Some((ErrorCode::RateLimited, auth::locked())),
    };
    if let Some((code, response)) = refused {
        return Err(Box::new(Reply {
            result: result_of(code),
            response,
        }));
    }
    let ticket = header_hex::<TICKET_LEN>(headers, &TICKET);
    if !ticket.is_some_and(|ticket| state.tickets.is_live(&ticket, Instant::now())) {
        return Err(Box::new(Reply::error(
            StatusCode::FORBIDDEN,
            ErrorCode::TicketRequired,
        )));
    }
    let item =
        header_hex::<ITEM_ID_LEN>(headers, &ITEM).ok_or_else(|| Box::new(Reply::bad_request()))?;
    Ok(Sha256::digest(item).into())
}

/// `PUT`: stores the body under the item id, in place of what was there.
async fn put(
    mailbox: &Mailbox,
    key: IpAddr,
    kind: Kind,
    file: FileId,
    headers: &HeaderMap,
    body: Body,
) -> Reply {
    let len = headers
        .get(CONTENT_LENGTH)
        .and_then(|value| value.to_str().ok()?.parse::<u64>().ok());
    let Some(len) = len.filter(|_| !headers.contains_key(TRANSFER_ENCODING)) else {
        return Reply::bad_request();
    };
    if len > kind.max_bytes() {
        return Reply::error(StatusCode::PAYLOAD_TOO_LARGE, ErrorCode::ItemTooBig);
    }
    let (Some(tag), Some(ttl)) = (
        header_hex::<TAG_LEN>(headers, &TAG),
        ttl_of(headers, mailbox.ttl_seconds),
    ) else {
        return Reply::bad_request();
    };
    let store = &mailbox.store;
    // Write-once: the devices of a group leave a shared item once. Checked before any
    // quota is reserved or a byte of the body is read, and again by the commit.
    let create_only = headers
        .get(IF_NONE_MATCH)
        .is_some_and(|value| value.as_bytes() == b"*");
    if create_only {
        match store.lookup(&file, kind, store.now()) {
            Lookup::Found(_) => {
                return Reply::error(StatusCode::PRECONDITION_FAILED, ErrorCode::ItemExists);
            }
            Lookup::Unavailable => return Reply::refused(Refusal::Unavailable),
            Lookup::Missing | Lookup::Expired => {}
        }
    }
    let reservation = match store.reserve(key, file, len) {
        Ok(reservation) => reservation,
        Err(refusal) => return Reply::refused(refusal),
    };
    if let Err(wait) = mailbox.uploads.try_take(key, len, Instant::now()) {
        return Reply::error(StatusCode::TOO_MANY_REQUESTS, ErrorCode::RateLimited)
            .retry_after(wait.as_secs_f64().ceil() as u64);
    }
    let created_at = store.now();
    let header = Header {
        kind,
        tag,
        created_at,
        expires_at: created_at.saturating_add(ttl),
        len,
    };
    let creating = Arc::clone(store);
    let (temp, file) = match blocking(move || creating.create_temp()).await {
        Some(Ok(created)) => created,
        Some(Err(error)) => return io_failed(store, error).await,
        None => return Reply::refused(Refusal::Unavailable),
    };
    if let Err(failure) = upload(File::from_std(file), &header, body).await {
        let store = Arc::clone(store);
        let reply = blocking(move || {
            // Deleted first, so a probe sees its space free.
            temp.remove();
            match failure {
                Upload::Io(error) => {
                    tracing::debug!(%error, "a mailbox upload failed to write");
                    Reply::refused(store.on_io_error(&error))
                }
                Upload::Body => Reply::bad_request(),
            }
        })
        .await;
        return reply.unwrap_or_else(|| Reply::refused(Refusal::Unavailable));
    }
    let committing = Arc::clone(store);
    let committed =
        blocking(move || committing.commit(reservation, temp, header, create_only)).await;
    match committed {
        Some(Ok(())) => {
            let mut response = StatusCode::NO_CONTENT.into_response();
            response
                .headers_mut()
                .insert(EXPIRES, HeaderValue::from(header.expires_at));
            Reply::ok(response)
        }
        Some(Err(CommitError::Io(error))) => io_failed(store, error).await,
        Some(Err(CommitError::Exists)) => {
            Reply::error(StatusCode::PRECONDITION_FAILED, ErrorCode::ItemExists)
        }
        Some(Err(CommitError::Refused(refusal))) => Reply::refused(refusal),
        None => Reply::refused(Refusal::Unavailable),
    }
}

/// The answer to a failed create or rename of an upload.
async fn io_failed(store: &Arc<Store>, error: io::Error) -> Reply {
    let store = Arc::clone(store);
    let refusal = blocking(move || store.on_io_error(&error)).await;
    Reply::refused(refusal.unwrap_or(Refusal::Unavailable))
}

enum Upload {
    /// Writing or syncing the temp file failed.
    Io(io::Error),
    /// The body broke off or doesn't match its `Content-Length`.
    Body,
}

/// Writes `header` and the body of `header.len` bytes to `file`, and syncs it.
/// The file stages each write itself, so an upload holds about one body frame.
async fn upload(mut file: File, header: &Header, mut body: Body) -> Result<(), Upload> {
    file.write_all(&header.encode()).await.map_err(Upload::Io)?;
    let mut left = header.len;
    while let Some(frame) = poll_fn(|cx| Pin::new(&mut body).poll_frame(cx)).await {
        let Ok(data) = frame.map_err(|_| Upload::Body)?.into_data() else {
            continue;
        };
        left = left.checked_sub(data.len() as u64).ok_or(Upload::Body)?;
        file.write_all(&data).await.map_err(Upload::Io)?;
    }
    if left != 0 {
        return Err(Upload::Body);
    }
    file.flush().await.map_err(Upload::Io)?;
    file.sync_all().await.map_err(Upload::Io)
}

/// `GET` and `HEAD`: the item, `304` while the client has its tag, or the body from the
/// offset of `Range: bytes=N-`. A body still unsent at `deadline` is cut off.
async fn get(
    mailbox: &Mailbox,
    kind: Kind,
    file: FileId,
    headers: &HeaderMap,
    head: bool,
    deadline: Instant,
) -> Reply {
    let store = &mailbox.store;
    let now = store.now();
    let header = match store.lookup(&file, kind, now) {
        Lookup::Found(header) => header,
        Lookup::Missing => return Reply::not_found(),
        Lookup::Expired => {
            let store = Arc::clone(store);
            tokio::task::spawn_blocking(move || store.expire(&file));
            return Reply::not_found();
        }
        Lookup::Unavailable => return Reply::refused(Refusal::Unavailable),
    };
    let cached = headers
        .get(IF_NONE_MATCH)
        .and_then(|value| tag_of(value.to_str().ok()?));
    if !head && cached == Some(header.tag) {
        let mut response = StatusCode::NOT_MODIFIED.into_response();
        item_headers(&mut response, &header);
        return Reply {
            result: NOT_MODIFIED,
            response,
        };
    }
    if head {
        let mut response = StatusCode::OK.into_response();
        item_headers(&mut response, &header);
        content_headers(&mut response, header.len);
        return Reply::ok(response);
    }
    // The file may have been replaced since the lookup: answer with what it holds.
    let path = store.item_path(&file);
    let (header, mut file) = match blocking(move || open_item(&path)).await {
        Some(Ok(Some((on_disk, file)))) if on_disk.kind == kind && !on_disk.is_expired(now) => {
            (on_disk, File::from_std(file))
        }
        Some(Ok(_)) => return Reply::not_found(),
        Some(Err(error)) => {
            tracing::debug!(%error, "could not read a mailbox item");
            return Reply::refused(Refusal::Unavailable);
        }
        None => return Reply::refused(Refusal::Unavailable),
    };
    // A resumed download: the range holds only while the item is the one it started.
    let etag = etag_of(&header);
    let start = range_start(headers).filter(|_| {
        headers
            .get_all(IF_RANGE)
            .iter()
            .all(|value| value.as_bytes() == etag.as_bytes())
    });
    let Some(start) = start else {
        let mut response = file_body(file, header.len, deadline).into_response();
        item_headers(&mut response, &header);
        content_headers(&mut response, header.len);
        return Reply::ok(response);
    };
    if start >= header.len {
        let mut response = StatusCode::RANGE_NOT_SATISFIABLE.into_response();
        item_headers(&mut response, &header);
        content_range(&mut response, &format!("bytes */{}", header.len));
        return Reply {
            result: RANGE_NOT_SATISFIABLE,
            response,
        };
    }
    if let Err(error) = file.seek(SeekFrom::Start(HEADER_LEN as u64 + start)).await {
        tracing::debug!(%error, "could not read a mailbox item");
        return Reply::refused(Refusal::Unavailable);
    }
    let len = header.len - start;
    let body = file_body(file, len, deadline);
    let mut response = (StatusCode::PARTIAL_CONTENT, body).into_response();
    item_headers(&mut response, &header);
    content_headers(&mut response, len);
    let last = header.len - 1;
    content_range(
        &mut response,
        &format!("bytes {start}-{last}/{}", header.len),
    );
    Reply {
        result: PARTIAL_CONTENT,
        response,
    }
}

/// `DELETE`, unless `If-Match` names another tag.
async fn delete(mailbox: &Mailbox, kind: Kind, file: FileId, headers: &HeaderMap) -> Reply {
    let if_match = headers
        .get(IF_MATCH)
        .map(|value| value.to_str().ok().and_then(tag_of));
    let store = Arc::clone(&mailbox.store);
    match blocking(move || store.delete(&file, kind, if_match)).await {
        Some(Deleted::Yes) => Reply::ok(StatusCode::NO_CONTENT.into_response()),
        Some(Deleted::Missing) => Reply::not_found(),
        Some(Deleted::TagMismatch) => {
            Reply::error(StatusCode::PRECONDITION_FAILED, ErrorCode::TagMismatch)
        }
        Some(Deleted::Unavailable) | None => Reply::refused(Refusal::Unavailable),
    }
}

/// Runs blocking file work off the async workers. `None` if it panicked.
async fn blocking<T: Send + 'static>(work: impl FnOnce() -> T + Send + 'static) -> Option<T> {
    tokio::task::spawn_blocking(work).await.ok()
}

/// The tag, expiry and version of an item, and that a `GET` may resume.
fn item_headers(response: &mut Response, header: &Header) {
    let headers = response.headers_mut();
    headers.insert(
        TAG,
        HeaderValue::try_from(encode_hex(&header.tag)).expect("hex is a valid header value"),
    );
    headers.insert(EXPIRES, HeaderValue::from(header.expires_at));
    headers.insert(
        ETAG,
        HeaderValue::try_from(etag_of(header)).expect("hex is a valid header value"),
    );
    headers.insert(ACCEPT_RANGES, HeaderValue::from_static("bytes"));
}

/// The strong `ETag` of an item: `"<created_at>-<len>"` in lowercase hex. Every upload
/// gets a new creation time, so an item uploaded again in a later second gets a new one.
fn etag_of(header: &Header) -> String {
    format!("\"{:x}-{:x}\"", header.created_at, header.len)
}

fn content_range(response: &mut Response, value: &str) {
    response.headers_mut().insert(
        CONTENT_RANGE,
        HeaderValue::try_from(value).expect("a range is a valid header value"),
    );
}

fn content_headers(response: &mut Response, len: u64) {
    let headers = response.headers_mut();
    headers.insert(
        CONTENT_TYPE,
        HeaderValue::from_static("application/octet-stream"),
    );
    headers.insert(CONTENT_LENGTH, HeaderValue::from(len));
}

/// `N` bytes in the lowercase hex of header `name`.
fn header_hex<const N: usize>(headers: &HeaderMap, name: &HeaderName) -> Option<[u8; N]> {
    decode_hex(headers.get(name)?.to_str().ok()?)
}

/// `N` of `Range: bytes=N-`, the one range form the relay serves. `None` for any other
/// form, or several `Range` headers: the relay ignores those and sends the whole body.
fn range_start(headers: &HeaderMap) -> Option<u64> {
    let mut values = headers.get_all(RANGE).iter();
    let (Some(value), None) = (values.next(), values.next()) else {
        return None;
    };
    let digits = value
        .as_bytes()
        .strip_prefix(b"bytes=")?
        .strip_suffix(b"-")?;
    if digits.is_empty() || !digits.iter().all(u8::is_ascii_digit) {
        return None;
    }
    // Only digits, so only an offset past `u64` fails to parse.
    std::str::from_utf8(digits).ok()?.parse().ok()
}

/// A tag as `If-Match` and `If-None-Match` carry it, quoted or not.
fn tag_of(value: &str) -> Option<Tag> {
    let unquoted = value
        .strip_prefix('"')
        .and_then(|value| value.strip_suffix('"'))
        .unwrap_or(value);
    decode_hex(unquoted)
}

/// The lifetime a `PUT` asks for in `X-Keyguard-Ttl`, from a minute to `max`; `max`
/// without the header. `None` if it isn't a decimal number.
fn ttl_of(headers: &HeaderMap, max: u64) -> Option<u64> {
    let Some(value) = headers.get(TTL) else {
        return Some(max);
    };
    let text = value.to_str().ok()?;
    if text.is_empty() || !text.bytes().all(|byte| byte.is_ascii_digit()) {
        return None;
    }
    // Only digits: a number too large to parse is longer than any limit.
    let seconds = text.parse::<u64>().unwrap_or(u64::MAX);
    Some(seconds.max(MIN_TTL_SECONDS).min(max))
}

/// The first `len` bytes of `file`, read in chunks as the client takes them. A task owns
/// the file and closes it at `deadline`, even while the client reads nothing, so a
/// deleted item can't keep its disk space. A body cut short ends the connection.
fn file_body(file: File, len: u64, deadline: Instant) -> Body {
    let (chunks, received) = mpsc::channel(1);
    tokio::spawn(async move {
        let mut file = file.take(len);
        let copy = async {
            loop {
                let mut chunk = BytesMut::with_capacity(READ_CHUNK);
                let chunk = match file.read_buf(&mut chunk).await {
                    Ok(0) => return,
                    Ok(_) => Ok(chunk.freeze()),
                    Err(error) => Err(error),
                };
                let failed = chunk.is_err();
                if chunks.send(chunk).await.is_err() || failed {
                    return;
                }
            }
        };
        _ = timeout_at(deadline, copy).await;
    });
    Body::new(Chunks(received))
}

/// A body of the chunks a channel receives.
struct Chunks(mpsc::Receiver<io::Result<Bytes>>);

impl hyper::body::Body for Chunks {
    type Data = Bytes;
    type Error = io::Error;

    fn poll_frame(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
    ) -> Poll<Option<io::Result<Frame<Bytes>>>> {
        let chunk = ready!(self.get_mut().0.poll_recv(cx));
        Poll::Ready(chunk.map(|chunk| chunk.map(Frame::data)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_mailbox_error_code_has_a_result_label() {
        for code in [
            ErrorCode::Unauthorized,
            ErrorCode::RateLimited,
            ErrorCode::TicketRequired,
            ErrorCode::BadRequest,
            ErrorCode::NotFound,
            ErrorCode::ItemTooBig,
            ErrorCode::TagMismatch,
            ErrorCode::ItemExists,
            ErrorCode::StorageFull,
            ErrorCode::StorageUnavailable,
            ErrorCode::Timeout,
        ] {
            assert_eq!(RESULTS[result_of(code)], code.as_str());
        }
        for (result, label) in [
            (OK, "ok"),
            (NOT_MODIFIED, "not_modified"),
            (PARTIAL_CONTENT, "partial_content"),
            (RANGE_NOT_SATISFIABLE, "range_not_satisfiable"),
        ] {
            assert_eq!(RESULTS[result], label);
        }
    }

    #[test]
    fn only_one_open_ended_byte_range_is_served() {
        let start = |values: &[&'static str]| {
            let mut headers = HeaderMap::new();
            for value in values {
                headers.append(RANGE, HeaderValue::from_static(value));
            }
            range_start(&headers)
        };
        assert_eq!(start(&[]), None);
        assert_eq!(start(&["bytes=0-"]), Some(0));
        assert_eq!(start(&["bytes=17825791-"]), Some(17_825_791));
        assert_eq!(start(&["bytes=0018-"]), Some(18));
        assert_eq!(
            start(&["bytes=18446744073709551615-"]),
            Some(u64::MAX),
            "the largest offset parses and is past any item"
        );
        for ignored in [
            "bytes=18446744073709551616-",
            "bytes=-5",
            "bytes=5-9",
            "bytes=5-,10-",
            "bytes=0-0,-1",
            "bytes= 5-",
            "bytes=5 -",
            "bytes =5-",
            "Bytes=5-",
            "bytes=+5-",
            "bytes=-",
            "bytes=",
            "items=5-",
            "5-",
        ] {
            assert_eq!(start(&[ignored]), None, "{ignored:?}");
        }
        assert_eq!(start(&["bytes=5-", "bytes=10-"]), None);
    }

    #[test]
    fn tags_may_be_quoted() {
        let tag = "0123456789abcdef0123456789abcdef";
        let expected = decode_hex::<TAG_LEN>(tag);
        assert!(expected.is_some());
        assert_eq!(tag_of(tag), expected);
        assert_eq!(tag_of(&format!("\"{tag}\"")), expected);
        assert_eq!(tag_of(&format!("\"{tag}")), None);
        assert_eq!(tag_of(&format!("W/\"{tag}\"")), None);
        assert_eq!(tag_of(&tag.to_uppercase()), None);
    }

    #[test]
    fn lifetimes_are_clamped_to_a_minute_and_the_configured_maximum() {
        let ttl = |value: Option<&'static str>| {
            let mut headers = HeaderMap::new();
            if let Some(value) = value {
                headers.insert(TTL, HeaderValue::from_static(value));
            }
            ttl_of(&headers, 3_600)
        };
        assert_eq!(ttl(None), Some(3_600));
        assert_eq!(ttl(Some("0")), Some(60));
        assert_eq!(ttl(Some("600")), Some(600));
        assert_eq!(ttl(Some("99999999999999999999999")), Some(3_600));
        for bad in ["", "-1", "1.5", " 60", "1e3"] {
            assert_eq!(ttl(Some(bad)), None, "{bad:?}");
        }
    }
}
