//! Tickets: proof that an ICE request comes from a device that signals on this relay.
//!
//! Every connection draws a random ticket and gets it in `joined`. The ticket is live
//! while the connection is in a room and has sent a frame within [`TICKET_ACTIVE`], and
//! a live ticket raises the TURN allowance of the ICE request that carries it.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

use tokio::time::Instant;

use crate::lock;

/// Length of a ticket in bytes (32 hex characters on the wire).
pub(crate) const TICKET_LEN: usize = 16;

pub(crate) type Ticket = [u8; TICKET_LEN];

/// How long a ticket stays live after its connection's last frame. Keyguard pings
/// every 20 seconds.
const TICKET_ACTIVE: Duration = Duration::from_mins(5);

/// When a connection last sent a frame, in [`Tickets::now_ms`].
pub(crate) type Activity = Arc<AtomicU64>;

/// The tickets of the connections that are in a room.
pub(crate) struct Tickets {
    started: Instant,
    joined: Mutex<HashMap<Ticket, Activity>>,
}

impl Tickets {
    pub(crate) fn new(now: Instant) -> Self {
        Self {
            started: now,
            joined: Mutex::new(HashMap::new()),
        }
    }

    fn lock(&self) -> MutexGuard<'_, HashMap<Ticket, Activity>> {
        lock(&self.joined)
    }

    /// Milliseconds since the relay started.
    pub(crate) fn now_ms(&self, now: Instant) -> u64 {
        let elapsed = now.saturating_duration_since(self.started).as_millis();
        u64::try_from(elapsed).unwrap_or(u64::MAX)
    }

    /// Makes `ticket` live while `activity` stays recent, until the returned guard drops.
    pub(crate) fn activate(self: &Arc<Self>, ticket: Ticket, activity: Activity) -> LiveTicket {
        self.lock().insert(ticket, activity);
        LiveTicket {
            tickets: Arc::clone(self),
            ticket,
        }
    }

    /// Whether `ticket` belongs to a connection in a room that sent a frame recently.
    pub(crate) fn is_live(&self, ticket: &Ticket, now: Instant) -> bool {
        let now = self.now_ms(now);
        self.lock().get(ticket).is_some_and(|activity| {
            let idle = now.saturating_sub(activity.load(Ordering::Relaxed));
            Duration::from_millis(idle) <= TICKET_ACTIVE
        })
    }
}

/// An active ticket; it stops being live when this drops.
pub(crate) struct LiveTicket {
    tickets: Arc<Tickets>,
    ticket: Ticket,
}

impl Drop for LiveTicket {
    fn drop(&mut self) {
        self.tickets.lock().remove(&self.ticket);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tickets_live_while_joined_and_active() {
        let start = Instant::now();
        let tickets = Arc::new(Tickets::new(start));
        let ticket: Ticket = [7; TICKET_LEN];
        let activity = Activity::default();
        assert!(!tickets.is_live(&ticket, start));

        let live = tickets.activate(ticket, Arc::clone(&activity));
        assert!(tickets.is_live(&ticket, start + TICKET_ACTIVE));
        assert!(!tickets.is_live(&[8; TICKET_LEN], start));

        // Quiet for too long, then active again.
        let late = start + TICKET_ACTIVE + Duration::from_secs(1);
        assert!(!tickets.is_live(&ticket, late));
        activity.store(tickets.now_ms(late), Ordering::Relaxed);
        assert!(tickets.is_live(&ticket, late));

        drop(live);
        assert!(!tickets.is_live(&ticket, late));
    }
}
