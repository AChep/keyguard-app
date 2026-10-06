//! Internal OpenPGP implementation.
//!
//! The module keeps wire preservation, certificate interpretation, policy
//! evaluation, and stateful operations as separate layers.  Only [`adapter`]
//! is called by the native protobuf dispatcher; all other modules expose
//! crate-private domain operations.
//!
//! Dependencies flow from packet and concrete cryptographic helpers through
//! certificates and policy into the key, message, mutation, and agent
//! workflows.  Lower layers must not import operation modules.

pub(crate) mod adapter;
pub(in crate::openpgp) mod agent;
pub(in crate::openpgp) mod certificate;
pub(in crate::openpgp) mod crypto;
pub(in crate::openpgp) mod error;
pub(in crate::openpgp) mod format;
pub(in crate::openpgp) mod key;
pub(in crate::openpgp) mod message;
pub(in crate::openpgp) mod mutation;
pub(in crate::openpgp) mod packet;
pub(in crate::openpgp) mod policy;
pub(in crate::openpgp) mod user_id;

#[cfg(test)]
mod tests {
    const FOUNDATION_SOURCES: &[(&str, &str)] = &[
        ("packet", include_str!("packet/mod.rs")),
        ("packet armor", include_str!("packet/armor.rs")),
        ("packet dearmor", include_str!("packet/dearmor.rs")),
        ("packet framing", include_str!("packet/framing.rs")),
        ("packet lengths", include_str!("packet/length.rs")),
        ("packet mpi", include_str!("packet/mpi.rs")),
        ("packet stream", include_str!("packet/stream.rs")),
        ("packet types", include_str!("packet/types.rs")),
        ("certificate", include_str!("certificate/mod.rs")),
        (
            "certificate component",
            include_str!("certificate/component.rs"),
        ),
        (
            "certificate certification",
            include_str!("certificate/certification.rs"),
        ),
        (
            "certificate identity",
            include_str!("certificate/identity.rs"),
        ),
        ("certificate index", include_str!("certificate/index.rs")),
        (
            "certificate material",
            include_str!("certificate/material.rs"),
        ),
        ("certificate model", include_str!("certificate/model.rs")),
        (
            "certificate canonicalization",
            include_str!("certificate/model/canonicalization.rs"),
        ),
        (
            "certificate export",
            include_str!("certificate/model/export.rs"),
        ),
        (
            "certificate parsing",
            include_str!("certificate/model/parsing.rs"),
        ),
        ("crypto", include_str!("crypto/mod.rs")),
        ("crypto keygrip", include_str!("crypto/keygrip.rs")),
        ("crypto public", include_str!("crypto/public.rs")),
        ("crypto secret", include_str!("crypto/secret.rs")),
        ("crypto signer", include_str!("crypto/signer.rs")),
        ("crypto verifier", include_str!("crypto/verifier.rs")),
        (
            "crypto verification",
            include_str!("crypto/verification.rs"),
        ),
        ("error", include_str!("error.rs")),
        ("format", include_str!("format.rs")),
        ("User ID", include_str!("user_id.rs")),
    ];

    const POLICY_SOURCES: &[(&str, &str)] = &[
        ("policy", include_str!("policy/mod.rs")),
        ("policy acceptance", include_str!("policy/acceptance.rs")),
        ("policy budget", include_str!("policy/budget.rs")),
        ("policy evaluation", include_str!("policy/evaluation.rs")),
        ("policy certificate index", include_str!("policy/index.rs")),
        ("policy model", include_str!("policy/model.rs")),
        ("policy revocation", include_str!("policy/revocation.rs")),
        ("policy selection", include_str!("policy/selection.rs")),
    ];

    const CRYPTO_SOURCES: &[(&str, &str)] = &[
        ("crypto", include_str!("crypto/mod.rs")),
        ("crypto keygrip", include_str!("crypto/keygrip.rs")),
        ("crypto public", include_str!("crypto/public.rs")),
        ("crypto secret", include_str!("crypto/secret.rs")),
        ("crypto signer", include_str!("crypto/signer.rs")),
        ("crypto verifier", include_str!("crypto/verifier.rs")),
        (
            "crypto verification",
            include_str!("crypto/verification.rs"),
        ),
    ];

    const WORKFLOW_SOURCES: &[(&str, &str)] = &[
        ("agent", include_str!("agent/mod.rs")),
        ("key", include_str!("key/mod.rs")),
        ("key generation", include_str!("key/generation.rs")),
        ("key import", include_str!("key/import.rs")),
        ("key model", include_str!("key/model.rs")),
        ("message read", include_str!("message/read.rs")),
        ("message read model", include_str!("message/read/model.rs")),
        ("message", include_str!("message/mod.rs")),
        ("message write", include_str!("message/write.rs")),
        (
            "message write common",
            include_str!("message/write/common.rs"),
        ),
        (
            "message write decryption",
            include_str!("message/write/decryption.rs"),
        ),
        (
            "message write encryption",
            include_str!("message/write/encryption.rs"),
        ),
        (
            "message write model",
            include_str!("message/write/model.rs"),
        ),
        (
            "message write signing",
            include_str!("message/write/signing.rs"),
        ),
        (
            "message write streaming",
            include_str!("message/write/streaming.rs"),
        ),
        (
            "mutation expiration",
            include_str!("mutation/expiration.rs"),
        ),
        ("mutation", include_str!("mutation/mod.rs")),
        ("mutation material", include_str!("mutation/material.rs")),
        ("mutation reconcile", include_str!("mutation/reconcile.rs")),
        (
            "mutation replace User ID",
            include_str!("mutation/replace_user_id.rs"),
        ),
        (
            "mutation revoke User ID",
            include_str!("mutation/revoke_user_id.rs"),
        ),
    ];

    #[test]
    fn foundation_layers_do_not_depend_on_wire_or_operation_layers() {
        for (name, source) in FOUNDATION_SOURCES {
            for forbidden in [
                "crate::protocol",
                "prost::Message",
                "openpgp::adapter",
                "openpgp::agent",
                "openpgp::key",
                "openpgp::message",
                "openpgp::mutation",
            ] {
                assert!(
                    !source.contains(forbidden),
                    "{name} imports the higher layer {forbidden}"
                );
            }
        }
    }

    #[test]
    fn policy_does_not_depend_on_wire_or_operation_layers() {
        for (name, source) in POLICY_SOURCES {
            for forbidden in [
                "crate::protocol",
                "prost::Message",
                "openpgp::adapter",
                "openpgp::agent",
                "openpgp::key",
                "openpgp::message",
                "openpgp::mutation",
            ] {
                assert!(
                    !source.contains(forbidden),
                    "{name} imports the higher layer {forbidden}"
                );
            }
        }
    }

    #[test]
    fn crypto_does_not_depend_on_certificate_or_policy_layers() {
        for (name, source) in CRYPTO_SOURCES {
            for forbidden in ["openpgp::certificate", "openpgp::policy"] {
                assert!(
                    !source.contains(forbidden),
                    "{name} imports the higher layer {forbidden}"
                );
            }
        }
    }

    #[test]
    fn workflows_reach_generated_protocol_only_through_the_adapter() {
        for (name, source) in WORKFLOW_SOURCES {
            for forbidden in [
                "crate::protocol",
                "prost::Message",
                "openpgp::adapter::wire",
            ] {
                assert!(
                    !source.contains(forbidden),
                    "{name} bypasses the OpenPGP adapter with {forbidden}"
                );
            }
        }
    }

    #[test]
    fn message_read_is_protocol_independent() {
        for (name, source) in [
            ("message read", include_str!("message/read.rs")),
            ("message read model", include_str!("message/read/model.rs")),
        ] {
            for forbidden in ["crate::protocol", "prost::Message", "openpgp::adapter"] {
                assert!(
                    !source.contains(forbidden),
                    "{name} bypasses its domain boundary with {forbidden}"
                );
            }
        }
    }

    #[test]
    fn message_workflows_do_not_depend_on_agent_workflows() {
        for (name, source) in [
            ("message read", include_str!("message/read.rs")),
            ("message read model", include_str!("message/read/model.rs")),
            ("message write", include_str!("message/write.rs")),
            (
                "message write common",
                include_str!("message/write/common.rs"),
            ),
            (
                "message write decryption",
                include_str!("message/write/decryption.rs"),
            ),
            (
                "message write encryption",
                include_str!("message/write/encryption.rs"),
            ),
            (
                "message write model",
                include_str!("message/write/model.rs"),
            ),
            (
                "message write signing",
                include_str!("message/write/signing.rs"),
            ),
            (
                "message write streaming",
                include_str!("message/write/streaming.rs"),
            ),
        ] {
            assert!(
                !source.contains("agent::"),
                "{name} imports the agent workflow"
            );
        }
    }

    /// rPGP methods that verify with a caller-supplied public key.
    const RPGP_VERIFY_METHODS: &[&str] = &[
        "verify",
        "verify_bindings",
        "verify_certification",
        "verify_key",
        "verify_key_third_party",
        "verify_nested",
        "verify_nested_explicit",
        "verify_primary_key_binding",
        "verify_read",
        "verify_subkey_binding",
        "verify_third_party",
        "verify_third_party_certification",
    ];

    /// `PublicComponent` forwards to its key; callers wrap the component.
    const FORWARDING_CALLS: &[(&str, &str)] =
        &[("certificate/component.rs", ".verify(hash, data, signature)")];

    #[test]
    fn rpgp_verification_goes_through_the_openpgp_verifier() {
        // k256 rejects the high-S secp256k1 signatures that GnuPG creates, so
        // a raw rPGP key silently fails to verify them.
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("src/openpgp");
        let mut wrapped = 0;
        let mut used_exemptions = [false; FORWARDING_CALLS.len()];
        let mut unwrapped = Vec::new();
        for path in production_sources(&root) {
            let name = path
                .strip_prefix(&root)
                .expect("source under the OpenPGP root")
                .to_string_lossy()
                .replace('\\', "/");
            if name == "crypto/verifier.rs" {
                continue;
            }
            let source = std::fs::read_to_string(&path)
                .expect("read OpenPGP source")
                .replace("\r\n", "\n");
            let source = without_line_comments(without_test_module(&source));
            for (dot, _) in source.match_indices('.') {
                let rest = &source[dot + 1..];
                let method_len = rest
                    .find(|c: char| !(c.is_ascii_alphanumeric() || c == '_'))
                    .unwrap_or(rest.len());
                if !RPGP_VERIFY_METHODS.contains(&&rest[..method_len]) {
                    continue;
                }
                let after = rest[method_len..].trim_start();
                if !after.starts_with('(') {
                    continue;
                }
                let arguments = call_arguments(after);
                let trimmed = arguments.trim_start();
                if trimmed.starts_with('|') || trimmed.starts_with("move ") {
                    // OpenPgpPolicyBudget::verify takes a closure.
                    continue;
                }
                if let Some(index) = FORWARDING_CALLS
                    .iter()
                    .position(|(file, call)| *file == name && source[dot..].starts_with(call))
                {
                    used_exemptions[index] = true;
                    continue;
                }
                if arguments.contains("OpenPgpVerifier(") || receiver_is_wrapped(&source[..dot]) {
                    wrapped += 1;
                    continue;
                }
                let line = source[..dot].matches('\n').count() + 1;
                unwrapped.push(format!("{name}:{line}: .{}", &rest[..method_len]));
            }
        }
        assert!(
            unwrapped.is_empty(),
            "wrap the signer in OpenPgpVerifier: {unwrapped:#?}"
        );
        assert!(wrapped > 0, "found no rPGP verification calls");
        for ((file, call), used) in FORWARDING_CALLS.iter().zip(used_exemptions) {
            assert!(used, "stale exemption {file}: {call}");
        }
    }

    fn production_sources(directory: &std::path::Path) -> Vec<std::path::PathBuf> {
        let mut sources = Vec::new();
        for entry in std::fs::read_dir(directory).expect("list OpenPGP sources") {
            let path = entry.expect("read OpenPGP source entry").path();
            let file_name = path
                .file_name()
                .and_then(|name| name.to_str())
                .unwrap_or_default();
            if path.is_dir() {
                if file_name != "tests" {
                    sources.extend(production_sources(&path));
                }
            } else if file_name.ends_with(".rs")
                && file_name != "tests.rs"
                && !file_name.ends_with("_tests.rs")
            {
                sources.push(path);
            }
        }
        sources
    }

    fn without_test_module(source: &str) -> &str {
        source
            .find("#[cfg(test)]\nmod tests {")
            .map_or(source, |start| &source[..start])
    }

    fn without_line_comments(source: &str) -> String {
        source
            .lines()
            .map(|line| line.find("//").map_or(line, |start| &line[..start]))
            .collect::<Vec<_>>()
            .join("\n")
    }

    /// Returns the text between `call`'s opening parenthesis and its match.
    fn call_arguments(call: &str) -> &str {
        let mut depth = 0_usize;
        for (index, c) in call.char_indices() {
            match c {
                '(' => depth += 1,
                ')' => {
                    depth -= 1;
                    if depth == 0 {
                        return &call[1..index];
                    }
                }
                _ => {}
            }
        }
        &call[1..]
    }

    /// Whether the method receiver is an `OpenPgpVerifier(..)` expression.
    fn receiver_is_wrapped(before_dot: &str) -> bool {
        let Some(receiver) = before_dot.trim_end().strip_suffix(')') else {
            return false;
        };
        let mut depth = 1_usize;
        for (index, c) in receiver.char_indices().rev() {
            match c {
                ')' => depth += 1,
                '(' => {
                    depth -= 1;
                    if depth == 0 {
                        return receiver[..index].ends_with("OpenPgpVerifier");
                    }
                }
                _ => {}
            }
        }
        false
    }
}
