//! Parsing, validation, and formatting for Assuan command values.

use crate::ipc::messages::GpgKey;
use anyhow::{bail, Context, Result};

const KEYGRIP_LEN: usize = 20;
const KEYGRIP_HEX_LEN: usize = KEYGRIP_LEN * 2;

pub(super) fn parse_command(line: &str) -> ParsedCommand<'_> {
    let (name, args) = line
        .split_once(char::is_whitespace)
        .map(|(name, args)| (name, args.trim_start()))
        .unwrap_or((line, ""));
    ParsedCommand {
        name: name.to_ascii_uppercase(),
        args,
    }
}

pub(super) struct ParsedCommand<'a> {
    pub(super) name: String,
    pub(super) args: &'a str,
}

pub(super) enum HaveKeyArgs {
    List { limit: Option<usize> },
    Query(Vec<String>),
}

pub(super) struct KeygripCommandArgs {
    pub(super) another: bool,
    pub(super) keygrip: String,
}

pub(super) fn parse_havekey_args(args: &str) -> Result<HaveKeyArgs> {
    let mut list = None;
    let mut requested = Vec::new();

    for arg in args.split_whitespace() {
        match arg {
            "--list" => {
                list = Some(None);
            }
            "--info" => bail!("HAVEKEY --info is not supported"),
            other if other.starts_with("--list=") => {
                let value = other.trim_start_matches("--list=");
                let limit = value
                    .parse::<usize>()
                    .context("invalid HAVEKEY --list limit")?;
                if limit == 0 {
                    bail!("invalid HAVEKEY --list limit");
                }
                list = Some(Some(limit));
            }
            other if other.starts_with("--") => {}
            other => requested.push(other),
        }
    }

    if let Some(limit) = list {
        Ok(HaveKeyArgs::List { limit })
    } else if requested.is_empty() {
        bail!("missing keygrip")
    } else {
        Ok(HaveKeyArgs::Query(
            requested
                .into_iter()
                .map(normalize_keygrip)
                .collect::<Result<Vec<_>>>()?,
        ))
    }
}

pub(super) fn parse_keyinfo_args(args: &str) -> Result<(bool, Option<String>)> {
    let mut list = false;
    let mut keygrip_arg = None;
    for arg in args.split_whitespace() {
        match arg {
            "--list" | "--list=1000" | "--data" => {
                if arg.starts_with("--list") {
                    list = true;
                }
            }
            other if !other.starts_with("--") => {
                if keygrip_arg.is_none() {
                    keygrip_arg = Some(other);
                }
            }
            _ => {}
        }
    }
    let keygrip = if list {
        None
    } else {
        keygrip_arg.map(normalize_keygrip).transpose()?
    };
    Ok((list, keygrip))
}

pub(super) fn parse_keygrip_command_args(args: &str) -> Result<KeygripCommandArgs> {
    let mut another = false;
    let mut keygrip = None;
    for arg in args.split_whitespace() {
        match arg {
            "--another" => another = true,
            other if other.starts_with("--") => {}
            other => {
                if keygrip.is_none() {
                    keygrip = Some(normalize_keygrip(other)?);
                }
            }
        }
    }

    Ok(KeygripCommandArgs {
        another,
        keygrip: keygrip.context("missing keygrip")?,
    })
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) struct PkdecryptArgs {
    pub(super) unwrap_ecdh: bool,
}

pub(super) fn parse_pkdecrypt_args(args: &str) -> Result<PkdecryptArgs> {
    let mut unwrap_ecdh = false;
    let mut args = args.split_whitespace().peekable();
    while let Some(arg) = args.next() {
        if arg == "--kem" {
            unwrap_ecdh = true;
            if let Some(next) = args.next_if(|next| !next.starts_with("--")) {
                validate_pkdecrypt_kem(next)?;
            }
            continue;
        }
        if let Some(kem) = arg.strip_prefix("--kem=") {
            unwrap_ecdh = true;
            validate_pkdecrypt_kem(kem)?;
        }
    }
    Ok(PkdecryptArgs { unwrap_ecdh })
}

fn validate_pkdecrypt_kem(kem: &str) -> Result<()> {
    match kem {
        "PQC-PGP" | "PGP" | "CMS" => Ok(()),
        _ => bail!("invalid KEM algorithm"),
    }
}

fn normalize_keygrip(input: &str) -> Result<String> {
    let keygrip = input.trim();
    if keygrip.len() != KEYGRIP_HEX_LEN {
        bail!("invalid keygrip length");
    }
    if !keygrip.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        bail!("invalid keygrip hex");
    }
    Ok(keygrip.to_ascii_uppercase())
}

pub(super) fn keygrip_bytes(input: &str) -> Result<Vec<u8>> {
    hex::decode(normalize_keygrip(input)?).context("invalid keygrip hex")
}

/// Converts libgcrypt's advanced-format value S-expression `(value #HEX#)` (what
/// the Keyguard processor returns) into the CANONICAL transport encoding that gpg's
/// PKDECRYPT result parser expects: `(5:value<N>:<N raw bytes>)`.
///
/// Whitespace inside the advanced form is tolerated; the hex payload must decode to
/// raw bytes. gpg performs the PKCS#1 unpadding / RFC 6637 unwrap itself from these
/// raw bytes, so we relay them verbatim.
pub(super) fn advanced_value_to_canonical(value_sexp: &str) -> Result<Vec<u8>> {
    let trimmed = value_sexp.trim();
    let inner = trimmed
        .strip_prefix('(')
        .and_then(|s| s.strip_suffix(')'))
        .context("value S-expression is not a parenthesized list")?
        .trim();
    let rest = inner
        .strip_prefix("value")
        .context("value S-expression does not start with `value`")?
        .trim();
    let hex = rest
        .strip_prefix('#')
        .and_then(|s| s.strip_suffix('#'))
        .context("value payload is not in #HEX# form")?;
    // Tolerate internal whitespace/newlines in the hex run.
    let hex: String = hex.chars().filter(|c| !c.is_whitespace()).collect();
    let raw = hex::decode(&hex).context("value payload is not valid hex")?;

    let mut out = Vec::with_capacity(raw.len() + 16);
    out.extend_from_slice(b"(5:value");
    out.extend_from_slice(raw.len().to_string().as_bytes());
    out.push(b':');
    out.extend_from_slice(&raw);
    out.push(b')');
    Ok(out)
}

#[derive(Debug)]
pub(super) enum SethashParseError {
    UnsupportedAlgorithm,
    Parameter(anyhow::Error),
}

impl From<anyhow::Error> for SethashParseError {
    fn from(value: anyhow::Error) -> Self {
        Self::Parameter(value)
    }
}

pub(super) fn parse_sethash(
    args: &str,
) -> std::result::Result<(String, Vec<u8>, bool), SethashParseError> {
    // gpg-agent's SETHASH grammar is:
    //   SETHASH (--hash=<name> | <algo-number>) <hexdigest>
    // i.e. the algorithm is either the `--hash=` option or the FIRST positional
    // argument (a numeric algorithm id), and the hex digest is the remaining
    // positional. Real gpg uses the positional form, e.g. `SETHASH 8 <hex>`.
    let mut hash_algorithm = None;
    let mut pss = false;
    let mut positionals = Vec::new();
    for arg in args.split_whitespace() {
        if let Some(name) = arg.strip_prefix("--hash=") {
            hash_algorithm = Some(normalize_hash_algorithm_option(name)?);
        } else if arg == "--pss" {
            pss = true;
        } else if arg == "--inquire" {
            return Err(SethashParseError::Parameter(anyhow::anyhow!(
                "unsupported SETHASH option: {arg}"
            )));
        } else if !arg.starts_with("--") {
            positionals.push(arg);
        }
    }

    let hash_hex = match (hash_algorithm.is_some(), positionals.as_slice()) {
        // `--hash=<name> <hexdigest>`
        (true, [hex]) => *hex,
        // `<algo-number> <hexdigest>`
        (false, [algo, hex]) => {
            hash_algorithm = Some(normalize_hash_algorithm(algo)?);
            *hex
        }
        (false, [hex]) => {
            // No algorithm at all; reject rather than guessing.
            let _ = hex;
            return Err(SethashParseError::UnsupportedAlgorithm);
        }
        (false, []) => return Err(SethashParseError::UnsupportedAlgorithm),
        _ => {
            return Err(SethashParseError::Parameter(anyhow::anyhow!(
                "malformed SETHASH arguments"
            )));
        }
    };

    let hash = hex::decode(hash_hex).context("invalid hash hex")?;
    validate_hash_length(hash_algorithm.as_deref(), &hash)?;
    let hash_algorithm = hash_algorithm.context("missing hash algorithm")?;
    Ok((hash_algorithm, hash, pss))
}

fn validate_hash_length(hash_algorithm: Option<&str>, hash: &[u8]) -> Result<()> {
    if hash_algorithm == Some("tls-md5sha1") && hash.len() == 36
        || matches!(hash.len(), 16 | 20 | 24 | 28 | 32 | 48 | 64)
    {
        Ok(())
    } else {
        bail!("unsupported length of hash")
    }
}

fn normalize_hash_algorithm(input: &str) -> std::result::Result<String, SethashParseError> {
    let normalized = match input.to_ascii_lowercase().as_str() {
        "2" | "sha1" => "sha1",
        "8" | "sha256" => "sha256",
        "9" | "sha384" => "sha384",
        "10" | "sha512" => "sha512",
        "11" | "sha224" => "sha224",
        "3" | "rmd160" | "ripemd160" => "rmd160",
        "1" | "md5" => "md5",
        _ => return Err(SethashParseError::UnsupportedAlgorithm),
    };
    Ok(normalized.to_string())
}

fn normalize_hash_algorithm_option(input: &str) -> std::result::Result<String, SethashParseError> {
    let normalized = match input.to_ascii_lowercase().as_str() {
        "sha1" => "sha1",
        "sha224" => "sha224",
        "sha256" => "sha256",
        "sha384" => "sha384",
        "sha512" => "sha512",
        "rmd160" | "ripemd160" => "rmd160",
        "md5" => "md5",
        "tls-md5sha1" => "tls-md5sha1",
        "none" => return Err(SethashParseError::UnsupportedAlgorithm),
        _ => {
            return Err(SethashParseError::Parameter(anyhow::anyhow!(
                "invalid hash algorithm"
            )));
        }
    };
    Ok(normalized.to_string())
}

pub(super) fn key_matches(key: &GpgKey, requested_keygrip: &str) -> bool {
    key.keygrip.eq_ignore_ascii_case(requested_keygrip)
}

/// Whether a key is usable by this agent: sign-capable (for PKSIGN) or
/// decrypt-capable (for PKDECRYPT). gpg probes both kinds via HAVEKEY/KEYINFO.
pub(super) fn key_usable(key: &GpgKey) -> bool {
    key.can_sign || key.can_decrypt
}

pub(super) fn format_keyinfo(key: &GpgKey) -> String {
    let keygrip = key.keygrip.to_ascii_uppercase();
    let fingerprint = if key.fingerprint.is_empty() {
        "-"
    } else {
        &key.fingerprint
    };
    let flags = if key.can_sign { "S" } else { "-" };
    // Fields follow gpg-agent's KEYINFO status shape:
    // keygrip type serialno idstr cached protection fpr ttl flags.
    format!("{keygrip} D - - - P {fingerprint} - {flags}")
}

#[cfg(test)]
mod tests {
    use super::*;

    const KEYGRIP_LOWER: &str = "0123456789abcdef0123456789abcdef01234567";
    const KEYGRIP_UPPER: &str = "0123456789ABCDEF0123456789ABCDEF01234567";

    #[test]
    fn sethash_parses_hash_name_and_hex() {
        let hash_hex = "AA".repeat(32);
        let (algorithm, hash, pss) = parse_sethash(&format!("--hash=sha256 {hash_hex}")).unwrap();
        assert_eq!(algorithm, "sha256");
        assert_eq!(hash, vec![0xaa; 32]);
        assert!(!pss);
    }

    #[test]
    fn sethash_parses_positional_algo_and_hex() {
        // Real gpg sends `SETHASH <algo-number> <hexdigest>`, e.g. `SETHASH 8 ...`.
        let hash_hex = "AA".repeat(32);
        let (algorithm, hash, pss) = parse_sethash(&format!("8 {hash_hex}")).unwrap();
        assert_eq!(algorithm, "sha256");
        assert_eq!(hash, vec![0xaa; 32]);
        assert!(!pss);
    }

    #[test]
    fn sethash_rejects_missing_algorithm() {
        let hash_hex = "AA".repeat(32);
        assert!(parse_sethash(&hash_hex).is_err());
    }

    #[test]
    fn sethash_rejects_unsupported_digest_length() {
        assert!(parse_sethash("8 AABB").is_err());
    }

    #[test]
    fn sethash_rejects_unsupported_options() {
        let hash_hex = "AA".repeat(32);
        assert!(parse_sethash(&format!("--inquire 8 {hash_hex}")).is_err());
        assert!(parse_sethash(&format!("--hash=none {hash_hex}")).is_err());
    }

    #[test]
    fn sethash_parses_pss_and_tls_md5sha1() {
        let sha256_hex = "AA".repeat(32);
        let (_, _, pss) = parse_sethash(&format!("--pss 8 {sha256_hex}")).unwrap();
        assert!(pss);

        let tls_hex = "AA".repeat(36);
        let (algorithm, hash, pss) =
            parse_sethash(&format!("--hash=tls-md5sha1 {tls_hex}")).unwrap();
        assert_eq!(algorithm, "tls-md5sha1");
        assert_eq!(hash, vec![0xaa; 36]);
        assert!(!pss);
    }

    #[test]
    fn pkdecrypt_args_accept_valid_kem_options() {
        assert!(!parse_pkdecrypt_args("").unwrap().unwrap_ecdh);
        assert!(parse_pkdecrypt_args("--kem").unwrap().unwrap_ecdh);
        assert!(parse_pkdecrypt_args("--kem PGP").unwrap().unwrap_ecdh);
        assert!(parse_pkdecrypt_args("--kem=PQC-PGP").unwrap().unwrap_ecdh);
        assert!(parse_pkdecrypt_args("--kem=PGP").unwrap().unwrap_ecdh);
        assert!(parse_pkdecrypt_args("--kem=CMS").unwrap().unwrap_ecdh);
        assert!(parse_pkdecrypt_args("--kem=BAD").is_err());
    }

    #[test]
    fn advanced_value_becomes_canonical() {
        // (value #DEADBEEF#) -> (5:value4:<raw>)
        let canonical = advanced_value_to_canonical("(value #DEADBEEF#)").unwrap();
        let mut expected = Vec::new();
        expected.extend_from_slice(b"(5:value4:");
        expected.extend_from_slice(&[0xde, 0xad, 0xbe, 0xef]);
        expected.push(b')');
        assert_eq!(canonical, expected);
    }

    #[test]
    fn advanced_value_tolerates_whitespace() {
        let canonical = advanced_value_to_canonical("( value #DE AD\nBEEF# )").unwrap();
        assert!(canonical.starts_with(b"(5:value4:"));
        assert_eq!(
            &canonical[canonical.len() - 5..],
            &[0xde, 0xad, 0xbe, 0xef, b')']
        );
    }

    #[test]
    fn advanced_value_rejects_garbage() {
        assert!(advanced_value_to_canonical("(sig-val ...)").is_err());
    }

    #[test]
    fn keyinfo_list_args_are_detected() {
        let (list, keygrip) = parse_keyinfo_args("--list --data").unwrap();
        assert!(list);
        assert_eq!(keygrip, None);
    }

    #[test]
    fn keyinfo_args_validate_and_normalize_keygrip() {
        let (list, keygrip) = parse_keyinfo_args(KEYGRIP_LOWER).unwrap();
        assert!(!list);
        assert_eq!(keygrip.as_deref(), Some(KEYGRIP_UPPER));
        assert!(parse_keyinfo_args("abcd").is_err());
    }

    #[test]
    fn havekey_args_parse_queries_and_list_limit() {
        match parse_havekey_args(KEYGRIP_LOWER).unwrap() {
            HaveKeyArgs::Query(keygrips) => assert_eq!(keygrips, vec![KEYGRIP_UPPER]),
            HaveKeyArgs::List { .. } => panic!("expected query mode"),
        }

        match parse_havekey_args("--list=2").unwrap() {
            HaveKeyArgs::List { limit } => assert_eq!(limit, Some(2)),
            HaveKeyArgs::Query(_) => panic!("expected list mode"),
        }

        match parse_havekey_args("--list").unwrap() {
            HaveKeyArgs::List { limit } => assert_eq!(limit, None),
            HaveKeyArgs::Query(_) => panic!("expected list mode"),
        }

        assert!(parse_havekey_args("abcd").is_err());
    }

    #[test]
    fn keygrip_command_args_detect_another() {
        let parsed = parse_keygrip_command_args(&format!("--another {KEYGRIP_LOWER}")).unwrap();
        assert!(parsed.another);
        assert_eq!(parsed.keygrip, KEYGRIP_UPPER);
    }

    #[test]
    fn keyinfo_status_contains_keygrip_and_flags() {
        let status = format_keyinfo(&GpgKey {
            name: "Test".to_string(),
            keygrip: "abcd".to_string(),
            fingerprint: "FFFF".to_string(),
            algorithm: "rsa".to_string(),
            can_sign: true,
            can_decrypt: false,
        });
        assert_eq!(status, "ABCD D - - - P FFFF - S");
    }

    #[test]
    fn key_usable_accepts_sign_or_decrypt() {
        let mut key = GpgKey {
            name: "k".to_string(),
            keygrip: "AB".to_string(),
            fingerprint: String::new(),
            algorithm: "rsa".to_string(),
            can_sign: false,
            can_decrypt: false,
        };
        assert!(!key_usable(&key));
        key.can_decrypt = true;
        assert!(key_usable(&key));
        key.can_decrypt = false;
        key.can_sign = true;
        assert!(key_usable(&key));
    }
}
