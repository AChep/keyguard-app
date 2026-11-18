//! The tests of KKhfs, whose ML-KEM needs `full`. The transport contract and
//! session isolation run over XX in the parent module, in every build.

use super::*;

fn kk(initiator: bool, keys: &Keys, remote: &[u8]) -> u64 {
    open(
        NoisePattern::KkHfs,
        initiator,
        &keys.private,
        remote,
        &[],
        PROLOGUE,
    )
    .expect("KKhfs")
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
fn kk_hfs_carries_an_ml_kem_key_and_ciphertext() {
    let (alice, bob) = (keys(), keys());
    let initiator = kk(true, &alice, &bob.public);
    let responder = kk(false, &bob, &alice.public);
    // An ephemeral key, an encrypted ML-KEM-1024 key or ciphertext, and the
    // tag of the empty payload.
    let expected = KEY_BYTES + crate::ml_kem::PUBLIC_KEY_BYTES + TAG_BYTES + TAG_BYTES;
    let first = write(initiator, b"").unwrap();
    assert_eq!(first.len(), expected);
    read(responder, &first).unwrap();
    let second = write(responder, b"").unwrap();
    assert_eq!(second.len(), expected);
    read(initiator, &second).unwrap();
    let (initiator, responder) = into_transports(initiator, responder);
    assert_transports_talk(initiator, responder);
    close(initiator);
    close(responder);
}

#[test]
fn a_changed_ml_kem_part_fails_the_handshake() {
    // The first message carries the encapsulation key, the second the
    // ciphertext; both right after the 32-byte ephemeral key.
    for tamper_first in [true, false] {
        let (alice, bob) = (keys(), keys());
        let initiator = kk(true, &alice, &bob.public);
        let responder = kk(false, &bob, &alice.public);
        let mut first = write(initiator, b"").unwrap();
        if tamper_first {
            first[KEY_BYTES + 7] ^= 1;
            assert_eq!(read(responder, &first), Err(AuthenticationFailed));
        } else {
            read(responder, &first).unwrap();
            let mut second = write(responder, b"").unwrap();
            second[KEY_BYTES + 7] ^= 1;
            assert_eq!(read(initiator, &second), Err(AuthenticationFailed));
        }
        close(initiator);
        close(responder);
    }
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
fn kk_hfs_rejects_invalid_arguments() {
    let (alice, bob) = (keys(), keys());
    let key = alice.private.as_slice();
    let remote = bob.public.as_slice();
    let oversized_prologue = vec![0; MAX_PROLOGUE_BYTES + 1];
    assert_opens_are_rejected(&[
        (NoisePattern::KkHfs, key, remote, &PSK, PROLOGUE),
        (NoisePattern::KkHfs, key, &[], &[], PROLOGUE),
        (NoisePattern::KkHfs, key, &remote[..31], &[], PROLOGUE),
        (NoisePattern::KkHfs, key, remote, &[], &oversized_prologue),
    ]);
}
