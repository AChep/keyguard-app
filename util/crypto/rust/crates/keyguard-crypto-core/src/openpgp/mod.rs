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
mod architecture_tests;

#[cfg(test)]
mod tests {
    #[test]
    fn production_modules_respect_layer_boundaries() {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("src/openpgp");
        for path in production_sources(&root) {
            let relative = path.strip_prefix(&root).expect("source under root");
            let name = relative.to_string_lossy().replace('\\', "/");
            let layer = name.split('/').next().expect("layer");
            if layer == "adapter" || name == "adapter.rs" || name == "mod.rs" {
                continue;
            }
            let source = std::fs::read_to_string(&path)
                .expect("read source")
                .replace("\r\n", "\n");
            let dependencies = super::architecture_tests::production_dependencies(&source);
            let mut forbidden = vec!["crate::protocol", "prost::Message", "openpgp::adapter"];
            if !["agent", "key", "message", "mutation"].contains(&layer) {
                forbidden.extend([
                    "openpgp::agent",
                    "openpgp::key",
                    "openpgp::message",
                    "openpgp::mutation",
                ]);
            }
            if layer == "crypto" {
                forbidden.extend(["openpgp::certificate", "openpgp::policy"]);
            }
            if layer == "message" {
                forbidden.push("agent::");
            }
            for dependency in forbidden {
                assert!(
                    !dependencies.iter().any(|path| path.contains(dependency)),
                    "{name} crosses its layer boundary with {dependency}"
                );
            }
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
