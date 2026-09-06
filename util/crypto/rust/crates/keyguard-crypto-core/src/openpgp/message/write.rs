//! OpenPGP write path and authenticated decryption.
//!
//! rPGP supplies packet composition, armor, public-key encryption, and the
//! non-RSA key implementations. All randomness comes from AWS-LC. RSA private
//! generation, signing, and decryption cross the audited sensitive adapter and
//! never invoke rPGP's RustCrypto RSA private operations.

use std::{
    collections::HashSet,
    io::{BufRead, BufReader, Cursor, Read, Write},
    panic::{AssertUnwindSafe, catch_unwind},
    sync::{
        Arc,
        atomic::{AtomicBool, AtomicUsize, Ordering},
        mpsc::{self, Receiver, SyncSender, TryRecvError},
    },
    thread::{self, JoinHandle},
};

use aes::Aes256;
#[cfg(test)]
use flate2::write::{DeflateEncoder, ZlibEncoder};
use flate2::{
    Compression,
    read::{DeflateEncoder as DeflateReader, ZlibEncoder as ZlibReader},
};
use ocb3::{
    AeadInPlace, KeyInit, Nonce, Ocb3,
    consts::{U15, U16},
};
#[cfg(test)]
use pgp::packet::PacketHeader;
use pgp::{
    armor::{self, BlockType, Headers},
    composed::{
        ArmorOptions, DecryptionOptions, Deserializable, DetachedSignature, Esk, Message,
        PlainSessionKey, PublicOrSecret, RawSessionKey, SignedPublicKey, SignedSecretKey,
        SubpacketConfig, TheRing,
    },
    crypto::{
        aead::{AeadAlgorithm, ChunkSize},
        hash::HashAlgorithm,
        public_key::PublicKeyAlgorithm,
        sym::SymmetricKeyAlgorithm,
    },
    packet::{
        OnePassSignature, PacketTrait, PublicKeyEncryptedSessionKey, SignatureConfig,
        SignatureHasher, SignatureType, SignatureVersion, SignatureVersionSpecific, Subpacket,
        SubpacketData, SymEncryptedProtectedData,
    },
    ser::Serialize,
    types::{
        CompressionAlgorithm, DecryptionKey, EskType, Fingerprint, KeyDetails, KeyVersion,
        Password, PkeskBytes, PkeskVersion, PublicParams, Seipdv1ReadMode, SigningKey, Tag,
        Timestamp,
    },
};
use rand::RngCore;
use zeroize::{Zeroize, Zeroizing};

use crate::{
    MAX_CONTROL_ENVELOPE_BYTES,
    openpgp::{
        certificate::{
            MaterialErrorSeverity, MutationMaterialError, ParsedSecretCertificate,
            parse_mutation_candidates, parse_secret_certificate,
        },
        crypto::{
            leading_mpi_bits,
            secret::{
                AwsLcRng, AwsLcRsaSecretKey, SecretChunks, SecretPacketRef, SecretPacketSelection,
                is_rsa_private_algorithm,
            },
            signer::select_signature_hash,
        },
        error::{OpenPgpWriteError, pgp_internal},
        format::{FixedCapacityWriter, fingerprint_hex, normalize_fingerprint},
        message::{
            DataSignatureVerificationTime, OpenPgpReadBudget,
            evaluate_preverified_signatures_with_recipients, parse_public_key_documents,
        },
        packet::{
            MAX_PARTIAL_BODY_CHUNKS, RawPacketStream, TolerantArmorReader,
            armor::signature_include_checksum,
        },
        policy::{
            ComponentPolicy, OpenPgpPolicyBudget, OpenPgpPolicyError, PublicComponent,
            all_components, reference_time, validate_certificate,
            validate_certificate_with_policy_time,
        },
    },
};

const MAX_OPENPGP_KEYS: usize = 64;
const MAX_OPENPGP_COMPONENTS: usize = 64;
const MAX_OPENPGP_NESTING: usize = 64;
// One request may contain `MAX_OPENPGP_KEYS` certificates, each with a
// private primary plus `MAX_OPENPGP_COMPONENTS` private subkeys.  Keep the
// private-operation ceiling request-global while allowing one anonymous
// PKESK to reach every accepted component.
const MAX_OPENPGP_PRIVATE_COMPONENTS_PER_CERTIFICATE: usize =
    match MAX_OPENPGP_COMPONENTS.checked_add(1) {
        Some(value) => value,
        None => panic!("OpenPGP component limit overflow"),
    };
const MAX_OPENPGP_PRIVATE_KEY_ATTEMPTS_PER_REQUEST: usize =
    match MAX_OPENPGP_KEYS.checked_mul(MAX_OPENPGP_PRIVATE_COMPONENTS_PER_CERTIFICATE) {
        Some(value) => value,
        None => panic!("OpenPGP request component limit overflow"),
    };
// Shares the certificate bound with import, merge, mutation, and the agent:
// a certificate the pipeline itself can store and sign with must stay usable
// for sign/encrypt/decrypt here too.
const MAX_OPENPGP_PACKETS: usize = crate::openpgp::packet::MAX_CERTIFICATE_PACKETS;
const MAX_FILE_NAME_BYTES: usize = 4 * 1024;
const MAX_CLEAR_SIGNED_PENDING_WHITESPACE_BYTES: usize = 64 * 1024;
const GNUPG_AEAD_CHUNK_OCTET: u8 = 10;
const GNUPG_AEAD_CHUNK_BYTES: usize = 1 << (GNUPG_AEAD_CHUNK_OCTET as usize + 6);
#[cfg(test)]
const AEAD_TAG_BYTES: usize = 16;
const OPENPGP_PARTIAL_PACKET_BYTES: usize = 64 * 1024;
const OPENPGP_PARTIAL_PACKET_OCTET: u8 = 0xf0;
const MAX_OPENPGP_STREAM_WORKERS: usize = 4;
const STREAM_CHANNEL_DEPTH: usize = 4;
static OPENPGP_STREAM_WORKERS: AtomicUsize = AtomicUsize::new(0);

type Aes256Ocb = Ocb3<Aes256, U15, U16>;

mod common;
mod decryption;
mod encryption;
mod model;
mod signing;
mod streaming;

use common::*;
#[cfg(test)]
use decryption::*;
use encryption::*;
use model::*;
use signing::*;
use streaming::*;

pub(crate) use decryption::OpenPgpDecryptionSession;
pub(in crate::openpgp) use decryption::decrypt_request;
pub(crate) use encryption::OpenPgpEncryptionSession;
pub(in crate::openpgp) use encryption::encrypt_request;
pub(in crate::openpgp) use model::{
    ClearSignInput, DecryptInput, DecryptStreamInput, DecryptionResult, DecryptionWarning,
    DetachedSignInput, EncryptInput, EncryptStreamInput, EncryptionResult, LiteralMetadata,
    ProtectionMode, SignInput, SignKind,
};
pub(in crate::openpgp) use signing::sign_request;
pub(crate) use signing::{ClearSigningSession, DetachedSigningSession};

#[cfg(test)]
mod tests;
