//! Tables of live native objects by handle, for bridges that lend `Arc`s.
//!
//! Handles share the [`SessionTable`](crate::SessionTable) layout: a
//! [`SessionTag`] above a sequence, positive as an `i64`, and never 0.
//! Each table counts its sequence from 1, never reuses one and stops issuing
//! at exhaustion instead of wrapping. That tells three cases apart without
//! tombstones: a live handle, a handle that was issued and removed since
//! ([`SharedError::Closed`]), and a value the table never issued
//! ([`SharedError::Invalid`]), such as a handle of another tag.
//!
//! A lookup clones the object's `Arc` and releases the lock before the caller
//! uses it, so removing a handle never waits for a call on it.

use std::{
    collections::BTreeMap,
    sync::{Arc, Mutex, MutexGuard, PoisonError},
};

use crate::sessions::{SEQUENCE_MASK, SessionTag};

/// Why a [`SharedTable`] refused a call.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SharedError {
    /// The table never issued the value: another tag, zero, or a sequence
    /// not issued yet.
    Invalid,
    /// The table issued the handle, and it was removed since.
    Closed,
    /// The table holds its most live handles.
    Full,
    /// The table issued every sequence and issues none again.
    Exhausted,
}

/// Live objects of one tag by handle.
///
/// keyguard-ffi owns no per-library state: each bridge declares its tables as
/// its own `static`s.
pub struct SharedTable<T> {
    tag: SessionTag,
    max_live: usize,
    state: Mutex<State<T>>,
}

struct State<T> {
    /// The next sequence; past [`SEQUENCE_MASK`] once exhausted.
    next: u64,
    live: BTreeMap<u64, Arc<T>>,
}

impl<T> SharedTable<T> {
    /// Returns an empty table of handles tagged `tag`, at most `max_live` of
    /// them live at once.
    #[must_use]
    pub const fn new(tag: SessionTag, max_live: usize) -> Self {
        Self {
            tag,
            max_live,
            state: Mutex::new(State {
                next: 1,
                live: BTreeMap::new(),
            }),
        }
    }

    /// Stores `value` under a fresh handle.
    ///
    /// # Errors
    ///
    /// Returns [`SharedError::Full`] or [`SharedError::Exhausted`]; `value`
    /// is then dropped.
    pub fn insert(&self, value: Arc<T>) -> Result<u64, SharedError> {
        self.insert_with(value, || Ok(()))
    }

    /// Stores `value` under a fresh handle once `admit` accepts it. `admit`
    /// runs under the table's lock, so [`SharedTable::remove_where`] sees the
    /// object either inserted or refused.
    ///
    /// # Errors
    ///
    /// Returns what `admit` returns, then [`SharedError::Full`] or
    /// [`SharedError::Exhausted`]; `value` is then dropped.
    pub fn insert_with<E: From<SharedError>>(
        &self,
        value: Arc<T>,
        admit: impl FnOnce() -> Result<(), E>,
    ) -> Result<u64, E> {
        let mut state = self.lock();
        admit()?;
        if state.live.len() >= self.max_live {
            return Err(SharedError::Full.into());
        }
        let sequence = state.next;
        if sequence > SEQUENCE_MASK {
            return Err(SharedError::Exhausted.into());
        }
        state.next = sequence + 1;
        let handle = self.tag.handle(sequence);
        state.live.insert(handle, value);
        Ok(handle)
    }

    /// Returns the live object.
    ///
    /// # Errors
    ///
    /// Returns [`SharedError::Invalid`] or [`SharedError::Closed`].
    pub fn get(&self, handle: u64) -> Result<Arc<T>, SharedError> {
        let state = self.lock();
        self.check(&state, handle)?;
        state.live.get(&handle).cloned().ok_or(SharedError::Closed)
    }

    /// Removes the object; `None` when the handle was removed before.
    ///
    /// # Errors
    ///
    /// Returns [`SharedError::Invalid`] for a value the table never issued.
    pub fn remove(&self, handle: u64) -> Result<Option<Arc<T>>, SharedError> {
        let mut state = self.lock();
        self.check(&state, handle)?;
        Ok(state.live.remove(&handle))
    }

    /// Removes and returns every object `predicate` selects, under one lock.
    pub fn remove_where(&self, mut predicate: impl FnMut(&T) -> bool) -> Vec<Arc<T>> {
        let mut state = self.lock();
        let handles: Vec<u64> = state
            .live
            .iter()
            .filter(|(_, value)| predicate(value))
            .map(|(handle, _)| *handle)
            .collect();
        handles
            .into_iter()
            .filter_map(|handle| state.live.remove(&handle))
            .collect()
    }

    /// Locks the table. Poisoning is ignored: a panic in a caller's closure
    /// leaves the state untouched.
    fn lock(&self) -> MutexGuard<'_, State<T>> {
        self.state.lock().unwrap_or_else(PoisonError::into_inner)
    }

    /// Accepts the handles this table issued, live or not.
    fn check(&self, state: &State<T>, handle: u64) -> Result<(), SharedError> {
        let issued = self.tag.matches(handle) && handle & SEQUENCE_MASK < state.next;
        if issued {
            Ok(())
        } else {
            Err(SharedError::Invalid)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn handles_are_positive_tagged_and_never_reused() {
        let runtimes = SharedTable::<u8>::new(SessionTag::new(1), 8);
        let peers = SharedTable::<u8>::new(SessionTag::new(7), 8);
        let runtime = runtimes.insert(Arc::new(1)).expect("a runtime is stored");
        let peer = peers.insert(Arc::new(2)).expect("a peer is stored");
        assert!(i64::try_from(runtime).is_ok_and(|value| value > 0));
        assert!(i64::try_from(peer).is_ok_and(|value| value > 0));
        assert_eq!(peers.get(runtime).err(), Some(SharedError::Invalid));
        assert_eq!(runtimes.get(peer).err(), Some(SharedError::Invalid));

        assert_eq!(runtimes.remove(runtime).ok().flatten().as_deref(), Some(&1));
        let next = runtimes.insert(Arc::new(3)).expect("a runtime is stored");
        assert!(next > runtime);
    }

    #[test]
    fn issued_handles_are_closed_once_removed_and_others_are_invalid() {
        let table = SharedTable::<u8>::new(SessionTag::new(2), 8);
        let handle = table.insert(Arc::new(7)).expect("the value is stored");
        assert_eq!(table.get(handle).ok().as_deref(), Some(&7));
        assert!(table.remove(handle).is_ok_and(|entry| entry.is_some()));
        assert!(table.remove(handle).is_ok_and(|entry| entry.is_none()));
        assert_eq!(table.get(handle).err(), Some(SharedError::Closed));

        for invalid in [0, 1, u64::MAX, handle + 1, handle + (1 << 40)] {
            assert_eq!(table.get(invalid).err(), Some(SharedError::Invalid));
            assert_eq!(table.remove(invalid).err(), Some(SharedError::Invalid));
        }
    }

    #[test]
    fn live_handles_are_capped() {
        let table = SharedTable::<u8>::new(SessionTag::new(1), 2);
        let first = table.insert(Arc::new(1)).expect("below the cap");
        table.insert(Arc::new(2)).expect("below the cap");
        assert_eq!(table.insert(Arc::new(3)), Err(SharedError::Full));
        assert!(table.remove(first).is_ok());
        // A released slot is free again, under a new handle.
        assert!(table.insert(Arc::new(4)).is_ok_and(|handle| handle > first));
    }

    #[test]
    fn exhausted_tables_fail_permanently_without_reuse() {
        let table = SharedTable::<u8>::new(SessionTag::new(1), 8);
        let live = table.insert(Arc::new(1)).expect("the value is stored");
        table.lock().next = SEQUENCE_MASK;
        let last = table
            .insert(Arc::new(2))
            .expect("the last sequence is issued");
        assert_eq!(last & SEQUENCE_MASK, SEQUENCE_MASK);
        for _ in 0..3 {
            assert_eq!(table.insert(Arc::new(3)), Err(SharedError::Exhausted));
        }
        assert_eq!(table.lock().next, SEQUENCE_MASK + 1);
        assert_eq!(table.get(live).ok().as_deref(), Some(&1));
        assert_eq!(table.get(last).ok().as_deref(), Some(&2));
    }

    #[test]
    fn admission_and_bulk_removal_share_the_lock() {
        let table = SharedTable::<u8>::new(SessionTag::new(1), 8);
        let refused: Result<u64, SharedError> =
            table.insert_with(Arc::new(1), || Err(SharedError::Closed));
        assert_eq!(refused, Err(SharedError::Closed));
        let odd = table.insert(Arc::new(1)).expect("the value is stored");
        let even = table.insert(Arc::new(2)).expect("the value is stored");
        // A refused insert issues no sequence.
        assert_eq!(odd & SEQUENCE_MASK, 1);

        let removed = table.remove_where(|value| value % 2 == 1);
        assert_eq!(removed.iter().map(|value| **value).collect::<Vec<_>>(), [1]);
        assert_eq!(table.get(odd).err(), Some(SharedError::Closed));
        assert_eq!(table.get(even).ok().as_deref(), Some(&2));
    }

    #[test]
    fn handles_share_the_session_table_layout() {
        let tag = SessionTag::new(3);
        let shared = SharedTable::<u8>::new(tag, 8);
        let sessions = crate::SessionTable::<u8>::new(tag, 8);
        let handle = shared.insert(Arc::new(1)).expect("the value is stored");
        assert_eq!(sessions.insert(1), Ok(handle));
    }
}
