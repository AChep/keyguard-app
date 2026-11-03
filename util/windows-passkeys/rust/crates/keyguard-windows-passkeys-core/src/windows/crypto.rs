use crate::error::Error;
use std::ptr;
use windows::{
    Win32::Security::Cryptography::{
        BCRYPT_PSS_PADDING_INFO, BCRYPT_PUBLIC_KEY_BLOB, BCRYPT_RSAPUBLIC_MAGIC,
        BCRYPT_SHA256_ALGORITHM, NCRYPT_FLAGS, NCRYPT_HANDLE, NCRYPT_KEY_HANDLE,
        NCRYPT_PAD_PSS_FLAG, NCRYPT_PROV_HANDLE, NCryptFreeObject, NCryptImportKey,
        NCryptOpenStorageProvider, NCryptVerifySignature,
    },
    core::PCWSTR,
};

pub(super) fn verify_signature(
    bytes: &[u8],
    hash: &[u8; 32],
    signature: &[u8],
) -> Result<(), Error> {
    let magic = u32::from_le_bytes(*bytes.first_chunk().ok_or(Error::Invalid)?);
    let mut provider = NCRYPT_PROV_HANDLE::default();
    // SAFETY: Live output slot and default system provider.
    unsafe { NCryptOpenStorageProvider(&raw mut provider, PCWSTR::null(), 0) }?;
    let _provider = CryptHandle(NCRYPT_HANDLE(provider.0));
    let mut key = NCRYPT_KEY_HANDLE::default();
    // SAFETY: NCrypt validates the complete public blob before allocating the key.
    unsafe {
        NCryptImportKey(
            provider,
            None,
            BCRYPT_PUBLIC_KEY_BLOB,
            None,
            &raw mut key,
            bytes,
            NCRYPT_FLAGS(0),
        )
    }?;
    let _key = CryptHandle(NCRYPT_HANDLE(key.0));
    let padding = BCRYPT_PSS_PADDING_INFO {
        pszAlgId: BCRYPT_SHA256_ALGORITHM,
        cbSalt: 32,
    };
    // Windows Hello RSA keys use PSS, including when the operation signing key is EC.
    let (padding, flags) = if magic == BCRYPT_RSAPUBLIC_MAGIC.0 {
        (Some(ptr::from_ref(&padding).cast()), NCRYPT_PAD_PSS_FLAG)
    } else {
        (None, NCRYPT_FLAGS(0))
    };
    // SAFETY: Handles and byte slices are valid; padding matches the imported key.
    unsafe { NCryptVerifySignature(key, padding, hash, signature, flags) }?;
    Ok(())
}

struct CryptHandle(NCRYPT_HANDLE);
impl Drop for CryptHandle {
    fn drop(&mut self) {
        // SAFETY: Owned key/provider handle, freed exactly once in reverse allocation order.
        let _ = unsafe { NCryptFreeObject(self.0) };
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use windows::Win32::Security::Cryptography::{
        BCRYPT_ECDSA_P256_ALGORITHM, BCRYPT_RSA_ALGORITHM, CERT_KEY_SPEC, MS_KEY_STORAGE_PROVIDER,
        NCryptCreatePersistedKey, NCryptExportKey, NCryptFinalizeKey, NCryptSignHash,
    };

    #[test]
    fn verifies_ec_and_rsa_pss_proofs_and_rejects_tampering() {
        for algorithm in [BCRYPT_ECDSA_P256_ALGORITHM, BCRYPT_RSA_ALGORITHM] {
            let mut provider = NCRYPT_PROV_HANDLE::default();
            // SAFETY: Valid output pointer and the built-in software provider.
            unsafe { NCryptOpenStorageProvider(&raw mut provider, MS_KEY_STORAGE_PROVIDER, 0) }
                .unwrap();
            let _provider = CryptHandle(NCRYPT_HANDLE(provider.0));
            let mut key = NCRYPT_KEY_HANDLE::default();
            // SAFETY: A null key name creates an ephemeral key, never a persisted/user Hello key.
            unsafe {
                NCryptCreatePersistedKey(
                    provider,
                    &raw mut key,
                    algorithm,
                    PCWSTR::null(),
                    CERT_KEY_SPEC(0),
                    NCRYPT_FLAGS(0),
                )
            }
            .unwrap();
            let _key = CryptHandle(NCRYPT_HANDLE(key.0));
            // SAFETY: Valid unfinalized ephemeral key.
            unsafe { NCryptFinalizeKey(key, NCRYPT_FLAGS(0)) }.unwrap();
            let mut size = 0;
            // SAFETY: Size query; only the public key is exported.
            unsafe {
                NCryptExportKey(
                    key,
                    None,
                    BCRYPT_PUBLIC_KEY_BLOB,
                    None,
                    None,
                    &raw mut size,
                    NCRYPT_FLAGS(0),
                )
            }
            .unwrap();
            let mut public = vec![0; size as usize];
            // SAFETY: Buffer sized by the preceding query.
            unsafe {
                NCryptExportKey(
                    key,
                    None,
                    BCRYPT_PUBLIC_KEY_BLOB,
                    None,
                    Some(&mut public),
                    &raw mut size,
                    NCRYPT_FLAGS(0),
                )
            }
            .unwrap();
            public.truncate(size as usize);
            let padding = BCRYPT_PSS_PADDING_INFO {
                pszAlgId: BCRYPT_SHA256_ALGORITHM,
                cbSalt: 32,
            };
            let (padding, flags) = if algorithm == BCRYPT_RSA_ALGORITHM {
                (Some(ptr::from_ref(&padding).cast()), NCRYPT_PAD_PSS_FLAG)
            } else {
                (None, NCRYPT_FLAGS(0))
            };
            let hash = [42; 32];
            // SAFETY: Valid key/hash/padding; query the signature length first.
            unsafe { NCryptSignHash(key, padding, &hash, None, &raw mut size, flags) }.unwrap();
            let mut signature = vec![0; size as usize];
            // SAFETY: Output is sized by the preceding query.
            unsafe {
                NCryptSignHash(
                    key,
                    padding,
                    &hash,
                    Some(&mut signature),
                    &raw mut size,
                    flags,
                )
            }
            .unwrap();
            signature.truncate(size as usize);
            verify_signature(&public, &hash, &signature).unwrap();
            assert!(verify_signature(&public, &[43; 32], &signature).is_err());
            signature[0] ^= 1;
            assert!(verify_signature(&public, &hash, &signature).is_err());
            assert!(verify_signature(&public[..4], &hash, &signature).is_err());
            assert_eq!(
                verify_signature(&public[..3], &hash, &signature),
                Err(Error::Invalid)
            );
        }
    }
}
