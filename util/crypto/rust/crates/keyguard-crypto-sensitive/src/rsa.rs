//! Audited AWS-LC adapter for compatibility RSA generation and signing.
//!
//! AWS-LC's safe Rust API intentionally excludes RSA-1024 generation and
//! SHA-1 signing. Keyguard must retain both for existing SSH data, so this
//! module contains the narrow raw API surface needed to preserve that behavior.

use std::{
    ffi::c_void,
    fmt,
    ptr::{self, NonNull},
};

use aws_lc_sys as aws_lc;
use num_bigint_dig::{BigUint, traits::ModInverse};
use num_integer::Integer;
use zeroize::{Zeroize, Zeroizing};

use crate::{DigestAlgorithm, DigestContext};

const MIN_RSA_MODULUS_BYTES: usize = 128;
const MAX_RSA_MODULUS_BYTES: usize = 1_024;
const MAX_PUBLIC_EXPONENT_BYTES: usize = 16;
const MAX_PRIVATE_DER_BYTES: usize = 16 * 1_024;
const MAX_RECOVERY_BASES: u32 = 64;
const MAX_RECOVERY_SQUARINGS: usize = 128;

/// Hash functions supported by SSH RSA PKCS#1 v1.5 signatures.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RsaSignatureHash {
    /// Legacy `ssh-rsa` signing.
    Sha1,
    /// SHA-224 retained for GnuPG agent digest compatibility.
    Sha224,
    /// `rsa-sha2-256` signing.
    Sha256,
    /// SHA-384 retained for GnuPG agent digest compatibility.
    Sha384,
    /// `rsa-sha2-512` signing.
    Sha512,
}

impl RsaSignatureHash {
    const fn digest(self) -> DigestAlgorithm {
        match self {
            Self::Sha1 => DigestAlgorithm::Sha1,
            Self::Sha224 => DigestAlgorithm::Sha224,
            Self::Sha256 => DigestAlgorithm::Sha256,
            Self::Sha384 => DigestAlgorithm::Sha384,
            Self::Sha512 => DigestAlgorithm::Sha512,
        }
    }

    const fn nid(self) -> i32 {
        match self {
            Self::Sha1 => aws_lc::NID_sha1,
            Self::Sha224 => aws_lc::NID_sha224,
            Self::Sha256 => aws_lc::NID_sha256,
            Self::Sha384 => aws_lc::NID_sha384,
            Self::Sha512 => aws_lc::NID_sha512,
        }
    }
}

/// Stable, non-sensitive failures from the RSA compatibility backend.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SensitiveRsaError {
    /// Key generation was requested with a size outside the frozen set.
    InvalidKeySize,
    /// Supplied private components were malformed or mathematically invalid.
    InvalidKey,
    /// A bounded output allocation failed.
    AllocationFailure,
    /// AWS-LC rejected an otherwise well-formed backend operation.
    BackendFailure,
}

impl fmt::Display for SensitiveRsaError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(match self {
            Self::InvalidKeySize => "invalid RSA key size",
            Self::InvalidKey => "invalid RSA private key",
            Self::AllocationFailure => "RSA backend allocation failure",
            Self::BackendFailure => "RSA backend failure",
        })
    }
}

impl std::error::Error for SensitiveRsaError {}

/// Complete RSA Chinese-remainder components, encoded as unsigned big-endian
/// integers.
pub struct RsaCrtComponents {
    prime_p: Vec<u8>,
    prime_q: Vec<u8>,
    exponent_p: Vec<u8>,
    exponent_q: Vec<u8>,
    coefficient: Vec<u8>,
}

/// RSA prime factors and OpenSSH's `q^-1 mod p` coefficient.
///
/// OpenSSH private-key records omit `d mod (p-1)` and `d mod (q-1)`. This
/// owned input lets the sensitive backend derive those values and validate
/// the complete key with AWS-LC before emitting canonical PKCS#1 DER.
pub struct RsaPrimeComponents {
    prime_p: Vec<u8>,
    prime_q: Vec<u8>,
    coefficient: Vec<u8>,
}

impl RsaPrimeComponents {
    /// Takes ownership of the OpenSSH prime component set.
    #[must_use]
    pub fn new(prime_p: Vec<u8>, prime_q: Vec<u8>, coefficient: Vec<u8>) -> Self {
        Self {
            prime_p,
            prime_q,
            coefficient,
        }
    }
}

impl Drop for RsaPrimeComponents {
    fn drop(&mut self) {
        self.prime_p.zeroize();
        self.prime_q.zeroize();
        self.coefficient.zeroize();
    }
}

impl RsaCrtComponents {
    /// Takes ownership of a complete CRT component set.
    #[must_use]
    pub fn new(
        prime_p: Vec<u8>,
        prime_q: Vec<u8>,
        exponent_p: Vec<u8>,
        exponent_q: Vec<u8>,
        coefficient: Vec<u8>,
    ) -> Self {
        Self {
            prime_p,
            prime_q,
            exponent_p,
            exponent_q,
            coefficient,
        }
    }
}

impl Drop for RsaCrtComponents {
    fn drop(&mut self) {
        self.prime_p.zeroize();
        self.prime_q.zeroize();
        self.exponent_p.zeroize();
        self.exponent_q.zeroize();
        self.coefficient.zeroize();
    }
}

/// Owned RSA private components, encoded as unsigned big-endian integers.
///
/// The CRT set may be omitted for legacy imported `n/e/d` keys. In that case
/// it is reconstructed in bounded arithmetic and then validated by AWS-LC
/// before any private operation.
pub struct RsaPrivateComponents {
    modulus: Vec<u8>,
    public_exponent: Vec<u8>,
    private_exponent: Vec<u8>,
    crt: Option<RsaCrtComponents>,
}

impl RsaPrivateComponents {
    /// Takes ownership of RSA private components.
    #[must_use]
    pub fn new(
        modulus: Vec<u8>,
        public_exponent: Vec<u8>,
        private_exponent: Vec<u8>,
        crt: Option<RsaCrtComponents>,
    ) -> Self {
        Self {
            modulus,
            public_exponent,
            private_exponent,
            crt,
        }
    }

    fn validated(&self) -> Result<ValidatedComponents<'_>, SensitiveRsaError> {
        let modulus = positive_bytes(&self.modulus, MAX_RSA_MODULUS_BYTES)?;
        if modulus.len() < MIN_RSA_MODULUS_BYTES {
            return Err(SensitiveRsaError::InvalidKey);
        }

        let public_exponent = positive_bytes(&self.public_exponent, MAX_PUBLIC_EXPONENT_BYTES)?;
        let private_exponent = positive_bytes(&self.private_exponent, MAX_RSA_MODULUS_BYTES)?;

        let crt = self
            .crt
            .as_ref()
            .map(|crt| {
                Ok(ValidatedCrt {
                    prime_p: positive_bytes(&crt.prime_p, MAX_RSA_MODULUS_BYTES)?,
                    prime_q: positive_bytes(&crt.prime_q, MAX_RSA_MODULUS_BYTES)?,
                    exponent_p: positive_bytes(&crt.exponent_p, MAX_RSA_MODULUS_BYTES)?,
                    exponent_q: positive_bytes(&crt.exponent_q, MAX_RSA_MODULUS_BYTES)?,
                    coefficient: positive_bytes(&crt.coefficient, MAX_RSA_MODULUS_BYTES)?,
                })
            })
            .transpose()?;

        Ok(ValidatedComponents {
            modulus,
            public_exponent,
            private_exponent,
            crt,
        })
    }
}

impl Drop for RsaPrivateComponents {
    fn drop(&mut self) {
        self.modulus.zeroize();
        self.public_exponent.zeroize();
        self.private_exponent.zeroize();
        // `RsaCrtComponents::drop` erases the optional CRT fields.
    }
}

/// Generates a PKCS#1 DER RSA private key with exponent 65537.
///
/// Only the application-frozen sizes 1024, 2048, 3072, and 4096 are admitted.
pub fn generate_rsa_pkcs1_der(bits: u32) -> Result<Zeroizing<Vec<u8>>, SensitiveRsaError> {
    let bits = match bits {
        1_024 | 2_048 | 3_072 | 4_096 => {
            i32::try_from(bits).map_err(|_| SensitiveRsaError::InvalidKeySize)?
        }
        _ => return Err(SensitiveRsaError::InvalidKeySize),
    };

    aws_lc::init();
    let key = RsaKey::generate(bits)?;
    key.to_pkcs1_der()
}

/// Completes an OpenSSH RSA key and returns validated PKCS#1 DER.
///
/// The OpenSSH container stores `n/e/d/p/q/iqmp`, omitting the two reduced
/// private exponents. They are derived in zeroizing big-integer owners. The
/// supplied factors and coefficient are checked before AWS-LC validates the
/// assembled key with `RSA_check_key`.
pub fn complete_rsa_pkcs1_der(
    components: &RsaPrivateComponents,
    primes: &RsaPrimeComponents,
) -> Result<Zeroizing<Vec<u8>>, SensitiveRsaError> {
    aws_lc::init();
    let validated = components.validated()?;
    if validated.crt.is_some() {
        return Err(SensitiveRsaError::InvalidKey);
    }
    let prime_p = positive_bytes(&primes.prime_p, MAX_RSA_MODULUS_BYTES)?;
    let prime_q = positive_bytes(&primes.prime_q, MAX_RSA_MODULUS_BYTES)?;
    let coefficient = positive_bytes(&primes.coefficient, MAX_RSA_MODULUS_BYTES)?;
    let derived = derive_crt_from_provided_primes(validated, prime_p, prime_q, coefficient)?;
    let crt = ValidatedCrt {
        prime_p: &derived.prime_p,
        prime_q: &derived.prime_q,
        exponent_p: &derived.exponent_p,
        exponent_q: &derived.exponent_q,
        coefficient: &derived.coefficient,
    };
    build_key_from_provided_crt(validated, crt)?.to_pkcs1_der()
}

/// Completes and validates RSA private components, returning canonical PKCS#1 DER.
///
/// Existing compatibility records can contain only `n/e/d`. Their CRT values
/// are reconstructed with the same bounded arithmetic used by private
/// operations. Complete records are admitted through the provided-CRT path.
/// AWS-LC validates the resulting key before it is serialized.
///
/// # Errors
///
/// Returns [`SensitiveRsaError::InvalidKey`] when the supplied components are
/// malformed, mathematically inconsistent, or cannot be completed within the
/// bounded recovery policy. Allocation and backend failures retain their
/// corresponding [`SensitiveRsaError`] variants.
pub fn complete_rsa_pkcs1_der_from_components(
    components: &RsaPrivateComponents,
) -> Result<Zeroizing<Vec<u8>>, SensitiveRsaError> {
    aws_lc::init();
    let validated = components.validated()?;
    let key = match validated.crt {
        Some(crt) => build_key_from_provided_crt(validated, crt)?,
        None => build_key_with_recovered_crt(validated)?,
    };
    key.to_pkcs1_der()
}

/// Signs a message using RSA PKCS#1 v1.5 and the selected SSH hash.
///
/// Complete CRT inputs are used directly. Missing CRT inputs are reconstructed
/// from `n/e/d`, then the complete key is admitted only if AWS-LC validates it.
pub fn sign_rsa_pkcs1_v1_5(
    components: &RsaPrivateComponents,
    hash: RsaSignatureHash,
    message: &[u8],
) -> Result<Vec<u8>, SensitiveRsaError> {
    aws_lc::init();
    let validated = components.validated()?;
    let mut key = match validated.crt {
        Some(crt) => build_key_from_provided_crt(validated, crt)?,
        None => build_key_with_recovered_crt(validated)?,
    };

    let digest_algorithm = hash.digest();
    let mut digest = Zeroizing::new([0_u8; 64]);
    let digest_output = &mut digest[..digest_algorithm.output_size()];
    let mut context =
        DigestContext::new(digest_algorithm).map_err(|_| SensitiveRsaError::BackendFailure)?;
    context
        .update(message)
        .map_err(|_| SensitiveRsaError::BackendFailure)?;
    context
        .finalize_into(digest_output)
        .map_err(|_| SensitiveRsaError::BackendFailure)?;

    key.sign_digest(hash, digest_output)
}

/// Signs an already-computed digest using RSA PKCS#1 v1.5.
///
/// OpenPGP packet construction hashes the packet trailer before invoking its
/// signing-key abstraction. This entry point keeps the private operation in
/// AWS-LC without hashing that digest a second time. The digest length must
/// exactly match the selected hash algorithm.
pub fn sign_rsa_pkcs1_v1_5_digest(
    components: &RsaPrivateComponents,
    hash: RsaSignatureHash,
    digest: &[u8],
) -> Result<Vec<u8>, SensitiveRsaError> {
    aws_lc::init();
    let validated = components.validated()?;
    let mut key = match validated.crt {
        Some(crt) => build_key_from_provided_crt(validated, crt)?,
        None => build_key_with_recovered_crt(validated)?,
    };

    key.sign_digest(hash, digest)
}

/// Decrypts RSA PKCS#1 v1.5 ciphertext in the audited AWS-LC boundary.
///
/// OpenPGP stores RSA ciphertext as an MPI and therefore omits leading zero
/// octets. The adapter left-pads a well-formed MPI to the exact modulus width
/// before invoking AWS-LC. All padding and private-key failures intentionally
/// collapse to [`SensitiveRsaError::BackendFailure`] at this boundary.
pub fn decrypt_rsa_pkcs1_v1_5(
    components: &RsaPrivateComponents,
    ciphertext: &[u8],
) -> Result<Zeroizing<Vec<u8>>, SensitiveRsaError> {
    aws_lc::init();
    let validated = components.validated()?;
    let mut key = match validated.crt {
        Some(crt) => build_key_from_provided_crt(validated, crt)?,
        None => build_key_with_recovered_crt(validated)?,
    };

    key.decrypt_pkcs1(ciphertext)
}

/// Performs a blinded raw RSA private operation in the audited AWS-LC boundary.
///
/// GnuPG's agent protocol delegates PKCS#1 decoding to the caller and therefore
/// requires the exact `c^d mod n` primitive. OpenPGP MPIs can omit leading zero
/// octets, so the ciphertext is reconstructed at the modulus width before the
/// private operation. The returned plaintext is always modulus-width; callers
/// which expose an MPI may remove leading zero octets after this function
/// returns.
pub fn decrypt_rsa_raw(
    components: &RsaPrivateComponents,
    ciphertext: &[u8],
) -> Result<Zeroizing<Vec<u8>>, SensitiveRsaError> {
    aws_lc::init();
    let validated = components.validated()?;
    let mut key = match validated.crt {
        Some(crt) => build_key_from_provided_crt(validated, crt)?,
        None => build_key_with_recovered_crt(validated)?,
    };

    key.decrypt_raw(ciphertext)
}

#[derive(Clone, Copy)]
struct ValidatedCrt<'a> {
    prime_p: &'a [u8],
    prime_q: &'a [u8],
    exponent_p: &'a [u8],
    exponent_q: &'a [u8],
    coefficient: &'a [u8],
}

#[derive(Clone, Copy)]
struct ValidatedComponents<'a> {
    modulus: &'a [u8],
    public_exponent: &'a [u8],
    private_exponent: &'a [u8],
    crt: Option<ValidatedCrt<'a>>,
}

fn positive_bytes(input: &[u8], maximum: usize) -> Result<&[u8], SensitiveRsaError> {
    if input.len() > maximum.saturating_add(1) {
        return Err(SensitiveRsaError::InvalidKey);
    }
    let first_nonzero = input
        .iter()
        .position(|byte| *byte != 0)
        .ok_or(SensitiveRsaError::InvalidKey)?;
    let value = &input[first_nonzero..];
    if value.len() > maximum {
        Err(SensitiveRsaError::InvalidKey)
    } else {
        Ok(value)
    }
}

fn build_key_from_provided_crt(
    components: ValidatedComponents<'_>,
    crt: ValidatedCrt<'_>,
) -> Result<RsaKey, SensitiveRsaError> {
    let modulus = BigNum::from_bytes(components.modulus)?;
    let public_exponent = BigNum::from_bytes(components.public_exponent)?;
    let private_exponent = BigNum::from_bytes(components.private_exponent)?;
    let prime_p = BigNum::from_bytes(crt.prime_p)?;
    let prime_q = BigNum::from_bytes(crt.prime_q)?;
    let exponent_p = BigNum::from_bytes(crt.exponent_p)?;
    let exponent_q = BigNum::from_bytes(crt.exponent_q)?;
    let coefficient = BigNum::from_bytes(crt.coefficient)?;

    RsaKey::from_big_nums(
        &modulus,
        &public_exponent,
        &private_exponent,
        &prime_p,
        &prime_q,
        &exponent_p,
        &exponent_q,
        &coefficient,
    )
}

fn build_key_with_recovered_crt(
    components: ValidatedComponents<'_>,
) -> Result<RsaKey, SensitiveRsaError> {
    let modulus_value = Zeroizing::new(BigUint::from_bytes_be(components.modulus));
    let public_exponent_value = Zeroizing::new(BigUint::from_bytes_be(components.public_exponent));
    let private_exponent_value =
        Zeroizing::new(BigUint::from_bytes_be(components.private_exponent));
    validate_recovery_inputs(
        &modulus_value,
        &public_exponent_value,
        &private_exponent_value,
    )?;
    let recovered = recover_crt(
        &modulus_value,
        &public_exponent_value,
        &private_exponent_value,
    )?;

    let modulus = BigNum::from_bytes(components.modulus)?;
    let public_exponent = BigNum::from_bytes(components.public_exponent)?;
    let private_exponent = BigNum::from_bytes(components.private_exponent)?;
    let prime_p = BigNum::from_biguint(&recovered.prime_p)?;
    let prime_q = BigNum::from_biguint(&recovered.prime_q)?;
    let exponent_p = BigNum::from_biguint(&recovered.exponent_p)?;
    let exponent_q = BigNum::from_biguint(&recovered.exponent_q)?;
    let coefficient = BigNum::from_biguint(&recovered.coefficient)?;

    RsaKey::from_big_nums(
        &modulus,
        &public_exponent,
        &private_exponent,
        &prime_p,
        &prime_q,
        &exponent_p,
        &exponent_q,
        &coefficient,
    )
}

fn validate_recovery_inputs(
    modulus: &BigUint,
    public_exponent: &BigUint,
    private_exponent: &BigUint,
) -> Result<(), SensitiveRsaError> {
    let one = BigUint::from(1_u8);
    let modulus_bits = modulus.bits();
    if !(1_024..=8_192).contains(&modulus_bits)
        || modulus.is_even()
        || public_exponent <= &one
        || public_exponent >= modulus
        || public_exponent.is_even()
        || private_exponent <= &one
        || private_exponent >= modulus
    {
        return Err(SensitiveRsaError::InvalidKey);
    }
    Ok(())
}

struct RecoveredCrt {
    prime_p: Zeroizing<BigUint>,
    prime_q: Zeroizing<BigUint>,
    exponent_p: Zeroizing<BigUint>,
    exponent_q: Zeroizing<BigUint>,
    coefficient: Zeroizing<BigUint>,
}

fn recover_crt(
    modulus: &BigUint,
    public_exponent: &BigUint,
    private_exponent: &BigUint,
) -> Result<RecoveredCrt, SensitiveRsaError> {
    let one = BigUint::from(1_u8);
    let two = BigUint::from(2_u8);

    // `k = e*d - 1` is even for a valid RSA private key. The multiplication
    // result moves directly into a zeroizing owner.
    let mut k = Zeroizing::new(public_exponent * private_exponent);
    if *k <= one {
        return Err(SensitiveRsaError::InvalidKey);
    }
    *k -= 1_u8;
    let squarings = k
        .trailing_zeros()
        .filter(|count| (1..=MAX_RECOVERY_SQUARINGS).contains(count))
        .ok_or(SensitiveRsaError::InvalidKey)?;

    let mut odd_part = Zeroizing::new((*k).clone());
    *odd_part >>= squarings;
    let mut modulus_minus_one = Zeroizing::new(modulus.clone());
    *modulus_minus_one -= 1_u8;

    for base_value in 2..2 + MAX_RECOVERY_BASES {
        let base = BigUint::from(base_value);
        if base >= *modulus_minus_one {
            break;
        }

        let mut previous = Zeroizing::new(base.modpow(&odd_part, modulus));
        if *previous == one || *previous == *modulus_minus_one {
            continue;
        }

        for _ in 0..squarings {
            let squared = Zeroizing::new(previous.modpow(&two, modulus));
            if *squared == one {
                if let Some(recovered) =
                    derive_crt_from_factor(modulus, private_exponent, &previous)
                {
                    return Ok(recovered);
                }
                break;
            }
            if *squared == *modulus_minus_one {
                break;
            }
            previous = squared;
        }
    }

    Err(SensitiveRsaError::InvalidKey)
}

fn derive_crt_from_factor(
    modulus: &BigUint,
    private_exponent: &BigUint,
    square_root: &BigUint,
) -> Option<RecoveredCrt> {
    let one = BigUint::from(1_u8);
    if square_root <= &one {
        return None;
    }

    let mut candidate = Zeroizing::new(square_root.clone());
    *candidate -= 1_u8;
    let mut prime_p = Zeroizing::new(candidate.gcd(modulus));
    if *prime_p == one || *prime_p == *modulus {
        return None;
    }
    let mut prime_q = Zeroizing::new(modulus / &*prime_p);

    let product = Zeroizing::new(&*prime_p * &*prime_q);
    if (&*product) != modulus {
        return None;
    }
    if *prime_p < *prime_q {
        std::mem::swap(&mut prime_p, &mut prime_q);
    }

    let mut p_minus_one = Zeroizing::new((*prime_p).clone());
    *p_minus_one -= 1_u8;
    let mut q_minus_one = Zeroizing::new((*prime_q).clone());
    *q_minus_one -= 1_u8;
    let exponent_p = Zeroizing::new(private_exponent % &*p_minus_one);
    let exponent_q = Zeroizing::new(private_exponent % &*q_minus_one);

    let coefficient_signed = Zeroizing::new((&*prime_q).mod_inverse(&*prime_p)?);
    let coefficient = Zeroizing::new(coefficient_signed.to_biguint()?);

    Some(RecoveredCrt {
        prime_p,
        prime_q,
        exponent_p,
        exponent_q,
        coefficient,
    })
}

fn derive_crt_from_provided_primes(
    components: ValidatedComponents<'_>,
    prime_p: &[u8],
    prime_q: &[u8],
    coefficient: &[u8],
) -> Result<RsaCrtComponents, SensitiveRsaError> {
    let modulus = Zeroizing::new(BigUint::from_bytes_be(components.modulus));
    let private_exponent = Zeroizing::new(BigUint::from_bytes_be(components.private_exponent));
    let prime_p_value = Zeroizing::new(BigUint::from_bytes_be(prime_p));
    let prime_q_value = Zeroizing::new(BigUint::from_bytes_be(prime_q));
    let coefficient_value = Zeroizing::new(BigUint::from_bytes_be(coefficient));
    validate_recovery_inputs(
        &modulus,
        &BigUint::from_bytes_be(components.public_exponent),
        &private_exponent,
    )?;

    let product = Zeroizing::new(&*prime_p_value * &*prime_q_value);
    if *product != *modulus {
        return Err(SensitiveRsaError::InvalidKey);
    }
    let one = BigUint::from(1_u8);
    if *prime_p_value <= one || *prime_q_value <= one {
        return Err(SensitiveRsaError::InvalidKey);
    }
    let mut p_minus_one = Zeroizing::new((*prime_p_value).clone());
    *p_minus_one -= 1_u8;
    let mut q_minus_one = Zeroizing::new((*prime_q_value).clone());
    *q_minus_one -= 1_u8;
    let exponent_p = Zeroizing::new(&*private_exponent % &*p_minus_one);
    let exponent_q = Zeroizing::new(&*private_exponent % &*q_minus_one);
    let expected_coefficient = Zeroizing::new(
        (&*prime_q_value)
            .mod_inverse(&*prime_p_value)
            .and_then(|value| value.to_biguint())
            .ok_or(SensitiveRsaError::InvalidKey)?,
    );
    if *expected_coefficient != *coefficient_value {
        return Err(SensitiveRsaError::InvalidKey);
    }

    Ok(RsaCrtComponents::new(
        prime_p.to_vec(),
        prime_q.to_vec(),
        exponent_p.to_bytes_be(),
        exponent_q.to_bytes_be(),
        coefficient.to_vec(),
    ))
}

struct BigNum(NonNull<aws_lc::BIGNUM>);

impl BigNum {
    fn from_word(value: aws_lc::BN_ULONG) -> Result<Self, SensitiveRsaError> {
        // SAFETY: `BN_new` takes no arguments and returns a uniquely owned
        // allocation when non-null.
        let raw = unsafe { aws_lc::BN_new() };
        let instance = Self(NonNull::new(raw).ok_or(SensitiveRsaError::AllocationFailure)?);
        // SAFETY: the BIGNUM is live and uniquely owned by `instance`.
        if unsafe { aws_lc::BN_set_word(instance.as_ptr(), value) } != 1 {
            return Err(SensitiveRsaError::BackendFailure);
        }
        Ok(instance)
    }

    fn from_bytes(value: &[u8]) -> Result<Self, SensitiveRsaError> {
        if value.is_empty() {
            return Err(SensitiveRsaError::InvalidKey);
        }
        // SAFETY: `value` is readable for `value.len()` bytes; a null output
        // requests a newly allocated BIGNUM owned by the returned wrapper.
        let raw = unsafe { aws_lc::BN_bin2bn(value.as_ptr(), value.len(), ptr::null_mut()) };
        NonNull::new(raw)
            .map(Self)
            .ok_or(SensitiveRsaError::AllocationFailure)
    }

    fn from_biguint(value: &BigUint) -> Result<Self, SensitiveRsaError> {
        let bytes = Zeroizing::new(value.to_bytes_be());
        Self::from_bytes(&bytes)
    }

    const fn as_ptr(&self) -> *mut aws_lc::BIGNUM {
        self.0.as_ptr()
    }
}

impl Drop for BigNum {
    fn drop(&mut self) {
        // SAFETY: this wrapper uniquely owns the live BIGNUM. `BN_clear_free`
        // erases its limbs before releasing the allocation.
        unsafe { aws_lc::BN_clear_free(self.as_ptr()) }
    }
}

struct RsaKey(NonNull<aws_lc::RSA>);

impl RsaKey {
    fn generate(bits: i32) -> Result<Self, SensitiveRsaError> {
        // SAFETY: `RSA_new` takes no arguments and returns a uniquely owned
        // allocation when non-null.
        let raw = unsafe { aws_lc::RSA_new() };
        let key = Self(NonNull::new(raw).ok_or(SensitiveRsaError::AllocationFailure)?);
        let exponent = BigNum::from_word(65_537)?;
        // SAFETY: `key` and `exponent` are valid live objects, `key` is
        // uniquely accessed, and a null callback is explicitly supported.
        let generated = unsafe {
            aws_lc::RSA_generate_key_ex(key.as_ptr(), bits, exponent.as_ptr(), ptr::null_mut())
        };
        if generated != 1 {
            return Err(SensitiveRsaError::BackendFailure);
        }
        // SAFETY: `key` is a fully initialized, live RSA object.
        if unsafe { aws_lc::RSA_check_key(key.as_ptr()) } != 1 {
            return Err(SensitiveRsaError::BackendFailure);
        }
        Ok(key)
    }

    #[allow(clippy::too_many_arguments)]
    fn from_big_nums(
        modulus: &BigNum,
        public_exponent: &BigNum,
        private_exponent: &BigNum,
        prime_p: &BigNum,
        prime_q: &BigNum,
        exponent_p: &BigNum,
        exponent_q: &BigNum,
        coefficient: &BigNum,
    ) -> Result<Self, SensitiveRsaError> {
        // SAFETY: every pointer references a live BIGNUM for the duration of
        // the call. AWS-LC duplicates all components into a newly allocated RSA
        // object and validates their mathematical relationship.
        let raw = unsafe {
            aws_lc::RSA_new_private_key(
                modulus.as_ptr(),
                public_exponent.as_ptr(),
                private_exponent.as_ptr(),
                prime_p.as_ptr(),
                prime_q.as_ptr(),
                exponent_p.as_ptr(),
                exponent_q.as_ptr(),
                coefficient.as_ptr(),
            )
        };
        let key = Self(NonNull::new(raw).ok_or(SensitiveRsaError::InvalidKey)?);
        // SAFETY: `key` is live and uniquely owned. This redundant validation
        // keeps the admission invariant explicit at the adapter boundary.
        if unsafe { aws_lc::RSA_check_key(key.as_ptr()) } != 1 {
            return Err(SensitiveRsaError::InvalidKey);
        }
        Ok(key)
    }

    fn to_pkcs1_der(&self) -> Result<Zeroizing<Vec<u8>>, SensitiveRsaError> {
        let mut raw = ptr::null_mut();
        let mut length = 0_usize;
        // SAFETY: `self` is a live validated key, and both output pointers are
        // writable. AWS-LC allocates the returned buffer with OPENSSL_malloc.
        let result =
            unsafe { aws_lc::RSA_private_key_to_bytes(&mut raw, &mut length, self.as_ptr()) };
        let allocation = NonNull::new(raw).map(|pointer| OpenSslBytes { pointer, length });
        if result != 1 {
            drop(allocation);
            return Err(SensitiveRsaError::BackendFailure);
        }
        let allocation = allocation.ok_or(SensitiveRsaError::BackendFailure)?;
        if length == 0 || length > MAX_PRIVATE_DER_BYTES {
            return Err(SensitiveRsaError::BackendFailure);
        }

        let mut output = Zeroizing::new(Vec::new());
        output
            .try_reserve_exact(length)
            .map_err(|_| SensitiveRsaError::AllocationFailure)?;
        output.extend_from_slice(allocation.as_slice());
        Ok(output)
    }

    fn sign_digest(
        &mut self,
        hash: RsaSignatureHash,
        digest: &[u8],
    ) -> Result<Vec<u8>, SensitiveRsaError> {
        if digest.len() != hash.digest().output_size() {
            return Err(SensitiveRsaError::BackendFailure);
        }
        // SAFETY: `self` is a live validated RSA key.
        let output_size = unsafe { aws_lc::RSA_size(self.as_ptr()) } as usize;
        if !(MIN_RSA_MODULUS_BYTES..=MAX_RSA_MODULUS_BYTES).contains(&output_size) {
            return Err(SensitiveRsaError::InvalidKey);
        }

        let mut signature = Vec::new();
        signature
            .try_reserve_exact(output_size)
            .map_err(|_| SensitiveRsaError::AllocationFailure)?;
        signature.resize(output_size, 0);
        let mut actual_size = 0_u32;
        // SAFETY: all input slices are valid, `signature` is writable for
        // `output_size == RSA_size(self)` bytes, and `self` is uniquely
        // borrowed for an operation which may update internal blinding state.
        let result = unsafe {
            aws_lc::RSA_sign(
                hash.nid(),
                digest.as_ptr(),
                digest.len(),
                signature.as_mut_ptr(),
                &mut actual_size,
                self.as_ptr(),
            )
        };
        if result != 1 || actual_size as usize != output_size {
            signature.zeroize();
            return Err(SensitiveRsaError::BackendFailure);
        }
        Ok(signature)
    }

    fn decrypt_pkcs1(
        &mut self,
        ciphertext: &[u8],
    ) -> Result<Zeroizing<Vec<u8>>, SensitiveRsaError> {
        // SAFETY: `self` is a live validated RSA key.
        let modulus_size = unsafe { aws_lc::RSA_size(self.as_ptr()) } as usize;
        if !(MIN_RSA_MODULUS_BYTES..=MAX_RSA_MODULUS_BYTES).contains(&modulus_size)
            || ciphertext.is_empty()
            || ciphertext.len() > modulus_size
        {
            return Err(SensitiveRsaError::InvalidKey);
        }

        // An OpenPGP MPI does not retain leading zero octets. AWS-LC's RSA
        // primitive admits a fixed-width ciphertext, so reconstruct that
        // representation in a wipeable owner.
        let mut padded = Zeroizing::new(vec![0_u8; modulus_size]);
        let offset = modulus_size - ciphertext.len();
        padded[offset..].copy_from_slice(ciphertext);

        let mut plaintext = Zeroizing::new(vec![0_u8; modulus_size]);
        let mut plaintext_size = 0_usize;
        // SAFETY: the key is live and uniquely borrowed; `padded` is readable
        // for exactly `modulus_size` bytes; `plaintext` is writable for its
        // advertised capacity. AWS-LC performs PKCS#1 type-2 validation.
        let result = unsafe {
            aws_lc::RSA_decrypt(
                self.as_ptr(),
                &mut plaintext_size,
                plaintext.as_mut_ptr(),
                plaintext.len(),
                padded.as_ptr(),
                padded.len(),
                aws_lc::RSA_PKCS1_PADDING,
            )
        };
        if result != 1 || plaintext_size > plaintext.len() {
            return Err(SensitiveRsaError::BackendFailure);
        }
        plaintext.truncate(plaintext_size);
        Ok(plaintext)
    }

    fn decrypt_raw(&mut self, ciphertext: &[u8]) -> Result<Zeroizing<Vec<u8>>, SensitiveRsaError> {
        // SAFETY: `self` is a live validated RSA key.
        let modulus_size = unsafe { aws_lc::RSA_size(self.as_ptr()) } as usize;
        if !(MIN_RSA_MODULUS_BYTES..=MAX_RSA_MODULUS_BYTES).contains(&modulus_size)
            || ciphertext.is_empty()
            || ciphertext.len() > modulus_size
        {
            return Err(SensitiveRsaError::InvalidKey);
        }

        let mut padded = Zeroizing::new(vec![0_u8; modulus_size]);
        let offset = modulus_size - ciphertext.len();
        padded[offset..].copy_from_slice(ciphertext);

        let mut plaintext = Zeroizing::new(vec![0_u8; modulus_size]);
        let mut plaintext_size = 0_usize;
        // SAFETY: the key is live and uniquely borrowed; `padded` is readable
        // for exactly the modulus width and `plaintext` is writable for the
        // same width. `RSA_NO_PADDING` requests the raw private transform while
        // AWS-LC retains the key's CRT validation and internal blinding.
        let result = unsafe {
            aws_lc::RSA_decrypt(
                self.as_ptr(),
                &mut plaintext_size,
                plaintext.as_mut_ptr(),
                plaintext.len(),
                padded.as_ptr(),
                padded.len(),
                aws_lc::RSA_NO_PADDING,
            )
        };
        if result != 1 || plaintext_size != modulus_size {
            return Err(SensitiveRsaError::BackendFailure);
        }
        Ok(plaintext)
    }

    const fn as_ptr(&self) -> *mut aws_lc::RSA {
        self.0.as_ptr()
    }
}

impl Drop for RsaKey {
    fn drop(&mut self) {
        // SAFETY: the RSA object is live and uniquely owned. The getters return
        // borrowed component pointers or null; no external aliases exist while
        // this exclusive destructor runs.
        let private_components = unsafe {
            [
                aws_lc::RSA_get0_d(self.as_ptr()),
                aws_lc::RSA_get0_p(self.as_ptr()),
                aws_lc::RSA_get0_q(self.as_ptr()),
                aws_lc::RSA_get0_dmp1(self.as_ptr()),
                aws_lc::RSA_get0_dmq1(self.as_ptr()),
                aws_lc::RSA_get0_iqmp(self.as_ptr()),
            ]
        };
        for component in private_components {
            if let Some(component) = NonNull::new(component.cast_mut()) {
                // SAFETY: the component is owned by this uniquely owned RSA
                // object. Clearing preserves its allocation for `RSA_free`.
                unsafe { aws_lc::BN_clear(component.as_ptr()) }
            }
        }
        // SAFETY: this wrapper uniquely owns the RSA object and releases it
        // exactly once after its private BIGNUM limbs have been erased.
        unsafe { aws_lc::RSA_free(self.as_ptr()) }
    }
}

struct OpenSslBytes {
    pointer: NonNull<u8>,
    length: usize,
}

impl OpenSslBytes {
    fn as_slice(&self) -> &[u8] {
        // SAFETY: AWS-LC allocated `pointer` for exactly `length` initialized
        // bytes, and this wrapper retains ownership for the returned lifetime.
        unsafe { std::slice::from_raw_parts(self.pointer.as_ptr(), self.length) }
    }
}

impl Drop for OpenSslBytes {
    fn drop(&mut self) {
        // SAFETY: the buffer is still allocated for `length` bytes. Cleanse
        // performs non-elidable erasure before OPENSSL_free releases it.
        unsafe {
            aws_lc::OPENSSL_cleanse(self.pointer.as_ptr().cast::<c_void>(), self.length);
            aws_lc::OPENSSL_free(self.pointer.as_ptr().cast::<c_void>());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const PARITY_MESSAGE: &[u8] = b"keyguard ssh signing parity vector";
    const PARITY_MODULUS_HEX: &str = concat!(
        "e37f505a9f8b834f4a8340a7a8dbd820c837ec855522f302a9e99f0a825b225b",
        "aa5f9a03f04dc4425347375e3eada0f125b97fc68b7b8859f9918369a17955df",
        "b18924df2a292a37ca74e604b884d9942f7f9728279b6e4180a3a9fe143e579e",
        "60da3f61a5c9ce9c195771014ad9fe99c8f79a2a97b50fc8266fd85c5f83c1cc",
        "01bf333768dc5c0fa3e7593f23651175a84f5eafad3211b53adac365de14707e",
        "42e5cf8800a3981fc5a6d8d0ee26fe592a46bcf10e0a75f24224b654d13b3aeb",
        "16641837b77567b1af01028ecce7664851c05c473327ef7bc64c20944a9c74c0",
        "3d43323207f2f3ed6c8b03cfe9575969c589f925f3fc7a46a5add107f91b273d",
    );
    const PARITY_PRIVATE_EXPONENT_HEX: &str = concat!(
        "2b8b32d625b38e6e9ed4808b96c67d97c8baeb8a99c116c26bc34badb745ba4d",
        "d14e7b2c45d29cbe15328c667d98be356a031771e940bbd87cec5d3adaad5ec2",
        "7238cdd5e93bf7d8b5e7aa1f3d3230732ca43f4a704a4fd039cf19ba8825669f",
        "e9e9dc37d483cc0814d1a7cf978422374d4017420fc7983db71db14bfcfc3b1b",
        "74c01ebe51082fd61dbed09f5ab85e8debbe0b4cacced1010273d6dcd713713d",
        "d0b890a8358810d9b33d390d38536c993b27b03d8ade5678574e40901fa67f7d",
        "1e552c23427c4af7210cecbecb0732a7b069f6e5c8d8896e5c5d77b8298fd782",
        "13b445c86164d74df5729ac8f266622afbfed18517910d3c9a9bc2cf67579d",
    );
    const PARITY_SHA256_SIGNATURE_HEX: &str = concat!(
        "77b339ba6ca43daed47a7ae98d97c58993c13497d58bad1add717e0f21963e49",
        "e876dd17a88d6c235d79c66aaabc6eb763acbb1b4f8f40872137a85c73f75dcc",
        "4bfa7719362054aba988c843c299dd987caf3ce0fd656c8e8eb0e6bd0609781f",
        "fd335fc26d3e52736166aa9a323f667d094930c4c904660297e9203b2f0be21a",
        "4b91f1fa13010f1b1a8cd2f574cc9df6be7d568d032029b74feef0eb1632d9df",
        "bc77045451e870235392b67f88ffc0dec716afee694a19d359d96a8e7027fc5a",
        "95002ec8015b973445b59205aa9b80e9e36b3562448f25c169ebd93bb1f9915",
        "c7f267825abadd0855306c0f328f468a8e3fd3243f86c3edcbcdebadb6ceac027",
    );

    #[test]
    fn generation_supports_every_frozen_size_and_exponent() {
        for bits in [1_024_u32, 2_048, 3_072, 4_096] {
            let der = generate_rsa_pkcs1_der(bits).expect("RSA generation must succeed");
            let key = parse_pkcs1_der(&der);
            // SAFETY: `key` is a live RSA object parsed and owned by the helper.
            assert_eq!(unsafe { aws_lc::RSA_bits(key.as_ptr()) }, bits);
            // SAFETY: `key` is live and its public exponent is present on every
            // private key admitted by the parser.
            let exponent = unsafe { aws_lc::RSA_get0_e(key.as_ptr()) };
            assert!(!exponent.is_null());
            // SAFETY: the non-null exponent is borrowed from the live key.
            assert_eq!(unsafe { aws_lc::BN_cmp_word(exponent, 65_537) }, 0);
        }
    }

    #[test]
    fn generated_rsa_1024_signs_all_ssh_hashes() {
        let der = generate_rsa_pkcs1_der(1_024).expect("RSA-1024 generation must succeed");
        let key = parse_pkcs1_der(&der);
        let components = components_from_key(&key);

        for hash in [
            RsaSignatureHash::Sha1,
            RsaSignatureHash::Sha224,
            RsaSignatureHash::Sha256,
            RsaSignatureHash::Sha384,
            RsaSignatureHash::Sha512,
        ] {
            let signature = sign_rsa_pkcs1_v1_5(&components, hash, b"rsa-1024 regression")
                .expect("RSA signing must succeed");
            assert!(verify(&key, hash, b"rsa-1024 regression", &signature));
        }
    }

    #[test]
    fn incomplete_n_e_d_reconstructs_and_matches_known_answer() {
        let components = RsaPrivateComponents::new(
            decode_hex(PARITY_MODULUS_HEX),
            decode_hex("010001"),
            decode_hex(PARITY_PRIVATE_EXPONENT_HEX),
            None,
        );
        let signature = sign_rsa_pkcs1_v1_5(&components, RsaSignatureHash::Sha256, PARITY_MESSAGE)
            .expect("incomplete RSA key must reconstruct");
        assert_eq!(signature, decode_hex(PARITY_SHA256_SIGNATURE_HEX));
    }

    #[test]
    fn incomplete_n_e_d_serializes_as_a_valid_complete_pkcs1_key() {
        let components = RsaPrivateComponents::new(
            decode_hex(PARITY_MODULUS_HEX),
            decode_hex("010001"),
            decode_hex(PARITY_PRIVATE_EXPONENT_HEX),
            None,
        );

        let der = complete_rsa_pkcs1_der_from_components(&components)
            .expect("incomplete RSA key must serialize after CRT recovery");
        let key = parse_pkcs1_der(&der);
        let completed = components_from_key(&key);

        assert_eq!(completed.modulus, components.modulus);
        assert_eq!(completed.public_exponent, components.public_exponent);
        assert_eq!(completed.private_exponent, components.private_exponent);
        assert!(completed.crt.is_some());
    }

    #[test]
    fn prehashed_signing_matches_message_signing() {
        let components = RsaPrivateComponents::new(
            decode_hex(PARITY_MODULUS_HEX),
            decode_hex("010001"),
            decode_hex(PARITY_PRIVATE_EXPONENT_HEX),
            None,
        );
        let mut digest = Zeroizing::new([0_u8; 32]);
        let mut context = DigestContext::new(DigestAlgorithm::Sha256).expect("digest initializes");
        context.update(PARITY_MESSAGE).expect("digest updates");
        context
            .finalize_into(&mut *digest)
            .expect("digest finalizes");

        let signature =
            sign_rsa_pkcs1_v1_5_digest(&components, RsaSignatureHash::Sha256, &digest[..])
                .expect("prehashed RSA signing must succeed");
        assert_eq!(signature, decode_hex(PARITY_SHA256_SIGNATURE_HEX));
    }

    #[test]
    fn pkcs1_decryption_accepts_mpi_without_leading_zero_octets() {
        let der = generate_rsa_pkcs1_der(1_024).expect("RSA-1024 generation must succeed");
        let key = parse_pkcs1_der(&der);
        let components = components_from_key(&key);
        let plaintext = b"\x09keyguard openpgp session key";
        let ciphertext = encrypt_pkcs1(&key, plaintext);
        let first_nonzero = ciphertext
            .iter()
            .position(|byte| *byte != 0)
            .expect("RSA ciphertext is nonzero");

        let decrypted = decrypt_rsa_pkcs1_v1_5(&components, &ciphertext[first_nonzero..])
            .expect("MPI-shaped ciphertext must decrypt");
        assert_eq!(&*decrypted, plaintext);
    }

    #[test]
    fn pkcs1_decryption_collapses_invalid_padding_to_backend_failure() {
        let der = generate_rsa_pkcs1_der(1_024).expect("RSA-1024 generation must succeed");
        let key = parse_pkcs1_der(&der);
        let components = components_from_key(&key);
        let malformed = vec![0x42_u8; 128];

        assert_eq!(
            decrypt_rsa_pkcs1_v1_5(&components, &malformed).expect_err("padding must fail"),
            SensitiveRsaError::BackendFailure,
        );
    }

    #[test]
    fn raw_decryption_returns_the_full_modulus_width() {
        let der = generate_rsa_pkcs1_der(1_024).expect("RSA-1024 generation must succeed");
        let key = parse_pkcs1_der(&der);
        let components = components_from_key(&key);
        let mut plaintext = vec![0_u8; 128];
        plaintext[120..].copy_from_slice(b"raw rsa!");
        let ciphertext = encrypt_raw(&key, &plaintext);

        let decrypted =
            decrypt_rsa_raw(&components, &ciphertext).expect("raw RSA ciphertext must decrypt");
        assert_eq!(&*decrypted, &plaintext);
    }

    #[test]
    fn raw_decryption_rejects_empty_and_oversized_ciphertexts() {
        let der = generate_rsa_pkcs1_der(1_024).expect("RSA-1024 generation must succeed");
        let key = parse_pkcs1_der(&der);
        let components = components_from_key(&key);

        assert_eq!(
            decrypt_rsa_raw(&components, &[]).expect_err("empty ciphertext must fail"),
            SensitiveRsaError::InvalidKey,
        );
        assert_eq!(
            decrypt_rsa_raw(&components, &[0x01; 129]).expect_err("oversized ciphertext must fail"),
            SensitiveRsaError::InvalidKey,
        );
    }

    #[test]
    fn generation_rejects_non_frozen_sizes() {
        for bits in [0_u32, 512, 1_536, 8_192] {
            assert_eq!(
                generate_rsa_pkcs1_der(bits).expect_err("size must be rejected"),
                SensitiveRsaError::InvalidKeySize,
            );
        }
    }

    #[test]
    fn signing_rejects_empty_and_oversized_components() {
        let empty = RsaPrivateComponents::new(Vec::new(), vec![1, 0, 1], vec![1], None);
        assert_eq!(
            sign_rsa_pkcs1_v1_5(&empty, RsaSignatureHash::Sha256, b"message"),
            Err(SensitiveRsaError::InvalidKey),
        );

        let oversized = RsaPrivateComponents::new(
            vec![1; MAX_RSA_MODULUS_BYTES + 2],
            vec![1, 0, 1],
            vec![1],
            None,
        );
        assert_eq!(
            sign_rsa_pkcs1_v1_5(&oversized, RsaSignatureHash::Sha256, b"message"),
            Err(SensitiveRsaError::InvalidKey),
        );
    }

    #[test]
    fn signing_rejects_inconsistent_complete_crt() {
        let der = generate_rsa_pkcs1_der(1_024).expect("RSA generation must succeed");
        let key = parse_pkcs1_der(&der);
        let mut components = components_from_key(&key);
        let crt = components.crt.as_mut().expect("generated key has CRT");
        crt.coefficient[0] ^= 1;
        assert_eq!(
            sign_rsa_pkcs1_v1_5(&components, RsaSignatureHash::Sha256, b"message"),
            Err(SensitiveRsaError::InvalidKey),
        );
    }

    fn parse_pkcs1_der(der: &[u8]) -> RsaKey {
        let mut input = der.as_ptr();
        let length = std::ffi::c_long::try_from(der.len()).expect("fixture length fits c_long");
        // SAFETY: `input` points to `der.len()` readable bytes. A null output
        // handle asks AWS-LC to allocate and return a new RSA object.
        let raw = unsafe { aws_lc::d2i_RSAPrivateKey(ptr::null_mut(), &mut input, length) };
        assert_eq!(input as usize, der.as_ptr() as usize + der.len());
        RsaKey(NonNull::new(raw).expect("generated DER must parse"))
    }

    fn components_from_key(key: &RsaKey) -> RsaPrivateComponents {
        // SAFETY: all getters borrow fields from the live validated private key.
        let values = unsafe {
            [
                aws_lc::RSA_get0_n(key.as_ptr()),
                aws_lc::RSA_get0_e(key.as_ptr()),
                aws_lc::RSA_get0_d(key.as_ptr()),
                aws_lc::RSA_get0_p(key.as_ptr()),
                aws_lc::RSA_get0_q(key.as_ptr()),
                aws_lc::RSA_get0_dmp1(key.as_ptr()),
                aws_lc::RSA_get0_dmq1(key.as_ptr()),
                aws_lc::RSA_get0_iqmp(key.as_ptr()),
            ]
        };
        let [
            modulus,
            public_exponent,
            private_exponent,
            prime_p,
            prime_q,
            exponent_p,
            exponent_q,
            coefficient,
        ] = values.map(bn_to_bytes);
        RsaPrivateComponents::new(
            modulus,
            public_exponent,
            private_exponent,
            Some(RsaCrtComponents::new(
                prime_p,
                prime_q,
                exponent_p,
                exponent_q,
                coefficient,
            )),
        )
    }

    fn bn_to_bytes(value: *const aws_lc::BIGNUM) -> Vec<u8> {
        assert!(!value.is_null());
        // SAFETY: the pointer is non-null and borrowed from a live RSA key.
        let length = unsafe { aws_lc::BN_num_bytes(value) } as usize;
        let mut output = vec![0_u8; length];
        // SAFETY: `output` is writable for the exact number of bytes reported
        // by `BN_num_bytes`, and `value` remains live for this call.
        let written = unsafe { aws_lc::BN_bn2bin(value, output.as_mut_ptr()) };
        assert_eq!(written, length);
        output
    }

    fn verify(key: &RsaKey, hash: RsaSignatureHash, message: &[u8], signature: &[u8]) -> bool {
        let digest_algorithm = hash.digest();
        let mut digest = Zeroizing::new([0_u8; 64]);
        let digest_output = &mut digest[..digest_algorithm.output_size()];
        let mut context = DigestContext::new(digest_algorithm).expect("digest initializes");
        context.update(message).expect("digest updates");
        context
            .finalize_into(digest_output)
            .expect("digest finalizes");
        // SAFETY: the digest and signature slices are readable for their
        // lengths and `key` is a live validated RSA key.
        unsafe {
            aws_lc::RSA_verify(
                hash.nid(),
                digest_output.as_ptr(),
                digest_output.len(),
                signature.as_ptr(),
                signature.len(),
                key.as_ptr(),
            ) == 1
        }
    }

    fn encrypt_pkcs1(key: &RsaKey, plaintext: &[u8]) -> Vec<u8> {
        // SAFETY: `key` is a live RSA object.
        let output_size = unsafe { aws_lc::RSA_size(key.as_ptr()) } as usize;
        let mut output = vec![0_u8; output_size];
        // SAFETY: all slices are valid for their declared lengths, the output
        // buffer is exactly `RSA_size`, and the public operation does not
        // mutate caller-visible key state.
        let written = unsafe {
            aws_lc::RSA_public_encrypt(
                plaintext.len(),
                plaintext.as_ptr(),
                output.as_mut_ptr(),
                key.as_ptr(),
                aws_lc::RSA_PKCS1_PADDING,
            )
        };
        assert_eq!(written, output_size as i32);
        output
    }

    fn encrypt_raw(key: &RsaKey, plaintext: &[u8]) -> Vec<u8> {
        // SAFETY: `key` is a live RSA object.
        let output_size = unsafe { aws_lc::RSA_size(key.as_ptr()) } as usize;
        assert_eq!(plaintext.len(), output_size);
        let mut output = vec![0_u8; output_size];
        // SAFETY: both slices have the exact modulus width and the key is live.
        let written = unsafe {
            aws_lc::RSA_public_encrypt(
                plaintext.len(),
                plaintext.as_ptr(),
                output.as_mut_ptr(),
                key.as_ptr(),
                aws_lc::RSA_NO_PADDING,
            )
        };
        assert_eq!(written, output_size as i32);
        output
    }

    fn decode_hex(value: &str) -> Vec<u8> {
        assert_eq!(value.len() % 2, 0, "hex must contain full bytes");
        value
            .as_bytes()
            .as_chunks::<2>()
            .0
            .iter()
            .map(|pair| {
                let text = std::str::from_utf8(pair).expect("hex is ASCII");
                u8::from_str_radix(text, 16).expect("hex byte is valid")
            })
            .collect()
    }
}
