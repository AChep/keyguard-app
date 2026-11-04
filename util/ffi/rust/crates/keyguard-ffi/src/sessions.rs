//! Tables mapping opaque `u64` handles to native sessions.
//!
//! Handles rather than raw pointers cross the FFI boundary so a caller's
//! lifecycle bug (double close, use after close) is a structured error instead
//! of undefined behavior. Map locks are held only for the lookup; a call runs
//! under its session's own lock, so two sessions never wait on each other.
//!
//! A panic while a session is in use leaves it half-updated. That session is
//! dropped on its next use and reports [`SessionError::Poisoned`] until it is
//! taken; every other session keeps working.

use std::{
    collections::BTreeMap,
    sync::{
        Arc, Mutex, MutexGuard, PoisonError,
        atomic::{AtomicU64, AtomicUsize, Ordering},
    },
};

const SHARD_COUNT: usize = 16;

/// Bit position of the tag inside a handle.
///
/// The tag and a 60-bit sequence both fit below the sign bit, so every handle
/// is a positive Java `long`.
const TAG_SHIFT: u32 = 60;
const SEQUENCE_MASK: u64 = (1_u64 << TAG_SHIFT) - 1;

/// Distinguishes the handles of one table from those of other tables in the
/// same library.
///
/// Every table counts from 1, so without a tag the handle `1` would resolve in
/// all of them, and a handle passed to the wrong function would act on an
/// unrelated live session instead of reporting [`SessionError::Unknown`].
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct SessionTag(u8);

impl SessionTag {
    /// Creates a tag from 1 to 7. Out of range fails const evaluation.
    ///
    /// # Panics
    ///
    /// Panics if `tag` is zero or above seven.
    #[must_use]
    pub const fn new(tag: u8) -> Self {
        assert!(matches!(tag, 1..=7), "a session tag must be 1..=7");
        Self(tag)
    }
}

/// Outcome of a failed table operation.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SessionError {
    /// The handle is unknown, already taken, malformed, or from another table.
    Unknown,
    /// Another thread was using the session when it was taken. The handle is
    /// consumed anyway; that thread drops the session when it finishes.
    Busy,
    /// An earlier call on this session panicked. The session was dropped; the
    /// handle stays reserved until it is taken.
    Poisoned,
    /// The table holds its maximum number of live sessions.
    Limit,
    /// Every sequence was issued; no handle is ever minted again.
    Exhausted,
}

/// `None` once a panic was detected and the session dropped.
type Cell<T> = Arc<Mutex<Option<T>>>;

/// Handle table for one native session type.
///
/// Each bridge owns its tables as its own `static`s: static libraries linked
/// into one Apple binary may share this crate's code, never its state.
pub struct SessionTable<T> {
    shards: [Mutex<BTreeMap<u64, Cell<T>>>; SHARD_COUNT],
    next: AtomicU64,
    live: AtomicUsize,
    tag: SessionTag,
    max_live: usize,
}

impl<T> SessionTable<T> {
    /// Creates an empty table that mints handles tagged with `tag` and holds at
    /// most `max_live` sessions (`usize::MAX` for no limit).
    #[must_use]
    pub const fn new(tag: SessionTag, max_live: usize) -> Self {
        Self {
            shards: [const { Mutex::new(BTreeMap::new()) }; SHARD_COUNT],
            next: AtomicU64::new(1),
            live: AtomicUsize::new(0),
            tag,
            max_live,
        }
    }

    /// Registers a session and returns its positive, tagged handle.
    ///
    /// Sequences run from 1 to 2^60 - 1 and are never reissued, so a stale
    /// handle never resolves to a newer session.
    ///
    /// # Errors
    ///
    /// Returns [`SessionError::Limit`] while the table is full and
    /// [`SessionError::Exhausted`] once every sequence was issued; `value` is
    /// then dropped.
    pub fn insert(&self, value: T) -> Result<u64, SessionError> {
        self.live
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |live| {
                (live < self.max_live).then(|| live + 1)
            })
            .map_err(|_| SessionError::Limit)?;
        let Ok(sequence) = self
            .next
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |next| {
                (next <= SEQUENCE_MASK).then(|| next + 1)
            })
        else {
            self.live.fetch_sub(1, Ordering::AcqRel);
            return Err(SessionError::Exhausted);
        };
        let handle = (u64::from(self.tag.0) << TAG_SHIFT) | sequence;
        self.shard(handle)
            .insert(handle, Arc::new(Mutex::new(Some(value))));
        Ok(handle)
    }

    /// Runs `operation` with exclusive access to the session behind `handle`.
    ///
    /// Calls on one session are serialized; calls on different sessions run in
    /// parallel.
    ///
    /// # Errors
    ///
    /// Returns [`SessionError::Unknown`] when no session exists, and
    /// [`SessionError::Poisoned`] when an earlier call on it panicked.
    pub fn with<R>(
        &self,
        handle: u64,
        operation: impl FnOnce(&mut T) -> R,
    ) -> Result<R, SessionError> {
        let cell = self.cell(handle)?;
        let mut guard = match cell.lock() {
            Ok(guard) => guard,
            Err(poisoned) => {
                // The panicked call may have left the session half-updated.
                // Drop it now, so its keys and files do not wait for the take.
                let mut guard = poisoned.into_inner();
                *guard = None;
                return Err(SessionError::Poisoned);
            }
        };
        let session = guard.as_mut().ok_or(SessionError::Poisoned)?;
        Ok(operation(session))
    }

    /// Consumes `handle` and returns exclusive ownership of its session.
    ///
    /// The handle is consumed on every result except [`SessionError::Unknown`],
    /// so a caller never needs to take it twice.
    ///
    /// # Errors
    ///
    /// Returns [`SessionError::Unknown`] when no session exists,
    /// [`SessionError::Busy`] when another thread still uses it, and
    /// [`SessionError::Poisoned`] when an earlier call on it panicked; the
    /// session is then dropped instead of returned.
    pub fn take(&self, handle: u64) -> Result<T, SessionError> {
        if !self.owns(handle) {
            return Err(SessionError::Unknown);
        }
        let cell = self
            .shard(handle)
            .remove(&handle)
            .ok_or(SessionError::Unknown)?;
        self.live.fetch_sub(1, Ordering::AcqRel);
        match Arc::try_unwrap(cell) {
            Ok(cell) => cell
                .into_inner()
                .ok()
                .flatten()
                .ok_or(SessionError::Poisoned),
            // The concurrent user holds the last reference and drops the
            // session when its call returns.
            Err(_cell) => Err(SessionError::Busy),
        }
    }

    fn cell(&self, handle: u64) -> Result<Cell<T>, SessionError> {
        if !self.owns(handle) {
            return Err(SessionError::Unknown);
        }
        self.shard(handle)
            .get(&handle)
            .cloned()
            .ok_or(SessionError::Unknown)
    }

    /// Whether `handle` was minted by this table.
    fn owns(&self, handle: u64) -> bool {
        handle >> TAG_SHIFT == u64::from(self.tag.0) && handle & SEQUENCE_MASK != 0
    }

    /// Locks the shard of `handle`. A poisoned map is recovered: no caller code
    /// runs while a shard is locked, so the map is never left half-updated.
    fn shard(&self, handle: u64) -> MutexGuard<'_, BTreeMap<u64, Cell<T>>> {
        self.shards[(handle as usize) % SHARD_COUNT]
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        panic::{AssertUnwindSafe, catch_unwind},
        sync::mpsc,
    };

    const A: SessionTag = SessionTag::new(1);
    const B: SessionTag = SessionTag::new(2);
    const C: SessionTag = SessionTag::new(7);

    fn table<T>() -> SessionTable<T> {
        SessionTable::new(A, usize::MAX)
    }

    #[test]
    fn insert_with_take_round_trips() {
        let table = table();
        let handle = table.insert(41_u32).expect("insert must succeed");
        assert!(handle > 0);

        table
            .with(handle, |value| *value += 1)
            .expect("known handle must resolve");
        assert_eq!(table.take(handle), Ok(42));
        assert_eq!(table.take(handle), Err(SessionError::Unknown));
        assert_eq!(table.with(handle, |_| ()), Err(SessionError::Unknown));
    }

    /// Each table counts from 1, so without a tag the handle `1` would resolve
    /// in all of them and a misused handle could act on an unrelated session.
    #[test]
    fn handles_are_rejected_by_tables_of_another_tag() {
        let first = SessionTable::new(A, usize::MAX);
        let second = SessionTable::new(B, usize::MAX);
        let third = SessionTable::new(C, usize::MAX);

        let a = first.insert(10_u32).expect("insert must succeed");
        let b = second.insert(20_u32).expect("insert must succeed");
        let c = third.insert(30_u32).expect("insert must succeed");

        // The sequence numbers collide; only the tag distinguishes them.
        assert_eq!(a & SEQUENCE_MASK, b & SEQUENCE_MASK);
        assert_eq!(a & SEQUENCE_MASK, c & SEQUENCE_MASK);
        assert_ne!(a, b);
        assert_ne!(a, c);

        for foreign in [b, c, 0, u64::MAX, a & SEQUENCE_MASK] {
            assert_eq!(first.with(foreign, |_| ()), Err(SessionError::Unknown));
            assert_eq!(first.take(foreign), Err(SessionError::Unknown));
        }

        assert_eq!(second.take(b), Ok(20));
        assert_eq!(third.take(c), Ok(30));
        assert_eq!(first.take(a), Ok(10));
    }

    /// Every handle must stay a positive Java `long`, and never zero.
    #[test]
    fn tagged_handles_stay_in_the_positive_java_long_range() {
        for tag in [A, B, C] {
            let table = SessionTable::new(tag, usize::MAX);
            table.next.store(SEQUENCE_MASK, Ordering::Relaxed);
            let handle = table.insert(0_u8).expect("insert must succeed");
            assert!(handle != 0, "{tag:?} handle must not be zero");
            assert!(i64::try_from(handle).is_ok(), "{tag:?} must fit a long");
        }
    }

    #[test]
    fn concurrent_sessions_stay_isolated() {
        let table = Arc::new(table());
        let handles: Vec<u64> = (0..64)
            .map(|value| table.insert(value).expect("insert must succeed"))
            .collect();

        let threads: Vec<_> = handles
            .iter()
            .copied()
            .map(|handle| {
                let table = Arc::clone(&table);
                std::thread::spawn(move || {
                    for _ in 0..100 {
                        table
                            .with(handle, |value| *value += 1)
                            .expect("known handle must resolve");
                    }
                })
            })
            .collect();
        for thread in threads {
            thread.join().expect("worker must not panic");
        }

        for (index, handle) in handles.into_iter().enumerate() {
            assert_eq!(table.take(handle), Ok(index as i32 + 100));
        }
    }

    /// A slow call holds only its own session; others proceed meanwhile.
    #[test]
    fn a_running_call_does_not_block_other_sessions() {
        let table = Arc::new(table());
        let slow = table.insert(0_u8).expect("insert must succeed");
        let fast = table.insert(0_u8).expect("insert must succeed");
        let (entered, wait_entered) = mpsc::channel();
        let (release, wait_release) = mpsc::channel::<()>();

        let worker = {
            let table = Arc::clone(&table);
            std::thread::spawn(move || {
                table
                    .with(slow, |_| {
                        entered.send(()).expect("test must be waiting");
                        wait_release.recv().expect("test must release");
                    })
                    .expect("known handle must resolve");
            })
        };
        wait_entered.recv().expect("worker must enter");
        table
            .with(fast, |value| *value = 1)
            .expect("another session must not wait");
        assert_eq!(table.take(fast), Ok(1));
        release.send(()).expect("worker must be waiting");
        worker.join().expect("worker must not panic");
        assert_eq!(table.take(slow), Ok(0));
    }

    /// A busy take still consumes the handle, and the value is dropped by the
    /// concurrent user's last reference. Restoring it instead would leave a
    /// handle the caller was already told is gone, which nothing reclaims.
    #[test]
    fn busy_take_still_consumes_the_handle() {
        let table: SessionTable<u8> = table();
        let handle = table.insert(7).expect("insert must succeed");
        let held = table.cell(handle).expect("known handle must resolve");

        assert_eq!(table.take(handle), Err(SessionError::Busy));
        assert_eq!(table.with(handle, |_| ()), Err(SessionError::Unknown));
        assert_eq!(table.take(handle), Err(SessionError::Unknown));

        // The concurrent user holds the only remaining reference.
        assert_eq!(Arc::strong_count(&held), 1);
    }

    struct Dropped(Arc<AtomicUsize>);

    impl Drop for Dropped {
        fn drop(&mut self) {
            self.0.fetch_add(1, Ordering::Relaxed);
        }
    }

    /// A panic kills only its own session, which is dropped on its next use
    /// rather than reused half-updated.
    #[test]
    fn a_panic_poisons_only_its_own_session() {
        let table = table();
        let drops = Arc::new(AtomicUsize::new(0));
        let broken = table
            .insert(Dropped(Arc::clone(&drops)))
            .expect("insert must succeed");
        let healthy = table
            .insert(Dropped(Arc::new(AtomicUsize::new(0))))
            .expect("insert must succeed");

        let panic = catch_unwind(AssertUnwindSafe(|| {
            table.with(broken, |_| panic!("simulated failure"))
        }));
        assert!(panic.is_err());
        assert_eq!(drops.load(Ordering::Relaxed), 0);

        assert_eq!(
            table.with(broken, |_| ()).err(),
            Some(SessionError::Poisoned)
        );
        assert_eq!(drops.load(Ordering::Relaxed), 1, "dropped on next use");
        assert_eq!(
            table.with(broken, |_| ()).err(),
            Some(SessionError::Poisoned)
        );

        table
            .with(healthy, |_| ())
            .expect("other sessions keep working");
        let another = table
            .insert(Dropped(Arc::new(AtomicUsize::new(0))))
            .expect("the table keeps accepting sessions");

        assert_eq!(table.take(broken).err(), Some(SessionError::Poisoned));
        assert_eq!(table.take(broken).err(), Some(SessionError::Unknown));
        assert!(table.take(healthy).is_ok());
        assert!(table.take(another).is_ok());
    }

    /// Taking a poisoned session that was never used again still drops it.
    #[test]
    fn taking_a_poisoned_session_drops_it() {
        let table = table();
        let drops = Arc::new(AtomicUsize::new(0));
        let handle = table
            .insert(Dropped(Arc::clone(&drops)))
            .expect("insert must succeed");
        let _ = catch_unwind(AssertUnwindSafe(|| {
            table.with(handle, |_| panic!("simulated failure"))
        }));

        assert_eq!(table.take(handle).err(), Some(SessionError::Poisoned));
        assert_eq!(drops.load(Ordering::Relaxed), 1);
        assert_eq!(table.take(handle).err(), Some(SessionError::Unknown));
    }

    #[test]
    fn live_sessions_are_capped_and_taking_frees_capacity() {
        let table = SessionTable::new(A, 2);
        let first = table.insert(1_u8).expect("insert must succeed");
        let second = table.insert(2_u8).expect("insert must succeed");
        assert_eq!(table.insert(3), Err(SessionError::Limit));

        // A busy take frees capacity even though its value lives on.
        let held = table.cell(first).expect("known handle must resolve");
        assert_eq!(table.take(first), Err(SessionError::Busy));
        drop(held);
        let third = table.insert(3).expect("capacity must be freed");
        assert_eq!(table.insert(4), Err(SessionError::Limit));

        assert_eq!(table.take(second), Ok(2));
        assert_eq!(table.take(third), Ok(3));
        assert_eq!(table.live.load(Ordering::Relaxed), 0);
    }

    /// A masked, wrapping sequence would reissue a taken handle after 2^60
    /// inserts, so a stale close would consume an unrelated newer session.
    #[test]
    fn exhausted_sequences_fail_permanently_without_reissuing_handles() {
        let table = table();
        let stale = table.insert(1_u8).expect("insert must succeed");
        assert_eq!(table.take(stale), Ok(1));

        table.next.store(SEQUENCE_MASK, Ordering::Relaxed);
        let last = table.insert(2).expect("the last sequence must be issued");
        assert_eq!(last & SEQUENCE_MASK, SEQUENCE_MASK);
        for _ in 0..3 {
            assert_eq!(table.insert(3), Err(SessionError::Exhausted));
        }
        assert_eq!(table.live.load(Ordering::Relaxed), 1, "no capacity leaked");
        assert_eq!(table.with(stale, |_| ()), Err(SessionError::Unknown));
        assert_eq!(table.take(last), Ok(2));
    }

    #[test]
    fn tables_can_be_plain_statics() {
        static SESSIONS: SessionTable<u8> = SessionTable::new(SessionTag::new(3), 1);
        let handle = SESSIONS.insert(5).expect("insert must succeed");
        assert_eq!(SESSIONS.take(handle), Ok(5));
    }
}
