//! Private ciphertext spool without a reachable name.
//!
//! POSIX targets unlink the owner-only file right after creating it, so no
//! other process can open it and a killed process leaves nothing behind.
//! Windows denies all sharing and deletes the file when its handle closes,
//! including on process termination. The name follows util/io's scratch
//! artifact format, so its orphan sweep reclaims a file that outlived a power
//! loss.

use crate::Error;
use std::{
    fs::File,
    io::{Seek, Write},
    path::Path,
};

pub(crate) struct Spool {
    file: File,
}

impl Spool {
    pub fn create(directory: &str) -> Result<Self, Error> {
        if directory.is_empty() || directory.len() > 4096 {
            return Err(Error::Invalid);
        }
        let path = Path::new(directory).join(artifact_name()?);
        let file = platform::create(&path).map_err(|_| Error::Io)?;
        Ok(Self { file })
    }

    pub fn write(&mut self, bytes: &[u8]) -> Result<(), Error> {
        self.file.write_all(bytes).map_err(|_| Error::Io)
    }

    /// Returns the spooled ciphertext, positioned at its start.
    pub fn into_file(mut self) -> Result<File, Error> {
        self.file.flush().map_err(|_| Error::Io)?;
        self.file.rewind().map_err(|_| Error::Io)?;
        Ok(self.file)
    }

    #[cfg(test)]
    pub fn len(&self) -> u64 {
        self.file.metadata().map(|m| m.len()).unwrap_or(0)
    }
}

/// `.kg-tmp-v1u-s-<uuid-v4>.tmp`: an uncoordinated util/io scratch artifact.
fn artifact_name() -> Result<String, Error> {
    let mut nonce = [0_u8; 16];
    getrandom::fill(&mut nonce).map_err(|_| Error::Internal)?;
    nonce[6] = (nonce[6] & 0x0f) | 0x40;
    nonce[8] = (nonce[8] & 0x3f) | 0x80;
    let hex: String = nonce.iter().map(|byte| format!("{byte:02x}")).collect();
    Ok(format!(
        ".kg-tmp-v1u-s-{}-{}-{}-{}-{}.tmp",
        &hex[0..8],
        &hex[8..12],
        &hex[12..16],
        &hex[16..20],
        &hex[20..32],
    ))
}

#[cfg(unix)]
mod platform {
    use std::{
        fs::{File, OpenOptions},
        io,
        os::unix::fs::OpenOptionsExt,
        path::Path,
    };

    pub(super) fn create(path: &Path) -> io::Result<File> {
        // `create_new` never follows a planted link at the final component.
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(path)?;
        // Nothing has been written yet, so a failed unlink leaks an empty file.
        std::fs::remove_file(path)?;
        Ok(file)
    }
}

#[cfg(windows)]
mod platform {
    use std::{
        fs::{File, OpenOptions},
        io,
        os::windows::fs::OpenOptionsExt,
        path::Path,
    };

    const GENERIC_READ: u32 = 0x8000_0000;
    const GENERIC_WRITE: u32 = 0x4000_0000;
    const DELETE: u32 = 0x0001_0000;
    const FILE_FLAG_DELETE_ON_CLOSE: u32 = 0x0400_0000;
    const FILE_ATTRIBUTE_TEMPORARY: u32 = 0x0000_0100;

    pub(super) fn create(path: &Path) -> io::Result<File> {
        OpenOptions::new()
            .read(true)
            .write(true)
            .create_new(true)
            .access_mode(GENERIC_READ | GENERIC_WRITE | DELETE)
            .share_mode(0)
            .custom_flags(FILE_FLAG_DELETE_ON_CLOSE)
            .attributes(FILE_ATTRIBUTE_TEMPORARY)
            .open(path)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Read;

    fn entries(directory: &Path) -> usize {
        std::fs::read_dir(directory)
            .expect("list directory")
            .count()
    }

    #[test]
    fn names_are_reserved_scratch_artifacts() {
        let name = artifact_name().expect("name");
        assert!(name.starts_with(".kg-tmp-v1u-s-"));
        assert!(name.ends_with(".tmp"));
        let uuid = &name[".kg-tmp-v1u-s-".len()..name.len() - ".tmp".len()];
        assert_eq!(uuid.len(), 36);
        assert_eq!(&uuid[14..15], "4");
        assert!("89ab".contains(&uuid[19..20]));
        assert_ne!(name, artifact_name().expect("name"));
    }

    #[test]
    fn spool_round_trips_and_leaves_nothing_behind() {
        let directory = tempfile::tempdir().expect("directory");
        let path = directory.path().to_str().expect("utf8");
        let mut spool = Spool::create(path).expect("spool");
        #[cfg(unix)]
        assert_eq!(entries(directory.path()), 0, "unlinked at creation");
        spool.write(b"cipher").expect("write");
        spool.write(b"text").expect("write");
        assert_eq!(spool.len(), 10);
        let mut file = spool.into_file().expect("file");
        let mut bytes = Vec::new();
        file.read_to_end(&mut bytes).expect("read");
        assert_eq!(bytes, b"ciphertext");
        drop(file);
        assert_eq!(entries(directory.path()), 0, "deleted after close");
    }

    #[test]
    fn rejects_missing_and_invalid_directories() {
        assert_eq!(Spool::create("").err(), Some(Error::Invalid));
        let missing = tempfile::tempdir()
            .expect("directory")
            .path()
            .join("missing");
        assert_eq!(
            Spool::create(missing.to_str().expect("utf8")).err(),
            Some(Error::Io)
        );
    }
}
