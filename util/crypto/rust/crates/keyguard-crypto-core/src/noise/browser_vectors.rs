//! The Noise vectors of the browser extension protocol,
//! `browserExtension/protocol/vectors/noise_xx.json`, which the app and the
//! extension test against. `browserExtension/protocol/README.md` specifies
//! the profile.
//!
//! The file holds the Cacophony vector of `Noise_XX_25519_ChaChaPoly_SHA256`,
//! as shipped with snow 0.10.0, and a transcript of the Keyguard profile with
//! fixed keys: the prologue of an extension origin, three handshake messages
//! with empty payloads, two framed requests and their responses, and the
//! pairing code. Both run through the builder of this module's parent, so the
//! file records what the app's Noise produces.
//! [`checked_in_vectors_are_current`] compares the file with a fresh run;
//! after a deliberate change, rewrite it with
//! `cargo test -p keyguard-crypto-core --lib -- --ignored write_browser_vectors`.

use aws_lc_rs::digest::{SHA256, digest};
use base64ct::{Base64, Encoding as _};
use serde::Serialize;
use x25519_dalek::{PublicKey, StaticSecret};

use super::{KEY_BYTES, MAX_MESSAGE_BYTES, builder};
use crate::primitives;

const PATH: &str = concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../../../../../browserExtension/protocol/vectors/noise_xx.json"
);

const PROTOCOL_NAME: &str = "Noise_XX_25519_ChaChaPoly_SHA256";

/// The Cacophony vector of [`PROTOCOL_NAME`]: the inputs, the payload and the
/// ciphertext of every message, the initiator sending the even ones, and the
/// handshake hash.
mod cacophony {
    pub(super) const PROLOGUE: &str = "4a6f686e2047616c74";
    pub(super) const INIT_STATIC: &str =
        "e61ef9919cde45dd5f82166404bd08e38bceb5dfdfded0a34c8df7ed542214d1";
    pub(super) const INIT_EPHEMERAL: &str =
        "893e28b9dc6ca8d611ab664754b8ceb7bac5117349a4439a6b0569da977c464a";
    pub(super) const RESP_STATIC: &str =
        "4a3acbfdb163dec651dfa3194dece676d437029c62a408b4c5ea9114246e4893";
    pub(super) const RESP_EPHEMERAL: &str =
        "bbdb4cdbd309f1a1f2e1456967fe288cadd6f712d65dc7b7793d5e63da6b375b";
    pub(super) const HANDSHAKE_HASH: &str =
        "c8e5f64e846193be2a834104c2a009868d6c9f3bd3c186299888b488b2f1f58e";
    pub(super) const MESSAGES: [(&str, &str); 6] = [
        (
            "4c756477696720766f6e204d69736573",
            "ca35def5ae56cec33dc2036731ab14896bc4c75dbb07a61f879f8e3afa4c79444c756477696720766f6e204d69736573",
        ),
        (
            "4d757272617920526f746862617264",
            "95ebc60d2b1fa672c1f46a8aa265ef51bfe38e7ccb39ec5be34069f14480884381cbad1f276e038c48378ffce2b65285e08d6b68aaa3629a5a8639392490e5b9bd5269c2f1e4f488ed8831161f19b7815528f8982ffe09be9b5c412f8a0db50f8814c7194e83f23dbd8d162c9326ad",
        ),
        (
            "462e20412e20486179656b",
            "c7195ffacac1307ff99046f219750fc47693e23c3cb08b89c2af808b444850a80ae475b9df0f169ae80a89be0865b57f58c9fea0d4ec82a286427402f113e4b6ae769a1d95941d49b25030",
        ),
        (
            "4361726c204d656e676572",
            "96763ed773f8e47bb3712f0e29b3060ffc956ffc146cee53d5e1df",
        ),
        (
            "4a65616e2d426170746973746520536179",
            "3e40f15f6f3a46ae446b253bf8b1d9ffb6ed9b174d272328ff91a7e2e5c79c07f5",
        ),
        (
            "457567656e2042f6686d20766f6e2042617765726b",
            "eb3f3515110702e047a6c9da4478b6ead94873c11c0f2d710ddb3f09fce024b3a58502ae3f",
        ),
    ];
}

/// The Keyguard profile, see `browserExtension/protocol/README.md`.
mod profile {
    /// Followed by the extension origin.
    pub(super) const PROLOGUE_PREFIX: &str = "keyguard-browser/v1\n";
    pub(super) const ORIGIN: &str = "chrome-extension://abcdefghijklmnopabcdefghijklmnop/";
    /// Followed by the extension's nonce.
    pub(super) const COMMIT_DOMAIN: &str = "keyguard-browser-commit/v1\n";
    /// Followed by the sorted static keys, the app's nonce and the
    /// extension's nonce.
    pub(super) const SAS_INFO: &str = "keyguard-browser-sas/v1";
    pub(super) const SAS_BYTES: u32 = 4;
    pub(super) const SAS_MODULUS: u32 = 1_000_000;
    /// The flags byte of a message that fits one fragment.
    pub(super) const LAST_FRAGMENT: u8 = 0;
}

#[derive(Clone, Copy, Serialize)]
#[serde(rename_all = "lowercase")]
enum Side {
    Initiator,
    Responder,
}

struct Message {
    sender: Side,
    payload: Vec<u8>,
    ciphertext: Vec<u8>,
}

struct Transcript {
    handshake_hash: Vec<u8>,
    messages: Vec<Message>,
}

struct Keys<'a> {
    prologue: &'a [u8],
    init_static: &'a [u8],
    init_ephemeral: &'a [u8],
    resp_static: &'a [u8],
    resp_ephemeral: &'a [u8],
}

fn hex(value: &str) -> Vec<u8> {
    (0..value.len())
        .step_by(2)
        .map(|index| u8::from_str_radix(&value[index..index + 2], 16).expect("test hex"))
        .collect()
}

fn to_hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

/// 32 bytes counting up from `start`.
fn counting(start: u8) -> [u8; KEY_BYTES] {
    std::array::from_fn(|index| start + u8::try_from(index).expect("small index"))
}

fn public_key(private_key: &[u8]) -> [u8; KEY_BYTES] {
    let secret: [u8; KEY_BYTES] = private_key.try_into().expect("a 32-byte key");
    *PublicKey::from(&StaticSecret::from(secret)).as_bytes()
}

/// Writes one message and reads it on the other side.
fn send(
    sender: Side,
    payload: &[u8],
    write: impl FnOnce(&[u8], &mut [u8]) -> Result<usize, snow::Error>,
    read: impl FnOnce(&[u8], &mut [u8]) -> Result<usize, snow::Error>,
) -> Message {
    let mut buffer = vec![0; MAX_MESSAGE_BYTES];
    let length = write(payload, &mut buffer).expect("write");
    let ciphertext = buffer[..length].to_vec();
    let length = read(&ciphertext, &mut buffer).expect("read");
    assert_eq!(&buffer[..length], payload);
    Message {
        sender,
        payload: payload.to_vec(),
        ciphertext,
    }
}

/// Runs an XX handshake with fixed ephemeral keys, then the transport. The
/// first three `messages` are the handshake's, the initiator sending the
/// first and the last of them.
fn run(keys: &Keys, messages: &[(Side, Vec<u8>)]) -> Transcript {
    let state = |local: &[u8], ephemeral: &[u8], initiator: bool| {
        let builder = builder(PROTOCOL_NAME, local, &[], None, keys.prologue)
            .expect("builder")
            .fixed_ephemeral_key_for_testing_only(ephemeral);
        if initiator {
            builder.build_initiator()
        } else {
            builder.build_responder()
        }
        .expect("handshake")
    };
    let mut initiator = state(keys.init_static, keys.init_ephemeral, true);
    let mut responder = state(keys.resp_static, keys.resp_ephemeral, false);
    let (handshake, transport) = messages.split_at(3);
    let mut transcript = Vec::new();
    for (sender, payload) in handshake {
        transcript.push(match sender {
            Side::Initiator => send(
                *sender,
                payload,
                |payload, message| initiator.write_message(payload, message),
                |message, payload| responder.read_message(message, payload),
            ),
            Side::Responder => send(
                *sender,
                payload,
                |payload, message| responder.write_message(payload, message),
                |message, payload| initiator.read_message(message, payload),
            ),
        });
    }
    assert!(initiator.is_handshake_finished() && responder.is_handshake_finished());
    let handshake_hash = initiator.get_handshake_hash().to_vec();
    assert_eq!(handshake_hash, responder.get_handshake_hash());
    let mut initiator = initiator.into_transport_mode().expect("transport");
    let mut responder = responder.into_transport_mode().expect("transport");
    for (sender, payload) in transport {
        transcript.push(match sender {
            Side::Initiator => send(
                *sender,
                payload,
                |payload, message| initiator.write_message(payload, message),
                |message, payload| responder.read_message(message, payload),
            ),
            Side::Responder => send(
                *sender,
                payload,
                |payload, message| responder.write_message(payload, message),
                |message, payload| initiator.read_message(message, payload),
            ),
        });
    }
    Transcript {
        handshake_hash,
        messages: transcript,
    }
}

/// The vectors file. Fields are written in their order; bytes are hex.
#[derive(Serialize)]
struct Vectors {
    description: &'static str,
    cacophony: Vec<CacophonyVector>,
    keyguard: KeyguardVector,
}

#[derive(Serialize)]
struct CacophonyVector {
    protocol_name: &'static str,
    init_prologue: &'static str,
    init_static: &'static str,
    init_ephemeral: &'static str,
    resp_prologue: &'static str,
    resp_static: &'static str,
    resp_ephemeral: &'static str,
    handshake_hash: &'static str,
    messages: Vec<CacophonyMessage>,
}

#[derive(Serialize)]
struct CacophonyMessage {
    payload: &'static str,
    ciphertext: &'static str,
}

#[derive(Serialize)]
struct KeyguardVector {
    protocol_name: &'static str,
    origin: &'static str,
    init_prologue: String,
    init_static: String,
    init_static_public: String,
    init_ephemeral: String,
    resp_prologue: String,
    resp_static: String,
    resp_static_public: String,
    resp_ephemeral: String,
    handshake_hash: String,
    messages: Vec<KeyguardMessage>,
    pairing: Pairing,
}

#[derive(Serialize)]
struct KeyguardMessage {
    sender: Side,
    /// The JSON text of a framed message.
    #[serde(skip_serializing_if = "Option::is_none")]
    text: Option<String>,
    payload: String,
    ciphertext: String,
}

#[derive(Serialize)]
struct Pairing {
    extension_nonce: String,
    commit: String,
    app_nonce: String,
    sas_info: String,
    sas_okm: String,
    code: String,
}

/// The Cacophony vector, run through this crate and checked byte for byte.
fn cacophony_vector() -> CacophonyVector {
    let senders = [Side::Initiator, Side::Responder].into_iter().cycle();
    let messages: Vec<_> = senders
        .zip(cacophony::MESSAGES)
        .map(|(sender, (payload, _))| (sender, hex(payload)))
        .collect();
    let prologue = hex(cacophony::PROLOGUE);
    let (init_static, init_ephemeral) =
        (hex(cacophony::INIT_STATIC), hex(cacophony::INIT_EPHEMERAL));
    let (resp_static, resp_ephemeral) =
        (hex(cacophony::RESP_STATIC), hex(cacophony::RESP_EPHEMERAL));
    let transcript = run(
        &Keys {
            prologue: &prologue,
            init_static: &init_static,
            init_ephemeral: &init_ephemeral,
            resp_static: &resp_static,
            resp_ephemeral: &resp_ephemeral,
        },
        &messages,
    );
    assert_eq!(transcript.handshake_hash, hex(cacophony::HANDSHAKE_HASH));
    for (index, (message, (_, ciphertext))) in transcript
        .messages
        .iter()
        .zip(cacophony::MESSAGES)
        .enumerate()
    {
        assert_eq!(message.ciphertext, hex(ciphertext), "message {index}");
    }
    CacophonyVector {
        protocol_name: PROTOCOL_NAME,
        init_prologue: cacophony::PROLOGUE,
        init_static: cacophony::INIT_STATIC,
        init_ephemeral: cacophony::INIT_EPHEMERAL,
        resp_prologue: cacophony::PROLOGUE,
        resp_static: cacophony::RESP_STATIC,
        resp_ephemeral: cacophony::RESP_EPHEMERAL,
        handshake_hash: cacophony::HANDSHAKE_HASH,
        messages: cacophony::MESSAGES
            .iter()
            .map(|&(payload, ciphertext)| CacophonyMessage {
                payload,
                ciphertext,
            })
            .collect(),
    }
}

/// A transcript of the Keyguard profile: an unpaired extension says `hello`
/// and starts pairing.
fn keyguard_vector() -> KeyguardVector {
    let prologue = [
        profile::PROLOGUE_PREFIX.as_bytes(),
        profile::ORIGIN.as_bytes(),
    ]
    .concat();
    let (init_static, init_ephemeral) = (counting(0x00), counting(0x20));
    let (resp_static, resp_ephemeral) = (counting(0x40), counting(0x60));
    let (init_public, resp_public) = (public_key(&init_static), public_key(&resp_static));
    let (extension_nonce, app_nonce) = (counting(0x80), counting(0xa0));

    let commit = digest(
        &SHA256,
        &[profile::COMMIT_DOMAIN.as_bytes(), &extension_nonce].concat(),
    );
    let texts = [
        r#"{"id":1,"type":"hello","protocol":1,"capabilities":["passkeys","save"]}"#.to_owned(),
        r#"{"id":1,"ok":true,"protocol":1,"capabilities":["passkeys","save"],"paired":false,"vault":"locked"}"#.to_owned(),
        format!(
            r#"{{"id":2,"type":"pair_commit","commit":"{}","name":"Chrome"}}"#,
            Base64::encode_string(commit.as_ref()),
        ),
        format!(
            r#"{{"id":2,"ok":true,"nonce":"{}"}}"#,
            Base64::encode_string(&app_nonce),
        ),
    ];
    let framed = |text: &str| [&[profile::LAST_FRAGMENT], text.as_bytes()].concat();
    let messages = [
        (Side::Initiator, Vec::new()),
        (Side::Responder, Vec::new()),
        (Side::Initiator, Vec::new()),
        (Side::Initiator, framed(&texts[0])),
        (Side::Responder, framed(&texts[1])),
        (Side::Initiator, framed(&texts[2])),
        (Side::Responder, framed(&texts[3])),
    ];
    let transcript = run(
        &Keys {
            prologue: &prologue,
            init_static: &init_static,
            init_ephemeral: &init_ephemeral,
            resp_static: &resp_static,
            resp_ephemeral: &resp_ephemeral,
        },
        &messages,
    );

    let (first, second) = if init_public <= resp_public {
        (init_public, resp_public)
    } else {
        (resp_public, init_public)
    };
    let sas_info = [
        profile::SAS_INFO.as_bytes(),
        &first,
        &second,
        &app_nonce,
        &extension_nonce,
    ]
    .concat();
    let sas_okm = primitives::hkdf_sha256(
        Vec::new(),
        Some(transcript.handshake_hash.clone()),
        Some(sas_info.clone()),
        profile::SAS_BYTES,
    )
    .expect("pairing code");
    let code =
        u32::from_be_bytes(sas_okm.as_slice().try_into().expect("4 bytes")) % profile::SAS_MODULUS;

    let mut texts = texts.into_iter();
    let messages = transcript
        .messages
        .iter()
        .map(|message| KeyguardMessage {
            sender: message.sender,
            text: (!message.payload.is_empty())
                .then(|| texts.next().expect("a text per framed message")),
            payload: to_hex(&message.payload),
            ciphertext: to_hex(&message.ciphertext),
        })
        .collect();
    KeyguardVector {
        protocol_name: PROTOCOL_NAME,
        origin: profile::ORIGIN,
        init_prologue: to_hex(&prologue),
        init_static: to_hex(&init_static),
        init_static_public: to_hex(&init_public),
        init_ephemeral: to_hex(&init_ephemeral),
        resp_prologue: to_hex(&prologue),
        resp_static: to_hex(&resp_static),
        resp_static_public: to_hex(&resp_public),
        resp_ephemeral: to_hex(&resp_ephemeral),
        handshake_hash: to_hex(&transcript.handshake_hash),
        messages,
        pairing: Pairing {
            extension_nonce: to_hex(&extension_nonce),
            commit: to_hex(commit.as_ref()),
            app_nonce: to_hex(&app_nonce),
            sas_info: to_hex(&sas_info),
            sas_okm: to_hex(&sas_okm),
            code: format!("{code:06}"),
        },
    }
}

fn vectors() -> String {
    let vectors = Vectors {
        description: "Noise_XX_25519_ChaChaPoly_SHA256 vectors of the Keyguard browser \
                      extension protocol, see ../README.md. Bytes are lowercase hex. \
                      Generated by util/crypto (keyguard-crypto-core, \
                      noise/browser_vectors.rs); do not edit.",
        cacophony: vec![cacophony_vector()],
        keyguard: keyguard_vector(),
    };
    let mut out = serde_json::to_string_pretty(&vectors).expect("the vectors serialize");
    out.push('\n');
    out
}

#[test]
fn checked_in_vectors_are_current() {
    let checked_in = std::fs::read_to_string(PATH)
        .expect("the vectors file; write it with `-- --ignored write_browser_vectors`")
        .replace("\r\n", "\n");
    assert!(
        checked_in == vectors(),
        "{PATH} is stale; rewrite it with `-- --ignored write_browser_vectors`",
    );
}

#[test]
#[ignore = "rewrites the checked-in vectors"]
fn write_browser_vectors() {
    std::fs::write(PATH, vectors()).expect("write the vectors file");
}
