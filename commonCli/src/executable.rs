//! Whether a file is a bundled executable or a copy of it, such as the
//! connector that `keyguard browsers install` copies into a Flatpak
//! browser's folder.
use sha2::{Digest, Sha256};
use std::{
    fs::File,
    io::{Read, Seek},
};

/// What a file holds: its size and SHA-256 digest.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Contents {
    size: u64,
    sha256: [u8; 32],
}

impl Contents {
    pub fn of_bytes(bytes: &[u8]) -> Self {
        Self {
            size: bytes.len() as u64,
            sha256: Sha256::digest(bytes).into(),
        }
    }

    /// All of `file`, whatever was read from it before, if it holds at most
    /// `limit` bytes. It is read up to one byte past `limit`: a file can
    /// grow after a check of its size.
    pub fn of_file(mut file: &File, limit: u64) -> Option<Self> {
        file.rewind().ok()?;
        let mut hasher = Sha256::new();
        let size = std::io::copy(
            &mut std::io::BufReader::new(file.take(limit.saturating_add(1))),
            &mut hasher,
        )
        .ok()?;
        (size <= limit).then(|| Self {
            size,
            sha256: hasher.finalize().into(),
        })
    }

    /// Whether `file` holds these contents. Only a file of the same size is
    /// hashed, and no further than one byte past that size.
    pub fn matches(&self, file: &File) -> bool {
        file.metadata()
            .is_ok_and(|metadata| metadata.len() == self.size)
            && Self::of_file(file, self.size) == Some(*self)
    }
}

/// Whether `file` is the executable `bundled`, or a copy of it. Only a copy
/// of the same size is hashed, and no further than that size.
pub fn same_executable(file: &File, bundled: &File) -> bool {
    let (Ok(file_metadata), Ok(bundled_metadata)) = (file.metadata(), bundled.metadata()) else {
        return false;
    };
    let size = bundled_metadata.len();
    if file_metadata.len() != size {
        return false;
    }
    let same_file = file_identity(file).is_some_and(|id| Some(id) == file_identity(bundled));
    same_file || Contents::of_file(bundled, size).is_some_and(|contents| contents.matches(file))
}

/// The file that `file` is: equal identities mean the same file.
#[cfg(unix)]
fn file_identity(file: &File) -> Option<(u64, u64)> {
    use std::os::unix::fs::MetadataExt;
    let metadata = file.metadata().ok()?;
    Some((metadata.dev(), metadata.ino()))
}

/// The file that `file` is: equal identities mean the same file. ReFS needs
/// the 128-bit file ID.
#[cfg(windows)]
fn file_identity(file: &File) -> Option<(u64, [u8; 16])> {
    use std::os::windows::io::AsRawHandle;
    use windows_sys::Win32::Storage::FileSystem::{
        FILE_ID_INFO, FileIdInfo, GetFileInformationByHandleEx,
    };
    let mut info = FILE_ID_INFO::default();
    // SAFETY: file owns a live handle, and info is writable for the stated size.
    let found = unsafe {
        GetFileInformationByHandleEx(
            file.as_raw_handle().cast(),
            FileIdInfo,
            std::ptr::from_mut(&mut info).cast(),
            size_of::<FILE_ID_INFO>() as u32,
        )
    } != 0;
    found.then_some((info.VolumeSerialNumber, info.FileId.Identifier))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn files_that_grew_past_their_checked_size_have_no_contents() {
        use std::io::Seek;
        let temp = tempfile::tempdir().unwrap();
        let path = temp.path().join("copy");
        std::fs::write(&path, b"bundled cli").unwrap();
        let file = File::open(&path).unwrap();
        let contents = Contents::of_file(&file, 11).unwrap();
        assert_eq!(contents, Contents::of_bytes(b"bundled cli"));
        // The file grows after its size was compared.
        std::fs::write(&path, [&b"bundled cli"[..], &[0; 1 << 20]].concat()).unwrap();
        assert_eq!(Contents::of_file(&file, 11), None);
        assert!(!contents.matches(&file));
        // Read no further than one byte past the size.
        assert_eq!((&file).stream_position().unwrap(), 12);
        std::fs::write(&path, b"bundled cli").unwrap();
        assert_eq!(Contents::of_file(&file, 11), Some(contents));
        assert!(contents.matches(&file));
    }
}
