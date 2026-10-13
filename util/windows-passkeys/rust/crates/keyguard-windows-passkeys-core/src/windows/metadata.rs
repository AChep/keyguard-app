//! Reconciles the OS credential index with the vault. Windows documents neither duplicate
//! handling, removal matching, nor atomicity, so this never adds an ID that is still cached,
//! removes entries exactly as the OS returned them, and re-reads the index on every sync.
use super::decode::{read_slice, read_string};
use super::{
    Provider,
    abi::{CredentialDetails, Credentials},
    len_u32, wide,
};
use crate::{
    error::Error,
    protocol::{Identity, MAX_CREDENTIAL_ID, MAX_USER_ID},
};
use std::{
    collections::{BTreeMap, HashSet},
    marker::PhantomData,
    ptr, slice,
};
use windows::{
    Win32::Foundation::ERROR_FILE_NOT_FOUND,
    core::{HRESULT, PCWSTR},
};

const MAX_CREDENTIALS: usize = 100_000;

struct OwnedDetails {
    id: Vec<u8>,
    user: Vec<u8>,
    rp: Vec<u16>,
    rp_name: Vec<u16>,
    username: Vec<u16>,
    display: Vec<u16>,
}
impl OwnedDetails {
    fn new(value: &Identity) -> Result<Self, Error> {
        let id = value.credential_id.clone();
        let user = value.user_id.clone();
        if id.is_empty()
            || id.len() > MAX_CREDENTIAL_ID
            || user.is_empty()
            || user.len() > MAX_USER_ID
            || value.rp_id.is_empty()
            || value.user_name.is_empty()
        {
            return Err(Error::Invalid);
        }
        Ok(Self {
            id,
            user,
            rp: wide(&value.rp_id)?,
            rp_name: wide(&value.rp_name)?,
            username: wide(&value.user_name)?,
            display: wide(&value.user_display_name)?,
        })
    }
    fn raw(&self) -> Result<CredentialDetails<'_>, Error> {
        Ok(CredentialDetails {
            id_len: len_u32(self.id.len())?,
            id: self.id.as_ptr(),
            rp_id: PCWSTR(self.rp.as_ptr()),
            rp_name: PCWSTR(self.rp_name.as_ptr()),
            user_len: len_u32(self.user.len())?,
            user: self.user.as_ptr(),
            username: PCWSTR(self.username.as_ptr()),
            display_name: PCWSTR(self.display.as_ptr()),
            buffers: PhantomData,
        })
    }
}

/// The OS index from `GetAllCredentials`, freed on drop. Its entries borrow it.
struct Existing<'a> {
    provider: &'a Provider,
    count: u32,
    /// Valid until drop; [`Existing::entries`] narrows the entries' lifetime to its borrow.
    pointer: *mut CredentialDetails<'static>,
    readable: usize,
}
impl Existing<'_> {
    fn entries(&self) -> &[CredentialDetails<'_>] {
        if self.readable == 0 {
            return &[];
        }
        // SAFETY: Provider::existing validated a non-null, aligned array of `readable` entries,
        // retained until drop.
        unsafe { slice::from_raw_parts(self.pointer, self.readable) }
    }
}
impl Drop for Existing<'_> {
    fn drop(&mut self) {
        if !self.pointer.is_null() {
            // SAFETY: Matching API and exact count/pointer returned by GetAllCredentials.
            unsafe { (self.provider.api.free_credentials)(self.count, self.pointer) };
        }
    }
}

/// Reads an OS entry back. `None` means it is not an entry this provider writes.
///
/// # Safety
/// Every buffer of `value` must hold the data its length and NUL terminator describe.
unsafe fn parse(value: &CredentialDetails<'_>) -> Option<Identity> {
    // SAFETY: The caller guarantees `id_len` id bytes.
    let id = unsafe { read_slice(value, value.id, value.id_len, MAX_CREDENTIAL_ID) }.ok()?;
    // SAFETY: The caller guarantees `user_len` user id bytes.
    let user = unsafe { read_slice(value, value.user, value.user_len, MAX_USER_ID) }.ok()?;
    Some(Identity {
        credential_id: id.to_vec(),
        user_id: user.to_vec(),
        // SAFETY: The caller guarantees a NUL-terminated RP ID.
        rp_id: unsafe { read_string(value.rp_id) }.ok()?,
        // SAFETY: The caller guarantees a NUL-terminated RP name.
        rp_name: unsafe { read_string(value.rp_name) }.ok()?,
        // SAFETY: The caller guarantees a NUL-terminated user name.
        user_name: unsafe { read_string(value.username) }.ok()?,
        // SAFETY: The caller guarantees a NUL-terminated display name.
        user_display_name: unsafe { read_string(value.display_name) }.ok()?,
    })
}

/// Returns the OS entries to remove, then the desired credentials to add. An entry stays only
/// when it equals the desired one; anything else, including unreadable entries, is removed as
/// returned. A changed credential is removed and added again.
fn plan<'e, 'd>(
    existing: &[(CredentialDetails<'e>, Option<Identity>)],
    desired: &'d BTreeMap<Vec<u8>, Identity>,
) -> (Vec<CredentialDetails<'e>>, Vec<&'d Identity>) {
    let mut kept = HashSet::new();
    let mut remove = Vec::new();
    for (entry, value) in existing {
        match value {
            Some(value) if desired.get(&value.credential_id) == Some(value) => {
                kept.insert(value.credential_id.as_slice());
            }
            _ => remove.push(*entry),
        }
    }
    let add = desired
        .values()
        .filter(|value| !kept.contains(value.credential_id.as_slice()))
        .collect();
    (remove, add)
}

impl Provider {
    pub(super) fn sync(&self, identities: Vec<Identity>) -> Result<(), Error> {
        if identities.len() > MAX_CREDENTIALS {
            return Err(Error::Invalid);
        }
        let _lock = self.metadata();
        // The first duplicate wins, matching Kotlin's prefiltered vault index.
        let mut desired = BTreeMap::new();
        for identity in identities {
            desired
                .entry(identity.credential_id.clone())
                .or_insert(identity);
        }
        // Validate the complete new set before making any OS changes.
        let owned = desired
            .iter()
            .map(|(id, value)| Ok((id.as_slice(), OwnedDetails::new(value)?)))
            .collect::<Result<BTreeMap<_, _>, Error>>()?;
        let existing = self.existing()?;
        let entries: Vec<_> = existing
            .entries()
            .iter()
            .map(|entry| {
                // SAFETY: The OS wrote these entries, and `existing` retains them.
                (*entry, unsafe { parse(entry) })
            })
            .collect();
        let (remove, add) = plan(&entries, &desired);
        let add = add
            .iter()
            .map(|value| owned[value.credential_id.as_slice()].raw())
            .collect::<Result<Vec<_>, _>>()?;
        self.apply(self.api.remove_credentials, &remove)?;
        self.apply(self.api.add_credentials, &add)
    }

    /// One OS call per batch, like Microsoft's sample. If a batch applies partially, the next
    /// sync compares against the OS index again.
    fn apply(&self, call: Credentials, batch: &[CredentialDetails<'_>]) -> Result<(), Error> {
        if batch.is_empty() {
            return Ok(());
        }
        let count = len_u32(batch.len())?;
        // SAFETY: Every entry borrows the OS array or owned storage, which outlive this call.
        unsafe { call(&raw const self.clsid, count, batch.as_ptr()) }.ok()?;
        Ok(())
    }

    fn existing(&self) -> Result<Existing<'_>, Error> {
        let mut count = 0;
        let mut pointer = ptr::null_mut();
        // SAFETY: OS populates count and an allocated array; Existing releases it on every path.
        let result = unsafe {
            (self.api.get_credentials)(&raw const self.clsid, &raw mut count, &raw mut pointer)
        };
        let mut existing = Existing {
            provider: self,
            count,
            pointer,
            readable: 0,
        };
        // Microsoft's sample expects this result from an index that was never filled.
        if result == HRESULT::from_win32(ERROR_FILE_NOT_FOUND.0) {
            return Ok(existing);
        }
        result.ok()?;
        if count == 0 {
            return Ok(existing);
        }
        if count as usize > MAX_CREDENTIALS || pointer.is_null() || !pointer.is_aligned() {
            return Err(Error::Invalid);
        }
        existing.readable = count as usize;
        Ok(existing)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{
        Mutex,
        atomic::{AtomicBool, Ordering},
    };
    use windows::{
        Win32::Foundation::{E_FAIL, S_OK},
        core::GUID,
    };

    fn credential(id: &str, name: &str) -> Identity {
        Identity {
            credential_id: id.as_bytes().to_vec(),
            rp_id: "example.com".into(),
            rp_name: "Example".into(),
            user_id: b"user".to_vec(),
            user_name: name.into(),
            user_display_name: name.into(),
        }
    }

    fn desired(values: &[Identity]) -> BTreeMap<Vec<u8>, Identity> {
        values
            .iter()
            .map(|value| (value.credential_id.clone(), value.clone()))
            .collect()
    }

    /// A placeholder OS entry, identified by `tag`. `plan` never dereferences it.
    fn entry(tag: u32) -> CredentialDetails<'static> {
        CredentialDetails {
            id_len: tag,
            id: ptr::null(),
            rp_id: PCWSTR::null(),
            rp_name: PCWSTR::null(),
            user_len: 0,
            user: ptr::null(),
            username: PCWSTR::null(),
            display_name: PCWSTR::null(),
            buffers: PhantomData,
        }
    }

    fn ids(values: &[&Identity]) -> Vec<Vec<u8>> {
        let mut ids: Vec<_> = values.iter().map(|v| v.credential_id.clone()).collect();
        ids.sort();
        ids
    }

    #[test]
    fn plan_keeps_equal_entries_and_replaces_everything_else() {
        let kept = credential("kept", "alice");
        let changed = credential("changed", "bob");
        let added = credential("added", "dave");
        let existing = [
            (entry(1), Some(kept.clone())),
            (entry(2), Some(credential("changed", "old name"))),
            (entry(3), Some(credential("deleted", "carol"))),
            (entry(4), None),
        ];
        let wanted = desired(&[kept.clone(), changed.clone(), added.clone()]);
        let (remove, add) = plan(&existing, &wanted);
        let removed: Vec<_> = remove.iter().map(|entry| entry.id_len).collect();
        assert_eq!(removed, [2, 3, 4]);
        assert_eq!(ids(&add), ids(&[&added, &changed]));

        let unchanged = desired(slice::from_ref(&kept));
        let (remove, add) = plan(&[(entry(1), Some(kept))], &unchanged);
        assert!(remove.is_empty() && add.is_empty());
    }

    // A fake OS index for `sync`. The arrays it returns point into these entries.
    static INDEX: Mutex<Vec<OwnedDetails>> = Mutex::new(Vec::new());
    static CALLS: Mutex<Vec<(&str, u32)>> = Mutex::new(Vec::new());
    static NEVER_FILLED: AtomicBool = AtomicBool::new(true);

    unsafe extern "system" fn get_all(
        _: *const GUID,
        count: *mut u32,
        pointer: *mut *mut CredentialDetails<'_>,
    ) -> HRESULT {
        CALLS.lock().unwrap().push(("get", 0));
        if NEVER_FILLED.load(Ordering::SeqCst) {
            return HRESULT::from_win32(ERROR_FILE_NOT_FOUND.0);
        }
        let index = INDEX.lock().unwrap();
        let Ok(entries) = index
            .iter()
            .map(OwnedDetails::raw)
            .collect::<Result<Box<[_]>, _>>()
        else {
            return E_FAIL;
        };
        let Ok(length) = u32::try_from(entries.len()) else {
            return E_FAIL;
        };
        // SAFETY: `sync` supplies a live count slot.
        unsafe { count.write(length) };
        // SAFETY: `sync` supplies a live pointer slot; free_all reclaims the boxed slice.
        unsafe { pointer.write(Box::into_raw(entries).cast()) };
        S_OK
    }

    unsafe extern "system" fn free_all(count: u32, pointer: *mut CredentialDetails<'_>) {
        CALLS.lock().unwrap().push(("free", count));
        // SAFETY: get_all leaked exactly this boxed slice.
        drop(unsafe { Box::from_raw(ptr::slice_from_raw_parts_mut(pointer, count as usize)) });
    }

    unsafe extern "system" fn add(
        _: *const GUID,
        count: u32,
        entries: *const CredentialDetails<'_>,
    ) -> HRESULT {
        CALLS.lock().unwrap().push(("add", count));
        // SAFETY: `sync` passes `count` entries backed by its owned storage for this call.
        let entries = unsafe { slice::from_raw_parts(entries, count as usize) };
        let mut index = INDEX.lock().unwrap();
        for entry in entries {
            // SAFETY: Same live storage as above.
            let value = unsafe { parse(entry) }.unwrap();
            index.push(OwnedDetails::new(&value).unwrap());
        }
        NEVER_FILLED.store(false, Ordering::SeqCst);
        S_OK
    }

    unsafe extern "system" fn remove(
        _: *const GUID,
        count: u32,
        entries: *const CredentialDetails<'_>,
    ) -> HRESULT {
        CALLS.lock().unwrap().push(("remove", count));
        // SAFETY: `sync` passes `count` entries copied from the live get_all array.
        let entries = unsafe { slice::from_raw_parts(entries, count as usize) };
        // Only the OS's own entries match, so a re-encoded removal would leave stale entries.
        INDEX.lock().unwrap().retain(|stored| {
            !entries
                .iter()
                .any(|entry| ptr::eq(entry.id, stored.id.as_ptr()))
        });
        S_OK
    }

    fn calls() -> Vec<(&'static str, u32)> {
        std::mem::take(&mut *CALLS.lock().unwrap())
    }

    fn index() -> Vec<Identity> {
        let mut values: Vec<_> = INDEX
            .lock()
            .unwrap()
            .iter()
            .map(|stored| {
                let raw = stored.raw().unwrap();
                // SAFETY: Parsed while the index lock retains the entries.
                unsafe { parse(&raw) }.unwrap()
            })
            .collect();
        values.sort_by(|a, b| a.credential_id.cmp(&b.credential_id));
        values
    }

    #[test]
    fn sync_batches_changes_against_the_os_index() {
        let (mut provider, _calls) = super::super::tests::provider();
        provider.api.get_credentials = get_all;
        provider.api.free_credentials = free_all;
        provider.api.add_credentials = add;
        provider.api.remove_credentials = remove;
        let (a, b, c) = (
            credential("a", "alice"),
            credential("b", "bob"),
            credential("c", "carol"),
        );

        // A never-filled index is empty, not a failure.
        provider.sync(vec![a.clone(), b.clone()]).unwrap();
        assert_eq!(calls(), [("get", 0), ("add", 2)]);

        // An entry this provider cannot read back is removed with the changed one.
        INDEX.lock().unwrap().push(OwnedDetails {
            id: vec![0; MAX_CREDENTIAL_ID + 1],
            user: vec![1],
            rp: wide("example.com").unwrap(),
            rp_name: wide("Example").unwrap(),
            username: wide("").unwrap(),
            display: wide("").unwrap(),
        });
        let renamed = credential("b", "bobby");
        provider
            .sync(vec![
                a.clone(),
                renamed.clone(),
                c.clone(),
                credential("a", "other account"),
            ])
            .unwrap();
        assert_eq!(
            calls(),
            [("get", 0), ("remove", 2), ("add", 2), ("free", 3)]
        );
        let mut expected = vec![a.clone(), renamed.clone(), c.clone()];
        expected.sort_by(|a, b| a.credential_id.cmp(&b.credential_id));
        assert_eq!(index(), expected);

        // Nothing changed: read the index only.
        provider.sync(vec![c, renamed, a]).unwrap();
        assert_eq!(calls(), [("get", 0), ("free", 3)]);
    }
}
