//! Process-wide tables mapping opaque `u64` handles to archive writers and
//! readers.
//!
//! Handles rather than raw pointers cross the FFI boundary so a Kotlin-side
//! lifecycle bug is a structured error instead of undefined behavior. Each
//! kind has its own tagged table: ids are never reused, and a handle of the
//! wrong kind reads as unknown. A call holds only its own archive, so an
//! export and an import run in parallel, and a contained panic retires only
//! the archive it happened in.

use keyguard_ffi::{SessionError, SessionTable, SessionTag};

use crate::{
    error::{BridgeError, pack_bridge_error},
    memory_reader::MemoryReader,
    reader::ArchiveReader,
    writer::ArchiveWriter,
};

/// Boxed because its deflate state is two orders of magnitude larger than a
/// reader.
static WRITERS: SessionTable<Box<ArchiveWriter>> =
    SessionTable::new(SessionTag::new(1), usize::MAX);
static READERS: SessionTable<ArchiveReader> = SessionTable::new(SessionTag::new(2), usize::MAX);
static MEMORY_READERS: SessionTable<MemoryReader> =
    SessionTable::new(SessionTag::new(3), usize::MAX);

fn packed(error: SessionError) -> i64 {
    pack_bridge_error(match error {
        // A busy take consumed the handle too; the caller must not retry it.
        SessionError::Unknown | SessionError::Busy => BridgeError::InvalidHandle,
        // An earlier call on this archive panicked; it was dropped, not reused.
        SessionError::Poisoned => BridgeError::Panic,
        SessionError::Limit | SessionError::Exhausted => BridgeError::Internal,
    })
}

/// Registers `writer` and returns its positive handle.
///
/// # Errors
///
/// Returns a packed [`BridgeError::Internal`] once the handle space is
/// exhausted.
pub fn insert_writer(writer: ArchiveWriter) -> Result<u64, i64> {
    WRITERS.insert(Box::new(writer)).map_err(packed)
}

/// Registers `reader` and returns its positive handle.
///
/// # Errors
///
/// Returns a packed [`BridgeError::Internal`] once the handle space is
/// exhausted.
pub fn insert_reader(reader: ArchiveReader) -> Result<u64, i64> {
    READERS.insert(reader).map_err(packed)
}

/// Runs `operation` with exclusive access to the writer behind `handle`.
///
/// # Errors
///
/// Returns a packed [`BridgeError::InvalidHandle`] for an unknown, consumed, or
/// reader handle, [`BridgeError::Panic`] after a contained panic, otherwise
/// `operation`'s own result.
pub fn with_writer_mut<R>(
    handle: u64,
    operation: impl FnOnce(&mut ArchiveWriter) -> Result<R, i64>,
) -> Result<R, i64> {
    WRITERS
        .with(handle, |writer| operation(writer))
        .map_err(packed)?
}

/// Runs `operation` with exclusive access to the reader behind `handle`.
///
/// # Errors
///
/// Returns a packed [`BridgeError::InvalidHandle`] for an unknown, consumed, or
/// writer handle, [`BridgeError::Panic`] after a contained panic, otherwise
/// `operation`'s own result.
pub fn with_reader_mut<R>(
    handle: u64,
    operation: impl FnOnce(&mut ArchiveReader) -> Result<R, i64>,
) -> Result<R, i64> {
    READERS.with(handle, operation).map_err(packed)?
}

/// Removes the writer behind `handle`, consuming it.
///
/// # Errors
///
/// Returns a packed [`BridgeError::InvalidHandle`] for an unknown, consumed, or
/// reader handle, and [`BridgeError::Panic`] after a contained panic.
pub fn take_writer(handle: u64) -> Result<ArchiveWriter, i64> {
    WRITERS.take(handle).map(|writer| *writer).map_err(packed)
}

/// Removes the reader behind `handle`, consuming it.
///
/// # Errors
///
/// Returns a packed [`BridgeError::InvalidHandle`] for an unknown, consumed, or
/// writer handle, and [`BridgeError::Panic`] after a contained panic.
pub fn take_reader(handle: u64) -> Result<ArchiveReader, i64> {
    READERS.take(handle).map_err(packed)
}

/// Registers an in-memory entry reader.
///
/// # Errors
///
/// Returns a packed [`BridgeError::Internal`] once the handle space is
/// exhausted.
pub fn insert_memory_reader(reader: MemoryReader) -> Result<u64, i64> {
    MEMORY_READERS.insert(reader).map_err(packed)
}

/// Reads one chunk of an in-memory entry.
///
/// # Errors
/// Returns an invalid-handle or archive read failure.
pub fn memory_read(handle: u64, output: &mut [u8]) -> Result<usize, i64> {
    MEMORY_READERS
        .with(handle, |reader| reader.read(output))
        .map_err(packed)?
}

/// Closes an in-memory entry, releasing its bytes.
///
/// # Errors
/// Returns an invalid-handle failure for unknown or other kinds of handle.
pub fn memory_close(handle: u64) -> Result<(), i64> {
    MEMORY_READERS.take(handle).map(drop).map_err(packed)
}
