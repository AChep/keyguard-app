use keyguard_crypto_sensitive::pkcs12::{self, ImportError};
use prost::Message;

use crate::protocol::{Pkcs12Request, Pkcs12Result};

pub(crate) fn process(request: Pkcs12Request) -> Vec<u8> {
    let result = match pkcs12::import_with_output_password(
        &request.data,
        request.password_utf8.as_deref(),
        request.normalize,
        request.output_password_utf8.as_deref().unwrap_or_default(),
        request.export_private_key,
    ) {
        Ok(mut identity) => Pkcs12Result {
            status: 0,
            normalized_pkcs12: std::mem::take(&mut *identity.bundle),
            private_key_der: std::mem::take(&mut *identity.private_key_der),
            display_name: identity.display_name,
            issuer: identity.issuer,
            expires_at: identity.expires_at,
            subject: identity.subject,
            issuer_common_name: identity.issuer_common_name,
            not_before: identity.not_before,
            serial_number: identity.serial_number,
            sha256_fingerprint: identity.sha256_fingerprint,
            key_type: identity.key_type,
        },
        Err(error) => {
            // The result zeroizes on drop, so it can not be built from a default.
            let mut result = Pkcs12Result::default();
            result.status = match error {
                ImportError::Password if request.password_utf8.is_none() => 1,
                ImportError::Password => 2,
                ImportError::Invalid => 3,
            };
            result
        }
    };
    result.encode_to_vec()
}
