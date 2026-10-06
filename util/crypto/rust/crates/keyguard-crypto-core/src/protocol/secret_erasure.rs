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

/// Implements `Drop` for protocol messages so their secret fields are zeroized,
/// in the listed order, whenever a decoded or encoded message goes out of scope.
macro_rules! erase_on_drop {
    ($record:ident; $($message:ident { $($field:ident),+ $(,)? }),+ $(,)?) => {
        $(
            impl Drop for $message {
                fn drop(&mut self) {
                    $(zeroize::Zeroize::zeroize(&mut self.$field);)+
                    #[cfg(test)]
                    $record();
                }
            }
        )+
    };
}

// Requests that carry keys, passphrases or plaintext.
erase_on_drop! {
    record_zeroized_secret_request_drop;
    HmacStreamOpenRequest { key },
    AesCbcPkcs7StreamOpenRequest { key, iv },
    AesCbcPkcs7HmacSha256EncryptStreamOpenRequest { encryption_key, mac_key, iv },
    AesCbcPkcs7HmacSha256DecryptStreamOpenRequest { encryption_key, mac_key, iv, expected_mac },
    TwofishCbcPkcs7StreamOpenRequest { key, iv },
    HkdfSha256Request { seed, salt, info },
    Pbkdf2Sha256Request { seed, salt },
    Argon2Request { seed, salt, secret, associated_data },
    HmacRequest { key, data },
    DigestRequest { data },
    AesEcbNoPaddingEncryptRequest { key, data },
    AesEcbNoPaddingTransformRequest { key, data },
    AesCbcPkcs7Request { key, iv, data },
    AesCbcPkcs7HmacSha256EncryptRequest { encryption_key, mac_key, iv, plaintext },
    AesCbcPkcs7HmacSha256DecryptRequest { encryption_key, mac_key, iv, ciphertext, expected_mac },
    StreamCipherXorAtOffsetRequest { key, nonce, data },
    RsaOaepDecryptRequest { private_key_pkcs8, ciphertext },
    RsaOaepEncryptRequest { public_key_spki, plaintext },
    RsaPkcs8ToSpkiRequest { private_key_pkcs8 },
    SshAgentTcpChaCha20Poly1305Request { key, nonce, header, payload },
    SshKeyParseRequest { private_key_pem },
    SshKeyDescribeRequest { private_key },
    SshPrivateKeyRsaBitsRequest { private_key },
    SshAgentSignRequest { private_key_pem, data },
    SshPrivateKeyImportRequest { content, passphrase_utf8 },
    SshKeyExportCxfRequest { private_key_pem },
    PasskeyKeyInspectRequest { private_key_pkcs8 },
    PasskeySignRequest { private_key_pkcs8, data },
    OpenPgpVerifyRequest { content },
    OpenPgpPublicKeyParseRequest { key_data },
    OpenPgpMetadataResolveRequest { private_key_data },
    OpenPgpKeyImportRequest { key_data, passphrase_utf8 },
    OpenPgpSignRequest { content, private_key },
    OpenPgpDetachedSignStreamOpenRequest { private_key },
    OpenPgpClearSignStreamOpenRequest { private_key },
    OpenPgpEncryptRequest { content, signing_private_key },
    OpenPgpEncryptStreamOpenRequest { signing_private_key },
    OpenPgpDecryptRequest { content, private_keys },
    OpenPgpDecryptStreamOpenRequest { private_keys },
    OpenPgpExpirationUpdateRequest { private_key },
    OpenPgpUserIdRevocationRequest { private_key },
    OpenPgpUserIdReplacementRequest { private_key },
    OpenPgpCertificateMaterialReconcileV2Request { existing_secret_certificate, incoming_secret_certificate },
    OpenPgpAgentSignRequest { private_key, hash },
    OpenPgpAgentDecryptRequest { private_key, ciphertext },
}

// Outputs that carry private key material or decrypted data.
erase_on_drop! {
    record_zeroized_secret_output_drop;
    SshKeyMaterial { private_key },
    SshKeyExportCxfResult { private_key_pkcs8 },
    PasskeyKeyMaterial { private_key_pkcs8 },
    SshKeyDescription { private_key_pem },
    OpenPgpKeyMaterial { private_key_armored },
    OpenPgpCertificateMaterialReconcileV2Success { local_secret_material, transferable_secret_key },
    OpenPgpDecryptResult { data },
    OpenPgpDecryptFinal { data },
    OpenPgpAgentSignSuccess { canonical_sexp },
    OpenPgpAgentDecryptSuccess { canonical_sexp },
}
