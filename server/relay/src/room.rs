//! In-memory rooms: who is connected under which room id.

use std::collections::HashMap;
use std::collections::hash_map::Entry;
use std::net::IpAddr;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

use axum::extract::ws::Message;
use tokio::sync::Notify;
use tokio::sync::mpsc::{self, error::TrySendError};
use tokio::time::Instant;

use crate::limits::EgressBudget;
use crate::lock;
use crate::protocol::{
    PeerId, PresenceState, RoomId, ServerFrame, Target, encode_hex, message_len,
};

/// Frames queued for one seat before its connection counts as a slow consumer.
const QUEUE_CAPACITY: usize = 128;

/// The sending side of a seat's queue.
struct Member {
    tx: mpsc::Sender<Message>,
    /// Signalled when the queue is full; the connection then closes as a slow consumer.
    overflow: Arc<Notify>,
}

impl Member {
    fn push(&self, message: Message) {
        if let Err(TrySendError::Full(_)) = self.tx.try_send(message) {
            self.overflow.notify_one();
        }
    }
}

/// A peer's place in a room and the frames queued for it. Dropping it leaves the room,
/// announces the leave and discards whatever is still queued.
pub(crate) struct Seat {
    rooms: Arc<Rooms>,
    room: RoomId,
    peer: PeerId,
    rx: mpsc::Receiver<Message>,
}

impl Seat {
    pub(crate) fn peer(&self) -> &PeerId {
        &self.peer
    }

    /// The next frame for this peer.
    pub(crate) async fn recv(&mut self) -> Option<Message> {
        self.rx.recv().await
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
    TooManyRooms,
    PeerTaken,
}

/// All rooms on the relay. Empty rooms are removed.
pub(crate) struct Rooms {
    max_rooms: usize,
    max_peers: usize,
    state: Mutex<RoomsState>,
}

struct RoomsState {
    /// A peer id stays taken until its [`Seat`] drops.
    rooms: HashMap<RoomId, HashMap<PeerId, Member>>,
    peers: usize,
    /// Spent on delivered messages.
    egress: EgressBudget,
}

impl Rooms {
    pub(crate) fn new(
        max_rooms: usize,
        max_peers: usize,
        egress_bytes_per_sec: u64,
        now: Instant,
    ) -> Self {
        Self {
            max_rooms,
            max_peers,
            state: Mutex::new(RoomsState {
                rooms: HashMap::new(),
                peers: 0,
                egress: EgressBudget::new(egress_bytes_per_sec, now),
            }),
        }
    }

    fn lock(&self) -> MutexGuard<'_, RoomsState> {
        lock(&self.state)
    }

    pub(crate) fn counts(&self) -> (usize, usize) {
        let state = self.lock();
        (state.rooms.len(), state.peers)
    }

    /// Seats `peer` in `room`, announces it to the others and returns their ids.
    /// `overflow` is signalled when the seat's queue is full.
    pub(crate) fn join(
        self: &Arc<Self>,
        room: RoomId,
        peer: PeerId,
        overflow: Arc<Notify>,
    ) -> Result<(Seat, Vec<PeerId>), JoinError> {
        let mut state = self.lock();
        let RoomsState { rooms, peers, .. } = &mut *state;
        let room_count = rooms.len();
        let members = match rooms.entry(room) {
            Entry::Occupied(entry) => entry.into_mut(),
            Entry::Vacant(entry) => {
                if room_count >= self.max_rooms {
                    return Err(JoinError::TooManyRooms);
                }
                entry.insert(HashMap::new())
            }
        };
        if members.contains_key(&peer) {
            return Err(JoinError::PeerTaken);
        }
        if members.len() >= self.max_peers {
            return Err(JoinError::RoomFull);
        }
        let presence = presence(&peer, PresenceState::Join);
        let others = members
            .iter()
            .map(|(id, other)| {
                other.push(presence.clone());
                *id
            })
            .collect();
        let (tx, rx) = mpsc::channel(QUEUE_CAPACITY);
        members.insert(peer, Member { tx, overflow });
        *peers += 1;
        let seat = Seat {
            rooms: Arc::clone(self),
            room,
            peer,
            rx,
        };
        Ok((seat, others))
    }

    /// Removes the member of a dropped [`Seat`] and announces the leave.
    fn leave(&self, room: &RoomId, peer: &PeerId) {
        let mut state = self.lock();
        let RoomsState { rooms, peers, .. } = &mut *state;
        let Some(members) = rooms.get_mut(room) else {
            return;
        };
        if members.remove(peer).is_some() {
            *peers -= 1;
        }
        if members.is_empty() {
            rooms.remove(room);
            return;
        }
        let presence = presence(peer, PresenceState::Leave);
        for other in members.values() {
            other.push(presence.clone());
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
            member.push(message.clone());
        }
        Ok(())
    }
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

fn presence(peer: &PeerId, state: PresenceState) -> Message {
    ServerFrame::Presence {
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

    fn join(rooms: &Arc<Rooms>, peer: u8) -> Result<(Seat, Vec<PeerId>), JoinError> {
        rooms.join(
            [1; ROOM_ID_LEN],
            [peer; PEER_ID_LEN],
            Arc::new(Notify::new()),
        )
    }

    #[test]
    fn each_delivered_copy_counts_against_the_egress_budget() {
        let rooms = Arc::new(Rooms::new(1, 4, 100, Instant::now()));
        let seats: Vec<Seat> = (1..=3).map(|n| join(&rooms, n).unwrap().0).collect();
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
        let received: Vec<usize> = seats.iter().map(|seat| seat.rx.len()).collect();
        // Presence frames, then the copies: none for 1, two for 2 and one for 3.
        assert_eq!(received, [2, 1 + 2, 1]);
    }

    #[test]
    fn dropping_a_seat_leaves_the_room() {
        let rooms = Arc::new(Rooms::new(1, 4, 100, Instant::now()));
        let (first, _) = join(&rooms, 1).unwrap();
        let (second, _) = join(&rooms, 2).unwrap();
        assert_eq!(join(&rooms, 2).err(), Some(JoinError::PeerTaken));

        // The leave is announced, and the peer id is free again.
        drop(second);
        assert_eq!(first.rx.len(), 2);
        let (second, others) = join(&rooms, 2).unwrap();
        assert_eq!(others, [[1; PEER_ID_LEN]]);

        drop((first, second));
        assert_eq!(rooms.counts(), (0, 0));
    }
}
