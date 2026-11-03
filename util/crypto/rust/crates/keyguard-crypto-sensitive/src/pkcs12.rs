//! PKCS#12 client identities, using the same backend on every platform.

use std::{ffi::CStr, ptr, slice};

use aws_lc_sys as lc;
use zeroize::Zeroizing;

/// The largest bundle, or DER key, that is read or written.
const MAX_BYTES: usize = 4 * 1024 * 1024;

/// Failures deliberately contain no input or backend diagnostic strings.
#[derive(Debug, PartialEq, Eq)]
pub enum ImportError {
    /// The bundle could not be authenticated with the supplied password.
    Password,
    /// The input is malformed or does not contain exactly one usable identity.
    Invalid,
}

/// A normalized identity and public display metadata.
pub struct Identity {
    /// PKCS#12 output; callers must protect it as private key material.
    pub bundle: Zeroizing<Vec<u8>>,
    /// Algorithm-specific DER, only requested for transient Apple key import.
    pub private_key_der: Zeroizing<Vec<u8>>,
    /// Friendly name, falling back to the subject common name, then the subject.
    pub display_name: String,
    /// Certificate subject as an RFC 2253 distinguished name.
    pub subject: String,
    /// Certificate issuer as an RFC 2253 distinguished name.
    pub issuer: String,
    /// Issuer common name, or empty when the issuer has none.
    pub issuer_common_name: String,
    /// Start of the validity period in Unix seconds.
    pub not_before: i64,
    /// Certificate expiration in Unix seconds.
    pub expires_at: i64,
    /// Big-endian serial number magnitude.
    pub serial_number: Vec<u8>,
    /// SHA-256 digest of the DER-encoded certificate.
    pub sha256_fingerprint: Vec<u8>,
    /// Key algorithm and size, e.g. `RSA 2048`, `EC P-256` or `Ed25519`; empty if unknown.
    pub key_type: String,
}

struct Key(*mut lc::EVP_PKEY);
impl Drop for Key {
    fn drop(&mut self) {
        // SAFETY: this guard uniquely owns the key returned by the parser.
        unsafe { lc::EVP_PKEY_free(self.0) }
    }
}

struct Certificates(*mut lc::OPENSSL_STACK);
impl Drop for Certificates {
    fn drop(&mut self) {
        // SAFETY: every entry was allocated by AWS-LC and ownership was transferred
        // to this stack. Removing entries before freeing the stack avoids double frees.
        unsafe {
            while lc::OPENSSL_sk_num(self.0) != 0 {
                lc::X509_free(lc::OPENSSL_sk_pop(self.0).cast());
            }
            lc::OPENSSL_sk_free(self.0);
        }
    }
}

struct Bundle(*mut lc::PKCS12);
impl Drop for Bundle {
    fn drop(&mut self) {
        // SAFETY: this guard uniquely owns the PKCS12 allocation.
        unsafe { lc::PKCS12_free(self.0) }
    }
}

/// Imports one identity and optionally emits its empty-password representation.
///
/// Missing passwords try both PKCS#12 encodings of the empty password. Existing
/// MACs are always authenticated; only a structurally absent MAC can be added to
/// an in-memory copy for compatibility with unprotected Java keystores.
pub fn import(
    data: &[u8],
    password: Option<&[u8]>,
    normalize: bool,
) -> Result<Identity, ImportError> {
    import_with_output_password(data, password, normalize, b"", false)
}

/// Rewraps an identity for an OS importer that requires a nonempty password.
/// The output password is transient and must never be saved with the account.
pub fn import_with_output_password(
    data: &[u8],
    password: Option<&[u8]>,
    normalize: bool,
    output_password: &[u8],
    export_private_key: bool,
) -> Result<Identity, ImportError> {
    if data.is_empty() || data.len() > MAX_BYTES {
        return Err(ImportError::Invalid);
    }
    let mut pass = Zeroizing::new(password.unwrap_or_default().to_vec());
    if pass.contains(&0) || std::str::from_utf8(&pass).is_err() {
        return Err(ImportError::Invalid);
    }
    pass.push(0);
    let pass_ptr = pass.as_ptr().cast();
    let mut output_pass = Zeroizing::new(output_password.to_vec());
    if output_pass.contains(&0) || std::str::from_utf8(&output_pass).is_err() {
        return Err(ImportError::Invalid);
    }
    output_pass.push(0);
    let output_pass_ptr = output_pass.as_ptr().cast();
    // SAFETY: all pointers below refer to live, bounded buffers or owned backend
    // allocations. Guards release partially populated outputs on every exit.
    unsafe {
        lc::ERR_clear_error();
        let certs = Certificates(lc::OPENSSL_sk_new_null());
        if certs.0.is_null() {
            return Err(ImportError::Invalid);
        }
        let mut key = Key(ptr::null_mut());
        let mut input = lc::CBS {
            data: data.as_ptr(),
            len: data.len(),
        };
        if lc::PKCS12_get_key_and_certs(&mut key.0, certs.0.cast(), &mut input, pass_ptr) != 1 {
            let (password_error, missing_mac) = take_errors();
            if !missing_mac {
                return Err(if password_error {
                    ImportError::Password
                } else {
                    ImportError::Invalid
                });
            }
            // The parser reports MISSING_MAC only when the PFX has no MAC field.
            // Never use this path for an incorrect MAC, including damaged input.
            let mut cursor = data.as_ptr();
            let copy = Bundle(lc::d2i_PKCS12(ptr::null_mut(), &mut cursor, data.len()));
            if copy.0.is_null()
                || lc::PKCS12_set_mac(
                    copy.0,
                    pass_ptr,
                    -1,
                    ptr::null_mut(),
                    0,
                    1,
                    lc::EVP_sha256(),
                ) != 1
            {
                return Err(ImportError::Invalid);
            }
            let bytes = encode(&copy)?;
            let mut input = lc::CBS {
                data: bytes.as_ptr(),
                len: bytes.len(),
            };
            if lc::PKCS12_get_key_and_certs(&mut key.0, certs.0.cast(), &mut input, pass_ptr) != 1 {
                let (password_error, _) = take_errors();
                return Err(if password_error {
                    ImportError::Password
                } else {
                    ImportError::Invalid
                });
            }
        }
        if key.0.is_null() {
            return Err(ImportError::Invalid);
        }
        let mut leaf = ptr::null_mut();
        for i in 0..lc::OPENSSL_sk_num(certs.0) {
            let cert: *mut lc::X509 = lc::OPENSSL_sk_value(certs.0, i).cast();
            if lc::X509_check_private_key(cert, key.0) == 1 {
                leaf = cert;
                break;
            }
        }
        lc::ERR_clear_error();
        if leaf.is_null() {
            return Err(ImportError::Invalid);
        }
        let subject_name = lc::X509_get_subject_name(leaf);
        let issuer_name = lc::X509_get_issuer_name(leaf);
        let subject = name(subject_name)?;
        let issuer = name(issuer_name)?;
        let mut alias_len = 0;
        let alias = lc::X509_alias_get0(leaf, &mut alias_len);
        let display_name = if !alias.is_null() && alias_len > 0 {
            String::from_utf8_lossy(slice::from_raw_parts(alias, alias_len as usize)).into_owned()
        } else {
            common_name(subject_name).unwrap_or_else(|| subject.clone())
        };
        let issuer_common_name = common_name(issuer_name).unwrap_or_default();
        let mut not_before = 0;
        let mut expires_at = 0;
        if lc::ASN1_TIME_to_posix(lc::X509_get0_notBefore(leaf), &mut not_before) != 1
            || lc::ASN1_TIME_to_posix(lc::X509_get0_notAfter(leaf), &mut expires_at) != 1
        {
            return Err(ImportError::Invalid);
        }
        let serial = lc::X509_get0_serialNumber(leaf);
        let serial_number = asn1_bytes(serial).to_vec();
        let mut fingerprint = [0u8; lc::EVP_MAX_MD_SIZE as usize];
        let mut fingerprint_len = 0;
        if lc::X509_digest(
            leaf,
            lc::EVP_sha256(),
            fingerprint.as_mut_ptr(),
            &mut fingerprint_len,
        ) != 1
        {
            return Err(ImportError::Invalid);
        }
        let sha256_fingerprint = fingerprint[..fingerprint_len as usize].to_vec();
        let key_type = key_type(key.0);
        let bundle = if normalize {
            // The temporary stack borrows certificates; the owning stack outlives it.
            let chain = lc::OPENSSL_sk_new_null();
            if chain.is_null() {
                return Err(ImportError::Invalid);
            }
            for i in 0..lc::OPENSSL_sk_num(certs.0) {
                let cert = lc::OPENSSL_sk_value(certs.0, i);
                if cert != leaf.cast() && lc::OPENSSL_sk_push(chain, cert) == 0 {
                    lc::OPENSSL_sk_free(chain);
                    return Err(ImportError::Invalid);
                }
            }
            // The legacy Apple parser and Java both support 3DES.
            // Vault encryption protects the stored key; the empty password exists for compatibility.
            let p12 = Bundle(lc::PKCS12_create(
                output_pass_ptr,
                ptr::null(),
                key.0,
                leaf,
                chain.cast(),
                lc::NID_pbe_WithSHA1And3_Key_TripleDES_CBC,
                lc::NID_pbe_WithSHA1And3_Key_TripleDES_CBC,
                2048,
                2048,
                0,
            ));
            lc::OPENSSL_sk_free(chain);
            if p12.0.is_null() {
                return Err(ImportError::Invalid);
            }
            // Use the legacy MAC supported by Apple's PKCS#12 importers.
            // Authenticated vault encryption protects the persisted identity.
            if lc::PKCS12_set_mac(
                p12.0,
                output_pass_ptr,
                -1,
                ptr::null_mut(),
                0,
                2048,
                lc::EVP_sha1(),
            ) != 1
            {
                return Err(ImportError::Invalid);
            }
            encode(&p12)?
        } else {
            Zeroizing::new(Vec::new())
        };
        let private_key_der = if export_private_key {
            let length = lc::i2d_PrivateKey(key.0, ptr::null_mut());
            if length <= 0 || length as usize > MAX_BYTES {
                return Err(ImportError::Invalid);
            }
            let mut der = Zeroizing::new(vec![0; length as usize]);
            let mut cursor = der.as_mut_ptr();
            if lc::i2d_PrivateKey(key.0, &mut cursor) != length {
                return Err(ImportError::Invalid);
            }
            der
        } else {
            Zeroizing::new(Vec::new())
        };
        Ok(Identity {
            bundle,
            private_key_der,
            display_name,
            subject,
            issuer,
            issuer_common_name,
            not_before,
            expires_at,
            serial_number,
            sha256_fingerprint,
            key_type,
        })
    }
}

fn encode(bundle: &Bundle) -> Result<Zeroizing<Vec<u8>>, ImportError> {
    // SAFETY: bundle owns a valid PKCS12. The first call sizes the caller-owned
    // output; the second writes exactly that many bytes without allocating.
    unsafe {
        let length = lc::i2d_PKCS12(bundle.0, ptr::null_mut());
        if length <= 0 || length as usize > MAX_BYTES {
            return Err(ImportError::Invalid);
        }
        let mut bytes = Zeroizing::new(vec![0; length as usize]);
        let mut cursor = bytes.as_mut_ptr();
        if lc::i2d_PKCS12(bundle.0, &mut cursor) != length {
            return Err(ImportError::Invalid);
        }
        Ok(bytes)
    }
}

struct MemoryBio(*mut lc::BIO);
impl Drop for MemoryBio {
    fn drop(&mut self) {
        // SAFETY: this guard uniquely owns the memory BIO.
        unsafe { lc::BIO_free(self.0) };
    }
}

/// Formats a name as RFC 2253, keeping UTF-8 instead of escaping it.
fn name(name: *const lc::X509_NAME) -> Result<String, ImportError> {
    // SAFETY: callers borrow the name from a live certificate. The memory BIO
    // owns the printed text until the guard frees it after we copy it.
    unsafe {
        let bio = MemoryBio(lc::BIO_new(lc::BIO_s_mem()));
        if bio.0.is_null() {
            return Err(ImportError::Invalid);
        }
        let flags = (lc::XN_FLAG_RFC2253 & !lc::ASN1_STRFLGS_ESC_MSB) as _;
        if lc::X509_NAME_print_ex(bio.0, name, 0, flags) < 0 {
            return Err(ImportError::Invalid);
        }
        let mut contents = ptr::null();
        let mut length = 0;
        if lc::BIO_mem_contents(bio.0, &mut contents, &mut length) != 1 {
            return Err(ImportError::Invalid);
        }
        if length == 0 {
            return Ok(String::new());
        }
        Ok(String::from_utf8_lossy(slice::from_raw_parts(contents, length)).into_owned())
    }
}

/// Returns the most specific common name in `name`, if any.
fn common_name(name: *const lc::X509_NAME) -> Option<String> {
    // SAFETY: callers borrow the name from a live certificate. Entries and
    // their data are borrowed; the UTF-8 copy is owned and released here.
    unsafe {
        let mut index = -1;
        let mut last = -1;
        loop {
            index = lc::X509_NAME_get_index_by_NID(name, lc::NID_commonName, index);
            if index < 0 {
                break;
            }
            last = index;
        }
        if last < 0 {
            return None;
        }
        let data = lc::X509_NAME_ENTRY_get_data(lc::X509_NAME_get_entry(name, last));
        let mut utf8 = ptr::null_mut();
        let length = lc::ASN1_STRING_to_UTF8(&mut utf8, data);
        if length < 0 || utf8.is_null() {
            return None;
        }
        let result = String::from_utf8_lossy(slice::from_raw_parts(utf8, length as usize))
            .trim()
            .to_owned();
        lc::OPENSSL_free(utf8.cast());
        Some(result).filter(|it| !it.is_empty())
    }
}

/// Borrows the content bytes of an ASN.1 string.
///
/// # Safety
/// `value` must be null or a live ASN.1 string that outlives the result.
unsafe fn asn1_bytes<'a>(value: *const lc::ASN1_STRING) -> &'a [u8] {
    // SAFETY: guaranteed by the caller.
    unsafe {
        if value.is_null() {
            return &[];
        }
        let data = lc::ASN1_STRING_get0_data(value);
        let length = lc::ASN1_STRING_length(value);
        if data.is_null() || length <= 0 {
            return &[];
        }
        slice::from_raw_parts(data, length as usize)
    }
}

/// Describes a key as its algorithm and size, e.g. `RSA 2048` or `EC P-256`,
/// or returns an empty string for an algorithm without a known name.
fn key_type(key: *const lc::EVP_PKEY) -> String {
    // SAFETY: callers pass a live key. Curve names are static strings.
    unsafe {
        match lc::EVP_PKEY_id(key) {
            lc::EVP_PKEY_RSA => format!("RSA {}", lc::EVP_PKEY_bits(key)),
            lc::EVP_PKEY_EC => {
                let group = lc::EC_KEY_get0_group(lc::EVP_PKEY_get0_EC_KEY(key));
                let curve = if group.is_null() {
                    ptr::null()
                } else {
                    lc::EC_curve_nid2nist(lc::EC_GROUP_get_curve_name(group))
                };
                if curve.is_null() {
                    format!("EC {}", lc::EVP_PKEY_bits(key))
                } else {
                    format!("EC {}", CStr::from_ptr(curve).to_string_lossy())
                }
            }
            lc::EVP_PKEY_ED25519 => "Ed25519".to_owned(),
            lc::EVP_PKEY_ED448 => "Ed448".to_owned(),
            _ => String::new(),
        }
    }
}

fn take_errors() -> (bool, bool) {
    let mut password = false;
    let mut missing_mac = false;
    // SAFETY: the error queue is thread-local and contains no borrowed pointers.
    unsafe {
        loop {
            let error = lc::ERR_get_error();
            if error == 0 {
                break;
            }
            let reason = lc::ERR_GET_REASON(error);
            password |= (lc::ERR_GET_LIB(error) == lc::ERR_LIB_PKCS12
                && reason == lc::PKCS12_R_MAC_VERIFY_FAILURE)
                || (lc::ERR_GET_LIB(error) == lc::ERR_LIB_CIPHER
                    && reason == lc::CIPHER_R_BAD_DECRYPT);
            missing_mac |=
                lc::ERR_GET_LIB(error) == lc::ERR_LIB_PKCS8 && reason == lc::PKCS8_R_MISSING_MAC;
        }
    }
    (password, missing_mac)
}

#[cfg(test)]
mod tests {
    use super::*;
    const PROTECTED: &[u8] = include_bytes!("../tests/fixtures/mtls/client.pkcs12.der");
    const EMPTY: &[u8] = include_bytes!("../tests/fixtures/mtls/client-empty-pass.pkcs12.der");

    #[test]
    fn password_and_normalization_round_trip() {
        assert!(matches!(
            import(PROTECTED, None, true),
            Err(ImportError::Password)
        ));
        assert!(matches!(
            import(PROTECTED, Some(b"wrong"), true),
            Err(ImportError::Password)
        ));
        let identity = import(PROTECTED, Some(b"changeit"), true).unwrap();
        assert!(!identity.display_name.is_empty());
        let restored = import(&identity.bundle, None, false).unwrap();
        assert_eq!(identity.display_name, restored.display_name);
        assert_eq!(identity.subject, restored.subject);
        assert_eq!(identity.issuer, restored.issuer);
        assert_eq!(identity.not_before, restored.not_before);
        assert_eq!(identity.expires_at, restored.expires_at);
        assert_eq!(identity.serial_number, restored.serial_number);
        assert_eq!(identity.sha256_fingerprint, restored.sha256_fingerprint);
        assert!(restored.bundle.is_empty());
        assert!(import(EMPTY, None, true).is_ok());
        let ec = import(
            include_bytes!("../tests/fixtures/mtls/client-ec-chain.pkcs12.der"),
            None,
            true,
        )
        .unwrap();
        assert!(ec.key_type.starts_with("EC "), "{}", ec.key_type);
        assert!(
            import(
                include_bytes!("../tests/fixtures/mtls/client-no-mac.pkcs12.der"),
                None,
                true
            )
            .is_ok()
        );
    }

    #[test]
    fn exposes_public_certificate_metadata() {
        let identity = import(PROTECTED, Some(b"changeit"), false).unwrap();
        assert_eq!(identity.sha256_fingerprint.len(), 32);
        assert!(!identity.serial_number.is_empty());
        assert!(identity.not_before < identity.expires_at);
        assert!(
            identity.key_type.starts_with("RSA "),
            "{}",
            identity.key_type
        );
        // RFC 2253 lists the most specific attribute first, without the one-line slashes.
        assert!(identity.subject.contains("CN="), "{}", identity.subject);
        assert!(identity.issuer.contains("CN="), "{}", identity.issuer);
        assert!(!identity.issuer.starts_with('/'), "{}", identity.issuer);
        assert!(!identity.issuer_common_name.is_empty());
        assert!(!identity.issuer_common_name.contains('='));
    }

    #[test]
    fn malformed_and_damaged_bundles_fail() {
        assert!(matches!(
            import(b"invalid", None, true),
            Err(ImportError::Invalid)
        ));
        let mut damaged = PROTECTED.to_vec();
        let middle = damaged.len() / 2;
        damaged[middle] ^= 1;
        assert!(import(&damaged, Some(b"changeit"), true).is_err());
    }

    #[test]
    fn transient_apple_wrapper_preserves_identity_and_exports_key_only_on_request() {
        let stored = import(PROTECTED, Some(b"changeit"), true).unwrap();
        assert!(stored.private_key_der.is_empty());
        let transient = import_with_output_password(
            &stored.bundle,
            None,
            true,
            b"transient-import-password",
            true,
        )
        .unwrap();
        assert!(!transient.private_key_der.is_empty());
        assert!(matches!(
            import(&transient.bundle, None, false),
            Err(ImportError::Password)
        ));
        let restored = import_with_output_password(
            &transient.bundle,
            Some(b"transient-import-password"),
            false,
            b"",
            true,
        )
        .unwrap();
        assert_eq!(stored.display_name, restored.display_name);
        assert_eq!(stored.issuer, restored.issuer);
        assert_eq!(stored.expires_at, restored.expires_at);
        assert_eq!(stored.sha256_fingerprint, restored.sha256_fingerprint);
        assert_eq!(transient.private_key_der, restored.private_key_der);
    }
}
