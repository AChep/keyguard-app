//! The WebSocket endpoint: JSON control frames and binary relay-forward frames.

use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::time::Duration;

use axum::Extension;
use axum::body::Bytes;
use axum::extract::State;
use axum::extract::ws::{CloseFrame, Message, Utf8Bytes, WebSocket, WebSocketUpgrade, close_code};
use axum::http::{HeaderMap, Uri};
use axum::response::Response;
use bytes::BytesMut;
use tokio::sync::Notify;
use tokio::sync::mpsc;
use tokio::time::{self, Instant, MissedTickBehavior};

use crate::limits::{AuthOutcome, ConnectionBudget, ConnectionPermit};
use crate::metrics::MessageSize;
use crate::protocol::{
    ClientFrame, ErrorCode, FEATURES, FEATURES_WITH_MAILBOX, FORWARD_HEADER_LEN,
    FORWARD_PEER_OFFSET, FORWARD_TAG, Limits, PEER_ID_LEN, PROTOCOL_VERSION, PeerId, ROOM_ID_LEN,
    RoomId, ServerFrame, Target, encode_hex, message_len,
};
use crate::room::{JoinError, Outbox, QUEUE_CAPACITY, Queued, Seat};
use crate::ticket::{Activity, LiveTicket, Ticket};
use crate::{
    AppState, ClientKey, MAILBOX_MAX_BLOB_BYTES, MAILBOX_MAX_STATE_BYTES, MAX_FRAME_BYTES, auth,
    http_error, stopping,
};

/// Upper bound of the server's WebSocket ping interval.
const PING_INTERVAL: Duration = Duration::from_secs(25);
/// How long a frame may wait for the relay's egress budget before it is dropped. The
/// sender's socket isn't read meanwhile, so the sender slows down instead of losing frames.
const EGRESS_MAX_WAIT: Duration = Duration::from_secs(5);
/// How long a client has to answer the relay's close frame before its socket is dropped.
const CLOSE_GRACE: Duration = Duration::from_secs(1);

/// `GET /v1/ws`.
pub(crate) async fn upgrade(
    ws: WebSocketUpgrade,
    State(state): State<Arc<AppState>>,
    Extension(ClientKey(key)): Extension<ClientKey>,
    headers: HeaderMap,
    uri: Uri,
) -> Response {
    let authorized = match auth::check(&state, key, &headers, uri.query()) {
        AuthOutcome::Allowed => true,
        AuthOutcome::Unauthorized => false,
        AuthOutcome::Locked => return auth::locked(),
    };
    // Held until the socket closes, so metrics and shutdown count pending upgrades too.
    let permit = match state.connections.acquire(Some(key)) {
        Ok(permit) => permit,
        Err(reason) => {
            state.metrics.websocket_refusals[reason as usize].inc();
            return http_error(reason.status(), ErrorCode::RateLimited);
        }
    };
    // Messages up to twice the limit are read and answered with `frame_too_big`;
    // anything larger drops the connection inside the WebSocket codec.
    let hard_limit = MAX_FRAME_BYTES * 2;
    // Keep the initial allocation small; larger frames grow the decoder buffer.
    ws.read_buffer_size(16 * 1024)
        .max_message_size(hard_limit)
        .max_frame_size(hard_limit)
        // Automatic control replies must not accumulate outside the outgoing queue.
        // Leave room for the JSON envelope added to a maximum-sized publication.
        .write_buffer_size(0)
        .max_write_buffer_size(hard_limit)
        .on_upgrade(move |socket| Connection::new(socket, state, key, permit).run(authorized))
}

enum Flow {
    Continue,
    /// Send a close frame, then stop.
    Close(u16, &'static str),
    /// Stop without a close frame: the socket is gone or already closing.
    Disconnect,
}

/// The connection's rooms, while it is in at least one. Dropping it, however the
/// connection ends, leaves every room and ends the ticket.
struct Joined {
    seats: HashMap<RoomId, Seat>,
    _ticket: LiveTicket,
}

impl Joined {
    /// Whether `queued` is for a seat the connection still has: leaving a room discards
    /// the frames queued from it, even after a new join of the same room.
    fn holds(&self, queued: &Queued) -> bool {
        self.seats
            .get(&queued.room)
            .is_some_and(|seat| seat.id() == queued.seat)
    }

    /// The room of a `pub`: the one it names, or else the only one.
    fn pub_room(&self, room: Option<RoomId>) -> Result<RoomId, ErrorCode> {
        match room {
            Some(room) if self.seats.contains_key(&room) => Ok(room),
            Some(_) => Err(ErrorCode::NotJoined),
            None => {
                let mut rooms = self.seats.keys();
                match (rooms.next(), rooms.next()) {
                    (Some(room), None) => Ok(*room),
                    _ => Err(ErrorCode::BadFrame),
                }
            }
        }
    }
}

/// A client frame over the connection's budget, handled once the budget allows it.
struct Held {
    message: Message,
    retry_at: Instant,
}

/// One outstanding heartbeat. A reply can be free only once, and a later heartbeat
/// replaces the previous one rather than accumulating exemptions during backpressure.
#[derive(Default)]
struct Heartbeat {
    pending: Option<[u8; 16]>,
}

impl Heartbeat {
    fn ping(&mut self) -> Message {
        let token = rand::random();
        self.pending = Some(token);
        Message::Ping(Bytes::copy_from_slice(&token))
    }

    fn accept(&mut self, data: &[u8]) -> bool {
        self.pending
            .take_if(|token| token.as_slice() == data)
            .is_some()
    }
}

/// A `msg` or binary frame from this connection on its way to `target` in `room`.
struct Delivery {
    room: RoomId,
    target: Target,
    message: Message,
    /// When to try again while the egress budget is spent.
    retry_at: Instant,
    /// When to drop the frame instead.
    give_up_at: Instant,
}

struct Connection {
    socket: WebSocket,
    state: Arc<AppState>,
    id: u64,
    /// Drawn by the relay for the connection's lifetime, the same in all its rooms.
    peer: PeerId,
    /// `peer` as `welcome` and `msg` frames name it.
    peer_hex: String,
    /// The client key, which shares the relay's egress budget.
    key: IpAddr,
    /// Where the connection's rooms queue frames for it.
    outbox: Outbox,
    /// Frames from the rooms, including some queued before a leave, which are discarded.
    inbox: mpsc::Receiver<Queued>,
    /// Signalled when the queue is full.
    overflow: Arc<Notify>,
    budget: ConnectionBudget,
    heartbeat: Heartbeat,
    /// One ticket per connection, live only while it is in at least one room.
    ticket: Ticket,
    /// When the client last sent an admitted frame.
    activity: Activity,
    /// A frame that waits for the connection's budget. The socket isn't read meanwhile.
    held: Option<Held>,
    /// A frame that waits for the egress budget. The socket isn't read meanwhile.
    waiting: Option<Delivery>,
    // Dropped before the permit, so leaves are announced before the socket stops counting.
    joined: Option<Joined>,
    _permit: ConnectionPermit,
}

impl Connection {
    fn new(socket: WebSocket, state: Arc<AppState>, key: IpAddr, permit: ConnectionPermit) -> Self {
        let id = state.next_connection_id.fetch_add(1, Ordering::Relaxed);
        let overflow = Arc::new(Notify::new());
        let (outbox, inbox) = Outbox::new(Arc::clone(&overflow));
        // `rand::random` draws from a CSPRNG, so a peer can't guess another's id.
        let peer: PeerId = rand::random();
        Self {
            socket,
            key,
            id,
            peer,
            peer_hex: encode_hex(&peer),
            outbox,
            inbox,
            overflow,
            budget: ConnectionBudget::new(
                state.config.msgs_per_sec,
                state.config.bytes_per_sec,
                Instant::now(),
            ),
            ticket: rand::random(),
            heartbeat: Heartbeat::default(),
            activity: Activity::default(),
            held: None,
            waiting: None,
            joined: None,
            _permit: permit,
            state,
        }
    }

    async fn run(self, authorized: bool) {
        let mut shutdown = self.state.shutdown.clone();
        tokio::select! {
            () = self.run_until_shutdown(authorized) => {}
            () = async {
                let deadline = shutdown.wait_for(Option::is_some).await.ok().and_then(|stop| *stop);
                if let Some(deadline) = deadline {
                    time::sleep_until(deadline).await;
                }
            } => {}
        }
    }

    async fn run_until_shutdown(mut self, authorized: bool) {
        tracing::debug!(connection = self.id, "socket opened");
        if !authorized {
            if let Flow::Close(code, reason) = self.fail(ErrorCode::Unauthorized).await {
                self.close(code, reason).await;
            }
            return;
        }
        if let Flow::Disconnect = self.welcome().await {
            return;
        }
        let idle = self.state.config.idle_timeout;
        let ping_every = (idle / 2).clamp(Duration::from_millis(100), PING_INTERVAL);
        let mut ping = time::interval_at(Instant::now() + ping_every, ping_every);
        ping.set_missed_tick_behavior(MissedTickBehavior::Delay);
        let mut last_seen = Instant::now();
        // Built once for the whole connection: making a timer or a waiter per frame takes
        // locks shared with every other connection.
        let idle_timer = time::sleep_until(last_seen + idle);
        // Moved to the time of a frame that waits for a budget.
        let retry_timer = time::sleep_until(last_seen);
        let overflow = Arc::clone(&self.overflow);
        let overflowed = overflow.notified();
        let mut shutdown = self.state.shutdown.clone();
        let stopping = stopping(&mut shutdown);
        tokio::pin!(idle_timer, retry_timer, overflowed, stopping);
        loop {
            let retry_at = self
                .waiting
                .as_ref()
                .map(|delivery| delivery.retry_at)
                .or_else(|| self.held.as_ref().map(|held| held.retry_at));
            if let Some(at) = retry_at
                && retry_timer.deadline() != at
            {
                retry_timer.as_mut().reset(at);
            }
            let reading = retry_at.is_none();
            let flow = tokio::select! {
                incoming = self.socket.recv(), if reading => match incoming {
                    Some(Ok(message)) => {
                        last_seen = Instant::now();
                        self.on_message(message).await
                    }
                    Some(Err(_)) | None => Flow::Disconnect,
                },
                Some(queued) = self.inbox.recv() => self.on_queued(queued).await,
                () = &mut retry_timer, if !reading => {
                    let flow = self.resume().await;
                    // The relay stopped reading, not the client: idle time starts over.
                    last_seen = Instant::now();
                    flow
                },
                // Not while the socket isn't read: the pongs would only pile up.
                _ = ping.tick(), if reading => {
                    let heartbeat = self.heartbeat.ping();
                    self.send(heartbeat).await
                },
                // Set for an earlier `last_seen`: moved on when it fires, not on every frame.
                () = &mut idle_timer, if reading => {
                    let deadline = last_seen + idle;
                    if Instant::now() < deadline {
                        idle_timer.as_mut().reset(deadline);
                        Flow::Continue
                    } else {
                        self.state.metrics.idle_closes.inc();
                        Flow::Close(close_code::NORMAL, "idle")
                    }
                },
                () = &mut overflowed => {
                    self.state.metrics.slow_consumer_closes.inc();
                    Flow::Close(close_code::AGAIN, "slow_consumer")
                },
                () = &mut stopping => {
                    self.state.metrics.shutdown_closes.inc();
                    Flow::Close(close_code::AWAY, "shutdown")
                },
            };
            match flow {
                Flow::Continue => {}
                Flow::Close(code, reason) => {
                    self.close(code, reason).await;
                    break;
                }
                Flow::Disconnect => break,
            }
        }
        tracing::debug!(connection = self.id, "socket closed");
    }

    /// Sends the `welcome` frame: the connection's peer id and the relay's limits.
    async fn welcome(&mut self) -> Flow {
        let config = &self.state.config;
        // Advertised while storage is configured, even while it is full or unusable.
        let mailbox = config.mailbox.as_ref();
        let limits = Limits {
            max_message_bytes: MAX_FRAME_BYTES,
            messages_per_second: config.msgs_per_sec,
            bytes_per_second: config.bytes_per_sec,
            rooms_per_connection: config.max_rooms_per_conn,
            peers_per_room: config.max_room_peers,
            queued_frames: QUEUE_CAPACITY,
            idle_seconds: config.idle_timeout.as_secs(),
            mailbox_state_bytes: mailbox.map(|_| MAILBOX_MAX_STATE_BYTES),
            mailbox_blob_bytes: mailbox.map(|_| MAILBOX_MAX_BLOB_BYTES),
            mailbox_ttl_seconds: mailbox.map(|mailbox| mailbox.ttl_seconds),
        };
        let welcome = ServerFrame::Welcome {
            v: PROTOCOL_VERSION,
            peer: &self.peer_hex,
            features: if mailbox.is_some() {
                FEATURES_WITH_MAILBOX
            } else {
                FEATURES
            },
            limits,
        };
        self.send(welcome.to_message()).await
    }

    async fn send(&mut self, message: Message) -> Flow {
        let timeout = self.state.config.timeouts.send;
        let size = MessageSize::of(&message);
        match time::timeout(timeout, self.socket.send(message)).await {
            Ok(Ok(())) => {
                self.state.metrics.sent(size);
                Flow::Continue
            }
            Ok(Err(_)) => {
                self.state.metrics.send_errors.inc();
                Flow::Disconnect
            }
            Err(_) => {
                self.state.metrics.send_timeouts.inc();
                Flow::Disconnect
            }
        }
    }

    /// Sends a close frame and waits up to [`CLOSE_GRACE`] for the client's answer.
    /// Dropping the socket at once would answer the frames the client still sends, such
    /// as pongs, with a TCP reset: its next write then fails before it reads the close.
    async fn close(&mut self, code: u16, reason: &'static str) {
        let frame = CloseFrame {
            code,
            reason: Utf8Bytes::from(reason),
        };
        if let Flow::Continue = self.send(Message::Close(Some(frame))).await {
            // Other peers see the leave now, not after the wait.
            self.joined = None;
            // Frames before the answer are dropped; the stream ends with it.
            let answered = async { while let Some(Ok(_)) = self.socket.recv().await {} };
            _ = time::timeout(CLOSE_GRACE, answered).await;
        }
    }

    /// Sends an `err` frame and decides whether the connection goes on.
    async fn fail(&mut self, code: ErrorCode) -> Flow {
        self.fail_in(code, None).await
    }

    /// Like [`Self::fail`], with the room of a refused join.
    async fn fail_in(&mut self, code: ErrorCode, room: Option<&RoomId>) -> Flow {
        self.state.metrics.errors[code as usize].inc();
        let room = room.map(|room| encode_hex(room));
        let frame = ServerFrame::Err {
            code,
            room: room.as_deref(),
        };
        match self.send(frame.to_message()).await {
            Flow::Continue => code
                .close_code()
                .map_or(Flow::Continue, |close| Flow::Close(close, code.as_str())),
            flow => flow,
        }
    }

    async fn on_message(&mut self, message: Message) -> Flow {
        self.state.metrics.received(&message);
        match message {
            // Classify only on receipt: a held frame is always admitted on retry.
            Message::Pong(ref data) if self.heartbeat.accept(data) => {
                self.state.metrics.heartbeat_replies.inc();
                Flow::Continue
            }
            // The codec answers close and ends the stream on the next read.
            Message::Close(_) => Flow::Continue,
            message if message_len(&message) > MAX_FRAME_BYTES => {
                self.fail(ErrorCode::FrameTooBig).await
            }
            message => self.admit(message, true).await,
        }
    }

    /// Handles a client data or control frame within the connection's budget. Over it,
    /// the frame waits and the socket isn't read meanwhile, so the client slows down
    /// instead of losing frames.
    async fn admit(&mut self, message: Message, first_attempt: bool) -> Flow {
        let now = Instant::now();
        if let Err(wait) = self.budget.try_spend(message_len(&message), now) {
            if first_attempt {
                self.state.metrics.connection_rate_limits.inc();
            }
            self.held = Some(Held {
                message,
                retry_at: now + wait,
            });
            return Flow::Continue;
        }
        self.activity
            .store(self.state.tickets.now_ms(now), Ordering::Relaxed);
        match message {
            Message::Text(text) => self.on_text(text).await,
            Message::Binary(data) => self.on_binary(data).await,
            // Replace the codec's pending automatic pong and flush it through the
            // timed writer before reading more pings from a non-reading client.
            Message::Ping(data) => self.send(Message::Pong(data)).await,
            Message::Pong(_) | Message::Close(_) => Flow::Continue,
        }
    }

    async fn on_text(&mut self, text: Utf8Bytes) -> Flow {
        let Some(frame) = ClientFrame::parse(&text) else {
            return self.fail(ErrorCode::BadFrame).await;
        };
        match frame {
            ClientFrame::Join { room } => self.on_join(room).await,
            ClientFrame::Pub { room, to, data } => self.on_pub(room, to, data).await,
            ClientFrame::Leave { room } => self.on_leave(room).await,
            ClientFrame::Ping => self.send(ServerFrame::Pong.to_message()).await,
            ClientFrame::Unknown => self.fail(ErrorCode::UnknownType).await,
        }
    }

    async fn on_join(&mut self, room: RoomId) -> Flow {
        if let Some(joined) = &self.joined {
            if joined.seats.contains_key(&room) {
                return self.fail(ErrorCode::BadFrame).await;
            }
            if joined.seats.len() >= self.state.config.max_rooms_per_conn {
                return self.fail_in(ErrorCode::TooManyRooms, Some(&room)).await;
            }
        }
        let joining = self
            .state
            .rooms
            .join(room, self.peer, self.key, &self.outbox);
        let (seat, others) = match joining {
            Ok(seated) => seated,
            Err(error) => {
                let metrics = &self.state.metrics;
                let limit = match error {
                    JoinError::RoomFull => {
                        return self.fail_in(ErrorCode::RoomFull, Some(&room)).await;
                    }
                    JoinError::RelayFull => &metrics.room_limits,
                    JoinError::AddressRoomLimit => &metrics.address_room_limits,
                    JoinError::NetworkRoomLimit => &metrics.network_room_limits,
                };
                limit.inc();
                return self.fail_in(ErrorCode::RateLimited, Some(&room)).await;
            }
        };
        let joined = self.joined.get_or_insert_with(|| Joined {
            seats: HashMap::new(),
            _ticket: self
                .state
                .tickets
                .activate(self.ticket, Arc::clone(&self.activity)),
        });
        joined.seats.insert(room, seat);
        let room = encode_hex(&room);
        let peers = others.iter().map(|id| encode_hex(id)).collect();
        let ticket = encode_hex(&self.ticket);
        let joined = ServerFrame::Joined {
            room: &room,
            peers,
            ticket: &ticket,
        };
        // Sent before the queue is read again, so `joined` comes before the room's frames.
        self.send(joined.to_message()).await
    }

    /// Leaves `room`, or every room without it. The frames still queued from a room are
    /// discarded.
    async fn on_leave(&mut self, room: Option<RoomId>) -> Flow {
        let Some(joined) = &mut self.joined else {
            return self.fail(ErrorCode::NotJoined).await;
        };
        if let Some(room) = room {
            if joined.seats.remove(&room).is_none() {
                return self.fail(ErrorCode::NotJoined).await;
            }
            if !joined.seats.is_empty() {
                return Flow::Continue;
            }
        }
        self.joined = None;
        Flow::Continue
    }

    async fn on_pub(&mut self, room: Option<RoomId>, target: Target, data: String) -> Flow {
        let Some(joined) = &self.joined else {
            return self.fail(ErrorCode::NotJoined).await;
        };
        let room = match joined.pub_room(room) {
            Ok(room) => room,
            Err(code) => return self.fail(code).await,
        };
        let message = ServerFrame::Msg {
            room: &encode_hex(&room),
            from: &self.peer_hex,
            data: &data,
        }
        .to_message();
        self.deliver(room, target, message).await
    }

    async fn on_binary(&mut self, data: Bytes) -> Flow {
        match data.first() {
            Some(&FORWARD_TAG) if data.len() >= FORWARD_HEADER_LEN => {}
            Some(&FORWARD_TAG) | None => return self.fail(ErrorCode::BadFrame).await,
            Some(_) => return self.fail(ErrorCode::UnknownType).await,
        }
        let mut room: RoomId = [0; ROOM_ID_LEN];
        room.copy_from_slice(&data[1..FORWARD_PEER_OFFSET]);
        // A frame for a room the connection isn't in, such as one it just left, goes
        // nowhere: forwarding never crosses rooms.
        if !self
            .joined
            .as_ref()
            .is_some_and(|joined| joined.seats.contains_key(&room))
        {
            return Flow::Continue;
        }
        let mut to: PeerId = [0; PEER_ID_LEN];
        to.copy_from_slice(&data[FORWARD_PEER_OFFSET..FORWARD_HEADER_LEN]);
        // The frame goes on with the sender in place of the recipient. This copies it
        // unless it was reassembled from fragments: tungstenite hands a single-frame
        // message over as a slice of its read buffer, which it still holds.
        let mut forward = BytesMut::from(data);
        forward[FORWARD_PEER_OFFSET..FORWARD_HEADER_LEN].copy_from_slice(&self.peer);
        let message = Message::Binary(forward.freeze());
        self.deliver(room, Target::Peer(to), message).await
    }

    /// Sends a frame from one of the connection's rooms, unless it was queued for a seat
    /// the connection has left since.
    async fn on_queued(&mut self, queued: Queued) -> Flow {
        if self
            .joined
            .as_ref()
            .is_some_and(|joined| joined.holds(&queued))
        {
            self.send(queued.message).await
        } else {
            Flow::Continue
        }
    }

    /// Delivers a frame the client just sent to `target` in `room`, waiting up to
    /// [`EGRESS_MAX_WAIT`] for the relay's egress budget.
    async fn deliver(&mut self, room: RoomId, target: Target, message: Message) -> Flow {
        let now = Instant::now();
        let delivery = Delivery {
            room,
            target,
            message,
            retry_at: now,
            give_up_at: now + EGRESS_MAX_WAIT,
        };
        self.try_deliver(delivery, true).await
    }

    /// Goes on with the frame the connection stopped reading for.
    async fn resume(&mut self) -> Flow {
        if let Some(delivery) = self.waiting.take() {
            return self.try_deliver(delivery, false).await;
        }
        match self.held.take() {
            Some(held) => self.admit(held.message, false).await,
            None => Flow::Continue,
        }
    }

    /// Queues `delivery` for its receivers, or sets it waiting while the egress budget is
    /// spent. Past its deadline the frame is dropped, and the sender hears about it as it
    /// would about its own rate limit.
    async fn try_deliver(&mut self, mut delivery: Delivery, first_attempt: bool) -> Flow {
        let Some(joined) = &self.joined else {
            return Flow::Continue;
        };
        let Some(seat) = joined.seats.get(&delivery.room) else {
            return Flow::Continue;
        };
        let Err(retry_after) = seat.deliver(self.key, delivery.target, &delivery.message) else {
            return Flow::Continue;
        };
        if first_attempt {
            self.state.metrics.egress_waits.inc();
        }
        let now = Instant::now();
        let left = delivery.give_up_at.saturating_duration_since(now);
        if left.is_zero() {
            return self.fail(ErrorCode::RateLimited).await;
        }
        delivery.retry_at = now + retry_after.min(left);
        self.waiting = Some(delivery);
        Flow::Continue
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::room::Rooms;
    use crate::ticket::Tickets;

    #[test]
    fn frames_queued_before_a_leave_are_discarded() {
        let now = Instant::now();
        let rooms = Arc::new(Rooms::new(4, 4, 16, 1 << 20, now));
        let tickets = Arc::new(Tickets::new(now));
        let (outbox, mut inbox) = Outbox::new(Arc::new(Notify::new()));
        let (other, _other_inbox) = Outbox::new(Arc::new(Notify::new()));
        let (room, peer) = ([1; ROOM_ID_LEN], [1; PEER_ID_LEN]);
        let key: IpAddr = "203.0.113.1".parse().unwrap();
        let (seat, _) = rooms.join(room, peer, key, &outbox).unwrap();
        let mut joined = Joined {
            seats: HashMap::from([(room, seat)]),
            _ticket: tickets.activate(rand::random(), Activity::default()),
        };
        // The presence of another peer is queued for the seat.
        let other_key = "203.0.113.2".parse().unwrap();
        let (second, _) = rooms
            .join(room, [2; PEER_ID_LEN], other_key, &other)
            .unwrap();
        let old = inbox.try_recv().unwrap();
        assert!(joined.holds(&old));

        joined.seats.remove(&room);
        assert!(!joined.holds(&old));
        let (seat, _) = rooms.join(room, peer, key, &outbox).unwrap();
        joined.seats.insert(room, seat);
        assert!(!joined.holds(&old));
        let sender = "203.0.113.2".parse().unwrap();
        let frame = Message::Binary(Bytes::from_static(b"new"));
        second.deliver(sender, Target::Everyone, &frame).unwrap();
        assert!(joined.holds(&inbox.try_recv().unwrap()));
    }
}
