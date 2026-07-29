//! Canonical naming contract for Keyguard temporary artifacts.
//!
//! Lease-aware names use explicit version-1 file, directory, or sidecar
//! coordination tokens. Kotlin mirrors the same contract, while uncoordinated
//! and unknown names remain reserved but are not parsed as sweep candidates.

use std::{fmt::Write as _, io};

/// Shared prefix of every Keyguard temporary artifact name.
pub const TEMPORARY_ARTIFACT_PREFIX: &str = ".kg-tmp-";

/// Shared suffix of every Keyguard temporary artifact name.
pub const TEMPORARY_ARTIFACT_SUFFIX: &str = ".tmp";

/// Bounded number of exclusive-creation attempts before giving up.
pub const MAX_TEMPORARY_ARTIFACT_ATTEMPTS: usize = 128;

const UUID_LENGTH: usize = 36;
const FILE_LEASE_V1_TOKEN: &str = "v1f-";
const DIRECTORY_LEASE_V1_TOKEN: &str = "v1d-";
const SIDECAR_LEASE_V1_TOKEN: &str = "v1s-";
const DATA_SUFFIX: &str = ".tmp";
const LEASE_SUFFIX: &str = ".lease";

/// Coordination protocol encoded in a temporary artifact name.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub enum TemporaryArtifactProtocol {
    /// Per-data-file producer lease.
    FileLeaseV1,
    /// Shared writer lease and exclusive sweeper lease on the directory.
    DirectoryLeaseV1,
    /// Per-artifact regular-file sidecar lease.
    SidecarLeaseV1,
}

/// Entry represented by a parsed temporary-artifact name.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub enum TemporaryArtifactEntryKind {
    /// Staged data.
    Data,
    /// Sidecar carrying the producer/sweeper lease.
    Lease,
}

/// Strictly parsed temporary-artifact name.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub struct ParsedTemporaryArtifact<'a> {
    /// Coordination protocol required before inspecting or deleting the entry.
    pub protocol: TemporaryArtifactProtocol,
    /// Purpose of the staged data.
    pub role: TemporaryFileRole,
    /// Canonical RFC 9562 version-4 UUID shared by a sidecar pair.
    pub nonce: &'a str,
    /// Whether the name denotes staged data or its lease sidecar.
    pub entry_kind: TemporaryArtifactEntryKind,
}

/// Purpose encoded in a Keyguard temporary artifact name.
#[repr(i32)]
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub enum TemporaryFileRole {
    /// New bytes staged for atomic publication.
    New = 0,
    /// Previous bytes retained while replacing an object.
    Previous = 1,
    /// Private scratch storage.
    Scratch = 2,
}

/// Names belonging to one temporary artifact and its optional lease sidecar.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct TemporaryArtifactNames {
    /// Name of the staged data entry.
    pub data: String,
    /// Owner-only lease sidecar used by [`TemporaryArtifactProtocol::SidecarLeaseV1`].
    pub lease: Option<String>,
}

impl TemporaryFileRole {
    const ALL: [Self; 3] = [Self::New, Self::Previous, Self::Scratch];

    const fn token(self) -> &'static str {
        match self {
            Self::New => "n",
            Self::Previous => "o",
            Self::Scratch => "s",
        }
    }

    /// Returns the sweep-mask bit assigned to this role.
    #[must_use]
    pub const fn mask_bit(self) -> u32 {
        1 << (self as u32)
    }
}

impl TryFrom<i32> for TemporaryFileRole {
    type Error = ();

    fn try_from(value: i32) -> Result<Self, Self::Error> {
        match value {
            0 => Ok(Self::New),
            1 => Ok(Self::Previous),
            2 => Ok(Self::Scratch),
            _ => Err(()),
        }
    }
}

/// Generates a fresh temporary artifact name for `role`.
///
/// The nonce is an RFC 9562 version-4 UUID drawn from the operating system's
/// random source, so names are unpredictable to a squatting sibling process.
///
/// # Errors
///
/// Returns an error when random nonce generation fails.
pub fn new_file_lease_artifact_name(role: TemporaryFileRole) -> io::Result<String> {
    Ok(new_temporary_artifact_names(role, TemporaryArtifactProtocol::FileLeaseV1)?.data)
}

/// Generates a fresh, protocol-aware set of temporary artifact names.
///
/// File-lease and directory-lease artifacts consist only of a data name. A
/// sidecar-lease artifact receives data and lease names carrying the same
/// unpredictable nonce.
///
/// # Errors
///
/// Returns an error when random nonce generation or filename formatting
/// fails.
pub fn new_temporary_artifact_names(
    role: TemporaryFileRole,
    protocol: TemporaryArtifactProtocol,
) -> io::Result<TemporaryArtifactNames> {
    let mut nonce = [0_u8; 16];
    getrandom::fill(&mut nonce)
        .map_err(|error| io::Error::other(format!("random nonce generation failed: {error}")))?;
    // RFC 9562 UUID version 4 and variant 1.
    nonce[6] = (nonce[6] & 0x0f) | 0x40;
    nonce[8] = (nonce[8] & 0x3f) | 0x80;

    let mut nonce_text = String::with_capacity(UUID_LENGTH);
    for (index, byte) in nonce.iter().enumerate() {
        if matches!(index, 4 | 6 | 8 | 10) {
            nonce_text.push('-');
        }
        write!(&mut nonce_text, "{byte:02x}")
            .map_err(|_| io::Error::other("temporary filename formatting failed"))?;
    }
    Ok(
        temporary_artifact_names_from_nonce(role, protocol, &nonce_text)
            .expect("generated RFC 9562 UUID must be canonical"),
    )
}

/// Constructs protocol-aware names from a canonical RFC 9562 version-4 UUID.
///
/// Returns `None` when `nonce` is not in the canonical lowercase UUID form.
#[must_use]
pub fn temporary_artifact_names_from_nonce(
    role: TemporaryFileRole,
    protocol: TemporaryArtifactProtocol,
    nonce: &str,
) -> Option<TemporaryArtifactNames> {
    if !is_canonical_uuid(nonce) {
        return None;
    }
    let protocol_token = match protocol {
        TemporaryArtifactProtocol::FileLeaseV1 => FILE_LEASE_V1_TOKEN,
        TemporaryArtifactProtocol::DirectoryLeaseV1 => DIRECTORY_LEASE_V1_TOKEN,
        TemporaryArtifactProtocol::SidecarLeaseV1 => SIDECAR_LEASE_V1_TOKEN,
    };
    let stem = format!(
        "{TEMPORARY_ARTIFACT_PREFIX}{protocol_token}{}-{nonce}",
        role.token()
    );
    Some(TemporaryArtifactNames {
        data: format!("{stem}{DATA_SUFFIX}"),
        lease: (protocol == TemporaryArtifactProtocol::SidecarLeaseV1)
            .then(|| format!("{stem}{LEASE_SUFFIX}")),
    })
}

/// Parses a canonical version-1 lease-aware temporary-artifact name.
///
/// Recognition alone does not make an entry safe to delete. The caller must
/// also establish the lease required by [`ParsedTemporaryArtifact::protocol`].
#[must_use]
pub fn parse_temporary_artifact_name(name: &str) -> Option<ParsedTemporaryArtifact<'_>> {
    let body = name.strip_prefix(TEMPORARY_ARTIFACT_PREFIX)?;
    let (protocol, body) = if let Some(body) = body.strip_prefix(FILE_LEASE_V1_TOKEN) {
        (TemporaryArtifactProtocol::FileLeaseV1, body)
    } else if let Some(body) = body.strip_prefix(DIRECTORY_LEASE_V1_TOKEN) {
        (TemporaryArtifactProtocol::DirectoryLeaseV1, body)
    } else {
        let body = body.strip_prefix(SIDECAR_LEASE_V1_TOKEN)?;
        (TemporaryArtifactProtocol::SidecarLeaseV1, body)
    };
    let (entry_kind, body) = if let Some(body) = body.strip_suffix(DATA_SUFFIX) {
        (TemporaryArtifactEntryKind::Data, body)
    } else if protocol == TemporaryArtifactProtocol::SidecarLeaseV1 {
        (
            TemporaryArtifactEntryKind::Lease,
            body.strip_suffix(LEASE_SUFFIX)?,
        )
    } else {
        return None;
    };
    let (token, nonce) = body.split_at_checked(1)?;
    let nonce = nonce.strip_prefix('-')?;
    if !is_canonical_uuid(nonce) {
        return None;
    }
    let role = TemporaryFileRole::ALL
        .into_iter()
        .find(|role| role.token() == token)?;
    Some(ParsedTemporaryArtifact {
        protocol,
        role,
        nonce,
        entry_kind,
    })
}

/// Returns whether `name` belongs to Keyguard's reserved temporary namespace.
///
/// Malformed and unknown future names remain reserved but are never assumed
/// safe to delete.
#[must_use]
pub fn is_reserved_temporary_artifact_name(name: &str) -> bool {
    name.starts_with(TEMPORARY_ARTIFACT_PREFIX)
}

fn is_canonical_uuid(value: &str) -> bool {
    if value.len() != UUID_LENGTH {
        return false;
    }
    let bytes = value.as_bytes();
    if bytes[14] != b'4' || !matches!(bytes[19], b'8' | b'9' | b'a' | b'b') {
        return false;
    }
    bytes.iter().copied().enumerate().all(|(index, byte)| {
        if matches!(index, 8 | 13 | 18 | 23) {
            byte == b'-'
        } else {
            byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase()
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn temporary_names_use_keyguard_uuid_format() {
        let name = new_file_lease_artifact_name(TemporaryFileRole::Scratch)
            .expect("temporary name generation must succeed");

        assert!(name.starts_with(".kg-tmp-v1f-s-"));
        assert!(name.ends_with(".tmp"));
        assert_eq!(name.len(), ".kg-tmp-v1f-s-".len() + 36 + ".tmp".len());
        assert_eq!(name.as_bytes()[".kg-tmp-v1f-s-".len() + 14], b'4');
        assert!(matches!(
            name.as_bytes()[".kg-tmp-v1f-s-".len() + 19],
            b'8' | b'9' | b'a' | b'b'
        ));
    }

    #[test]
    fn generated_names_round_trip_through_the_parser() {
        for role in TemporaryFileRole::ALL {
            let name = new_file_lease_artifact_name(role).expect("name generation must succeed");
            let parsed = parse_temporary_artifact_name(&name).expect("name must parse");
            assert_eq!(parsed.protocol, TemporaryArtifactProtocol::FileLeaseV1);
            assert_eq!(parsed.role, role);
            assert_eq!(parsed.entry_kind, TemporaryArtifactEntryKind::Data);
        }
    }

    #[test]
    fn parser_rejects_non_canonical_names() {
        // Golden vector shared with the Kotlin TemporaryArtifactNamingTest.
        assert_eq!(
            parse_temporary_artifact_name(".kg-tmp-v1f-n-01234567-89ab-4cde-8f01-23456789abcd.tmp")
                .map(|parsed| parsed.role),
            Some(TemporaryFileRole::New),
        );
        for name in [
            "",
            "vault.kdbx",
            ".kg-tmp-v1f-n-.tmp",
            ".kg-tmp-v1f-x-01234567-89ab-4cde-8f01-23456789abcd.tmp",
            ".kg-tmp-v1f-n-01234567-89ab-4cde-8f01-23456789abcd.bak",
            ".kg-tmp-v1f-n-01234567-89AB-4CDE-8F01-23456789ABCD.tmp",
            ".kg-tmp-v1f-n-0123456789ab4cde8f0123456789abcd.tmp",
            "kg-tmp-v1f-n-01234567-89ab-4cde-8f01-23456789abcd.tmp",
            ".kg-tmp-v1f-s-01234567-89ab-4cde-8f01-23456789abcg.tmp",
            ".kg-tmp-v1f-n-01234567-89ab-3cde-8f01-23456789abcd.tmp",
            ".kg-tmp-v1f-n-01234567-89ab-4cde-7f01-23456789abcd.tmp",
            ".kg-tmp-v1f-n-01234567-89ab-4cde-cf01-23456789abcd.tmp",
        ] {
            assert_eq!(parse_temporary_artifact_name(name), None, "name: {name}");
        }
    }

    #[test]
    fn all_version_one_lease_protocols_parse() {
        let nonce = "01234567-89ab-4cde-8f01-23456789abcd";
        assert_eq!(
            parse_temporary_artifact_name(&format!(".kg-tmp-v1f-n-{nonce}.tmp")),
            Some(ParsedTemporaryArtifact {
                protocol: TemporaryArtifactProtocol::FileLeaseV1,
                role: TemporaryFileRole::New,
                nonce,
                entry_kind: TemporaryArtifactEntryKind::Data,
            })
        );
        assert_eq!(
            parse_temporary_artifact_name(&format!(".kg-tmp-v1d-o-{nonce}.tmp")),
            Some(ParsedTemporaryArtifact {
                protocol: TemporaryArtifactProtocol::DirectoryLeaseV1,
                role: TemporaryFileRole::Previous,
                nonce,
                entry_kind: TemporaryArtifactEntryKind::Data,
            })
        );
        assert_eq!(
            parse_temporary_artifact_name(&format!(".kg-tmp-v1s-s-{nonce}.lease")),
            Some(ParsedTemporaryArtifact {
                protocol: TemporaryArtifactProtocol::SidecarLeaseV1,
                role: TemporaryFileRole::Scratch,
                nonce,
                entry_kind: TemporaryArtifactEntryKind::Lease,
            })
        );
    }

    #[test]
    fn protocol_aware_construction_round_trips_and_pairs_sidecar_nonce() {
        let nonce = "01234567-89ab-4cde-8f01-23456789abcd";
        for protocol in [
            TemporaryArtifactProtocol::FileLeaseV1,
            TemporaryArtifactProtocol::DirectoryLeaseV1,
            TemporaryArtifactProtocol::SidecarLeaseV1,
        ] {
            let names =
                temporary_artifact_names_from_nonce(TemporaryFileRole::Previous, protocol, nonce)
                    .expect("golden nonce is canonical");
            let data = parse_temporary_artifact_name(&names.data).expect("data name must parse");
            assert_eq!(data.protocol, protocol);
            assert_eq!(data.role, TemporaryFileRole::Previous);
            assert_eq!(data.nonce, nonce);
            assert_eq!(data.entry_kind, TemporaryArtifactEntryKind::Data);

            match names.lease {
                Some(lease_name) => {
                    assert_eq!(protocol, TemporaryArtifactProtocol::SidecarLeaseV1);
                    let lease =
                        parse_temporary_artifact_name(&lease_name).expect("lease name must parse");
                    assert_eq!(lease.protocol, protocol);
                    assert_eq!(lease.role, TemporaryFileRole::Previous);
                    assert_eq!(lease.nonce, nonce);
                    assert_eq!(lease.entry_kind, TemporaryArtifactEntryKind::Lease);
                }
                None => assert_ne!(protocol, TemporaryArtifactProtocol::SidecarLeaseV1),
            }
        }
    }

    #[test]
    fn generated_protocol_names_are_canonical_and_sidecars_share_the_nonce() {
        for protocol in [
            TemporaryArtifactProtocol::FileLeaseV1,
            TemporaryArtifactProtocol::DirectoryLeaseV1,
            TemporaryArtifactProtocol::SidecarLeaseV1,
        ] {
            let names = new_temporary_artifact_names(TemporaryFileRole::New, protocol)
                .expect("temporary names must generate");
            let data = parse_temporary_artifact_name(&names.data).expect("data must parse");
            assert_eq!(data.protocol, protocol);
            if let Some(lease_name) = names.lease {
                let lease = parse_temporary_artifact_name(&lease_name).expect("lease must parse");
                assert_eq!(lease.nonce, data.nonce);
            }
        }
    }

    #[test]
    fn construction_rejects_noncanonical_nonces() {
        for nonce in [
            "01234567-89AB-4CDE-8F01-23456789ABCD",
            "01234567-89ab-3cde-8f01-23456789abcd",
            "01234567-89ab-4cde-7f01-23456789abcd",
            "0123456789ab4cde8f0123456789abcd",
        ] {
            assert_eq!(
                temporary_artifact_names_from_nonce(
                    TemporaryFileRole::Scratch,
                    TemporaryArtifactProtocol::SidecarLeaseV1,
                    nonce,
                ),
                None
            );
        }
    }

    #[test]
    fn malformed_and_unknown_names_stay_reserved_but_do_not_parse() {
        for name in [
            ".kg-tmp-n-01234567-89ab-4cde-8f01-23456789abcd.tmp",
            ".kg-tmp-v2d-n-01234567-89ab-4cde-8f01-23456789abcd.tmp",
            ".kg-tmp-v2s-n-01234567-89ab-4cde-8f01-23456789abcd.tmp",
            ".kg-tmp-v1u-n-01234567-89ab-4cde-8f01-23456789abcd.tmp",
            ".kg-tmp-v3-n-01234567-89ab-4cde-8f01-23456789abcd.tmp",
            ".kg-tmp-v1d-n-01234567-89ab-4cde-8f01-23456789abcd.lease",
            ".kg-tmp-v1s-x-01234567-89ab-4cde-8f01-23456789abcd.tmp",
            ".kg-tmp-v1s-n-01234567-89ab-3cde-8f01-23456789abcd.tmp",
        ] {
            assert!(is_reserved_temporary_artifact_name(name), "name: {name}");
            assert_eq!(parse_temporary_artifact_name(name), None, "name: {name}");
        }
    }

    #[test]
    fn role_mask_bits_are_stable() {
        assert_eq!(TemporaryFileRole::New.mask_bit(), 1);
        assert_eq!(TemporaryFileRole::Previous.mask_bit(), 2);
        assert_eq!(TemporaryFileRole::Scratch.mask_bit(), 4);
    }
}
