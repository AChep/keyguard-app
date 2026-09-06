//! Semantic evaluation for caller-authenticated OpenPGP revocation packets.
//!
//! Cryptographic verification and revocation-authority resolution happen at
//! the caller's trust boundary. Read policy supplies policy-acceptable
//! evidence. This module then applies one consistent interpretation of reason
//! codes, timestamps, and the target's Revocable flag.
//!
//! RFC 9580 §5.2.3.31 distinguishes compromised keys from superseded or
//! retired keys so that artifacts created before a prospective revocation
//! remain valid. A newer live owner signature may supersede an explicit key
//! retirement or supersession. Other key revocations remain final.

use pgp::packet::{RevocationCode, Signature, SubpacketData};

use crate::openpgp::crypto::verification::signature_creation_time;

use super::acceptance::signature_expired;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(in crate::openpgp) enum RevocationScope {
    /// Retroactive for primary-key and subkey targets: older signatures and
    /// data must be treated as suspect.
    Retrospective,
    /// Prospective: artifacts from before the revocation remain valid.
    Prospective,
}

/// Describes the kind of OpenPGP object targeted by the revocation.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(in crate::openpgp) enum RevocationTarget {
    PrimaryKey,
    Subkey,
    /// Certification revocations are always ordered by creation time,
    /// irrespective of their reason code. They must be live at the reference
    /// time and remain effective until a strictly newer authoritative
    /// certification supersedes them.
    ///
    /// `revocable` describes the individual certification currently being
    /// tested against the revocation. RFC 4880 §5.2.3.12 and RFC 9580
    /// §5.2.3.20 scope the Revocable flag to the signature that carries it.
    /// Callers therefore resolve each certification independently, discard
    /// the revoked ones, and only then select the newest survivor.
    Certification {
        revocable: bool,
    },
    /// A Certification Revocation attached directly to the primary key can
    /// revoke a Direct Key signature under the same ordering and Revocable
    /// rules as an identity certification. Unlike identity certification
    /// revocations, this target retains Signature Expiration semantics.
    DirectKeySignature {
        revocable: bool,
    },
}

/// Evaluates already-authenticated revocations against the current effective
/// authoritative signature.
///
/// Prospective primary-key and subkey reasons take effect at their creation
/// time; retrospective reasons also invalidate historical use. Only explicit
/// retirement and supersession can be superseded by a strictly newer, live,
/// policy-acceptable owner signature for that target. Revocation signature
/// expiration alone never restores a key. Certification revocations
/// remain live only until expiration or a strictly newer authoritative
/// certification. Every revocation requires its mandatory creation time;
/// without one, it is malformed and ineffective.
pub(in crate::openpgp) fn revocation_is_effective<'a>(
    mut revocations: impl Iterator<Item = &'a Signature>,
    effective_signature: Option<&Signature>,
    reference_time: u64,
    target: RevocationTarget,
) -> bool {
    let key_target = matches!(
        target,
        RevocationTarget::PrimaryKey | RevocationTarget::Subkey
    );
    if matches!(
        target,
        RevocationTarget::Certification { revocable: false }
            | RevocationTarget::DirectKeySignature { revocable: false }
    ) {
        return false;
    }

    let effective_time = effective_signature.and_then(signature_creation_time);
    revocations.any(|signature| {
        let Some(revocation_time) = signature_creation_time(signature) else {
            return false;
        };
        if key_target {
            if revocation_scope(signature) == RevocationScope::Retrospective {
                return true;
            }
            if u64::from(revocation_time) > reference_time {
                return false;
            }
            let supersedable = matches!(
                revocation_reason(signature),
                Some(RevocationCode::KeySuperseded | RevocationCode::KeyRetired)
            );
            let superseded = supersedable
                && effective_time.is_some_and(|time| {
                    revocation_time < time && u64::from(time) <= reference_time
                })
                && effective_signature
                    .is_some_and(|signature| !signature_expired(signature, reference_time));
            return !superseded;
        }

        if u64::from(revocation_time) > reference_time {
            return false;
        }
        let ordered_after_effective_signature =
            effective_time.is_none_or(|time| time <= revocation_time);
        !signature_expired(signature, reference_time) && ordered_after_effective_signature
    })
}

pub(in crate::openpgp) fn revocation_scope(signature: &Signature) -> RevocationScope {
    revocation_scope_for_reason(revocation_reason(signature))
}

fn revocation_reason(signature: &Signature) -> Option<RevocationCode> {
    signature.config().and_then(|config| {
        config
            .hashed_subpackets
            .iter()
            .rev()
            .find_map(|subpacket| match &subpacket.data {
                SubpacketData::RevocationReason(reason, _) => Some(*reason),
                _ => None,
            })
    })
}

fn revocation_scope_for_reason(reason: Option<RevocationCode>) -> RevocationScope {
    match reason {
        Some(
            RevocationCode::KeySuperseded
            | RevocationCode::KeyRetired
            | RevocationCode::CertUserIdInvalid,
        ) => RevocationScope::Prospective,
        Some(
            RevocationCode::NoReason
            | RevocationCode::KeyCompromised
            | RevocationCode::Private100
            | RevocationCode::Private101
            | RevocationCode::Private102
            | RevocationCode::Private103
            | RevocationCode::Private104
            | RevocationCode::Private105
            | RevocationCode::Private106
            | RevocationCode::Private107
            | RevocationCode::Private108
            | RevocationCode::Private109
            | RevocationCode::Private110
            | RevocationCode::Other(_),
        )
        | None => RevocationScope::Retrospective,
    }
}

pub(in crate::openpgp) fn signature_is_revocable(signature: &Signature) -> bool {
    signature.config().is_none_or(|config| {
        config
            .hashed_subpackets
            .iter()
            .rev()
            .find_map(|subpacket| match subpacket.data {
                SubpacketData::Revocable(revocable) => Some(revocable),
                _ => None,
            })
            .unwrap_or(true)
    })
}

#[cfg(test)]
mod tests;
