//! Peer connections, their data channel and their event queue.

use std::{
    future::Future,
    sync::{
        Arc, Mutex, MutexGuard, OnceLock, PoisonError, Weak,
        atomic::{AtomicUsize, Ordering},
    },
    time::Duration,
};

use bytes::BytesMut;
use keyguard_ffi::{SessionTag, SharedTable};
use serde::{Deserialize, Serialize};
use tokio::sync::{mpsc, watch};
use webrtc::{
    data_channel::{DataChannel, DataChannelEvent},
    peer_connection::{
        PeerConnection, PeerConnectionBuilder, PeerConnectionEventHandler, RTCIceCandidateInit,
        RTCIceGatheringState, RTCPeerConnectionIceErrorEvent, RTCPeerConnectionIceEvent,
        RTCPeerConnectionState, RTCSessionDescription,
    },
};

use crate::{
    BUFFERED_LOW_THRESHOLD, EVENT_QUEUE_CAPACITY, MAX_CANDIDATE_BYTES, MAX_LABEL_BYTES,
    MAX_MESSAGE_BYTES, MAX_PEERS, MAX_SDP_BYTES, REQUEST_TIMEOUT, SDP_KIND_ANSWER, SDP_KIND_OFFER,
    SEND_BUFFER_LIMIT, WebrtcError,
    config::{PeerConfig, setting_engine},
    event::{
        ConnectionState, EVENT_ERROR_CHANNEL, EVENT_ERROR_ICE_SERVER, EVENT_ERROR_REMOTE_CANDIDATE,
        Event,
    },
    runtime::{RUNTIMES, RuntimeEntry},
    udp::{CappedRuntime, SourceFilter},
};

/// Remote candidates one peer takes, trickled or inline in a remote
/// description, held or applied: the stack pairs each with every local
/// candidate, and RFC 8445 §6.1.2.5 asks to bound the pairs.
const MAX_REMOTE_CANDIDATES: usize = 256;

/// Tag of a peer handle.
const HANDLE_TAG: SessionTag = SessionTag::new(2);

/// The live peers.
pub(crate) static PEERS: SharedTable<PeerEntry> = SharedTable::new(HANDLE_TAG, MAX_PEERS);

/// A remote or local ICE candidate on the wire.
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct CandidateJson {
    candidate: String,
    #[serde(default)]
    sdp_mid: Option<String>,
    #[serde(rename = "sdpMLineIndex", default)]
    sdp_m_line_index: Option<u16>,
}

/// The selected candidate pair on the wire.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SelectedPairJson {
    local_type: String,
    remote_type: String,
    protocol: String,
}

/// State shared by a peer's handle, its stack handler and its channel pump.
struct PeerShared {
    events: mpsc::Sender<Event>,
    closed: watch::Sender<bool>,
    channel: Mutex<Option<Arc<dyn DataChannel>>>,
    /// Weak, because the stack owns the handler that owns this.
    connection: OnceLock<Weak<dyn PeerConnection>>,
}

impl PeerShared {
    fn channel(&self) -> MutexGuard<'_, Option<Arc<dyn DataChannel>>> {
        self.channel.lock().unwrap_or_else(PoisonError::into_inner)
    }

    fn is_closed(&self) -> bool {
        *self.closed.borrow()
    }

    /// Queues an event, waiting while the queue is full. Returns `false`
    /// once the peer is closed.
    async fn emit(&self, event: Event) -> bool {
        let mut closed = self.closed.subscribe();
        tokio::select! {
            biased;
            _ = closed.wait_for(|closed| *closed) => false,
            sent = self.events.send(event) => sent.is_ok(),
        }
    }

    /// Adopts the peer's data channel and starts pumping its events. A second
    /// channel closes the whole connection: the stack keeps state for every
    /// channel a peer opens, so one that opens more could grow it without end.
    /// Must run inside the peer's runtime.
    fn attach_channel(self: &Arc<Self>, channel: Arc<dyn DataChannel>) -> bool {
        let mut slot = self.channel();
        if slot.is_some() || self.is_closed() {
            drop(slot);
            let connection = self.connection.get().and_then(Weak::upgrade);
            tokio::spawn(async move {
                let _ = channel.close().await;
                if let Some(connection) = connection {
                    let _ = connection.close().await;
                }
            });
            return false;
        }
        *slot = Some(Arc::clone(&channel));
        drop(slot);
        tokio::spawn(pump(channel, Arc::clone(self)));
        true
    }
}

/// Forwards the data channel's events into the peer's queue.
async fn pump(channel: Arc<dyn DataChannel>, shared: Arc<PeerShared>) {
    // Not in the stack's callback: these calls round-trip through the driver
    // that runs the callback.
    let _ = channel
        .set_buffered_amount_low_threshold(BUFFERED_LOW_THRESHOLD)
        .await;
    while let Some(event) = channel.poll().await {
        let event = match event {
            DataChannelEvent::OnOpen => Event::ChannelOpen,
            DataChannelEvent::OnMessage(message) => Event::ChannelMessage(message.data.into()),
            DataChannelEvent::OnBufferedAmountLow => Event::BufferedLow,
            DataChannelEvent::OnError => Event::Error(EVENT_ERROR_CHANNEL),
            DataChannelEvent::OnClose => break,
            _ => continue,
        };
        if !shared.emit(event).await {
            return;
        }
    }
    shared.emit(Event::ChannelClose).await;
}

/// Reads the selected candidate pair, if ICE selected one.
async fn selected_pair(connection: &dyn PeerConnection) -> Option<String> {
    let sctp = connection.sctp().await?;
    let pair = sctp
        .transport()
        .ice_transport()
        .get_selected_candidate_pair()
        .await
        .ok()??;
    serde_json::to_string(&SelectedPairJson {
        local_type: pair.local().typ.to_string(),
        remote_type: pair.remote().typ.to_string(),
        protocol: pair.local().protocol.to_string(),
    })
    .ok()
}

/// Turns stack callbacks into queued events. Runs on the stack's driver, so it
/// never calls back into the connection directly.
struct Handler {
    shared: Arc<PeerShared>,
}

#[async_trait::async_trait]
impl PeerConnectionEventHandler for Handler {
    async fn on_ice_candidate(&self, event: RTCPeerConnectionIceEvent) {
        let Ok(init) = event.candidate.to_json() else {
            return;
        };
        let json = serde_json::to_string(&CandidateJson {
            candidate: init.candidate,
            sdp_mid: init.sdp_mid,
            sdp_m_line_index: init.sdp_mline_index,
        });
        if let Ok(json) = json {
            self.shared.emit(Event::IceCandidate(json)).await;
        }
    }

    async fn on_ice_candidate_error(&self, _event: RTCPeerConnectionIceErrorEvent) {
        self.shared.emit(Event::Error(EVENT_ERROR_ICE_SERVER)).await;
    }

    async fn on_ice_gathering_state_change(&self, state: RTCIceGatheringState) {
        if state == RTCIceGatheringState::Complete {
            self.shared.emit(Event::IceGatheringComplete).await;
        }
    }

    async fn on_connection_state_change(&self, state: RTCPeerConnectionState) {
        let state = ConnectionState::from_stack(state);
        if !self.shared.emit(Event::ConnectionState(state)).await {
            return;
        }
        if state == ConnectionState::Connected {
            let shared = Arc::clone(&self.shared);
            tokio::spawn(async move {
                let Some(connection) = shared.connection.get().and_then(Weak::upgrade) else {
                    return;
                };
                if let Some(json) = selected_pair(connection.as_ref()).await {
                    shared.emit(Event::SelectedPair(json)).await;
                }
            });
        }
    }

    async fn on_data_channel(&self, channel: Arc<dyn DataChannel>) {
        self.shared.attach_channel(channel);
    }
}

/// One peer connection.
pub(crate) struct PeerEntry {
    runtime: Arc<RuntimeEntry>,
    connection: Arc<dyn PeerConnection>,
    shared: Arc<PeerShared>,
    events: tokio::sync::Mutex<mpsc::Receiver<Event>>,
    /// `Some` until the remote description is applied.
    pending_candidates: Mutex<Option<Vec<RTCIceCandidateInit>>>,
    /// Remote candidates taken so far, trickled or inline, held or applied.
    remote_candidates: AtomicUsize,
    /// Decides which datagrams reach the connection's stack.
    sources: SourceFilter,
}

impl PeerEntry {
    pub(crate) fn runtime(&self) -> &Arc<RuntimeEntry> {
        &self.runtime
    }

    pub(crate) fn connection(&self) -> &Arc<dyn PeerConnection> {
        &self.connection
    }

    /// Wakes every blocked call and stops queuing events. Idempotent.
    pub(crate) fn mark_closed(&self) {
        self.shared.closed.send_replace(true);
        // Drops the channel's reference back to the stack, which owns this.
        self.shared.channel().take();
    }

    fn pending_candidates(&self) -> MutexGuard<'_, Option<Vec<RTCIceCandidateInit>>> {
        self.pending_candidates
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
    }

    /// Charges `count` remote candidates to the peer's lifetime budget.
    fn take_remote_candidates(&self, count: usize) -> Result<(), WebrtcError> {
        self.remote_candidates
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |taken| {
                taken
                    .checked_add(count)
                    .filter(|&taken| taken <= MAX_REMOTE_CANDIDATES)
            })
            .map(drop)
            .map_err(|_| WebrtcError::InvalidState)
    }

    fn channel(&self) -> Result<Arc<dyn DataChannel>, WebrtcError> {
        if self.shared.is_closed() {
            return Err(WebrtcError::Closed);
        }
        self.shared
            .channel()
            .clone()
            .ok_or(WebrtcError::InvalidState)
    }

    /// Runs a stack request on the calling thread, bounded by
    /// [`REQUEST_TIMEOUT`] and cut short by a close.
    fn request<T>(
        &self,
        future: impl Future<Output = Result<T, webrtc::error::Error>>,
    ) -> Result<T, WebrtcError> {
        if self.shared.is_closed() {
            return Err(WebrtcError::Closed);
        }
        self.runtime.block_on(async {
            let mut closed = self.shared.closed.subscribe();
            tokio::select! {
                biased;
                _ = closed.wait_for(|closed| *closed) => Err(WebrtcError::Closed),
                result = tokio::time::timeout(REQUEST_TIMEOUT, future) => match result {
                    Ok(Ok(value)) => Ok(value),
                    Ok(Err(error)) => Err(WebrtcError::from_stack(&error)),
                    Err(_) => Err(WebrtcError::Timeout),
                },
            }
        })?
    }

    /// Applies a remote candidate in the background; a rejection becomes an
    /// [`Event::Error`].
    fn add_candidate(&self, candidate: RTCIceCandidateInit) -> Result<(), WebrtcError> {
        let connection = Arc::clone(&self.connection);
        let shared = Arc::clone(&self.shared);
        self.runtime.spawn(async move {
            if connection.add_ice_candidate(candidate).await.is_err() {
                shared
                    .emit(Event::Error(EVENT_ERROR_REMOTE_CANDIDATE))
                    .await;
            }
        })
    }
}

/// Creates a peer on `runtime` from the JSON configuration.
///
/// # Errors
///
/// Returns [`WebrtcError::InvalidArgument`] or [`WebrtcError::InputTooLong`]
/// for a bad configuration, [`WebrtcError::Closed`] when the runtime is shut
/// down, [`WebrtcError::Busy`] when [`MAX_PEERS`] are live, and the stack's
/// failure otherwise.
pub fn peer_new(runtime: u64, config_json: &str) -> Result<u64, WebrtcError> {
    let runtime = RUNTIMES.get(runtime)?;
    let config = PeerConfig::parse(config_json)?;
    let (events_tx, events_rx) = mpsc::channel(EVENT_QUEUE_CAPACITY);
    let shared = Arc::new(PeerShared {
        events: events_tx,
        closed: watch::Sender::new(false),
        channel: Mutex::new(None),
        connection: OnceLock::new(),
    });
    let handler = Arc::new(Handler {
        shared: Arc::clone(&shared),
    });
    let sources = SourceFilter::default();
    let builder = PeerConnectionBuilder::new()
        .with_configuration(config.configuration)
        .with_setting_engine(setting_engine())
        .with_handler(handler)
        .with_runtime(Arc::new(CappedRuntime::new(sources.clone())))
        .with_udp_addrs(config.bind_addresses)
        .with_data_channel_send_buffer_limit(SEND_BUFFER_LIMIT);
    // The timer must be created inside the runtime.
    let built =
        runtime.block_on(async { tokio::time::timeout(REQUEST_TIMEOUT, builder.build()).await })?;
    let connection: Arc<dyn PeerConnection> = match built {
        Ok(Ok(connection)) => Arc::new(connection),
        Ok(Err(error)) => return Err(WebrtcError::from_stack(&error)),
        Err(_) => return Err(WebrtcError::Timeout),
    };
    let _ = shared.connection.set(Arc::downgrade(&connection));
    let entry = Arc::new(PeerEntry {
        runtime: Arc::clone(&runtime),
        connection,
        shared,
        events: tokio::sync::Mutex::new(events_rx),
        pending_candidates: Mutex::new(Some(Vec::new())),
        remote_candidates: AtomicUsize::new(0),
        sources,
    });
    // Checked under the lock `runtime_shutdown` collects the peers under.
    PEERS
        .insert_with(Arc::clone(&entry), || runtime.check_open())
        .inspect_err(|_| {
            entry.mark_closed();
            let connection = Arc::clone(&entry.connection);
            // A runtime that is shutting down closes the connection itself.
            let _ = runtime.spawn(async move {
                let _ = connection.close().await;
            });
        })
}

/// Creates an offer, applies it as the local description and returns its SDP.
///
/// # Errors
///
/// Returns the stack's failure, [`WebrtcError::Timeout`] or [`WebrtcError::Closed`].
pub fn peer_create_offer(peer: u64) -> Result<String, WebrtcError> {
    let peer = PEERS.get(peer)?;
    let connection = Arc::clone(&peer.connection);
    let sources = peer.sources.clone();
    let sdp = peer.request(async move {
        let offer = connection.create_offer(None).await?;
        let sdp = offer.sdp.clone();
        let credentials = local_credentials(&offer);
        connection.set_local_description(offer).await?;
        arm(&sources, credentials);
        Ok(sdp)
    })?;
    bounded_sdp(sdp)
}

/// Creates an answer, applies it as the local description and returns its SDP.
///
/// # Errors
///
/// As [`peer_create_offer`]; [`WebrtcError::InvalidState`] or a stack failure
/// when no remote offer is set.
pub fn peer_create_answer(peer: u64) -> Result<String, WebrtcError> {
    let peer = PEERS.get(peer)?;
    let connection = Arc::clone(&peer.connection);
    let sources = peer.sources.clone();
    let sdp = peer.request(async move {
        let answer = connection.create_answer(None).await?;
        let sdp = answer.sdp.clone();
        let credentials = local_credentials(&answer);
        connection.set_local_description(answer).await?;
        arm(&sources, credentials);
        Ok(sdp)
    })?;
    bounded_sdp(sdp)
}

/// Reads the ICE ufrag and password of a description, at session or media
/// level. The stack writes the same pair into every media section.
fn local_credentials(description: &RTCSessionDescription) -> Option<(String, String)> {
    let parsed = description.unmarshal().ok()?;
    let value = |key: &str| {
        parsed
            .attribute(key)
            .map(String::as_str)
            .or_else(|| {
                parsed
                    .media_descriptions
                    .iter()
                    .find_map(|media| media.attribute(key).flatten())
            })
            .map(str::to_owned)
    };
    Some((value("ice-ufrag")?, value("ice-pwd")?))
}

/// Authenticates new sources with the credentials of the local description
/// the stack just applied: a remote peer learns them only from the SDP this
/// call returns, so no check of theirs can arrive before.
fn arm(sources: &SourceFilter, credentials: Option<(String, String)>) {
    if let Some((ufrag, password)) = credentials {
        sources.set_local_credentials(&ufrag, &password);
    }
}

fn bounded_sdp(sdp: String) -> Result<String, WebrtcError> {
    if sdp.len() > MAX_SDP_BYTES {
        Err(WebrtcError::Internal)
    } else {
        Ok(sdp)
    }
}

/// Counts the `a=candidate` lines of a description, at session and media
/// level, as the stack's own parser reads them.
fn inline_candidates(description: &RTCSessionDescription) -> Result<usize, WebrtcError> {
    let parsed = description
        .unmarshal()
        .map_err(|_| WebrtcError::InvalidArgument)?;
    let media = parsed
        .media_descriptions
        .iter()
        .flat_map(|media| &media.attributes);
    Ok(parsed
        .attributes
        .iter()
        .chain(media)
        .filter(|attribute| attribute.is_ice_candidate())
        .count())
}

/// Applies the remote description; `kind` is [`SDP_KIND_OFFER`] or
/// [`SDP_KIND_ANSWER`]. Candidates added before it are applied after it.
///
/// # Errors
///
/// Returns [`WebrtcError::InvalidArgument`] for an unknown kind or a
/// malformed SDP, [`WebrtcError::InvalidState`] when its candidates would
/// take the peer past [`MAX_REMOTE_CANDIDATES`], and the stack's failure
/// otherwise.
pub fn peer_set_remote(peer: u64, kind: u32, sdp: &str) -> Result<(), WebrtcError> {
    if sdp.len() > MAX_SDP_BYTES {
        return Err(WebrtcError::InputTooLong);
    }
    let peer = PEERS.get(peer)?;
    let description = match kind {
        SDP_KIND_OFFER => RTCSessionDescription::offer(sdp.to_owned()),
        SDP_KIND_ANSWER => RTCSessionDescription::answer(sdp.to_owned()),
        _ => return Err(WebrtcError::InvalidArgument),
    }
    .map_err(|_| WebrtcError::InvalidArgument)?;
    // The stack installs inline candidates itself, so they draw on the trickle budget.
    peer.take_remote_candidates(inline_candidates(&description)?)?;
    let connection = Arc::clone(&peer.connection);
    peer.request(async move { connection.set_remote_description(description).await })?;
    let pending = peer.pending_candidates().take().unwrap_or_default();
    for candidate in pending {
        peer.add_candidate(candidate)?;
    }
    Ok(())
}

/// Adds a remote ICE candidate, as `{"candidate", "sdpMid", "sdpMLineIndex"}`.
///
/// Returns once the candidate is parsed; it is applied in the background, and
/// held until the remote description is set. An empty `candidate` marks the
/// end of the remote candidates and is ignored, as is a TCP candidate.
///
/// # Errors
///
/// Returns [`WebrtcError::InvalidArgument`] for malformed JSON or candidate,
/// and [`WebrtcError::InvalidState`] once the peer took
/// [`MAX_REMOTE_CANDIDATES`].
pub fn peer_add_ice_candidate(peer: u64, candidate_json: &str) -> Result<(), WebrtcError> {
    if candidate_json.len() > MAX_CANDIDATE_BYTES {
        return Err(WebrtcError::InputTooLong);
    }
    let peer = PEERS.get(peer)?;
    let parsed: CandidateJson =
        serde_json::from_str(candidate_json).map_err(|_| WebrtcError::InvalidArgument)?;
    if parsed.candidate.is_empty() {
        return Ok(());
    }
    let attribute = parsed
        .candidate
        .strip_prefix("candidate:")
        .unwrap_or(&parsed.candidate);
    let parsed_candidate = rtc::ice::candidate::unmarshal_candidate(attribute)
        .map_err(|_| WebrtcError::InvalidArgument)?;
    // Peers gather UDP only. The stack would dial a TCP candidate itself, whatever the ICE
    // transport policy, so a relay-only peer would reach the address directly.
    if parsed_candidate.network_type().is_tcp() {
        return Ok(());
    }
    if peer.shared.is_closed() {
        return Err(WebrtcError::Closed);
    }
    peer.take_remote_candidates(1)?;
    let candidate = RTCIceCandidateInit {
        candidate: parsed.candidate,
        sdp_mid: parsed.sdp_mid,
        sdp_mline_index: parsed.sdp_m_line_index,
        ..Default::default()
    };
    if let Some(pending) = peer.pending_candidates().as_mut() {
        pending.push(candidate);
        return Ok(());
    }
    peer.add_candidate(candidate)
}

/// Creates the peer's ordered, reliable data channel. Call it on the offerer
/// before [`peer_create_offer`]; the answerer receives the channel.
///
/// # Errors
///
/// Returns [`WebrtcError::InvalidState`] when the peer already has a channel.
pub fn peer_create_channel(peer: u64, label: &str) -> Result<(), WebrtcError> {
    if label.len() > MAX_LABEL_BYTES {
        return Err(WebrtcError::InputTooLong);
    }
    let peer = PEERS.get(peer)?;
    if peer.shared.channel().is_some() {
        return Err(WebrtcError::InvalidState);
    }
    let connection = Arc::clone(&peer.connection);
    let shared = Arc::clone(&peer.shared);
    let label = label.to_owned();
    let attached = peer.request(async move {
        let channel = connection.create_data_channel(&label, None).await?;
        // Inside the runtime, which the pump is spawned on.
        Ok(shared.attach_channel(channel))
    })?;
    if attached {
        Ok(())
    } else {
        Err(WebrtcError::InvalidState)
    }
}

/// Queues one message of at most [`MAX_MESSAGE_BYTES`] on the open channel.
///
/// # Errors
///
/// Returns [`WebrtcError::Busy`] when the message would take the buffered
/// amount over [`SEND_BUFFER_LIMIT`]; retry after [`Event::BufferedLow`].
/// Returns [`WebrtcError::InvalidState`] with no open channel.
pub fn channel_send(peer: u64, data: &[u8]) -> Result<(), WebrtcError> {
    if data.len() > MAX_MESSAGE_BYTES {
        return Err(WebrtcError::InputTooLong);
    }
    let peer = PEERS.get(peer)?;
    let channel = peer.channel()?;
    let data = BytesMut::from(data);
    peer.request(async move { channel.try_send(data).await })
}

/// Returns the bytes queued on the channel and not yet acknowledged.
///
/// # Errors
///
/// Returns [`WebrtcError::InvalidState`] when the peer has no channel.
pub fn channel_buffered_amount(peer: u64) -> Result<usize, WebrtcError> {
    let peer = PEERS.get(peer)?;
    let channel = peer.channel()?;
    peer.request(async move { channel.outstanding_bytes().await })
}

/// Waits for the next event. Returns `None` after `timeout`.
///
/// # Errors
///
/// Returns [`WebrtcError::Closed`] once the peer is closed, also when it is
/// closed while this call waits.
pub fn peer_next_event(peer: u64, timeout: Duration) -> Result<Option<Event>, WebrtcError> {
    let peer = PEERS.get(peer)?;
    if peer.shared.is_closed() {
        return Err(WebrtcError::Closed);
    }
    peer.runtime.block_on(async {
        let mut closed = peer.shared.closed.subscribe();
        tokio::select! {
            biased;
            _ = closed.wait_for(|closed| *closed) => Err(WebrtcError::Closed),
            event = async { peer.events.lock().await.recv().await } => {
                event.map(Some).ok_or(WebrtcError::Closed)
            }
            () = tokio::time::sleep(timeout) => Ok(None),
        }
    })?
}

/// Closes the peer: wakes every blocked call at once and closes the
/// connection in the background. Idempotent.
///
/// # Errors
///
/// Returns [`WebrtcError::InvalidHandle`] for a value that is not a peer handle.
pub fn peer_close(peer: u64) -> Result<(), WebrtcError> {
    let Some(peer) = PEERS.remove(peer)? else {
        return Ok(());
    };
    peer.mark_closed();
    let connection = Arc::clone(&peer.connection);
    // A runtime that is shutting down closes the connection itself.
    let _ = peer.runtime.spawn(async move {
        let _ = connection.close().await;
    });
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn candidate_json_uses_the_browser_field_names() {
        let json = serde_json::to_string(&CandidateJson {
            candidate: "candidate:1 1 udp 1 127.0.0.1 9 typ host".into(),
            sdp_mid: Some("0".into()),
            sdp_m_line_index: Some(0),
        })
        .expect("a candidate serializes");
        assert_eq!(
            json,
            r#"{"candidate":"candidate:1 1 udp 1 127.0.0.1 9 typ host","sdpMid":"0","sdpMLineIndex":0}"#
        );
        let parsed: CandidateJson =
            serde_json::from_str(r#"{"candidate":"x"}"#).expect("optional fields may be missing");
        assert_eq!(parsed.sdp_mid, None);
        assert_eq!(parsed.sdp_m_line_index, None);
    }

    #[test]
    fn a_tcp_candidate_is_ignored() {
        let runtime = crate::runtime_new().expect("a runtime starts");
        let handle = peer_new(runtime, "{}").expect("a peer is created");
        let tcp = r#"{"candidate":"candidate:1 1 tcp 1 192.0.2.1 443 typ host tcptype passive","sdpMid":"0","sdpMLineIndex":0}"#;
        assert_eq!(peer_add_ice_candidate(handle, tcp), Ok(()));
        let udp = r#"{"candidate":"candidate:1 1 udp 1 192.0.2.1 9 typ host","sdpMid":"0","sdpMLineIndex":0}"#;
        assert_eq!(peer_add_ice_candidate(handle, udp), Ok(()));
        let pending = PEERS
            .get(handle)
            .expect("the peer is live")
            .pending_candidates()
            .as_ref()
            .map(Vec::len);
        // Only the UDP candidate waits for the remote description.
        assert_eq!(pending, Some(1));
        crate::runtime_shutdown(runtime).expect("the runtime stops");
    }

    #[test]
    fn remote_candidates_past_the_cap_are_refused() {
        let runtime = crate::runtime_new().expect("a runtime starts");
        let handle = peer_new(runtime, "{}").expect("a peer is created");
        let candidate = |port: usize| {
            format!(
                r#"{{"candidate":"candidate:1 1 udp 1 192.0.2.1 {port} typ host","sdpMid":"0","sdpMLineIndex":0}}"#
            )
        };
        for port in 1..=MAX_REMOTE_CANDIDATES {
            assert_eq!(peer_add_ice_candidate(handle, &candidate(port)), Ok(()));
        }
        assert_eq!(
            peer_add_ice_candidate(handle, &candidate(MAX_REMOTE_CANDIDATES + 1)),
            Err(WebrtcError::InvalidState)
        );
        crate::runtime_shutdown(runtime).expect("the runtime stops");
    }

    #[test]
    fn the_local_description_arms_the_source_filter() {
        let runtime = crate::runtime_new().expect("a runtime starts");
        let loopback = r#"{"bindAddresses":["127.0.0.1:0"]}"#;
        let offerer = peer_new(runtime, loopback).expect("the offerer is created");
        let answerer = peer_new(runtime, loopback).expect("the answerer is created");
        peer_create_channel(offerer, "keyguard").expect("the offerer creates the channel");
        let offer = peer_create_offer(offerer).expect("the offerer creates an offer");
        peer_set_remote(answerer, SDP_KIND_OFFER, &offer).expect("the answerer takes the offer");
        let answer = peer_create_answer(answerer).expect("the answerer creates an answer");
        let value = |sdp: &str, key: &str| {
            sdp.lines()
                .find_map(|line| line.strip_prefix(key))
                .expect("the description has ICE credentials")
                .to_owned()
        };
        let source = std::net::SocketAddr::from(([192, 0, 2, 1], 9));
        for (handle, sdp) in [(offerer, &offer), (answerer, &answer)] {
            let ufrag = value(sdp, "a=ice-ufrag:");
            let password = value(sdp, "a=ice-pwd:");
            let sources = &PEERS.get(handle).expect("the peer is live").sources;
            let forged = crate::udp::binding_request(&format!("{ufrag}:remote"), "forged");
            assert!(!sources.admit(source, &forged));
            let check = crate::udp::binding_request(&format!("{ufrag}:remote"), &password);
            assert!(sources.admit(source, &check));
        }
        crate::runtime_shutdown(runtime).expect("the runtime stops");
    }

    #[test]
    fn inline_candidates_share_the_cap_with_trickled_ones() {
        let runtime = crate::runtime_new().expect("a runtime starts");
        let loopback = r#"{"bindAddresses":["127.0.0.1:0"]}"#;
        let offerer = peer_new(runtime, loopback).expect("the offerer is created");
        let answerer = peer_new(runtime, loopback).expect("the answerer is created");
        peer_create_channel(offerer, "keyguard").expect("the offerer creates the channel");
        let offer = peer_create_offer(offerer).expect("the offerer creates an offer");
        // Drops the candidates the offer may already hold. Its only media
        // section is the data channel's, so appended lines belong to it.
        let offer: String = offer
            .lines()
            .filter(|line| !line.starts_with("a=candidate:"))
            .map(|line| format!("{line}\r\n"))
            .collect();
        let with_candidates = |count: usize| {
            (1..=count).fold(offer.clone(), |sdp, port| {
                sdp + &format!("a=candidate:1 1 udp 1 127.0.0.1 {port} typ host\r\n")
            })
        };
        let taken = || {
            PEERS
                .get(answerer)
                .expect("the peer is live")
                .remote_candidates
                .load(Ordering::Relaxed)
        };

        let trickled = MAX_REMOTE_CANDIDATES - 2;
        for port in 1..=trickled {
            let json = format!(
                r#"{{"candidate":"candidate:1 1 udp 1 127.0.0.2 {port} typ host","sdpMid":"0","sdpMLineIndex":0}}"#
            );
            assert_eq!(peer_add_ice_candidate(answerer, &json), Ok(()));
        }
        // Past the remaining budget: refused, and nothing is charged.
        assert_eq!(
            peer_set_remote(answerer, SDP_KIND_OFFER, &with_candidates(3)),
            Err(WebrtcError::InvalidState)
        );
        assert_eq!(taken(), trickled);
        assert_eq!(
            peer_set_remote(answerer, SDP_KIND_OFFER, &with_candidates(2)),
            Ok(())
        );
        assert_eq!(taken(), MAX_REMOTE_CANDIDATES);
        let json = r#"{"candidate":"candidate:1 1 udp 1 127.0.0.3 9 typ host","sdpMid":"0","sdpMLineIndex":0}"#;
        assert_eq!(
            peer_add_ice_candidate(answerer, json),
            Err(WebrtcError::InvalidState)
        );
        crate::runtime_shutdown(runtime).expect("the runtime stops");
    }
}
