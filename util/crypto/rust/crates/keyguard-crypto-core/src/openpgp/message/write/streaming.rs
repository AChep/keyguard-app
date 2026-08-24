//! Shared streaming transport and framing machinery.
//!
//! Worker permits, bounded channels, partial packet framing, and incremental
//! armor keep their existing backpressure, quota, and finalization semantics.

use super::*;

use crate::openpgp::packet::{partial_body_length, two_octet_new_length};

const MESSAGE_ENVELOPE_SCRATCH_BYTES: usize = 8 * 1024;

pub(super) enum OpenPgpWorkerInput {
    Data { bytes: Zeroizing<Vec<u8>> },
    Finish,
}

pub(super) enum OpenPgpWorkerOutput {
    Data(Zeroizing<Vec<u8>>),
    Consumed,
    Finished(Result<OpenPgpWorkerFinal, OpenPgpWriteError>),
}

pub(super) enum OpenPgpWorkerFinal {
    Encrypt(ProtectionMode),
    Decrypt(Box<OpenPgpDecryptWorkerFinal>),
}

pub(super) struct OpenPgpDecryptWorkerFinal {
    pub(super) verification: Option<Verification>,
    pub(super) metadata: Option<LiteralMetadata>,
    pub(super) decryption_key_fingerprint: Option<Fingerprint>,
    pub(super) declared_charset: Option<String>,
    pub(super) warnings: Vec<DecryptionWarning>,
}

pub(super) struct OpenPgpWorkerPermit;

impl OpenPgpWorkerPermit {
    pub(super) fn acquire() -> Result<Self, OpenPgpWriteError> {
        OPENPGP_STREAM_WORKERS
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |active| {
                (active < MAX_OPENPGP_STREAM_WORKERS).then_some(active + 1)
            })
            .map_err(|_| OpenPgpWriteError::ResourceLimit)?;
        Ok(Self)
    }
}

impl Drop for OpenPgpWorkerPermit {
    fn drop(&mut self) {
        let previous = OPENPGP_STREAM_WORKERS.fetch_sub(1, Ordering::AcqRel);
        debug_assert!(previous > 0);
    }
}

pub(super) struct OpenPgpWorkerPipe {
    input: Option<SyncSender<OpenPgpWorkerInput>>,
    output: Option<Receiver<OpenPgpWorkerOutput>>,
    join: Option<JoinHandle<()>>,
    finished: Option<Result<OpenPgpWorkerFinal, OpenPgpWriteError>>,
    pending_output: SecretChunks,
}

impl OpenPgpWorkerPipe {
    pub(super) fn spawn(
        name: &'static str,
        worker: impl FnOnce(
            OpenPgpChannelReader,
            SyncSender<OpenPgpWorkerOutput>,
        ) -> Result<OpenPgpWorkerFinal, OpenPgpWriteError>
        + Send
        + 'static,
    ) -> Result<Self, OpenPgpWriteError> {
        let permit = OpenPgpWorkerPermit::acquire()?;
        let (input_tx, input_rx) = mpsc::sync_channel(1);
        let (output_tx, output_rx) = mpsc::sync_channel(STREAM_CHANNEL_DEPTH);
        let join = thread::Builder::new()
            .name(name.to_owned())
            .spawn(move || {
                let result = catch_unwind(AssertUnwindSafe(|| {
                    worker(
                        OpenPgpChannelReader::new(input_rx, output_tx.clone()),
                        output_tx.clone(),
                    )
                }))
                .unwrap_or(Err(OpenPgpWriteError::Panic));
                let _ = output_tx.send(OpenPgpWorkerOutput::Finished(result));
                drop(permit);
            })
            .map_err(|_| OpenPgpWriteError::Internal)?;
        Ok(Self {
            input: Some(input_tx),
            output: Some(output_rx),
            join: Some(join),
            finished: None,
            pending_output: SecretChunks::default(),
        })
    }

    pub(super) fn update(&mut self, data: &[u8]) -> Result<Vec<u8>, OpenPgpWriteError> {
        if let Some(error) = self.terminal_update_error() {
            return Err(error);
        }
        if data.len() > OPENPGP_PARTIAL_PACKET_BYTES {
            return Err(OpenPgpWriteError::ResourceLimit);
        }
        let input_disconnected = match self.input.as_ref() {
            Some(input) => input
                .send(OpenPgpWorkerInput::Data {
                    bytes: Zeroizing::new(data.to_vec()),
                })
                .is_err(),
            None => true,
        };
        let mut output = std::mem::take(&mut self.pending_output);
        if input_disconnected {
            // The input receiver is owned by the worker closure, but Finished is
            // sent afterward by its wrapper on an independent channel. Await
            // that terminal result instead of racing it with input disconnection.
            self.input.take();
            self.collect_until_finished(&mut output)?;
            return self.complete_update(output);
        }
        loop {
            let message = self.receive_output()?;
            match message {
                OpenPgpWorkerOutput::Consumed => break,
                message => self.accept_output(message, &mut output)?,
            }
            if self.finished.is_some() {
                return self.complete_update(output);
            }
        }
        self.collect_available(&mut output)?;
        self.complete_update(output)
    }

    fn terminal_update_error(&self) -> Option<OpenPgpWriteError> {
        self.finished.as_ref().map(|result| {
            result
                .as_ref()
                .err()
                .copied()
                .unwrap_or(OpenPgpWriteError::InvalidArgument)
        })
    }

    fn complete_update(&mut self, output: SecretChunks) -> Result<Vec<u8>, OpenPgpWriteError> {
        match self.finished.as_ref() {
            Some(Ok(_)) => {
                // The rejected update was never accepted by the worker. Keep
                // output already produced by the successful worker for finish().
                self.pending_output = output;
                Err(OpenPgpWriteError::InvalidArgument)
            }
            Some(Err(error)) => Err(*error),
            None => output
                .into_zeroizing()
                .map(|mut output| std::mem::take(&mut *output))
                .map_err(|_| OpenPgpWriteError::ResourceLimit),
        }
    }

    fn cache_transport_failure(&mut self) -> OpenPgpWriteError {
        if self.finished.is_none() {
            self.finished = Some(Err(OpenPgpWriteError::Internal));
        }
        self.terminal_update_error()
            .unwrap_or(OpenPgpWriteError::Internal)
    }

    fn receive_output(&mut self) -> Result<OpenPgpWorkerOutput, OpenPgpWriteError> {
        let result = match self.output.as_ref() {
            Some(output) => output.recv(),
            None => return Err(self.cache_transport_failure()),
        };
        result.map_err(|_| self.cache_transport_failure())
    }

    #[cfg(test)]
    pub(super) fn from_test_channels(
        input: SyncSender<OpenPgpWorkerInput>,
        output: Receiver<OpenPgpWorkerOutput>,
    ) -> Self {
        Self {
            input: Some(input),
            output: Some(output),
            join: None,
            finished: None,
            pending_output: SecretChunks::default(),
        }
    }

    pub(super) fn finish(mut self) -> Result<(Vec<u8>, OpenPgpWorkerFinal), OpenPgpWriteError> {
        if let Some(input) = self.input.take() {
            if self.finished.is_none() {
                // A failed send means the worker already terminated. Its stable
                // result still arrives on the independent output channel below.
                let _ = input.send(OpenPgpWorkerInput::Finish);
            }
            drop(input);
        }
        let mut output = std::mem::take(&mut self.pending_output);
        if self.finished.is_none() {
            self.collect_until_finished(&mut output)?;
        } else {
            self.collect_available(&mut output)?;
        }
        self.output.take();
        self.join_worker()?;
        let final_result = self.finished.take().ok_or(OpenPgpWriteError::Internal)??;
        let mut output = output
            .into_zeroizing()
            .map_err(|_| OpenPgpWriteError::ResourceLimit)?;
        Ok((std::mem::take(&mut *output), final_result))
    }

    pub(super) fn collect_available(
        &mut self,
        destination: &mut SecretChunks,
    ) -> Result<(), OpenPgpWriteError> {
        loop {
            let result = match self.output.as_ref() {
                Some(output) => output.try_recv(),
                None => return Err(self.cache_transport_failure()),
            };
            match result {
                Ok(message) => self.accept_output(message, destination)?,
                Err(TryRecvError::Empty) => return Ok(()),
                Err(TryRecvError::Disconnected) if self.finished.is_some() => return Ok(()),
                Err(TryRecvError::Disconnected) => {
                    return Err(self.cache_transport_failure());
                }
            }
        }
    }

    pub(super) fn collect_until_finished(
        &mut self,
        destination: &mut SecretChunks,
    ) -> Result<(), OpenPgpWriteError> {
        while self.finished.is_none() {
            let message = self.receive_output()?;
            self.accept_output(message, destination)?;
        }
        Ok(())
    }

    pub(super) fn accept_output(
        &mut self,
        message: OpenPgpWorkerOutput,
        destination: &mut SecretChunks,
    ) -> Result<(), OpenPgpWriteError> {
        match message {
            OpenPgpWorkerOutput::Data(bytes) => {
                destination
                    .push(bytes, MAX_CONTROL_ENVELOPE_BYTES)
                    .map_err(|_| OpenPgpWriteError::ResourceLimit)?;
            }
            OpenPgpWorkerOutput::Consumed => {}
            OpenPgpWorkerOutput::Finished(result) => {
                if self.finished.replace(result).is_some() {
                    return Err(OpenPgpWriteError::Internal);
                }
            }
        }
        Ok(())
    }

    pub(super) fn join_worker(&mut self) -> Result<(), OpenPgpWriteError> {
        if let Some(join) = self.join.take() {
            join.join().map_err(|_| OpenPgpWriteError::Internal)?;
        }
        Ok(())
    }
}

impl Drop for OpenPgpWorkerPipe {
    fn drop(&mut self) {
        self.input.take();
        self.output.take();
        let _ = self.join_worker();
    }
}

pub(super) struct OpenPgpChannelReader {
    receiver: Receiver<OpenPgpWorkerInput>,
    acknowledgements: SyncSender<OpenPgpWorkerOutput>,
    current: Option<(Zeroizing<Vec<u8>>, usize)>,
    finished: bool,
}

impl std::fmt::Debug for OpenPgpChannelReader {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("OpenPgpChannelReader")
            .finish_non_exhaustive()
    }
}

impl OpenPgpChannelReader {
    pub(super) fn new(
        receiver: Receiver<OpenPgpWorkerInput>,
        acknowledgements: SyncSender<OpenPgpWorkerOutput>,
    ) -> Self {
        Self {
            receiver,
            acknowledgements,
            current: None,
            finished: false,
        }
    }

    pub(super) fn acknowledge_consumed(&mut self) {
        if self.current.take().is_some() {
            let _ = self.acknowledgements.send(OpenPgpWorkerOutput::Consumed);
        }
    }
}

impl Read for OpenPgpChannelReader {
    fn read(&mut self, destination: &mut [u8]) -> std::io::Result<usize> {
        if destination.is_empty() {
            return Ok(0);
        }
        loop {
            if let Some((bytes, offset)) = &mut self.current {
                if *offset < bytes.len() {
                    let count = destination.len().min(bytes.len() - *offset);
                    destination[..count].copy_from_slice(&bytes[*offset..*offset + count]);
                    *offset += count;
                    if *offset == bytes.len() {
                        self.acknowledge_consumed();
                    }
                    return Ok(count);
                }
                self.acknowledge_consumed();
            }
            if self.finished {
                return Ok(0);
            }
            match self.receiver.recv() {
                Ok(OpenPgpWorkerInput::Data { bytes }) => {
                    self.current = Some((bytes, 0));
                }
                Ok(OpenPgpWorkerInput::Finish) | Err(_) => self.finished = true,
            }
        }
    }
}

#[derive(Debug)]
pub(super) struct OpenPgpPreludeLimitedReader<R> {
    inner: R,
    active: Arc<AtomicBool>,
    exceeded: Arc<AtomicBool>,
    bytes_read: usize,
}

impl<R> OpenPgpPreludeLimitedReader<R> {
    pub(super) fn new(inner: R, active: Arc<AtomicBool>, exceeded: Arc<AtomicBool>) -> Self {
        Self {
            inner,
            active,
            exceeded,
            bytes_read: 0,
        }
    }
}

impl<R: Read> Read for OpenPgpPreludeLimitedReader<R> {
    fn read(&mut self, destination: &mut [u8]) -> std::io::Result<usize> {
        if destination.is_empty() || !self.active.load(Ordering::Acquire) {
            return self.inner.read(destination);
        }
        let remaining = MAX_CONTROL_ENVELOPE_BYTES.saturating_sub(self.bytes_read);
        if remaining == 0 {
            self.exceeded.store(true, Ordering::Release);
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "OpenPGP prelude resource limit exceeded",
            ));
        }
        let read_limit = destination.len().min(remaining);
        let read = self.inner.read(&mut destination[..read_limit])?;
        self.bytes_read += read;
        Ok(read)
    }
}

#[derive(Clone, Copy, Debug)]
enum MessageEnvelopeLength {
    Fixed(usize),
    Partial(usize),
    Indeterminate,
}

#[derive(Clone, Copy, Debug)]
enum MessageEnvelopeState {
    Header,
    FixedBody { remaining: usize },
    PartialBody { remaining: usize, chunks: usize },
    Passthrough,
}

/// Removes unknown noncritical packets from the one rPGP message-parser gap
/// where they are not ignored: between an ESK and its encryption container.
///
/// Packet headers and known packet bodies otherwise pass through byte-for-byte
/// so rPGP retains responsibility for the message grammar, including critical
/// unknown packets, known packets in invalid positions, and trailing data.
/// Unknown bodies are drained with constant scratch space and the same prelude,
/// packet-count, partial-chunk, and byte limits as other control envelopes.
#[derive(Debug)]
pub(super) struct MessageEnvelopeReader<R> {
    inner: R,
    state: MessageEnvelopeState,
    header: [u8; 6],
    header_len: usize,
    header_offset: usize,
    saw_esk: bool,
    packets: usize,
    resource_exceeded: Arc<AtomicBool>,
}

impl<R> MessageEnvelopeReader<R> {
    pub(super) fn new(inner: R, resource_exceeded: Arc<AtomicBool>) -> Self {
        Self {
            inner,
            state: MessageEnvelopeState::Header,
            header: [0_u8; 6],
            header_len: 0,
            header_offset: 0,
            saw_esk: false,
            packets: 0,
            resource_exceeded,
        }
    }

    fn resource_limit(&self, message: &'static str) -> std::io::Error {
        self.resource_exceeded.store(true, Ordering::Release);
        std::io::Error::new(std::io::ErrorKind::InvalidData, message)
    }
}

impl<R: Read> MessageEnvelopeReader<R> {
    fn read_byte(&mut self) -> std::io::Result<Option<u8>> {
        let mut byte = [0_u8; 1];
        loop {
            match self.inner.read(&mut byte) {
                Ok(0) => return Ok(None),
                Ok(_) => return Ok(Some(byte[0])),
                Err(error) if error.kind() == std::io::ErrorKind::Interrupted => {}
                Err(error) => return Err(error),
            }
        }
    }

    fn read_required_byte(&mut self) -> std::io::Result<u8> {
        self.read_byte()?.ok_or_else(|| {
            std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "truncated OpenPGP packet header",
            )
        })
    }

    fn read_new_length(
        &mut self,
        output: &mut [u8; 5],
    ) -> std::io::Result<(MessageEnvelopeLength, usize)> {
        let first = self.read_required_byte()?;
        output[0] = first;
        match first {
            0..=191 => Ok((MessageEnvelopeLength::Fixed(usize::from(first)), 1)),
            192..=223 => {
                let second = self.read_required_byte()?;
                output[1] = second;
                let length = two_octet_new_length(first, second);
                Ok((MessageEnvelopeLength::Fixed(length), 2))
            }
            224..=254 => {
                let length = partial_body_length(first)
                    .ok_or_else(|| self.resource_limit("OpenPGP packet length overflow"))?;
                Ok((MessageEnvelopeLength::Partial(length), 1))
            }
            255 => {
                for byte in &mut output[1..5] {
                    *byte = self.read_required_byte()?;
                }
                let length = u32::from_be_bytes([output[1], output[2], output[3], output[4]]);
                let length = usize::try_from(length)
                    .map_err(|_| self.resource_limit("OpenPGP packet length overflow"))?;
                Ok((MessageEnvelopeLength::Fixed(length), 5))
            }
        }
    }

    fn read_header(&mut self) -> std::io::Result<Option<(u8, MessageEnvelopeLength)>> {
        let Some(first) = self.read_byte()? else {
            return Ok(None);
        };
        if first & 0x80 == 0 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "malformed OpenPGP packet header",
            ));
        }
        self.header[0] = first;
        self.header_offset = 0;
        let (tag, length, length_len) = if first & 0x40 != 0 {
            let mut encoded = [0_u8; 5];
            let (length, length_len) = self.read_new_length(&mut encoded)?;
            self.header[1..1 + length_len].copy_from_slice(&encoded[..length_len]);
            (first & 0x3f, length, length_len)
        } else {
            let tag = (first >> 2) & 0x0f;
            let length_type = first & 0x03;
            let (length, length_len) = match length_type {
                0 => {
                    let length = self.read_required_byte()?;
                    self.header[1] = length;
                    (MessageEnvelopeLength::Fixed(usize::from(length)), 1)
                }
                1 => {
                    let first = self.read_required_byte()?;
                    let second = self.read_required_byte()?;
                    self.header[1..3].copy_from_slice(&[first, second]);
                    (
                        MessageEnvelopeLength::Fixed(usize::from(u16::from_be_bytes([
                            first, second,
                        ]))),
                        2,
                    )
                }
                2 => {
                    let mut encoded = [0_u8; 4];
                    for byte in &mut encoded {
                        *byte = self.read_required_byte()?;
                    }
                    self.header[1..5].copy_from_slice(&encoded);
                    let length = usize::try_from(u32::from_be_bytes(encoded))
                        .map_err(|_| self.resource_limit("OpenPGP packet length overflow"))?;
                    (MessageEnvelopeLength::Fixed(length), 4)
                }
                3 => (MessageEnvelopeLength::Indeterminate, 0),
                _ => unreachable!("two-bit OpenPGP packet length type"),
            };
            (tag, length, length_len)
        };
        self.header_len = 1 + length_len;
        self.packets = self
            .packets
            .checked_add(1)
            .filter(|count| *count <= MAX_OPENPGP_PACKETS)
            .ok_or_else(|| self.resource_limit("OpenPGP packet count limit exceeded"))?;
        Ok(Some((tag, length)))
    }

    fn copy_body(&mut self, destination: &mut [u8], remaining: usize) -> std::io::Result<usize> {
        let read_limit = destination.len().min(remaining);
        let read = self.inner.read(&mut destination[..read_limit])?;
        if read == 0 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "truncated OpenPGP packet body",
            ));
        }
        Ok(read)
    }

    fn skip_exact(&mut self, mut remaining: usize) -> std::io::Result<()> {
        let mut scratch = Zeroizing::new([0_u8; MESSAGE_ENVELOPE_SCRATCH_BYTES]);
        while remaining > 0 {
            let read_limit = scratch.len().min(remaining);
            self.inner.read_exact(&mut scratch[..read_limit])?;
            remaining -= read_limit;
        }
        Ok(())
    }

    fn skip_unknown_packet(&mut self, mut length: MessageEnvelopeLength) -> std::io::Result<()> {
        let mut body_bytes = 0_usize;
        let mut chunks = 0_usize;
        let mut first_partial = true;
        loop {
            let chunk = match length {
                MessageEnvelopeLength::Fixed(length) => length,
                MessageEnvelopeLength::Partial(length) => {
                    if first_partial && length < 512 {
                        return Err(std::io::Error::new(
                            std::io::ErrorKind::InvalidData,
                            "short initial OpenPGP partial body length",
                        ));
                    }
                    length
                }
                MessageEnvelopeLength::Indeterminate => {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::InvalidData,
                        "unknown packet cannot use an indeterminate length",
                    ));
                }
            };
            chunks = chunks
                .checked_add(1)
                .filter(|count| *count <= MAX_PARTIAL_BODY_CHUNKS)
                .ok_or_else(|| self.resource_limit("OpenPGP partial body chunk limit exceeded"))?;
            body_bytes = body_bytes
                .checked_add(chunk)
                .filter(|bytes| *bytes <= MAX_CONTROL_ENVELOPE_BYTES)
                .ok_or_else(|| self.resource_limit("OpenPGP packet body limit exceeded"))?;
            self.skip_exact(chunk)?;
            match length {
                MessageEnvelopeLength::Fixed(_) => return Ok(()),
                MessageEnvelopeLength::Partial(_) => {
                    let mut ignored = [0_u8; 5];
                    (length, _) = self.read_new_length(&mut ignored)?;
                    first_partial = false;
                }
                MessageEnvelopeLength::Indeterminate => unreachable!("rejected above"),
            }
        }
    }

    fn set_body_state(&mut self, length: MessageEnvelopeLength) {
        self.state = match length {
            MessageEnvelopeLength::Fixed(remaining) => {
                MessageEnvelopeState::FixedBody { remaining }
            }
            MessageEnvelopeLength::Partial(remaining) => MessageEnvelopeState::PartialBody {
                remaining,
                chunks: 1,
            },
            MessageEnvelopeLength::Indeterminate => MessageEnvelopeState::Passthrough,
        };
    }
}

impl<R: Read> Read for MessageEnvelopeReader<R> {
    fn read(&mut self, destination: &mut [u8]) -> std::io::Result<usize> {
        if destination.is_empty() {
            return Ok(0);
        }
        loop {
            if self.header_offset < self.header_len {
                let count = destination.len().min(self.header_len - self.header_offset);
                destination[..count]
                    .copy_from_slice(&self.header[self.header_offset..self.header_offset + count]);
                self.header_offset += count;
                return Ok(count);
            }
            match self.state {
                MessageEnvelopeState::Header => {
                    let Some((tag, length)) = self.read_header()? else {
                        return Ok(0);
                    };
                    if self.saw_esk && matches!(tag, 40..=63) {
                        self.header_len = 0;
                        self.skip_unknown_packet(length)?;
                        continue;
                    }
                    if matches!(tag, 1 | 3) {
                        self.saw_esk = true;
                    }
                    if self.saw_esk && matches!(tag, 9 | 18 | 20) {
                        self.state = MessageEnvelopeState::Passthrough;
                    } else {
                        self.set_body_state(length);
                    }
                }
                MessageEnvelopeState::FixedBody { remaining: 0 } => {
                    self.state = MessageEnvelopeState::Header;
                }
                MessageEnvelopeState::FixedBody { remaining } => {
                    let read = self.copy_body(destination, remaining)?;
                    self.state = MessageEnvelopeState::FixedBody {
                        remaining: remaining - read,
                    };
                    return Ok(read);
                }
                MessageEnvelopeState::PartialBody {
                    remaining: 0,
                    chunks,
                } => {
                    let mut encoded = [0_u8; 5];
                    let (length, encoded_len) = self.read_new_length(&mut encoded)?;
                    self.header[..encoded_len].copy_from_slice(&encoded[..encoded_len]);
                    self.header_len = encoded_len;
                    self.header_offset = 0;
                    self.state = match length {
                        MessageEnvelopeLength::Fixed(remaining) => {
                            MessageEnvelopeState::FixedBody { remaining }
                        }
                        MessageEnvelopeLength::Partial(remaining) => {
                            let chunks = chunks
                                .checked_add(1)
                                .filter(|count| *count <= MAX_PARTIAL_BODY_CHUNKS)
                                .ok_or_else(|| {
                                    self.resource_limit("OpenPGP partial body chunk limit exceeded")
                                })?;
                            MessageEnvelopeState::PartialBody { remaining, chunks }
                        }
                        MessageEnvelopeLength::Indeterminate => {
                            return Err(std::io::Error::new(
                                std::io::ErrorKind::InvalidData,
                                "indeterminate partial body terminator",
                            ));
                        }
                    };
                }
                MessageEnvelopeState::PartialBody { remaining, chunks } => {
                    let read = self.copy_body(destination, remaining)?;
                    self.state = MessageEnvelopeState::PartialBody {
                        remaining: remaining - read,
                        chunks,
                    };
                    return Ok(read);
                }
                MessageEnvelopeState::Passthrough => return self.inner.read(destination),
            }
        }
    }
}

pub(super) fn parse_streaming_message<'a, R>(
    input: R,
) -> Result<(Message<'a>, Option<Headers>), OpenPgpWriteError>
where
    R: Read + std::fmt::Debug + Send + 'a,
{
    let active = Arc::new(AtomicBool::new(true));
    let resource_exceeded = Arc::new(AtomicBool::new(false));
    let reader = OpenPgpPreludeLimitedReader::new(input, active.clone(), resource_exceeded.clone());
    let mut reader = BufReader::new(reader);
    let parsed = match reader.fill_buf() {
        Ok([first, ..]) if first & 0x80 == 0 => {
            let reader = BufReader::new(TolerantArmorReader::new(reader));
            let mut dearmor = armor::Dearmor::new(reader);
            match dearmor.read_header() {
                Ok(())
                    if matches!(
                        dearmor.typ,
                        Some(
                            BlockType::File
                                | BlockType::Message
                                | BlockType::MultiPartMessage(_, _)
                        )
                    ) =>
                {
                    let headers = dearmor.headers.clone();
                    Message::from_bytes(BufReader::new(MessageEnvelopeReader::new(
                        dearmor,
                        resource_exceeded.clone(),
                    )))
                    .map(|message| (message, Some(headers)))
                }
                Ok(()) => Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidData,
                    "unexpected OpenPGP armor block type",
                )
                .into()),
                Err(error) => Err(error),
            }
        }
        Ok(_) => Message::from_bytes(BufReader::new(MessageEnvelopeReader::new(
            reader,
            resource_exceeded.clone(),
        )))
        .map(|message| (message, None)),
        Err(error) => Err(error.into()),
    };
    active.store(false, Ordering::Release);
    parsed.map_err(|_| {
        if resource_exceeded.load(Ordering::Acquire) {
            OpenPgpWriteError::ResourceLimit
        } else {
            OpenPgpWriteError::InvalidArgument
        }
    })
}

pub(super) fn declared_armor_charset(headers: Option<&Headers>) -> Option<String> {
    let mut values = headers?
        .iter()
        .filter(|(name, _)| name.eq_ignore_ascii_case("Charset"))
        .flat_map(|(_, values)| values.iter());
    let value = values.next()?;
    if values.next().is_some() {
        return None;
    }
    let value = value.trim();
    (!value.is_empty()).then(|| value.to_owned())
}

pub(super) struct OpenPgpChannelWriter {
    sender: SyncSender<OpenPgpWorkerOutput>,
    pending: Zeroizing<Vec<u8>>,
}

impl OpenPgpChannelWriter {
    pub(super) fn new(sender: SyncSender<OpenPgpWorkerOutput>) -> Self {
        Self {
            sender,
            pending: Zeroizing::new(Vec::with_capacity(OPENPGP_PARTIAL_PACKET_BYTES)),
        }
    }

    pub(super) fn send_pending(&mut self) -> std::io::Result<()> {
        if self.pending.is_empty() {
            return Ok(());
        }
        let bytes = Zeroizing::new(std::mem::take(&mut *self.pending));
        self.sender
            .send(OpenPgpWorkerOutput::Data(bytes))
            .map_err(|_| std::io::Error::new(std::io::ErrorKind::BrokenPipe, "stream closed"))?;
        self.pending = Zeroizing::new(Vec::with_capacity(OPENPGP_PARTIAL_PACKET_BYTES));
        Ok(())
    }

    pub(super) fn finish(mut self) -> std::io::Result<()> {
        self.send_pending()
    }
}

impl Write for OpenPgpChannelWriter {
    fn write(&mut self, mut source: &[u8]) -> std::io::Result<usize> {
        let original_len = source.len();
        while !source.is_empty() {
            let available = OPENPGP_PARTIAL_PACKET_BYTES - self.pending.len();
            let count = available.min(source.len());
            self.pending.extend_from_slice(&source[..count]);
            source = &source[count..];
            if self.pending.len() == OPENPGP_PARTIAL_PACKET_BYTES {
                self.send_pending()?;
            }
        }
        Ok(original_len)
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

pub(super) trait OpenPgpOutputSink: Write {
    type Output;

    fn finish(self) -> std::io::Result<Self::Output>;
}

impl OpenPgpOutputSink for OpenPgpChannelWriter {
    type Output = ();

    fn finish(self) -> std::io::Result<Self::Output> {
        OpenPgpChannelWriter::finish(self)
    }
}

impl OpenPgpOutputSink for Vec<u8> {
    type Output = Self;

    fn finish(self) -> std::io::Result<Self::Output> {
        Ok(self)
    }
}

pub(super) struct PartialPacketReader<R> {
    tag: Tag,
    inner: R,
    emitted_tag: bool,
    finished: bool,
    output: Zeroizing<Vec<u8>>,
    output_offset: usize,
}

impl<R> PartialPacketReader<R> {
    pub(super) fn new(tag: Tag, inner: R) -> Self {
        Self {
            tag,
            inner,
            emitted_tag: false,
            finished: false,
            output: Zeroizing::new(Vec::with_capacity(OPENPGP_PARTIAL_PACKET_BYTES + 5)),
            output_offset: 0,
        }
    }

    pub(super) fn into_inner(self) -> R {
        self.inner
    }
}

impl<R: Read> PartialPacketReader<R> {
    pub(super) fn refill(&mut self) -> std::io::Result<()> {
        self.output.clear();
        self.output_offset = 0;
        if !self.emitted_tag {
            self.emitted_tag = true;
            self.output.push(self.tag.encode());
            return Ok(());
        }
        if self.finished {
            return Ok(());
        }

        let mut body = Zeroizing::new(vec![0_u8; OPENPGP_PARTIAL_PACKET_BYTES]);
        let mut body_len = 0_usize;
        while body_len < body.len() {
            match self.inner.read(&mut body[body_len..]) {
                Ok(0) => break,
                Ok(read) => body_len += read,
                Err(error) if error.kind() == std::io::ErrorKind::Interrupted => {}
                Err(error) => return Err(error),
            }
        }
        if body_len == OPENPGP_PARTIAL_PACKET_BYTES {
            self.output.push(OPENPGP_PARTIAL_PACKET_OCTET);
        } else {
            write_new_packet_length(&mut self.output, body_len)?;
            self.finished = true;
        }
        FixedCapacityWriter(&mut self.output).write_all(&body[..body_len])?;
        Ok(())
    }
}

impl<R: Read> Read for PartialPacketReader<R> {
    fn read(&mut self, destination: &mut [u8]) -> std::io::Result<usize> {
        if destination.is_empty() {
            return Ok(0);
        }
        while self.output_offset == self.output.len() && !self.finished {
            self.refill()?;
        }
        if self.output_offset == self.output.len() {
            return Ok(0);
        }
        let count = destination
            .len()
            .min(self.output.len() - self.output_offset);
        destination[..count]
            .copy_from_slice(&self.output[self.output_offset..self.output_offset + count]);
        self.output_offset += count;
        Ok(count)
    }
}

pub(super) fn write_new_packet_length(output: &mut Vec<u8>, length: usize) -> std::io::Result<()> {
    if length < 192 {
        output.push(length as u8);
    } else if length <= 8_383 {
        let encoded = length - 192;
        output.push(((encoded >> 8) + 192) as u8);
        output.push(encoded as u8);
    } else {
        let length = u32::try_from(length).map_err(|_| {
            std::io::Error::new(std::io::ErrorKind::InvalidInput, "packet length overflow")
        })?;
        output.push(0xff);
        output.extend_from_slice(&length.to_be_bytes());
    }
    Ok(())
}

pub(super) struct PrefixedReader<R> {
    prefix: Zeroizing<Vec<u8>>,
    prefix_offset: usize,
    inner: R,
}

impl<R> PrefixedReader<R> {
    pub(super) fn new(prefix: Vec<u8>, inner: R) -> Self {
        Self {
            prefix: Zeroizing::new(prefix),
            prefix_offset: 0,
            inner,
        }
    }
}

impl<R: Read> Read for PrefixedReader<R> {
    fn read(&mut self, destination: &mut [u8]) -> std::io::Result<usize> {
        if destination.is_empty() {
            return Ok(0);
        }
        if self.prefix_offset < self.prefix.len() {
            let count = destination
                .len()
                .min(self.prefix.len() - self.prefix_offset);
            destination[..count]
                .copy_from_slice(&self.prefix[self.prefix_offset..self.prefix_offset + count]);
            self.prefix_offset += count;
            return Ok(count);
        }
        self.inner.read(destination)
    }
}

pub(super) struct LiteralBodyReader<R> {
    prefix: Zeroizing<Vec<u8>>,
    prefix_offset: usize,
    source: R,
    hasher: Option<SignatureHasher>,
}

impl<R> LiteralBodyReader<R> {
    pub(super) fn new(
        source: R,
        file_name: &[u8],
        literal_time: Timestamp,
        hasher: Option<SignatureHasher>,
    ) -> Result<Self, OpenPgpWriteError> {
        let file_name_len =
            u8::try_from(file_name.len()).map_err(|_| OpenPgpWriteError::InvalidArgument)?;
        let mut prefix = Zeroizing::new(Vec::with_capacity(file_name.len() + 6));
        prefix.push(b'b');
        prefix.push(file_name_len);
        prefix.extend_from_slice(file_name);
        prefix.extend_from_slice(&literal_time.as_secs().to_be_bytes());
        Ok(Self {
            prefix,
            prefix_offset: 0,
            source,
            hasher,
        })
    }
}

impl<R: Read> Read for LiteralBodyReader<R> {
    fn read(&mut self, destination: &mut [u8]) -> std::io::Result<usize> {
        if destination.is_empty() {
            return Ok(0);
        }
        if self.prefix_offset < self.prefix.len() {
            let count = destination
                .len()
                .min(self.prefix.len() - self.prefix_offset);
            destination[..count]
                .copy_from_slice(&self.prefix[self.prefix_offset..self.prefix_offset + count]);
            self.prefix_offset += count;
            return Ok(count);
        }
        let read = self.source.read(destination)?;
        if let Some(hasher) = &mut self.hasher {
            hasher.write_all(&destination[..read])?;
        }
        Ok(read)
    }
}

pub(super) struct SignedLiteralReader<'a, R> {
    prefix: Zeroizing<Vec<u8>>,
    prefix_offset: usize,
    literal: Option<PartialPacketReader<LiteralBodyReader<R>>>,
    signer: Option<&'a dyn SigningKey>,
    trailer: Zeroizing<Vec<u8>>,
    trailer_offset: usize,
}

impl<'a, R: Read> SignedLiteralReader<'a, R> {
    pub(super) fn new(
        source: R,
        file_name: &[u8],
        literal_time: Timestamp,
        signature_time: Option<Timestamp>,
        signer: Option<&'a dyn SigningKey>,
        intended_recipients: &[Fingerprint],
    ) -> Result<Self, OpenPgpWriteError> {
        let (prefix, hasher) = signer
            .map(|key| {
                let signature_time = signature_time.ok_or(OpenPgpWriteError::Internal)?;
                streaming_inline_signature(key, signature_time, intended_recipients)
            })
            .transpose()?
            .map_or_else(
                || (Vec::new(), None),
                |(prefix, hasher)| (prefix, Some(hasher)),
            );
        let literal = LiteralBodyReader::new(source, file_name, literal_time, hasher)?;
        Ok(Self {
            prefix: Zeroizing::new(prefix),
            prefix_offset: 0,
            literal: Some(PartialPacketReader::new(Tag::LiteralData, literal)),
            signer,
            trailer: Zeroizing::new(Vec::new()),
            trailer_offset: 0,
        })
    }

    pub(super) fn finalize_signature(&mut self) -> std::io::Result<()> {
        let literal = self
            .literal
            .take()
            .ok_or_else(|| std::io::Error::other("literal stream missing"))?
            .into_inner();
        let Some(signer) = self.signer else {
            return Ok(());
        };
        let hasher = literal
            .hasher
            .ok_or_else(|| std::io::Error::other("signature hasher missing"))?;
        let signature = hasher
            .sign(signer, &Password::empty())
            .map_err(|_| std::io::Error::other("OpenPGP signing failed"))?;
        signature
            .to_writer_with_header(&mut *self.trailer)
            .map_err(|_| std::io::Error::other("OpenPGP signature encoding failed"))?;
        Ok(())
    }
}

impl<R: Read> Read for SignedLiteralReader<'_, R> {
    fn read(&mut self, destination: &mut [u8]) -> std::io::Result<usize> {
        if destination.is_empty() {
            return Ok(0);
        }
        loop {
            if self.prefix_offset < self.prefix.len() {
                let count = destination
                    .len()
                    .min(self.prefix.len() - self.prefix_offset);
                destination[..count]
                    .copy_from_slice(&self.prefix[self.prefix_offset..self.prefix_offset + count]);
                self.prefix_offset += count;
                return Ok(count);
            }
            if let Some(literal) = &mut self.literal {
                let read = literal.read(destination)?;
                if read > 0 {
                    return Ok(read);
                }
                self.finalize_signature()?;
                continue;
            }
            if self.trailer_offset < self.trailer.len() {
                let count = destination
                    .len()
                    .min(self.trailer.len() - self.trailer_offset);
                destination[..count].copy_from_slice(
                    &self.trailer[self.trailer_offset..self.trailer_offset + count],
                );
                self.trailer_offset += count;
                return Ok(count);
            }
            return Ok(0);
        }
    }
}

pub(super) fn streaming_inline_signature(
    key: &dyn SigningKey,
    signature_time: Timestamp,
    intended_recipients: &[Fingerprint],
) -> Result<(Vec<u8>, SignatureHasher), OpenPgpWriteError> {
    let (config, one_pass) = inline_signature_setup(key, signature_time, intended_recipients)?;
    let mut prefix = Vec::new();
    one_pass
        .to_writer_with_header(&mut prefix)
        .map_err(|_| OpenPgpWriteError::Internal)?;
    let hasher = config
        .into_hasher()
        .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
    Ok((prefix, hasher))
}

pub(super) struct GnuPgpOcbEncryptReader<R> {
    source: R,
    cipher: Aes256Ocb,
    iv: Zeroizing<[u8; 15]>,
    chunk_index: u64,
    plaintext_bytes: u64,
    output: Zeroizing<Vec<u8>>,
    output_offset: usize,
    final_emitted: bool,
}

impl<R> GnuPgpOcbEncryptReader<R> {
    pub(super) fn new(
        source: R,
        session_key: &RawSessionKey,
        rng: &mut AwsLcRng,
    ) -> Result<Self, OpenPgpWriteError> {
        let mut iv = Zeroizing::new([0_u8; 15]);
        rng.try_fill_bytes(&mut *iv)
            .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
        let cipher = Aes256Ocb::new_from_slice(session_key.as_ref())
            .map_err(|_| OpenPgpWriteError::CryptoFailure)?;
        let mut output = Zeroizing::new(Vec::with_capacity(19));
        output.extend_from_slice(&[
            1,
            u8::from(SymmetricKeyAlgorithm::AES256),
            u8::from(AeadAlgorithm::Ocb),
            GNUPG_AEAD_CHUNK_OCTET,
        ]);
        output.extend_from_slice(iv.as_slice());
        Ok(Self {
            source,
            cipher,
            iv,
            chunk_index: 0,
            plaintext_bytes: 0,
            output,
            output_offset: 0,
            final_emitted: false,
        })
    }
}

impl<R: Read> GnuPgpOcbEncryptReader<R> {
    pub(super) fn refill(&mut self) -> std::io::Result<()> {
        self.output.clear();
        self.output_offset = 0;
        if self.final_emitted {
            return Ok(());
        }
        let mut plaintext = Zeroizing::new(vec![0_u8; GNUPG_AEAD_CHUNK_BYTES]);
        let mut length = 0_usize;
        while length < plaintext.len() {
            match self.source.read(&mut plaintext[length..]) {
                Ok(0) => break,
                Ok(read) => length += read,
                Err(error) if error.kind() == std::io::ErrorKind::Interrupted => {}
                Err(error) => return Err(error),
            }
        }
        if length > 0 {
            plaintext.truncate(length);
            let nonce = gnupg_ocb_nonce(&self.iv, self.chunk_index);
            let associated_data = gnupg_ocb_associated_data(self.chunk_index);
            let tag = self
                .cipher
                .encrypt_in_place_detached(
                    Nonce::<U15>::from_slice(&nonce),
                    &associated_data,
                    &mut plaintext,
                )
                .map_err(|_| std::io::Error::other("OpenPGP OCB encryption failed"))?;
            self.output.extend_from_slice(&plaintext);
            self.output.extend_from_slice(&tag);
            self.plaintext_bytes = self
                .plaintext_bytes
                .checked_add(
                    u64::try_from(length).map_err(|_| {
                        std::io::Error::other("OpenPGP OCB plaintext length overflow")
                    })?,
                )
                .ok_or_else(|| std::io::Error::other("OpenPGP OCB plaintext length overflow"))?;
            self.chunk_index = self
                .chunk_index
                .checked_add(1)
                .ok_or_else(|| std::io::Error::other("OpenPGP OCB chunk overflow"))?;
            return Ok(());
        }

        let nonce = gnupg_ocb_nonce(&self.iv, self.chunk_index);
        let mut associated_data =
            Zeroizing::new(gnupg_ocb_associated_data(self.chunk_index).to_vec());
        associated_data.extend_from_slice(&self.plaintext_bytes.to_be_bytes());
        let mut empty = Vec::new();
        let tag = self
            .cipher
            .encrypt_in_place_detached(
                Nonce::<U15>::from_slice(&nonce),
                &associated_data,
                &mut empty,
            )
            .map_err(|_| std::io::Error::other("OpenPGP OCB finalization failed"))?;
        self.output.extend_from_slice(&tag);
        self.final_emitted = true;
        Ok(())
    }
}

impl<R: Read> Read for GnuPgpOcbEncryptReader<R> {
    fn read(&mut self, destination: &mut [u8]) -> std::io::Result<usize> {
        if destination.is_empty() {
            return Ok(0);
        }
        while self.output_offset == self.output.len() && !self.final_emitted {
            self.refill()?;
        }
        if self.output_offset == self.output.len() {
            return Ok(0);
        }
        let count = destination
            .len()
            .min(self.output.len() - self.output_offset);
        destination[..count]
            .copy_from_slice(&self.output[self.output_offset..self.output_offset + count]);
        self.output_offset += count;
        Ok(count)
    }
}

pub(super) enum OpenPgpMessageWriter<W> {
    Binary(W),
    Armored(OpenPgpArmorWriter<W>),
}

impl<W: OpenPgpOutputSink> OpenPgpMessageWriter<W> {
    pub(super) fn new(
        writer: W,
        armored: bool,
        include_checksum: bool,
    ) -> Result<Self, OpenPgpWriteError> {
        if armored {
            OpenPgpArmorWriter::new(writer, include_checksum)
                .map(Self::Armored)
                .map_err(|_| OpenPgpWriteError::Internal)
        } else {
            Ok(Self::Binary(writer))
        }
    }

    pub(super) fn finish(self) -> Result<W::Output, OpenPgpWriteError> {
        match self {
            Self::Binary(writer) => OpenPgpOutputSink::finish(writer),
            Self::Armored(writer) => writer.finish(),
        }
        .map_err(|_| OpenPgpWriteError::Internal)
    }
}

impl<W: OpenPgpOutputSink> Write for OpenPgpMessageWriter<W> {
    fn write(&mut self, source: &[u8]) -> std::io::Result<usize> {
        match self {
            Self::Binary(writer) => writer.write(source),
            Self::Armored(writer) => writer.write(source),
        }
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

pub(super) struct OpenPgpArmorWriter<W> {
    inner: W,
    carry: Zeroizing<[u8; 3]>,
    carry_len: usize,
    line_length: usize,
    crc: Option<u32>,
}

impl<W: OpenPgpOutputSink> OpenPgpArmorWriter<W> {
    pub(super) fn new(mut inner: W, include_checksum: bool) -> std::io::Result<Self> {
        inner.write_all(b"-----BEGIN PGP MESSAGE-----\n\n")?;
        Ok(Self {
            inner,
            carry: Zeroizing::new([0_u8; 3]),
            carry_len: 0,
            line_length: 0,
            crc: include_checksum.then_some(0x00b7_04ce),
        })
    }

    pub(super) fn write_quartet(&mut self, quartet: [u8; 4]) -> std::io::Result<()> {
        self.inner.write_all(&quartet)?;
        self.line_length += quartet.len();
        if self.line_length == 64 {
            self.inner.write_all(b"\n")?;
            self.line_length = 0;
        }
        Ok(())
    }

    pub(super) fn finish(mut self) -> std::io::Result<W::Output> {
        if self.carry_len > 0 {
            let quartet = encode_base64_triplet(&self.carry, self.carry_len);
            self.write_quartet(quartet)?;
            self.carry.zeroize();
            self.carry_len = 0;
        }
        if self.line_length != 0 {
            self.inner.write_all(b"\n")?;
            self.line_length = 0;
        }
        if let Some(crc) = self.crc {
            let crc = crc & 0x00ff_ffff;
            let crc_bytes = [
                ((crc >> 16) & 0xff) as u8,
                ((crc >> 8) & 0xff) as u8,
                (crc & 0xff) as u8,
            ];
            self.inner.write_all(b"=")?;
            self.inner
                .write_all(&encode_base64_triplet(&crc_bytes, crc_bytes.len()))?;
            self.inner.write_all(b"\n")?;
        }
        self.inner.write_all(b"-----END PGP MESSAGE-----\n")?;
        OpenPgpOutputSink::finish(self.inner)
    }
}

impl<W: OpenPgpOutputSink> Write for OpenPgpArmorWriter<W> {
    fn write(&mut self, source: &[u8]) -> std::io::Result<usize> {
        for byte in source {
            if let Some(crc) = &mut self.crc {
                *crc = crc24_update(*crc, *byte);
            }
            self.carry[self.carry_len] = *byte;
            self.carry_len += 1;
            if self.carry_len == self.carry.len() {
                let quartet = encode_base64_triplet(&self.carry, self.carry.len());
                self.write_quartet(quartet)?;
                self.carry.zeroize();
                self.carry_len = 0;
            }
        }
        Ok(source.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

pub(super) fn encode_base64_triplet(input: &[u8; 3], length: usize) -> [u8; 4] {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let value = (u32::from(input[0]) << 16) | (u32::from(input[1]) << 8) | u32::from(input[2]);
    [
        ALPHABET[((value >> 18) & 0x3f) as usize],
        ALPHABET[((value >> 12) & 0x3f) as usize],
        if length > 1 {
            ALPHABET[((value >> 6) & 0x3f) as usize]
        } else {
            b'='
        },
        if length > 2 {
            ALPHABET[(value & 0x3f) as usize]
        } else {
            b'='
        },
    ]
}

pub(super) fn crc24_update(mut crc: u32, byte: u8) -> u32 {
    crc ^= u32::from(byte) << 16;
    for _ in 0..8 {
        crc <<= 1;
        if crc & 0x0100_0000 != 0 {
            crc ^= 0x0186_4cfb;
        }
    }
    crc & 0x00ff_ffff
}
