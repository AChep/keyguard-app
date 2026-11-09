//! In-memory rooms: who is connected under which room id.

use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

use axum::extract::ws::Message;
use tokio::sync::Notify;
use tokio::sync::mpsc::{self, error::TrySendError};
use tokio::time::Instant;

use crate::limits::{Counts, EgressBudget, KEYS_PER_NETWORK, network_of};
use crate::lock;
use crate::protocol::{
    PeerId, PresenceState, RoomId, ServerFrame, Target, encode_hex, message_len,
};

/// Frames queued for one connection, from all its rooms, before it counts as a slow
/// consumer.
pub(crate) const QUEUE_CAPACITY: usize = 128;

/// A frame queued for a connection by one of its seats.
pub(crate) struct Queued {
    pub(crate) room: RoomId,
    /// [`Seat::id`] of the seat it was queued for.
    pub(crate) seat: u64,
    pub(crate) message: Message,
}

/// The sending side of a connection's queue, which all its seats share.
#[derive(Clone)]
pub(crate) struct Outbox {
    tx: mpsc::Sender<Queued>,
    /// Signalled when the queue is full; the connection then closes as a slow consumer.
    overflow: Arc<Notify>,
}

impl Outbox {
    /// A queue for one connection. `overflow` is signalled when it is full.
    pub(crate) fn new(overflow: Arc<Notify>) -> (Self, mpsc::Receiver<Queued>) {
        let (tx, rx) = mpsc::channel(QUEUE_CAPACITY);
        let outbox = Self { tx, overflow };
        (outbox, rx)
    }
}

/// A connection's place in one room.
struct Member {
    seat: u64,
    /// The connection's client key, whose rooms count toward `RELAY_MAX_ROOMS_PER_IP`, and
    /// those of its IPv6 /48 toward [`KEYS_PER_NETWORK`] times as many.
    key: IpAddr,
    outbox: Outbox,
}

impl Member {
    fn push(&self, room: &RoomId, message: Message) {
        let queued = Queued {
            room: *room,
            seat: self.seat,
            message,
        };
        if let Err(TrySendError::Full(_)) = self.outbox.tx.try_send(queued) {
            self.outbox.overflow.notify_one();
        }
    }
}

/// A peer's place in a room. Dropping it leaves the room and announces the leave. The
/// connection discards the frames still queued for it.
pub(crate) struct Seat {
    rooms: Arc<Rooms>,
    room: RoomId,
    peer: PeerId,
    id: u64,
}

impl Seat {
    /// Unique on the relay, so frames queued before a leave never pass for frames of a
    /// later join of the same room.
    pub(crate) fn id(&self) -> u64 {
        self.id
    }

    /// Queues `message` for the targets in the room, never for this peer itself. Each
    /// copy counts against the egress budget of `sender`, this peer's client key. When
    /// that is spent, queues nothing and returns how long until it may allow the message.
    pub(crate) fn deliver(
        &self,
        sender: IpAddr,
        target: Target,
        message: &Message,
    ) -> Result<(), Duration> {
        self.rooms
            .deliver(&self.room, &self.peer, sender, target, message)
    }
}

impl Drop for Seat {
    fn drop(&mut self) {
        self.rooms.leave(&self.room, &self.peer);
    }
}

/// Why a join was refused.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum JoinError {
    RoomFull,
    /// The relay has `RELAY_MAX_ROOMS` rooms.
    RelayFull,
    /// The client key is in `RELAY_MAX_ROOMS_PER_IP` rooms.
    AddressRoomLimit,
    /// The IPv6 /48 of the client key is in [`KEYS_PER_NETWORK`] times as many.
    NetworkRoomLimit,
}

/// All rooms on the relay. Empty rooms are removed.
pub(crate) struct Rooms {
    max_rooms: usize,
    max_peers: usize,
    max_rooms_per_key: usize,
    max_rooms_per_network: usize,
    state: Mutex<RoomsState>,
}

struct RoomsState {
    /// A peer id stays taken until its [`Seat`] drops.
    rooms: HashMap<RoomId, HashMap<PeerId, Member>>,
    /// The rooms each client key is in: a room counts once, however many of the key's
    /// connections are in it. Keys in no room are removed.
    key_rooms: Counts,
    /// The same for each IPv6 /48.
    network_rooms: Counts,
    /// Seats in all rooms: a connection in two rooms counts twice.
    seats: usize,
    /// The id of the last seat.
    last_seat: u64,
    /// Spent on delivered messages.
    egress: EgressBudget,
}

impl Rooms {
    pub(crate) fn new(
        max_rooms: usize,
        max_peers: usize,
        max_rooms_per_key: usize,
        egress_bytes_per_sec: u64,
        now: Instant,
    ) -> Self {
        Self {
            max_rooms,
            max_peers,
            max_rooms_per_key,
            max_rooms_per_network: max_rooms_per_key * KEYS_PER_NETWORK as usize,
            state: Mutex::new(RoomsState {
                rooms: HashMap::new(),
                key_rooms: Counts::default(),
                network_rooms: Counts::default(),
                seats: 0,
                last_seat: 0,
                egress: EgressBudget::new(egress_bytes_per_sec, now),
            }),
        }
    }

    fn lock(&self) -> MutexGuard<'_, RoomsState> {
        lock(&self.state)
    }

    /// Rooms and seats.
    pub(crate) fn counts(&self) -> (usize, usize) {
        let state = self.lock();
        (state.rooms.len(), state.seats)
    }

    /// Seats `peer`, a connection from the client key `key`, in `room`, announces it to
    /// the others and returns their ids. Frames for the seat go to `outbox`.
    pub(crate) fn join(
        self: &Arc<Self>,
        room: RoomId,
        peer: PeerId,
        key: IpAddr,
        outbox: &Outbox,
    ) -> Result<(Seat, Vec<PeerId>), JoinError> {
        let mut state = self.lock();
        let RoomsState {
            rooms,
            key_rooms,
            network_rooms,
            seats,
            last_seat,
            ..
        } = &mut *state;
        let room_count = rooms.len();
        let existing = rooms.get(&room);
        if existing.is_none() && room_count >= self.max_rooms {
            return Err(JoinError::RelayFull);
        }
        let new_for_key = existing.is_none_or(|members| !has_seat_of(members, key));
        if new_for_key && key_rooms.get(key) >= self.max_rooms_per_key {
            return Err(JoinError::AddressRoomLimit);
        }
        let new_for_network = network_of(key)
            .filter(|network| existing.is_none_or(|members| !has_seat_in(members, *network)));
        if new_for_network
            .is_some_and(|network| network_rooms.get(network) >= self.max_rooms_per_network)
        {
            return Err(JoinError::NetworkRoomLimit);
        }
        // Relay-assigned peer ids are 128 random bits and never collide. If one did, the
        // join must not replace the other member.
        if existing
            .is_some_and(|members| members.len() >= self.max_peers || members.contains_key(&peer))
        {
            return Err(JoinError::RoomFull);
        }
        let members = rooms.entry(room).or_default();
        let presence = presence(&room, &peer, PresenceState::Join);
        let others = members
            .iter()
            .map(|(id, other)| {
                other.push(&room, presence.clone());
                *id
            })
            .collect();
        *last_seat += 1;
        let member = Member {
            seat: *last_seat,
            key,
            outbox: outbox.clone(),
        };
        members.insert(peer, member);
        *seats += 1;
        if new_for_key {
            key_rooms.add(key);
        }
        if let Some(network) = new_for_network {
            network_rooms.add(network);
        }
        let seat = Seat {
            rooms: Arc::clone(self),
            room,
            peer,
            id: *last_seat,
        };
        Ok((seat, others))
    }

    /// Removes the member of a dropped [`Seat`] and announces the leave.
    fn leave(&self, room: &RoomId, peer: &PeerId) {
        let mut state = self.lock();
        let RoomsState {
            rooms,
            key_rooms,
            network_rooms,
            seats,
            ..
        } = &mut *state;
        let Some(members) = rooms.get_mut(room) else {
            return;
        };
        if let Some(member) = members.remove(peer) {
            *seats -= 1;
            if !has_seat_of(members, member.key) {
                key_rooms.remove(member.key);
            }
            if let Some(network) = network_of(member.key)
                && !has_seat_in(members, network)
            {
                network_rooms.remove(network);
            }
        }
        if members.is_empty() {
            rooms.remove(room);
            return;
        }
        let presence = presence(room, peer, PresenceState::Leave);
        for other in members.values() {
            other.push(room, presence.clone());
        }
    }

    fn deliver(
        &self,
        room: &RoomId,
        from: &PeerId,
        sender: IpAddr,
        target: Target,
        message: &Message,
    ) -> Result<(), Duration> {
        let mut state = self.lock();
        let RoomsState { rooms, egress, .. } = &mut *state;
        let Some(members) = rooms.get(room) else {
            return Ok(());
        };
        let count = receivers(members, from, target).count();
        if count == 0 {
            return Ok(());
        }
        egress.try_spend(sender, message_len(message) * count, Instant::now())?;
        for member in receivers(members, from, target) {
            member.push(room, message.clone());
        }
        Ok(())
    }
}

/// Whether a connection from the client key `key` is among `members`. A room counts
/// toward the rooms of the key from its first seat until its last.
fn has_seat_of(members: &HashMap<PeerId, Member>, key: IpAddr) -> bool {
    members.values().any(|member| member.key == key)
}

/// Whether a connection from the IPv6 /48 `network` is among `members`, which counts the
/// same way toward the rooms of the network.
fn has_seat_in(members: &HashMap<PeerId, Member>, network: IpAddr) -> bool {
    members
        .values()
        .any(|member| network_of(member.key) == Some(network))
}

/// The members of a room that a message from `from` to `target` reaches: never the
/// sender itself.
fn receivers<'a>(
    members: &'a HashMap<PeerId, Member>,
    from: &'a PeerId,
    target: Target,
) -> impl Iterator<Item = &'a Member> {
    let (peer, everyone) = match target {
        Target::Peer(id) => (members.get(&id).filter(|_| id != *from), None),
        Target::Everyone => (None, Some(members.iter())),
    };
    let everyone = everyone
        .into_iter()
        .flatten()
        .filter(move |(id, _)| *id != from)
        .map(|(_, member)| member);
    peer.into_iter().chain(everyone)
}

fn presence(room: &RoomId, peer: &PeerId, state: PresenceState) -> Message {
    ServerFrame::Presence {
        room: &encode_hex(room),
        peer: &encode_hex(peer),
        state,
    }
    .to_message()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::{PEER_ID_LEN, ROOM_ID_LEN};
    use axum::body::Bytes;

    /// A connection's outbox and its queue.
    fn connection() -> (Outbox, mpsc::Receiver<Queued>) {
        Outbox::new(Arc::new(Notify::new()))
    }

    /// Joins as peer `peer` from the client key `203.0.113.<peer>`.
    fn join(
        rooms: &Arc<Rooms>,
        room: u8,
        peer: u8,
        outbox: &Outbox,
    ) -> Result<(Seat, Vec<PeerId>), JoinError> {
        join_from(rooms, room, peer, peer, outbox)
    }

    /// Joins as peer `peer` from the client key `203.0.113.<key>`.
    fn join_from(
        rooms: &Arc<Rooms>,
        room: u8,
        peer: u8,
        key: u8,
        outbox: &Outbox,
    ) -> Result<(Seat, Vec<PeerId>), JoinError> {
        let key = IpAddr::from([203, 0, 113, key]);
        rooms.join([room; ROOM_ID_LEN], [peer; PEER_ID_LEN], key, outbox)
    }

    #[test]
    fn each_delivered_copy_counts_against_the_egress_budget() {
        let rooms = Arc::new(Rooms::new(1, 4, 16, 100, Instant::now()));
        let connections: Vec<_> = (1..=3).map(|_| connection()).collect();
        let seats: Vec<Seat> = (1..=3)
            .zip(&connections)
            .map(|(n, (outbox, _))| join(&rooms, 1, n, outbox).unwrap().0)
            .collect();
        let frame = Message::Binary(Bytes::from(vec![0; 60]));
        let sender: IpAddr = "203.0.113.1".parse().unwrap();
        let send = |from: usize, target| seats[from].deliver(sender, target, &frame);
        // No receiver, no cost: an unknown peer and the sender itself.
        send(0, Target::Peer([9; PEER_ID_LEN])).unwrap();
        send(0, Target::Peer([1; PEER_ID_LEN])).unwrap();
        send(0, Target::Peer([2; PEER_ID_LEN])).unwrap();
        // Two copies of 60 bytes overdraw the remaining 40.
        send(0, Target::Everyone).unwrap();
        assert!(send(2, Target::Everyone).is_err());
        let received: Vec<usize> = connections.iter().map(|(_, rx)| rx.len()).collect();
        // Presence frames, then the copies: none for 1, two for 2 and one for 3.
        assert_eq!(received, [2, 1 + 2, 1]);
    }

    #[test]
    fn dropping_a_seat_leaves_the_room() {
        let rooms = Arc::new(Rooms::new(1, 4, 16, 100, Instant::now()));
        let (one, rx) = connection();
        let (two, _) = connection();
        let (first, _) = join(&rooms, 1, 1, &one).unwrap();
        let (second, _) = join(&rooms, 1, 2, &two).unwrap();
        // Assigned peer ids never collide, but a collision wouldn't replace the member.
        assert_eq!(join(&rooms, 1, 2, &two).err(), Some(JoinError::RoomFull));

        // The leave is announced, and the peer id is free again.
        drop(second);
        assert_eq!(rx.len(), 2);
        let (second, others) = join(&rooms, 1, 2, &two).unwrap();
        assert_eq!(others, [[1; PEER_ID_LEN]]);

        drop((first, second));
        assert_eq!(rooms.counts(), (0, 0));
    }

    #[test]
    fn one_queue_takes_the_frames_of_every_room() {
        let rooms = Arc::new(Rooms::new(4, 4, 16, 1000, Instant::now()));
        let (one, mut rx) = connection();
        let (two, _) = connection();
        let (three, _) = connection();
        let (first, _) = join(&rooms, 1, 1, &one).unwrap();
        let (second, _) = join(&rooms, 2, 1, &one).unwrap();
        let (_two, _) = join(&rooms, 1, 2, &two).unwrap();
        let (three, _) = join(&rooms, 2, 3, &three).unwrap();
        assert_eq!(rooms.counts(), (2, 4));

        let frame = Message::Binary(Bytes::from_static(b"x"));
        three
            .deliver("203.0.113.3".parse().unwrap(), Target::Everyone, &frame)
            .unwrap();
        let tags: Vec<(RoomId, u64)> = std::iter::from_fn(|| rx.try_recv().ok())
            .map(|queued| (queued.room, queued.seat))
            .collect();
        let (room1, room2) = ([1; ROOM_ID_LEN], [2; ROOM_ID_LEN]);
        // Presence of 2 in room 1, presence of 3 in room 2, then the frame from room 2.
        assert_eq!(
            tags,
            [
                (room1, first.id()),
                (room2, second.id()),
                (room2, second.id())
            ]
        );
        assert_ne!(first.id(), second.id());
    }

    #[test]
    fn a_frame_to_a_peer_reaches_it_only_in_its_room() {
        let rooms = Arc::new(Rooms::new(4, 4, 16, 1000, Instant::now()));
        let (sender, _) = connection();
        let (owner, owner_rx) = connection();
        let (other, other_rx) = connection();
        // The sender is in rooms 1 and 2, and two connections use peer id 2: one in each.
        let (in_one, _) = join(&rooms, 1, 1, &sender).unwrap();
        let (_in_two, _) = join(&rooms, 2, 1, &sender).unwrap();
        let (_owner, _) = join(&rooms, 1, 2, &owner).unwrap();
        let (_other, _) = join(&rooms, 2, 2, &other).unwrap();
        let (owner_len, other_len) = (owner_rx.len(), other_rx.len());

        let frame = Message::Binary(Bytes::from_static(b"x"));
        let sender_ip: IpAddr = "203.0.113.1".parse().unwrap();
        in_one
            .deliver(sender_ip, Target::Peer([2; PEER_ID_LEN]), &frame)
            .unwrap();
        assert_eq!(owner_rx.len(), owner_len + 1);
        assert_eq!(other_rx.len(), other_len);
    }

    #[test]
    fn a_client_key_is_in_a_limited_number_of_rooms() {
        let rooms = Arc::new(Rooms::new(10, 4, 2, 1000, Instant::now()));
        let (one, _) = connection();
        let (two, _) = connection();
        let (other, _) = connection();
        // Two connections from key 1 share room 1, which counts once.
        let (first, _) = join_from(&rooms, 1, 1, 1, &one).unwrap();
        let (shared, _) = join_from(&rooms, 1, 2, 1, &two).unwrap();
        let (_second, _) = join_from(&rooms, 2, 1, 1, &one).unwrap();
        assert_eq!(
            join_from(&rooms, 3, 2, 1, &two).err(),
            Some(JoinError::AddressRoomLimit)
        );
        // Other keys have their own count, and joining room 3 doesn't make it one of key 1.
        let (_third, _) = join_from(&rooms, 3, 3, 3, &other).unwrap();
        assert_eq!(
            join_from(&rooms, 3, 2, 1, &two).err(),
            Some(JoinError::AddressRoomLimit)
        );

        // Room 1 counts until the last connection of key 1 leaves it.
        drop(first);
        assert_eq!(
            join_from(&rooms, 3, 2, 1, &two).err(),
            Some(JoinError::AddressRoomLimit)
        );
        drop(shared);
        let (_fourth, _) = join_from(&rooms, 3, 2, 1, &two).unwrap();
        assert_eq!(lock(&rooms.state).key_rooms.len(), 2);
    }

    #[test]
    fn an_ipv6_network_is_in_a_limited_number_of_rooms() {
        let max = KEYS_PER_NETWORK as u8;
        let rooms = Arc::new(Rooms::new(100, 4, 1, 1000, Instant::now()));
        let (outbox, _) = connection();
        // One room for each /64 of the network, each its own client key.
        let key = |site: u8, subnet: u8| {
            IpAddr::from([0x2001, 0xdb8, site.into(), subnet.into(), 0, 0, 0, 1])
        };
        let join_as = |room: u8, peer: u8, key: IpAddr| {
            rooms.join([room; ROOM_ID_LEN], [peer; PEER_ID_LEN], key, &outbox)
        };
        let seats: Vec<Seat> = (0..max)
            .map(|n| join_as(n, n, key(1, n)).unwrap().0)
            .collect();
        assert_eq!(
            join_as(max, max, key(1, max)).err(),
            Some(JoinError::NetworkRoomLimit)
        );
        // A room the network is in already counts once, and other networks have their own.
        let (shared, _) = join_as(0, max, key(1, max)).unwrap();
        let (_other, _) = join_as(max, 100, key(2, 0)).unwrap();
        assert_eq!(lock(&rooms.state).network_rooms.len(), 2);

        // Room 0 counts until the network's last connection leaves it.
        let mut seats = seats.into_iter();
        drop(seats.next());
        assert_eq!(
            join_as(max + 1, 101, key(1, 101)).err(),
            Some(JoinError::NetworkRoomLimit)
        );
        drop(shared);
        let (_new, _) = join_as(max + 1, 101, key(1, 101)).unwrap();
    }

    #[test]
    fn keys_in_no_room_are_forgotten() {
        let rooms = Arc::new(Rooms::new(10, 4, 2, 1000, Instant::now()));
        let (one, _) = connection();
        let (two, _) = connection();
        let seats = [
            join_from(&rooms, 1, 1, 1, &one).unwrap().0,
            join_from(&rooms, 1, 2, 2, &two).unwrap().0,
            join_from(&rooms, 2, 1, 1, &one).unwrap().0,
        ];
        assert_eq!(lock(&rooms.state).key_rooms.len(), 2);
        drop(seats);
        assert_eq!(lock(&rooms.state).key_rooms.len(), 0);
        assert_eq!(rooms.counts(), (0, 0));
    }
}
