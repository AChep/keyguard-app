//! Process-wide registry mapping opaque `u64` handles to archive writers and
//! readers.
//!
//! Handles rather than raw pointers cross the FFI boundary so a Kotlin-side
//! lifecycle bug is a structured error instead of undefined behavior. Ids start
//! at one, increase monotonically, and are never reused; writers and readers
//! share one counter, and a handle of the wrong kind reads as unknown.
//!
//! The lock is held for the whole call, file I/O included. Archive work is
//! one export or one import at a time, so the serialization costs nothing and
//! the ABI needs no "busy" outcome.

use std::{
    collections::HashMap,
    sync::{LazyLock, Mutex, MutexGuard, PoisonError},
};

use crate::{
    error::{BridgeError, pack_bridge_error},
    reader::ArchiveReader,
    writer::ArchiveWriter,
};

/// One live archive. The writer is boxed because its deflate state is two
/// orders of magnitude larger than a reader.
enum Entry {
    Writer(Box<ArchiveWriter>),
    Reader(ArchiveReader),
}

struct Registry {
    next_handle: u64,
    entries: HashMap<u64, Entry>,
}

static REGISTRY: LazyLock<Mutex<Registry>> = LazyLock::new(|| {
    Mutex::new(Registry {
        next_handle: 1,
        entries: HashMap::new(),
    })
});

/// Locks the registry. A poisoned lock is recovered: the map stays consistent
/// after a contained panic, and refusing every later call would break the
/// bridge for good.
fn lock() -> MutexGuard<'static, Registry> {
    REGISTRY.lock().unwrap_or_else(PoisonError::into_inner)
}

/// Registers `entry` and returns its positive handle.
///
/// # Panics
///
/// Panics if the handle space is exhausted rather than reuse an id.
fn insert(entry: Entry) -> u64 {
    let mut registry = lock();
    let handle = registry.next_handle;
    registry.next_handle = handle
        .checked_add(1)
        .expect("the archive handle space must not be exhausted");
    registry.entries.insert(handle, entry);
    handle
}

fn invalid_handle() -> i64 {
    pack_bridge_error(BridgeError::InvalidHandle)
}

/// Registers `writer` and returns its positive handle.
pub fn insert_writer(writer: ArchiveWriter) -> u64 {
    insert(Entry::Writer(Box::new(writer)))
}

/// Registers `reader` and returns its positive handle.
pub fn insert_reader(reader: ArchiveReader) -> u64 {
    insert(Entry::Reader(reader))
}

/// Runs `operation` with exclusive access to the writer behind `handle`.
///
/// # Errors
///
/// Returns a packed [`BridgeError::InvalidHandle`] for an unknown, consumed, or
/// reader handle, otherwise `operation`'s own result.
pub fn with_writer_mut<R>(
    handle: u64,
    operation: impl FnOnce(&mut ArchiveWriter) -> Result<R, i64>,
) -> Result<R, i64> {
    let mut registry = lock();
    match registry.entries.get_mut(&handle) {
        Some(Entry::Writer(writer)) => operation(writer),
        _ => Err(invalid_handle()),
    }
}

/// Runs `operation` with exclusive access to the reader behind `handle`.
///
/// # Errors
///
/// Returns a packed [`BridgeError::InvalidHandle`] for an unknown, consumed, or
/// writer handle, otherwise `operation`'s own result.
pub fn with_reader_mut<R>(
    handle: u64,
    operation: impl FnOnce(&mut ArchiveReader) -> Result<R, i64>,
) -> Result<R, i64> {
    let mut registry = lock();
    match registry.entries.get_mut(&handle) {
        Some(Entry::Reader(reader)) => operation(reader),
        _ => Err(invalid_handle()),
    }
}

/// Removes the writer behind `handle`, consuming it. A reader handle is left
/// in place.
///
/// # Errors
///
/// Returns a packed [`BridgeError::InvalidHandle`] for an unknown, consumed, or
/// reader handle.
pub fn take_writer(handle: u64) -> Result<ArchiveWriter, i64> {
    let mut registry = lock();
    match registry.entries.remove(&handle) {
        Some(Entry::Writer(writer)) => Ok(*writer),
        Some(reader) => {
            registry.entries.insert(handle, reader);
            Err(invalid_handle())
        }
        None => Err(invalid_handle()),
    }
}

/// Removes the reader behind `handle`, consuming it. A writer handle is left
/// in place.
///
/// # Errors
///
/// Returns a packed [`BridgeError::InvalidHandle`] for an unknown, consumed, or
/// writer handle.
pub fn take_reader(handle: u64) -> Result<ArchiveReader, i64> {
    let mut registry = lock();
    match registry.entries.remove(&handle) {
        Some(Entry::Reader(reader)) => Ok(reader),
        Some(writer) => {
            registry.entries.insert(handle, writer);
            Err(invalid_handle())
        }
        None => Err(invalid_handle()),
    }
}
