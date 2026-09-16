//! Ephemeral Linux credentials. Persistent data contains only a random handle;
//! the master key stays in a process keyring or a memfd_secret mapping. There
//! is deliberately no ordinary-heap fallback and no string getter for secrets.

mod protected;

use protected::ProtectedSecret;
use std::io;
use std::sync::{LazyLock, Mutex, PoisonError};
use zeroize::Zeroizing;

const HANDLE_PREFIX: &[u8] = b"KGLX\x01";
const HANDLE_RANDOM_LEN: usize = 32;

pub(crate) struct MemoryStore {
    credential: Mutex<Option<Credential>>,
}

struct Credential {
    handle: Vec<u8>,
    secret: ProtectedSecret,
}

pub(crate) fn store() -> &'static MemoryStore {
    static STORE: LazyLock<MemoryStore> = LazyLock::new(MemoryStore::new);
    &STORE
}

impl MemoryStore {
    pub(crate) fn new() -> Self {
        Self {
            credential: Mutex::new(None),
        }
    }

    pub(crate) fn is_supported() -> bool {
        ProtectedSecret::new(b"probe").is_ok()
    }

    /// Fully provision the replacement before dropping the previous credential.
    pub(crate) fn put(&self, value: &[u8]) -> io::Result<Vec<u8>> {
        let mut handle = vec![0; HANDLE_PREFIX.len() + HANDLE_RANDOM_LEN];
        handle[..HANDLE_PREFIX.len()].copy_from_slice(HANDLE_PREFIX);
        random_bytes(&mut handle[HANDLE_PREFIX.len()..])?;
        let secret = ProtectedSecret::new(value)?;
        *self.lock() = Some(Credential {
            handle: handle.clone(),
            secret,
        });
        Ok(handle)
    }

    /// Called only after successful authentication. A stale or modified handle
    /// cannot release a different credential provisioned while the prompt ran.
    pub(crate) fn get(&self, handle: &[u8]) -> io::Result<Zeroizing<Vec<u8>>> {
        let credential = self.lock();
        let credential = credential
            .as_ref()
            .filter(|credential| credential.handle == handle)
            .ok_or_else(|| {
                io::Error::new(io::ErrorKind::NotFound, "unlock credential is missing")
            })?;
        credential.secret.read()
    }

    pub(crate) fn remove(&self) {
        *self.lock() = None;
    }

    pub(crate) fn contains(&self) -> bool {
        self.lock()
            .as_ref()
            .is_some_and(|credential| credential.secret.is_valid())
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Option<Credential>> {
        self.credential
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
    }
}

fn random_bytes(bytes: &mut [u8]) -> io::Result<()> {
    let mut offset = 0;
    while offset < bytes.len() {
        // SAFETY: The output points to the remaining initialized, writable
        // portion of bytes. getrandom retains no pointer after this call.
        let count = unsafe {
            libc::getrandom(bytes[offset..].as_mut_ptr().cast(), bytes.len() - offset, 0)
        };
        if count < 0 {
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(error);
        }
        if count == 0 {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "getrandom returned no bytes",
            ));
        }
        offset += count as usize;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::MemoryStore;

    #[test]
    fn credentials_round_trip_and_stale_handles_fail_closed() {
        let store = MemoryStore::new();
        assert!(!store.contains());
        let first = store.put(b"first master key").unwrap();
        assert_eq!(store.get(&first).unwrap().as_slice(), b"first master key");
        let second = store.put(b"second master key").unwrap();
        assert_ne!(first, second);
        assert!(store.get(&first).is_err());
        assert_eq!(store.get(&second).unwrap().as_slice(), b"second master key");
        let mut modified = second.clone();
        modified[0] ^= 1;
        assert!(store.get(&modified).is_err());
        assert!(store.get(&second[..second.len() - 1]).is_err());
        store.remove();
        assert!(!store.contains());
        assert!(store.get(&second).is_err());
        store.remove();
        assert!(MemoryStore::new().get(&second).is_err());
    }

    #[test]
    fn failed_replacement_preserves_existing_credential() {
        let store = MemoryStore::new();
        let handle = store.put(b"master key").unwrap();
        assert!(store.put(&[]).is_err());
        assert_eq!(store.get(&handle).unwrap().as_slice(), b"master key");
    }
}
