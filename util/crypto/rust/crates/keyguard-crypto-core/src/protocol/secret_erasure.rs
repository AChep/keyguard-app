//! Handwritten erasure policy for secret-bearing protocol messages.

use super::*;

#[cfg(test)]
use std::cell::Cell;

#[cfg(test)]
thread_local! {
    static ZEROIZED_SECRET_REQUEST_DROPS: Cell<usize> = const { Cell::new(0) };
    static ZEROIZED_SECRET_OUTPUT_DROPS: Cell<usize> = const { Cell::new(0) };
}

#[cfg(test)]
pub(crate) fn reset_zeroized_secret_request_drops() {
    ZEROIZED_SECRET_REQUEST_DROPS.with(|drops| drops.set(0));
}

#[cfg(test)]
pub(crate) fn zeroized_secret_request_drops() -> usize {
    ZEROIZED_SECRET_REQUEST_DROPS.with(Cell::get)
}

#[cfg(test)]
fn record_zeroized_secret_request_drop() {
    ZEROIZED_SECRET_REQUEST_DROPS.with(|drops| drops.set(drops.get() + 1));
}

#[cfg(test)]
pub(crate) fn reset_zeroized_secret_output_drops() {
    ZEROIZED_SECRET_OUTPUT_DROPS.with(|drops| drops.set(0));
}

#[cfg(test)]
pub(crate) fn zeroized_secret_output_drops() -> usize {
    ZEROIZED_SECRET_OUTPUT_DROPS.with(Cell::get)
}

#[cfg(test)]
fn record_zeroized_secret_output_drop() {
    ZEROIZED_SECRET_OUTPUT_DROPS.with(|drops| drops.set(drops.get() + 1));
}

impl Drop for HmacSha256StreamOpenRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.key);
        debug_assert!(self.key.iter().all(|byte| *byte == 0));
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for HmacStreamOpenRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.key);
        debug_assert!(self.key.iter().all(|byte| *byte == 0));
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for AesCbcPkcs7StreamOpenRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.key);
        zeroize::Zeroize::zeroize(&mut self.iv);
        debug_assert!(self.key.iter().chain(&self.iv).all(|byte| *byte == 0));
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for AesCbcPkcs7HmacSha256EncryptStreamOpenRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.encryption_key);
        zeroize::Zeroize::zeroize(&mut self.mac_key);
        zeroize::Zeroize::zeroize(&mut self.iv);
        debug_assert!(
            self.encryption_key
                .iter()
                .chain(&self.mac_key)
                .chain(&self.iv)
                .all(|byte| *byte == 0)
        );
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for AesCbcPkcs7HmacSha256DecryptStreamOpenRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.encryption_key);
        zeroize::Zeroize::zeroize(&mut self.mac_key);
        zeroize::Zeroize::zeroize(&mut self.iv);
        zeroize::Zeroize::zeroize(&mut self.expected_mac);
        debug_assert!(
            self.encryption_key
                .iter()
                .chain(&self.mac_key)
                .chain(&self.iv)
                .chain(&self.expected_mac)
                .all(|byte| *byte == 0)
        );
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for TwofishCbcPkcs7StreamOpenRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.key);
        zeroize::Zeroize::zeroize(&mut self.iv);
        debug_assert!(self.key.iter().chain(&self.iv).all(|byte| *byte == 0));
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for HkdfSha256Request {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.seed);
        if let Some(salt) = self.salt.as_mut() {
            zeroize::Zeroize::zeroize(salt);
        }
        if let Some(info) = self.info.as_mut() {
            zeroize::Zeroize::zeroize(info);
        }
        debug_assert!(
            self.seed
                .iter()
                .chain(self.salt.iter().flatten())
                .chain(self.info.iter().flatten())
                .all(|byte| *byte == 0)
        );
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for Pbkdf2Sha256Request {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.seed);
        zeroize::Zeroize::zeroize(&mut self.salt);
        debug_assert!(self.seed.iter().chain(&self.salt).all(|byte| *byte == 0));
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for Argon2Request {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.seed);
        zeroize::Zeroize::zeroize(&mut self.salt);
        if let Some(secret) = self.secret.as_mut() {
            zeroize::Zeroize::zeroize(secret);
        }
        if let Some(associated_data) = self.associated_data.as_mut() {
            zeroize::Zeroize::zeroize(associated_data);
        }
        debug_assert!(
            self.seed
                .iter()
                .chain(&self.salt)
                .chain(self.secret.iter().flatten())
                .chain(self.associated_data.iter().flatten())
                .all(|byte| *byte == 0)
        );
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for HmacRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.key);
        zeroize::Zeroize::zeroize(&mut self.data);
        debug_assert!(self.key.iter().chain(&self.data).all(|byte| *byte == 0));
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for DigestRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.data);
        debug_assert!(self.data.iter().all(|byte| *byte == 0));
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for AesEcbNoPaddingEncryptRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.key);
        zeroize::Zeroize::zeroize(&mut self.data);
        debug_assert!(self.key.iter().chain(&self.data).all(|byte| *byte == 0));
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for AesEcbNoPaddingTransformRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.key);
        zeroize::Zeroize::zeroize(&mut self.data);
        debug_assert!(self.key.iter().chain(&self.data).all(|byte| *byte == 0));
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for AesCbcPkcs7Request {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.key);
        zeroize::Zeroize::zeroize(&mut self.iv);
        zeroize::Zeroize::zeroize(&mut self.data);
        debug_assert!(
            self.key
                .iter()
                .chain(&self.iv)
                .chain(&self.data)
                .all(|byte| *byte == 0)
        );
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for AesCbcPkcs7HmacSha256EncryptRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.encryption_key);
        zeroize::Zeroize::zeroize(&mut self.mac_key);
        zeroize::Zeroize::zeroize(&mut self.iv);
        zeroize::Zeroize::zeroize(&mut self.plaintext);
        debug_assert!(
            self.encryption_key
                .iter()
                .chain(&self.mac_key)
                .chain(&self.iv)
                .chain(&self.plaintext)
                .all(|byte| *byte == 0)
        );
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for AesCbcPkcs7HmacSha256DecryptRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.encryption_key);
        zeroize::Zeroize::zeroize(&mut self.mac_key);
        zeroize::Zeroize::zeroize(&mut self.iv);
        zeroize::Zeroize::zeroize(&mut self.ciphertext);
        zeroize::Zeroize::zeroize(&mut self.expected_mac);
        debug_assert!(
            self.encryption_key
                .iter()
                .chain(&self.mac_key)
                .chain(&self.iv)
                .chain(&self.ciphertext)
                .chain(&self.expected_mac)
                .all(|byte| *byte == 0)
        );
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for StreamCipherXorAtOffsetRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.key);
        zeroize::Zeroize::zeroize(&mut self.nonce);
        zeroize::Zeroize::zeroize(&mut self.data);
        debug_assert!(
            self.key
                .iter()
                .chain(&self.nonce)
                .chain(&self.data)
                .all(|byte| *byte == 0)
        );
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for TwofishCbcPkcs7Request {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.key);
        zeroize::Zeroize::zeroize(&mut self.iv);
        zeroize::Zeroize::zeroize(&mut self.data);
        debug_assert!(
            self.key
                .iter()
                .chain(&self.iv)
                .chain(&self.data)
                .all(|byte| *byte == 0)
        );
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for RsaOaepDecryptRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.private_key_pkcs8);
        zeroize::Zeroize::zeroize(&mut self.ciphertext);
        debug_assert!(
            self.private_key_pkcs8
                .iter()
                .chain(&self.ciphertext)
                .all(|byte| *byte == 0)
        );
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for RsaOaepEncryptRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.public_key_spki);
        zeroize::Zeroize::zeroize(&mut self.plaintext);
        debug_assert!(
            self.public_key_spki
                .iter()
                .chain(&self.plaintext)
                .all(|byte| *byte == 0)
        );
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for RsaPkcs8ToSpkiRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.private_key_pkcs8);
        debug_assert!(self.private_key_pkcs8.iter().all(|byte| *byte == 0));
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for SshAgentTcpChaCha20Poly1305Request {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.key);
        zeroize::Zeroize::zeroize(&mut self.nonce);
        zeroize::Zeroize::zeroize(&mut self.header);
        zeroize::Zeroize::zeroize(&mut self.payload);
        debug_assert!(
            self.key
                .iter()
                .chain(&self.nonce)
                .chain(&self.header)
                .chain(&self.payload)
                .all(|byte| *byte == 0)
        );
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for SshKeyParseRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.private_key_pem);
        debug_assert!(
            self.private_key_pem
                .as_bytes()
                .iter()
                .all(|byte| *byte == 0)
        );
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for SshKeyDescribeRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.private_key);
        debug_assert!(self.private_key.iter().all(|byte| *byte == 0));
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for SshPrivateKeyRsaBitsRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.private_key);
        debug_assert!(self.private_key.iter().all(|byte| *byte == 0));
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for SshPrivateKeyFormatRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.private_key);
        debug_assert!(self.private_key.iter().all(|byte| *byte == 0));
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for SshAgentSignRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.private_key_pem);
        zeroize::Zeroize::zeroize(&mut self.data);
        debug_assert!(
            self.private_key_pem
                .as_bytes()
                .iter()
                .chain(&self.data)
                .all(|byte| *byte == 0)
        );
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for SshPrivateKeyImportRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.content);
        if let Some(passphrase) = self.passphrase_utf8.as_mut() {
            zeroize::Zeroize::zeroize(passphrase);
        }
        debug_assert!(
            self.content
                .as_bytes()
                .iter()
                .chain(self.passphrase_utf8.iter().flatten())
                .all(|byte| *byte == 0)
        );
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for SshKeyExportCxfRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.private_key_pem);
        debug_assert!(
            self.private_key_pem
                .as_bytes()
                .iter()
                .all(|byte| *byte == 0)
        );
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for PasskeyKeyInspectRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.private_key_pkcs8);
        debug_assert!(self.private_key_pkcs8.iter().all(|byte| *byte == 0));
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for PasskeySignRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.private_key_pkcs8);
        zeroize::Zeroize::zeroize(&mut self.data);
        debug_assert!(
            self.private_key_pkcs8
                .iter()
                .chain(&self.data)
                .all(|byte| *byte == 0)
        );
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for OpenPgpVerifyRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.content);
        debug_assert!(self.content.iter().all(|byte| *byte == 0));
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for OpenPgpMetadataResolveRequest {
    fn drop(&mut self) {
        if let Some(private_key_data) = self.private_key_data.as_mut() {
            zeroize::Zeroize::zeroize(private_key_data);
        }
        debug_assert!(
            self.private_key_data
                .iter()
                .flatten()
                .all(|byte| *byte == 0)
        );
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for OpenPgpKeyImportRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.key_data);
        if let Some(passphrase) = self.passphrase_utf8.as_mut() {
            zeroize::Zeroize::zeroize(passphrase);
        }
        debug_assert!(
            self.key_data
                .iter()
                .chain(self.passphrase_utf8.iter().flatten())
                .all(|byte| *byte == 0)
        );
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for OpenPgpSignRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.content);
        zeroize::Zeroize::zeroize(&mut self.private_key);
        debug_assert!(
            self.content
                .iter()
                .chain(&self.private_key)
                .all(|byte| *byte == 0)
        );
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for OpenPgpDetachedSignStreamOpenRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.private_key);
        debug_assert!(self.private_key.iter().all(|byte| *byte == 0));
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for OpenPgpEncryptRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.content);
        if let Some(private_key) = self.signing_private_key.as_mut() {
            zeroize::Zeroize::zeroize(private_key);
        }
        debug_assert!(
            self.content
                .iter()
                .chain(self.signing_private_key.iter().flatten())
                .all(|byte| *byte == 0)
        );
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for OpenPgpEncryptStreamOpenRequest {
    fn drop(&mut self) {
        if let Some(private_key) = self.signing_private_key.as_mut() {
            zeroize::Zeroize::zeroize(private_key);
        }
        debug_assert!(
            self.signing_private_key
                .iter()
                .flatten()
                .all(|byte| *byte == 0)
        );
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for OpenPgpDecryptRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.content);
        zeroize::Zeroize::zeroize(&mut self.private_keys);
        debug_assert!(
            self.content
                .iter()
                .chain(self.private_keys.iter().flatten())
                .all(|byte| *byte == 0)
        );
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for OpenPgpDecryptStreamOpenRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.private_keys);
        debug_assert!(self.private_keys.iter().flatten().all(|byte| *byte == 0));
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for OpenPgpExpirationUpdateRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.private_key);
        debug_assert!(self.private_key.iter().all(|byte| *byte == 0));
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for OpenPgpAgentSignRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.private_key);
        zeroize::Zeroize::zeroize(&mut self.hash);
        debug_assert!(
            self.private_key
                .iter()
                .chain(&self.hash)
                .all(|byte| *byte == 0)
        );
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for OpenPgpAgentDecryptRequest {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.private_key);
        zeroize::Zeroize::zeroize(&mut self.ciphertext);
        debug_assert!(
            self.private_key
                .iter()
                .chain(&self.ciphertext)
                .all(|byte| *byte == 0)
        );
        #[cfg(test)]
        record_zeroized_secret_request_drop();
    }
}

impl Drop for SshKeyMaterial {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.private_key);
        debug_assert!(self.private_key.iter().all(|byte| *byte == 0));
        #[cfg(test)]
        record_zeroized_secret_output_drop();
    }
}

impl Drop for SshKeyExportCxfResult {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.private_key_pkcs8);
        debug_assert!(self.private_key_pkcs8.iter().all(|byte| *byte == 0));
        #[cfg(test)]
        record_zeroized_secret_output_drop();
    }
}

impl Drop for PasskeyKeyMaterial {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.private_key_pkcs8);
        debug_assert!(self.private_key_pkcs8.iter().all(|byte| *byte == 0));
        #[cfg(test)]
        record_zeroized_secret_output_drop();
    }
}

impl Drop for SshKeyDescription {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.private_key_pem);
        debug_assert!(
            self.private_key_pem
                .as_bytes()
                .iter()
                .all(|byte| *byte == 0)
        );
        #[cfg(test)]
        record_zeroized_secret_output_drop();
    }
}

impl Drop for SshFormattedPrivateKey {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.value);
        debug_assert!(self.value.as_bytes().iter().all(|byte| *byte == 0));
        #[cfg(test)]
        record_zeroized_secret_output_drop();
    }
}

impl Drop for OpenPgpKeyMaterial {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.private_key_armored);
        debug_assert!(self.private_key_armored.iter().all(|byte| *byte == 0));
        #[cfg(test)]
        record_zeroized_secret_output_drop();
    }
}

impl Drop for OpenPgpDecryptResult {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.data);
        debug_assert!(self.data.iter().all(|byte| *byte == 0));
        #[cfg(test)]
        record_zeroized_secret_output_drop();
    }
}

impl Drop for OpenPgpDecryptFinal {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.data);
        debug_assert!(self.data.iter().all(|byte| *byte == 0));
        #[cfg(test)]
        record_zeroized_secret_output_drop();
    }
}

impl Drop for OpenPgpAgentSignSuccess {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.canonical_sexp);
        debug_assert!(self.canonical_sexp.iter().all(|byte| *byte == 0));
        #[cfg(test)]
        record_zeroized_secret_output_drop();
    }
}

impl Drop for OpenPgpAgentDecryptSuccess {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.canonical_sexp);
        debug_assert!(self.canonical_sexp.iter().all(|byte| *byte == 0));
        #[cfg(test)]
        record_zeroized_secret_output_drop();
    }
}
