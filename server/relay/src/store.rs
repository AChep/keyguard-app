//! Relay storage on disk: one file per mailbox item, an index in memory, expiry and
//! trimming under disk pressure.
//!
//! An item is `items/<hex sha256(item id)>`: a [`Header`], then the opaque body. The disk
//! holds no item ids, which are capabilities, and no client addresses. Quota attribution
//! lives in memory only, so items restored after a restart count toward the totals but
//! toward no client key.
//!
//! Locks: `files` is held while an item file is renamed or deleted and while items leave
//! the index, so a commit sees the item it replaces stay or go. It comes before `index`.
//! Async code takes `index` only, and never across I/O. Eviction takes items out of the
//! index first, then deletes their files one lock at a time.

use std::collections::HashMap;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read};
use std::net::IpAddr;
use std::ops::{Add, AddAssign, SubAssign};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use crate::config::MailboxConfig;
use crate::limits::{KEYS_PER_NETWORK, network_of};
use crate::metrics::Counter;
use crate::protocol::{decode_hex, encode_hex};
use crate::{MAILBOX_MAX_BLOB_BYTES, MAILBOX_MAX_STATE_BYTES, lock, unix_now};

/// Length of an item tag in bytes (32 hex characters on the wire).
pub(crate) const TAG_LEN: usize = 16;
pub(crate) type Tag = [u8; TAG_LEN];
/// SHA-256 of an item id: the file name of the item.
pub(crate) type FileId = [u8; 32];

const MAGIC: &[u8; 4] = b"KGMB";
const VERSION: u8 = 1;
/// Magic, version, kind, tag, creation time, expiry time and body length.
pub(crate) const HEADER_LEN: usize = 4 + 1 + 1 + TAG_LEN + 8 + 8 + 8;

/// Trimming goes this far below the limit, so the next write doesn't trim again.
const TRIM_TO_PERCENT: u64 = 90;

/// What an item holds. It sets the size cap and the eviction order: blobs go first.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Kind {
    State = 0,
    Blob = 1,
}

impl Kind {
    pub(crate) const ALL: [Self; 2] = [Self::State, Self::Blob];

    pub(crate) fn parse(text: &str) -> Option<Self> {
        match text {
            "state" => Some(Self::State),
            "blob" => Some(Self::Blob),
            _ => None,
        }
    }

    pub(crate) fn as_str(self) -> &'static str {
        match self {
            Self::State => "state",
            Self::Blob => "blob",
        }
    }

    /// The largest body of an item of this kind.
    pub(crate) fn max_bytes(self) -> u64 {
        match self {
            Self::State => MAILBOX_MAX_STATE_BYTES,
            Self::Blob => MAILBOX_MAX_BLOB_BYTES,
        }
    }

    fn from_byte(byte: u8) -> Option<Self> {
        Self::ALL.into_iter().find(|kind| *kind as u8 == byte)
    }
}

/// What the relay knows of an item, stored in front of its body.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct Header {
    pub(crate) kind: Kind,
    pub(crate) tag: Tag,
    /// Unix seconds.
    pub(crate) created_at: u64,
    /// Unix seconds.
    pub(crate) expires_at: u64,
    /// Length of the body in bytes.
    pub(crate) len: u64,
}

impl Header {
    pub(crate) fn encode(&self) -> [u8; HEADER_LEN] {
        let mut out = [0; HEADER_LEN];
        let mut at = 0;
        let mut put = |bytes: &[u8]| {
            out[at..at + bytes.len()].copy_from_slice(bytes);
            at += bytes.len();
        };
        put(MAGIC);
        put(&[VERSION, self.kind as u8]);
        put(&self.tag);
        put(&self.created_at.to_be_bytes());
        put(&self.expires_at.to_be_bytes());
        put(&self.len.to_be_bytes());
        out
    }

    pub(crate) fn decode(bytes: &[u8; HEADER_LEN]) -> Option<Self> {
        let (magic, rest) = bytes.split_first_chunk::<4>()?;
        let ([version, kind], rest) = rest.split_first_chunk::<2>()?;
        let (tag, rest) = rest.split_first_chunk::<TAG_LEN>()?;
        let (created_at, rest) = rest.split_first_chunk::<8>()?;
        let (expires_at, rest) = rest.split_first_chunk::<8>()?;
        let (len, _) = rest.split_first_chunk::<8>()?;
        if magic != MAGIC || *version != VERSION {
            return None;
        }
        Some(Self {
            kind: Kind::from_byte(*kind)?,
            tag: *tag,
            created_at: u64::from_be_bytes(*created_at),
            expires_at: u64::from_be_bytes(*expires_at),
            len: u64::from_be_bytes(*len),
        })
    }

    pub(crate) fn is_expired(&self, now: u64) -> bool {
        now >= self.expires_at
    }
}

/// Why the relay deleted an item before a client did. Fixed values label metrics.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Eviction {
    Expired = 0,
    /// Over the byte limit, which shrinks with free disk space.
    Pressure = 1,
    /// Over the item limit.
    Items = 2,
}

impl Eviction {
    pub(crate) const LABELS: [&str; 3] = ["expired", "pressure", "items"];
}

/// The effective byte limit: `max` while the disk has room, less once storing up to it
/// would leave under `min_free` bytes free. `used` are the bytes stored now.
pub(crate) fn effective_limit(max: u64, min_free: u64, used: u64, free: u64) -> u64 {
    max.min(used.saturating_add(free).saturating_sub(min_free))
}

/// `quota` scaled down by `limit / max`.
pub(crate) fn scaled(quota: u64, limit: u64, max: u64) -> u64 {
    if limit >= max {
        return quota;
    }
    let scaled = u128::from(quota) * u128::from(limit) / u128::from(max.max(1));
    u64::try_from(scaled).unwrap_or(u64::MAX)
}

/// Bytes and items, stored or held.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub(crate) struct Usage {
    pub(crate) bytes: u64,
    pub(crate) items: usize,
}

impl Usage {
    fn fits(self, grow: Self, max_bytes: u64, max_items: usize) -> bool {
        self.bytes.saturating_add(grow.bytes) <= max_bytes
            && self.items.saturating_add(grow.items) <= max_items
    }

    fn saturating_sub(self, other: Self) -> Self {
        Self {
            bytes: self.bytes.saturating_sub(other.bytes),
            items: self.items.saturating_sub(other.items),
        }
    }
}

impl AddAssign for Usage {
    fn add_assign(&mut self, other: Self) {
        self.bytes += other.bytes;
        self.items += other.items;
    }
}

impl SubAssign for Usage {
    fn sub_assign(&mut self, other: Self) {
        self.bytes -= other.bytes;
        self.items -= other.items;
    }
}

impl Add for Usage {
    type Output = Self;

    fn add(mut self, other: Self) -> Self {
        self += other;
        self
    }
}

/// Use per client key or network. A key that uses nothing is removed.
#[derive(Debug, Default)]
struct UsageMap(HashMap<IpAddr, Usage>);

impl UsageMap {
    fn get(&self, key: IpAddr) -> Usage {
        self.0.get(&key).copied().unwrap_or_default()
    }

    fn add(&mut self, key: IpAddr, usage: Usage) {
        *self.0.entry(key).or_default() += usage;
    }

    fn sub(&mut self, key: IpAddr, usage: Usage) {
        if let Some(entry) = self.0.get_mut(&key) {
            *entry = entry.saturating_sub(usage);
            if *entry == Usage::default() {
                self.0.remove(&key);
            }
        }
    }
}

struct Entry {
    header: Header,
    /// The client key that stored it; `None` for an item restored at startup.
    owner: Option<IpAddr>,
    /// Commit order, which breaks ties between items created in the same second.
    seq: u64,
}

impl Entry {
    fn usage(&self) -> Usage {
        Usage {
            bytes: self.header.len,
            items: 1,
        }
    }
}

struct Index {
    available: bool,
    /// Changes whenever the index is dropped, so reservations made before can't touch
    /// the new one.
    generation: u64,
    entries: HashMap<FileId, Entry>,
    next_seq: u64,
    /// Stored bytes and items per [`Kind`].
    per_kind: [Usage; 2],
    /// How much uploads in flight may add to the totals.
    pending: Usage,
    /// Bytes of the files of uploads in flight, which sit beside the items they replace.
    staging: u64,
    /// Stored and pending use per client key and per IPv6 /48.
    keys: UsageMap,
    networks: UsageMap,
    limit_bytes: u64,
    limit_items: usize,
}

impl Index {
    fn new(limit_bytes: u64, limit_items: usize) -> Self {
        Self {
            available: false,
            generation: 0,
            entries: HashMap::new(),
            next_seq: 0,
            per_kind: [Usage::default(); 2],
            pending: Usage::default(),
            staging: 0,
            keys: UsageMap::default(),
            networks: UsageMap::default(),
            limit_bytes,
            limit_items,
        }
    }

    fn used(&self) -> Usage {
        self.per_kind.into_iter().fold(Usage::default(), Add::add)
    }

    fn account(&mut self, entry: &Entry, add: bool) {
        let kind = entry.header.kind as usize;
        let usage = entry.usage();
        if add {
            self.per_kind[kind] += usage;
        } else {
            self.per_kind[kind] -= usage;
        }
        if let Some(owner) = entry.owner {
            let maps = [Some(owner), network_of(owner)];
            for (map, key) in [&mut self.keys, &mut self.networks].into_iter().zip(maps) {
                match key {
                    Some(key) if add => map.add(key, usage),
                    Some(key) => map.sub(key, usage),
                    None => {}
                }
            }
        }
    }

    fn insert(&mut self, file: FileId, header: Header, owner: Option<IpAddr>) {
        self.remove(&file);
        let entry = Entry {
            header,
            owner,
            seq: self.next_seq,
        };
        self.next_seq += 1;
        self.account(&entry, true);
        self.entries.insert(file, entry);
    }

    fn remove(&mut self, file: &FileId) -> Option<Entry> {
        let entry = self.entries.remove(file)?;
        self.account(&entry, false);
        Some(entry)
    }

    /// Forgets every item and reservation.
    fn clear(&mut self) {
        let next = Self::new(self.limit_bytes, self.limit_items);
        *self = Self {
            generation: self.generation + 1,
            ..next
        };
    }

    /// Removes items until the index is within [`TRIM_TO_PERCENT`] of each limit it is
    /// over: expired items first, then blobs, then state, oldest first. Returns the
    /// removed files and why.
    fn trim(&mut self, now: u64) -> Vec<(FileId, Eviction)> {
        let used = self.used();
        let over_bytes = used.bytes > self.limit_bytes;
        let over_items = used.items > self.limit_items;
        if !over_bytes && !over_items {
            return Vec::new();
        }
        let target_bytes = if over_bytes {
            scaled(self.limit_bytes, TRIM_TO_PERCENT, 100)
        } else {
            u64::MAX
        };
        let target_items = if over_items {
            self.limit_items * TRIM_TO_PERCENT as usize / 100
        } else {
            usize::MAX
        };
        let mut order: Vec<_> = self
            .entries
            .iter()
            .map(|(file, entry)| {
                let header = &entry.header;
                let kind_order = match header.kind {
                    Kind::Blob => 0,
                    Kind::State => 1,
                };
                (
                    !header.is_expired(now),
                    kind_order,
                    header.created_at,
                    entry.seq,
                    *file,
                )
            })
            .collect();
        order.sort_unstable();
        let mut removed = Vec::new();
        for (live, _, _, _, file) in order {
            let used = self.used();
            let bytes_over = used.bytes > target_bytes;
            if live && !bytes_over && used.items <= target_items {
                break;
            }
            let reason = match (live, bytes_over) {
                (false, _) => Eviction::Expired,
                (true, true) => Eviction::Pressure,
                (true, false) => Eviction::Items,
            };
            self.remove(&file);
            removed.push((file, reason));
        }
        removed
    }
}

/// Why a write can't start.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Refusal {
    /// A limit or quota has no room for it.
    Full,
    Unavailable,
}

/// Room held for an upload in flight. Dropping it gives the room back.
pub(crate) struct Reservation {
    store: Arc<Store>,
    generation: u64,
    file: FileId,
    /// The commit order of the item it replaces, which sized it.
    replaces: Option<u64>,
    key: IpAddr,
    /// How much the upload may add to the totals, to the key and to its network.
    total: Usage,
    own: Usage,
    network: Usage,
    /// The length of its file.
    len: u64,
    settled: bool,
}

impl Reservation {
    /// Gives the room back to `index`, once.
    fn settle(&mut self, index: &mut Index) {
        if std::mem::replace(&mut self.settled, true) || index.generation != self.generation {
            return;
        }
        index.pending -= self.total;
        index.staging -= self.len;
        index.keys.sub(self.key, self.own);
        if let Some(network) = network_of(self.key) {
            index.networks.sub(network, self.network);
        }
    }
}

impl Drop for Reservation {
    fn drop(&mut self) {
        if !self.settled {
            let store = Arc::clone(&self.store);
            self.settle(&mut lock(&store.index));
        }
    }
}

/// A temporary file, deleted when dropped unless it became an item.
pub(crate) struct TempFile {
    path: PathBuf,
    armed: bool,
}

impl TempFile {
    /// Deletes the file now. Blocks.
    pub(crate) fn remove(mut self) {
        self.armed = false;
        _ = fs::remove_file(&self.path);
    }
}

impl Drop for TempFile {
    /// Deletes the file on the blocking pool: a disk that hangs must not stall the async
    /// workers, which drop it when an upload is cancelled.
    fn drop(&mut self) {
        if !self.armed {
            return;
        }
        let path = std::mem::take(&mut self.path);
        match tokio::runtime::Handle::try_current() {
            Ok(runtime) => _ = runtime.spawn_blocking(move || fs::remove_file(path)),
            Err(_) => _ = fs::remove_file(path),
        }
    }
}

/// The answer of [`Store::lookup`].
pub(crate) enum Lookup {
    Found(Header),
    Missing,
    /// Expired but not swept yet: missing to clients.
    Expired,
    Unavailable,
}

/// The answer of [`Store::delete`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Deleted {
    Yes,
    Missing,
    TagMismatch,
    Unavailable,
}

/// Why an upload could not become an item.
pub(crate) enum CommitError {
    Refused(Refusal),
    /// A write-once upload found a live item of its kind.
    Exists,
    Io(io::Error),
}

/// Totals for metrics.
pub(crate) struct Stats {
    /// Stored bytes and items per [`Kind`].
    pub(crate) per_kind: [Usage; 2],
    pub(crate) limit_bytes: u64,
    pub(crate) available: bool,
}

pub(crate) struct Store {
    pub(crate) config: MailboxConfig,
    items: PathBuf,
    tmp: PathBuf,
    files: Mutex<()>,
    index: Mutex<Index>,
    pub(crate) evictions: [Counter; 3],
    pub(crate) write_failures: Counter,
}

impl Store {
    /// A store that is unavailable until [`Self::retry`] restores the data directory.
    pub(crate) fn new(config: &MailboxConfig) -> Self {
        Self {
            items: config.dir.join("items"),
            tmp: config.dir.join("tmp"),
            files: Mutex::new(()),
            index: Mutex::new(Index::new(config.max_bytes, config.max_items)),
            evictions: Default::default(),
            write_failures: Counter::default(),
            config: config.clone(),
        }
    }

    /// Unix seconds.
    pub(crate) fn now(&self) -> u64 {
        self.config
            .hooks
            .clock
            .as_ref()
            .map_or_else(unix_now, |clock| clock())
    }

    pub(crate) fn item_path(&self, file: &FileId) -> PathBuf {
        self.items.join(encode_hex(file))
    }

    /// Creates an empty temp file with the guard that deletes it. Both come out of one
    /// blocking job, so a request cancelled meanwhile can't leave the file behind.
    /// Blocks.
    pub(crate) fn create_temp(&self) -> io::Result<(TempFile, File)> {
        let path = self.tmp.join(encode_hex(&rand::random::<[u8; 16]>()));
        let file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&path)?;
        Ok((TempFile { path, armed: true }, file))
    }

    pub(crate) fn stats(&self) -> Stats {
        let index = lock(&self.index);
        Stats {
            per_kind: index.per_kind,
            limit_bytes: index.limit_bytes,
            available: index.available,
        }
    }

    pub(crate) fn is_available(&self) -> bool {
        lock(&self.index).available
    }

    /// Holds room for `len` bytes of `file` from `key`, checked against the relay's
    /// limits, then the network's quotas, then the key's. Replacing an item needs room
    /// only for what it grows by, toward the totals and toward a key or network that
    /// owns the old one. Its file sits beside the old one until it commits: uploads in
    /// flight may go over the byte limit by half the free space kept on the volume.
    pub(crate) fn reserve(
        self: &Arc<Self>,
        key: IpAddr,
        file: FileId,
        len: u64,
    ) -> Result<Reservation, Refusal> {
        let mut index = lock(&self.index);
        if !index.available {
            return Err(Refusal::Unavailable);
        }
        let network = network_of(key);
        let old = index.entries.get(&file);
        let replaces = old.map(|old| old.seq);
        let grow = |replaces: bool| match old {
            Some(old) if replaces => Usage {
                bytes: len.saturating_sub(old.header.len),
                items: 0,
            },
            _ => Usage {
                bytes: len,
                items: 1,
            },
        };
        let old_owner = old.and_then(|old| old.owner);
        let total = grow(true);
        let own = grow(old_owner == Some(key));
        let network_grow = grow(network.is_some() && old_owner.and_then(network_of) == network);
        let limit = index.limit_bytes;
        let used = index.used();
        let held = used + index.pending;
        let on_disk = used.bytes.saturating_add(index.staging).saturating_add(len);
        let config = &self.config;
        // With no bytes left, even an empty item doesn't fit.
        let fits = limit > 0
            && held.fits(total, limit, index.limit_items)
            && on_disk <= limit.saturating_add(config.min_free_bytes / 2)
            && network.is_none_or(|network| {
                let per_network = u64::from(KEYS_PER_NETWORK);
                index.networks.get(network).fits(
                    network_grow,
                    scaled(
                        config.bytes_per_ip.saturating_mul(per_network),
                        limit,
                        config.max_bytes,
                    ),
                    config
                        .items_per_ip
                        .saturating_mul(KEYS_PER_NETWORK as usize),
                )
            })
            && index.keys.get(key).fits(
                own,
                scaled(config.bytes_per_ip, limit, config.max_bytes),
                config.items_per_ip,
            );
        if !fits {
            return Err(Refusal::Full);
        }
        index.pending += total;
        index.staging += len;
        index.keys.add(key, own);
        if let Some(network) = network {
            index.networks.add(network, network_grow);
        }
        Ok(Reservation {
            store: Arc::clone(self),
            generation: index.generation,
            file,
            replaces,
            key,
            total,
            own,
            network: network_grow,
            len,
            settled: false,
        })
    }

    /// Moves an uploaded and synced `temp` file in place of the item, and counts it
    /// toward the key of `reservation`. Blocks.
    pub(crate) fn commit(
        self: &Arc<Self>,
        mut reservation: Reservation,
        mut temp: TempFile,
        header: Header,
        create_only: bool,
    ) -> Result<(), CommitError> {
        let now = self.now();
        let files = lock(&self.files);
        let current = {
            let index = lock(&self.index);
            if !index.available || index.generation != reservation.generation {
                return Err(CommitError::Refused(Refusal::Unavailable));
            }
            let entry = index.entries.get(&reservation.file);
            // Write-once: another upload stored the item first.
            let live =
                |entry: &Entry| entry.header.kind == header.kind && !entry.header.is_expired(now);
            if create_only && entry.is_some_and(live) {
                return Err(CommitError::Exists);
            }
            entry.map(|entry| entry.seq)
        };
        if current != reservation.replaces {
            // The item changed during the upload, and the room it freed may be in use:
            // hold room again, for what the upload adds to the item there now.
            let (key, file) = (reservation.key, reservation.file);
            drop(reservation);
            reservation = self
                .reserve(key, file, header.len)
                .map_err(CommitError::Refused)?;
        }
        let path = self.item_path(&reservation.file);
        fs::rename(&temp.path, &path).map_err(CommitError::Io)?;
        temp.armed = false;
        {
            let mut index = lock(&self.index);
            reservation.settle(&mut index);
            index.insert(reservation.file, header, Some(reservation.key));
        }
        drop(files);
        self.sync_items_dir();
        Ok(())
    }

    /// Makes a rename or deletion durable. Best effort: some platforms can't sync a
    /// directory, and the item is a cache.
    fn sync_items_dir(&self) {
        if let Err(error) = File::open(&self.items).and_then(|dir| dir.sync_all()) {
            tracing::debug!(%error, "could not sync the mailbox item directory");
        }
    }

    pub(crate) fn lookup(&self, file: &FileId, kind: Kind, now: u64) -> Lookup {
        let index = lock(&self.index);
        if !index.available {
            return Lookup::Unavailable;
        }
        match index.entries.get(file) {
            Some(entry) if entry.header.kind != kind => Lookup::Missing,
            Some(entry) if entry.header.is_expired(now) => Lookup::Expired,
            Some(entry) => Lookup::Found(entry.header),
            None => Lookup::Missing,
        }
    }

    /// Deletes `file` if it is a live item of `kind` whose tag is `if_match`, when given.
    /// Blocks.
    pub(crate) fn delete(
        &self,
        file: &FileId,
        kind: Kind,
        if_match: Option<Option<Tag>>,
    ) -> Deleted {
        let now = self.now();
        let _files = lock(&self.files);
        let mut index = lock(&self.index);
        if !index.available {
            return Deleted::Unavailable;
        }
        let answer = match index.entries.get(file) {
            None => return Deleted::Missing,
            Some(entry) if entry.header.kind != kind => return Deleted::Missing,
            Some(entry) if entry.header.is_expired(now) => {
                self.evictions[Eviction::Expired as usize].inc();
                Deleted::Missing
            }
            Some(entry) if if_match.is_some_and(|tag| tag != Some(entry.header.tag)) => {
                return Deleted::TagMismatch;
            }
            Some(_) => Deleted::Yes,
        };
        index.remove(file);
        drop(index);
        self.unlink(file);
        answer
    }

    /// Deletes `file` if it is still in the index and expired. Blocks.
    pub(crate) fn expire(&self, file: &FileId) {
        let now = self.now();
        let _files = lock(&self.files);
        let mut index = lock(&self.index);
        if index
            .entries
            .get(file)
            .is_some_and(|entry| entry.header.is_expired(now))
        {
            index.remove(file);
            drop(index);
            self.evictions[Eviction::Expired as usize].inc();
            self.unlink(file);
        }
    }

    /// Deletes the file of an item that is no longer in the index. The files lock must
    /// be held.
    fn unlink(&self, file: &FileId) {
        match fs::remove_file(self.item_path(file)) {
            Ok(()) => {}
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            // It expires with its header; a restart drops it then.
            Err(error) => tracing::debug!(%error, "could not delete a mailbox item"),
        }
    }

    /// Deletes the files of items taken out of the index, unless a commit has put one
    /// back since. One file per lock, so uploads and deletions don't wait for a batch.
    fn unlink_removed(&self, removed: impl IntoIterator<Item = FileId>) {
        for file in removed {
            let _files = lock(&self.files);
            if !lock(&self.index).entries.contains_key(&file) {
                self.unlink(&file);
            }
        }
    }

    /// Deletes expired items. Blocks.
    pub(crate) fn sweep(&self) {
        let now = self.now();
        let expired: Vec<FileId> = {
            let _files = lock(&self.files);
            let mut index = lock(&self.index);
            let expired: Vec<FileId> = index
                .entries
                .iter()
                .filter(|(_, entry)| entry.header.is_expired(now))
                .map(|(file, _)| *file)
                .collect();
            for file in &expired {
                index.remove(file);
            }
            expired
        };
        self.evictions[Eviction::Expired as usize].add(expired.len() as u64);
        self.unlink_removed(expired);
    }

    /// Recomputes the limits from free disk space and trims down to them. A directory
    /// that can't be probed makes the store unavailable. Blocks.
    pub(crate) fn probe(&self) {
        if !self.is_available() {
            return;
        }
        let free = match self.check_usable().and_then(|()| self.free_bytes()) {
            Ok(free) => free,
            Err(error) => return self.fail(&error),
        };
        let now = self.now();
        let removed = {
            let _files = lock(&self.files);
            self.limit(&mut lock(&self.index), free, now)
        };
        self.evict(removed);
    }

    /// Sets the limits of `index` from `free` disk space and trims it down to them.
    /// Returns the removed items, whose files [`Self::evict`] deletes.
    fn limit(&self, index: &mut Index, free: u64, now: u64) -> Vec<(FileId, Eviction)> {
        let limit = effective_limit(
            self.config.max_bytes,
            self.config.min_free_bytes,
            index.used().bytes,
            free,
        );
        if limit < index.limit_bytes {
            tracing::debug!(limit, "free disk space lowered the mailbox limit");
        }
        index.limit_bytes = limit;
        index.limit_items = if limit == 0 { 0 } else { self.config.max_items };
        index.trim(now)
    }

    fn evict(&self, removed: Vec<(FileId, Eviction)>) {
        if !removed.is_empty() {
            tracing::info!(
                items = removed.len(),
                "trimmed the mailbox under disk pressure"
            );
        }
        for (_, reason) in &removed {
            self.evictions[*reason as usize].inc();
        }
        self.unlink_removed(removed.into_iter().map(|(file, _)| file));
    }

    /// Answers a failed create, write, sync or rename of an upload, whose temp file is
    /// gone: on a full disk, probes at once; if the directory no longer works, drops the
    /// store. Blocks.
    pub(crate) fn on_io_error(&self, error: &io::Error) -> Refusal {
        self.write_failures.inc();
        if is_full(error) {
            self.probe();
            return Refusal::Full;
        }
        if let Err(error) = self.check_usable() {
            self.fail(&error);
        }
        Refusal::Unavailable
    }

    fn free_bytes(&self) -> io::Result<u64> {
        if let Some(free_space) = &self.config.hooks.free_space {
            return free_space();
        }
        let stat = rustix::fs::statvfs(&self.config.dir)?;
        Ok(stat.f_bavail.saturating_mul(stat.f_frsize))
    }

    /// Whether a file can be created in `tmp`, moved into `items` and deleted there, as
    /// uploads and evictions do. A probe left in `items` isn't named like an item, so
    /// the next restore deletes it.
    fn check_usable(&self) -> io::Result<()> {
        let (probe, _) = self.create_temp()?;
        let moved = self.items.join(probe.path.file_name().unwrap_or_default());
        let checked = fs::rename(&probe.path, &moved).and_then(|()| fs::remove_file(&moved));
        probe.remove();
        checked
    }

    /// Drops the index and, best effort, the files: they no longer count toward
    /// anything. Requests get 503 until [`Self::retry`] succeeds.
    pub(crate) fn fail(&self, error: &io::Error) {
        let _files = lock(&self.files);
        let was_available = {
            let mut index = lock(&self.index);
            let was_available = index.available;
            index.clear();
            was_available
        };
        if was_available {
            tracing::warn!(%error, "relay storage stopped working; mailbox requests get 503 until it works again");
        }
        for dir in [&self.items, &self.tmp] {
            if let Ok(entries) = fs::read_dir(dir) {
                for entry in entries.flatten() {
                    _ = fs::remove_file(entry.path());
                }
            }
        }
    }

    /// Restores the store from the directory while it is unavailable. Blocks.
    pub(crate) fn retry(&self) {
        if self.is_available() {
            return;
        }
        let now = self.now();
        let files = lock(&self.files);
        let (found, free) = match self
            .scan(now)
            .and_then(|found| Ok((found, self.free_bytes()?)))
        {
            Ok(restored) => restored,
            Err(error) => {
                tracing::warn!(%error, "relay storage is unusable; mailbox requests get 503 until it works");
                // Its items count toward nothing, and deleting them may make room, such
                // as inodes on a full volume, for the next try.
                drop(files);
                self.fail(&error);
                return;
            }
        };
        // Within its limits before the first request.
        let (removed, used) = {
            let mut index = lock(&self.index);
            index.clear();
            for (file, header) in found {
                index.insert(file, header, None);
            }
            let removed = self.limit(&mut index, free, now);
            index.available = true;
            (removed, index.used())
        };
        drop(files);
        tracing::info!(
            items = used.items,
            bytes = used.bytes,
            "relay storage ready"
        );
        self.evict(removed);
    }

    /// Creates the directories, deletes temp files and reads the headers of the items.
    /// Expired and malformed items are deleted. Returns the rest, oldest first.
    fn scan(&self, now: u64) -> io::Result<Vec<(FileId, Header)>> {
        fs::create_dir_all(&self.items)?;
        fs::create_dir_all(&self.tmp)?;
        for entry in fs::read_dir(&self.tmp)? {
            _ = fs::remove_file(entry?.path());
        }
        self.check_usable()?;
        let mut found = Vec::new();
        for entry in fs::read_dir(&self.items)? {
            let entry = entry?;
            let file = entry.file_name().to_str().and_then(decode_hex::<32>);
            let header = file
                .and_then(|_| open_item(&entry.path()).ok().flatten())
                .map(|(header, _)| header);
            match (file, header) {
                (Some(file), Some(header)) if !header.is_expired(now) => {
                    found.push((file, header));
                }
                _ => _ = fs::remove_file(entry.path()),
            }
        }
        found.sort_by_key(|(_, header)| header.created_at);
        Ok(found)
    }
}

/// The header of the item file at `path` and the file, positioned at the body. `None`
/// for a file that is missing, malformed or not as long as its header says. Blocks.
pub(crate) fn open_item(path: &Path) -> io::Result<Option<(Header, File)>> {
    let mut file = match File::open(path) {
        Ok(file) => file,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error),
    };
    let len = file.metadata()?.len();
    let mut raw = [0; HEADER_LEN];
    match file.read_exact(&mut raw) {
        Ok(()) => {}
        // Shorter than a header: malformed, not a broken disk.
        Err(error) if error.kind() == io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(error) => return Err(error),
    }
    Ok(Header::decode(&raw)
        .filter(|header| len == HEADER_LEN as u64 + header.len)
        .map(|header| (header, file)))
}

/// Whether `error` means the disk or a quota on it is full.
fn is_full(error: &io::Error) -> bool {
    matches!(
        error.kind(),
        io::ErrorKind::StorageFull | io::ErrorKind::QuotaExceeded
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn header(kind: Kind, created_at: u64, len: u64) -> Header {
        Header {
            kind,
            tag: [7; TAG_LEN],
            created_at,
            expires_at: created_at + 600,
            len,
        }
    }

    #[test]
    fn headers_round_trip_and_reject_other_files() {
        let header = Header {
            kind: Kind::Blob,
            tag: *b"0123456789abcdef",
            created_at: 1_767_225_600,
            expires_at: 1_767_830_400,
            len: 17_825_792,
        };
        let raw = header.encode();
        assert_eq!(&raw[..6], b"KGMB\x01\x01");
        assert_eq!(Header::decode(&raw), Some(header));
        let state = Header {
            kind: Kind::State,
            ..header
        };
        assert_eq!(Header::decode(&state.encode()), Some(state));
        for (at, byte) in [(0, b'X'), (4, 2), (5, 2)] {
            let mut bad = raw;
            bad[at] = byte;
            assert_eq!(Header::decode(&bad), None, "byte {at}");
        }
    }

    #[test]
    fn items_expire_at_their_expiry_second() {
        let header = header(Kind::State, 100, 1);
        assert!(!header.is_expired(699));
        assert!(header.is_expired(700));
    }

    #[test]
    fn the_limit_follows_free_space_down_to_nothing() {
        // Plenty of room: the configured maximum.
        assert_eq!(effective_limit(1_000, 100, 200, 10_000), 1_000);
        // 200 stored and 300 free, 100 of which stay free.
        assert_eq!(effective_limit(1_000, 100, 200, 300), 400);
        // Already below the free-space floor: less than what is stored.
        assert_eq!(effective_limit(1_000, 100, 200, 50), 150);
        assert_eq!(effective_limit(1_000, 500, 200, 50), 0);
        assert_eq!(effective_limit(u64::MAX, 0, u64::MAX, u64::MAX), u64::MAX);
    }

    #[test]
    fn quotas_shrink_with_the_limit() {
        assert_eq!(scaled(128, 4_000, 4_000), 128);
        assert_eq!(scaled(128, 1_000, 4_000), 32);
        assert_eq!(scaled(128, 0, 4_000), 0);
        assert_eq!(scaled(u64::MAX, u64::MAX - 1, u64::MAX), u64::MAX - 1);
    }

    fn file(n: u8) -> FileId {
        [n; 32]
    }

    #[test]
    fn trimming_evicts_expired_items_then_blobs_then_state_oldest_first() {
        let mut index = Index::new(u64::MAX, usize::MAX);
        let now = 800;
        // Inserted out of age order: age decides, not insertion.
        index.insert(file(1), header(Kind::State, 500, 100), None);
        index.insert(file(2), header(Kind::Blob, 600, 100), None);
        index.insert(file(3), header(Kind::Blob, 400, 100), None);
        index.insert(file(4), header(Kind::State, 300, 100), None);
        // Expired, though newest.
        let mut expired = header(Kind::State, 900, 100);
        expired.expires_at = now;
        index.insert(file(5), expired, None);
        index.insert(file(6), header(Kind::Blob, 700, 100), None);
        assert!(index.trim(now).is_empty());

        // 600 stored, limit 400: trim to 360.
        index.limit_bytes = 400;
        assert_eq!(
            index.trim(now),
            [
                (file(5), Eviction::Expired),
                (file(3), Eviction::Pressure),
                (file(2), Eviction::Pressure),
            ]
        );
        assert_eq!(
            index.used(),
            Usage {
                bytes: 300,
                items: 3
            }
        );
        assert_eq!(index.per_kind.map(|usage| usage.bytes), [200, 100]);

        // The item cap: 3 items, limit 2, trim to 1.
        index.limit_items = 2;
        assert_eq!(
            index.trim(now),
            [(file(6), Eviction::Items), (file(4), Eviction::Items)]
        );
        index.limit_bytes = 0;
        assert_eq!(index.trim(now), [(file(1), Eviction::Pressure)]);
        assert_eq!(index.used(), Usage::default());
    }

    #[test]
    fn items_created_in_the_same_second_leave_in_commit_order() {
        // 300 stored, limit 210: trim to 189.
        let mut index = Index::new(210, usize::MAX);
        for n in [3, 1, 2] {
            index.insert(file(n), header(Kind::Blob, 100, 100), None);
        }
        assert_eq!(
            index.trim(0),
            [(file(3), Eviction::Pressure), (file(1), Eviction::Pressure)]
        );
    }

    /// Commits a blob of `len` zero bytes.
    fn commit(store: &Arc<Store>, reservation: Reservation, len: u64) -> Result<(), CommitError> {
        commit_with(store, reservation, len, false)
    }

    /// Commits a blob of `len` zero bytes.
    fn commit_with(
        store: &Arc<Store>,
        reservation: Reservation,
        len: u64,
        create_only: bool,
    ) -> Result<(), CommitError> {
        use std::io::Write;

        let header = Header {
            expires_at: store.now() + 600,
            ..header(Kind::Blob, store.now(), len)
        };
        let (temp, mut file) = store.create_temp().unwrap();
        let mut bytes = header.encode().to_vec();
        bytes.resize(HEADER_LEN + len as usize, 0);
        file.write_all(&bytes).unwrap();
        store.commit(reservation, temp, header, create_only)
    }

    #[test]
    fn a_write_once_upload_loses_to_one_that_stored_the_item_first() {
        let dir = tempfile::TempDir::new().unwrap();
        let store = Arc::new(Store::new(&MailboxConfig {
            min_free_bytes: 0,
            ..MailboxConfig::new(dir.path())
        }));
        store.retry();
        let key: IpAddr = "203.0.113.1".parse().unwrap();
        // Both start while the item is missing.
        let first = store.reserve(key, file(1), 10).unwrap();
        let second = store.reserve(key, file(1), 10).unwrap();
        assert!(commit_with(&store, first, 10, true).is_ok());
        assert!(matches!(
            commit_with(&store, second, 10, true),
            Err(CommitError::Exists)
        ));
        assert_eq!(
            lock(&store.index).keys.get(key),
            Usage {
                bytes: 10,
                items: 1
            }
        );
        assert_eq!(fs::read_dir(&store.tmp).unwrap().count(), 0);
    }

    #[test]
    fn a_replacement_whose_item_changed_needs_room_again() {
        let dir = tempfile::TempDir::new().unwrap();
        let config = MailboxConfig {
            min_free_bytes: 0,
            items_per_ip: 1,
            ..MailboxConfig::new(dir.path())
        };
        let store = Arc::new(Store::new(&config));
        store.retry();
        let key: IpAddr = "203.0.113.1".parse().unwrap();
        let reserve = |n| store.reserve(key, file(n), 10).unwrap();
        assert!(commit(&store, reserve(1), 10).is_ok());

        // A replacement holds no room for another item, and its item goes away during
        // the upload: another item takes the room.
        let replacement = reserve(1);
        assert_eq!(store.delete(&file(1), Kind::Blob, None), Deleted::Yes);
        assert!(commit(&store, reserve(2), 10).is_ok());
        assert!(matches!(
            commit(&store, replacement, 10),
            Err(CommitError::Refused(Refusal::Full))
        ));

        // With room, it is stored as a new item.
        let replacement = reserve(2);
        assert_eq!(store.delete(&file(2), Kind::Blob, None), Deleted::Yes);
        assert!(commit(&store, replacement, 10).is_ok());
        assert_eq!(
            lock(&store.index).keys.get(key),
            Usage {
                bytes: 10,
                items: 1
            }
        );
        assert!(store.item_path(&file(2)).exists() && !store.item_path(&file(1)).exists());
    }

    #[test]
    fn uploads_in_flight_need_disk_room_for_their_files() {
        let dir = tempfile::TempDir::new().unwrap();
        let config = MailboxConfig {
            max_bytes: 100,
            min_free_bytes: 40,
            ..MailboxConfig::new(dir.path())
        };
        let store = Arc::new(Store::new(&config));
        store.retry();
        let key: IpAddr = "203.0.113.1".parse().unwrap();
        assert!(commit(&store, store.reserve(key, file(1), 60).unwrap(), 60).is_ok());

        // A replacement adds nothing to the stored bytes, but its file sits beside the
        // item: 120 bytes on disk, within the limit and half the free space kept.
        let first = store.reserve(key, file(1), 60).unwrap();
        assert!(matches!(
            store.reserve(key, file(1), 60),
            Err(Refusal::Full)
        ));
        drop(first);
        assert!(store.reserve(key, file(1), 60).is_ok());
    }

    #[cfg(unix)]
    #[test]
    fn a_read_only_item_directory_makes_the_store_unavailable() {
        use std::os::unix::fs::PermissionsExt;

        let dir = tempfile::TempDir::new().unwrap();
        let store = Store::new(&MailboxConfig {
            min_free_bytes: 0,
            ..MailboxConfig::new(dir.path())
        });
        store.retry();
        assert!(store.is_available());
        let items = dir.path().join("items");
        let set_mode =
            |mode| fs::set_permissions(&items, fs::Permissions::from_mode(mode)).unwrap();
        set_mode(0o555);
        // Root writes anyway: nothing to check.
        if File::create(items.join("root")).is_ok() {
            set_mode(0o755);
            return;
        }
        // Uploads could still be created in `tmp`, but never moved in place.
        store.probe();
        assert!(!store.is_available());
        set_mode(0o755);
        store.retry();
        assert!(store.is_available());
    }

    #[test]
    fn replacing_an_item_moves_its_use_to_the_new_owner() {
        let mut index = Index::new(u64::MAX, usize::MAX);
        let a: IpAddr = "203.0.113.1".parse().unwrap();
        let b: IpAddr = "2001:db8:1:2::".parse().unwrap();
        index.insert(file(1), header(Kind::State, 1, 100), Some(a));
        assert_eq!(
            index.keys.get(a),
            Usage {
                bytes: 100,
                items: 1
            }
        );
        index.insert(file(1), header(Kind::Blob, 2, 40), Some(b));
        assert_eq!(index.keys.get(a), Usage::default());
        assert_eq!(index.keys.0.len(), 1);
        assert_eq!(
            index.keys.get(b),
            Usage {
                bytes: 40,
                items: 1
            }
        );
        assert_eq!(
            index.networks.get(network_of(b).unwrap()),
            Usage {
                bytes: 40,
                items: 1
            }
        );
        assert_eq!(
            index.per_kind,
            [
                Usage::default(),
                Usage {
                    bytes: 40,
                    items: 1
                }
            ]
        );
        index.remove(&file(1));
        assert!(index.keys.0.is_empty() && index.networks.0.is_empty());
    }
}
