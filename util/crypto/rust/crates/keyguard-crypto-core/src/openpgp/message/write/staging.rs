//! Bounded, encrypted replay storage for raw SEIPDv1 packet data.
//!
//! The caller seals this store only after MDC authentication. No unauthenticated
//! inner packet is parsed. Spill files are anonymous/delete-on-close and contain
//! independently authenticated chunks under a fresh, memory-only key.

use super::{AwsLcRng, OPENPGP_PARTIAL_PACKET_BYTES, OpenPgpWriteError};
use crate::{primitives::chacha20_poly1305_in_place, protocol::CipherDirection};
use rand::RngCore;
use std::{
    fs::File,
    io::{self, Cursor, Read, Seek, SeekFrom, Write},
    path::Path,
};
use zeroize::{Zeroize, Zeroizing};

const MEMORY_BYTES: usize = 8 * 1024 * 1024;
// Separate from the protobuf envelope budget; counters must work on 32-bit targets.
const MAX_PACKET_BYTES: u64 = 16 * 1024 * 1024 * 1024;
const TAG_BYTES: usize = 16;
const DOMAIN: &[u8] = b"Keyguard OpenPGP authenticated replay v1";

pub(super) struct AuthenticatedStaging {
    memory: Zeroizing<Vec<u8>>,
    spill: Option<EncryptedSpill>,
    length: u64,
}

impl AuthenticatedStaging {
    pub(super) fn new() -> Self {
        Self {
            memory: Zeroizing::new(Vec::with_capacity(MEMORY_BYTES)),
            spill: None,
            length: 0,
        }
    }

    pub(super) fn push(&mut self, directory: &Path, bytes: &[u8]) -> Result<(), OpenPgpWriteError> {
        self.length = self
            .length
            .checked_add(bytes.len() as u64)
            .filter(|length| *length <= MAX_PACKET_BYTES)
            .ok_or(OpenPgpWriteError::ResourceLimit)?;
        if self.spill.is_none() && self.length <= MEMORY_BYTES as u64 {
            self.memory.extend_from_slice(bytes);
            return Ok(());
        }
        if self.spill.is_none() {
            let mut spill = EncryptedSpill::new(directory)?;
            for chunk in self.memory.chunks(OPENPGP_PARTIAL_PACKET_BYTES) {
                spill.append(chunk)?;
            }
            self.memory.zeroize();
            self.memory = Zeroizing::new(Vec::new());
            self.spill = Some(spill);
        }
        let spill = self.spill.as_mut().ok_or(OpenPgpWriteError::Internal)?;
        for chunk in bytes.chunks(OPENPGP_PARTIAL_PACKET_BYTES) {
            spill.append(chunk)?;
        }
        Ok(())
    }

    // Consuming seal prevents appending after authentication or replaying early.
    pub(super) fn seal(self) -> Result<AuthenticatedReplay, OpenPgpWriteError> {
        match self.spill {
            Some(mut spill) => {
                spill
                    .file
                    .seek(SeekFrom::Start(0))
                    .map_err(|_| OpenPgpWriteError::Internal)?;
                Ok(AuthenticatedReplay::Spill {
                    spill,
                    index: 0,
                    remaining: self.length,
                    buffer: Zeroizing::new(Vec::with_capacity(
                        OPENPGP_PARTIAL_PACKET_BYTES + TAG_BYTES,
                    )),
                    offset: 0,
                })
            }
            None => Ok(AuthenticatedReplay::Memory(Cursor::new(self.memory))),
        }
    }
}

pub(super) struct EncryptedSpill {
    file: File,
    key: Zeroizing<[u8; 32]>,
    chunks: u64,
}

impl EncryptedSpill {
    fn new(directory: &Path) -> Result<Self, OpenPgpWriteError> {
        let mut key = Zeroizing::new([0; 32]);
        AwsLcRng
            .try_fill_bytes(key.as_mut())
            .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
        let file = tempfile::tempfile_in(directory).map_err(|_| OpenPgpWriteError::Internal)?;
        Ok(Self {
            file,
            key,
            chunks: 0,
        })
    }

    fn append(&mut self, bytes: &[u8]) -> Result<(), OpenPgpWriteError> {
        let mut encrypted = Zeroizing::new(Vec::with_capacity(bytes.len() + TAG_BYTES));
        encrypted.extend_from_slice(bytes);
        chacha20_poly1305_in_place(
            CipherDirection::Encrypt,
            self.key.as_ref(),
            &nonce(self.chunks),
            DOMAIN,
            &mut encrypted,
        )
        .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
        self.file
            .write_all(&(bytes.len() as u32).to_be_bytes())
            .and_then(|()| self.file.write_all(&encrypted))
            .map_err(|_| OpenPgpWriteError::Internal)?;
        self.chunks = self
            .chunks
            .checked_add(1)
            .ok_or(OpenPgpWriteError::ResourceLimit)?;
        Ok(())
    }
}

fn nonce(index: u64) -> [u8; 12] {
    let mut nonce = [0; 12];
    nonce[4..].copy_from_slice(&index.to_be_bytes());
    nonce
}

pub(super) enum AuthenticatedReplay {
    Memory(Cursor<Zeroizing<Vec<u8>>>),
    Spill {
        spill: EncryptedSpill,
        index: u64,
        remaining: u64,
        buffer: Zeroizing<Vec<u8>>,
        offset: usize,
    },
}

impl std::fmt::Debug for AuthenticatedReplay {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("AuthenticatedReplay { .. }")
    }
}

impl Read for AuthenticatedReplay {
    fn read(&mut self, destination: &mut [u8]) -> io::Result<usize> {
        if destination.is_empty() {
            return Ok(0);
        }
        match self {
            Self::Memory(cursor) => cursor.read(destination),
            Self::Spill {
                spill,
                index,
                remaining,
                buffer,
                offset,
            } => {
                if *offset == buffer.len() {
                    buffer.zeroize();
                    buffer.clear();
                    *offset = 0;
                    if *index == spill.chunks {
                        return if *remaining == 0 {
                            Ok(0)
                        } else {
                            Err(io::Error::other("incomplete authenticated replay"))
                        };
                    }
                    let mut length = [0; 4];
                    spill.file.read_exact(&mut length)?;
                    let length = u32::from_be_bytes(length) as usize;
                    if length == 0
                        || length > OPENPGP_PARTIAL_PACKET_BYTES
                        || length as u64 > *remaining
                    {
                        return Err(io::Error::other("invalid authenticated replay chunk"));
                    }
                    buffer.resize(length + TAG_BYTES, 0);
                    spill.file.read_exact(buffer)?;
                    chacha20_poly1305_in_place(
                        CipherDirection::Decrypt,
                        spill.key.as_ref(),
                        &nonce(*index),
                        DOMAIN,
                        buffer,
                    )
                    .map_err(|_| io::Error::other("authenticated replay verification failed"))?;
                    *index += 1;
                    *remaining -= length as u64;
                }
                let count = destination.len().min(buffer.len() - *offset);
                destination[..count].copy_from_slice(&buffer[*offset..*offset + count]);
                buffer[*offset..*offset + count].zeroize();
                *offset += count;
                Ok(count)
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn replay_roundtrips_memory_and_encrypted_spill_with_small_reads() {
        let directory = tempfile::tempdir().unwrap();
        for size in [31, MEMORY_BYTES, MEMORY_BYTES + 137] {
            let expected: Vec<u8> = (0..size).map(|i| (i % 251) as u8).collect();
            let mut staging = AuthenticatedStaging::new();
            for chunk in expected.chunks(OPENPGP_PARTIAL_PACKET_BYTES) {
                staging.push(directory.path(), chunk).unwrap();
            }
            assert_eq!(staging.spill.is_some(), size > MEMORY_BYTES);
            let mut replay = staging.seal().unwrap();
            let mut actual = Vec::new();
            let mut buffer = [0; 7919];
            loop {
                let count = replay.read(&mut buffer).unwrap();
                if count == 0 {
                    break;
                }
                actual.extend_from_slice(&buffer[..count]);
            }
            assert!(actual == expected);
        }
        assert_eq!(std::fs::read_dir(directory.path()).unwrap().count(), 0);
    }

    #[test]
    fn spill_tampering_truncation_and_reordering_fail_closed() {
        let directory = tempfile::tempdir().unwrap();
        for tamper in 0..3 {
            let mut spill = EncryptedSpill::new(directory.path()).unwrap();
            spill.append(b"first secret").unwrap();
            spill.append(b"other secret").unwrap();
            match tamper {
                0 => {
                    spill.file.seek(SeekFrom::Start(4)).unwrap();
                    spill.file.write_all(&[0; 12]).unwrap();
                }
                1 => spill.file.set_len(8).unwrap(),
                _ => {
                    spill.file.seek(SeekFrom::Start(32)).unwrap();
                    let mut second = [0; 32];
                    spill.file.read_exact(&mut second).unwrap();
                    spill.file.seek(SeekFrom::Start(0)).unwrap();
                    spill.file.write_all(&second).unwrap();
                }
            }
            let mut replay = AuthenticatedStaging {
                memory: Zeroizing::new(Vec::new()),
                spill: Some(spill),
                length: 24,
            }
            .seal()
            .unwrap();
            let mut buffer = [0; 64];
            assert!(replay.read(&mut buffer).is_err());
        }
    }

    #[test]
    fn packet_limit_does_not_depend_on_control_envelope_or_usize() {
        let directory = tempfile::tempdir().unwrap();
        let mut staging = AuthenticatedStaging::new();
        staging.length = MAX_PACKET_BYTES;
        assert_eq!(
            staging.push(directory.path(), &[0]),
            Err(OpenPgpWriteError::ResourceLimit)
        );
        assert!(staging.spill.is_none());
    }
}
