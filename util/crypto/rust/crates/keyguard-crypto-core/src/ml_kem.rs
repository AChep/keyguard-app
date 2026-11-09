//! ML-KEM-1024 (FIPS 203) through AWS-LC.
//!
//! Device-to-device sync uses it in the `hfs` handshakes of Noise, see
//! [`crate::noise`], and to seal the items a device leaves on the relay to
//! the static key of its peer. A private key is the 3168-byte decapsulation
//! key, a public key the 1568-byte encapsulation key.
//!
//! Decapsulation does not fail on a ciphertext of the right size: ML-KEM
//! rejects a forged one implicitly, with a pseudorandom secret that opens
//! nothing.

use aws_lc_rs::kem::{Ciphertext, DecapsulationKey, EncapsulationKey, ML_KEM_1024};
use prost::Message;
use zeroize::Zeroizing;

use crate::{
    primitives::PrimitiveError,
    protocol::{MlKemDecapsulateResult, MlKemEncapsulateResult, MlKemKeyGenerateResult},
};

/// Length of an encapsulation key.
pub(crate) const PUBLIC_KEY_BYTES: usize = 1568;
/// Length of a decapsulation key.
pub(crate) const PRIVATE_KEY_BYTES: usize = 3168;
/// Length of a ciphertext.
pub(crate) const CIPHERTEXT_BYTES: usize = 1568;
/// Length of a shared secret.
pub(crate) const SHARED_SECRET_BYTES: usize = 32;

/// A shared secret, wiped when it is dropped.
pub(crate) type SharedSecret = Zeroizing<[u8; SHARED_SECRET_BYTES]>;

/// A decapsulation key held in AWS-LC, which cleanses it when it is dropped,
/// and its encapsulation key.
pub(crate) struct KeyPair {
    secret: DecapsulationKey,
    public: Vec<u8>,
}

impl KeyPair {
    /// Generates a key pair from AWS-LC's random source.
    pub(crate) fn generate() -> Result<Self, PrimitiveError> {
        let secret =
            DecapsulationKey::generate(&ML_KEM_1024).map_err(|_| PrimitiveError::CryptoFailure)?;
        let public = secret
            .encapsulation_key()
            .and_then(|key| key.key_bytes())
            .map_err(|_| PrimitiveError::CryptoFailure)?
            .as_ref()
            .to_vec();
        if public.len() != PUBLIC_KEY_BYTES {
            return Err(PrimitiveError::CryptoFailure);
        }
        Ok(Self { secret, public })
    }

    /// The encapsulation key.
    pub(crate) fn public_key(&self) -> &[u8] {
        &self.public
    }

    /// Returns the secret a `ciphertext` encapsulates to this key pair.
    pub(crate) fn decapsulate(&self, ciphertext: &[u8]) -> Result<SharedSecret, PrimitiveError> {
        decapsulate(&self.secret, ciphertext)
    }
}

fn decapsulate(
    secret: &DecapsulationKey,
    ciphertext: &[u8],
) -> Result<SharedSecret, PrimitiveError> {
    if ciphertext.len() != CIPHERTEXT_BYTES {
        return Err(PrimitiveError::InvalidArgument);
    }
    // AWS-LC runs the FIPS 203 hash check of the private key when it
    // decapsulates; a well-sized ciphertext and key fail only that.
    let secret = secret
        .decapsulate(Ciphertext::from(ciphertext))
        .map_err(|_| PrimitiveError::InvalidArgument)?;
    shared_secret(secret.as_ref())
}

/// Generates a key pair; returns an encoded [`MlKemKeyGenerateResult`].
pub(crate) fn key_generate() -> Result<Vec<u8>, PrimitiveError> {
    let pair = KeyPair::generate()?;
    let private_key = pair
        .secret
        .key_bytes()
        .map_err(|_| PrimitiveError::CryptoFailure)?;
    if private_key.as_ref().len() != PRIVATE_KEY_BYTES {
        return Err(PrimitiveError::CryptoFailure);
    }
    Ok(MlKemKeyGenerateResult {
        private_key: private_key.as_ref().to_vec(),
        public_key: pair.public,
    }
    .encode_to_vec())
}

/// Encapsulates a fresh secret to `public_key`; returns an encoded
/// [`MlKemEncapsulateResult`].
pub(crate) fn encapsulate_op(public_key: &[u8]) -> Result<Vec<u8>, PrimitiveError> {
    let (ciphertext, secret) = encapsulate(public_key)?;
    Ok(MlKemEncapsulateResult {
        ciphertext,
        shared_secret: secret.to_vec(),
    }
    .encode_to_vec())
}

/// Returns the secret `ciphertext` encapsulates to `private_key`, as an
/// encoded [`MlKemDecapsulateResult`].
pub(crate) fn decapsulate_op(
    private_key: Vec<u8>,
    ciphertext: &[u8],
) -> Result<Vec<u8>, PrimitiveError> {
    // The dispatcher moves the secret out of the protobuf request, so the
    // local must own it under a zeroizing guard before any early return.
    let private_key = Zeroizing::new(private_key);
    if private_key.len() != PRIVATE_KEY_BYTES {
        return Err(PrimitiveError::InvalidArgument);
    }
    let secret = DecapsulationKey::new(&ML_KEM_1024, &private_key)
        .map_err(|_| PrimitiveError::InvalidArgument)?;
    let shared = decapsulate(&secret, ciphertext)?;
    Ok(MlKemDecapsulateResult {
        shared_secret: shared.to_vec(),
    }
    .encode_to_vec())
}

/// Encapsulates a fresh secret to `public_key`; returns the ciphertext and
/// the secret. A key of the wrong size, or one that fails the FIPS 203 check,
/// is an invalid argument.
pub(crate) fn encapsulate(public_key: &[u8]) -> Result<(Vec<u8>, SharedSecret), PrimitiveError> {
    if public_key.len() != PUBLIC_KEY_BYTES {
        return Err(PrimitiveError::InvalidArgument);
    }
    let key = EncapsulationKey::new(&ML_KEM_1024, public_key)
        .map_err(|_| PrimitiveError::InvalidArgument)?;
    // AWS-LC runs the FIPS 203 modulus check of the key when it encapsulates;
    // a well-sized key fails only that.
    let (ciphertext, secret) = key
        .encapsulate()
        .map_err(|_| PrimitiveError::InvalidArgument)?;
    let ciphertext = ciphertext.as_ref().to_vec();
    if ciphertext.len() != CIPHERTEXT_BYTES {
        return Err(PrimitiveError::CryptoFailure);
    }
    Ok((ciphertext, shared_secret(secret.as_ref())?))
}

fn shared_secret(bytes: &[u8]) -> Result<SharedSecret, PrimitiveError> {
    let mut secret = Zeroizing::new([0_u8; SHARED_SECRET_BYTES]);
    if bytes.len() != SHARED_SECRET_BYTES {
        return Err(PrimitiveError::CryptoFailure);
    }
    secret.copy_from_slice(bytes);
    Ok(secret)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_secret_round_trips() {
        let pair = KeyPair::generate().expect("generate");
        assert_eq!(pair.public_key().len(), PUBLIC_KEY_BYTES);
        let (ciphertext, sent) = encapsulate(pair.public_key()).expect("encapsulate");
        assert_eq!(ciphertext.len(), CIPHERTEXT_BYTES);
        let received = pair.decapsulate(&ciphertext).expect("decapsulate");
        assert_eq!(*sent, *received);
    }

    #[test]
    fn a_forged_ciphertext_yields_another_secret() {
        let pair = KeyPair::generate().expect("generate");
        let (mut ciphertext, sent) = encapsulate(pair.public_key()).expect("encapsulate");
        ciphertext[0] ^= 1;
        let received = pair.decapsulate(&ciphertext).expect("implicit rejection");
        assert_ne!(*sent, *received);
    }

    #[test]
    fn the_operations_round_trip_through_the_protocol() {
        let pair = MlKemKeyGenerateResult::decode(key_generate().expect("generate").as_slice())
            .expect("generate result");
        assert_eq!(pair.private_key.len(), PRIVATE_KEY_BYTES);
        assert_eq!(pair.public_key.len(), PUBLIC_KEY_BYTES);
        let sent = MlKemEncapsulateResult::decode(
            encapsulate_op(&pair.public_key)
                .expect("encapsulate")
                .as_slice(),
        )
        .expect("encapsulate result");
        let received = MlKemDecapsulateResult::decode(
            decapsulate_op(pair.private_key.clone(), &sent.ciphertext)
                .expect("decapsulate")
                .as_slice(),
        )
        .expect("decapsulate result");
        assert_eq!(sent.shared_secret, received.shared_secret);
        assert!(matches!(
            decapsulate_op(pair.private_key[1..].to_vec(), &sent.ciphertext),
            Err(PrimitiveError::InvalidArgument)
        ));
    }

    #[test]
    fn sizes_are_checked() {
        let pair = KeyPair::generate().expect("generate");
        assert!(matches!(
            encapsulate(&pair.public_key()[1..]),
            Err(PrimitiveError::InvalidArgument)
        ));
        assert!(matches!(
            pair.decapsulate(&[0; CIPHERTEXT_BYTES - 1]),
            Err(PrimitiveError::InvalidArgument)
        ));
        // FIPS 203 rejects an encapsulation key whose coefficients are not
        // reduced modulo q.
        assert!(matches!(
            encapsulate(&[0xff; PUBLIC_KEY_BYTES]),
            Err(PrimitiveError::InvalidArgument)
        ));
    }
}
