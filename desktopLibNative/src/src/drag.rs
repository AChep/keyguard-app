//! Rules shared by the drag-out bridges: AppKit file promises on macOS and
//! OLE virtual files on Windows. Nothing here touches the JVM, so host tests
//! cover it on every platform.

#![cfg_attr(not(any(target_os = "macos", windows)), allow(dead_code))]

/// The most files one drag carries. Mirrors `NATIVE_DRAG_MAX_FILES` in
/// `NativeDrag.kt` and `kg_promise_max_files` in `file_promise.m`.
pub(crate) const MAX_FILES: usize = 64;
/// The largest JVM byte array one read goes through.
pub(crate) const BUFFER_SIZE: usize = 64 * 1024;

/// How one positional read ended.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum ReadStatus {
    /// Every requested byte was copied.
    Filled,
    /// The declared end was reached first.
    Short,
    /// The reader failed, misbehaved or was already closed.
    Fault,
}

/// Bytes to request from the reader at `position`, bounded by the caller's
/// remaining request, the JVM buffer and the declared size. Zero means EOF.
pub(crate) fn chunk_length(position: u64, size: u64, wanted: usize, capacity: usize) -> usize {
    let limit = wanted.min(capacity);
    usize::try_from(size.saturating_sub(position)).map_or(limit, |remaining| remaining.min(limit))
}

/// Accepts a reader's count for a request of `requested` bytes before the
/// declared end. Zero there is a truncated payload, not EOF.
pub(crate) fn reader_count(count: i32, requested: usize) -> Option<usize> {
    usize::try_from(count)
        .ok()
        .filter(|count| (1..=requested).contains(count))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chunks_stop_at_the_request_the_buffer_and_the_end() {
        assert_eq!(chunk_length(0, 100, 10, 64), 10);
        assert_eq!(chunk_length(0, 100, 1000, 64), 64);
        assert_eq!(chunk_length(95, 100, 1000, 64), 5);
        assert_eq!(chunk_length(100, 100, 1000, 64), 0);
        assert_eq!(chunk_length(u64::MAX, 100, 1000, 64), 0);
        assert_eq!(
            chunk_length(0, u64::MAX, usize::MAX, BUFFER_SIZE),
            BUFFER_SIZE
        );
    }

    #[test]
    fn reader_counts_must_be_positive_and_within_the_request() {
        assert_eq!(reader_count(1, 8), Some(1));
        assert_eq!(reader_count(8, 8), Some(8));
        assert_eq!(reader_count(0, 8), None);
        assert_eq!(reader_count(-1, 8), None);
        assert_eq!(reader_count(9, 8), None);
    }
}
