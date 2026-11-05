//! Rate limits and connection caps keyed by client address, and the relay's egress budget.

use std::collections::HashMap;
use std::net::{IpAddr, Ipv6Addr, SocketAddr};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use axum::http::HeaderMap;
use tokio::sync::Notify;
use tokio::time::Instant;

use crate::config::IpNet;
use crate::lock;

/// A token bucket: `capacity` tokens, refilled at `rate` tokens per second.
#[derive(Debug, Clone)]
pub(crate) struct TokenBucket {
    capacity: f64,
    rate: f64,
    tokens: f64,
    updated: Instant,
}

impl TokenBucket {
    pub(crate) fn new(capacity: f64, rate: f64, now: Instant) -> Self {
        Self {
            capacity,
            rate,
            tokens: capacity,
            updated: now,
        }
    }

    /// The tokens left at `now`.
    fn available(&mut self, now: Instant) -> f64 {
        let elapsed = now.saturating_duration_since(self.updated).as_secs_f64();
        self.tokens = (self.tokens + elapsed * self.rate).min(self.capacity);
        self.updated = now;
        self.tokens
    }

    /// Spends `amount`, which may overdraw the bucket. Check [`Self::available`] first.
    fn take(&mut self, amount: f64) {
        self.tokens -= amount;
    }

    /// Takes `amount` while more than `reserve` tokens are left, even if that overdraws
    /// the bucket: a take larger than the whole capacity still goes through, and the debt
    /// refills like any other spend.
    pub(crate) fn try_overdraw(&mut self, amount: f64, reserve: f64, now: Instant) -> bool {
        if self.available(now) <= reserve {
            return false;
        }
        self.take(amount);
        true
    }

    fn is_full(&mut self, now: Instant) -> bool {
        self.available(now) >= self.capacity
    }

    /// How long until the bucket holds `amount` tokens, as of its last refill.
    fn time_until(&self, amount: f64) -> Duration {
        Duration::try_from_secs_f64(((amount - self.tokens) / self.rate).max(0.0))
            .unwrap_or(Duration::MAX)
    }
}

/// The shortest wait a budget asks for: rounding can leave a bucket a hair short when the
/// wait has passed.
const MIN_RETRY: Duration = Duration::from_millis(1);

/// Message and byte budget of one WebSocket connection, with a one-second burst.
pub(crate) struct ConnectionBudget {
    messages: TokenBucket,
    bytes: TokenBucket,
}

impl ConnectionBudget {
    pub(crate) fn new(msgs_per_sec: u32, bytes_per_sec: u64, now: Instant) -> Self {
        let msgs = f64::from(msgs_per_sec);
        // Exact for every configurable value (at most 1 GiB).
        let bytes = bytes_per_sec as f64;
        Self {
            messages: TokenBucket::new(msgs, msgs, now),
            bytes: TokenBucket::new(bytes, bytes, now),
        }
    }

    /// Spends one message of `len` bytes. When either budget is short, spends nothing and
    /// returns how long until both allow it; a message of at most one second's bytes
    /// always fits then.
    pub(crate) fn try_spend(&mut self, len: usize, now: Instant) -> Result<(), Duration> {
        let len = len as f64;
        let messages = self.messages.available(now);
        let bytes = self.bytes.available(now);
        if messages >= 1.0 && bytes >= len {
            self.messages.take(1.0);
            self.bytes.take(len);
            return Ok(());
        }
        let wait = self
            .messages
            .time_until(1.0)
            .max(self.bytes.time_until(len));
        Err(wait.max(MIN_RETRY))
    }
}

/// The address a request comes from. Behind trusted proxies, it is the rightmost
/// `X-Forwarded-For` entry that isn't a trusted proxy itself: each proxy appends the
/// address it got the request from, so entries left of that one are the client's word.
pub(crate) fn client_ip(peer: SocketAddr, headers: &HeaderMap, trusted: &[IpNet]) -> IpAddr {
    let mut client = peer.ip().to_canonical();
    if !is_trusted_proxy(client, trusted) {
        return client;
    }
    let mut forwarded = false;
    for value in headers.get_all("x-forwarded-for").iter().rev() {
        for entry in value.to_str().unwrap_or_default().rsplit(',') {
            // Stop at the first entry that isn't an address: what's left of it can't be
            // told from what the client wrote.
            let Ok(ip) = entry.trim().parse::<IpAddr>() else {
                warn_no_forwarded_for();
                return client;
            };
            forwarded = true;
            client = ip.to_canonical();
            if !is_trusted_proxy(client, trusted) {
                return client;
            }
        }
    }
    // Every entry is a trusted proxy: the leftmost one sent the request.
    if !forwarded {
        warn_no_forwarded_for();
    }
    client
}

/// Warns once per process: a proxy that never sends the header would warn on every
/// request. Neither the header nor an address goes into the log.
fn warn_no_forwarded_for() {
    static WARNED: AtomicBool = AtomicBool::new(false);
    if !WARNED.swap(true, Ordering::Relaxed) {
        tracing::warn!(
            "a request from a TRUST_PROXY address has no usable X-Forwarded-For entry, so \
             its client shares a proxy's limits"
        );
    }
}

/// Whether the TCP peer `ip` is one of the `trusted` proxies.
pub(crate) fn is_trusted_proxy(ip: IpAddr, trusted: &[IpNet]) -> bool {
    trusted.iter().any(|net| net.contains(ip))
}

/// The key limits are counted under: the IPv4 address, or the /64 of an IPv6
/// address (one subscriber usually owns a whole /64).
pub(crate) fn client_key(ip: IpAddr) -> IpAddr {
    match ip.to_canonical() {
        IpAddr::V6(v6) => IpAddr::V6(Ipv6Addr::from(u128::from(v6) & !(u128::from(u64::MAX)))),
        v4 => v4,
    }
}

/// Caps concurrent connections per client key and on the whole relay.
pub(crate) struct ConnectionLimiter {
    max_per_key: u32,
    max_total: usize,
    counts: Mutex<ConnectionCounts>,
    /// Signalled whenever a permit is released.
    released: Notify,
}

struct ConnectionCounts {
    per_key: HashMap<IpAddr, u32>,
    total: usize,
}

/// Why a connection got no slot.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ConnectionRefused {
    /// The client key holds its maximum.
    PerAddress,
    /// The relay holds its maximum.
    Total,
}

impl ConnectionLimiter {
    pub(crate) fn new(max_per_key: u32, max_total: usize) -> Self {
        Self {
            max_per_key,
            max_total,
            counts: Mutex::new(ConnectionCounts {
                per_key: HashMap::new(),
                total: 0,
            }),
            released: Notify::new(),
        }
    }

    /// The number of held permits.
    pub(crate) fn len(&self) -> usize {
        lock(&self.counts).total
    }

    /// Waits until every permit is released.
    pub(crate) async fn wait_until_empty(&self) {
        loop {
            let released = self.released.notified();
            if self.len() == 0 {
                return;
            }
            released.await;
        }
    }

    /// Takes a connection slot for `key`, released when the permit drops. Without a key,
    /// as for a connection from a trusted proxy, the slot counts toward the total only.
    pub(crate) fn acquire(
        self: &Arc<Self>,
        key: Option<IpAddr>,
    ) -> Result<ConnectionPermit, ConnectionRefused> {
        let mut counts = lock(&self.counts);
        if key.is_some_and(|key| {
            counts
                .per_key
                .get(&key)
                .is_some_and(|count| *count >= self.max_per_key)
        }) {
            return Err(ConnectionRefused::PerAddress);
        }
        if counts.total >= self.max_total {
            return Err(ConnectionRefused::Total);
        }
        if let Some(key) = key {
            *counts.per_key.entry(key).or_insert(0) += 1;
        }
        counts.total += 1;
        Ok(ConnectionPermit {
            limiter: Arc::clone(self),
            key,
        })
    }
}

/// A held connection slot.
pub(crate) struct ConnectionPermit {
    limiter: Arc<ConnectionLimiter>,
    key: Option<IpAddr>,
}

impl Drop for ConnectionPermit {
    fn drop(&mut self) {
        let mut counts = lock(&self.limiter.counts);
        counts.total -= 1;
        if let Some(key) = self.key
            && let Some(count) = counts.per_key.get_mut(&key)
        {
            *count -= 1;
            if *count == 0 {
                counts.per_key.remove(&key);
            }
        }
        drop(counts);
        self.limiter.released.notify_one();
    }
}

/// Full buckets are forgotten this often, which bounds memory to the clients of
/// roughly the last minute.
const SWEEP_INTERVAL: Duration = Duration::from_secs(60);

/// The most keys [`PerKey::get`] tracks, however many addresses a client rotates through.
const MAX_KEYS: usize = 100_000;

/// How often a [`PerKey`] that holds [`MAX_KEYS`] sweeps early to make room.
const FULL_SWEEP_INTERVAL: Duration = Duration::from_secs(1);

/// State per client key, swept of stale entries every `interval`.
struct PerKey<T> {
    entries: HashMap<IpAddr, T>,
    interval: Duration,
    swept: Instant,
}

impl<T> PerKey<T> {
    fn new(interval: Duration, now: Instant) -> Self {
        Self {
            entries: HashMap::new(),
            interval,
            swept: now,
        }
    }

    /// Forgets the entries that `keep` rejects, once per interval.
    fn sweep(&mut self, now: Instant, keep: impl FnMut(&mut T) -> bool) {
        self.sweep_every(self.interval, now, keep);
    }

    fn sweep_every(
        &mut self,
        interval: Duration,
        now: Instant,
        mut keep: impl FnMut(&mut T) -> bool,
    ) {
        if now.saturating_duration_since(self.swept) >= interval {
            self.entries.retain(|_, entry| keep(entry));
            self.swept = now;
        }
    }

    fn is_full_for(&self, key: &IpAddr) -> bool {
        self.entries.len() >= MAX_KEYS && !self.entries.contains_key(key)
    }

    /// The entry of `key`, made by `make` if missing, after a [`Self::sweep`]. `None` for
    /// a new key while [`MAX_KEYS`] entries are worth keeping.
    fn get(
        &mut self,
        key: IpAddr,
        now: Instant,
        keep: impl FnMut(&mut T) -> bool,
        make: impl FnOnce() -> T,
    ) -> Option<&mut T> {
        let interval = if self.is_full_for(&key) {
            self.interval.min(FULL_SWEEP_INTERVAL)
        } else {
            self.interval
        };
        self.sweep_every(interval, now, keep);
        if self.is_full_for(&key) {
            return None;
        }
        Some(self.entries.entry(key).or_insert_with(make))
    }
}

/// A bucket that refills `per_minute` tokens a minute, and holds as many.
fn per_minute_bucket(per_minute: u32, now: Instant) -> TokenBucket {
    let capacity = f64::from(per_minute);
    TokenBucket::new(capacity, capacity / 60.0, now)
}

/// TURN credentials: `per_minute` per client key, of which `ticketless_per_minute` may
/// go to requests without a live ticket, and `total_per_minute` on the whole relay, of
/// which requests without a live ticket can't take the last half.
pub(crate) struct TurnAllowance {
    per_minute: u32,
    ticketless_per_minute: u32,
    total_reserve: f64,
    inner: Mutex<TurnAllowanceState>,
}

struct TurnAllowanceState {
    keys: PerKey<TurnBuckets>,
    total: TokenBucket,
}

struct TurnBuckets {
    all: TokenBucket,
    ticketless: TokenBucket,
}

impl TurnAllowance {
    pub(crate) fn new(
        per_minute: u32,
        ticketless_per_minute: u32,
        total_per_minute: u32,
        now: Instant,
    ) -> Self {
        Self {
            per_minute,
            ticketless_per_minute,
            total_reserve: f64::from(total_per_minute) / 2.0,
            inner: Mutex::new(TurnAllowanceState {
                keys: PerKey::new(SWEEP_INTERVAL, now),
                total: per_minute_bucket(total_per_minute, now),
            }),
        }
    }

    /// Spends one credential of `key`, or nothing when an allowance is short.
    pub(crate) fn try_take(&self, key: IpAddr, ticketed: bool, now: Instant) -> bool {
        let mut guard = lock(&self.inner);
        let state = &mut *guard;
        // First, so a request the relay can't serve anyway adds no state for its key.
        let reserve = if ticketed { 0.0 } else { self.total_reserve };
        if state.total.available(now) < 1.0 + reserve {
            return false;
        }
        let Some(buckets) = state.keys.get(
            key,
            now,
            |buckets| !(buckets.all.is_full(now) && buckets.ticketless.is_full(now)),
            || TurnBuckets {
                all: per_minute_bucket(self.per_minute, now),
                ticketless: per_minute_bucket(self.ticketless_per_minute, now),
            },
        ) else {
            return false;
        };
        if buckets.all.available(now) < 1.0
            || (!ticketed && buckets.ticketless.available(now) < 1.0)
        {
            return false;
        }
        buckets.all.take(1.0);
        if !ticketed {
            buckets.ticketless.take(1.0);
        }
        state.total.take(1.0);
        true
    }
}

/// Failed token checks a client key may make per minute.
pub(crate) const AUTH_FAILURES_PER_MINUTE: u32 = 10;

/// The answer to a token check.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum AuthOutcome {
    Allowed,
    Unauthorized,
    /// The key failed too often; even the right token waits.
    Locked,
}

/// Spends one of [`AUTH_FAILURES_PER_MINUTE`] on every failed token check of a client key.
/// With none left, every check of the key fails, so a token can't be guessed fast.
pub(crate) struct AuthGuard {
    failures: Mutex<PerKey<TokenBucket>>,
}

impl AuthGuard {
    pub(crate) fn new(now: Instant) -> Self {
        Self {
            failures: Mutex::new(PerKey::new(SWEEP_INTERVAL, now)),
        }
    }

    /// Seconds until a locked key may try again.
    pub(crate) fn retry_after_secs() -> u64 {
        60u64.div_ceil(u64::from(AUTH_FAILURES_PER_MINUTE))
    }

    pub(crate) fn check(&self, key: IpAddr, authorized: bool, now: Instant) -> AuthOutcome {
        let mut failures = lock(&self.failures);
        // Only failures add state: the right token from a key that never failed adds none.
        if authorized && !failures.entries.contains_key(&key) {
            return AuthOutcome::Allowed;
        }
        let Some(bucket) = failures.get(
            key,
            now,
            |bucket| !bucket.is_full(now),
            || per_minute_bucket(AUTH_FAILURES_PER_MINUTE, now),
        ) else {
            // Too many keys are failing at once to track another; it waits like a locked one.
            return AuthOutcome::Locked;
        };
        if bucket.available(now) < 1.0 {
            return AuthOutcome::Locked;
        }
        if authorized {
            return AuthOutcome::Allowed;
        }
        bucket.take(1.0);
        AuthOutcome::Unauthorized
    }
}

/// How long a client key counts as sending after its last delivery, and the time over
/// which its send rate is averaged.
const SENDER_WINDOW: Duration = Duration::from_secs(1);

/// Bytes per second the relay delivers, with a one-second burst, shared fairly between
/// client keys. A key's fair share is the rate over the keys that sent in the last
/// second. A key above its share can't take the last half of the budget, so heavy
/// senders wait before light ones.
pub(crate) struct EgressBudget {
    rate: f64,
    bucket: TokenBucket,
    senders: PerKey<SendRate>,
}

/// The bytes per second a key delivered, averaged exponentially over [`SENDER_WINDOW`].
struct SendRate {
    bytes_per_sec: f64,
    updated: Instant,
}

impl SendRate {
    fn decay(&mut self, now: Instant) {
        let elapsed = now.saturating_duration_since(self.updated).as_secs_f64();
        self.bytes_per_sec *= (-elapsed / SENDER_WINDOW.as_secs_f64()).exp();
        self.updated = now;
    }
}

impl EgressBudget {
    pub(crate) fn new(bytes_per_sec: u64, now: Instant) -> Self {
        // Exact for every configurable value (at most 1 TiB).
        let rate = bytes_per_sec as f64;
        Self {
            rate,
            bucket: TokenBucket::new(rate, rate, now),
            senders: PerKey::new(SENDER_WINDOW, now),
        }
    }

    /// Spends `bytes` delivered for `sender`. When its part of the budget is spent, spends
    /// nothing and returns how long until the budget refills enough to try again. A
    /// delivery may overdraw the budget.
    pub(crate) fn try_spend(
        &mut self,
        sender: IpAddr,
        bytes: usize,
        now: Instant,
    ) -> Result<(), Duration> {
        self.senders.sweep(now, |sent| {
            now.saturating_duration_since(sent.updated) < SENDER_WINDOW
        });
        let senders = &mut self.senders.entries;
        let fair_share =
            self.rate / (senders.len() + usize::from(!senders.contains_key(&sender))) as f64;
        let sent = senders.entry(sender).or_insert(SendRate {
            bytes_per_sec: 0.0,
            updated: now,
        });
        sent.decay(now);
        let reserve = if sent.bytes_per_sec > fair_share {
            self.rate / 2.0
        } else {
            0.0
        };
        let bytes = bytes as f64;
        if !self.bucket.try_overdraw(bytes, reserve, now) {
            // The bucket must hold more than the reserve, not just as much.
            return Err(self.bucket.time_until(reserve).max(MIN_RETRY));
        }
        sent.bytes_per_sec += bytes;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::http::HeaderValue;

    #[test]
    fn bucket_refills_over_time() {
        let start = Instant::now();
        let mut bucket = TokenBucket::new(2.0, 1.0, start);
        assert!(bucket.try_overdraw(1.0, 0.0, start));
        assert!(bucket.try_overdraw(1.0, 0.0, start));
        assert!(!bucket.try_overdraw(1.0, 0.0, start));
        assert!(bucket.try_overdraw(1.0, 0.0, start + Duration::from_secs(1)));
        assert!(bucket.is_full(start + Duration::from_secs(10)));
    }

    #[test]
    fn budget_needs_both_messages_and_bytes() {
        let start = Instant::now();
        let mut budget = ConnectionBudget::new(10, 100, start);
        assert!(budget.try_spend(60, start).is_ok());
        // 20 bytes short at 100 a second.
        assert_millis(budget.try_spend(60, start), 200);
        assert!(budget.try_spend(40, start).is_ok());

        let mut budget = ConnectionBudget::new(2, 100, start);
        assert!(budget.try_spend(1, start).is_ok());
        assert!(budget.try_spend(1, start).is_ok());
        // One message short at 2 a second.
        assert_millis(budget.try_spend(1, start), 500);
        let later = start + Duration::from_millis(500);
        assert!(budget.try_spend(1, later).is_ok());
    }

    #[test]
    fn ipv6_clients_share_their_slash_64() {
        let a = client_key("2001:db8:1:2:aaaa::1".parse().unwrap());
        let b = client_key("2001:db8:1:2:bbbb::2".parse().unwrap());
        let c = client_key("2001:db8:1:3::1".parse().unwrap());
        assert_eq!(a, b);
        assert_ne!(a, c);
        assert_eq!(
            client_key("::ffff:203.0.113.7".parse().unwrap()),
            "203.0.113.7".parse::<IpAddr>().unwrap()
        );
    }

    #[test]
    fn forwarded_for_is_used_only_from_trusted_proxies() {
        let mut headers = HeaderMap::new();
        headers.append(
            "x-forwarded-for",
            HeaderValue::from_static("198.51.100.1, 203.0.113.5"),
        );
        let proxy: SocketAddr = "127.0.0.1:5000".parse().unwrap();
        let trusted = ["127.0.0.1".parse().unwrap()];
        assert_eq!(
            client_ip(proxy, &headers, &trusted),
            "203.0.113.5".parse::<IpAddr>().unwrap()
        );
        assert_eq!(
            client_ip(proxy, &headers, &[]),
            "127.0.0.1".parse::<IpAddr>().unwrap()
        );

        headers.insert(
            "x-forwarded-for",
            HeaderValue::from_static("not an address"),
        );
        assert_eq!(
            client_ip(proxy, &headers, &trusted),
            "127.0.0.1".parse::<IpAddr>().unwrap()
        );
    }

    #[test]
    fn forwarded_for_skips_every_trusted_hop() {
        // A CDN edge in 198.51.100.0/24, then a proxy on loopback.
        let proxy: SocketAddr = "127.0.0.1:5000".parse().unwrap();
        let trusted = [
            "127.0.0.1".parse().unwrap(),
            "198.51.100.0/24".parse().unwrap(),
        ];
        let ip = |text: &str| text.parse::<IpAddr>().unwrap();
        let from = |values: &[&'static str]| {
            let mut headers = HeaderMap::new();
            for value in values {
                headers.append("x-forwarded-for", HeaderValue::from_static(value));
            }
            client_ip(proxy, &headers, &trusted)
        };
        // Whatever the client wrote left of the address the edge saw is ignored.
        assert_eq!(
            from(&["192.0.2.66, 203.0.113.5, 198.51.100.7"]),
            ip("203.0.113.5")
        );
        // Across header lines too, the last one being the newest.
        assert_eq!(
            from(&["192.0.2.66, 203.0.113.5", "198.51.100.7"]),
            ip("203.0.113.5")
        );
        // Every hop trusted: the leftmost one sent the request.
        assert_eq!(from(&["198.51.100.1, 198.51.100.7"]), ip("198.51.100.1"));
        // An entry that isn't an address ends the walk at the hop before it.
        assert_eq!(
            from(&["203.0.113.5, unknown, 198.51.100.7"]),
            ip("198.51.100.7")
        );
    }

    #[test]
    fn overdraw_takes_past_the_capacity_then_waits_for_the_refill() {
        let start = Instant::now();
        let mut bucket = TokenBucket::new(100.0, 100.0, start);
        assert!(bucket.try_overdraw(250.0, 0.0, start));
        assert!(!bucket.try_overdraw(1.0, 0.0, start));
        // 150 in debt: refilled after 1.5 seconds.
        assert!(!bucket.try_overdraw(1.0, 0.0, start + Duration::from_millis(1400)));
        assert!(bucket.try_overdraw(1.0, 0.0, start + Duration::from_millis(1600)));
    }

    #[test]
    fn permits_release_their_slot() {
        let limiter = Arc::new(ConnectionLimiter::new(1, 10));
        let key: IpAddr = "203.0.113.1".parse().unwrap();
        let permit = limiter.acquire(Some(key)).unwrap();
        assert_eq!(
            limiter.acquire(Some(key)).err(),
            Some(ConnectionRefused::PerAddress)
        );
        assert!(
            limiter
                .acquire(Some("203.0.113.2".parse().unwrap()))
                .is_ok()
        );
        drop(permit);
        assert!(limiter.acquire(Some(key)).is_ok());
        let counts = lock(&limiter.counts);
        assert!(counts.per_key.is_empty());
        assert_eq!(counts.total, 0);
    }

    #[test]
    fn total_connections_are_capped() {
        let limiter = Arc::new(ConnectionLimiter::new(2, 3));
        let a: IpAddr = "203.0.113.1".parse().unwrap();
        let b: IpAddr = "203.0.113.2".parse().unwrap();
        let permits = [
            limiter.acquire(Some(a)).unwrap(),
            limiter.acquire(Some(a)).unwrap(),
            limiter.acquire(Some(b)).unwrap(),
        ];
        // The address limit is reported first.
        assert_eq!(
            limiter.acquire(Some(a)).err(),
            Some(ConnectionRefused::PerAddress)
        );
        assert_eq!(
            limiter.acquire(Some(b)).err(),
            Some(ConnectionRefused::Total)
        );
        drop(permits);
        assert!(limiter.acquire(Some(b)).is_ok());
    }

    #[test]
    fn keyless_permits_count_toward_the_total_only() {
        let limiter = Arc::new(ConnectionLimiter::new(1, 3));
        let key: IpAddr = "203.0.113.1".parse().unwrap();
        let _keyless = [
            limiter.acquire(None).unwrap(),
            limiter.acquire(None).unwrap(),
        ];
        let _keyed = limiter.acquire(Some(key)).unwrap();
        assert_eq!(limiter.acquire(None).err(), Some(ConnectionRefused::Total));
        assert_eq!(lock(&limiter.counts).per_key.get(&key), Some(&1));
    }

    #[test]
    fn ticketless_requests_get_the_smaller_allowance() {
        let start = Instant::now();
        let allowance = TurnAllowance::new(3, 1, 100, start);
        let key: IpAddr = "203.0.113.1".parse().unwrap();
        assert!(allowance.try_take(key, false, start));
        assert!(!allowance.try_take(key, false, start));
        // A ticket draws on the rest.
        assert!(allowance.try_take(key, true, start));
        assert!(allowance.try_take(key, true, start));
        assert!(!allowance.try_take(key, true, start));
        // Every address has its own allowance.
        assert!(allowance.try_take("203.0.113.2".parse().unwrap(), false, start));
        // One credential refills every 20 seconds, and one ticketless one every 60.
        let later = start + Duration::from_secs(21);
        assert!(!allowance.try_take(key, false, later));
        assert!(allowance.try_take(key, true, later));
        let later = start + Duration::from_secs(61);
        assert!(allowance.try_take(key, false, later));
    }

    #[test]
    fn ticketless_requests_share_the_whole_allowance() {
        let start = Instant::now();
        let allowance = TurnAllowance::new(2, 4, 100, start);
        let key: IpAddr = "203.0.113.1".parse().unwrap();
        assert!(allowance.try_take(key, true, start));
        assert!(allowance.try_take(key, false, start));
        assert!(!allowance.try_take(key, false, start));
    }

    #[test]
    fn zero_ticketless_allowance_needs_a_ticket() {
        let start = Instant::now();
        let allowance = TurnAllowance::new(2, 0, 100, start);
        let key: IpAddr = "203.0.113.1".parse().unwrap();
        assert!(!allowance.try_take(key, false, start));
        assert!(!allowance.try_take(key, false, start + Duration::from_secs(600)));
        assert!(allowance.try_take(key, true, start));
    }

    #[test]
    fn turn_allowance_forgets_idle_clients() {
        let start = Instant::now();
        let allowance = TurnAllowance::new(2, 1, 100, start);
        assert!(allowance.try_take("203.0.113.1".parse().unwrap(), false, start));
        assert!(allowance.try_take("203.0.113.2".parse().unwrap(), true, start));

        let later = start + Duration::from_secs(120);
        assert!(allowance.try_take("203.0.113.3".parse().unwrap(), false, later));
        assert_eq!(lock(&allowance.inner).keys.entries.len(), 1);
    }

    #[test]
    fn the_relay_wide_allowance_keeps_half_for_tickets() {
        let start = Instant::now();
        let allowance = TurnAllowance::new(10, 10, 4, start);
        let keys: Vec<IpAddr> = (1..=6)
            .map(|n| format!("203.0.113.{n}").parse().unwrap())
            .collect();
        assert!(allowance.try_take(keys[0], false, start));
        assert!(allowance.try_take(keys[1], false, start));
        assert!(!allowance.try_take(keys[2], false, start));
        assert!(allowance.try_take(keys[3], true, start));
        assert!(allowance.try_take(keys[4], true, start));
        assert!(!allowance.try_take(keys[5], true, start));
        // A refused request spends nothing of its address.
        assert!(allowance.try_take(keys[5], true, start + Duration::from_secs(16)));
    }

    #[test]
    fn requests_over_the_relay_wide_allowance_add_no_state() {
        let start = Instant::now();
        let allowance = TurnAllowance::new(10, 10, 2, start);
        for n in 1..=50 {
            let key = format!("203.0.113.{n}").parse().unwrap();
            assert_eq!(allowance.try_take(key, true, start), n <= 2);
        }
        assert_eq!(lock(&allowance.inner).keys.entries.len(), 2);
    }

    #[test]
    fn per_key_state_is_bounded() {
        let start = Instant::now();
        let mut keys = PerKey::new(SWEEP_INTERVAL, start);
        let key = |n: u32| IpAddr::from(std::net::Ipv4Addr::from(n));
        let keep = |(): &mut ()| true;
        for n in 0..u32::try_from(MAX_KEYS).unwrap() {
            assert!(keys.get(key(n), start, keep, || ()).is_some());
        }
        // Full of entries worth keeping: a new key gets none, a known one still does.
        assert!(keys.get(key(u32::MAX), start, keep, || ()).is_none());
        assert!(keys.get(key(0), start, keep, || ()).is_some());
        // Once they go stale, a full map sweeps early to make room.
        let later = start + FULL_SWEEP_INTERVAL;
        assert!(keys.get(key(u32::MAX), later, |()| false, || ()).is_some());
        assert_eq!(keys.entries.len(), 1);
    }

    #[test]
    fn failed_token_checks_lock_the_address() {
        let start = Instant::now();
        let guard = AuthGuard::new(start);
        let key: IpAddr = "203.0.113.1".parse().unwrap();
        assert_eq!(guard.check(key, true, start), AuthOutcome::Allowed);
        for _ in 0..AUTH_FAILURES_PER_MINUTE {
            assert_eq!(guard.check(key, false, start), AuthOutcome::Unauthorized);
        }
        assert_eq!(guard.check(key, false, start), AuthOutcome::Locked);
        // Even the right token waits, or guessing would go on at full speed.
        assert_eq!(guard.check(key, true, start), AuthOutcome::Locked);
        let other: IpAddr = "203.0.113.2".parse().unwrap();
        assert_eq!(guard.check(other, true, start), AuthOutcome::Allowed);
        // Only the key that failed is tracked.
        assert_eq!(lock(&guard.failures).entries.len(), 1);

        assert_eq!(AuthGuard::retry_after_secs(), 6);
        let later =
            start + Duration::from_secs(AuthGuard::retry_after_secs()) + Duration::from_millis(100);
        assert_eq!(guard.check(key, false, later), AuthOutcome::Unauthorized);
        assert_eq!(guard.check(key, true, later), AuthOutcome::Locked);
    }

    #[test]
    fn heavy_senders_yield_the_last_half_of_the_egress_budget() {
        let start = Instant::now();
        let mut egress = EgressBudget::new(1000, start);
        let heavy: IpAddr = "203.0.113.1".parse().unwrap();
        let light: IpAddr = "203.0.113.2".parse().unwrap();
        // Alone on the relay, a sender may use all of it.
        assert!(egress.try_spend(heavy, 600, start).is_ok());
        // Two senders: the fair share is 500, and only 400 are left.
        assert!(egress.try_spend(light, 100, start).is_ok());
        // The heavy one waits until more than half of the budget is back.
        assert_millis(egress.try_spend(heavy, 100, start), 200);
        assert!(egress.try_spend(light, 100, start).is_ok());
        assert!(egress.try_spend(light, 100, start).is_ok());
        // Light senders may spend the budget down to nothing, and overdraw it. That makes
        // this one heavy too: 200 in debt, it waits for 700 to get above half.
        assert!(egress.try_spend(light, 300, start).is_ok());
        assert_millis(egress.try_spend(light, 1, start), 700);

        // A second later both rates have decayed and the budget has refilled.
        let later = start + Duration::from_secs(2);
        assert!(egress.try_spend(heavy, 600, later).is_ok());
    }

    #[test]
    fn a_wait_ends_with_the_budget_above_the_reserve() {
        let start = Instant::now();
        let mut egress = EgressBudget::new(1000, start);
        let key: IpAddr = "203.0.113.1".parse().unwrap();
        assert!(egress.try_spend(key, 1000, start).is_ok());
        // Exactly empty: a retry needs at least a little refill.
        assert_eq!(egress.try_spend(key, 1, start), Err(MIN_RETRY));
        assert!(egress.try_spend(key, 1, start + MIN_RETRY).is_ok());
    }

    fn assert_millis(wait: Result<(), Duration>, millis: u64) {
        let wait = wait.expect_err("the budget is spent");
        let expected = Duration::from_millis(millis);
        assert!(
            wait.abs_diff(expected) < Duration::from_millis(1),
            "waits {wait:?}, expected {expected:?}"
        );
    }

    #[test]
    fn quiet_senders_stop_counting_toward_the_fair_share() {
        let start = Instant::now();
        let mut egress = EgressBudget::new(1000, start);
        let a: IpAddr = "203.0.113.1".parse().unwrap();
        let b: IpAddr = "203.0.113.2".parse().unwrap();
        assert!(egress.try_spend(b, 1, start).is_ok());
        let later = start + Duration::from_millis(1500);
        assert!(egress.try_spend(a, 1, later).is_ok());
        assert_eq!(egress.senders.entries.len(), 1);
    }
}
