//! Noise handshakes and transports of device-to-device sync.
//!
//! [`snow`] runs the two Keyguard patterns over the primitives of this crate
//! (see [`resolver`]); snow's own primitives stay disabled.
//!
//! - `Noise_XXpsk3_25519_ChaChaPoly_SHA256` pairs two devices. Each side learns
//!   the other's static key, and a pre-shared key authenticates the exchange.
//! - `Noise_KKhfs_25519+Kyber1024_ChaChaPoly_SHA256` runs sessions between
//!   paired devices. Each side pins the other's static key. The `hfs` modifier
//!   adds an ephemeral ML-KEM-1024 exchange to the Diffie-Hellman ones (snow
//!   names it Kyber1024, see [`resolver`]), so the session keys hold as long as
//!   either X25519 or ML-KEM does: a recorded session stays sealed against a
//!   quantum computer.
//!
//! Handshakes and transports live in the generation-tagged session registry,
//! at most [`MAX_LIVE_SESSIONS`] at once, so a misbehaving peer cannot crowd
//! out the vault's streaming sessions. Every call computes over at most one
//! [`MAX_MESSAGE_BYTES`] message. A read or write that fails past argument
//! validation ends its session and wipes its keys; the handle stays until it
//! is closed. Kotlin owns framing, timeouts and retries. A transport never
//! rekeys; Kotlin replaces it with a new handshake.

#[cfg(test)]
mod dispatch_tests;
mod resolver;

use std::{
    hint::black_box,
    sync::atomic::{AtomicUsize, Ordering},
};

use aws_lc_rs::rand::{SecureRandom, SystemRandom};
use prost::Message;
use snow::{Builder, HandshakeState, TransportState, params::NoiseParams};
use x25519_dalek::{PublicKey, StaticSecret};
use zeroize::Zeroizing;

use crate::{
    primitives::PrimitiveError,
    protocol::{
        NoiseHandshakeStatusResult, NoiseHandshakeStreamOpenRequest, NoiseKeyPairResult,
        NoisePattern,
    },
    sessions::SessionError,
};

/// Length of a Curve25519 key and of a ChaCha20-Poly1305 key.
const KEY_BYTES: usize = 32;
/// Length of an ML-KEM-1024 encapsulation key, and of its ciphertext.
const KEM_BYTES: usize = crate::ml_kem::PUBLIC_KEY_BYTES;
/// Length of a pre-shared key.
const PSK_BYTES: usize = 32;
/// Length of a ChaCha20-Poly1305 tag.
const TAG_BYTES: usize = 16;
/// Largest Noise message.
const MAX_MESSAGE_BYTES: usize = 65_535;
/// Largest number of bytes a handshake message of either pattern adds to its
/// payload: an ephemeral key, an encrypted ML-KEM key or ciphertext, and two
/// tags. An encrypted static key of XXpsk3 is shorter than the ML-KEM part.
const HANDSHAKE_OVERHEAD_BYTES: usize = KEY_BYTES + KEM_BYTES + TAG_BYTES + TAG_BYTES;
/// Largest payload of a handshake message.
const MAX_HANDSHAKE_PAYLOAD_BYTES: usize = MAX_MESSAGE_BYTES - HANDSHAKE_OVERHEAD_BYTES;
/// Largest payload of a transport message.
const MAX_TRANSPORT_PAYLOAD_BYTES: usize = MAX_MESSAGE_BYTES - TAG_BYTES;
/// Largest prologue.
const MAX_PROLOGUE_BYTES: usize = MAX_MESSAGE_BYTES;
/// Most handshakes and transports live at once.
pub(crate) const MAX_LIVE_SESSIONS: usize = 256;
/// Position of the PSK token in `XXpsk3`.
const PSK_LOCATION: u8 = 3;

static LIVE_SESSIONS: AtomicUsize = AtomicUsize::new(0);

/// Why a session step failed, and whether the session survives it.
pub(crate) enum Failure {
    /// Rejected before the state was touched; the session goes on.
    Rejected(SessionError),
    /// The state failed; the session ends and its keys are wiped.
    Ended(SessionError),
}

/// Maps a snow failure onto the session taxonomy.
fn classify(error: snow::Error) -> SessionError {
    match error {
        // A wrong PSK or pinned key, a low-order key, or a forged, replayed,
        // reordered or truncated message.
        snow::Error::Decrypt | snow::Error::Dh => SessionError::AuthenticationFailed,
        // A malformed or out-of-turn message, or an exhausted nonce.
        snow::Error::Input | snow::Error::State(_) => SessionError::CryptoFailure,
        // Pattern, initialization and RNG failures: the arguments were
        // validated, so these are the library's own.
        _ => SessionError::Internal,
    }
}

fn ended(error: snow::Error) -> Failure {
    Failure::Ended(classify(error))
}

/// Writes one message of at most `max_payload` bytes, which grows by at most
/// `overhead` bytes.
fn write_message(
    payload: &[u8],
    max_payload: usize,
    overhead: usize,
    write: impl FnOnce(&mut [u8]) -> Result<usize, Failure>,
) -> Result<Vec<u8>, Failure> {
    if payload.len() > max_payload {
        return Err(Failure::Rejected(SessionError::InvalidArgument));
    }
    let mut message = vec![0; payload.len() + overhead];
    let length = write(&mut message)?;
    message.truncate(length);
    Ok(message)
}

/// Reads one message and returns its payload. The payload is decrypted into
/// zeroizing scratch first, so a failure returns nothing.
fn read_message(
    message: &[u8],
    read: impl FnOnce(&mut [u8]) -> Result<usize, Failure>,
) -> Result<Vec<u8>, Failure> {
    if message.len() > MAX_MESSAGE_BYTES {
        return Err(Failure::Rejected(SessionError::InvalidArgument));
    }
    let mut payload = Zeroizing::new(vec![0; message.len()]);
    let length = read(&mut payload)?;
    payload.truncate(length);
    Ok(std::mem::take(&mut *payload))
}

/// One of the [`MAX_LIVE_SESSIONS`] Noise sessions, released on drop.
struct LiveSession {
    counter: &'static AtomicUsize,
}

impl LiveSession {
    fn acquire(counter: &'static AtomicUsize) -> Result<Self, SessionError> {
        counter
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |live| {
                (live < MAX_LIVE_SESSIONS).then_some(live + 1)
            })
            .map(|_| Self { counter })
            .map_err(|_| SessionError::ResourceLimit)
    }
}

impl Drop for LiveSession {
    fn drop(&mut self) {
        self.counter.fetch_sub(1, Ordering::AcqRel);
    }
}

/// Generates a static key pair, or derives the public key of a 32-byte
/// `private_key`.
pub(crate) fn key_pair(private_key: Vec<u8>) -> Result<Vec<u8>, PrimitiveError> {
    let private_key = Zeroizing::new(private_key);
    let mut seed = Zeroizing::new([0_u8; KEY_BYTES]);
    match private_key.len() {
        0 => SystemRandom::new()
            .fill(seed.as_mut())
            .map_err(|_| PrimitiveError::CryptoFailure)?,
        KEY_BYTES => seed.copy_from_slice(&private_key),
        _ => return Err(PrimitiveError::InvalidArgument),
    }
    let public_key = PublicKey::from(&StaticSecret::from(*seed));
    Ok(NoiseKeyPairResult {
        private_key: seed.to_vec(),
        public_key: public_key.as_bytes().to_vec(),
    }
    .encode_to_vec())
}

fn protocol_name(pattern: NoisePattern) -> Option<&'static str> {
    match pattern {
        NoisePattern::XxPsk3 => Some("Noise_XXpsk3_25519_ChaChaPoly_SHA256"),
        NoisePattern::KkHfs => Some("Noise_KKhfs_25519+Kyber1024_ChaChaPoly_SHA256"),
        NoisePattern::Unspecified => None,
    }
}

/// A handshake in progress.
pub(crate) struct Handshake {
    /// Taken only by [`Handshake::take_transport`]; the live slot moves to the
    /// transport.
    state: Option<(HandshakeState, LiveSession)>,
    /// Whether snow holds a PSK that must be wiped.
    psk: bool,
}

/// Starts a handshake.
///
/// XXpsk3 takes a PSK and no remote key; KKhfs takes the pinned remote key and
/// no PSK. An empty key counts as absent.
pub(crate) fn open_handshake(
    mut request: NoiseHandshakeStreamOpenRequest,
) -> Result<Handshake, SessionError> {
    let local_private_key = Zeroizing::new(std::mem::take(&mut request.local_private_key));
    let psk_bytes = Zeroizing::new(std::mem::take(&mut request.psk));
    let pattern =
        NoisePattern::try_from(request.pattern).map_err(|_| SessionError::InvalidArgument)?;
    let name = protocol_name(pattern).ok_or(SessionError::InvalidArgument)?;
    if local_private_key.len() != KEY_BYTES || request.prologue.len() > MAX_PROLOGUE_BYTES {
        return Err(SessionError::InvalidArgument);
    }
    let remote_public_key = request.remote_public_key.as_slice();
    let psk: Option<&[u8; PSK_BYTES]> = match pattern {
        NoisePattern::XxPsk3 if remote_public_key.is_empty() => Some(
            psk_bytes
                .as_slice()
                .try_into()
                .map_err(|_| SessionError::InvalidArgument)?,
        ),
        NoisePattern::KkHfs if remote_public_key.len() == KEY_BYTES && psk_bytes.is_empty() => None,
        _ => return Err(SessionError::InvalidArgument),
    };
    let live = LiveSession::acquire(&LIVE_SESSIONS)?;
    let builder = builder(
        name,
        &local_private_key,
        remote_public_key,
        psk,
        &request.prologue,
    )?;
    let state = if request.initiator {
        builder.build_initiator()
    } else {
        builder.build_responder()
    }
    .map_err(classify)?;
    Ok(Handshake {
        state: Some((state, live)),
        psk: psk.is_some(),
    })
}

/// A builder over the primitives of [`resolver`].
fn builder<'a>(
    name: &str,
    local_private_key: &'a [u8],
    remote_public_key: &'a [u8],
    psk: Option<&'a [u8; PSK_BYTES]>,
    prologue: &'a [u8],
) -> Result<Builder<'a>, SessionError> {
    let params: NoiseParams = name.parse().map_err(|_| SessionError::Internal)?;
    let builder = Builder::with_resolver(params, Box::new(resolver::Resolver))
        .local_private_key(local_private_key)
        .and_then(|builder| builder.prologue(prologue))
        .and_then(|builder| match psk {
            Some(psk) => builder.psk(PSK_LOCATION, psk),
            None => builder.remote_public_key(remote_public_key),
        })
        .map_err(|_| SessionError::Internal)?;
    Ok(builder)
}

impl Handshake {
    fn state(&mut self) -> Result<&mut HandshakeState, Failure> {
        self.state
            .as_mut()
            .map(|(state, _)| state)
            .ok_or(Failure::Rejected(SessionError::InvalidSession))
    }

    /// Overwrites snow's copy of the PSK in place.
    fn wipe_psk(&mut self) {
        if self.psk
            && let Some((state, _)) = self.state.as_mut()
        {
            // Cannot fail: the key length and location are the ones the
            // builder accepted.
            let _ = state.set_psk(usize::from(PSK_LOCATION), &[0; PSK_BYTES]);
            black_box(&*state);
        }
    }

    /// Writes the next handshake message carrying `payload`.
    pub(crate) fn write(&mut self, payload: &[u8]) -> Result<Vec<u8>, Failure> {
        write_message(
            payload,
            MAX_HANDSHAKE_PAYLOAD_BYTES,
            HANDSHAKE_OVERHEAD_BYTES,
            |message| self.state()?.write_message(payload, message).map_err(ended),
        )
    }

    /// Reads the next handshake message and returns its payload.
    pub(crate) fn read(&mut self, message: &[u8]) -> Result<Vec<u8>, Failure> {
        read_message(message, |payload| {
            self.state()?.read_message(message, payload).map_err(ended)
        })
    }

    /// Reports whether the handshake is finished, the remote static key once
    /// known, and the handshake hash once finished.
    pub(crate) fn status(&mut self) -> Result<Vec<u8>, Failure> {
        let state = self.state()?;
        let finished = state.is_handshake_finished();
        // Binding to the hash of an unfinished handshake binds to nothing.
        let handshake_hash = if finished {
            state.get_handshake_hash().to_vec()
        } else {
            Vec::new()
        };
        Ok(NoiseHandshakeStatusResult {
            finished,
            remote_static_key: state
                .get_remote_static()
                .map(<[u8]>::to_vec)
                .unwrap_or_default(),
            handshake_hash,
        }
        .encode_to_vec())
    }

    /// Turns a finished handshake into its transport. The handshake is spent
    /// whatever the outcome.
    pub(crate) fn take_transport(&mut self) -> Result<Transport, SessionError> {
        self.wipe_psk();
        let (state, live) = self.state.take().ok_or(SessionError::InvalidSession)?;
        if !state.is_handshake_finished() {
            return Err(SessionError::CryptoFailure);
        }
        let state = state.into_transport_mode().map_err(classify)?;
        Ok(Transport { state, _live: live })
    }
}

impl Drop for Handshake {
    fn drop(&mut self) {
        self.wipe_psk();
    }
}

/// An established transport.
pub(crate) struct Transport {
    state: TransportState,
    _live: LiveSession,
}

impl Transport {
    /// Encrypts one message.
    pub(crate) fn write(&mut self, payload: &[u8]) -> Result<Vec<u8>, Failure> {
        write_message(payload, MAX_TRANSPORT_PAYLOAD_BYTES, TAG_BYTES, |message| {
            self.state.write_message(payload, message).map_err(ended)
        })
    }

    /// Decrypts the next message and returns its payload.
    pub(crate) fn read(&mut self, message: &[u8]) -> Result<Vec<u8>, Failure> {
        read_message(message, |payload| {
            self.state.read_message(message, payload).map_err(ended)
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex(value: &str) -> Vec<u8> {
        (0..value.len())
            .step_by(2)
            .map(|index| u8::from_str_radix(&value[index..index + 2], 16).expect("test hex"))
            .collect()
    }

    struct Vector {
        name: &'static str,
        psk: Option<&'static str>,
        handshake_hash: &'static str,
        /// The payload and the ciphertext of every message, the initiator
        /// sending the even ones.
        messages: [(&'static str, &'static str); 5],
    }

    const PROLOGUE: &str = "4a6f686e2047616c74";
    const INIT_STATIC: &str = "e61ef9919cde45dd5f82166404bd08e38bceb5dfdfded0a34c8df7ed542214d1";
    const INIT_EPHEMERAL: &str = "893e28b9dc6ca8d611ab664754b8ceb7bac5117349a4439a6b0569da977c464a";
    const RESP_STATIC: &str = "4a3acbfdb163dec651dfa3194dece676d437029c62a408b4c5ea9114246e4893";
    const RESP_EPHEMERAL: &str = "bbdb4cdbd309f1a1f2e1456967fe288cadd6f712d65dc7b7793d5e63da6b375b";

    /// From the Cacophony test vectors, as shipped with snow 0.10.0.
    const VECTORS: [Vector; 2] = [
        Vector {
            name: "Noise_KK_25519_ChaChaPoly_SHA256",
            psk: None,
            handshake_hash: "24c6b51ecb76277140ca018b5985bc9f03de321dae2d34dcae433dafef0131d9",
            messages: [
                (
                    "4c756477696720766f6e204d69736573",
                    "ca35def5ae56cec33dc2036731ab14896bc4c75dbb07a61f879f8e3afa4c79440177015efc1fe7a37c629af7120a96274e6ab7afcc9261901d0e09ae32a5bb96",
                ),
                (
                    "4d757272617920526f746862617264",
                    "95ebc60d2b1fa672c1f46a8aa265ef51bfe38e7ccb39ec5be34069f144808843b274d3429adc47ca093ba63ef90f8da89fda108db471dccfa4894aa7b00003",
                ),
                (
                    "462e20412e20486179656b",
                    "966b05bc69ec01b8454d3160a214e6f24a3d884eb31ec2408af63f",
                ),
                (
                    "4361726c204d656e676572",
                    "0ad887fba4f611bbb4afe44ba3556b8164332ca7d5934634d63d80",
                ),
                (
                    "4a65616e2d426170746973746520536179",
                    "012b28ae646ae7830e2c5472cb023eab071c1db3d8413ec69b513b83832f974c2d",
                ),
            ],
        },
        Vector {
            name: "Noise_XXpsk3_25519_ChaChaPoly_SHA256",
            psk: Some("54686973206973206d7920417573747269616e20706572737065637469766521"),
            handshake_hash: "a477edf6a131bbdb54707f6ea30eab6cd935d9b560f0e5fd1f053a95a99669fb",
            messages: [
                (
                    "4c756477696720766f6e204d69736573",
                    "ca35def5ae56cec33dc2036731ab14896bc4c75dbb07a61f879f8e3afa4c7944c9f5ff0e8079630cb7e270c20bbf480821b77a384a645c71a2fd9b3db1c16a5f",
                ),
                (
                    "4d757272617920526f746862617264",
                    "95ebc60d2b1fa672c1f46a8aa265ef51bfe38e7ccb39ec5be34069f144808843b123def17f71e6ae8e57e0e1dec5949c5f7415c6f33517398747d821a06dc23ad430aa1fd7381d46195c378a819fd574425462cbb2d4ca339e738a0b7001dc91423fbf55a99af0c6f1df21012ceb2f",
                ),
                (
                    "462e20412e20486179656b",
                    "52187316111b118d4c060364f7b975dc0809b2590779aff2d63113c564f11744493384db7bf32d5ae6686df6ab06d508d2e07caaf1d6afc010b978735fc78900e71ae1d314130d042e729a",
                ),
                (
                    "4361726c204d656e676572",
                    "eaedc672d4c21e0e2955758756fb98f194c4e90d5deb5b6cf30b27",
                ),
                (
                    "4a65616e2d426170746973746520536179",
                    "522d543c5fe799d09a3d9da7ff54d0dc03c8af1dc7751d2ff708339d2290943e98",
                ),
            ],
        },
    ];

    static TEST_SESSIONS: AtomicUsize = AtomicUsize::new(0);

    /// A handshake of `vector` with its fixed ephemeral key.
    fn vector_handshake(vector: &Vector, initiator: bool) -> Handshake {
        let (local, remote, ephemeral) = if initiator {
            (INIT_STATIC, RESP_STATIC, INIT_EPHEMERAL)
        } else {
            (RESP_STATIC, INIT_STATIC, RESP_EPHEMERAL)
        };
        let local = hex(local);
        let remote_public_key = match vector.psk {
            Some(_) => Vec::new(),
            None => {
                let secret: [u8; KEY_BYTES] = hex(remote).try_into().expect("key");
                PublicKey::from(&StaticSecret::from(secret))
                    .as_bytes()
                    .to_vec()
            }
        };
        let psk: Option<[u8; PSK_BYTES]> = vector.psk.map(|psk| hex(psk).try_into().expect("psk"));
        let prologue = hex(PROLOGUE);
        let ephemeral = hex(ephemeral);
        let builder = builder(
            vector.name,
            &local,
            &remote_public_key,
            psk.as_ref(),
            &prologue,
        )
        .expect("builder")
        .fixed_ephemeral_key_for_testing_only(&ephemeral);
        let state = if initiator {
            builder.build_initiator()
        } else {
            builder.build_responder()
        }
        .expect("handshake");
        Handshake {
            state: Some((state, LiveSession::acquire(&TEST_SESSIONS).expect("live"))),
            psk: psk.is_some(),
        }
    }

    fn status(handshake: &mut Handshake) -> NoiseHandshakeStatusResult {
        let Ok(encoded) = handshake.status() else {
            panic!("status");
        };
        NoiseHandshakeStatusResult::decode(encoded.as_slice()).expect("status result")
    }

    fn sealed(transport: &mut Transport, payload: &[u8]) -> Vec<u8> {
        let Ok(message) = transport.write(payload) else {
            panic!("transport write");
        };
        message
    }

    #[test]
    fn cacophony_vectors_match_byte_for_byte() {
        for vector in &VECTORS {
            let mut initiator = vector_handshake(vector, true);
            let mut responder = vector_handshake(vector, false);
            let mut index = 0;
            while !status(&mut initiator).finished {
                let (payload, ciphertext) = vector.messages[index];
                let (sender, receiver) = if index % 2 == 0 {
                    (&mut initiator, &mut responder)
                } else {
                    (&mut responder, &mut initiator)
                };
                let Ok(message) = sender.write(&hex(payload)) else {
                    panic!("{} write {index}", vector.name);
                };
                assert_eq!(message, hex(ciphertext), "{} message {index}", vector.name);
                let Ok(read) = receiver.read(&message) else {
                    panic!("{} read {index}", vector.name);
                };
                assert_eq!(read, hex(payload), "{} payload {index}", vector.name);
                index += 1;
            }
            for handshake in [&mut initiator, &mut responder] {
                let status = status(handshake);
                assert!(status.finished);
                assert_eq!(status.handshake_hash, hex(vector.handshake_hash));
            }

            let mut initiator = initiator.take_transport().expect("initiator transport");
            let mut responder = responder.take_transport().expect("responder transport");
            for (offset, (payload, ciphertext)) in vector.messages[index..].iter().enumerate() {
                let index = index + offset;
                let (sender, receiver) = if index % 2 == 0 {
                    (&mut initiator, &mut responder)
                } else {
                    (&mut responder, &mut initiator)
                };
                let message = sealed(sender, &hex(payload));
                assert_eq!(message, hex(ciphertext), "{} message {index}", vector.name);
                let Ok(read) = receiver.read(&message) else {
                    panic!("{} read {index}", vector.name);
                };
                assert_eq!(read, hex(payload), "{} payload {index}", vector.name);
            }
        }
    }

    #[test]
    fn a_derived_key_pair_matches_rfc_7748() {
        // RFC 7748, section 6.1: Alice's key pair.
        let encoded = key_pair(hex(
            "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a",
        ))
        .expect("key pair");
        let result = NoiseKeyPairResult::decode(encoded.as_slice()).expect("result");
        assert_eq!(
            result.public_key,
            hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        );

        let generated =
            NoiseKeyPairResult::decode(key_pair(Vec::new()).expect("generate").as_slice())
                .expect("result");
        let derived = NoiseKeyPairResult::decode(
            key_pair(generated.private_key.clone())
                .expect("derive")
                .as_slice(),
        )
        .expect("result");
        assert_eq!(derived.public_key, generated.public_key);
        assert_eq!(
            key_pair(vec![0; KEY_BYTES - 1]).err(),
            Some(PrimitiveError::InvalidArgument)
        );
    }

    #[test]
    fn the_live_session_cap_is_released_on_drop() {
        static COUNTER: AtomicUsize = AtomicUsize::new(0);
        let held: Vec<_> = (0..MAX_LIVE_SESSIONS)
            .map(|_| LiveSession::acquire(&COUNTER).expect("below the cap"))
            .collect();
        assert_eq!(
            LiveSession::acquire(&COUNTER).err(),
            Some(SessionError::ResourceLimit)
        );
        drop(held);
        assert_eq!(COUNTER.load(Ordering::Acquire), 0);
        assert!(LiveSession::acquire(&COUNTER).is_ok());
    }
}
