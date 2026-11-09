//! Rate limits and connection caps keyed by client address, and the relay's egress budget.

use std::collections::HashMap;
use std::collections::hash_map::Entry;
use std::io;
use std::net::{IpAddr, Ipv6Addr, SocketAddr};
use std::pin::Pin;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::task::{Context, Poll, ready};
use std::time::Duration;

use axum::http::{HeaderMap, StatusCode};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::sync::Notify;
use tokio::time::{self, Instant, Sleep};

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
    fn new(capacity: f64, rate: f64, now: Instant) -> Self {
        Self {
            capacity,
            rate,
            tokens: capacity,
            updated: now,
        }
    }

    /// A bucket of `count` tokens that refills as many every `window_secs`.
    fn per_window(count: f64, window_secs: f64, now: Instant) -> Self {
        Self::new(count, count / window_secs, now)
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
            messages: TokenBucket::per_window(msgs, 1.0, now),
            bytes: TokenBucket::per_window(bytes, 1.0, now),
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

/// A stream whose reads spend a byte budget with a one-second burst. Past it, the relay
/// stops reading until the budget refills, so TCP slows the client down. It counts every
/// byte read, HTTP and WebSocket framing included: the message budget never sees a
/// message that doesn't complete, such as one sent as endless empty fragments.
///
/// A write the peer takes no bytes of for `write_stall` fails, which drops the
/// connection: a client that stops reading a response can't hold it open.
pub(crate) struct MeteredStream<S> {
    inner: S,
    budget: TokenBucket,
    /// Wakes the reader once the budget refills.
    refill: Pin<Box<Sleep>>,
    write_stall: Duration,
    /// Armed while a write waits for the peer.
    stalled: Pin<Box<Sleep>>,
    waiting: bool,
}

impl<S> MeteredStream<S> {
    pub(crate) fn new(inner: S, bytes_per_sec: u64, write_stall: Duration, now: Instant) -> Self {
        // Exact for every configurable value (about 1 GiB at most).
        let bytes = bytes_per_sec as f64;
        Self {
            inner,
            budget: TokenBucket::per_window(bytes, 1.0, now),
            refill: Box::pin(time::sleep_until(now)),
            write_stall,
            stalled: Box::pin(time::sleep_until(now)),
            waiting: false,
        }
    }

    /// `poll` of a write, failed once it has waited `write_stall`.
    fn timed<T>(&mut self, cx: &mut Context<'_>, poll: Poll<io::Result<T>>) -> Poll<io::Result<T>> {
        if poll.is_ready() {
            self.waiting = false;
            return poll;
        }
        if !self.waiting {
            self.waiting = true;
            let deadline = Instant::now() + self.write_stall;
            self.stalled.as_mut().reset(deadline);
        }
        ready!(self.stalled.as_mut().poll(cx));
        Poll::Ready(Err(io::ErrorKind::TimedOut.into()))
    }
}

impl<S: AsyncRead + Unpin> AsyncRead for MeteredStream<S> {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        loop {
            let now = Instant::now();
            if this.budget.available(now) >= 1.0 {
                break;
            }
            let wait = this.budget.time_until(1.0).max(MIN_RETRY);
            this.refill.as_mut().reset(now + wait);
            ready!(this.refill.as_mut().poll(cx));
        }
        let before = buf.filled().len();
        ready!(Pin::new(&mut this.inner).poll_read(cx, buf))?;
        // A read may overdraw the budget by its size; the debt refills like any spend.
        this.budget.take((buf.filled().len() - before) as f64);
        Poll::Ready(Ok(()))
    }
}

impl<S: AsyncWrite + Unpin> AsyncWrite for MeteredStream<S> {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        let this = self.get_mut();
        let poll = Pin::new(&mut this.inner).poll_write(cx, buf);
        this.timed(cx, poll)
    }

    fn poll_write_vectored(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        bufs: &[io::IoSlice<'_>],
    ) -> Poll<io::Result<usize>> {
        let this = self.get_mut();
        let poll = Pin::new(&mut this.inner).poll_write_vectored(cx, bufs);
        this.timed(cx, poll)
    }

    fn is_write_vectored(&self) -> bool {
        self.inner.is_write_vectored()
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        let poll = Pin::new(&mut this.inner).poll_flush(cx);
        this.timed(cx, poll)
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        let poll = Pin::new(&mut this.inner).poll_shutdown(cx);
        this.timed(cx, poll)
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

/// The IPv6 prefix one party can hold cheaply: RFC 6177 lets an end site have a /48, and
/// tunnel brokers hand them out for free.
const NETWORK_PREFIX: u32 = 48;

/// What the /64s of one IPv6 /48 may take together, as a multiple of each per-address
/// limit. Without it, a /48 counts as 65 536 client keys and can take a relay-wide limit
/// on its own. go-libp2p caps a /48 at 8 times its per-client connection limit too.
pub(crate) const KEYS_PER_NETWORK: u32 = 8;

/// The IPv6 /48 of the client key `key`, whose /64s share [`KEYS_PER_NETWORK`] times each
/// per-address limit. An IPv4 address has none: each one is scarce on its own.
pub(crate) fn network_of(key: IpAddr) -> Option<IpAddr> {
    match key {
        IpAddr::V6(v6) => Some(IpAddr::V6(Ipv6Addr::from(
            u128::from(v6) & !(u128::MAX >> NETWORK_PREFIX),
        ))),
        IpAddr::V4(_) => None,
    }
}

/// How many of something each key holds. A key that holds none is removed.
#[derive(Debug, Default)]
pub(crate) struct Counts(HashMap<IpAddr, usize>);

impl Counts {
    pub(crate) fn get(&self, key: IpAddr) -> usize {
        self.0.get(&key).copied().unwrap_or(0)
    }

    pub(crate) fn add(&mut self, key: IpAddr) {
        *self.0.entry(key).or_insert(0) += 1;
    }

    pub(crate) fn remove(&mut self, key: IpAddr) {
        if let Entry::Occupied(mut count) = self.0.entry(key) {
            *count.get_mut() -= 1;
            if *count.get() == 0 {
                count.remove();
            }
        }
    }

    /// The keys that hold something.
    #[cfg(test)]
    pub(crate) fn len(&self) -> usize {
        self.0.len()
    }
}

/// Caps concurrent connections per client key, per IPv6 /48 and on the whole relay.
pub(crate) struct ConnectionLimiter {
    max_per_key: usize,
    max_per_network: usize,
    max_total: usize,
    counts: Mutex<ConnectionCounts>,
    /// Signalled whenever a permit is released.
    released: Notify,
}

struct ConnectionCounts {
    per_key: Counts,
    per_network: Counts,
    total: usize,
}

/// Why a connection got no slot. Fixed values label metrics.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ConnectionRefused {
    /// The client key holds its maximum.
    PerAddress = 0,
    /// The relay holds its maximum.
    Total = 1,
    /// The IPv6 /48 of the client key holds its maximum.
    PerNetwork = 2,
}

impl ConnectionRefused {
    pub(crate) const LABELS: [&str; 3] = ["per_address", "total", "per_network"];

    /// `429` for a client over its own limit, `503` for a relay over its own.
    pub(crate) fn status(self) -> StatusCode {
        match self {
            Self::PerAddress | Self::PerNetwork => StatusCode::TOO_MANY_REQUESTS,
            Self::Total => StatusCode::SERVICE_UNAVAILABLE,
        }
    }
}

impl ConnectionLimiter {
    pub(crate) fn new(max_per_key: u32, max_total: usize) -> Self {
        let max_per_key = max_per_key as usize;
        Self {
            max_per_key,
            max_per_network: max_per_key * KEYS_PER_NETWORK as usize,
            max_total,
            counts: Mutex::new(ConnectionCounts {
                per_key: Counts::default(),
                per_network: Counts::default(),
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
        let network = key.and_then(network_of);
        if key.is_some_and(|key| counts.per_key.get(key) >= self.max_per_key) {
            return Err(ConnectionRefused::PerAddress);
        }
        if network.is_some_and(|network| counts.per_network.get(network) >= self.max_per_network) {
            return Err(ConnectionRefused::PerNetwork);
        }
        if counts.total >= self.max_total {
            return Err(ConnectionRefused::Total);
        }
        if let Some(key) = key {
            counts.per_key.add(key);
        }
        if let Some(network) = network {
            counts.per_network.add(network);
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
        if let Some(key) = self.key {
            counts.per_key.remove(key);
            if let Some(network) = network_of(key) {
                counts.per_network.remove(network);
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

impl PerKey<TokenBucket> {
    /// The bucket of `key`, made by `make` if missing. Full buckets are forgotten: a new
    /// one is the same.
    fn bucket(
        &mut self,
        key: IpAddr,
        now: Instant,
        make: impl FnOnce() -> TokenBucket,
    ) -> Option<&mut TokenBucket> {
        self.get(key, now, |bucket| !bucket.is_full(now), make)
    }
}

/// A bucket per client key, and one per IPv6 /48 that holds [`KEYS_PER_NETWORK`] times
/// as many tokens. Both refill over the same window.
struct KeyedBuckets {
    /// Tokens of a key's bucket.
    per_key: f64,
    /// Seconds a bucket takes to refill.
    window: f64,
    keys: PerKey<TokenBucket>,
    networks: PerKey<TokenBucket>,
}

/// Why [`KeyedBuckets::check`] found no room.
enum Short<'a> {
    /// No room to track another key or network.
    Untracked,
    Network(&'a TokenBucket),
    Key(&'a TokenBucket),
}

/// The buckets [`KeyedBuckets::check`] allowed a spend from.
struct Spend<'a> {
    key: &'a mut TokenBucket,
    network: Option<&'a mut TokenBucket>,
}

impl Spend<'_> {
    fn take(self, amount: f64) {
        self.key.take(amount);
        if let Some(network) = self.network {
            network.take(amount);
        }
    }
}

impl KeyedBuckets {
    fn new(per_key: f64, window: f64, now: Instant) -> Self {
        Self {
            per_key,
            window,
            keys: PerKey::new(SWEEP_INTERVAL, now),
            networks: PerKey::new(SWEEP_INTERVAL, now),
        }
    }

    /// The buckets of `key` and its network, if `enough` accepts both. The network comes
    /// first, so a spent one adds no state for the key.
    fn check(
        &mut self,
        key: IpAddr,
        now: Instant,
        enough: impl Fn(&mut TokenBucket) -> bool,
    ) -> Result<Spend<'_>, Short<'_>> {
        let (per_key, window) = (self.per_key, self.window);
        let network = match network_of(key) {
            Some(network) => {
                let count = per_key * f64::from(KEYS_PER_NETWORK);
                let bucket = self
                    .networks
                    .bucket(network, now, || TokenBucket::per_window(count, window, now))
                    .ok_or(Short::Untracked)?;
                if !enough(bucket) {
                    return Err(Short::Network(bucket));
                }
                Some(bucket)
            }
            None => None,
        };
        let key = self
            .keys
            .bucket(key, now, || TokenBucket::per_window(per_key, window, now))
            .ok_or(Short::Untracked)?;
        if !enough(key) {
            return Err(Short::Key(key));
        }
        Ok(Spend { key, network })
    }
}

/// Why an HTTP request was not admitted. Fixed values label metrics.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum RequestRefused {
    PerAddress = 0,
    Total = 1,
    /// The bounded address tracker has no room for a new key.
    Addresses = 2,
    /// The IPv6 /48 of the client key spent its allowance.
    PerNetwork = 3,
}

impl RequestRefused {
    pub(crate) const LABELS: [&str; 4] = ["per_address", "total", "addresses", "per_network"];

    /// `429` for a client over its own allowance, `503` for a relay over its own.
    pub(crate) fn status(self) -> StatusCode {
        match self {
            Self::PerAddress | Self::PerNetwork => StatusCode::TOO_MANY_REQUESTS,
            Self::Total | Self::Addresses => StatusCode::SERVICE_UNAVAILABLE,
        }
    }
}

/// HTTP requests per second or per minute, with a burst of that window per address, per
/// IPv6 /48 and globally. State survives connection closure, so reconnecting does not
/// replenish an address's budget.
pub(crate) struct RequestAllowance {
    /// Seconds one request of a client key takes to refill.
    retry_after: u64,
    inner: Mutex<RequestAllowanceState>,
}

struct RequestAllowanceState {
    buckets: KeyedBuckets,
    total: TokenBucket,
}

impl RequestAllowance {
    pub(crate) fn new(per_sec: u32, total_per_sec: u32, now: Instant) -> Self {
        Self::with_window(per_sec, total_per_sec, 1, now)
    }

    pub(crate) fn per_minute(per_minute: u32, total_per_minute: u32, now: Instant) -> Self {
        Self::with_window(per_minute, total_per_minute, 60, now)
    }

    fn with_window(per_window: u32, total_per_window: u32, window_secs: u32, now: Instant) -> Self {
        let window = f64::from(window_secs);
        Self {
            retry_after: u64::from(window_secs).div_ceil(u64::from(per_window.max(1))),
            inner: Mutex::new(RequestAllowanceState {
                buckets: KeyedBuckets::new(f64::from(per_window), window, now),
                total: TokenBucket::per_window(f64::from(total_per_window), window, now),
            }),
        }
    }

    /// `Retry-After` of a refusal: the seconds one request of a client key takes to
    /// refill.
    pub(crate) fn retry_after(&self) -> u64 {
        self.retry_after
    }

    /// Takes every allowance, or none. Rejections cannot consume another address's
    /// global allowance, and a spent global or network allowance creates no new address
    /// state.
    pub(crate) fn try_take(&self, key: IpAddr, now: Instant) -> Result<(), RequestRefused> {
        let mut guard = lock(&self.inner);
        let RequestAllowanceState { buckets, total } = &mut *guard;
        if total.available(now) < 1.0 {
            return Err(RequestRefused::Total);
        }
        let spend = buckets
            .check(key, now, |bucket| bucket.available(now) >= 1.0)
            .map_err(|short| match short {
                Short::Untracked => RequestRefused::Addresses,
                Short::Network(_) => RequestRefused::PerNetwork,
                Short::Key(_) => RequestRefused::PerAddress,
            })?;
        spend.take(1.0);
        total.take(1.0);
        Ok(())
    }
}

/// TURN credentials: `per_minute` per client key, of which `ticketless_per_minute` may
/// go to requests without a live ticket, [`KEYS_PER_NETWORK`] times `per_minute` per
/// IPv6 /48, and `total_per_minute` on the whole relay, of which requests without a live
/// ticket can't take the last half.
pub(crate) struct TurnAllowance {
    ticketless_per_minute: f64,
    total_reserve: f64,
    inner: Mutex<TurnAllowanceState>,
}

struct TurnAllowanceState {
    /// Every credential of a key and of its network.
    all: KeyedBuckets,
    /// The credentials of a key requested without a live ticket.
    ticketless: PerKey<TokenBucket>,
    total: TokenBucket,
}

impl TurnAllowance {
    pub(crate) fn new(
        per_minute: u32,
        ticketless_per_minute: u32,
        total_per_minute: u32,
        now: Instant,
    ) -> Self {
        Self {
            ticketless_per_minute: f64::from(ticketless_per_minute),
            total_reserve: f64::from(total_per_minute) / 2.0,
            inner: Mutex::new(TurnAllowanceState {
                all: KeyedBuckets::new(f64::from(per_minute), 60.0, now),
                ticketless: PerKey::new(SWEEP_INTERVAL, now),
                total: TokenBucket::per_window(f64::from(total_per_minute), 60.0, now),
            }),
        }
    }

    /// Spends one credential of `key`, or nothing when an allowance is short.
    pub(crate) fn try_take(&self, key: IpAddr, ticketed: bool, now: Instant) -> bool {
        let mut guard = lock(&self.inner);
        let TurnAllowanceState {
            all,
            ticketless,
            total,
        } = &mut *guard;
        // First, so a request the relay can't serve anyway adds no state for its key.
        let reserve = if ticketed { 0.0 } else { self.total_reserve };
        if total.available(now) < 1.0 + reserve {
            return false;
        }
        let Ok(spend) = all.check(key, now, |bucket| bucket.available(now) >= 1.0) else {
            return false;
        };
        if !ticketed {
            let per_minute = self.ticketless_per_minute;
            let Some(bucket) =
                ticketless.bucket(key, now, || TokenBucket::per_window(per_minute, 60.0, now))
            else {
                return false;
            };
            if bucket.available(now) < 1.0 {
                return false;
            }
            bucket.take(1.0);
        }
        spend.take(1.0);
        total.take(1.0);
        true
    }
}

/// Bytes per minute per client key, and [`KEYS_PER_NETWORK`] times as many per IPv6 /48.
/// A bucket that isn't empty may be overdrawn, so an upload larger than a minute's
/// allowance still goes through; the debt refills like any other spend.
pub(crate) struct ByteAllowance {
    buckets: Mutex<KeyedBuckets>,
}

impl ByteAllowance {
    pub(crate) fn new(per_minute: u64, now: Instant) -> Self {
        Self {
            // Exact for every configurable value (at most 1 TiB).
            buckets: Mutex::new(KeyedBuckets::new(per_minute as f64, 60.0, now)),
        }
    }

    /// Spends `bytes` of `key` and its network, or nothing and returns how long until
    /// both have bytes left.
    pub(crate) fn try_take(&self, key: IpAddr, bytes: u64, now: Instant) -> Result<(), Duration> {
        let mut buckets = lock(&self.buckets);
        match buckets.check(key, now, |bucket| bucket.available(now) > 0.0) {
            Ok(spend) => {
                // Exact up to 2^53 bytes.
                spend.take(bytes as f64);
                Ok(())
            }
            Err(Short::Untracked) => Err(FULL_SWEEP_INTERVAL),
            Err(Short::Network(bucket) | Short::Key(bucket)) => {
                Err(bucket.time_until(0.0).max(MIN_RETRY))
            }
        }
    }
}

/// Failed token checks a client key may make per minute.
pub(crate) const AUTH_FAILURES_PER_MINUTE: u32 = 10;

/// Seconds until a locked key may try again.
pub(crate) const AUTH_RETRY_AFTER_SECS: u64 = 60u64.div_ceil(AUTH_FAILURES_PER_MINUTE as u64);

/// The answer to a token check.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum AuthOutcome {
    Allowed,
    Unauthorized,
    /// The key failed too often; another wrong token must wait.
    Locked,
}

/// Spends one of [`AUTH_FAILURES_PER_MINUTE`] on every failed token check of a client key,
/// and one of [`KEYS_PER_NETWORK`] times as many of its IPv6 /48. With none left, wrong
/// tokens wait. A valid token never spends or clears failures.
pub(crate) struct AuthGuard {
    failures: Mutex<KeyedBuckets>,
}

impl AuthGuard {
    pub(crate) fn new(now: Instant) -> Self {
        let per_key = f64::from(AUTH_FAILURES_PER_MINUTE);
        Self {
            failures: Mutex::new(KeyedBuckets::new(per_key, 60.0, now)),
        }
    }

    pub(crate) fn check(&self, key: IpAddr, authorized: bool, now: Instant) -> AuthOutcome {
        // A different client behind the same NAT must not lock out a valid token, or
        // clear the failure debt by sending one. Overall HTTP admission is separate.
        if authorized {
            return AuthOutcome::Allowed;
        }
        let mut failures = lock(&self.failures);
        match failures.check(key, now, |bucket| bucket.available(now) >= 1.0) {
            Ok(spend) => {
                spend.take(1.0);
                AuthOutcome::Unauthorized
            }
            // Including too many keys or networks failing at once to track another.
            Err(_) => AuthOutcome::Locked,
        }
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
            bucket: TokenBucket::per_window(rate, 1.0, now),
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
        let tracked = senders.len();
        let entry = senders.entry(sender);
        let fair_share =
            self.rate / (tracked + usize::from(matches!(entry, Entry::Vacant(_)))) as f64;
        let sent = entry.or_insert(SendRate {
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
            let mut wait = self.bucket.time_until(reserve);
            if reserve > 0.0 {
                // A sender above its share waits as long as its share takes to earn the
                // bytes, so blocked senders take turns instead of all waking at each refill.
                let earn = Duration::try_from_secs_f64(bytes / fair_share).unwrap_or(Duration::MAX);
                wait = wait.max(earn);
            }
            return Err(wait.max(MIN_RETRY));
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
    fn http_admission_preserves_other_addresses_and_refills() {
        let start = Instant::now();
        let allowance = RequestAllowance::new(2, 3, start);
        let a: IpAddr = "203.0.113.1".parse().unwrap();
        let b: IpAddr = "203.0.113.2".parse().unwrap();
        let c: IpAddr = "203.0.113.3".parse().unwrap();
        assert_eq!(allowance.try_take(a, start), Ok(()));
        assert_eq!(allowance.try_take(a, start), Ok(()));
        assert_eq!(
            allowance.try_take(a, start),
            Err(RequestRefused::PerAddress)
        );
        // A refused address did not spend the final global token.
        assert_eq!(allowance.try_take(b, start), Ok(()));
        assert_eq!(allowance.try_take(c, start), Err(RequestRefused::Total));
        assert_eq!(lock(&allowance.inner).buckets.keys.entries.len(), 2);
        assert_eq!(
            allowance.try_take(a, start + Duration::from_millis(500)),
            Ok(())
        );
        // Full buckets are forgotten; churn does not retain idle addresses forever.
        assert_eq!(
            allowance.try_take(c, start + Duration::from_secs(61)),
            Ok(())
        );
        assert_eq!(lock(&allowance.inner).buckets.keys.entries.len(), 1);
    }

    #[test]
    fn per_minute_admission_refills_over_a_minute() {
        let start = Instant::now();
        let allowance = RequestAllowance::per_minute(2, 100, start);
        let key: IpAddr = "203.0.113.1".parse().unwrap();
        assert_eq!(allowance.try_take(key, start), Ok(()));
        assert_eq!(allowance.try_take(key, start), Ok(()));
        assert_eq!(
            allowance.try_take(key, start + Duration::from_secs(29)),
            Err(RequestRefused::PerAddress)
        );
        assert_eq!(
            allowance.try_take(key, start + Duration::from_secs(31)),
            Ok(())
        );
    }

    #[test]
    fn byte_allowance_lets_one_large_upload_overdraw_then_waits() {
        let start = Instant::now();
        let allowance = ByteAllowance::new(600, start);
        let key: IpAddr = "203.0.113.1".parse().unwrap();
        assert_eq!(allowance.try_take(key, 1_200, start), Ok(()));
        // 600 in debt at 10 bytes a second.
        let wait = allowance.try_take(key, 1, start).unwrap_err();
        assert_eq!(wait, Duration::from_secs(60));
        assert!(allowance.try_take(key, 1, start + wait).is_err());
        assert_eq!(
            allowance.try_take(key, 1, start + wait + Duration::from_secs(1)),
            Ok(())
        );
        // Another address has its own allowance.
        assert_eq!(
            allowance.try_take("203.0.113.2".parse().unwrap(), 600, start),
            Ok(())
        );
    }

    #[test]
    fn byte_allowance_caps_an_ipv6_network() {
        let start = Instant::now();
        let allowance = ByteAllowance::new(100, start);
        for subnet in 0..KEYS_PER_NETWORK {
            assert_eq!(allowance.try_take(v6(1, subnet as u16), 100, start), Ok(()));
        }
        assert!(allowance.try_take(v6(1, 100), 1, start).is_err());
        assert_eq!(allowance.try_take(v6(2, 0), 100, start), Ok(()));
    }

    #[test]
    fn http_admission_fails_closed_when_the_address_tracker_is_full() {
        let start = Instant::now();
        let allowance = RequestAllowance::new(1, 10, start);
        {
            let mut state = lock(&allowance.inner);
            for n in 0..u32::try_from(MAX_KEYS).unwrap() {
                let mut bucket = TokenBucket::new(1.0, 1.0, start);
                bucket.take(1.0);
                state
                    .buckets
                    .keys
                    .entries
                    .insert(IpAddr::from(std::net::Ipv4Addr::from(n)), bucket);
            }
        }
        let key = IpAddr::from(std::net::Ipv4Addr::from(u32::MAX));
        assert_eq!(
            allowance.try_take(key, start),
            Err(RequestRefused::Addresses)
        );
        assert_eq!(lock(&allowance.inner).buckets.keys.entries.len(), MAX_KEYS);
        assert_eq!(allowance.try_take(key, start + FULL_SWEEP_INTERVAL), Ok(()));
        assert_eq!(lock(&allowance.inner).buckets.keys.entries.len(), 1);
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

    /// The client key of the /64 `subnet` in the IPv6 /48 `2001:db8:<site>::/48`.
    fn v6(site: u16, subnet: u16) -> IpAddr {
        client_key(format!("2001:db8:{site:x}:{subnet:x}::1").parse().unwrap())
    }

    #[test]
    fn ipv6_networks_are_their_slash_48() {
        assert_eq!(network_of(v6(1, 0)), network_of(v6(1, 0xffff)));
        assert_ne!(network_of(v6(1, 0)), network_of(v6(2, 0)));
        assert_eq!(
            network_of(v6(1, 7)),
            Some("2001:db8:1::".parse::<IpAddr>().unwrap())
        );
        assert_eq!(network_of("203.0.113.1".parse().unwrap()), None);
        assert_eq!(
            network_of(client_key("::ffff:203.0.113.1".parse().unwrap())),
            None
        );
    }

    #[test]
    fn connections_per_ipv6_network_are_capped() {
        let limiter = Arc::new(ConnectionLimiter::new(1, 100));
        let max = KEYS_PER_NETWORK as usize;
        let permits: Vec<_> = (0..max)
            .map(|subnet| limiter.acquire(Some(v6(1, subnet as u16))).unwrap())
            .collect();
        assert_eq!(
            limiter.acquire(Some(v6(1, max as u16))).err(),
            Some(ConnectionRefused::PerNetwork)
        );
        // The address limit is reported first, and other networks have their own.
        assert_eq!(
            limiter.acquire(Some(v6(1, 0))).err(),
            Some(ConnectionRefused::PerAddress)
        );
        let other = limiter.acquire(Some(v6(2, 0))).unwrap();
        drop(permits);
        let _again = limiter.acquire(Some(v6(1, max as u16))).unwrap();
        drop(other);
        let counts = lock(&limiter.counts);
        assert_eq!((counts.per_key.len(), counts.per_network.len()), (1, 1));
    }

    #[test]
    fn http_admission_caps_an_ipv6_network() {
        let start = Instant::now();
        let allowance = RequestAllowance::new(1, 1000, start);
        for subnet in 0..KEYS_PER_NETWORK {
            assert_eq!(allowance.try_take(v6(1, subnet as u16), start), Ok(()));
        }
        // Fresh /64s of the network are refused without adding address state.
        for subnet in KEYS_PER_NETWORK..100 {
            assert_eq!(
                allowance.try_take(v6(1, subnet as u16), start),
                Err(RequestRefused::PerNetwork)
            );
        }
        assert_eq!(
            lock(&allowance.inner).buckets.keys.entries.len(),
            KEYS_PER_NETWORK as usize
        );
        assert_eq!(allowance.try_take(v6(2, 0), start), Ok(()));
        // The network refills KEYS_PER_NETWORK requests a second.
        let later = start + Duration::from_millis(1000 / u64::from(KEYS_PER_NETWORK) + 1);
        assert_eq!(allowance.try_take(v6(1, 100), later), Ok(()));
        assert_eq!(
            allowance.try_take(v6(1, 101), later),
            Err(RequestRefused::PerNetwork)
        );
    }

    #[test]
    fn turn_allowance_caps_an_ipv6_network() {
        let start = Instant::now();
        let allowance = TurnAllowance::new(1, 1, 1000, start);
        for subnet in 0..KEYS_PER_NETWORK {
            assert!(allowance.try_take(v6(1, subnet as u16), true, start));
        }
        assert!(!allowance.try_take(v6(1, 100), true, start));
        assert!(!allowance.try_take(v6(1, 101), false, start));
        assert!(allowance.try_take(v6(2, 0), false, start));
        // A refused network spends nothing and adds no address state.
        assert_eq!(
            lock(&allowance.inner).all.keys.entries.len(),
            KEYS_PER_NETWORK as usize + 1
        );
    }

    #[test]
    fn failed_token_checks_lock_an_ipv6_network() {
        let start = Instant::now();
        let guard = AuthGuard::new(start);
        let failures = AUTH_FAILURES_PER_MINUTE * KEYS_PER_NETWORK;
        // Each /64 fails less often than its own limit allows.
        for attempt in 0..failures {
            let key = v6(1, (attempt % (KEYS_PER_NETWORK * 2)) as u16);
            assert_eq!(guard.check(key, false, start), AuthOutcome::Unauthorized);
        }
        assert_eq!(guard.check(v6(1, 999), false, start), AuthOutcome::Locked);
        // A valid token still goes through, and other networks have their own failures.
        assert_eq!(guard.check(v6(1, 999), true, start), AuthOutcome::Allowed);
        assert_eq!(
            guard.check(v6(2, 0), false, start),
            AuthOutcome::Unauthorized
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
        assert_eq!(counts.per_key.len(), 0);
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
        assert_eq!(lock(&limiter.counts).per_key.get(key), 1);
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
        assert_eq!(lock(&allowance.inner).all.keys.entries.len(), 1);
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
        assert_eq!(lock(&allowance.inner).all.keys.entries.len(), 2);
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
        // Valid traffic neither inherits nor clears another client's failure debt.
        assert_eq!(guard.check(key, true, start), AuthOutcome::Allowed);
        assert_eq!(guard.check(key, false, start), AuthOutcome::Locked);
        let other: IpAddr = "203.0.113.2".parse().unwrap();
        assert_eq!(guard.check(other, true, start), AuthOutcome::Allowed);
        // Only the key that failed is tracked.
        assert_eq!(lock(&guard.failures).keys.entries.len(), 1);

        assert_eq!(AUTH_RETRY_AFTER_SECS, 6);
        let later = start + Duration::from_secs(AUTH_RETRY_AFTER_SECS) + Duration::from_millis(100);
        assert_eq!(guard.check(key, false, later), AuthOutcome::Unauthorized);
        assert_eq!(guard.check(key, true, later), AuthOutcome::Allowed);
        assert_eq!(guard.check(key, false, later), AuthOutcome::Locked);
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
    fn blocked_heavy_senders_wait_for_their_share() {
        let start = Instant::now();
        let mut egress = EgressBudget::new(1000, start);
        let heavy: IpAddr = "203.0.113.1".parse().unwrap();
        let light: IpAddr = "203.0.113.2".parse().unwrap();
        assert!(egress.try_spend(heavy, 600, start).is_ok());
        assert!(egress.try_spend(light, 100, start).is_ok());
        // The budget is above half again in 200 ms, but 400 bytes take 800 ms at a fair
        // share of 500 a second.
        assert_millis(egress.try_spend(heavy, 400, start), 800);
        // A sender within its share only waits for the budget, here until it isn't empty.
        assert!(egress.try_spend(light, 300, start).is_ok());
        assert_eq!(egress.try_spend(light, 1, start), Err(MIN_RETRY));
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
