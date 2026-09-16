use std::ffi::CString;
use std::io;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::ptr::NonNull;
use std::sync::mpsc::{self, Sender};
use std::thread::{self, JoinHandle};
use zeroize::{Zeroize, Zeroizing};

const MAX_SECRET_LEN: usize = 4096;
const PROCESS_KEYRING: libc::c_long = -2;
const KEYCTL_UPDATE: libc::c_long = 2;
const KEYCTL_REVOKE: libc::c_long = 3;
const KEYCTL_SETPERM: libc::c_long = 5;
const KEYCTL_UNLINK: libc::c_long = 9;
const KEYCTL_READ: libc::c_long = 11;
// Possessor view/read/write/search/setattr; no link permission and no
// user/group/other permissions. Knowing the serial number grants no access.
const POSSESSOR_PERMISSIONS: libc::c_long = 0x2f00_0000;

pub(super) enum ProtectedSecret {
    Keyring(KernelSecret),
    Mapping(SecretMapping),
}

impl ProtectedSecret {
    pub(super) fn new(bytes: &[u8]) -> io::Result<Self> {
        if bytes.is_empty() || bytes.len() > MAX_SECRET_LEN {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "invalid secret length",
            ));
        }
        // Prefer memfd_secret for its stronger memory isolation, even though
        // it inhibits hibernation. Fall back to the process keyring if unavailable.
        SecretMapping::new(bytes)
            .map(Self::Mapping)
            .or_else(|_| KernelSecret::new(bytes).map(Self::Keyring))
    }

    pub(super) fn read(&self) -> io::Result<Zeroizing<Vec<u8>>> {
        match self {
            Self::Keyring(secret) => secret.read(),
            Self::Mapping(secret) => Ok(Zeroizing::new(secret.as_slice().to_vec())),
        }
    }

    pub(super) fn is_valid(&self) -> bool {
        match self {
            Self::Keyring(secret) => secret.is_valid(),
            Self::Mapping(_) => true,
        }
    }
}

pub(super) struct KernelSecret {
    requests: Option<Sender<KeyRequest>>,
    worker: Option<JoinHandle<()>>,
    #[cfg(test)]
    serial: libc::c_long,
}

enum KeyRequest {
    Read(Sender<io::Result<Zeroizing<Vec<u8>>>>),
    IsValid(Sender<bool>),
}

impl KernelSecret {
    fn new(bytes: &[u8]) -> io::Result<Self> {
        let bytes = Zeroizing::new(bytes.to_vec());
        let (ready_tx, ready_rx) = mpsc::sync_channel(0);
        let (requests, receiver) = mpsc::channel();
        // A lazily created process keyring is not installed in the credentials
        // of pre-existing sibling threads. Keep every key operation, including
        // revocation, on its owning thread instead of expanding UID access.
        let worker = thread::Builder::new()
            .name("keyguard-unlock-key".into())
            .spawn(move || {
                let key = KernelKey::new(&bytes);
                drop(bytes);
                let key = match key {
                    Ok(key) => key,
                    Err(error) => {
                        let _ = ready_tx.send(Err(error));
                        return;
                    }
                };
                if ready_tx.send(Ok(key.serial)).is_err() {
                    return;
                }
                for request in receiver {
                    match request {
                        KeyRequest::Read(reply) => {
                            let _ = reply.send(key.read());
                        }
                        KeyRequest::IsValid(reply) => {
                            let _ = reply.send(key.read_len().is_ok_and(|len| len == key.len));
                        }
                    }
                }
                // Dropping the channel revokes the key here, where possession
                // still exists. The caller joins before reporting deletion.
            })?;
        match ready_rx
            .recv()
            .map_err(|_| key_worker_unavailable())
            .and_then(|result| result)
        {
            Ok(_serial) => Ok(Self {
                requests: Some(requests),
                worker: Some(worker),
                #[cfg(test)]
                serial: _serial,
            }),
            Err(error) => {
                let _ = worker.join();
                Err(error)
            }
        }
    }

    fn read(&self) -> io::Result<Zeroizing<Vec<u8>>> {
        let (reply, result) = mpsc::channel();
        self.requests
            .as_ref()
            .ok_or_else(key_worker_unavailable)?
            .send(KeyRequest::Read(reply))
            .map_err(|_| key_worker_unavailable())?;
        result.recv().map_err(|_| key_worker_unavailable())?
    }

    fn is_valid(&self) -> bool {
        let (reply, result) = mpsc::channel();
        self.requests
            .as_ref()
            .is_some_and(|requests| requests.send(KeyRequest::IsValid(reply)).is_ok())
            && result.recv().unwrap_or(false)
    }
}

impl Drop for KernelSecret {
    fn drop(&mut self) {
        self.requests.take();
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
    }
}

fn key_worker_unavailable() -> io::Error {
    io::Error::other("protected key worker is unavailable")
}

struct KernelKey {
    serial: libc::c_long,
    len: usize,
}

impl KernelKey {
    fn new(bytes: &[u8]) -> io::Result<Self> {
        let mut random = [0; 16];
        super::random_bytes(&mut random)?;
        let name = CString::new(format!("keyguard-unlock-{random:02x?}"))?;
        // Create with harmless data, restrict permissions, THEN install the
        // secret. Unique names prevent replacing a still-live key by accident.
        // SAFETY: Both strings are NUL terminated. The one-byte payload is
        // readable during the call. The kernel copies all arguments.
        let serial = unsafe {
            libc::syscall(
                libc::SYS_add_key,
                c"user".as_ptr(),
                name.as_ptr(),
                b"-".as_ptr(),
                1usize,
                PROCESS_KEYRING,
            )
        };
        syscall_result(serial)?;
        let secret = Self {
            serial,
            len: bytes.len(),
        };
        // SAFETY: These operations use a live kernel serial owned by secret;
        // UPDATE borrows a readable slice which the kernel copies synchronously.
        unsafe {
            syscall_result(libc::syscall(
                libc::SYS_keyctl,
                KEYCTL_SETPERM,
                serial,
                POSSESSOR_PERMISSIONS,
            ))?;
            syscall_result(libc::syscall(
                libc::SYS_keyctl,
                KEYCTL_UPDATE,
                serial,
                bytes.as_ptr(),
                bytes.len(),
            ))?;
        }
        Ok(secret)
    }

    fn read_len(&self) -> io::Result<usize> {
        // SAFETY: A null READ buffer with zero length queries only the size.
        let result = unsafe {
            libc::syscall(
                libc::SYS_keyctl,
                KEYCTL_READ,
                self.serial,
                std::ptr::null_mut::<u8>(),
                0usize,
            )
        };
        syscall_result(result).map(|len| len as usize)
    }

    fn read(&self) -> io::Result<Zeroizing<Vec<u8>>> {
        let mut bytes = Zeroizing::new(vec![0; self.len]);
        // SAFETY: bytes is writable for its full length, and READ retains no
        // pointer. A changed/revoked key fails rather than returning partial data.
        let result = unsafe {
            libc::syscall(
                libc::SYS_keyctl,
                KEYCTL_READ,
                self.serial,
                bytes.as_mut_ptr(),
                bytes.len(),
            )
        };
        if syscall_result(result)? as usize != self.len {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "secret length changed",
            ));
        }
        Ok(bytes)
    }
}

impl Drop for KernelKey {
    fn drop(&mut self) {
        // SAFETY: Scalar kernel IDs only. Revoke makes the payload inaccessible;
        // unlink removes our process's reference. Already-revoked keys are safe.
        unsafe {
            libc::syscall(libc::SYS_keyctl, KEYCTL_REVOKE, self.serial);
            libc::syscall(
                libc::SYS_keyctl,
                KEYCTL_UNLINK,
                self.serial,
                PROCESS_KEYRING,
            );
        }
    }
}

fn syscall_result(value: libc::c_long) -> io::Result<libc::c_long> {
    if value < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(value)
    }
}

pub(super) struct SecretMapping {
    address: NonNull<u8>,
    len: usize,
}

// SAFETY: Each mapping is uniquely owned, process-wide, and only read after
// construction. MemoryStore serializes access and drop through its mutex.
unsafe impl Send for SecretMapping {}

impl SecretMapping {
    fn new(bytes: &[u8]) -> io::Result<Self> {
        // SAFETY: memfd_secret takes only flags and returns a new owned fd.
        let fd = syscall_result(unsafe { libc::syscall(libc::SYS_memfd_secret, libc::O_CLOEXEC) })?;
        // SAFETY: This newly created fd has no other owner.
        let fd = unsafe { OwnedFd::from_raw_fd(fd as libc::c_int) };
        syscall_result(
            // SAFETY: A live owned fd and bounded positive size; no pointers.
            unsafe { libc::ftruncate(fd.as_raw_fd(), bytes.len() as libc::off_t) } as libc::c_long,
        )?;
        // SAFETY: The fd names the sized secret memory object. The kernel picks
        // an exclusive mapping, automatically locked and excluded from dumps
        // and process_vm_readv/ptrace. No ordinary mapping is used on failure.
        let raw = unsafe {
            libc::mmap(
                std::ptr::null_mut(),
                bytes.len(),
                libc::PROT_READ | libc::PROT_WRITE,
                libc::MAP_SHARED,
                fd.as_raw_fd(),
                0,
            )
        };
        if raw == libc::MAP_FAILED {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: raw identifies the new mapping. Prevent even forked children
        // from inheriting it; a failed protection never retains the secret.
        if unsafe { libc::madvise(raw, bytes.len(), libc::MADV_DONTFORK) } != 0 {
            let error = io::Error::last_os_error();
            // SAFETY: mmap succeeded and this object exclusively owns the range.
            unsafe { libc::munmap(raw, bytes.len()) };
            return Err(error);
        }
        let Some(address) = NonNull::new(raw.cast::<u8>()) else {
            // SAFETY: mmap succeeded at address zero; release that mapping
            // because Rust references cannot describe it.
            unsafe { libc::munmap(raw, bytes.len()) };
            return Err(io::Error::other("null secret mapping"));
        };
        // SAFETY: The new writable mapping has bytes.len() bytes and cannot
        // overlap the input. Copy in userspace: secret mappings reject kernel IO.
        unsafe { std::ptr::copy_nonoverlapping(bytes.as_ptr(), address.as_ptr(), bytes.len()) };
        Ok(Self {
            address,
            len: bytes.len(),
        })
    }

    fn as_slice(&self) -> &[u8] {
        // SAFETY: The owned mapping stays alive and immutable while borrowed.
        unsafe { std::slice::from_raw_parts(self.address.as_ptr(), self.len) }
    }
}

impl Drop for SecretMapping {
    fn drop(&mut self) {
        // SAFETY: Exclusive ownership of a live writable mapping. Erase before
        // unmapping; each object owns separate pages, so no munlock can affect
        // another credential. Closing the fd earlier does not unmap the memory.
        unsafe {
            std::slice::from_raw_parts_mut(self.address.as_ptr(), self.len).zeroize();
            libc::munmap(self.address.as_ptr().cast(), self.len);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    #[ignore = "requires memfd_secret; run explicitly on a supporting Linux kernel"]
    fn separate_secret_mappings_remain_protected_after_drop() {
        let first = match SecretMapping::new(b"first") {
            Ok(first) => first,
            // memfd_secret is compiled out or disabled (secretmem.enable=0).
            Err(err) if err.raw_os_error() == Some(libc::ENOSYS) => {
                eprintln!("skipping: memfd_secret is unavailable on this kernel: {err}");
                return;
            }
            Err(err) => panic!("memfd_secret failed: {err}"),
        };
        let second = SecretMapping::new(b"second").unwrap();
        assert_ne!(first.address, second.address);
        drop(first);
        assert_eq!(second.as_slice(), b"second");
        // Even the owning process cannot read a secret mapping through kernel
        // memory-inspection APIs, unlike the previous mlocked heap allocation.
        let mut copy = [0u8; 6];
        let local = libc::iovec {
            iov_base: copy.as_mut_ptr().cast(),
            iov_len: copy.len(),
        };
        let remote = libc::iovec {
            iov_base: second.address.as_ptr().cast(),
            iov_len: second.len,
        };
        // SAFETY: Both iovecs describe live allocations; the kernel validates
        // the target mapping and returns EFAULT instead of exposing its bytes.
        let count = unsafe { libc::process_vm_readv(libc::getpid(), &local, 1, &remote, 1, 0) };
        assert_eq!(count, -1);
        assert_eq!(
            io::Error::last_os_error().raw_os_error(),
            Some(libc::EFAULT)
        );
        assert_eq!(copy, [0; 6]);
    }

    #[test]
    #[ignore = "requires keyctl permission; run explicitly outside restrictive seccomp filters"]
    fn preexisting_threads_can_read_and_remove_key() {
        let (secret_tx, secret_rx) = mpsc::channel::<KernelSecret>();
        let (ready_tx, ready_rx) = mpsc::channel();
        let worker = thread::spawn(move || {
            ready_tx.send(()).unwrap();
            let secret = secret_rx.recv().unwrap();
            assert!(secret.is_valid());
            assert_eq!(secret.read().unwrap().as_slice(), b"secret");
            let serial = secret.serial;
            drop(secret);
            let removed = std::mem::ManuallyDrop::new(KernelKey { serial, len: 6 });
            assert!(removed.read().is_err());
        });
        ready_rx.recv().unwrap();
        secret_tx
            .send(KernelSecret::new(b"secret").unwrap())
            .unwrap_or_else(|_| panic!("key worker exited"));
        worker.join().unwrap();
    }

    #[test]
    #[ignore = "requires keyctl permission; run explicitly outside restrictive seccomp filters"]
    fn keyring_permissions_and_removal() {
        let secret = KernelSecret::new(b"secret").unwrap();
        assert_eq!(secret.read().unwrap().as_slice(), b"secret");
        let child = std::process::Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "keychain::memory::protected::tests::unrelated_process_cannot_read_key",
            ])
            .env("KEYGUARD_TEST_KEY_SERIAL", secret.serial.to_string())
            .output()
            .unwrap();
        assert!(
            child.status.success(),
            "{}",
            String::from_utf8_lossy(&child.stdout)
        );
        assert!(String::from_utf8_lossy(&child.stdout).contains("1 passed"));
        let serial = secret.serial;
        drop(secret);
        let removed = std::mem::ManuallyDrop::new(KernelKey { serial, len: 6 });
        assert!(removed.read().is_err());
    }

    #[test]
    fn unrelated_process_cannot_read_key() {
        let Ok(serial) = std::env::var("KEYGUARD_TEST_KEY_SERIAL") else {
            return;
        };
        let secret = std::mem::ManuallyDrop::new(KernelKey {
            serial: serial.parse().unwrap(),
            len: 6,
        });
        assert!(secret.read().is_err());
    }
}
