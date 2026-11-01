//! OLE virtual-file drags on Windows: a `FileGroupDescriptorW` with one item
//! per file, whose `FileContents` streams read from the JVM. Keyguard never
//! writes a file and never offers a path (`CF_HDROP`), so targets that only
//! accept paths are unsupported.
//!
//! This file holds the platform-independent rules, so host tests cover them.

#![cfg_attr(not(windows), allow(dead_code))]

#[cfg(windows)]
mod java;
#[cfg(windows)]
mod ole;

use crate::drag::{BUFFER_SIZE, MAX_FILES};
use std::collections::BTreeMap;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU32, AtomicU8, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::Duration;
use zeroize::Zeroizing;

/// `FILEDESCRIPTORW::cFileName` holds `MAX_PATH` UTF-16 units, terminator included.
pub(crate) const NAME_CAPACITY: usize = 260;
/// The longest name accepted from the JVM, in UTF-8 bytes. Every UTF-16 unit
/// encodes to at most three of them.
pub(crate) const NAME_MAX_UTF8: usize = NAME_CAPACITY * 3;
/// `FILEDESCRIPTORW` is packed and this long.
pub(crate) const DESCRIPTOR_SIZE: usize = 592;
/// `FILEGROUPDESCRIPTORW::cItems` precedes the descriptors.
pub(crate) const GROUP_HEADER_SIZE: usize = 4;
/// `FileGroupDescriptorW` is only requested whole.
pub(crate) const DESCRIPTOR_LINDEX: i32 = -1;
/// How long an offer may go without opening or reading a stream before native
/// severs the remaining COM references. This only bounds references a target
/// leaks: the offer's deadline lives in the JVM and arrives as a revoke.
pub(crate) const IDLE_LIMIT: Duration = Duration::from_secs(5 * 60);
/// How long the JVM caller waits for the OLE thread to take ownership.
pub(crate) const STARTUP_TIMEOUT: Duration = Duration::from_secs(2);

pub(crate) const SEEK_SET: u32 = 0;
pub(crate) const SEEK_CUR: u32 = 1;
pub(crate) const SEEK_END: u32 = 2;

/// Device names Windows reserves, with or without an extension.
const RESERVED_STEMS: [&str; 32] = [
    "CON",
    "PRN",
    "AUX",
    "NUL",
    "CONIN$",
    "CONOUT$",
    "COM0",
    "COM1",
    "COM2",
    "COM3",
    "COM4",
    "COM5",
    "COM6",
    "COM7",
    "COM8",
    "COM9",
    "COM\u{b9}",
    "COM\u{b2}",
    "COM\u{b3}",
    "LPT0",
    "LPT1",
    "LPT2",
    "LPT3",
    "LPT4",
    "LPT5",
    "LPT6",
    "LPT7",
    "LPT8",
    "LPT9",
    "LPT\u{b9}",
    "LPT\u{b2}",
    "LPT\u{b3}",
];

/// Whether Explorer can create `name` as one path component. Mirrors
/// `isValidWindowsVirtualFileName` on the JVM side.
pub(crate) fn is_valid_file_name(name: &str) -> bool {
    let Some(last) = name.chars().last() else {
        return false;
    };
    if last == ' ' || last == '.' || name.encode_utf16().count() >= NAME_CAPACITY {
        return false;
    }
    if name
        .chars()
        .any(|c| c < ' ' || matches!(c, '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*'))
    {
        return false;
    }
    let stem = name.split('.').next().unwrap_or_default();
    let stem = stem.trim_end_matches(' ');
    !RESERVED_STEMS
        .iter()
        .any(|reserved| stem.eq_ignore_ascii_case(reserved))
}

/// Encodes a valid name as a NUL-terminated `cFileName`.
pub(crate) fn file_name_units(name: &str) -> Option<[u16; NAME_CAPACITY]> {
    if !is_valid_file_name(name) {
        return None;
    }
    let mut units = [0_u16; NAME_CAPACITY];
    for (output, unit) in units.iter_mut().zip(name.encode_utf16()) {
        *output = unit;
    }
    Some(units)
}

/// The units of a NUL-terminated `cFileName`, terminator included.
pub(crate) fn terminated_units(units: &[u16; NAME_CAPACITY]) -> &[u16] {
    let length = units
        .iter()
        .position(|unit| *unit == 0)
        .unwrap_or(NAME_CAPACITY - 1);
    &units[..=length]
}

/// One file of an offer, as its descriptor and its streams see it.
pub(crate) struct FileEntry {
    pub(crate) name: [u16; NAME_CAPACITY],
    pub(crate) size: u64,
}

/// Validates the files of one offer: `1..=MAX_FILES` of them, each with a
/// valid name and a non-negative size, and no two names equal ignoring case.
/// Mirrors `isValidWindowsVirtualFileSet` on the JVM side.
pub(crate) fn file_entries<S: AsRef<str>>(names: &[S], sizes: &[i64]) -> Option<Vec<FileEntry>> {
    if names.len() != sizes.len() || !(1..=MAX_FILES).contains(&names.len()) {
        return None;
    }
    // Locale-independent uppercase, as Kotlin's `uppercase()`. It also joins
    // a few names Windows keeps apart, such as "ß" and "SS".
    let folded: Vec<Zeroizing<String>> = names
        .iter()
        .map(|name| Zeroizing::new(name.as_ref().to_uppercase()))
        .collect();
    for (index, name) in folded.iter().enumerate() {
        if folded[..index].contains(name) {
            return None;
        }
    }
    names
        .iter()
        .zip(sizes)
        .map(|(name, size)| {
            Some(FileEntry {
                name: file_name_units(name.as_ref())?,
                size: u64::try_from(*size).ok()?,
            })
        })
        .collect()
}

/// Where descriptor `index` starts in a `FILEGROUPDESCRIPTORW`, which declares
/// one descriptor and is followed in memory by the rest. A group of `count`
/// descriptors is `descriptor_offset(count)` bytes long.
pub(crate) const fn descriptor_offset(index: usize) -> usize {
    GROUP_HEADER_SIZE + index * DESCRIPTOR_SIZE
}

/// The file a `FileContents` request names by `lindex`, if the offer has it.
pub(crate) fn contents_index(lindex: i32, count: usize) -> Option<usize> {
    usize::try_from(lindex).ok().filter(|index| *index < count)
}

/// Splits a 64-bit size into `nFileSizeHigh` and `nFileSizeLow`.
pub(crate) fn split_size(size: u64) -> (u32, u32) {
    ((size >> 32) as u32, size as u32)
}

/// The JVM byte array a stream needs for a payload of `size` bytes.
pub(crate) fn buffer_capacity(size: u64) -> usize {
    usize::try_from(size).map_or(BUFFER_SIZE, |size| size.clamp(1, BUFFER_SIZE))
}

/// The `IStream::Seek` target, or `None` for an unknown origin or a position
/// outside `0..=u64::MAX`. Positions past the end are valid and read as EOF.
pub(crate) fn seek_position(current: u64, size: u64, delta: i64, origin: u32) -> Option<u64> {
    let base = match origin {
        SEEK_SET => 0,
        SEEK_CUR => current,
        SEEK_END => size,
        _ => return None,
    };
    u64::try_from(i128::from(base) + i128::from(delta)).ok()
}

/// What `IDropSource::QueryContinueDrag` answers.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum DragStep {
    Continue,
    Drop,
    Cancel,
}

/// Escape cancels, and the OLE side passes a revoke as one. A release seen by
/// the first query happened while the drag was being prepared, so it cancels
/// too rather than dropping where the pointer happens to be.
pub(crate) fn drag_step(first: bool, escape: bool, left_button: bool) -> DragStep {
    match (escape, left_button) {
        (true, _) => DragStep::Cancel,
        (false, true) => DragStep::Continue,
        (false, false) if first => DragStep::Cancel,
        (false, false) => DragStep::Drop,
    }
}

/// Whether a finished drag loop delivered the offer. Some targets extract
/// asynchronously and report no effect, so a started operation counts.
pub(crate) fn drop_accepted(dropped: bool, copied: bool, in_operation: bool) -> bool {
    dropped && (copied || in_operation)
}

/// What the OLE thread knows about an offer once the drag loop has returned.
#[derive(Clone, Copy, Debug)]
pub(crate) struct OfferState {
    pub(crate) accepted: bool,
    /// The JVM revoked the offer: no new stream may open.
    pub(crate) revoked: bool,
    /// The data object still has a reference outside this thread.
    pub(crate) data_referenced: bool,
    /// Streams that have not been released yet.
    pub(crate) streams: usize,
    /// A target is inside `StartOperation`..`EndOperation`.
    pub(crate) in_operation: bool,
    /// Time since the drop or the last stream open or read.
    pub(crate) idle: Duration,
}

/// What the OLE thread does next.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum Lifetime {
    /// Nothing references the offer: retire it.
    Retire,
    /// Disconnect the remaining external references, then retire.
    Sever,
    /// Keep pumping messages for at most this long, then decide again.
    Wait(Duration),
}

pub(crate) fn lifetime(state: OfferState) -> Lifetime {
    if !state.data_referenced && state.streams == 0 {
        return Lifetime::Retire;
    }
    if state.streams == 0 && (state.revoked || (!state.accepted && !state.in_operation)) {
        // A revoked offer has nothing left to serve, and a rejected drop can
        // only leave leaked data object references.
        return Lifetime::Sever;
    }
    match IDLE_LIMIT.checked_sub(state.idle) {
        Some(left) if !left.is_zero() => Lifetime::Wait(left),
        _ => Lifetime::Sever,
    }
}

const PENDING: u8 = 0;
const STARTED: u8 = 1;
const FAILED: u8 = 2;
const ABANDONED: u8 = 3;

/// Decides, exactly once, whether the OLE thread or the JVM caller owns an
/// offer: the thread either starts it or fails, and the caller may give up
/// waiting. Whoever moves it out of pending first wins.
pub(crate) struct Handshake(AtomicU8);

impl Handshake {
    pub(crate) fn new() -> Self {
        Self(AtomicU8::new(PENDING))
    }

    /// The thread takes ownership. False when the caller already gave up,
    /// in which case the thread must not call back.
    pub(crate) fn start(&self) -> bool {
        self.settle(STARTED).is_ok()
    }

    /// The thread could not prepare the drag.
    pub(crate) fn fail(&self) {
        let _ = self.settle(FAILED);
    }

    /// The caller stops waiting. True when the thread took ownership.
    pub(crate) fn conclude(&self) -> bool {
        match self.settle(ABANDONED) {
            Ok(()) => false,
            Err(state) => state == STARTED,
        }
    }

    fn settle(&self, state: u8) -> Result<(), u8> {
        self.0
            .compare_exchange(PENDING, state, Ordering::AcqRel, Ordering::Acquire)
            .map(|_| ())
    }
}

/// The cross-thread state of one offer.
pub(crate) struct Control {
    revoked: AtomicBool,
    /// The OLE thread's id once its message queue exists, zero before.
    thread: AtomicU32,
}

impl Control {
    pub(crate) fn is_revoked(&self) -> bool {
        self.revoked.load(Ordering::SeqCst)
    }

    /// Publishes the OLE thread's id. Its message queue must already exist.
    ///
    /// Both sides use `SeqCst`: either a revoke reads this id and wakes the
    /// thread, or the thread's next `is_revoked` sees the revoke.
    pub(crate) fn set_thread(&self, id: u32) {
        self.thread.store(id, Ordering::SeqCst);
    }
}

/// Offers the JVM can still revoke, by handle. Handles are positive and
/// never reused.
pub(crate) struct Offers {
    next: AtomicI64,
    live: Mutex<BTreeMap<i64, Arc<Control>>>,
}

impl Offers {
    pub(crate) const fn new() -> Self {
        Self::starting_at(1)
    }

    const fn starting_at(next: i64) -> Self {
        Self {
            next: AtomicI64::new(next),
            live: Mutex::new(BTreeMap::new()),
        }
    }

    /// Registers an offer under a new handle, or `None` once handles run out.
    pub(crate) fn register(&self) -> Option<Registration<'_>> {
        let handle = self
            .next
            .fetch_update(Ordering::SeqCst, Ordering::SeqCst, |next| {
                next.checked_add(1)
            })
            .ok()?;
        let control = Arc::new(Control {
            revoked: AtomicBool::new(false),
            thread: AtomicU32::new(0),
        });
        self.lock().insert(handle, Arc::clone(&control));
        Some(Registration {
            offers: self,
            handle,
            control,
        })
    }

    /// Marks an offer revoked and, the first time, passes its thread to
    /// `wake`. Unknown handles are ignored.
    ///
    /// `wake` runs under the lock. The OLE thread unregisters as its last
    /// step, so a registered thread id never names an exited thread.
    pub(crate) fn revoke(&self, handle: i64, wake: impl FnOnce(u32)) {
        let live = self.lock();
        let Some(control) = live.get(&handle) else {
            return;
        };
        if control.revoked.swap(true, Ordering::SeqCst) {
            return;
        }
        let thread = control.thread.load(Ordering::SeqCst);
        if thread != 0 {
            wake(thread);
        }
    }

    fn lock(&self) -> MutexGuard<'_, BTreeMap<i64, Arc<Control>>> {
        self.live.lock().unwrap_or_else(PoisonError::into_inner)
    }
}

/// Keeps an offer revocable until dropped.
pub(crate) struct Registration<'a> {
    offers: &'a Offers,
    pub(crate) handle: i64,
    pub(crate) control: Arc<Control>,
}

impl Drop for Registration<'_> {
    fn drop(&mut self) {
        self.offers.lock().remove(&self.handle);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_follow_windows_component_rules() {
        for name in [
            "report.pdf",
            "a",
            ".hidden",
            "con.txt.bak ",
            "CONSOLE.txt",
            "COM10",
            "nul-file",
            "\u{65e5}\u{672c}.txt",
            "\u{1f510}.bin",
            "e\u{301}",
        ] {
            let valid = !name.ends_with(' ');
            assert_eq!(is_valid_file_name(name), valid, "{name:?}");
        }
        for name in [
            "",
            ".",
            "..",
            "name.",
            "name ",
            "a/b",
            "a\\b",
            "a:b",
            "a*b",
            "a?b",
            "a\"b",
            "a<b",
            "a>b",
            "a|b",
            "a\u{0}b",
            "a\tb",
            "\u{1f}",
            "CON",
            "con.txt",
            "Nul.tar.gz",
            "aux .txt",
            "COM1",
            "lpt9.log",
            "COM\u{b9}",
            "lpt\u{b3}.txt",
            "CONIN$",
            "conout$.x",
        ] {
            assert!(!is_valid_file_name(name), "{name:?}");
        }
    }

    #[test]
    fn names_fit_the_descriptor_with_a_terminator() {
        let longest = "a".repeat(NAME_CAPACITY - 1);
        let units = file_name_units(&longest).unwrap();
        assert_eq!(units[NAME_CAPACITY - 1], 0);
        assert_eq!(terminated_units(&units).len(), NAME_CAPACITY);
        assert!(file_name_units(&"a".repeat(NAME_CAPACITY)).is_none());
        // A surrogate pair takes two of the units.
        let pairs = "\u{1f510}".repeat(NAME_CAPACITY / 2);
        assert!(file_name_units(&pairs).is_none());
        let pairs = "\u{1f510}".repeat(NAME_CAPACITY / 2 - 1);
        assert!(file_name_units(&pairs).is_some());
        assert!(pairs.len() <= NAME_MAX_UTF8);
        let three_byte = "\u{65e5}".repeat(NAME_CAPACITY - 1);
        assert_eq!(three_byte.len(), NAME_MAX_UTF8 - 3);

        let units = file_name_units("a.b").unwrap();
        assert_eq!(terminated_units(&units), &[0x61, 0x2e, 0x62, 0]);
    }

    #[test]
    fn files_follow_count_size_and_case_rules() {
        let files = file_entries(&["a.txt", "b.txt"], &[0, i64::MAX]).unwrap();
        assert_eq!(files.len(), 2);
        assert_eq!(
            terminated_units(&files[1].name),
            &[0x62, 0x2e, 0x74, 0x78, 0x74, 0]
        );
        assert_eq!(files[1].size, i64::MAX as u64);

        let names: Vec<String> = (0..=MAX_FILES)
            .map(|index| format!("{index}.bin"))
            .collect();
        let sizes = [0; MAX_FILES + 1];
        assert!(file_entries(&names[..MAX_FILES], &sizes[..MAX_FILES]).is_some());
        assert!(file_entries(&names, &sizes).is_none());
        assert!(file_entries::<&str>(&[], &[]).is_none());

        assert!(file_entries(&["a.txt"], &[-1]).is_none());
        assert!(file_entries(&["a.txt", "b.txt"], &[0]).is_none());
        assert!(file_entries(&["a.txt", "CON"], &[0, 0]).is_none());
        for (first, second) in [
            ("report.pdf", "Report.PDF"),
            ("\u{e4}.txt", "\u{c4}.txt"),
            ("\u{3c3}", "\u{3a3}"),
            ("stra\u{df}e", "STRASSE"),
        ] {
            assert!(
                file_entries(&[first, "x", second], &[0, 0, 0]).is_none(),
                "{first:?} {second:?}"
            );
        }
        assert!(file_entries(&["a.txt", "a.txt.txt"], &[0, 0]).is_some());
    }

    #[test]
    fn descriptor_group_grows_by_one_descriptor_per_file() {
        assert_eq!(descriptor_offset(0), GROUP_HEADER_SIZE);
        assert_eq!(descriptor_offset(1), 596);
        assert_eq!(descriptor_offset(2), 596 + DESCRIPTOR_SIZE);
        assert_eq!(
            descriptor_offset(MAX_FILES),
            GROUP_HEADER_SIZE + MAX_FILES * DESCRIPTOR_SIZE
        );
        for index in 0..MAX_FILES {
            assert_eq!(
                descriptor_offset(index) + DESCRIPTOR_SIZE,
                descriptor_offset(index + 1)
            );
            assert!(descriptor_offset(index) + DESCRIPTOR_SIZE <= descriptor_offset(MAX_FILES));
        }
    }

    #[test]
    fn contents_lindex_names_one_offered_file() {
        assert_eq!(contents_index(0, 1), Some(0));
        assert_eq!(contents_index(2, 3), Some(2));
        assert_eq!(contents_index(3, 3), None);
        assert_eq!(contents_index(DESCRIPTOR_LINDEX, 3), None);
        assert_eq!(contents_index(i32::MIN, 3), None);
        assert_eq!(contents_index(i32::MAX, MAX_FILES), None);
    }

    #[test]
    fn sizes_split_into_descriptor_halves() {
        assert_eq!(split_size(0), (0, 0));
        assert_eq!(split_size(u64::from(u32::MAX)), (0, u32::MAX));
        assert_eq!(split_size((1 << 32) + 17), (1, 17));
        assert_eq!(split_size(i64::MAX as u64), (i32::MAX as u32, u32::MAX));
    }

    #[test]
    fn buffers_are_bounded_and_never_empty() {
        assert_eq!(buffer_capacity(0), 1);
        assert_eq!(buffer_capacity(10), 10);
        assert_eq!(buffer_capacity(BUFFER_SIZE as u64), BUFFER_SIZE);
        assert_eq!(buffer_capacity(u64::MAX), BUFFER_SIZE);
    }

    #[test]
    fn seek_handles_backwards_eof_overflow_and_invalid_origins() {
        assert_eq!(seek_position(5, 9, -1, SEEK_CUR), Some(4));
        assert_eq!(seek_position(5, 9, -1, SEEK_END), Some(8));
        assert_eq!(seek_position(5, 9, 20, SEEK_SET), Some(20));
        assert_eq!(seek_position(5, 9, 0, SEEK_END), Some(9));
        assert_eq!(seek_position(5, 9, -1, SEEK_SET), None);
        assert_eq!(seek_position(5, 9, -10, SEEK_END), None);
        assert_eq!(seek_position(u64::MAX, 9, 1, SEEK_CUR), None);
        assert_eq!(seek_position(u64::MAX, 9, 0, SEEK_CUR), Some(u64::MAX));
        assert_eq!(seek_position(5, 9, 0, 3), None);
    }

    #[test]
    fn drag_cancels_on_escape_and_on_a_release_before_the_drag() {
        assert_eq!(drag_step(true, false, true), DragStep::Continue);
        assert_eq!(drag_step(true, false, false), DragStep::Cancel);
        assert_eq!(drag_step(false, false, false), DragStep::Drop);
        assert_eq!(drag_step(false, true, true), DragStep::Cancel);
        assert_eq!(drag_step(false, true, false), DragStep::Cancel);
    }

    #[test]
    fn drops_count_when_copied_or_extracting() {
        assert!(drop_accepted(true, true, false));
        assert!(drop_accepted(true, false, true));
        assert!(!drop_accepted(true, false, false));
        assert!(!drop_accepted(false, true, true));
    }

    fn state() -> OfferState {
        OfferState {
            accepted: true,
            revoked: false,
            data_referenced: true,
            streams: 0,
            in_operation: false,
            idle: Duration::ZERO,
        }
    }

    #[test]
    fn released_offers_retire() {
        let released = OfferState {
            data_referenced: false,
            ..state()
        };
        assert_eq!(lifetime(released), Lifetime::Retire);
        let rejected = OfferState {
            accepted: false,
            ..released
        };
        assert_eq!(lifetime(rejected), Lifetime::Retire);
        // Nobody can end an operation without a reference.
        let orphaned = OfferState {
            in_operation: true,
            ..released
        };
        assert_eq!(lifetime(orphaned), Lifetime::Retire);
    }

    #[test]
    fn rejected_offers_sever_leaked_data_references() {
        let rejected = OfferState {
            accepted: false,
            ..state()
        };
        assert_eq!(lifetime(rejected), Lifetime::Sever);
        let extracting = OfferState {
            in_operation: true,
            ..rejected
        };
        assert_eq!(lifetime(extracting), Lifetime::Wait(IDLE_LIMIT));
        let reading = OfferState {
            streams: 1,
            data_referenced: false,
            ..rejected
        };
        assert_eq!(lifetime(reading), Lifetime::Wait(IDLE_LIMIT));
    }

    #[test]
    fn idle_offers_wait_out_the_limit_then_sever() {
        let busy = OfferState {
            streams: 2,
            idle: Duration::from_secs(60),
            ..state()
        };
        assert_eq!(
            lifetime(busy),
            Lifetime::Wait(IDLE_LIMIT - Duration::from_secs(60))
        );
        let idle = OfferState {
            idle: IDLE_LIMIT,
            ..busy
        };
        assert_eq!(lifetime(idle), Lifetime::Sever);
        let overdue = OfferState {
            idle: IDLE_LIMIT * 2,
            data_referenced: false,
            ..busy
        };
        assert_eq!(lifetime(overdue), Lifetime::Sever);
    }

    #[test]
    fn revoked_offers_sever_once_streams_are_released() {
        let revoked = OfferState {
            revoked: true,
            ..state()
        };
        assert_eq!(lifetime(revoked), Lifetime::Sever);
        let extracting = OfferState {
            in_operation: true,
            ..revoked
        };
        assert_eq!(lifetime(extracting), Lifetime::Sever);
        let reading = OfferState {
            streams: 1,
            idle: Duration::from_secs(60),
            ..revoked
        };
        assert_eq!(
            lifetime(reading),
            Lifetime::Wait(IDLE_LIMIT - Duration::from_secs(60))
        );
        let leaked = OfferState {
            idle: IDLE_LIMIT,
            ..reading
        };
        assert_eq!(lifetime(leaked), Lifetime::Sever);
        let released = OfferState {
            data_referenced: false,
            ..revoked
        };
        assert_eq!(lifetime(released), Lifetime::Retire);
    }

    #[test]
    fn handles_are_positive_and_never_reused() {
        let offers = Offers::new();
        let first = offers.register().unwrap();
        let second = offers.register().unwrap();
        assert_eq!((first.handle, second.handle), (1, 2));
        let handle = first.handle;
        drop(first);
        assert_eq!(offers.register().unwrap().handle, 3);
        assert!(!offers.lock().contains_key(&handle));

        let exhausted = Offers::starting_at(i64::MAX - 1);
        assert_eq!(exhausted.register().unwrap().handle, i64::MAX - 1);
        assert!(exhausted.register().is_none());
    }

    #[test]
    fn revoke_marks_the_offer_and_wakes_its_thread_once() {
        let offers = Offers::new();
        let offer = offers.register().unwrap();
        offer.control.set_thread(42);
        let mut woken = Vec::new();
        offers.revoke(offer.handle, |thread| woken.push(thread));
        offers.revoke(offer.handle, |thread| woken.push(thread));
        assert!(offer.control.is_revoked());
        assert_eq!(woken, [42]);
    }

    #[test]
    fn revoke_before_the_thread_is_known_only_marks_the_offer() {
        let offers = Offers::new();
        let offer = offers.register().unwrap();
        offers.revoke(offer.handle, |_| panic!("no thread to wake"));
        assert!(offer.control.is_revoked());
        offer.control.set_thread(42);
        offers.revoke(offer.handle, |_| panic!("already revoked"));
    }

    #[test]
    fn revoke_ignores_unknown_and_unregistered_handles() {
        let offers = Offers::new();
        offers.revoke(0, |_| panic!("unknown"));
        offers.revoke(-1, |_| panic!("unknown"));
        let offer = offers.register().unwrap();
        offer.control.set_thread(42);
        let (handle, control) = (offer.handle, Arc::clone(&offer.control));
        offers.revoke(handle + 1, |_| panic!("unknown"));
        drop(offer);
        offers.revoke(handle, |_| panic!("unregistered"));
        assert!(!control.is_revoked());
    }

    #[test]
    fn handshake_has_one_owner() {
        let started = Handshake::new();
        assert!(started.start());
        assert!(started.conclude());
        assert!(started.conclude());

        let failed = Handshake::new();
        failed.fail();
        assert!(!failed.start());
        assert!(!failed.conclude());

        let abandoned = Handshake::new();
        assert!(!abandoned.conclude());
        assert!(!abandoned.start());
        abandoned.fail();
        assert!(!abandoned.conclude());
    }
}
