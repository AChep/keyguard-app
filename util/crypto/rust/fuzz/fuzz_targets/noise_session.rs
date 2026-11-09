#![no_main]

//! Runs both sides of a Noise handshake, then their transports, in one
//! process. The input picks, step by step, which side writes or reads, and
//! whether the network drops, reorders, replays, tampers with or injects a
//! message. Handshakes finish, so transports carry real traffic.
//!
//! A bug is a forged, replayed or reordered transport message that decrypts,
//! two finished sides that disagree, a session of one kind that answers a
//! request of the other, or a handshake that no longer opens. Every handle is
//! closed, so the process-wide registry and the Noise session cap cannot fill
//! across iterations.

use std::{collections::VecDeque, sync::OnceLock};

use keyguard_crypto_core::{
    PROTOCOL_VERSION,
    protocol::{
        NativeErrorCode, NativeRequest, NativeResponse, NativeStreamOpenRequest,
        NoiseHandshakeReadRequest, NoiseHandshakeStatusRequest, NoiseHandshakeStatusResult,
        NoiseHandshakeStreamOpenRequest, NoiseHandshakeWriteRequest, NoiseIntoTransportRequest,
        NoiseKeyPairRequest, NoiseKeyPairResult, NoisePattern, NoiseTransportReadRequest,
        NoiseTransportWriteRequest, native_request, native_response, native_stream_open_request,
    },
};
use libfuzzer_sys::fuzz_target;
use prost::Message;

const INITIATOR_KEY: [u8; 32] = [0x11; 32];
const RESPONDER_KEY: [u8; 32] = [0x22; 32];
const PSK: [u8; 32] = [0x42; 32];
const PROLOGUE: &[u8] = b"keyguard-noise-fuzz";
/// Bounds the work of one input.
const MAX_STEPS: usize = 256;
/// One past the largest Noise message: rejected, and the session goes on.
const OVERSIZED: usize = 65_536;

/// The public keys of [`INITIATOR_KEY`] and [`RESPONDER_KEY`].
static PUBLIC_KEYS: OnceLock<(Vec<u8>, Vec<u8>)> = OnceLock::new();

/// The result of a successful response.
fn result_of(response: &[u8]) -> Option<native_response::Result> {
    let response = NativeResponse::decode(response).expect("the core answers a response");
    let status = response.status.expect("a response has a status");
    if status.code != NativeErrorCode::Ok as i32 {
        return None;
    }
    response.result
}

fn invoke(operation: native_request::Operation) -> Option<native_response::Result> {
    let request = NativeRequest {
        protocol_version: PROTOCOL_VERSION,
        operation: Some(operation),
    };
    result_of(&keyguard_crypto_core::call(&request.encode_to_vec()))
}

fn bytes_of(result: Option<native_response::Result>) -> Option<Vec<u8>> {
    match result? {
        native_response::Result::BytesValue(bytes) => Some(bytes),
        other => panic!("expected bytes, got {other:?}"),
    }
}

fn handle_of(result: Option<native_response::Result>) -> Option<u64> {
    match result? {
        native_response::Result::Uint64Value(handle) => Some(handle),
        other => panic!("expected a handle, got {other:?}"),
    }
}

fn public_key(private_key: &[u8]) -> Vec<u8> {
    let encoded = bytes_of(invoke(native_request::Operation::NoiseKeyPair(
        NoiseKeyPairRequest {
            private_key: private_key.to_vec(),
        },
    )))
    .expect("a 32-byte private key derives a key pair");
    // The result wipes its private key on drop, so it can't be moved out of.
    NoiseKeyPairResult::decode(encoded.as_slice())
        .expect("a key pair result")
        .public_key
        .clone()
}

fn open(pattern: NoisePattern, initiator: bool, local: &[u8], remote: &[u8], psk: &[u8]) -> u64 {
    let request = NativeStreamOpenRequest {
        protocol_version: PROTOCOL_VERSION,
        operation: Some(native_stream_open_request::Operation::NoiseHandshake(
            NoiseHandshakeStreamOpenRequest {
                pattern: pattern as i32,
                initiator,
                local_private_key: local.to_vec(),
                remote_public_key: remote.to_vec(),
                psk: psk.to_vec(),
                prologue: PROLOGUE.to_vec(),
            },
        )),
    };
    // Failing here means an earlier iteration leaked a session.
    handle_of(result_of(&keyguard_crypto_core::stream_open(
        &request.encode_to_vec(),
    )))
    .expect("a well-formed handshake opens")
}

/// The input, a byte or a slice at a time; zeros once it runs out.
struct Input<'a>(&'a [u8]);

impl Input<'_> {
    fn byte(&mut self) -> u8 {
        let (&first, rest) = self.0.split_first().unwrap_or((&0, &[]));
        self.0 = rest;
        first
    }

    /// A length byte, then that many bytes. The largest length asks for an
    /// oversized payload instead.
    fn payload(&mut self) -> Vec<u8> {
        match self.byte() {
            u8::MAX => vec![0; OVERSIZED],
            len => {
                let (taken, rest) = self.0.split_at(usize::from(len).min(self.0.len()));
                self.0 = rest;
                taken.to_vec()
            }
        }
    }
}

/// A message on its way, which the network may change.
#[derive(Clone)]
struct Envelope {
    message: Vec<u8>,
}

/// An immutable record of a transport message as its sender wrote it.
struct SealedMessage {
    message: Vec<u8>,
    sequence: u64,
    payload: Vec<u8>,
}

struct Side {
    /// The handshake, then the transport it turned into.
    handle: u64,
    written: u64,
    read: u64,
    /// The handshake hash and remote static key, once the handshake turned
    /// into a transport.
    finished: Option<(Vec<u8>, Vec<u8>)>,
    /// Messages on their way to this side.
    inbox: VecDeque<Envelope>,
    /// Original outgoing transport messages, separate from the network.
    sealed: Vec<SealedMessage>,
}

impl Side {
    fn new(handle: u64) -> Self {
        Self {
            handle,
            written: 0,
            read: 0,
            finished: None,
            inbox: VecDeque::new(),
            sealed: Vec::new(),
        }
    }

    fn is_transport(&self) -> bool {
        self.finished.is_some()
    }

    fn write(&mut self, payload: Vec<u8>) -> Option<Envelope> {
        let handle = self.handle;
        if !self.is_transport() {
            let message = bytes_of(invoke(native_request::Operation::NoiseHandshakeWrite(
                NoiseHandshakeWriteRequest { handle, payload },
            )))?;
            return Some(Envelope { message });
        }
        let message = bytes_of(invoke(native_request::Operation::NoiseTransportWrite(
            NoiseTransportWriteRequest {
                handle,
                payload: payload.clone(),
            },
        )))?;
        self.sealed.push(SealedMessage {
            message: message.clone(),
            sequence: self.written,
            payload,
        });
        self.written += 1;
        Some(Envelope { message })
    }

    fn read(&mut self, peer: &Self) {
        let Some(envelope) = self.inbox.pop_front() else {
            return;
        };
        let handle = self.handle;
        if !self.is_transport() {
            let _ = invoke(native_request::Operation::NoiseHandshakeRead(
                NoiseHandshakeReadRequest {
                    handle,
                    message: envelope.message,
                },
            ));
            return;
        }
        let expected = peer
            .sealed
            .iter()
            .find(|sealed| sealed.sequence == self.read && sealed.message == envelope.message)
            .map(|sealed| sealed.payload.as_slice());
        let opened = bytes_of(invoke(native_request::Operation::NoiseTransportRead(
            NoiseTransportReadRequest {
                handle,
                message: envelope.message,
            },
        )));
        if let Some(payload) = opened {
            // Only the next authentic ciphertext decrypts, even if the network
            // restored or injected a copy of it.
            assert_eq!(
                expected,
                Some(payload.as_slice()),
                "a forged, replayed or reordered message decrypted",
            );
            self.read += 1;
        }
    }

    fn status(&self) -> Option<NoiseHandshakeStatusResult> {
        let encoded = bytes_of(invoke(native_request::Operation::NoiseHandshakeStatus(
            NoiseHandshakeStatusRequest {
                handle: self.handle,
            },
        )))?;
        Some(NoiseHandshakeStatusResult::decode(encoded.as_slice()).expect("a status result"))
    }

    /// Turns a finished handshake into its transport. With `force`, tries an
    /// unfinished one too, which spends it.
    fn finish(&mut self, force: bool) {
        if self.is_transport() {
            return;
        }
        let status = self.status().filter(|status| status.finished);
        if status.is_none() && !force {
            return;
        }
        let transport = handle_of(invoke(native_request::Operation::NoiseIntoTransport(
            NoiseIntoTransportRequest {
                handle: self.handle,
            },
        )));
        if let Some(transport) = transport {
            let status = status.expect("only a finished handshake turns into a transport");
            self.finished = Some((status.handshake_hash, status.remote_static_key));
            self.handle = transport;
        }
    }

    /// Sends a request of the wrong kind, or closes the session while it is
    /// still in use.
    fn misuse(&self, kind: u8) {
        let handle = self.handle;
        match kind % 3 {
            0 if self.is_transport() => {
                let handshake = invoke(native_request::Operation::NoiseHandshakeWrite(
                    NoiseHandshakeWriteRequest {
                        handle,
                        payload: Vec::new(),
                    },
                ));
                assert!(handshake.is_none(), "a transport wrote a handshake message");
                assert!(
                    self.status().is_none(),
                    "a transport reported a handshake status"
                );
            }
            0 => {
                let transport = invoke(native_request::Operation::NoiseTransportWrite(
                    NoiseTransportWriteRequest {
                        handle,
                        payload: Vec::new(),
                    },
                ));
                assert!(transport.is_none(), "a handshake wrote a transport message");
            }
            1 => {
                let _ = keyguard_crypto_core::stream_close(handle);
            }
            _ => {
                let _ = self.status();
            }
        }
    }
}

fuzz_target!(|data: &[u8]| {
    let mut input = Input(data);
    let (initiator_public, responder_public) =
        PUBLIC_KEYS.get_or_init(|| (public_key(&INITIATOR_KEY), public_key(&RESPONDER_KEY)));

    // Bit 0 picks the pattern; bit 1 gives the responder a wrong pre-shared
    // key or pins a wrong initiator key.
    let setup = input.byte();
    let wrong = setup & 2 != 0;
    let (initiator, responder) = if setup & 1 == 0 {
        let pinned = if wrong {
            responder_public
        } else {
            initiator_public
        };
        (
            open(
                NoisePattern::KkHfs,
                true,
                &INITIATOR_KEY,
                responder_public,
                &[],
            ),
            open(NoisePattern::KkHfs, false, &RESPONDER_KEY, pinned, &[]),
        )
    } else {
        let mut psk = PSK;
        psk[0] ^= u8::from(wrong);
        (
            open(NoisePattern::XxPsk3, true, &INITIATOR_KEY, &[], &PSK),
            open(NoisePattern::XxPsk3, false, &RESPONDER_KEY, &[], &psk),
        )
    };
    let mut sides = [Side::new(initiator), Side::new(responder)];

    for _ in 0..MAX_STEPS {
        if input.0.is_empty() {
            break;
        }
        let action = input.byte();
        // Bit 0 picks the side that acts, or whose inbox the network touches.
        let [initiator, responder] = &mut sides;
        let (me, peer) = if action & 1 == 0 {
            (initiator, responder)
        } else {
            (responder, initiator)
        };
        match (action >> 1) % 10 {
            0 => {
                let payload = input.payload();
                if let Some(envelope) = me.write(payload) {
                    peer.inbox.push_back(envelope);
                }
            }
            1 => me.read(peer),
            // An honest round in one step, so handshakes finish within a short
            // run: an empty message, read at once, and finished sides turned
            // into transports.
            8 => {
                if let Some(envelope) = me.write(Vec::new()) {
                    peer.inbox.push_back(envelope);
                    peer.read(me);
                }
                me.finish(false);
                peer.finish(false);
            }
            2 => {
                let at = usize::from(u16::from_le_bytes([input.byte(), input.byte()]));
                let mask = input.byte() | 1;
                if let Some(envelope) = me.inbox.front_mut()
                    && !envelope.message.is_empty()
                {
                    let at = at % envelope.message.len();
                    envelope.message[at] ^= mask;
                }
            }
            3 => {
                me.inbox.pop_front();
            }
            4 => {
                if me.inbox.len() > 1 {
                    me.inbox.swap(0, 1);
                }
            }
            5 => {
                if let Some(envelope) = me.inbox.front().cloned() {
                    me.inbox.push_back(envelope);
                }
            }
            6 => {
                let message = input.payload();
                me.inbox.push_back(Envelope { message });
            }
            7 => {
                let force = input.byte() & 1 != 0;
                me.finish(force);
            }
            _ => me.misuse(input.byte()),
        }
    }

    // Two finished sides saw the same transcript and learned each other's key.
    if let (Some((left_hash, left_remote)), Some((right_hash, right_remote))) =
        (&sides[0].finished, &sides[1].finished)
    {
        assert_eq!(
            left_hash, right_hash,
            "the sides disagree on the handshake hash"
        );
        assert_eq!(
            left_remote, responder_public,
            "the initiator learned a wrong key"
        );
        assert_eq!(
            right_remote, initiator_public,
            "the responder learned a wrong key"
        );
    }

    // A side holds its transport, or the handshake that never turned into
    // one; turning a handshake into a transport closes it either way. Closed
    // handles make this a no-op for some.
    for side in sides {
        let _ = keyguard_crypto_core::stream_close(side.handle);
    }
});
