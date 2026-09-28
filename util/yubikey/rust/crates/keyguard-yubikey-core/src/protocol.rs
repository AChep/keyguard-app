//! Yubico OTP HID protocol: 64-byte payload, command, CRC16 and three padding bytes.
//! Reference: https://developers.yubico.com/yubikey-personalization/Manuals_&_Tutorials/
use crate::{Error, Operation, RESPONSE_LENGTH, Request};
use std::{
    sync::atomic::{AtomicBool, Ordering},
    time::{Duration, Instant},
};
use zeroize::Zeroizing;

/// Dummy report write that aborts any pending operation.
const RESET_REPORT: [u8; 8] = [0, 0, 0, 0, 0, 0, 0, 0x8f];
const SLOT_WRITE_FLAG: u8 = 0x80;
const RESP_PENDING_FLAG: u8 = 0x40;
const RESP_TIMEOUT_WAIT_FLAG: u8 = 0x20;
const SEQUENCE_MASK: u8 = 0x1f;
const CRC_OK_RESIDUAL: u16 = 0xf0b8;

pub(crate) trait Transport {
    fn read(&mut self) -> Result<[u8; 8], Error>;
    fn write(&mut self, data: &[u8; 8]) -> Result<(), Error>;
}

pub(crate) fn execute(
    device: &mut impl Transport,
    request: &Request,
    canceled: &AtomicBool,
) -> Result<Vec<u8>, Error> {
    let mut session = Session {
        device,
        canceled,
        deadline: Instant::now() + Duration::from_secs(30),
    };
    let mut status = session.read_status()?;
    if status[1..4] < [2, 2, 0][..] {
        return Err(Error::Unsupported);
    }
    if status[1] == 3 {
        // NEO's arbitrator can cache the programming sequence and slot state.
        // Yubico refreshes it with an invalid, non-mutating scan-map command.
        let mut payload = [0; 64];
        payload[..51].fill(b'c');
        let refresh = session
            .send(0x12, &payload)
            .and_then(|()| session.read_update(status[4]));
        if let Err(error) = refresh
            && error != Error::Rejected
        {
            session.reset();
            return Err(error);
        }
        status = session.read_status()?;
    }
    let configured = status[5] & (1 << (request.slot - 1)) != 0;
    match &request.operation {
        Operation::Inspect => return Ok(vec![u8::from(configured)]),
        Operation::ChallengeResponse if !configured => return Err(Error::NotConfigured),
        Operation::Provision {
            overwrite: false, ..
        } if configured => return Err(Error::ConfirmationRequired),
        _ => {}
    }
    let result = session.perform(request, status[4]);
    // Reset on success, cancellation, malformed data, and I/O failure; release the handle next.
    session.reset();
    result
}

struct Session<'a, T> {
    device: &'a mut T,
    canceled: &'a AtomicBool,
    deadline: Instant,
}
impl<T: Transport> Session<'_, T> {
    fn check(&self) -> Result<(), Error> {
        if self.canceled.load(Ordering::Acquire) {
            Err(Error::Canceled)
        } else if Instant::now() >= self.deadline {
            Err(Error::Timeout)
        } else {
            Ok(())
        }
    }
    fn read(&mut self) -> Result<[u8; 8], Error> {
        self.check()?;
        self.device.read()
    }
    /// Do not interpret a pending response as configuration status or interrupt it.
    fn read_status(&mut self) -> Result<[u8; 8], Error> {
        let status = self.read()?;
        if status[7] != 0 {
            return Err(Error::Busy);
        }
        Ok(status)
    }
    fn reset(&mut self) {
        let _ = self.device.write(&RESET_REPORT);
    }
    fn wait(&self) -> Result<(), Error> {
        self.check()?;
        std::thread::sleep(Duration::from_millis(10));
        Ok(())
    }
    fn perform(&mut self, request: &Request, sequence: u8) -> Result<Vec<u8>, Error> {
        if let Operation::Provision {
            secret,
            require_touch,
            ..
        } = &request.operation
        {
            let payload = configuration(secret, *require_touch);
            self.send(if request.slot == 1 { 1 } else { 3 }, &payload)?;
            self.read_update(sequence)?;
        }
        let payload = challenge_payload(&request.challenge);
        self.send(if request.slot == 1 { 0x30 } else { 0x38 }, &payload)?;
        self.response()
    }
    fn send(&mut self, command: u8, payload: &[u8; 64]) -> Result<(), Error> {
        for report in reports(command, payload).iter() {
            let deadline = Instant::now() + Duration::from_secs(2);
            while self.read()?[7] & SLOT_WRITE_FLAG != 0 {
                if Instant::now() >= deadline {
                    return Err(Error::Timeout);
                }
                self.wait()?;
            }
            self.check()?;
            self.device.write(report)?;
        }
        Ok(())
    }
    fn read_update(&mut self, previous: u8) -> Result<(), Error> {
        loop {
            let status = self.read()?;
            if status[7] == 0 {
                return if status[4] == previous.wrapping_add(1) {
                    Ok(())
                } else {
                    Err(Error::Rejected)
                };
            }
            self.wait()?;
        }
    }
    fn response(&mut self) -> Result<Vec<u8>, Error> {
        let mut data = Zeroizing::new(Vec::with_capacity(28));
        let mut sequence = 0;
        let mut touched = false;
        loop {
            let report = self.read()?;
            let flags = report[7];
            if flags & RESP_PENDING_FLAG != 0 {
                let received = flags & SEQUENCE_MASK;
                if received == sequence {
                    if sequence >= 4 {
                        return Err(Error::Protocol);
                    }
                    data.extend_from_slice(&report[..7]);
                    sequence += 1;
                } else if received == 0 && sequence == 4 {
                    if crc16(&data[..22]) != CRC_OK_RESIDUAL {
                        return Err(Error::Protocol);
                    }
                    return Ok(data[..RESPONSE_LENGTH].to_vec());
                } else if received + 1 != sequence {
                    return Err(Error::Protocol);
                }
            } else if flags == 0 {
                return Err(if !data.is_empty() {
                    Error::Protocol
                } else if touched {
                    Error::Timeout
                } else {
                    Error::Rejected
                });
            } else {
                touched |= flags & RESP_TIMEOUT_WAIT_FLAG != 0;
            }
            self.wait()?;
        }
    }
}

fn challenge_payload(challenge: &[u8]) -> [u8; 64] {
    let padding = u8::from(challenge.last() == Some(&0));
    let mut payload = [padding; 64];
    payload[..challenge.len()].copy_from_slice(challenge);
    payload
}
fn configuration(secret: &[u8], touch: bool) -> Zeroizing<[u8; 64]> {
    let mut payload = Zeroizing::new([0; 64]);
    payload[22..38].copy_from_slice(&secret[..16]);
    payload[16..20].copy_from_slice(&secret[16..20]);
    payload[45] = 0x24;
    payload[46] = 0x40;
    payload[47] = 0x26 | if touch { 0x08 } else { 0 };
    let checksum = !crc16(&payload[..50]);
    payload[50..52].copy_from_slice(&checksum.to_le_bytes());
    payload
}
fn reports(command: u8, payload: &[u8; 64]) -> Zeroizing<Vec<[u8; 8]>> {
    let mut frame = Zeroizing::new([0; 70]);
    frame[..64].copy_from_slice(payload);
    frame[64] = command;
    frame[65..67].copy_from_slice(&crc16(payload).to_le_bytes());
    let mut reports = Zeroizing::new(Vec::new());
    for (sequence, chunk) in frame.as_chunks::<7>().0.iter().enumerate() {
        if sequence != 0 && sequence != 9 && chunk.iter().all(|&byte| byte == 0) {
            continue;
        }
        let mut report = [0; 8];
        report[..7].copy_from_slice(chunk);
        report[7] = SLOT_WRITE_FLAG | sequence as u8;
        reports.push(report);
    }
    reports
}
fn crc16(bytes: &[u8]) -> u16 {
    bytes.iter().fold(0xffff, |mut crc, byte| {
        crc ^= u16::from(*byte);
        for _ in 0..8 {
            crc = (crc >> 1) ^ if crc & 1 != 0 { 0x8408 } else { 0 };
        }
        crc
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::VecDeque;
    type Reads = VecDeque<Result<[u8; 8], Error>>;
    struct Fake {
        reads: Reads,
        writes: Vec<[u8; 8]>,
    }
    impl Fake {
        fn new<const N: usize>(reads: [Result<[u8; 8], Error>; N]) -> Self {
            Self {
                reads: reads.into(),
                writes: Vec::new(),
            }
        }
    }
    impl Transport for Fake {
        fn read(&mut self) -> Result<[u8; 8], Error> {
            self.reads.pop_front().expect("unexpected read")
        }
        fn write(&mut self, bytes: &[u8; 8]) -> Result<(), Error> {
            self.writes.push(*bytes);
            Ok(())
        }
    }
    fn status(flags: u8) -> [u8; 8] {
        [0, 5, 7, 3, 42, flags, 0, 0]
    }
    fn run(device: &mut impl Transport, wire: &[u8]) -> Result<Vec<u8>, Error> {
        execute(
            device,
            &Request::parse(wire).unwrap(),
            &AtomicBool::new(false),
        )
    }
    fn provision_wire(slot: u8, flags: u8) -> Vec<u8> {
        let mut wire = vec![3, slot, flags, 1, 1];
        wire.extend([9; 20]);
        wire
    }
    fn response_reports(corrupt: bool) -> Reads {
        let mut response = vec![7; 20];
        response.extend((!crc16(&response)).to_le_bytes());
        response.resize(28, 0);
        if corrupt {
            response[0] ^= 1;
        }
        let mut reads = VecDeque::new();
        for (i, chunk) in response.chunks(7).enumerate() {
            let mut report = [0; 8];
            report[..7].copy_from_slice(chunk);
            report[7] = 0x40 | i as u8;
            reads.push_back(Ok(report));
        }
        reads.push_back(Ok([0, 0, 0, 0, 0, 0, 0, 0x40]));
        reads
    }
    #[test]
    fn frame_boundaries_are_sent_even_for_zero_challenge() {
        assert_eq!(
            &*reports(0x30, &[0; 64]),
            &[
                [0, 0, 0, 0, 0, 0, 0, 0x80],
                [0, 0x30, 0x6b, 0x5b, 0, 0, 0, 0x89]
            ]
        );
    }
    #[test]
    fn trailing_zero_challenges_use_nonzero_padding() {
        assert_eq!(&challenge_payload(&[1, 0])[..4], &[1, 0, 1, 1]);
        assert_eq!(&challenge_payload(&[1, 2])[..4], &[1, 2, 0, 0]);
        assert_eq!(challenge_payload(&[0; 64]), [0; 64]);
    }
    #[test]
    fn provisioning_layout_and_crc_are_valid() {
        let secret: Vec<u8> = (1..=20).collect();
        let config = configuration(&secret, true);
        assert_eq!(&config[22..38], &secret[..16]);
        assert_eq!(&config[16..20], &secret[16..]);
        assert_eq!(&config[45..48], &[0x24, 0x40, 0x2e]);
        assert_eq!(crc16(&config[..52]), 0xf0b8);
    }
    #[test]
    fn inspect_checks_selected_slot() {
        for (slot, expected) in [(1, 1), (2, 0)] {
            let mut fake = Fake::new([Ok(status(1))]);
            assert_eq!(run(&mut fake, &[1, slot, 0, 0]), Ok(vec![expected]));
            assert!(fake.writes.is_empty());
        }
    }
    #[test]
    fn overwrite_requires_confirmation_before_any_write() {
        let mut fake = Fake::new([Ok(status(2))]);
        assert_eq!(
            run(&mut fake, &provision_wire(2, 0)),
            Err(Error::ConfirmationRequired)
        );
        assert!(fake.writes.is_empty());
    }
    #[test]
    fn unconfigured_slot_never_sends_challenge() {
        let mut fake = Fake::new([Ok(status(0))]);
        assert_eq!(run(&mut fake, &[2, 2, 0, 1, 1]), Err(Error::NotConfigured));
        assert!(fake.writes.is_empty());
    }
    #[test]
    fn crc_checked_response_and_reset() {
        let mut fake = Fake::new([Ok(status(2)); 3]);
        fake.reads.extend(response_reports(false));
        assert_eq!(run(&mut fake, &[2, 2, 0, 1, 1]), Ok(vec![7; 20]));
        assert_eq!(fake.writes.last(), Some(&RESET_REPORT));
    }
    #[test]
    fn disconnect_resets_and_propagates_error() {
        let mut fake = Fake::new([Ok(status(2)), Err(Error::Io)]);
        assert_eq!(run(&mut fake, &[2, 2, 0, 1, 1]), Err(Error::Io));
        assert_eq!(fake.writes.last(), Some(&RESET_REPORT));
    }
    #[test]
    fn corrupt_response_is_rejected_and_resets_device() {
        let mut fake = Fake::new([Ok(status(2)); 3]);
        fake.reads.extend(response_reports(true));
        assert_eq!(run(&mut fake, &[2, 2, 0, 1, 1]), Err(Error::Protocol));
        assert_eq!(fake.writes.last(), Some(&RESET_REPORT));
    }
    #[test]
    fn provision_verifies_on_same_connection_for_both_slots() {
        for slot in 1..=2 {
            let config = configuration(&[9; 20], true);
            let expected = reports(if slot == 1 { 1 } else { 3 }, &config);
            let mut fake = Fake::new([Ok(status(0))]);
            fake.reads
                .extend((0..expected.len()).map(|_| Ok(status(0))));
            let mut updated = status(1 << (slot - 1));
            updated[4] += 1;
            fake.reads.extend([Ok(updated); 3]); // Write acknowledgement and two challenge packets.
            fake.reads.extend(response_reports(false));
            assert_eq!(run(&mut fake, &provision_wire(slot, 2)), Ok(vec![7; 20]));
            assert_eq!(&fake.writes[..expected.len()], expected.as_slice());
            assert_eq!(
                fake.writes[expected.len() + 1][1],
                if slot == 1 { 0x30 } else { 0x38 }
            );
            assert!(fake.reads.is_empty());
        }
    }
    #[test]
    fn pending_operation_is_not_overwritten() {
        let mut busy = status(0);
        busy[7] = 0x20;
        let mut fake = Fake::new([Ok(busy)]);
        assert_eq!(run(&mut fake, &[1, 2, 0, 0]), Err(Error::Busy));
        assert!(fake.writes.is_empty());
    }
    #[test]
    fn cancellation_while_waiting_for_touch_resets_device() {
        struct CancelOnTouch<'a> {
            inner: Fake,
            canceled: &'a AtomicBool,
        }
        impl Transport for CancelOnTouch<'_> {
            fn read(&mut self) -> Result<[u8; 8], Error> {
                let report = self.inner.read()?;
                if report[7] == 0x20 {
                    self.canceled.store(true, Ordering::Release);
                }
                Ok(report)
            }
            fn write(&mut self, data: &[u8; 8]) -> Result<(), Error> {
                self.inner.write(data)
            }
        }
        let canceled = AtomicBool::new(false);
        let mut fake = CancelOnTouch {
            inner: Fake::new([
                Ok(status(2)),
                Ok(status(2)),
                Ok(status(2)),
                Ok([0, 0, 0, 0, 0, 0, 0, 0x20]),
            ]),
            canceled: &canceled,
        };
        assert_eq!(
            execute(
                &mut fake,
                &Request::parse(&[2, 2, 0, 1, 1]).unwrap(),
                &canceled
            ),
            Err(Error::Canceled)
        );
        assert_eq!(fake.inner.writes.last(), Some(&RESET_REPORT));
    }
    #[test]
    fn neo_refreshes_cached_slot_status_before_overwrite_check() {
        let mut stale = status(0);
        stale[1] = 3;
        let mut payload = [0; 64];
        payload[..51].fill(b'c');
        let refresh = reports(0x12, &payload);
        let mut fake = Fake::new([Ok(stale)]);
        fake.reads.extend((0..refresh.len()).map(|_| Ok(stale)));
        let mut fresh = stale;
        fresh[5] = 2;
        fake.reads.extend([Ok(fresh); 2]);
        assert_eq!(
            run(&mut fake, &provision_wire(2, 0)),
            Err(Error::ConfirmationRequired)
        );
        assert_eq!(fake.writes.as_slice(), refresh.as_slice());
    }
}
