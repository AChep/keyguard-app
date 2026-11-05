//! The WebSocket endpoint: JSON control frames and binary relay-forward frames.

use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::time::Duration;

use axum::body::Bytes;
use axum::extract::ws::{CloseFrame, Message, Utf8Bytes, WebSocket, WebSocketUpgrade, close_code};
use axum::extract::{ConnectInfo, State};
use axum::http::{HeaderMap, StatusCode, Uri};
use axum::response::Response;
use bytes::BytesMut;
use tokio::sync::Notify;
use tokio::time::{self, Instant, MissedTickBehavior};

use crate::limits::{
    AuthOutcome, ConnectionBudget, ConnectionPermit, ConnectionRefused, client_ip, client_key,
};
use crate::metrics::MessageSize;
use crate::protocol::{
    ClientFrame, ErrorCode, FORWARD_HEADER_LEN, FORWARD_TAG, PEER_ID_LEN, PeerId, RoomId,
    ServerFrame, Target, encode_hex, message_len,
};
use crate::room::{JoinError, Seat};
use crate::ticket::{Activity, LiveTicket, Ticket};
use crate::{AppState, MAX_FRAME_BYTES, auth, http_error};

/// Upper bound of the server's WebSocket ping interval.
const PING_INTERVAL: Duration = Duration::from_secs(25);
/// How long a frame may wait for the relay's egress budget before it is dropped. The
/// sender's socket isn't read meanwhile, so the sender slows down instead of losing frames.
const EGRESS_MAX_WAIT: Duration = Duration::from_secs(5);

/// `GET /v1/ws`.
pub(crate) async fn upgrade(
    ws: WebSocketUpgrade,
    State(state): State<Arc<AppState>>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    uri: Uri,
) -> Response {
    let key = client_key(client_ip(peer, &headers, &state.config.trust_proxy));
    let authorized = match auth::check(&state, key, &headers, uri.query()) {
        AuthOutcome::Allowed => true,
        AuthOutcome::Unauthorized => false,
        AuthOutcome::Locked => return auth::locked(),
    };
    // Held until the socket closes, so metrics and shutdown count pending upgrades too.
    let permit = match state.connections.acquire(Some(key)) {
        Ok(permit) => permit,
        Err(ConnectionRefused::PerAddress) => {
            state.metrics.websocket_refusals[ConnectionRefused::PerAddress as usize].inc();
            return http_error(StatusCode::TOO_MANY_REQUESTS, ErrorCode::RateLimited);
        }
        Err(ConnectionRefused::Total) => {
            state.metrics.websocket_refusals[ConnectionRefused::Total as usize].inc();
            return http_error(StatusCode::SERVICE_UNAVAILABLE, ErrorCode::RateLimited);
        }
    };
    // Messages up to twice the limit are read and answered with `frame_too_big`;
    // anything larger drops the connection inside the WebSocket codec.
    let hard_limit = MAX_FRAME_BYTES * 2;
    ws.max_message_size(hard_limit)
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

/// The connection's place in a room. Dropping it, however the connection ends, leaves the
/// room, discards the frames queued for it and ends the ticket.
struct Membership {
    seat: Seat,
    _ticket: LiveTicket,
    /// The seat's peer id as `msg` frames name the sender.
    peer_hex: String,
}

/// A client frame over the connection's budget, handled once the budget allows it.
struct Held {
    message: Message,
    retry_at: Instant,
}

/// A `msg` or binary frame from this connection on its way to the room.
struct Delivery {
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
    /// The client key, which shares the relay's egress budget.
    key: IpAddr,
    /// Signalled when the seat's queue is full.
    overflow: Arc<Notify>,
    budget: ConnectionBudget,
    /// One ticket per connection, live only while it is in a room.
    ticket: Ticket,
    /// When the client last sent an admitted frame.
    activity: Activity,
    /// A frame that waits for the connection's budget. The socket isn't read meanwhile.
    held: Option<Held>,
    /// A frame that waits for the egress budget. The socket isn't read meanwhile.
    waiting: Option<Delivery>,
    // Dropped before the permit, so a leave is announced before the socket stops counting.
    membership: Option<Membership>,
    _permit: ConnectionPermit,
}

impl Connection {
    fn new(socket: WebSocket, state: Arc<AppState>, key: IpAddr, permit: ConnectionPermit) -> Self {
        Self {
            socket,
            key,
            id: state.next_connection_id.fetch_add(1, Ordering::Relaxed),
            overflow: Arc::new(Notify::new()),
            budget: ConnectionBudget::new(
                state.config.msgs_per_sec,
                state.config.bytes_per_sec,
                Instant::now(),
            ),
            ticket: rand::random(),
            activity: Activity::default(),
            held: None,
            waiting: None,
            membership: None,
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
        let idle = self.state.config.idle_timeout;
        let ping_every = (idle / 2).clamp(Duration::from_millis(100), PING_INTERVAL);
        let mut ping = time::interval_at(Instant::now() + ping_every, ping_every);
        ping.set_missed_tick_behavior(MissedTickBehavior::Delay);
        let mut last_seen = Instant::now();
        // Built once for the whole connection: making a timer or a waiter per frame takes
        // locks shared with every other connection.
        let idle_timer = time::sleep_until(last_seen + idle);
        let overflow = Arc::clone(&self.overflow);
        let overflowed = overflow.notified();
        let mut shutdown = self.state.shutdown.clone();
        let stopping = async {
            _ = shutdown.wait_for(Option::is_some).await;
        };
        tokio::pin!(idle_timer, overflowed, stopping);
        loop {
            let retry_at = self
                .waiting
                .as_ref()
                .map(|delivery| delivery.retry_at)
                .or_else(|| self.held.as_ref().map(|held| held.retry_at));
            let reading = retry_at.is_none();
            let flow = tokio::select! {
                incoming = self.socket.recv(), if reading => match incoming {
                    Some(Ok(message)) => {
                        last_seen = Instant::now();
                        self.on_message(message).await
                    }
                    Some(Err(_)) | None => Flow::Disconnect,
                },
                Some(outgoing) = next_queued(&mut self.membership) => self.send(outgoing).await,
                () = sleep_until_some(retry_at) => {
                    let flow = self.resume().await;
                    // The relay stopped reading, not the client: idle time starts over.
                    last_seen = Instant::now();
                    flow
                },
                // Not while the socket isn't read: the pongs would only pile up.
                _ = ping.tick(), if reading => self.send(Message::Ping(Bytes::new())).await,
                // Set for an earlier `last_seen`: moved on when it fires, not on every frame.
                () = &mut idle_timer, if reading => {
                    let deadline = last_seen + idle;
                    if Instant::now() < deadline {
                        idle_timer.as_mut().reset(deadline);
                        Flow::Continue
                    } else {
                        Flow::Close(close_code::NORMAL, "idle")
                    }
                },
                () = &mut overflowed => Flow::Close(close_code::AGAIN, "slow_consumer"),
                () = &mut stopping => Flow::Close(close_code::AWAY, "shutdown"),
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

    async fn close(&mut self, code: u16, reason: &'static str) {
        match reason {
            "idle" => self.state.metrics.idle_closes.inc(),
            "slow_consumer" => self.state.metrics.slow_consumer_closes.inc(),
            "shutdown" => self.state.metrics.shutdown_closes.inc(),
            _ => {}
        }
        let frame = CloseFrame {
            code,
            reason: Utf8Bytes::from(reason),
        };
        _ = self.send(Message::Close(Some(frame))).await;
    }

    /// Sends an `err` frame and decides whether the connection goes on.
    async fn fail(&mut self, code: ErrorCode) -> Flow {
        self.state.metrics.errors[code as usize].inc();
        match self.send(ServerFrame::Err { code }.to_message()).await {
            Flow::Continue => code
                .close_code()
                .map_or(Flow::Continue, |close| Flow::Close(close, code.as_str())),
            flow => flow,
        }
    }

    async fn on_message(&mut self, message: Message) -> Flow {
        self.state.metrics.received(&message);
        match message {
            // Free: a pong answers the relay's own heartbeat, or is dropped anyway.
            Message::Pong(_) => Flow::Continue,
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
            ClientFrame::Join { room, peer } => self.on_join(room, peer).await,
            ClientFrame::Pub { to, data } => self.on_pub(to, data).await,
            // Dropping the membership also discards the frames queued for the room.
            ClientFrame::Leave => match self.membership.take() {
                Some(_) => Flow::Continue,
                None => self.fail(ErrorCode::NotJoined).await,
            },
            ClientFrame::Ping => self.send(ServerFrame::Pong.to_message()).await,
        }
    }

    async fn on_join(&mut self, room: RoomId, peer: PeerId) -> Flow {
        if self.membership.is_some() {
            return self.fail(ErrorCode::BadFrame).await;
        }
        match self
            .state
            .rooms
            .join(room, peer, Arc::clone(&self.overflow))
        {
            Ok((seat, others)) => {
                let activity = Arc::clone(&self.activity);
                self.membership = Some(Membership {
                    seat,
                    _ticket: self.state.tickets.activate(self.ticket, activity),
                    peer_hex: encode_hex(&peer),
                });
                let peers = others.iter().map(|id| encode_hex(id)).collect();
                let ticket = encode_hex(&self.ticket);
                let joined = ServerFrame::Joined {
                    peers,
                    ticket: &ticket,
                };
                // Sent before the queue is read again, so `joined` comes first.
                self.send(joined.to_message()).await
            }
            Err(JoinError::RoomFull) => self.fail(ErrorCode::RoomFull).await,
            Err(JoinError::TooManyRooms) => {
                self.state.metrics.room_limits.inc();
                self.fail(ErrorCode::RateLimited).await
            }
            Err(JoinError::PeerTaken) => self.fail(ErrorCode::PeerTaken).await,
        }
    }

    async fn on_pub(&mut self, target: Target, data: String) -> Flow {
        let Some(membership) = &self.membership else {
            return self.fail(ErrorCode::NotJoined).await;
        };
        let message = ServerFrame::Msg {
            from: &membership.peer_hex,
            data: &data,
        }
        .to_message();
        self.deliver(target, message).await
    }

    async fn on_binary(&mut self, data: Bytes) -> Flow {
        if data.len() < FORWARD_HEADER_LEN || data[0] != FORWARD_TAG {
            return self.fail(ErrorCode::BadFrame).await;
        }
        let Some(membership) = &self.membership else {
            return self.fail(ErrorCode::NotJoined).await;
        };
        let mut to: PeerId = [0; PEER_ID_LEN];
        to.copy_from_slice(&data[1..FORWARD_HEADER_LEN]);
        // The frame goes on with the sender in place of the recipient. This copies it
        // unless it was reassembled from fragments: tungstenite hands a single-frame
        // message over as a slice of its read buffer, which it still holds.
        let mut forward = BytesMut::from(data);
        forward[1..FORWARD_HEADER_LEN].copy_from_slice(membership.seat.peer());
        self.deliver(Target::Peer(to), Message::Binary(forward.freeze()))
            .await
    }

    /// Delivers a frame the client just sent, waiting up to [`EGRESS_MAX_WAIT`] for the
    /// relay's egress budget.
    async fn deliver(&mut self, target: Target, message: Message) -> Flow {
        let now = Instant::now();
        let delivery = Delivery {
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

    /// Queues `delivery` for the room, or sets it waiting while the egress budget is spent.
    /// Past its deadline the frame is dropped, and the sender hears about it as it would
    /// about its own rate limit.
    async fn try_deliver(&mut self, mut delivery: Delivery, first_attempt: bool) -> Flow {
        let Some(membership) = &self.membership else {
            return Flow::Continue;
        };
        let delivered = membership
            .seat
            .deliver(self.key, delivery.target, &delivery.message);
        let Err(retry_after) = delivered else {
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

/// The next frame queued for the room, or never while not in one.
async fn next_queued(membership: &mut Option<Membership>) -> Option<Message> {
    match membership {
        Some(membership) => membership.seat.recv().await,
        None => std::future::pending().await,
    }
}

/// Sleeps until `at`, or forever without it.
async fn sleep_until_some(at: Option<Instant>) {
    match at {
        Some(at) => time::sleep_until(at).await,
        None => std::future::pending().await,
    }
}
