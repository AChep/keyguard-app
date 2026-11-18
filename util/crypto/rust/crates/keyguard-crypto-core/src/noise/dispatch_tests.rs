//! The Noise contract, driven through the protobuf dispatcher as the bridges
//! drive it.

// KKhfs runs ML-KEM, which needs `full`.
#[cfg(feature = "full")]
mod kk_hfs;

use prost::Message;

use crate::{
    PROTOCOL_VERSION, call,
    noise::{
        HANDSHAKE_OVERHEAD_BYTES, KEY_BYTES, MAX_HANDSHAKE_PAYLOAD_BYTES, MAX_MESSAGE_BYTES,
        MAX_PROLOGUE_BYTES, MAX_TRANSPORT_PAYLOAD_BYTES, PSK_BYTES, TAG_BYTES,
    },
    protocol::{
        NativeErrorCode, NativeRequest, NativeResponse, NativeStreamOpenRequest,
        NoiseHandshakeReadRequest, NoiseHandshakeStatusRequest, NoiseHandshakeStatusResult,
        NoiseHandshakeStreamOpenRequest, NoiseHandshakeWriteRequest, NoiseIntoTransportRequest,
        NoiseKeyPairRequest, NoiseKeyPairResult, NoisePattern, NoiseTransportReadRequest,
        NoiseTransportWriteRequest, native_request, native_response, native_stream_open_request,
    },
    stream_close, stream_open, stream_update,
};

use NativeErrorCode::{AuthenticationFailed, CryptoFailure, InvalidArgument, InvalidSession};

const PROLOGUE: &[u8] = b"keyguard-noise-test";
const PSK: [u8; PSK_BYTES] = [0x42; PSK_BYTES];

fn decode(response: &[u8]) -> NativeResponse {
    NativeResponse::decode(response).expect("native response must decode")
}

fn code(response: &NativeResponse) -> NativeErrorCode {
    let status = response.status.as_ref().expect("status");
    NativeErrorCode::try_from(status.code).expect("known code")
}

fn invoke(operation: native_request::Operation) -> NativeResponse {
    let request = NativeRequest {
        protocol_version: PROTOCOL_VERSION,
        operation: Some(operation),
    };
    decode(&call(&request.encode_to_vec()))
}

fn bytes(response: NativeResponse) -> Result<Vec<u8>, NativeErrorCode> {
    match code(&response) {
        NativeErrorCode::Ok => match response.result {
            Some(native_response::Result::BytesValue(bytes)) => Ok(bytes),
            other => panic!("expected bytes, got {other:?}"),
        },
        error => Err(error),
    }
}

fn handle(response: NativeResponse) -> Result<u64, NativeErrorCode> {
    match code(&response) {
        NativeErrorCode::Ok => match response.result {
            Some(native_response::Result::Uint64Value(handle)) => Ok(handle),
            other => panic!("expected a handle, got {other:?}"),
        },
        error => Err(error),
    }
}

struct Keys {
    private: Vec<u8>,
    public: Vec<u8>,
}

fn keys() -> Keys {
    let encoded = bytes(invoke(native_request::Operation::NoiseKeyPair(
        NoiseKeyPairRequest {
            private_key: Vec::new(),
        },
    )))
    .expect("key pair");
    let result = NoiseKeyPairResult::decode(encoded.as_slice()).expect("key pair result");
    Keys {
        private: result.private_key.clone(),
        public: result.public_key.clone(),
    }
}

fn open(
    pattern: NoisePattern,
    initiator: bool,
    local: &[u8],
    remote: &[u8],
    psk: &[u8],
    prologue: &[u8],
) -> Result<u64, NativeErrorCode> {
    let request = NativeStreamOpenRequest {
        protocol_version: PROTOCOL_VERSION,
        operation: Some(native_stream_open_request::Operation::NoiseHandshake(
            NoiseHandshakeStreamOpenRequest {
                pattern: pattern as i32,
                initiator,
                local_private_key: local.to_vec(),
                remote_public_key: remote.to_vec(),
                psk: psk.to_vec(),
                prologue: prologue.to_vec(),
            },
        )),
    };
    handle(decode(&stream_open(&request.encode_to_vec())))
}

fn xx(initiator: bool, keys: &Keys, psk: &[u8]) -> u64 {
    open(
        NoisePattern::XxPsk3,
        initiator,
        &keys.private,
        &[],
        psk,
        PROLOGUE,
    )
    .expect("XXpsk3")
}

fn plain_xx(initiator: bool, keys: &Keys, prologue: &[u8]) -> u64 {
    open(
        NoisePattern::Xx,
        initiator,
        &keys.private,
        &[],
        &[],
        prologue,
    )
    .expect("XX")
}

fn write(handle: u64, payload: &[u8]) -> Result<Vec<u8>, NativeErrorCode> {
    bytes(invoke(native_request::Operation::NoiseHandshakeWrite(
        NoiseHandshakeWriteRequest {
            handle,
            payload: payload.to_vec(),
        },
    )))
}

fn read(handle: u64, message: &[u8]) -> Result<Vec<u8>, NativeErrorCode> {
    bytes(invoke(native_request::Operation::NoiseHandshakeRead(
        NoiseHandshakeReadRequest {
            handle,
            message: message.to_vec(),
        },
    )))
}

fn status(handle: u64) -> Result<NoiseHandshakeStatusResult, NativeErrorCode> {
    bytes(invoke(native_request::Operation::NoiseHandshakeStatus(
        NoiseHandshakeStatusRequest { handle },
    )))
    .map(|encoded| NoiseHandshakeStatusResult::decode(encoded.as_slice()).expect("status"))
}

fn into_transport(handle: u64) -> Result<u64, NativeErrorCode> {
    self::handle(invoke(native_request::Operation::NoiseIntoTransport(
        NoiseIntoTransportRequest { handle },
    )))
}

fn seal(handle: u64, payload: &[u8]) -> Result<Vec<u8>, NativeErrorCode> {
    bytes(invoke(native_request::Operation::NoiseTransportWrite(
        NoiseTransportWriteRequest {
            handle,
            payload: payload.to_vec(),
        },
    )))
}

fn unseal(handle: u64, message: &[u8]) -> Result<Vec<u8>, NativeErrorCode> {
    bytes(invoke(native_request::Operation::NoiseTransportRead(
        NoiseTransportReadRequest {
            handle,
            message: message.to_vec(),
        },
    )))
}

fn close(handle: u64) -> NativeErrorCode {
    code(&decode(&stream_close(handle)))
}

/// Finishes both handshakes, checks what each side learned, and returns the
/// two transports.
fn into_transports(initiator: u64, responder: u64) -> (u64, u64) {
    let (left, right) = (status(initiator).unwrap(), status(responder).unwrap());
    assert!(left.finished && right.finished);
    assert_eq!(left.handshake_hash.len(), 32);
    assert_eq!(left.handshake_hash, right.handshake_hash);
    let transports = (
        into_transport(initiator).unwrap(),
        into_transport(responder).unwrap(),
    );
    // A spent handshake handle is gone, and closing it is a no-op.
    assert_eq!(status(initiator).err(), Some(InvalidSession));
    assert_eq!(close(initiator), NativeErrorCode::Ok);
    transports
}

fn assert_transports_talk(initiator: u64, responder: u64) {
    for (from, to) in [(initiator, responder), (responder, initiator)] {
        let message = seal(from, b"vault delta").unwrap();
        assert_eq!(message.len(), b"vault delta".len() + TAG_BYTES);
        assert_eq!(unseal(to, &message).unwrap(), b"vault delta");
    }
    let empty = seal(initiator, b"").unwrap();
    assert_eq!(unseal(responder, &empty).unwrap(), b"");
}

/// An XX pair after its three messages: both handshakes are finished, and
/// neither has turned into its transport yet.
fn finished_xx() -> (u64, u64) {
    let (alice, bob) = (keys(), keys());
    let initiator = plain_xx(true, &alice, PROLOGUE);
    let responder = plain_xx(false, &bob, PROLOGUE);
    read(responder, &write(initiator, b"").unwrap()).unwrap();
    read(initiator, &write(responder, b"").unwrap()).unwrap();
    read(responder, &write(initiator, b"").unwrap()).unwrap();
    (initiator, responder)
}

/// The transports of an XX pair. XX runs in every build, so the contract
/// tests of the transport use it.
fn xx_transports() -> (u64, u64) {
    let (initiator, responder) = finished_xx();
    into_transports(initiator, responder)
}

/// The arguments of a handshake open: the pattern, the local private key, the
/// remote public key, the PSK and the prologue.
type OpenArguments<'a> = (NoisePattern, &'a [u8], &'a [u8], &'a [u8], &'a [u8]);

fn assert_opens_are_rejected(cases: &[OpenArguments<'_>]) {
    for &(pattern, local, remote, psk, prologue) in cases {
        assert_eq!(
            open(pattern, true, local, remote, psk, prologue),
            Err(InvalidArgument),
            "{pattern:?}"
        );
    }
}

#[test]
fn xxpsk3_round_trips_and_learns_both_static_keys() {
    let (alice, bob) = (keys(), keys());
    let initiator = xx(true, &alice, &PSK);
    let responder = xx(false, &bob, &PSK);
    assert!(status(initiator).unwrap().remote_static_key.is_empty());

    assert_eq!(
        read(responder, &write(initiator, b"").unwrap()).unwrap(),
        b""
    );
    assert_eq!(
        read(initiator, &write(responder, b"").unwrap()).unwrap(),
        b""
    );
    assert_eq!(status(initiator).unwrap().remote_static_key, bob.public);
    let pending = status(responder).unwrap();
    assert!(!pending.finished);
    assert!(pending.handshake_hash.is_empty(), "no hash before the end");
    let message = write(initiator, b"hello").unwrap();
    assert_eq!(read(responder, &message).unwrap(), b"hello");
    assert_eq!(status(responder).unwrap().remote_static_key, alice.public);

    let (initiator, responder) = into_transports(initiator, responder);
    assert_transports_talk(initiator, responder);
    assert_eq!(close(initiator), NativeErrorCode::Ok);
    assert_eq!(close(responder), NativeErrorCode::Ok);
}

#[test]
fn xx_round_trips_and_learns_both_static_keys() {
    let (alice, bob) = (keys(), keys());
    let initiator = plain_xx(true, &alice, PROLOGUE);
    let responder = plain_xx(false, &bob, PROLOGUE);
    assert!(status(initiator).unwrap().remote_static_key.is_empty());

    // -> e: the ephemeral key alone, as no key encrypts the payload yet.
    let first = write(initiator, b"").unwrap();
    assert_eq!(first.len(), KEY_BYTES);
    assert_eq!(read(responder, &first).unwrap(), b"");
    assert!(status(responder).unwrap().remote_static_key.is_empty());
    // <- e, ee, s, es: an ephemeral key, an encrypted static key and a tag.
    let second = write(responder, b"").unwrap();
    assert_eq!(second.len(), KEY_BYTES + KEY_BYTES + TAG_BYTES + TAG_BYTES);
    assert_eq!(read(initiator, &second).unwrap(), b"");
    // The initiator knows the responder's key before it sends its own.
    let pending = status(initiator).unwrap();
    assert_eq!(pending.remote_static_key, bob.public);
    assert!(!pending.finished);
    // -> s, se: an encrypted static key and a tag.
    let third = write(initiator, b"").unwrap();
    assert_eq!(third.len(), KEY_BYTES + TAG_BYTES + TAG_BYTES);
    assert_eq!(read(responder, &third).unwrap(), b"");
    assert_eq!(status(responder).unwrap().remote_static_key, alice.public);

    let (initiator, responder) = into_transports(initiator, responder);
    assert_transports_talk(initiator, responder);
    assert_eq!(close(initiator), NativeErrorCode::Ok);
    assert_eq!(close(responder), NativeErrorCode::Ok);
}

#[test]
fn xx_fails_against_a_different_prologue() {
    let (alice, bob) = (keys(), keys());
    let initiator = plain_xx(true, &alice, PROLOGUE);
    let responder = plain_xx(false, &bob, b"keyguard-noise-other");
    // The first message is not encrypted, so the prologue first counts in the
    // second.
    read(responder, &write(initiator, b"").unwrap()).unwrap();
    let second = write(responder, b"").unwrap();
    assert_eq!(read(initiator, &second), Err(AuthenticationFailed));
    assert_eq!(status(initiator).err(), Some(InvalidSession));
    close(initiator);
    close(responder);
}

#[test]
fn a_wrong_psk_fails_and_ends_the_session() {
    let (alice, bob) = (keys(), keys());
    let initiator = xx(true, &alice, &PSK);
    let responder = xx(false, &bob, &[0x24; PSK_BYTES]);
    // The first two messages are not authenticated by the PSK, so they carry
    // no payload in the sync protocol.
    read(responder, &write(initiator, b"").unwrap()).unwrap();
    read(initiator, &write(responder, b"").unwrap()).unwrap();

    let message = write(initiator, b"secret").unwrap();
    assert_eq!(read(responder, &message), Err(AuthenticationFailed));
    // The failure ended the session; the handle is still released by close.
    assert_eq!(status(responder).err(), Some(InvalidSession));
    assert_eq!(read(responder, &message), Err(InvalidSession));
    assert_eq!(close(responder), NativeErrorCode::Ok);
    close(initiator);
}

#[test]
fn a_key_pair_is_derived_from_its_private_key() {
    let generated = keys();
    let encoded = bytes(invoke(native_request::Operation::NoiseKeyPair(
        NoiseKeyPairRequest {
            private_key: generated.private.clone(),
        },
    )))
    .unwrap();
    let derived = NoiseKeyPairResult::decode(encoded.as_slice()).unwrap();
    assert_eq!(derived.private_key, generated.private);
    assert_eq!(derived.public_key, generated.public);
}

#[test]
fn transport_messages_must_arrive_in_nonce_order() {
    let (initiator, responder) = xx_transports();
    let first = seal(initiator, b"first").unwrap();
    let second = seal(initiator, b"second").unwrap();
    assert_ne!(
        first,
        seal(responder, b"first").unwrap(),
        "each direction has its key"
    );

    // A reordered message fails and ends the session.
    assert_eq!(unseal(responder, &second), Err(AuthenticationFailed));
    assert_eq!(unseal(responder, &first), Err(InvalidSession));
    close(initiator);
    close(responder);

    let (initiator, responder) = xx_transports();
    let first = seal(initiator, b"first").unwrap();
    let second = seal(initiator, b"second").unwrap();
    assert_eq!(unseal(responder, &first).unwrap(), b"first");
    assert_eq!(unseal(responder, &second).unwrap(), b"second");
    // A replayed and a truncated message fail too.
    assert_eq!(unseal(responder, &second), Err(AuthenticationFailed));
    let (initiator2, responder2) = xx_transports();
    let third = seal(initiator2, b"third").unwrap();
    assert_eq!(
        unseal(responder2, &third[..TAG_BYTES - 1]),
        Err(AuthenticationFailed)
    );
    for handle in [initiator, responder, initiator2, responder2] {
        close(handle);
    }
}

#[test]
fn the_largest_transport_payload_round_trips() {
    // 65,519 bytes and the 16-byte tag fill the largest Noise message.
    let payload = vec![0x5a; 65_519];
    let (initiator, responder) = xx_transports();
    for (from, to) in [(initiator, responder), (responder, initiator)] {
        let message = seal(from, &payload).unwrap();
        assert_eq!(message.len(), 65_535);
        assert_eq!(unseal(to, &message).unwrap(), payload);
        assert_eq!(seal(from, &vec![0; 65_520]), Err(InvalidArgument));
    }
    close(initiator);
    close(responder);
}

#[test]
fn concurrent_sessions_stay_independent() {
    let threads: Vec<_> = (0..8)
        .map(|_| {
            std::thread::spawn(|| {
                for _ in 0..16 {
                    let (initiator, responder) = xx_transports();
                    assert_transports_talk(initiator, responder);
                    close(initiator);
                    close(responder);
                }
            })
        })
        .collect();
    for thread in threads {
        thread.join().expect("a session thread must not panic");
    }
}

#[test]
fn protocol_misuse_is_reported_and_ends_the_session() {
    let (alice, bob) = (keys(), keys());
    // The responder may not speak first.
    let responder = plain_xx(false, &bob, PROLOGUE);
    assert_eq!(write(responder, b""), Err(CryptoFailure));
    assert_eq!(status(responder).err(), Some(InvalidSession));
    close(responder);

    // Junk where a handshake message belongs.
    let responder = plain_xx(false, &bob, PROLOGUE);
    assert_eq!(read(responder, b"junk"), Err(CryptoFailure));
    assert_eq!(status(responder).err(), Some(InvalidSession));
    close(responder);

    // An unfinished handshake cannot become a transport, and is spent.
    let initiator = plain_xx(true, &alice, PROLOGUE);
    assert!(!status(initiator).unwrap().finished);
    assert_eq!(into_transport(initiator), Err(CryptoFailure));
    assert_eq!(status(initiator).err(), Some(InvalidSession));
    assert_eq!(close(initiator), NativeErrorCode::Ok);
}

#[test]
fn invalid_arguments_leave_the_session_usable() {
    let (alice, bob) = (keys(), keys());
    let key = alice.private.as_slice();
    let remote = bob.public.as_slice();
    let oversized_prologue = vec![0; MAX_PROLOGUE_BYTES + 1];
    assert_opens_are_rejected(&[
        (NoisePattern::Unspecified, key, &[], &PSK, PROLOGUE),
        (NoisePattern::XxPsk3, &key[..31], &[], &PSK, PROLOGUE),
        (NoisePattern::XxPsk3, key, remote, &PSK, PROLOGUE),
        (NoisePattern::XxPsk3, key, &[], &[], PROLOGUE),
        (NoisePattern::XxPsk3, key, &[], &PSK[..31], PROLOGUE),
        (NoisePattern::Xx, &key[..31], &[], &[], PROLOGUE),
        (NoisePattern::Xx, key, remote, &[], PROLOGUE),
        (NoisePattern::Xx, key, &[], &PSK, PROLOGUE),
        (NoisePattern::Xx, key, &[], &[], &oversized_prologue),
    ]);
    let unknown_pattern = NativeStreamOpenRequest {
        protocol_version: PROTOCOL_VERSION,
        operation: Some(native_stream_open_request::Operation::NoiseHandshake(
            NoiseHandshakeStreamOpenRequest {
                pattern: 4,
                initiator: true,
                local_private_key: alice.private.clone(),
                remote_public_key: bob.public.clone(),
                psk: Vec::new(),
                prologue: Vec::new(),
            },
        )),
    };
    assert_eq!(
        handle(decode(&stream_open(&unknown_pattern.encode_to_vec()))),
        Err(InvalidArgument)
    );

    let initiator = plain_xx(true, &alice, PROLOGUE);
    let responder = plain_xx(false, &bob, PROLOGUE);
    assert_eq!(
        write(initiator, &vec![0; MAX_HANDSHAKE_PAYLOAD_BYTES + 1]),
        Err(InvalidArgument)
    );
    assert_eq!(
        read(responder, &vec![0; MAX_MESSAGE_BYTES + 1]),
        Err(InvalidArgument)
    );
    let message = write(initiator, b"after").unwrap();
    assert!(message.len() <= b"after".len() + HANDSHAKE_OVERHEAD_BYTES);
    assert_eq!(read(responder, &message).unwrap(), b"after");
    read(initiator, &write(responder, b"").unwrap()).unwrap();
    read(responder, &write(initiator, b"").unwrap()).unwrap();

    let (initiator, responder) = into_transports(initiator, responder);
    assert_eq!(
        seal(initiator, &vec![0; MAX_TRANSPORT_PAYLOAD_BYTES + 1]).err(),
        Some(InvalidArgument)
    );
    assert_eq!(
        unseal(responder, &vec![0; MAX_MESSAGE_BYTES + 1]),
        Err(InvalidArgument)
    );
    assert_transports_talk(initiator, responder);
    assert_eq!(
        bytes(invoke(native_request::Operation::NoiseKeyPair(
            NoiseKeyPairRequest {
                private_key: vec![0; KEY_BYTES - 1],
            }
        ))),
        Err(InvalidArgument)
    );
    close(initiator);
    close(responder);
}

#[test]
fn handles_of_the_wrong_kind_or_never_issued_are_rejected() {
    let (initiator, responder) = finished_xx();
    let transport = into_transport(initiator).unwrap();

    assert_eq!(status(transport).err(), Some(InvalidArgument));
    assert_eq!(into_transport(transport), Err(InvalidArgument));
    assert_eq!(seal(responder, b"").err(), Some(InvalidArgument));
    // Noise sessions take no raw stream chunks.
    assert_eq!(
        code(&decode(&stream_update(transport, b"chunk"))),
        InvalidArgument
    );
    // Both kinds survive a request meant for the other kind.
    assert!(seal(transport, b"").is_ok());
    assert!(status(responder).unwrap().finished);

    for never_issued in [0, u64::MAX, 1 << 40] {
        assert_eq!(status(never_issued).err(), Some(InvalidSession));
        assert_eq!(seal(never_issued, b"").err(), Some(InvalidSession));
    }

    for handle in [transport, responder] {
        assert_eq!(close(handle), NativeErrorCode::Ok);
        assert_eq!(close(handle), NativeErrorCode::Ok, "close is idempotent");
        assert_eq!(read(handle, b""), Err(InvalidSession));
        assert_eq!(unseal(handle, b""), Err(InvalidSession));
    }
}

/// Without `full`, the dispatcher offers Noise alone and refuses KKhfs, whose
/// ML-KEM needs `full`, while XX still opens.
#[cfg(not(feature = "full"))]
#[test]
fn a_noise_only_build_refuses_kk_hfs() {
    assert_eq!(crate::CAPABILITIES, crate::CAPABILITY_NOISE);
    let (alice, bob) = (keys(), keys());
    for initiator in [true, false] {
        assert_eq!(
            open(
                NoisePattern::KkHfs,
                initiator,
                &alice.private,
                &bob.public,
                &[],
                PROLOGUE,
            ),
            Err(NativeErrorCode::UnsupportedOperation)
        );
    }
    let initiator = plain_xx(true, &alice, PROLOGUE);
    assert_eq!(close(initiator), NativeErrorCode::Ok);
}

/// Without `full`, a one-shot request, a stream and a data-plane call of
/// `fast` that are not Noise answer UNSUPPORTED_OPERATION.
#[cfg(not(feature = "full"))]
#[test]
fn a_noise_only_build_refuses_other_operations() {
    use crate::{
        fast,
        protocol::{DigestRequest, DigestStreamOpenRequest, HashAlgorithm},
    };
    use NativeErrorCode::UnsupportedOperation;
    const SENTINEL: u8 = 0xa5;

    let digest = invoke(native_request::Operation::Digest(DigestRequest {
        algorithm: HashAlgorithm::Sha256 as i32,
        data: b"data".to_vec(),
    }));
    assert_eq!(code(&digest), UnsupportedOperation);
    let digest_stream = NativeStreamOpenRequest {
        protocol_version: PROTOCOL_VERSION,
        operation: Some(native_stream_open_request::Operation::Digest(
            DigestStreamOpenRequest {
                algorithm: HashAlgorithm::Sha256 as i32,
            },
        )),
    };
    assert_eq!(
        handle(decode(&stream_open(&digest_stream.encode_to_vec()))),
        Err(UnsupportedOperation)
    );

    assert_eq!(fast::random_int(10), Err(UnsupportedOperation));
    // The calls that fill caller buffers zero them.
    let (mut ciphertext, mut mac) = ([SENTINEL; 32], [SENTINEL; 32]);
    assert_eq!(
        fast::encrypt_into(
            &[1; 32],
            &[2; 32],
            &[3; 16],
            b"plaintext",
            &mut ciphertext,
            &mut mac,
        ),
        Err(UnsupportedOperation)
    );
    assert_eq!(ciphertext, [0; 32]);
    assert_eq!(mac, [0; 32]);
    let mut plaintext = [SENTINEL; 32];
    assert_eq!(
        fast::decrypt_into(
            &[1; 32],
            &[2; 32],
            &[3; 16],
            &[4; 32],
            &[5; 32],
            &mut plaintext,
        ),
        Err(UnsupportedOperation)
    );
    assert_eq!(plaintext, [0; 32]);
}
