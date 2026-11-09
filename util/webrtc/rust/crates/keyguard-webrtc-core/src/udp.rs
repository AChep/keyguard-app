//! UDP sockets that take datagrams only from expected or authenticated sources.
//!
//! rtc-ice 0.21.1 keeps a peer-reflexive candidate, and its candidate pairs,
//! for every authenticated STUN Binding request from a new source address,
//! with no cap, and searches them linearly. The remote peer reads the ICE
//! credentials in the SDP, so it could send requests from endless source
//! ports. The stack binds and polls its sockets through the runtime it is
//! given: [`CappedRuntime`] wraps each one so that a datagram reaches the
//! stack only from an address the stack sent to, or from one of at most
//! [`MAX_REMOTE_ADDRESSES`] other sources, each admitted by a Binding request
//! that passes authentication with the local ICE credentials. Any other
//! datagram is dropped before the stack sees it, and takes no slot.

use std::{
    collections::HashSet,
    fmt,
    future::Future,
    io::{self, IoSliceMut},
    net::SocketAddr,
    pin::Pin,
    sync::{Arc, Mutex, MutexGuard, PoisonError},
    task::{Context, Poll},
    time::{Duration, Instant},
};

use rtc::{
    crypto::{SecretVec, providers::RingCrypto},
    stun::{
        attributes::ATTR_USERNAME,
        integrity::MessageIntegrity,
        message::{BINDING_REQUEST, Message, is_stun_message},
    },
};
use webrtc::runtime::{
    AsyncInterval, AsyncTcpListener, AsyncTcpStream, AsyncUdpSocket, JoinHandle, RecvMeta, Runtime,
    TokioRuntime, Transmit,
};

/// Distinct sources, other than the addresses the stack sent to, that one
/// peer connection takes datagrams from. Each is a remote candidate the peer
/// did not signal, such as the far side of a NAT.
const MAX_REMOTE_ADDRESSES: usize = 64;

/// Datagrams one receive drops in a row before it yields to other tasks.
const MAX_DROPS_PER_POLL: usize = 64;

/// The ICE credentials of the local description, which a connectivity check
/// to this agent authenticates with.
struct LocalCredentials {
    /// `ufrag:`, which the USERNAME of a check to this agent starts with:
    /// RFC 8445 §7.2.2 puts the receiver's fragment first.
    username_prefix: Vec<u8>,
    /// The local password: the MESSAGE-INTEGRITY key.
    password: SecretVec,
}

impl fmt::Debug for LocalCredentials {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("LocalCredentials")
            .field(
                "username_prefix",
                &String::from_utf8_lossy(&self.username_prefix),
            )
            .field("password", &self.password)
            .finish()
    }
}

impl LocalCredentials {
    fn new(ufrag: &str, password: &str) -> Self {
        Self {
            username_prefix: format!("{ufrag}:").into_bytes(),
            password: SecretVec::new(password.as_bytes().to_vec()),
        }
    }

    /// Whether `datagram` is a STUN Binding request to this agent: its
    /// USERNAME starts with the local ufrag and a colon, and its
    /// MESSAGE-INTEGRITY is the HMAC-SHA1 keyed with the local password
    /// (RFC 5389 §15.4), checked as rtc-ice checks it.
    fn authenticate(&self, datagram: &[u8]) -> bool {
        // The header first: anything else costs no allocation.
        if !is_stun_message(datagram) || datagram[..2] != BINDING_REQUEST.value().to_be_bytes() {
            return false;
        }
        let mut message = Message::new();
        if message.unmarshal_binary(datagram).is_err() {
            return false;
        }
        // The first USERNAME, as the stack reads it.
        let Ok(username) = message.get(ATTR_USERNAME) else {
            return false;
        };
        username.starts_with(&self.username_prefix)
            && MessageIntegrity::check(&mut message, self.password.as_ref(), &RingCrypto).is_ok()
    }
}

/// The sources a peer connection takes datagrams from.
#[derive(Debug, Default)]
struct RemoteAddresses {
    /// The addresses the stack sent to, and the authenticated sources.
    known: HashSet<SocketAddr>,
    /// Sources admitted by an authenticated Binding request.
    authenticated: usize,
    /// `None` until a local description is set.
    credentials: Option<LocalCredentials>,
}

impl RemoteAddresses {
    /// Whether a datagram from `addr` may reach the stack: one from a known
    /// address may, and an authenticated Binding request from a new one while
    /// fewer than [`MAX_REMOTE_ADDRESSES`] were admitted that way.
    fn admit(&mut self, addr: SocketAddr, datagram: &[u8]) -> bool {
        if self.known.contains(&addr) {
            return true;
        }
        let admitted = self.authenticated < MAX_REMOTE_ADDRESSES
            && self
                .credentials
                .as_ref()
                .is_some_and(|credentials| credentials.authenticate(datagram));
        if admitted {
            self.known.insert(addr);
            self.authenticated += 1;
        }
        admitted
    }
}

/// Decides which datagrams reach the stack of one peer connection. Every UDP
/// socket of the connection shares it, so the cap holds across interfaces and
/// across the rebinds of an ICE restart.
///
/// The addresses the stack sends to are bounded without a cap of their own:
/// they are the remote candidates, which a peer takes at most 256 of, the
/// sources admitted here, and the configured STUN and TURN servers.
#[derive(Clone, Debug, Default)]
pub(crate) struct SourceFilter(Arc<Mutex<RemoteAddresses>>);

impl SourceFilter {
    fn lock(&self) -> MutexGuard<'_, RemoteAddresses> {
        self.0.lock().unwrap_or_else(PoisonError::into_inner)
    }

    /// Authenticates the Binding requests of new sources with the ICE
    /// credentials of the local description. Set it before the description
    /// leaves the peer: no check can carry the credentials before that.
    pub(crate) fn set_local_credentials(&self, ufrag: &str, password: &str) {
        self.lock().credentials = Some(LocalCredentials::new(ufrag, password));
    }

    /// Admits every later datagram from an address the stack sends to.
    fn sent_to(&self, addr: SocketAddr) {
        self.lock().known.insert(addr);
    }

    /// See [`RemoteAddresses::admit`].
    pub(crate) fn admit(&self, addr: SocketAddr, datagram: &[u8]) -> bool {
        self.lock().admit(addr, datagram)
    }
}

/// The tokio runtime of one peer connection, whose UDP sockets drop the
/// datagrams its [`SourceFilter`] does not admit.
#[derive(Debug)]
pub(crate) struct CappedRuntime {
    inner: TokioRuntime,
    filter: SourceFilter,
}

impl CappedRuntime {
    pub(crate) fn new(filter: SourceFilter) -> Self {
        Self {
            inner: TokioRuntime,
            filter,
        }
    }
}

impl Runtime for CappedRuntime {
    fn spawn(&self, future: Pin<Box<dyn Future<Output = ()> + Send>>) -> Box<dyn JoinHandle> {
        self.inner.spawn(future)
    }

    fn spawn_reactor(
        &self,
        reactor_pool_size: usize,
        future: Pin<Box<dyn Future<Output = ()> + Send>>,
    ) -> Box<dyn JoinHandle> {
        self.inner.spawn_reactor(reactor_pool_size, future)
    }

    fn wrap_udp_socket(&self, socket: std::net::UdpSocket) -> io::Result<Arc<dyn AsyncUdpSocket>> {
        Ok(Arc::new(CappedUdpSocket {
            inner: self.inner.wrap_udp_socket(socket)?,
            filter: self.filter.clone(),
        }))
    }

    fn wrap_tcp_listener(
        &self,
        listener: std::net::TcpListener,
    ) -> io::Result<Arc<dyn AsyncTcpListener>> {
        self.inner.wrap_tcp_listener(listener)
    }

    fn connect_tcp<'a>(
        &'a self,
        remote_addr: SocketAddr,
    ) -> Pin<Box<dyn Future<Output = io::Result<Arc<dyn AsyncTcpStream>>> + Send + 'a>> {
        self.inner.connect_tcp(remote_addr)
    }

    fn resolve_host<'a>(
        &'a self,
        host: &'a str,
    ) -> Pin<Box<dyn Future<Output = io::Result<Vec<SocketAddr>>> + Send + 'a>> {
        self.inner.resolve_host(host)
    }

    fn now(&self) -> Instant {
        self.inner.now()
    }

    fn sleep(&self, duration: Duration) -> Pin<Box<dyn Future<Output = ()> + Send + 'static>> {
        self.inner.sleep(duration)
    }

    fn interval(&self, period: Duration) -> Box<dyn AsyncInterval> {
        self.inner.interval(period)
    }

    fn block_on(&self, future: Pin<Box<dyn Future<Output = ()> + '_>>) {
        self.inner.block_on(future);
    }

    fn yield_now(&self) -> Pin<Box<dyn Future<Output = ()> + Send + 'static>> {
        self.inner.yield_now()
    }

    fn name(&self) -> &'static str {
        self.inner.name()
    }
}

/// A UDP socket that drops the datagrams its connection does not admit.
#[derive(Debug)]
struct CappedUdpSocket {
    inner: Arc<dyn AsyncUdpSocket>,
    filter: SourceFilter,
}

impl CappedUdpSocket {
    /// Whether the message received into `buf` may reach the stack. With GRO
    /// the buffer holds several datagrams of one source: the first decides.
    fn admit(&self, buf: &[u8], meta: &RecvMeta) -> bool {
        let first = meta.len.min(meta.stride);
        let datagram = buf.get(..first).unwrap_or_default();
        self.filter.admit(meta.addr, datagram)
    }
}

impl AsyncUdpSocket for CappedUdpSocket {
    fn local_addr(&self) -> io::Result<SocketAddr> {
        self.inner.local_addr()
    }

    fn poll_send(&self, cx: &mut Context<'_>, transmit: &Transmit<'_>) -> Poll<io::Result<usize>> {
        self.filter.sent_to(transmit.destination);
        self.inner.poll_send(cx, transmit)
    }

    /// Receives one message at a time, so a dropped one leaves no gap among
    /// the messages returned. The stack asks for one anyway.
    fn poll_recv(
        &self,
        cx: &mut Context<'_>,
        bufs: &mut [IoSliceMut<'_>],
        meta: &mut [RecvMeta],
    ) -> Poll<io::Result<usize>> {
        if bufs.is_empty() || meta.is_empty() {
            return self.inner.poll_recv(cx, bufs, meta);
        }
        let bufs = &mut bufs[..1];
        let meta = &mut meta[..1];
        for _ in 0..MAX_DROPS_PER_POLL {
            match self.inner.poll_recv(cx, bufs, meta) {
                Poll::Ready(Ok(n)) if n > 0 && !self.admit(&bufs[0], &meta[0]) => {}
                other => return other,
            }
        }
        // A flood from sources that are not admitted: let the other tasks run.
        cx.waker().wake_by_ref();
        Poll::Pending
    }

    fn max_gso_segments(&self) -> usize {
        self.inner.max_gso_segments()
    }

    fn max_gro_segments(&self) -> usize {
        self.inner.max_gro_segments()
    }
}

/// A connectivity check as rtc-ice sends it, to the agent whose credentials
/// are `username`'s first fragment and `password`.
#[cfg(test)]
pub(crate) fn binding_request(username: &str, password: &str) -> Vec<u8> {
    use rtc::stun::{fingerprint::FINGERPRINT, message::TransactionId, textattrs::Username};

    let mut message = Message::new();
    message
        .build(&[
            Box::new(BINDING_REQUEST),
            Box::new(TransactionId::new()),
            Box::new(Username::new(ATTR_USERNAME, username.to_owned())),
            Box::new(MessageIntegrity::new_short_term_integrity_with_provider(
                password.to_owned(),
                &RingCrypto,
            )),
            Box::new(FINGERPRINT),
        ])
        .expect("the request builds");
    message.raw
}

#[cfg(test)]
mod tests {
    use std::net::{Ipv4Addr, UdpSocket};

    use rtc::stun::message::MESSAGE_HEADER_SIZE;

    use super::*;

    const UFRAG: &str = "local";
    const PASSWORD: &str = "the-local-ice-password";

    fn addr(port: u16) -> SocketAddr {
        SocketAddr::from((Ipv4Addr::LOCALHOST, port))
    }

    /// A check to this agent with the right credentials.
    fn request() -> Vec<u8> {
        binding_request(&format!("{UFRAG}:remote"), PASSWORD)
    }

    fn armed() -> RemoteAddresses {
        RemoteAddresses {
            credentials: Some(LocalCredentials::new(UFRAG, PASSWORD)),
            ..RemoteAddresses::default()
        }
    }

    #[test]
    fn new_sources_past_the_cap_are_refused_and_known_ones_admitted() {
        let mut remotes = armed();
        for port in 1..=MAX_REMOTE_ADDRESSES as u16 {
            assert!(
                remotes.admit(addr(port), &request()),
                "address {port} is under the cap"
            );
        }
        let past = addr(MAX_REMOTE_ADDRESSES as u16 + 1);
        assert!(!remotes.admit(past, &request()));
        assert!(
            remotes.admit(addr(1), b"x"),
            "any datagram from a known address is admitted"
        );
        assert!(
            !remotes.admit(past, &request()),
            "a refused address stays refused"
        );
    }

    #[test]
    fn garbage_from_new_sources_takes_no_slot() {
        let mut remotes = armed();
        for port in 1..=2 * MAX_REMOTE_ADDRESSES as u16 {
            assert!(!remotes.admit(addr(port), b"garbage"));
        }
        assert_eq!(remotes.authenticated, 0);
        let late = addr(2 * MAX_REMOTE_ADDRESSES as u16 + 1);
        assert!(remotes.admit(late, &request()), "a later check is admitted");
    }

    #[test]
    fn a_check_with_another_ufrag_is_dropped() {
        let mut remotes = armed();
        for username in ["remote:local", "other:remote", "localx:remote", "local"] {
            let request = binding_request(username, PASSWORD);
            assert!(!remotes.admit(addr(1), &request), "{username}");
        }
        assert_eq!(remotes.authenticated, 0);
    }

    #[test]
    fn a_check_with_a_bad_integrity_is_dropped() {
        let mut remotes = armed();
        let forged = binding_request(&format!("{UFRAG}:remote"), "another-password");
        assert!(!remotes.admit(addr(1), &forged));
        // The right key over an altered transaction ID.
        let mut altered = request();
        altered[MESSAGE_HEADER_SIZE - 1] ^= 1;
        assert!(!remotes.admit(addr(1), &altered));
        assert_eq!(remotes.authenticated, 0);
        assert!(remotes.admit(addr(1), &request()));
    }

    #[test]
    fn checks_are_dropped_until_the_local_description_is_set() {
        let mut remotes = RemoteAddresses::default();
        assert!(!remotes.admit(addr(1), &request()));
        assert_eq!(remotes.authenticated, 0);
    }

    /// RFC 5769 §2.1: an ICE Binding request from `h6vY` to `evtj`, with
    /// SOFTWARE, PRIORITY, ICE-CONTROLLED, MESSAGE-INTEGRITY and FINGERPRINT.
    #[test]
    fn the_rfc_5769_sample_request_authenticates() {
        const SAMPLE: [u8; 108] = [
            0x00, 0x01, 0x00, 0x58, 0x21, 0x12, 0xa4, 0x42, 0xb7, 0xe7, 0xa7, 0x01, 0xbc, 0x34,
            0xd6, 0x86, 0xfa, 0x87, 0xdf, 0xae, 0x80, 0x22, 0x00, 0x10, 0x53, 0x54, 0x55, 0x4e,
            0x20, 0x74, 0x65, 0x73, 0x74, 0x20, 0x63, 0x6c, 0x69, 0x65, 0x6e, 0x74, 0x00, 0x24,
            0x00, 0x04, 0x6e, 0x00, 0x01, 0xff, 0x80, 0x29, 0x00, 0x08, 0x93, 0x2f, 0xf9, 0xb1,
            0x51, 0x26, 0x3b, 0x36, 0x00, 0x06, 0x00, 0x09, 0x65, 0x76, 0x74, 0x6a, 0x3a, 0x68,
            0x36, 0x76, 0x59, 0x20, 0x20, 0x20, 0x00, 0x08, 0x00, 0x14, 0x9a, 0xea, 0xa7, 0x0c,
            0xbf, 0xd8, 0xcb, 0x56, 0x78, 0x1e, 0xf2, 0xb5, 0xb2, 0xd3, 0xf2, 0x49, 0xc1, 0xb5,
            0x71, 0xa2, 0x80, 0x28, 0x00, 0x04, 0xe5, 0x7a, 0x3b, 0xcf,
        ];
        let filter = SourceFilter::default();
        filter.set_local_credentials("evtj", "VOkJxbRl1RmTxUk/WvJxBt");
        assert!(filter.admit(addr(1), &SAMPLE));
        // The sender's fragment comes second: `h6vY` is not the receiver.
        let filter = SourceFilter::default();
        filter.set_local_credentials("h6vY", "VOkJxbRl1RmTxUk/WvJxBt");
        assert!(!filter.admit(addr(1), &SAMPLE));
    }

    #[test]
    fn addresses_sent_to_are_always_admitted() {
        let filter = SourceFilter::default();
        filter.sent_to(addr(1));
        assert!(
            filter.admit(addr(1), b"x"),
            "admitted before the local description is set"
        );
        filter.set_local_credentials(UFRAG, PASSWORD);
        for port in 2..=MAX_REMOTE_ADDRESSES as u16 + 1 {
            assert!(filter.admit(addr(port), &request()));
        }
        let past = addr(MAX_REMOTE_ADDRESSES as u16 + 2);
        assert!(!filter.admit(past, &request()), "the cap is full");
        filter.sent_to(past);
        assert!(filter.admit(past, b"x"), "admitted past the cap");
    }

    /// Receives the next admitted datagram and its source.
    async fn next(socket: &dyn AsyncUdpSocket) -> (Vec<u8>, SocketAddr) {
        let mut buf = [0; 512];
        let (n, from) = tokio::time::timeout(Duration::from_secs(5), socket.recv_from(&mut buf))
            .await
            .expect("a datagram arrives")
            .expect("the receive succeeds");
        (buf[..n].to_vec(), from)
    }

    /// A wrapped receiver armed with [`UFRAG`] and [`PASSWORD`], and its address.
    fn receiver() -> (Arc<dyn AsyncUdpSocket>, SocketAddr) {
        let filter = SourceFilter::default();
        filter.set_local_credentials(UFRAG, PASSWORD);
        let runtime = CappedRuntime::new(filter);
        let receiver = UdpSocket::bind(addr(0)).expect("the receiver binds");
        receiver
            .set_nonblocking(true)
            .expect("the receiver is non-blocking");
        let target = receiver.local_addr().expect("the receiver has an address");
        let receiver = runtime
            .wrap_udp_socket(receiver)
            .expect("the receiver wraps");
        (receiver, target)
    }

    fn senders(count: usize) -> Vec<UdpSocket> {
        (0..count)
            .map(|_| UdpSocket::bind(addr(0)).expect("a sender binds"))
            .collect()
    }

    fn source(sender: &UdpSocket) -> SocketAddr {
        sender.local_addr().expect("the sender has an address")
    }

    #[tokio::test]
    async fn datagrams_from_sources_past_the_cap_never_reach_the_stack() {
        let (receiver, target) = receiver();
        // One sender per source address, plus one past the cap.
        let senders = senders(MAX_REMOTE_ADDRESSES + 1);

        for sender in &senders[..MAX_REMOTE_ADDRESSES] {
            sender.send_to(&request(), target).expect("a send succeeds");
        }
        let mut received = Vec::new();
        for _ in 0..MAX_REMOTE_ADDRESSES {
            received.push(next(receiver.as_ref()).await.1);
        }
        received.sort_unstable();
        let mut expected = senders[..MAX_REMOTE_ADDRESSES]
            .iter()
            .map(source)
            .collect::<Vec<_>>();
        expected.sort_unstable();
        assert_eq!(received, expected, "every source under the cap is admitted");

        // The receive skips the check from the new source and returns the
        // datagram the known one sent after it.
        let past = &senders[MAX_REMOTE_ADDRESSES];
        let known = &senders[0];
        for _ in 0..2 {
            past.send_to(&request(), target).expect("a send succeeds");
            known.send_to(&[0], target).expect("a send succeeds");
            assert_eq!(next(receiver.as_ref()).await, (vec![0], source(known)));
        }
    }

    #[tokio::test]
    async fn garbage_from_many_sources_does_not_block_a_later_check() {
        let (receiver, target) = receiver();
        let senders = senders(MAX_REMOTE_ADDRESSES + 2);
        let (late, garbage) = senders.split_last().expect("there are senders");
        for sender in garbage {
            sender.send_to(b"garbage", target).expect("a send succeeds");
            let forged = binding_request(&format!("{UFRAG}:remote"), "another-password");
            sender.send_to(&forged, target).expect("a send succeeds");
        }
        let request = request();
        late.send_to(&request, target).expect("a send succeeds");
        assert_eq!(next(receiver.as_ref()).await, (request, source(late)));
    }

    #[tokio::test]
    async fn datagrams_from_an_address_sent_to_reach_the_stack() {
        let (receiver, target) = receiver();
        let remote = UdpSocket::bind(addr(0)).expect("the remote binds");
        receiver
            .send_to(b"check", source(&remote))
            .await
            .expect("a send succeeds");
        remote.send_to(&[7], target).expect("a send succeeds");
        assert_eq!(next(receiver.as_ref()).await, (vec![7], source(&remote)));
    }
}
