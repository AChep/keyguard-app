//! A single selected entry backed only by memory, separate from the AES-only backup reader.

use crate::{
    error::{Operation, pack_zip_error},
    reader::{open_entry, open_entry_failure, read_entry},
};
use self_cell::self_cell;
use std::{cell::UnsafeCell, io::Cursor};
use zeroize::Zeroizing;
use zip::{ZipArchive, read::ZipFile, result::ZipError};

type Input = Cursor<Zeroizing<Vec<u8>>>;
struct Entry<'a>(ZipFile<'a, Input>);
self_cell!(
    struct Cell {
        owner: UnsafeCell<ZipArchive<Input>>,
        #[not_covariant]
        dependent: Entry,
    }
);

/// Owns the archive and the selected entry for the lifetime of a streaming read.
pub struct MemoryReader {
    cell: Cell,
}

impl MemoryReader {
    /// Opens one entry, checking its local header against the caller's validated directory.
    ///
    /// # Errors
    /// Returns a packed failure for invalid archives, mismatched entries, or passwords.
    pub fn open(
        bytes: &[u8],
        index: usize,
        local_header_offset: u64,
        password: Option<&str>,
    ) -> Result<Self, i64> {
        let mut archive = ZipArchive::new(Cursor::new(Zeroizing::new(bytes.to_vec())))
            .map_err(|e| pack_zip_error(Operation::ReaderOpen, &e))?;
        // The zip crate deduplicates normalized names, so its indices can differ
        // from the caller's central-directory indices. Check identity before
        // attempting decryption or handing out any bytes from the selected entry.
        let offset = archive
            .by_index_raw(index)
            .map_err(|e| open_entry_failure(&e))?
            .header_start();
        if offset != local_header_offset {
            return Err(pack_zip_error(
                Operation::ReaderOpen,
                &ZipError::InvalidArchive("Selected entry does not match its local header".into()),
            ));
        }
        let cell = Cell::try_new(UnsafeCell::new(archive), |owner| {
            // SAFETY: As in ArchiveReader's EntryCell, this private cell never exposes
            // its owner. Only the dependent accesses the archive until it is dropped.
            let archive = unsafe { &mut *owner.get() };
            open_entry(archive, index, password).map(Entry)
        })?;
        Ok(Self { cell })
    }

    /// Reads a chunk. EOF verifies the entry's CRC or authentication code.
    ///
    /// # Errors
    /// Returns a packed read failure, including integrity failures.
    pub fn read(&mut self, output: &mut [u8]) -> Result<usize, i64> {
        self.cell
            .with_dependent_mut(|_, entry| read_entry(&mut entry.0, output))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use zip::{AesMode, ZipWriter, unstable::write::FileOptionsExt, write::SimpleFileOptions};

    #[test]
    fn rejects_an_entry_replaced_by_unicode_name_deduplication() {
        // Two stored entries, both five bytes long: CP437 C3 A9 + ".txt" contains
        // FIRST at local offset 0; "other.txt" contains OTHER at offset 41 and
        // has a Unicode Path extra field naming UTF-8 "é.txt". Their displayed
        // names and original raw names differ, but zip normalizes both keys to
        // C3 A9 + ".txt", replacing the first entry at index zero.
        let bytes = include_bytes!("../tests/fixtures/unicode-name-collision.zip");
        assert!(MemoryReader::open(bytes, 0, 0, None).is_err());

        // The surviving native entry is readable only when its offset agrees.
        let mut reader = MemoryReader::open(bytes, 0, 41, None).unwrap();
        let mut output = [0; 16];
        assert_eq!(reader.read(&mut output).unwrap(), 5);
        assert_eq!(&output[..5], b"OTHER");
        assert_eq!(reader.read(&mut output).unwrap(), 0);
    }

    #[test]
    fn reads_mixed_plain_aes_and_zipcrypto_entries_and_retries_passwords() {
        let mut writer = ZipWriter::new(Cursor::new(Vec::new()));
        writer
            .start_file(
                "plain.txt",
                SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored),
            )
            .unwrap();
        writer.write_all(b"plain").unwrap();
        writer
            .start_file(
                "secret.txt",
                SimpleFileOptions::default().with_aes_encryption(AesMode::Aes256, "password"),
            )
            .unwrap();
        writer.write_all(b"secret").unwrap();
        writer
            .start_file(
                "legacy.txt",
                SimpleFileOptions::default()
                    .with_deprecated_encryption(b"password")
                    .unwrap(),
            )
            .unwrap();
        writer.write_all(b"legacy").unwrap();
        let bytes = writer.finish().unwrap().into_inner();
        let mut archive = ZipArchive::new(Cursor::new(&bytes)).unwrap();
        let offsets: Vec<_> = (0..archive.len())
            .map(|index| archive.by_index_raw(index).unwrap().header_start())
            .collect();
        assert!(MemoryReader::open(&bytes, 1, offsets[1], Some("wrong")).is_err());
        assert!(MemoryReader::open(&bytes, 1, offsets[1], None).is_err());
        assert!(MemoryReader::open(&bytes, 2, offsets[2], Some("wrong")).is_err());
        for (index, expected) in [
            (0, b"plain".as_slice()),
            (1, b"secret".as_slice()),
            (2, b"legacy".as_slice()),
        ] {
            let mut reader =
                MemoryReader::open(&bytes, index, offsets[index], Some("password")).unwrap();
            let mut output = [0; 32];
            let count = reader.read(&mut output).unwrap();
            assert_eq!(&output[..count], expected);
            assert_eq!(reader.read(&mut output).unwrap(), 0);
        }
    }
}
