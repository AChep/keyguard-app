//! Sharded registry mapping opaque `u64` handles to native objects.
//!
//! Handles rather than raw pointers cross the FFI boundary so a Kotlin-side
//! lifecycle bug (double close, use after close) is a clean structured error
//! instead of undefined behavior. Shard locks are held only for the O(1)
//! lookup; all I/O happens under the per-entry lock, so two different handles
//! never contend.

use std::{
    collections::HashMap,
    sync::{
        Arc, Mutex,
        atomic::{AtomicU64, Ordering},
    },
};

const SHARD_COUNT: usize = 16;

/// Bit position of the kind tag inside a handle.
///
/// Chosen so the tag and a 60-bit sequence both fit below the sign bit, keeping
/// every handle inside the positive Java `long` range.
const KIND_SHIFT: u32 = 60;
const SEQUENCE_MASK: u64 = (1_u64 << KIND_SHIFT) - 1;

/// The native object type a handle refers to.
///
/// Encoded into the handle so an integer minted by one registry is rejected by
/// the others. Without it, each registry counts from 1 independently and the
/// handle `1` resolves in all three — so confusing a scratch handle for a
/// transaction handle would abort an unrelated live transaction, deleting its
/// staged file and leaving the destination unpublished, instead of returning the
/// structured unknown-handle error this module exists to guarantee.
#[repr(u8)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RegistryKind {
    /// An atomic-write transaction.
    Transaction = 1,
    /// A retained directory capability.
    Directory = 2,
    /// Private scratch storage.
    Scratch = 3,
}

// The tag must not reach the sign bit.
const _: () = assert!((RegistryKind::Scratch as u64) << KIND_SHIFT <= i64::MAX as u64);

/// Sharded handle registry for one native object type.
pub struct Registry<T> {
    shards: [Mutex<HashMap<u64, Arc<Mutex<T>>>>; SHARD_COUNT],
    next_handle: AtomicU64,
    kind: RegistryKind,
}

/// Outcome of a registry lookup.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RegistryError {
    /// The handle is unknown, already consumed, or malformed.
    UnknownHandle,
    /// The entry is concurrently in use and cannot be consumed.
    Busy,
}

impl<T> Registry<T> {
    /// Creates an empty registry that mints handles tagged with `kind`.
    #[must_use]
    pub fn new(kind: RegistryKind) -> Self {
        Self {
            shards: std::array::from_fn(|_| Mutex::new(HashMap::new())),
            next_handle: AtomicU64::new(1),
            kind,
        }
    }

    /// Registers an object and returns its positive, kind-tagged handle.
    pub fn insert(&self, value: T) -> u64 {
        let entry = Arc::new(Mutex::new(value));
        loop {
            // Positive Java `long` range; a zero sequence is reserved so no
            // handle is ever zero, and the tag occupies the bits above it.
            let sequence = self.next_handle.fetch_add(1, Ordering::Relaxed) & SEQUENCE_MASK;
            if sequence == 0 {
                continue;
            }
            let handle = ((self.kind as u64) << KIND_SHIFT) | sequence;
            let mut shard = self.lock_shard(handle);
            if shard.contains_key(&handle) {
                continue;
            }
            shard.insert(handle, entry);
            return handle;
        }
    }

    /// Whether `handle` was minted by this registry.
    ///
    /// Checked before every lookup so a handle belonging to another object type
    /// is reported as unknown rather than silently resolving to whatever this
    /// registry stored at the same sequence number.
    fn owns(&self, handle: u64) -> bool {
        handle >> KIND_SHIFT == self.kind as u64 && handle & SEQUENCE_MASK != 0
    }

    /// Runs `operation` with exclusive access to the entry behind `handle`.
    ///
    /// # Errors
    ///
    /// Returns [`RegistryError::UnknownHandle`] when no entry exists.
    pub fn with<R>(
        &self,
        handle: u64,
        operation: impl FnOnce(&mut T) -> R,
    ) -> Result<R, RegistryError> {
        if !self.owns(handle) {
            return Err(RegistryError::UnknownHandle);
        }
        let entry = {
            let shard = self.lock_shard(handle);
            shard
                .get(&handle)
                .cloned()
                .ok_or(RegistryError::UnknownHandle)?
        };
        let mut guard = entry
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        Ok(operation(&mut guard))
    }

    /// Consumes `handle` and returns exclusive ownership of its entry.
    ///
    /// The handle is always consumed, including on [`RegistryError::Busy`]. That
    /// matters because the ABI documents consumption unconditionally and the
    /// Kotlin facade acts on it: restoring a busy entry instead left a handle
    /// that nothing could ever reclaim, retaining an open descriptor and a staged
    /// file for the life of the process.
    ///
    /// When another thread still holds the entry, ownership cannot be handed to
    /// this caller, so the last reference to drop destroys the value instead.
    /// That is the correct outcome rather than a lost result: `AtomicWriteTxn`'s
    /// `Drop` removes its staged artifact, and `ScratchFile`'s descriptor is
    /// pathless or delete-on-close, so the object still tidies up after itself.
    ///
    /// # Errors
    ///
    /// Returns [`RegistryError::UnknownHandle`] when no entry exists, or
    /// [`RegistryError::Busy`] when another thread still uses the entry.
    pub fn remove(&self, handle: u64) -> Result<T, RegistryError> {
        if !self.owns(handle) {
            return Err(RegistryError::UnknownHandle);
        }
        let entry = {
            let mut shard = self.lock_shard(handle);
            shard.remove(&handle).ok_or(RegistryError::UnknownHandle)?
        };
        match Arc::try_unwrap(entry) {
            Ok(value) => Ok(value
                .into_inner()
                .unwrap_or_else(std::sync::PoisonError::into_inner)),
            // Dropping this reference leaves the concurrent user holding the
            // last one; the value is destroyed when that use finishes.
            Err(_entry) => Err(RegistryError::Busy),
        }
    }

    fn lock_shard(&self, handle: u64) -> std::sync::MutexGuard<'_, HashMap<u64, Arc<Mutex<T>>>> {
        let index = (handle as usize) % SHARD_COUNT;
        self.shards[index]
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    #[cfg(test)]
    pub(crate) fn clone_entry_for_tests(&self, handle: u64) -> Arc<Mutex<T>> {
        self.lock_shard(handle)
            .get(&handle)
            .cloned()
            .expect("entry must exist")
    }
}

// No `Default`: a registry has no meaningful default kind, and one chosen
// arbitrarily would hand out handles that alias another registry's.

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn insert_with_remove_round_trips() {
        let registry = Registry::new(RegistryKind::Transaction);
        let handle = registry.insert(41_u32);
        assert!(handle > 0);

        registry
            .with(handle, |value| *value += 1)
            .expect("known handle must resolve");
        assert_eq!(registry.remove(handle), Ok(42));
        assert_eq!(registry.remove(handle), Err(RegistryError::UnknownHandle));
        assert_eq!(
            registry.with(handle, |_| ()),
            Err(RegistryError::UnknownHandle)
        );
    }

    /// Each registry counts from 1, so without a kind tag the handle `1` would
    /// resolve in all three and a misused handle could abort an unrelated live
    /// transaction instead of returning the structured unknown-handle error.
    #[test]
    fn handles_are_rejected_by_registries_of_another_kind() {
        let transactions = Registry::new(RegistryKind::Transaction);
        let directories = Registry::new(RegistryKind::Directory);
        let scratches = Registry::new(RegistryKind::Scratch);

        let transaction = transactions.insert(10_u32);
        let directory = directories.insert(20_u32);
        let scratch = scratches.insert(30_u32);

        // The sequence numbers collide; only the tag distinguishes them.
        assert_eq!(transaction & SEQUENCE_MASK, directory & SEQUENCE_MASK);
        assert_eq!(transaction & SEQUENCE_MASK, scratch & SEQUENCE_MASK);
        assert_ne!(transaction, directory);
        assert_ne!(transaction, scratch);

        for foreign in [directory, scratch] {
            assert_eq!(
                transactions.with(foreign, |_| ()),
                Err(RegistryError::UnknownHandle),
                "a foreign-kind handle must not resolve"
            );
            assert_eq!(
                transactions.remove(foreign),
                Err(RegistryError::UnknownHandle),
                "a foreign-kind handle must not be consumable"
            );
        }

        // The owning registries are unaffected.
        assert_eq!(directories.remove(directory), Ok(20));
        assert_eq!(scratches.remove(scratch), Ok(30));
        assert_eq!(transactions.remove(transaction), Ok(10));
    }

    /// Every handle must stay a positive Java `long`, and never zero.
    #[test]
    fn tagged_handles_stay_in_the_positive_java_long_range() {
        for kind in [
            RegistryKind::Transaction,
            RegistryKind::Directory,
            RegistryKind::Scratch,
        ] {
            let registry = Registry::new(kind);
            let handle = registry.insert(0_u8);
            assert!(handle != 0, "{kind:?} handle must not be zero");
            assert!(
                handle <= i64::MAX as u64,
                "{kind:?} handle must fit a positive Java long"
            );
            assert!(i64::try_from(handle).is_ok());
        }
    }

    #[test]
    fn concurrent_entries_do_not_contend_and_stay_isolated() {
        let registry = Arc::new(Registry::new(RegistryKind::Transaction));
        let handles: Vec<u64> = (0..64).map(|value| registry.insert(value)).collect();

        let threads: Vec<_> = handles
            .iter()
            .copied()
            .map(|handle| {
                let registry = Arc::clone(&registry);
                std::thread::spawn(move || {
                    for _ in 0..100 {
                        registry
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
            assert_eq!(registry.remove(handle), Ok(index as i32 + 100));
        }
    }

    #[test]
    /// A busy removal still consumes the handle, and the value is destroyed by
    /// the concurrent user's last reference. Restoring it instead produced a
    /// handle the ABI had already told the caller was gone, so nothing could
    /// reclaim the descriptor or the staged file it held.
    fn busy_removal_still_consumes_the_handle() {
        let registry = Registry::new(RegistryKind::Transaction);
        let handle = registry.insert(7_u8);
        let held = registry.clone_entry_for_tests(handle);

        assert_eq!(registry.remove(handle), Err(RegistryError::Busy));
        assert_eq!(
            registry.with(handle, |_| ()),
            Err(RegistryError::UnknownHandle),
            "a busy removal must not leave the handle resolvable"
        );
        assert_eq!(
            registry.remove(handle),
            Err(RegistryError::UnknownHandle),
            "the handle must not be reclaimable a second time"
        );

        // The concurrent user holds the only remaining reference.
        assert_eq!(Arc::strong_count(&held), 1);
        drop(held);
    }
}
