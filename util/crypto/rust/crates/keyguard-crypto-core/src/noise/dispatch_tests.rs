//! The Noise contract, driven through the protobuf dispatcher as the bridges
//! drive it.

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

fn kk(initiator: bool, keys: &Keys, remote: &[u8]) -> u64 {
    open(
        NoisePattern::Kk,
        initiator,
        &keys.private,
        remote,
        &[],
        PROLOGUE,
    )
    .expect("KK")
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

fn kk_transports() -> (u64, u64) {
    let (alice, bob) = (keys(), keys());
    let initiator = kk(true, &alice, &bob.public);
    let responder = kk(false, &bob, &alice.public);
    read(responder, &write(initiator, b"").unwrap()).unwrap();
    read(initiator, &write(responder, b"").unwrap()).unwrap();
    into_transports(initiator, responder)
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
fn kk_round_trips_between_pinned_keys() {
    let (alice, bob) = (keys(), keys());
    let initiator = kk(true, &alice, &bob.public);
    let responder = kk(false, &bob, &alice.public);
    assert_eq!(status(initiator).unwrap().remote_static_key, bob.public);

    assert_eq!(
        read(responder, &write(initiator, b"one").unwrap()).unwrap(),
        b"one"
    );
    assert_eq!(
        read(initiator, &write(responder, b"two").unwrap()).unwrap(),
        b"two"
    );

    let (initiator, responder) = into_transports(initiator, responder);
    assert_transports_talk(initiator, responder);
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
fn kk_fails_against_a_wrong_pinned_key_on_either_side() {
    let (alice, bob, mallory) = (keys(), keys(), keys());

    // The initiator pins the wrong responder.
    let initiator = kk(true, &alice, &mallory.public);
    let responder = kk(false, &bob, &alice.public);
    let message = write(initiator, b"one").unwrap();
    assert_eq!(read(responder, &message), Err(AuthenticationFailed));
    close(initiator);
    close(responder);

    // The responder pins the wrong initiator.
    let initiator = kk(true, &alice, &bob.public);
    let responder = kk(false, &bob, &mallory.public);
    let message = write(initiator, b"one").unwrap();
    assert_eq!(read(responder, &message), Err(AuthenticationFailed));
    close(initiator);
    close(responder);
}

#[test]
fn a_low_order_remote_key_is_an_authentication_failure() {
    let alice = keys();
    let initiator = kk(true, &alice, &[0; KEY_BYTES]);
    assert_eq!(write(initiator, b""), Err(AuthenticationFailed));
    assert_eq!(status(initiator).err(), Some(InvalidSession));
    close(initiator);
}

#[test]
fn transport_messages_must_arrive_in_nonce_order() {
    let (initiator, responder) = kk_transports();
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

    let (initiator, responder) = kk_transports();
    let first = seal(initiator, b"first").unwrap();
    let second = seal(initiator, b"second").unwrap();
    assert_eq!(unseal(responder, &first).unwrap(), b"first");
    assert_eq!(unseal(responder, &second).unwrap(), b"second");
    // A replayed and a truncated message fail too.
    assert_eq!(unseal(responder, &second), Err(AuthenticationFailed));
    let (initiator2, responder2) = kk_transports();
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
fn protocol_misuse_is_reported_and_ends_the_session() {
    let (alice, bob) = (keys(), keys());
    // The responder may not speak first.
    let responder = kk(false, &bob, &alice.public);
    assert_eq!(write(responder, b""), Err(CryptoFailure));
    assert_eq!(status(responder).err(), Some(InvalidSession));
    close(responder);

    // Junk where a handshake message belongs.
    let responder = kk(false, &bob, &alice.public);
    assert_eq!(read(responder, b"junk"), Err(CryptoFailure));
    assert_eq!(status(responder).err(), Some(InvalidSession));
    close(responder);

    // An unfinished handshake cannot become a transport, and is spent.
    let initiator = kk(true, &alice, &bob.public);
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
    for (pattern, local, remote, psk, prologue) in [
        (NoisePattern::Unspecified, key, &[][..], &PSK[..], PROLOGUE),
        (
            NoisePattern::XxPsk3,
            &key[..31],
            &[][..],
            &PSK[..],
            PROLOGUE,
        ),
        (NoisePattern::XxPsk3, key, remote, &PSK[..], PROLOGUE),
        (NoisePattern::XxPsk3, key, &[][..], &[][..], PROLOGUE),
        (NoisePattern::XxPsk3, key, &[][..], &PSK[..31], PROLOGUE),
        (NoisePattern::Kk, key, remote, &PSK[..], PROLOGUE),
        (NoisePattern::Kk, key, &[][..], &[][..], PROLOGUE),
        (NoisePattern::Kk, key, &remote[..31], &[][..], PROLOGUE),
        (
            NoisePattern::Kk,
            key,
            remote,
            &[][..],
            oversized_prologue.as_slice(),
        ),
    ] {
        assert_eq!(
            open(pattern, true, local, remote, psk, prologue),
            Err(InvalidArgument),
            "{pattern:?}"
        );
    }
    let unknown_pattern = NativeStreamOpenRequest {
        protocol_version: PROTOCOL_VERSION,
        operation: Some(native_stream_open_request::Operation::NoiseHandshake(
            NoiseHandshakeStreamOpenRequest {
                pattern: 3,
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

    let initiator = kk(true, &alice, &bob.public);
    let responder = kk(false, &bob, &alice.public);
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
fn handles_of_the_wrong_kind_or_never_issued_are_rejected() {
    let (alice, bob) = (keys(), keys());
    let initiator = kk(true, &alice, &bob.public);
    let responder = kk(false, &bob, &alice.public);
    read(responder, &write(initiator, b"").unwrap()).unwrap();
    read(initiator, &write(responder, b"").unwrap()).unwrap();
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

#[test]
fn concurrent_sessions_stay_independent() {
    let threads: Vec<_> = (0..8)
        .map(|_| {
            std::thread::spawn(|| {
                for _ in 0..16 {
                    let (initiator, responder) = kk_transports();
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
