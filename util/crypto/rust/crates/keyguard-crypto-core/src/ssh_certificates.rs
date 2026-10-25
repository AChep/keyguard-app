//! OpenSSH certificate inspection.
//!
//! Keyguard stores a user certificate next to an SSH key and shows its fields.
//! The CA signature is not verified: the SSH server decides whether it trusts
//! the certificate, and the client never needs the CA's public key.

use prost::Message;
use ssh_key::{
    Certificate, HashAlg, PublicKey,
    certificate::{CertType, OptionsMap},
};

use crate::{
    primitives::PrimitiveError,
    protocol::{SshCertificateDescription, SshCertificateOption, SshCertificateType},
    ssh_keys::bound_text,
};

pub(crate) fn describe(
    certificate_openssh: String,
    public_key_openssh: Option<String>,
) -> Result<Vec<u8>, PrimitiveError> {
    bound_text(&certificate_openssh)?;
    let certificate = Certificate::from_openssh(certificate_openssh.trim())
        .map_err(|_| PrimitiveError::InvalidArgument)?;
    let public_key_matches = match public_key_openssh {
        Some(public_key_openssh) => {
            bound_text(&public_key_openssh)?;
            // A malformed item key cannot be certified.
            PublicKey::from_openssh(public_key_openssh.trim())
                .is_ok_and(|public_key| public_key.key_data() == certificate.public_key())
        }
        None => false,
    };

    Ok(SshCertificateDescription {
        r#type: match certificate.cert_type() {
            CertType::User => SshCertificateType::User,
            CertType::Host => SshCertificateType::Host,
        } as i32,
        key_id: certificate.key_id().to_owned(),
        serial: certificate.serial(),
        principals: certificate.valid_principals().to_vec(),
        valid_after: saturating_i64(certificate.valid_after()),
        valid_before: saturating_i64(certificate.valid_before()),
        ca_fingerprint: certificate
            .signature_key()
            .fingerprint(HashAlg::Sha256)
            .to_string(),
        critical_options: options(certificate.critical_options()),
        extensions: options(certificate.extensions()),
        public_key_matches,
    }
    .encode_to_vec())
}

fn saturating_i64(value: u64) -> i64 {
    i64::try_from(value).unwrap_or(i64::MAX)
}

fn options(map: &OptionsMap) -> Vec<SshCertificateOption> {
    map.iter()
        .map(|(name, value)| SshCertificateOption {
            name: name.clone(),
            value: value.clone(),
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    const EXAMPLES: &str = concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../../../../thirdParty/rust/ssh-key-0.6.7-keyguard/tests/examples"
    );

    fn example(name: &str) -> String {
        std::fs::read_to_string(format!("{EXAMPLES}/{name}")).expect("example file")
    }

    fn describe_example(certificate: &str, public_key: Option<&str>) -> SshCertificateDescription {
        let payload = describe(example(certificate), public_key.map(example))
            .expect("certificate description");
        SshCertificateDescription::decode(payload.as_slice()).expect("description payload")
    }

    #[test]
    fn describes_a_user_certificate_like_ssh_keygen() {
        let description = describe_example("ed25519-user-forever-cert.pub", Some("id_ed25519.pub"));
        assert_eq!(description.r#type, SshCertificateType::User as i32);
        assert_eq!(description.key_id, "keyguard-ed25519-user-forever");
        assert_eq!(description.serial, 0);
        assert_eq!(description.principals, ["test-user"]);
        // `ssh-keygen -L` prints the same CA fingerprint.
        assert_eq!(
            description.ca_fingerprint,
            "SHA256:jTLGWbD4UG+AGexRMvU0a1/TJtuiUI597k4ZJzE4/8U"
        );
        assert!(description.critical_options.is_empty());
        let extensions: Vec<_> = description
            .extensions
            .iter()
            .map(|option| option.name.as_str())
            .collect();
        assert_eq!(
            extensions,
            [
                "permit-X11-forwarding",
                "permit-agent-forwarding",
                "permit-port-forwarding",
                "permit-pty",
                "permit-user-rc",
            ]
        );
        assert!(description.public_key_matches);
    }

    #[test]
    fn saturates_a_certificate_that_never_expires() {
        let description = describe_example("ed25519-user-forever-cert.pub", None);
        assert_eq!(description.valid_after, 0);
        assert_eq!(description.valid_before, i64::MAX);
    }

    #[test]
    fn reports_a_bounded_validity_window() {
        let description = describe_example("id_ed25519-cert-with-p256-ca.pub", None);
        // 2022-04-09T14:35:39Z to 2100-03-05T14:35:39Z; `ssh-keygen -L`
        // prints the same window in local time.
        assert_eq!(description.valid_after, 1_649_514_939);
        assert_eq!(description.valid_before, 4_107_940_539);
    }

    #[test]
    fn reports_a_certificate_for_another_key() {
        let description =
            describe_example("ed25519-user-forever-cert.pub", Some("id_rsa_3072.pub"));
        assert!(!description.public_key_matches);
    }

    #[test]
    fn reports_no_match_without_or_with_a_malformed_key() {
        assert!(!describe_example("ed25519-user-forever-cert.pub", None).public_key_matches);
        let payload = describe(
            example("ed25519-user-forever-cert.pub"),
            Some("ssh-ed25519 !".to_owned()),
        )
        .expect("certificate description");
        let description = SshCertificateDescription::decode(payload.as_slice()).unwrap();
        assert!(!description.public_key_matches);
    }

    #[test]
    fn describes_an_rsa_certificate() {
        let description = describe_example("rsa-user-forever-cert.pub", Some("id_rsa_3072.pub"));
        assert_eq!(description.r#type, SshCertificateType::User as i32);
        assert!(description.public_key_matches);
    }

    #[test]
    fn describes_a_host_certificate() {
        let description = describe_example("ed25519-host-forever-cert.pub", None);
        assert_eq!(description.r#type, SshCertificateType::Host as i32);
    }

    #[test]
    fn describes_certificates_signed_by_rsa_and_ecdsa_cas() {
        let rsa = describe_example("id_ed25519-cert-with-rsa-ca.pub", Some("id_ed25519.pub"));
        assert_eq!(
            rsa.ca_fingerprint,
            "SHA256:FKAyeywtQNZLl1YTzIzCV/ThadBlnWMaD7jHQYDseEY"
        );
        assert!(rsa.public_key_matches);
        let ecdsa = describe_example("id_ed25519-cert-with-p256-ca.pub", Some("id_ed25519.pub"));
        assert_eq!(
            ecdsa.ca_fingerprint,
            "SHA256:JQ6FV0rf7qqJHZqIj4zNH8eV0oB8KLKh9Pph3FTD98g"
        );
        assert!(ecdsa.principals.is_empty());
        assert!(ecdsa.public_key_matches);
    }

    #[test]
    fn does_not_verify_the_ca_signature() {
        let description = describe_example("id_ed25519-cert-badsig.pub", Some("id_ed25519.pub"));
        assert!(description.public_key_matches);
    }

    #[test]
    fn accepts_surrounding_whitespace() {
        let certificate = format!(
            "\n  {}  \n",
            example("ed25519-user-forever-cert.pub").trim()
        );
        assert!(describe(certificate, None).is_ok());
    }

    #[test]
    fn rejects_a_plain_key_and_malformed_input() {
        for input in [
            example("id_ed25519.pub"),
            "garbage".to_owned(),
            String::new(),
        ] {
            assert!(describe(input, None).is_err());
        }
    }
}
