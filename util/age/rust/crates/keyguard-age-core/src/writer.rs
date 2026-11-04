use crate::{CHUNK, Error, Key, LOG_N, MAX_ENTRIES, MAX_NAME};
use age::{Encryptor, Recipient, secrecy::SecretString, stream::StreamWriter as AgeWriter};
use std::{
    collections::VecDeque,
    io::{self, Write},
    sync::{Arc, Mutex},
};
use zip::{
    CompressionMethod, ZipWriter,
    write::{SimpleFileOptions, StreamWriter},
};

#[derive(Clone, Default)]
pub(crate) struct Output(Arc<Mutex<VecDeque<u8>>>);
impl Output {
    pub fn drain(&self) -> Result<Vec<u8>, Error> {
        let mut queue = self.0.lock().map_err(|_| Error::Internal)?;
        let n = queue.len().min(CHUNK);
        Ok(queue.drain(..n).collect())
    }
}
impl Write for Output {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        let mut queue = self
            .0
            .lock()
            .map_err(|_| io::Error::other("output failed"))?;
        // Bounds a caller which forgets to drain, and ZIP central-directory output.
        if queue.len().saturating_add(bytes.len()) > 16 * 1024 * 1024 {
            return Err(io::Error::other("output limit exceeded"));
        }
        queue.extend(bytes);
        Ok(bytes.len())
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

pub(crate) struct Writer {
    zip: ZipWriter<StreamWriter<AgeWriter<Output>>>,
    pub output: Output,
    in_entry: bool,
    count: usize,
}
impl Writer {
    pub fn new(key: &Key) -> Result<Self, Error> {
        let encryptor = match key {
            Key::Password(password) => {
                let mut recipient =
                    age::scrypt::Recipient::new(SecretString::from(password.to_string()));
                recipient.set_work_factor(LOG_N);
                Encryptor::with_recipients(std::iter::once(&recipient as &dyn Recipient))
            }
            Key::Identity(identity) => {
                let recipient = identity.to_public();
                Encryptor::with_recipients(std::iter::once(&recipient as &dyn Recipient))
            }
        }
        .map_err(|_| Error::Internal)?;
        let output = Output::default();
        let age = encryptor
            .wrap_output(output.clone())
            .map_err(|_| Error::Io)?;
        Ok(Self {
            zip: ZipWriter::new_stream(age),
            output,
            in_entry: false,
            count: 0,
        })
    }
    pub fn begin(&mut self, name: &str) -> Result<(), Error> {
        if self.in_entry || name.is_empty() {
            return Err(Error::Invalid);
        }
        if self.count >= MAX_ENTRIES || name.len() > MAX_NAME {
            return Err(Error::Limit);
        }
        self.zip
            .start_file(
                name,
                SimpleFileOptions::default()
                    .compression_method(CompressionMethod::Deflated)
                    .large_file(true),
            )
            .map_err(|_| Error::Corrupt)?;
        self.count += 1;
        self.in_entry = true;
        Ok(())
    }
    pub fn write(&mut self, bytes: &[u8]) -> Result<(), Error> {
        if !self.in_entry {
            return Err(Error::Invalid);
        }
        self.zip.write_all(bytes).map_err(|_| Error::Io)
    }
    pub fn end(&mut self) -> Result<(), Error> {
        if !self.in_entry {
            return Err(Error::Invalid);
        }
        self.in_entry = false;
        Ok(())
    }
    pub fn finish(self) -> Result<Output, Error> {
        if self.in_entry {
            return Err(Error::Invalid);
        }
        self.zip
            .finish()
            .map_err(|_| Error::Corrupt)?
            .into_inner()
            .finish()
            .map_err(|_| Error::Io)
    }
}
