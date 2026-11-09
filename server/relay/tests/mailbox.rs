//! Relay storage: the mailbox API, its quotas, expiry, restarts and trimming under disk
//! pressure.

mod common;

use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::Duration;

use common::*;
use common::{peer_id as tag, room_id as item_id};
use keyguard_relay::config::{FreeSpace, MailboxConfig, Timeouts};
use keyguard_relay::{Config, MAILBOX_MAX_BLOB_BYTES, MAILBOX_MAX_STATE_BYTES};
use serde_json::json;
use sha2::{Digest, Sha256};
use tempfile::TempDir;
use tokio::time::Instant;

/// A relay and a connection in a room, whose ticket mailbox requests carry.
struct Fixture {
    relay: Relay,
    client: Client,
    ticket: String,
}

impl Fixture {
    /// A relay whose storage has read its directory.
    async fn start(config: Config) -> Self {
        let fixture = Self::connect(config).await;
        wait_until_ready(&fixture.relay).await;
        fixture
    }

    /// A relay, without waiting for its storage.
    async fn connect(config: Config) -> Self {
        let relay = Relay::start(config).await;
        let mut client = relay.connect().await;
        let ticket = join_ticket(&mut client, &room_id(1)).await;
        Self {
            relay,
            client,
            ticket,
        }
    }

    async fn stop(self) {
        drop(self.client);
        self.relay.shutdown().await;
    }

    async fn request(
        &self,
        method: &str,
        kind: &str,
        item: u8,
        headers: &[(&str, &str)],
        body: &str,
    ) -> HttpResponse {
        let item = item_id(item);
        let mut all = vec![
            ("X-Keyguard-Ticket", self.ticket.as_str()),
            ("X-Keyguard-Item", item.as_str()),
        ];
        all.extend_from_slice(headers);
        self.relay
            .http_with_body(method, &format!("/v1/mailbox/{kind}"), &all, body)
            .await
    }

    async fn put(&self, kind: &str, item: u8, tag_n: u8, body: &str) -> HttpResponse {
        self.put_with(kind, item, tag_n, body, &[]).await
    }

    async fn put_with(
        &self,
        kind: &str,
        item: u8,
        tag_n: u8,
        body: &str,
        headers: &[(&str, &str)],
    ) -> HttpResponse {
        let tag = tag(tag_n);
        let mut all = vec![("X-Keyguard-Tag", tag.as_str())];
        all.extend_from_slice(headers);
        self.request("PUT", kind, item, &all, body).await
    }

    /// `PUT` from the client address `ip`, through a relay that trusts loopback.
    async fn put_from(&self, ip: &str, kind: &str, item: u8, body: &str) -> HttpResponse {
        self.put_with(kind, item, 1, body, &[("X-Forwarded-For", ip)])
            .await
    }

    async fn get(&self, kind: &str, item: u8) -> HttpResponse {
        self.request("GET", kind, item, &[], "").await
    }

    async fn wait_for_metric(&self, name: &str, value: f64) {
        self.relay.wait_for_metric(name, value).await;
    }

    async fn metric(&self, name: &str) -> f64 {
        self.relay.metrics().await.metric(name)
    }
}

/// Waits until the storage of `relay` has read its directory: it serves before.
async fn wait_until_ready(relay: &Relay) {
    relay
        .wait_for_metric("keyguard_relay_mailbox_available", 1.0)
        .await;
}

/// The storage of `config`.
fn storage(config: &mut Config) -> &mut MailboxConfig {
    config.mailbox.as_mut().expect("storage is configured")
}

/// Background work every 50 milliseconds instead of every 30 to 60 seconds.
fn fast(config: &mut Config) {
    let hooks = &mut storage(config).hooks;
    hooks.sweep_every = Duration::from_millis(50);
    hooks.probe_every = Duration::from_millis(50);
    hooks.retry_every = Duration::from_millis(50);
}

/// A clock the test moves forward.
#[derive(Clone)]
struct TestClock(Arc<AtomicU64>);

impl TestClock {
    fn install(config: &mut Config) -> Self {
        let clock = Self(Arc::new(AtomicU64::new(1_800_000_000)));
        let now = Arc::clone(&clock.0);
        storage(config).hooks.clock = Some(Arc::new(move || now.load(Ordering::Relaxed)));
        clock
    }

    fn now(&self) -> u64 {
        self.0.load(Ordering::Relaxed)
    }

    fn advance(&self, seconds: u64) {
        self.0.fetch_add(seconds, Ordering::Relaxed);
    }
}

/// A volume of `capacity` bytes that holds only the data directory, and can fail.
#[derive(Clone)]
struct Volume {
    capacity: Arc<AtomicU64>,
    broken: Arc<AtomicBool>,
}

impl Volume {
    fn install(config: &mut Config, capacity: u64) -> Self {
        let volume = Self {
            capacity: Arc::new(AtomicU64::new(capacity)),
            broken: Arc::new(AtomicBool::new(false)),
        };
        let dir = storage(config).dir.clone();
        let (size, broken) = (Arc::clone(&volume.capacity), Arc::clone(&volume.broken));
        let free_space: FreeSpace = Arc::new(move || {
            if broken.load(Ordering::Relaxed) {
                return Err(io::Error::other("the volume is gone"));
            }
            Ok(size.load(Ordering::Relaxed).saturating_sub(dir_size(&dir)))
        });
        storage(config).hooks.free_space = Some(free_space);
        volume
    }

    fn resize(&self, capacity: u64) {
        self.capacity.store(capacity, Ordering::Relaxed);
    }
}

/// Bytes in the item and temp files.
fn dir_size(dir: &Path) -> u64 {
    ["items", "tmp"]
        .into_iter()
        .filter_map(|sub| fs::read_dir(dir.join(sub)).ok())
        .flatten()
        .filter_map(|entry| entry.ok()?.metadata().ok())
        .map(|metadata| metadata.len())
        .sum()
}

fn file_names(dir: &Path) -> Vec<String> {
    let mut names: Vec<String> = fs::read_dir(dir)
        .unwrap()
        .map(|entry| entry.unwrap().file_name().into_string().unwrap())
        .collect();
    names.sort();
    names
}

/// Waits until `dir` holds `count` files: they are deleted after the index forgets them.
async fn wait_for_files(dir: &Path, count: usize) {
    let deadline = Instant::now() + TIMEOUT;
    loop {
        let names = file_names(dir);
        if names.len() == count {
            return;
        }
        assert!(
            Instant::now() < deadline,
            "{names:?}, expected {count} files"
        );
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
}

fn expect_refusal(response: &HttpResponse, status: u16, code: &str) {
    assert_eq!(response.status, status, "{}", response.body);
    assert_eq!(response.json(), json!({ "error": code }));
}

#[tokio::test]
async fn items_round_trip_through_put_get_head_and_delete() {
    let dir = TempDir::new().unwrap();
    let relay = Fixture::start(mailbox_config(dir.path())).await;

    let put = relay.put("state", 1, 1, "sealed state").await;
    assert_eq!(put.status, 204, "{}", put.body);
    assert_eq!(put.header("cache-control"), Some("no-store"));
    let expires: u64 = put.header("x-keyguard-expires").unwrap().parse().unwrap();
    // Seven days by default.
    assert!(expires.abs_diff(unix_now() + 604_800) <= 5, "{expires}");

    let get = relay.get("state", 1).await;
    assert_eq!(get.status, 200);
    assert_eq!(get.body, "sealed state");
    assert_eq!(get.header("content-type"), Some("application/octet-stream"));
    assert_eq!(get.header("content-length"), Some("12"));
    assert_eq!(get.header("x-keyguard-tag"), Some(tag(1).as_str()));
    assert_eq!(
        get.header("x-keyguard-expires"),
        put.header("x-keyguard-expires")
    );
    assert_eq!(get.header("cache-control"), Some("no-store"));

    let head = relay.request("HEAD", "state", 1, &[], "").await;
    assert_eq!(head.status, 200);
    assert_eq!(head.body, "");
    assert_eq!(head.header("content-length"), Some("12"));
    assert_eq!(head.header("x-keyguard-tag"), Some(tag(1).as_str()));
    assert_eq!(
        head.header("x-keyguard-expires"),
        put.header("x-keyguard-expires")
    );

    // An item exists under one kind only.
    for method in ["GET", "HEAD", "DELETE"] {
        let other = relay.request(method, "blob", 1, &[], "").await;
        assert_eq!(other.status, 404, "{method}");
    }
    expect_refusal(&relay.get("blob", 1).await, 404, "not_found");

    // The disk holds a hash of the item id, never the id.
    let items = dir.path().join("items");
    let name: String = Sha256::digest([1u8; 32])
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect();
    assert_eq!(file_names(&items), [name]);

    // A replacement takes the place of the item.
    assert_eq!(relay.put("state", 1, 2, "newer state").await.status, 204);
    let get = relay.get("state", 1).await;
    assert_eq!(get.body, "newer state");
    assert_eq!(get.header("x-keyguard-tag"), Some(tag(2).as_str()));
    assert_eq!(file_names(&items).len(), 1);

    let delete = relay.request("DELETE", "state", 1, &[], "").await;
    assert_eq!(delete.status, 204);
    expect_refusal(&relay.get("state", 1).await, 404, "not_found");
    expect_refusal(
        &relay.request("DELETE", "state", 1, &[], "").await,
        404,
        "not_found",
    );
    assert!(file_names(&items).is_empty());

    // Blobs larger than a read chunk come back whole.
    let blob: String = (0..200_000u32)
        .map(|n| char::from(b'a' + (n % 26) as u8))
        .collect();
    assert_eq!(relay.put("blob", 2, 3, &blob).await.status, 204);
    let get = relay.get("blob", 2).await;
    assert_eq!(get.status, 200);
    assert_eq!(get.header("content-length"), Some("200000"));
    assert!(get.body == blob, "the blob comes back as stored");
    // Nothing is left behind in the temp directory.
    assert!(file_names(&dir.path().join("tmp")).is_empty());
}

#[tokio::test]
async fn known_tags_answer_not_modified_and_stale_ones_fail_a_delete() {
    let dir = TempDir::new().unwrap();
    let relay = Fixture::start(mailbox_config(dir.path())).await;
    assert_eq!(relay.put("state", 1, 1, "state").await.status, 204);

    let quoted = format!("\"{}\"", tag(1));
    for cached in [tag(1), quoted.clone()] {
        let answer = relay
            .request("GET", "state", 1, &[("If-None-Match", &cached)], "")
            .await;
        assert_eq!(answer.status, 304, "{cached}");
        assert_eq!(answer.body, "");
        assert_eq!(answer.header("x-keyguard-tag"), Some(tag(1).as_str()));
        assert!(answer.header("x-keyguard-expires").is_some());
    }
    let answer = relay
        .request("GET", "state", 1, &[("If-None-Match", &tag(2))], "")
        .await;
    assert_eq!((answer.status, answer.body.as_str()), (200, "state"));

    for stale in [tag(2), "not a tag".to_owned()] {
        let answer = relay
            .request("DELETE", "state", 1, &[("If-Match", &stale)], "")
            .await;
        expect_refusal(&answer, 412, "tag_mismatch");
    }
    assert_eq!(relay.get("state", 1).await.status, 200);
    let answer = relay
        .request("DELETE", "state", 1, &[("If-Match", &quoted)], "")
        .await;
    assert_eq!(answer.status, 204);
    assert_eq!(relay.get("state", 1).await.status, 404);
}

/// The `ETag` of an item created at `created_at` with a body of `len` bytes.
fn etag_of(created_at: u64, len: usize) -> String {
    format!("\"{created_at:x}-{len:x}\"")
}

/// Checks the headers that describe the item on every answer about it.
fn expect_item_headers(response: &HttpResponse, tag_n: u8, etag: &str) {
    assert_eq!(response.header("x-keyguard-tag"), Some(tag(tag_n).as_str()));
    assert!(response.header("x-keyguard-expires").is_some());
    assert_eq!(response.header("etag"), Some(etag));
    assert_eq!(response.header("accept-ranges"), Some("bytes"));
}

#[tokio::test]
async fn a_get_resumes_from_an_open_ended_byte_range() {
    let dir = TempDir::new().unwrap();
    let mut config = mailbox_config(dir.path());
    let clock = TestClock::install(&mut config);
    let relay = Fixture::start(config).await;
    // Longer than a read chunk, so a resumed body starts inside one.
    let blob: String = (0..200_000u32)
        .map(|n| char::from(b'a' + (n % 26) as u8))
        .collect();
    let len = blob.len();
    assert_eq!(relay.put("blob", 1, 1, &blob).await.status, 204);
    let etag = etag_of(clock.now(), len);
    let get = |headers: Vec<(&'static str, String)>| {
        let relay = &relay;
        async move {
            let headers: Vec<(&str, &str)> = headers
                .iter()
                .map(|(name, value)| (*name, value.as_str()))
                .collect();
            relay.request("GET", "blob", 1, &headers, "").await
        }
    };

    let whole = get(vec![]).await;
    assert_eq!(whole.status, 200);
    expect_item_headers(&whole, 1, &etag);
    assert_eq!(whole.header("content-range"), None);

    for start in [0, 1, 65_536, 70_001, len - 1] {
        let range = format!("bytes={start}-");
        for if_range in [None, Some(etag.clone())] {
            let mut headers = vec![("Range", range.clone())];
            headers.extend(if_range.map(|etag| ("If-Range", etag)));
            let part = get(headers).await;
            assert_eq!(part.status, 206, "{range}");
            assert!(part.body == blob[start..], "{range}: the rest of the blob");
            expect_item_headers(&part, 1, &etag);
            assert_eq!(
                part.header("content-length"),
                Some((len - start).to_string().as_str())
            );
            assert_eq!(
                part.header("content-range"),
                Some(format!("bytes {start}-{}/{len}", len - 1).as_str())
            );
            assert_eq!(
                part.header("content-type"),
                Some("application/octet-stream")
            );
            assert_eq!(part.header("cache-control"), Some("no-store"));
        }
    }

    // At or past the end: nothing to send.
    for start in [len.to_string(), (len + 1).to_string(), u64::MAX.to_string()] {
        let answer = get(vec![("Range", format!("bytes={start}-"))]).await;
        assert_eq!(answer.status, 416, "{start}");
        assert_eq!(answer.body, "");
        assert_eq!(
            answer.header("content-range"),
            Some(format!("bytes */{len}").as_str())
        );
        expect_item_headers(&answer, 1, &etag);
    }

    // Another version, or a range form the relay doesn't serve: the whole item.
    let other_versions = [
        etag_of(clock.now() - 1, len),
        etag_of(clock.now(), len + 1),
        format!("W/{etag}"),
        etag.trim_matches('"').to_owned(),
        tag(1),
    ];
    for if_range in other_versions {
        for start in [1, len] {
            let headers = vec![
                ("Range", format!("bytes={start}-")),
                ("If-Range", if_range.clone()),
            ];
            let answer = get(headers).await;
            assert_eq!(answer.status, 200, "{if_range} {start}");
            assert!(answer.body == blob, "{if_range}: the whole blob");
            assert_eq!(answer.header("content-range"), None);
        }
    }
    for ignored in [
        "bytes=-5",
        "bytes=5-9",
        "bytes=5-,10-",
        "bytes= 5-",
        "bytes=+5-",
        "bytes=18446744073709551616-",
        "items=5-",
        "nonsense",
    ] {
        let answer = get(vec![("Range", ignored.to_owned())]).await;
        assert_eq!(answer.status, 200, "{ignored}");
        assert!(answer.body == blob, "{ignored}: the whole blob");
        assert_eq!(answer.header("content-range"), None);
    }

    // `If-None-Match` comes first, and `HEAD` ignores the range.
    let range = ("Range", "bytes=1-".to_owned());
    let cached = get(vec![range.clone(), ("If-None-Match", tag(1))]).await;
    assert_eq!((cached.status, cached.body.as_str()), (304, ""));
    expect_item_headers(&cached, 1, &etag);
    let head = relay
        .request("HEAD", "blob", 1, &[("Range", "bytes=1-")], "")
        .await;
    assert_eq!((head.status, head.body.as_str()), (200, ""));
    assert_eq!(
        head.header("content-length"),
        Some(len.to_string().as_str())
    );
    assert_eq!(head.header("content-range"), None);
    expect_item_headers(&head, 1, &etag);

    for (result, count) in [("partial_content", 10.0), ("range_not_satisfiable", 3.0)] {
        assert_eq!(
            relay
                .metric(&format!(
                    "keyguard_relay_mailbox_requests_total{{op=\"get\",result=\"{result}\"}}"
                ))
                .await,
            count,
            "{result}"
        );
    }
    relay.stop().await;
}

#[tokio::test]
async fn an_item_uploaded_again_gets_a_new_etag() {
    let dir = TempDir::new().unwrap();
    let mut config = mailbox_config(dir.path());
    storage(&mut config).ttl_seconds = 3_600;
    let clock = TestClock::install(&mut config);
    let relay = Fixture::start(config).await;
    let once = [("If-None-Match", "*")];
    let resume = |etag: &str| {
        let relay = &relay;
        let headers = [
            ("Range", "bytes=2-".to_owned()),
            ("If-Range", etag.to_owned()),
        ];
        async move {
            let headers: Vec<(&str, &str)> = headers
                .iter()
                .map(|(name, value)| (*name, value.as_str()))
                .collect();
            relay.request("GET", "blob", 1, &headers, "").await
        }
    };

    assert_eq!(
        relay.put_with("blob", 1, 1, "first", &once).await.status,
        204
    );
    let first = etag_of(clock.now(), 5);
    assert_eq!(
        relay.get("blob", 1).await.header("etag"),
        Some(first.as_str())
    );

    // Expired, then written again: a download of the old item can't resume.
    clock.advance(3_600);
    assert_eq!(
        relay.put_with("blob", 1, 2, "again", &once).await.status,
        204
    );
    let again = etag_of(clock.now(), 5);
    assert_ne!(again, first);
    let stale = resume(&first).await;
    assert_eq!((stale.status, stale.body.as_str()), (200, "again"));
    assert_eq!(stale.header("etag"), Some(again.as_str()));
    let resumed = resume(&again).await;
    assert_eq!((resumed.status, resumed.body.as_str()), (206, "ain"));

    // Deleted, then written again a second later.
    let delete = relay.request("DELETE", "blob", 1, &[], "").await;
    assert_eq!(delete.status, 204);
    clock.advance(1);
    assert_eq!(
        relay.put_with("blob", 1, 3, "third", &once).await.status,
        204
    );
    let third = etag_of(clock.now(), 5);
    assert_ne!(third, again);
    let stale = resume(&again).await;
    assert_eq!((stale.status, stale.body.as_str()), (200, "third"));
    assert_eq!(stale.header("etag"), Some(third.as_str()));
    relay.stop().await;
}

#[tokio::test]
async fn a_put_with_if_none_match_star_never_replaces_a_live_item() {
    let dir = TempDir::new().unwrap();
    let mut config = mailbox_config(dir.path());
    storage(&mut config).ttl_seconds = 3_600;
    let clock = TestClock::install(&mut config);
    let relay = Fixture::start(config).await;
    let once = [("If-None-Match", "*")];

    let first = relay.put_with("blob", 1, 1, "first", &once).await;
    assert_eq!(first.status, 204, "{}", first.body);
    let second = relay.put_with("blob", 1, 2, "second", &once).await;
    expect_refusal(&second, 412, "item_exists");
    let kept = relay.get("blob", 1).await;
    assert_eq!((kept.status, kept.body.as_str()), (200, "first"));
    assert_eq!(kept.header("x-keyguard-tag"), Some(tag(1).as_str()));

    // Without the header, a PUT replaces the item as before.
    let replaced = relay.put("blob", 1, 3, "third").await;
    assert_eq!(replaced.status, 204, "{}", replaced.body);
    assert_eq!(relay.get("blob", 1).await.body, "third");

    // An expired item no longer counts.
    clock.advance(3_601);
    let again = relay.put_with("blob", 1, 4, "fourth", &once).await;
    assert_eq!(again.status, 204, "{}", again.body);
    assert_eq!(relay.get("blob", 1).await.body, "fourth");
    relay.stop().await;
}

#[tokio::test]
async fn requests_need_the_token_a_live_ticket_and_an_item_id() {
    let dir = TempDir::new().unwrap();
    let token = "private-relay-token";
    let relay = Relay::start(Config {
        token: Some(token.to_owned()),
        ..mailbox_config(dir.path())
    })
    .await;
    wait_until_ready(&relay).await;
    let mut client = Client::welcomed(relay.try_connect("", Some(token)).await.unwrap()).await;
    let ticket = join_ticket(&mut client, &room_id(1)).await;
    let bearer = format!("Bearer {token}");
    let item = item_id(1);
    let tag = tag(1);
    let request = |headers: Vec<(&'static str, String)>| {
        let (relay, tag) = (&relay, &tag);
        async move {
            let mut all: Vec<(&str, &str)> = vec![("X-Keyguard-Tag", tag.as_str())];
            all.extend(headers.iter().map(|(name, value)| (*name, value.as_str())));
            relay
                .http_with_body("PUT", "/v1/mailbox/state", &all, "state")
                .await
        }
    };

    let answer = request(vec![
        ("X-Keyguard-Ticket", ticket.clone()),
        ("X-Keyguard-Item", item.clone()),
    ])
    .await;
    expect_refusal(&answer, 401, "unauthorized");
    assert_eq!(answer.header("www-authenticate"), Some("Bearer"));

    for ticket in [None, Some(format!("{ticket}0")), Some(peer_id(9))] {
        let mut headers = vec![
            ("Authorization", bearer.clone()),
            ("X-Keyguard-Item", item.clone()),
        ];
        if let Some(ticket) = ticket {
            headers.push(("X-Keyguard-Ticket", ticket));
        }
        expect_refusal(&request(headers).await, 403, "ticket_required");
    }
    for item in [
        None,
        Some(item[1..].to_owned()),
        Some(item_id(0xab).to_uppercase()),
    ] {
        let mut headers = vec![
            ("Authorization", bearer.clone()),
            ("X-Keyguard-Ticket", ticket.clone()),
        ];
        if let Some(item) = item {
            headers.push(("X-Keyguard-Item", item));
        }
        expect_refusal(&request(headers).await, 400, "bad_request");
    }
    let valid = vec![
        ("Authorization", bearer.clone()),
        ("X-Keyguard-Ticket", ticket.clone()),
        ("X-Keyguard-Item", item.clone()),
    ];
    assert_eq!(request(valid.clone()).await.status, 204);

    // A connection that left its rooms has no live ticket.
    send_json(&mut client, json!({ "t": "leave" })).await;
    send_json(&mut client, json!({ "t": "ping" })).await;
    assert_eq!(recv_json(&mut client).await, json!({ "t": "pong" }));
    expect_refusal(&request(valid).await, 403, "ticket_required");
}

#[tokio::test]
async fn items_over_the_cap_of_their_kind_are_refused_before_their_body() {
    let dir = TempDir::new().unwrap();
    let relay = Fixture::start(mailbox_config(dir.path())).await;
    let raw_put = |kind: &str, length: Option<u64>, extra: &str| {
        let length = length.map_or(String::new(), |length| {
            format!("Content-Length: {length}\r\n")
        });
        format!(
            "PUT /v1/mailbox/{kind} HTTP/1.1\r\nHost: relay\r\nConnection: close\r\n\
             X-Keyguard-Ticket: {}\r\nX-Keyguard-Item: {}\r\nX-Keyguard-Tag: {}\r\n{length}{extra}\r\n",
            relay.ticket,
            item_id(1),
            tag(1),
        )
    };
    // No body follows: the answer comes from the headers alone.
    for (kind, cap) in [
        ("state", MAILBOX_MAX_STATE_BYTES),
        ("blob", MAILBOX_MAX_BLOB_BYTES),
    ] {
        let answer = relay
            .relay
            .raw_http(&raw_put(kind, Some(cap + 1), ""))
            .await;
        expect_refusal(&answer, 413, "item_too_big");
    }
    // The length must be declared.
    let answer = relay.relay.raw_http(&raw_put("state", None, "")).await;
    expect_refusal(&answer, 400, "bad_request");
    let chunked = raw_put("state", None, "Transfer-Encoding: chunked\r\n") + "0\r\n\r\n";
    expect_refusal(&relay.relay.raw_http(&chunked).await, 400, "bad_request");

    // At the cap is fine, and blobs may be larger than state.
    let state = "s".repeat(MAILBOX_MAX_STATE_BYTES as usize);
    assert_eq!(relay.put("state", 1, 1, &state).await.status, 204);
    let blob = "b".repeat(MAILBOX_MAX_STATE_BYTES as usize + 1);
    assert_eq!(relay.put("blob", 2, 1, &blob).await.status, 204);

    // A tag and a decimal lifetime are required.
    let answer = relay.request("PUT", "state", 3, &[], "state").await;
    expect_refusal(&answer, 400, "bad_request");
    let answer = relay
        .put_with("state", 3, 1, "state", &[("X-Keyguard-Ttl", "soon")])
        .await;
    expect_refusal(&answer, 400, "bad_request");
    assert_eq!(relay.get("state", 3).await.status, 404);
}

#[tokio::test]
async fn an_upload_body_has_a_deadline_and_leaves_nothing_behind() {
    let dir = TempDir::new().unwrap();
    let deadline = Duration::from_millis(300);
    let relay = Fixture::start(Config {
        timeouts: Timeouts {
            mailbox_body: deadline,
            ..Timeouts::default()
        },
        ..mailbox_config(dir.path())
    })
    .await;
    let request = format!(
        "PUT /v1/mailbox/state HTTP/1.1\r\nHost: relay\r\nConnection: close\r\n\
         X-Keyguard-Ticket: {}\r\nX-Keyguard-Item: {}\r\nX-Keyguard-Tag: {}\r\n\
         Content-Length: 10\r\n\r\nabc",
        relay.ticket,
        item_id(1),
        tag(1),
    );
    let started = Instant::now();
    let answer = relay.relay.raw_http(&request).await;
    expect_refusal(&answer, 408, "timeout");
    assert!(started.elapsed() >= deadline);
    // The abandoned upload deletes its file off the request.
    wait_for_files(&dir.path().join("tmp"), 0).await;
    assert_eq!(relay.get("state", 1).await.status, 404);
    // The upload's room was given back.
    assert_eq!(
        relay
            .metric("keyguard_relay_mailbox_requests_total{op=\"put\",result=\"timeout\"}")
            .await,
        1.0
    );
    assert_eq!(relay.put("state", 1, 1, "state").await.status, 204);
}

#[tokio::test]
async fn a_download_is_cut_off_at_its_deadline_while_the_client_reads_nothing() {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let dir = TempDir::new().unwrap();
    let relay = Fixture::start(mailbox_config(dir.path())).await;
    let len = MAILBOX_MAX_BLOB_BYTES as usize;
    assert_eq!(relay.put("blob", 1, 1, &"x".repeat(len)).await.status, 204);
    relay.stop().await;

    let deadline = Duration::from_millis(300);
    let relay = Fixture::start(Config {
        timeouts: Timeouts {
            mailbox_body: deadline,
            ..Timeouts::default()
        },
        ..mailbox_config(dir.path())
    })
    .await;
    // A whole body and a resumed one.
    let mut streams = Vec::new();
    for (range, status) in [
        ("", "200 OK"),
        ("Range: bytes=1-\r\n", "206 Partial Content"),
    ] {
        let mut stream = tokio::net::TcpStream::connect(relay.relay.addr)
            .await
            .unwrap();
        let request = format!(
            "GET /v1/mailbox/blob HTTP/1.1\r\nHost: relay\r\n\
             X-Keyguard-Ticket: {}\r\nX-Keyguard-Item: {}\r\n{range}\r\n",
            relay.ticket,
            item_id(1),
        );
        stream.write_all(request.as_bytes()).await.unwrap();
        streams.push((stream, status));
    }
    // More than socket buffers hold stays unsent until the deadline closes the file.
    tokio::time::sleep(deadline * 2).await;
    for (mut stream, status) in streams {
        let mut received = Vec::new();
        tokio::time::timeout(TIMEOUT, stream.read_to_end(&mut received))
            .await
            .expect("the relay closes the connection")
            .unwrap();
        let status_line = format!("HTTP/1.1 {status}\r\n");
        assert!(received.starts_with(status_line.as_bytes()), "{status}");
        assert!(received.len() < len, "{} bytes arrived", received.len());
    }
}

#[tokio::test]
async fn a_client_that_stops_reading_a_download_is_dropped() {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let dir = TempDir::new().unwrap();
    let stall = Duration::from_millis(300);
    let relay = Fixture::start(Config {
        timeouts: Timeouts {
            write_stall: stall,
            ..Timeouts::default()
        },
        ..mailbox_config(dir.path())
    })
    .await;
    let len = MAILBOX_MAX_BLOB_BYTES as usize;
    assert_eq!(relay.put("blob", 1, 1, &"x".repeat(len)).await.status, 204);
    let mut stream = tokio::net::TcpStream::connect(relay.relay.addr)
        .await
        .unwrap();
    let request = format!(
        "GET /v1/mailbox/blob HTTP/1.1\r\nHost: relay\r\n\
         X-Keyguard-Ticket: {}\r\nX-Keyguard-Item: {}\r\n\r\n",
        relay.ticket,
        item_id(1),
    );
    stream.write_all(request.as_bytes()).await.unwrap();
    // Long before the download's own deadline.
    tokio::time::sleep(stall * 2).await;
    let mut received = Vec::new();
    tokio::time::timeout(TIMEOUT, stream.read_to_end(&mut received))
        .await
        .expect("the relay closes the connection")
        .unwrap();
    assert!(received.starts_with(b"HTTP/1.1 200 OK\r\n"));
    assert!(received.len() < len, "{} bytes arrived", received.len());
}

#[tokio::test]
async fn quotas_cap_each_address_and_each_ipv6_network() {
    let dir = TempDir::new().unwrap();
    let mut config = Config {
        trust_proxy: loopback_proxy(),
        ..mailbox_config(dir.path())
    };
    storage(&mut config).items_per_ip = 2;
    storage(&mut config).bytes_per_ip = 1_000;
    let relay = Fixture::start(config).await;
    let a = "203.0.113.1";

    assert_eq!(
        relay.put_from(a, "blob", 1, &"x".repeat(600)).await.status,
        204
    );
    let full = relay.put_from(a, "blob", 2, &"x".repeat(600)).await;
    expect_refusal(&full, 507, "storage_full");
    assert_eq!(full.header("retry-after"), Some("300"));
    assert_eq!(
        relay.put_from(a, "blob", 2, &"x".repeat(300)).await.status,
        204
    );
    // Two items stored: a third one doesn't fit, even empty.
    expect_refusal(&relay.put_from(a, "blob", 3, "").await, 507, "storage_full");
    // A replacement needs room only for what it adds.
    assert_eq!(
        relay.put_from(a, "blob", 1, &"x".repeat(700)).await.status,
        204
    );
    expect_refusal(
        &relay.put_from(a, "blob", 1, &"x".repeat(701)).await,
        507,
        "storage_full",
    );
    // Another address has its own quota.
    assert_eq!(
        relay.put_from("203.0.113.2", "blob", 3, "x").await.status,
        204
    );

    // The /64s of an IPv6 /48 share 8 times the quota of one.
    let mut item = 10;
    for subnet in 0..8 {
        let ip = format!("2001:db8:1:{subnet:x}::1");
        for _ in 0..2 {
            let answer = relay.put_from(&ip, "blob", item, &"x".repeat(500)).await;
            assert_eq!(answer.status, 204, "{ip}");
            item += 1;
        }
    }
    expect_refusal(
        &relay.put_from("2001:db8:1:8::1", "blob", item, "x").await,
        507,
        "storage_full",
    );
    assert_eq!(
        relay
            .put_from("2001:db8:2::1", "blob", item, "x")
            .await
            .status,
        204
    );
}

#[tokio::test]
async fn replacing_an_item_moves_its_quota_to_the_new_writer() {
    let dir = TempDir::new().unwrap();
    let mut config = Config {
        trust_proxy: loopback_proxy(),
        ..mailbox_config(dir.path())
    };
    storage(&mut config).items_per_ip = 1;
    let relay = Fixture::start(config).await;
    let (a, b) = ("203.0.113.1", "203.0.113.2");
    assert_eq!(relay.put_from(a, "state", 1, "from a").await.status, 204);
    assert_eq!(relay.put_from(b, "state", 1, "from b").await.status, 204);
    // The item counts toward b now.
    assert_eq!(relay.put_from(a, "state", 2, "from a").await.status, 204);
    expect_refusal(
        &relay.put_from(b, "state", 3, "from b").await,
        507,
        "storage_full",
    );
}

#[tokio::test]
async fn the_relay_wide_caps_refuse_writes_that_do_not_fit() {
    let dir = TempDir::new().unwrap();
    let mut config = mailbox_config(dir.path());
    storage(&mut config).max_bytes = 1_000;
    storage(&mut config).max_items = 3;
    let relay = Fixture::start(config).await;
    assert_eq!(relay.put("blob", 1, 1, &"x".repeat(400)).await.status, 204);
    assert_eq!(relay.put("blob", 2, 1, &"x".repeat(400)).await.status, 204);
    expect_refusal(
        &relay.put("blob", 3, 1, &"x".repeat(300)).await,
        507,
        "storage_full",
    );
    assert_eq!(relay.put("blob", 3, 1, &"x".repeat(200)).await.status, 204);
    expect_refusal(&relay.put("state", 4, 1, "").await, 507, "storage_full");
    assert_eq!(
        relay.metric("keyguard_relay_mailbox_limit_bytes").await,
        1_000.0
    );
    assert_eq!(
        relay
            .metric("keyguard_relay_mailbox_bytes{kind=\"blob\"}")
            .await,
        1_000.0
    );
}

#[tokio::test]
async fn lifetimes_are_clamped_and_expired_items_are_swept() {
    let dir = TempDir::new().unwrap();
    let mut config = mailbox_config(dir.path());
    storage(&mut config).ttl_seconds = 3_600;
    fast(&mut config);
    let clock = TestClock::install(&mut config);
    let relay = Fixture::start(config).await;
    let start = clock.now();
    for (item, ttl, expires) in [
        (1, Some("10"), start + 60),
        (2, Some("120"), start + 120),
        (3, Some("99999999"), start + 3_600),
        (4, None, start + 3_600),
    ] {
        let headers: Vec<(&str, &str)> =
            ttl.map(|ttl| ("X-Keyguard-Ttl", ttl)).into_iter().collect();
        let answer = relay.put_with("state", item, 1, "state", &headers).await;
        assert_eq!(answer.status, 204, "{}", answer.body);
        assert_eq!(
            answer.header("x-keyguard-expires"),
            Some(expires.to_string().as_str()),
            "{ttl:?}"
        );
    }

    // Expired items are missing at once, before the sweeper deletes them.
    clock.advance(60);
    assert_eq!(relay.get("state", 1).await.status, 404);
    assert_eq!(relay.request("HEAD", "state", 1, &[], "").await.status, 404);
    clock.advance(60);
    relay
        .wait_for_metric("keyguard_relay_mailbox_items{kind=\"state\"}", 2.0)
        .await;
    relay
        .wait_for_metric(
            "keyguard_relay_mailbox_evictions_total{reason=\"expired\"}",
            2.0,
        )
        .await;
    wait_for_files(&dir.path().join("items"), 2).await;

    clock.advance(3_600);
    relay
        .wait_for_metric("keyguard_relay_mailbox_items{kind=\"state\"}", 0.0)
        .await;
    wait_for_files(&dir.path().join("items"), 0).await;
    assert_eq!(
        relay
            .metric("keyguard_relay_mailbox_evictions_total{reason=\"expired\"}")
            .await,
        4.0
    );
}

#[tokio::test]
async fn a_restart_keeps_items_and_drops_expired_items_and_temp_files() {
    let dir = TempDir::new().unwrap();
    let mut config = Config {
        trust_proxy: loopback_proxy(),
        ..mailbox_config(dir.path())
    };
    storage(&mut config).items_per_ip = 1;
    let clock = TestClock::install(&mut config);
    let relay = Fixture::start(config.clone()).await;
    let a = "203.0.113.1";
    let kept = relay
        .put_with("state", 1, 7, "kept", &[("X-Forwarded-For", a)])
        .await;
    assert_eq!(kept.status, 204);
    let expiring = relay
        .put_with(
            "blob",
            2,
            1,
            "expiring",
            &[("X-Forwarded-For", "203.0.113.2"), ("X-Keyguard-Ttl", "60")],
        )
        .await;
    assert_eq!(expiring.status, 204);
    relay.stop().await;

    let items = dir.path().join("items");
    fs::write(dir.path().join("tmp").join("upload"), "partial").unwrap();
    fs::write(items.join(item_id(3)), "not an item").unwrap();
    fs::write(items.join("notes.txt"), "not an item").unwrap();
    clock.advance(120);

    let relay = Fixture::start(config).await;
    let get = relay.get("state", 1).await;
    assert_eq!((get.status, get.body.as_str()), (200, "kept"));
    assert_eq!(get.header("x-keyguard-tag"), Some(tag(7).as_str()));
    assert_eq!(
        get.header("x-keyguard-expires"),
        kept.header("x-keyguard-expires")
    );
    assert_eq!(relay.get("blob", 2).await.status, 404);
    assert_eq!(file_names(&items).len(), 1);
    assert!(file_names(&dir.path().join("tmp")).is_empty());
    assert_eq!(
        relay
            .metric("keyguard_relay_mailbox_items{kind=\"state\"}")
            .await,
        1.0
    );
    assert_eq!(
        relay
            .metric("keyguard_relay_mailbox_items{kind=\"blob\"}")
            .await,
        0.0
    );
    // Addresses never reach the disk: a restored item counts toward no address.
    let answer = relay
        .put_with("state", 4, 1, "new", &[("X-Forwarded-For", a)])
        .await;
    assert_eq!(answer.status, 204);
}

#[tokio::test]
async fn low_free_space_evicts_blobs_before_state_and_shrinks_quotas() {
    let dir = TempDir::new().unwrap();
    let mut config = Config {
        trust_proxy: loopback_proxy(),
        ..mailbox_config(dir.path())
    };
    storage(&mut config).max_bytes = 10_000;
    storage(&mut config).min_free_bytes = 1_000;
    storage(&mut config).bytes_per_ip = 5_000;
    fast(&mut config);
    let volume = Volume::install(&mut config, 1_000_000);
    let relay = Fixture::start(config).await;
    let (a, b) = ("203.0.113.1", "203.0.113.2");
    let kilobyte = "x".repeat(1_000);
    for (kind, item) in [
        ("blob", 1),
        ("state", 2),
        ("blob", 3),
        ("state", 4),
        ("blob", 5),
    ] {
        let answer = relay.put_from(a, kind, item, &kilobyte).await;
        assert_eq!(answer.status, 204, "{}", answer.body);
    }
    assert_eq!(
        relay.metric("keyguard_relay_mailbox_limit_bytes").await,
        10_000.0
    );

    // Each file has a 46-byte header. Five files and 1000 bytes kept free: the limit
    // drops to 4000, and trimming goes down to 3600.
    let header = 46;
    volume.resize(4_000 + 1_000 + 5 * header);
    relay
        .wait_for_metric(
            "keyguard_relay_mailbox_evictions_total{reason=\"pressure\"}",
            2.0,
        )
        .await;
    for (kind, item, status) in [
        ("blob", 1, 404),
        ("blob", 3, 404),
        ("blob", 5, 200),
        ("state", 2, 200),
        ("state", 4, 200),
    ] {
        assert_eq!(relay.get(kind, item).await.status, status, "{kind} {item}");
    }

    // The volume shrinks below what is stored: the limit falls to the stored bytes less
    // the floor, 2000, and trimming goes to 1800. The last blob goes, then the oldest
    // state. With one file left, the limit is 1592.
    let limit = 1_592;
    volume.resize(limit + 1_000 + header);
    relay
        .wait_for_metric(
            "keyguard_relay_mailbox_evictions_total{reason=\"pressure\"}",
            4.0,
        )
        .await;
    assert_eq!(relay.get("blob", 5).await.status, 404);
    assert_eq!(relay.get("state", 2).await.status, 404);
    assert_eq!(relay.get("state", 4).await.status, 200);
    relay
        .wait_for_metric("keyguard_relay_mailbox_limit_bytes", limit as f64)
        .await;

    // The address quota shrank with the limit, from 5000 to 796: a holds 1000.
    let full = relay.put_from(a, "state", 6, "x").await;
    expect_refusal(&full, 507, "storage_full");
    assert_eq!(full.header("retry-after"), Some("300"));
    assert_eq!(
        relay.put_from(b, "state", 6, &"x".repeat(500)).await.status,
        204
    );
    expect_refusal(
        &relay.put_from(b, "state", 7, &"x".repeat(300)).await,
        507,
        "storage_full",
    );
}

#[tokio::test]
async fn a_full_purge_keeps_the_relay_serving() {
    let dir = TempDir::new().unwrap();
    let mut config = mailbox_config(dir.path());
    storage(&mut config).min_free_bytes = 1_000;
    fast(&mut config);
    let volume = Volume::install(&mut config, 1_000_000);
    let relay = Fixture::start(config).await;
    for item in 1..=3 {
        assert_eq!(relay.put("blob", item, 1, "blob").await.status, 204);
    }
    // Less free space than the floor: nothing may be stored.
    volume.resize(0);
    relay
        .wait_for_metric("keyguard_relay_mailbox_limit_bytes", 0.0)
        .await;
    relay
        .wait_for_metric("keyguard_relay_mailbox_items{kind=\"blob\"}", 0.0)
        .await;
    wait_for_files(&dir.path().join("items"), 0).await;
    assert_eq!(relay.get("blob", 1).await.status, 404);
    let full = relay.put("state", 4, 1, "").await;
    expect_refusal(&full, 507, "storage_full");
    assert_eq!(full.header("retry-after"), Some("300"));
    assert_eq!(relay.metric("keyguard_relay_mailbox_available").await, 1.0);

    // Rooms and the health check work on.
    assert_eq!(relay.relay.health().await, json!({ "status": "ok" }));
    let mut other = relay.relay.connect().await;
    join(&mut other, &room_id(1)).await;

    volume.resize(1_000_000);
    // Nothing stored and 1000 bytes kept free.
    relay
        .wait_for_metric("keyguard_relay_mailbox_limit_bytes", 999_000.0)
        .await;
    assert_eq!(relay.put("state", 4, 1, "state").await.status, 204);
}

#[tokio::test]
async fn a_directory_that_never_worked_answers_503_while_rooms_work() {
    let parent = TempDir::new().unwrap();
    let dir: PathBuf = parent.path().join("data");
    fs::write(&dir, "a file where the directory should be").unwrap();
    let mut config = mailbox_config(&dir);
    fast(&mut config);
    let relay = Fixture::connect(config).await;
    assert_eq!(
        relay.client.welcome["features"],
        json!(["room-forward", "mailbox"])
    );
    let answer = relay.put("state", 1, 1, "state").await;
    expect_refusal(&answer, 503, "storage_unavailable");
    assert_eq!(answer.header("retry-after"), Some("60"));
    expect_refusal(&relay.get("state", 1).await, 503, "storage_unavailable");
    assert_eq!(relay.metric("keyguard_relay_mailbox_available").await, 0.0);

    // Forwarding works.
    let mut a = relay.relay.connect().await;
    let mut b = relay.relay.connect().await;
    join(&mut a, &room_id(2)).await;
    join(&mut b, &room_id(2)).await;
    assert_eq!(recv_json(&mut a).await, presence(2, &b.peer, "join"));
    send_json(&mut a, json!({ "t": "pub", "to": b.peer, "data": "AAEC" })).await;
    assert_eq!(recv_json(&mut b).await, msg(2, &a.peer, "AAEC"));
    assert_eq!(relay.relay.health().await, json!({ "status": "ok" }));

    // Once the path can hold a directory, storage starts.
    fs::remove_file(&dir).unwrap();
    relay
        .wait_for_metric("keyguard_relay_mailbox_available", 1.0)
        .await;
    assert_eq!(relay.put("state", 1, 1, "state").await.status, 204);
}

#[tokio::test]
async fn a_directory_that_stops_working_drops_its_items_and_recovers() {
    let dir = TempDir::new().unwrap();
    let mut config = mailbox_config(dir.path());
    fast(&mut config);
    let volume = Volume::install(&mut config, 1 << 40);
    let relay = Fixture::start(config).await;
    assert_eq!(relay.put("state", 1, 1, "state").await.status, 204);

    volume.broken.store(true, Ordering::Relaxed);
    relay
        .wait_for_metric("keyguard_relay_mailbox_available", 0.0)
        .await;
    expect_refusal(&relay.get("state", 1).await, 503, "storage_unavailable");
    wait_for_files(&dir.path().join("items"), 0).await;
    assert_eq!(
        relay
            .metric("keyguard_relay_mailbox_items{kind=\"state\"}")
            .await,
        0.0
    );

    volume.broken.store(false, Ordering::Relaxed);
    relay
        .wait_for_metric("keyguard_relay_mailbox_available", 1.0)
        .await;
    assert_eq!(relay.get("state", 1).await.status, 404);
    assert_eq!(relay.put("state", 1, 1, "state").await.status, 204);
}

#[tokio::test]
async fn the_welcome_frame_advertises_storage_only_when_configured() {
    let dir = TempDir::new().unwrap();
    let mut config = mailbox_config(dir.path());
    storage(&mut config).ttl_seconds = 7_200;
    let relay = Fixture::start(config).await;
    let welcome = &relay.client.welcome;
    assert_eq!(welcome["features"], json!(["room-forward", "mailbox"]));
    assert_eq!(welcome["limits"]["mailboxStateBytes"], 1_048_576);
    assert_eq!(welcome["limits"]["mailboxBlobBytes"], 17_825_792);
    assert_eq!(welcome["limits"]["mailboxTtlSeconds"], 7_200);

    let plain = Relay::start(test_config()).await;
    let welcome = plain.connect().await.welcome;
    assert_eq!(welcome["features"], json!(["room-forward"]));
    let limits = welcome["limits"].as_object().unwrap();
    assert!(
        limits.keys().all(|key| !key.starts_with("mailbox")),
        "{limits:?}"
    );
}

#[tokio::test]
async fn mailbox_paths_are_missing_without_storage_or_for_unknown_kinds() {
    let plain = Relay::start(test_config()).await;
    for (method, path) in [
        ("PUT", "/v1/mailbox/state"),
        ("GET", "/v1/mailbox/blob"),
        ("DELETE", "/v1/mailbox/other"),
    ] {
        expect_refusal(&plain.http(method, path, &[]).await, 404, "not_found");
    }
    assert!(
        !plain
            .metrics()
            .await
            .body
            .contains("keyguard_relay_mailbox_")
    );

    let dir = TempDir::new().unwrap();
    let relay = Fixture::start(mailbox_config(dir.path())).await;
    for path in ["/v1/mailbox/other", "/v1/mailbox/state/1", "/v1/mailbox/"] {
        expect_refusal(&relay.relay.http("GET", path, &[]).await, 404, "not_found");
    }
    let answer = relay.relay.http("POST", "/v1/mailbox/state", &[]).await;
    assert_eq!(answer.status, 405);
    assert_eq!(answer.header("allow"), Some("GET, HEAD, PUT, DELETE"));
}

#[tokio::test]
async fn mailbox_requests_and_uploads_are_rate_limited_per_address() {
    let dir = TempDir::new().unwrap();
    let mut config = Config {
        trust_proxy: loopback_proxy(),
        ..mailbox_config(dir.path())
    };
    storage(&mut config).requests_per_minute = 2;
    let relay = Fixture::start(config).await;
    let a = "203.0.113.1";
    for item in 1..=2 {
        assert_eq!(relay.put_from(a, "state", item, "state").await.status, 204);
    }
    let limited = relay.put_from(a, "state", 3, "state").await;
    expect_refusal(&limited, 429, "rate_limited");
    // One request refills every 30 seconds.
    assert_eq!(limited.header("retry-after"), Some("30"));
    assert_eq!(
        relay
            .metric(
                "keyguard_relay_http_rate_limits_total{endpoint=\"mailbox\",reason=\"per_address\"}"
            )
            .await,
        1.0
    );
    assert_eq!(
        relay
            .put_from("203.0.113.2", "state", 3, "state")
            .await
            .status,
        204
    );

    let mut config = Config {
        trust_proxy: loopback_proxy(),
        ..mailbox_config(dir.path())
    };
    storage(&mut config).upload_bytes_per_minute = 1_200;
    relay.stop().await;
    let relay = Fixture::start(config).await;
    // One upload may overdraw the allowance, then the address waits for the refill.
    assert_eq!(
        relay
            .put_from(a, "blob", 1, &"x".repeat(1_800))
            .await
            .status,
        204
    );
    let limited = relay.put_from(a, "blob", 2, "x").await;
    expect_refusal(&limited, 429, "rate_limited");
    // 600 bytes short at 20 a second.
    assert_eq!(limited.header("retry-after"), Some("30"));
    assert_eq!(relay.get("blob", 2).await.status, 404);
    assert_eq!(
        relay.put_from("203.0.113.2", "blob", 2, "x").await.status,
        204
    );
}

#[tokio::test]
async fn metrics_count_stored_items_and_request_results_without_ids() {
    let dir = TempDir::new().unwrap();
    let relay = Fixture::start(mailbox_config(dir.path())).await;
    assert_eq!(relay.put("state", 1, 1, "state").await.status, 204);
    assert_eq!(relay.put("blob", 2, 1, "blob!").await.status, 204);
    assert_eq!(relay.get("state", 3).await.status, 404);
    let metrics = relay.relay.metrics().await;
    for (name, value) in [
        ("keyguard_relay_mailbox_items{kind=\"state\"}", 1.0),
        ("keyguard_relay_mailbox_items{kind=\"blob\"}", 1.0),
        ("keyguard_relay_mailbox_bytes{kind=\"state\"}", 5.0),
        ("keyguard_relay_mailbox_bytes{kind=\"blob\"}", 5.0),
        ("keyguard_relay_mailbox_available", 1.0),
        (
            "keyguard_relay_mailbox_requests_total{op=\"put\",result=\"ok\"}",
            2.0,
        ),
        (
            "keyguard_relay_mailbox_requests_total{op=\"get\",result=\"not_found\"}",
            1.0,
        ),
        (
            "keyguard_relay_mailbox_evictions_total{reason=\"pressure\"}",
            0.0,
        ),
        ("keyguard_relay_mailbox_write_failures_total", 0.0),
    ] {
        assert_eq!(metrics.metric(name), value, "{name}");
    }
    for private in [item_id(1), item_id(2), tag(1), relay.ticket.clone()] {
        assert!(
            !metrics.body.contains(&private),
            "metrics contain {private}"
        );
    }
}
