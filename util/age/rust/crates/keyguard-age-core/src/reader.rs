use crate::{CHUNK, Error, Key, MAX_ENTRIES, MAX_LOG_N, MAX_NAME};
use age::{Decryptor, Identity, secrecy::SecretString, stream::StreamReader};
use self_cell::self_cell;
use std::{
    cell::UnsafeCell,
    fs::File,
    io::{BufRead, BufReader, Read, Seek, SeekFrom},
};
use zeroize::Zeroizing;
use zip::{ZipArchive, read::ZipFile};

pub(crate) type Input = StreamReader<BufReader<File>>;
struct Entry<'a>(ZipFile<'a, Input>);
self_cell!(
    struct Cell {
        owner: UnsafeCell<ZipArchive<Input>>,
        #[not_covariant]
        dependent: Entry,
    }
);
enum State {
    Idle(ZipArchive<Input>),
    Entry(Cell),
    Failed,
}
pub(crate) struct Reader {
    state: State,
    index: usize,
}
impl Reader {
    pub fn decrypt(file: File, key: Key) -> Result<Input, Error> {
        let mut file = BufReader::new(file);
        // Bound header parsing before passing untrusted input to the age parser.
        let mut header = Vec::new();
        let mut found = false;
        while header.len() < 8192 {
            let before = header.len();
            (&mut file)
                .take((8192 - before) as u64)
                .read_until(b'\n', &mut header)
                .map_err(|_| Error::Io)?;
            if header.len() == before {
                break;
            }
            if header[before..].starts_with(b"--- ") {
                found = true;
                break;
            }
        }
        if !found {
            return Err(Error::Corrupt);
        }
        file.rewind().map_err(|_| Error::Io)?;
        let decryptor = Decryptor::new_buffered(file).map_err(|_| Error::Corrupt)?;
        let plain = match key {
            Key::Password(password) => {
                if !decryptor.is_scrypt() {
                    return Err(Error::Corrupt);
                }
                let mut identity =
                    age::scrypt::Identity::new(SecretString::from(password.to_string()));
                identity.set_max_work_factor(MAX_LOG_N);
                decryptor.decrypt(std::iter::once(&identity as &dyn Identity))
            }
            Key::Identity(identity) => {
                // A password-encrypted file was not written for this key.
                if decryptor.is_scrypt() {
                    return Err(Error::Password);
                }
                decryptor.decrypt(std::iter::once(&identity as &dyn Identity))
            }
        };
        plain.map_err(|e| match e {
            age::DecryptError::ExcessiveWork { .. } => Error::Limit,
            _ => Error::Password,
        })
    }
    pub fn from_verified(mut plain: Input) -> Result<Self, Error> {
        plain.seek(SeekFrom::Start(0)).map_err(|_| Error::Corrupt)?;
        let archive = ZipArchive::new(plain).map_err(|_| Error::Corrupt)?;
        if archive.len() > MAX_ENTRIES {
            return Err(Error::Limit);
        }
        Ok(Self {
            state: State::Idle(archive),
            index: 0,
        })
    }
    pub fn next(&mut self) -> Result<Vec<u8>, Error> {
        // Unselected entries need no inflation: the entire age payload was authenticated.
        let archive = match std::mem::replace(&mut self.state, State::Failed) {
            State::Idle(a) => a,
            State::Entry(c) => c.into_owner().into_inner(),
            State::Failed => return Err(Error::Invalid),
        };
        if self.index == archive.len() {
            self.state = State::Idle(archive);
            return Ok(Vec::new());
        }
        let index = self.index;
        let cell = Cell::try_new(UnsafeCell::new(archive), |owner| {
            // SAFETY: The private cell exposes only its dependent until into_owner
            // drops that dependent. No other reference to the archive is created.
            let archive = unsafe { &mut *owner.get() };
            let entry = archive.by_index(index).map_err(|_| Error::Corrupt)?;
            let name = entry.name().map_err(|_| Error::Corrupt)?;
            if entry.encrypted() || name.is_empty() {
                return Err(Error::Corrupt);
            }
            if name.len() > MAX_NAME {
                return Err(Error::Limit);
            }
            Ok(Entry(entry))
        })?;
        let name = cell
            .with_dependent(|_, e| e.0.name().map(|n| n.as_bytes().to_vec()))
            .map_err(|_| Error::Corrupt)?;
        self.state = State::Entry(cell);
        self.index += 1;
        Ok(name)
    }
    pub fn read(&mut self) -> Result<Vec<u8>, Error> {
        let State::Entry(cell) = &mut self.state else {
            return Err(Error::Invalid);
        };
        let mut bytes = Zeroizing::new(vec![0; CHUNK]);
        let n = cell
            .with_dependent_mut(|_, e| e.0.read(&mut bytes))
            .map_err(|_| Error::Corrupt)?;
        Ok(bytes[..n].to_vec())
    }
}
