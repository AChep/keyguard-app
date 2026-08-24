//! OpenPGP v4 expiration recertification on the shared certificate packet set.
//!
//! One of the three mutations built on [`crate::openpgp::mutation`]'s
//! pipeline: raw framing -> `PublicCertificatePacketSet` -> `finalize()` /
//! `CanonicalCertificate` -> `validate_certificate` -> this operation.
//!
//! Renewal replaces the policy-selected self-signature of every requested
//! component with a freshly issued equivalent, in place, inside the same
//! packet evidence the merge pipeline produces. Nothing else about the
//! certificate is rewritten, and the whole mutation is serialized and
//! re-validated exactly once. RSA private signatures are routed through the
//! AWS-LC adapter in [`crate::openpgp::crypto`].

use pgp::{
    composed::{SignedPublicKey, SignedPublicSubKey},
    crypto::{hash::HashAlgorithm, public_key::PublicKeyAlgorithm},
    packet::{
        PacketTrait, SecretSubkey, Signature, SignatureConfig, SignatureType, Subpacket,
        SubpacketData,
    },
    types::{
        Duration, KeyDetails, Password, SignedUser, SignedUserAttribute, SigningKey, Timestamp,
    },
};
use zeroize::{Zeroize, Zeroizing};

use crate::openpgp::{
    certificate::{
        CertificateAddition, CertificateIndex, CertificateSignatureOwner, KeyMaterial,
        serialize_packet_body,
    },
    crypto::{
        secret::{OpenPgpSecretSigner, SecretPacketRef},
        signer::{SigningKeyRef, select_signature_hash},
        verification::{
            cryptographic_signature_material_cmp, signature_ignoring_unhashed_issuer_hints,
            signature_verification_compatible,
        },
    },
    format::{fingerprint_hex, normalize_fingerprint},
    mutation::{
        MutationPreflight, impl_mutation_failure_conversions, next_signature_time,
        validate_mutation_document_bounds,
    },
    packet::{USER_ATTRIBUTE_TAG, USER_ID_TAG},
    policy::{
        DesignatedRevokerId, IdentityPolicy, PolicyContext, PolicyInactiveTemplate,
        PolicySelection, ValidatedCertificate, authenticated_key_flags,
        authentication_signature_acceptable, certificate_index, select_newest_policy_signature_in,
        signature_creation_time, signature_expiration_seconds, signature_expired,
        signature_issuer_consistent,
    },
};

const MAX_COMPONENTS: usize = 64;

pub(crate) struct ExpirationUpdateInput {
    pub(crate) private_key: Vec<u8>,
    pub(crate) public_key: Vec<u8>,
    pub(crate) expected_primary_fingerprint: String,
    pub(crate) component_fingerprints: Vec<String>,
    pub(crate) expires_at_epoch_seconds: Option<u64>,
    pub(crate) candidate_revocation_keys: Vec<Vec<u8>>,
    pub(crate) reference_time_epoch_seconds: u64,
}

impl Drop for ExpirationUpdateInput {
    fn drop(&mut self) {
        self.private_key.zeroize();
    }
}

pub(crate) struct ExpirationUpdateSuccess {
    pub(crate) key_material: KeyMaterial,
    pub(crate) certificate_index: CertificateIndex,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum ExpirationUpdateFailure {
    EmptyPrivateKey,
    MalformedKey,
    FingerprintMismatch,
    NoComponentsSelected,
    ComponentNotFound,
    RevokedComponent,
    UnresolvedRevocationAuthority,
    UnsupportedKeyVersion,
    MissingSecretKey,
    ProtectedSecretKey,
    MissingSelfSignature,
    InvalidExpiration,
    TimeConflict,
    SignatureVerificationFailed,
    InternalFailure,
    UnsupportedSigningHash,
    ResourceLimit,
}

impl_mutation_failure_conversions!(ExpirationUpdateFailure, revoked => RevokedComponent);

/// What a single planned renewal re-certifies.
enum RenewalSubject<'a> {
    Direct,
    UserId(&'a SignedUser),
    UserAttribute(&'a SignedUserAttribute),
    Subkey {
        subkey: &'a SignedPublicSubKey,
        secret: Option<&'a SecretSubkey>,
        /// The authenticated binding's existing primary-key back-signature.
        ///
        /// A back-signature certifies the subkey's possession and is not an
        /// expiration statement. Reusing the verified packet preserves
        /// public-only signing subkeys and the mutation's historical
        /// byte-preserving behavior.
        embedded: Option<Signature>,
    },
}

/// How a replacement signature treats the primary-User-ID marker.
///
/// Renewing every certification at one instant flattens both timestamp order
/// and duplicate markers, so a multi-User-ID renewal reproduces the policy
/// outcome explicitly instead of copying markers from the templates: the
/// current policy primary is pinned and every other identity sheds stale
/// markers that could otherwise flip the post-renewal tie-break.
#[derive(Clone, Copy, PartialEq, Eq)]
enum PrimaryMarker {
    /// Keep whatever marker the template carries.
    Preserve,
    /// Assert the marker, replacing any the template carries.
    Pin,
    /// Drop any marker the template carries.
    Strip,
}

/// One policy-selected self-signature and the fresh statement replacing it.
struct PlannedRenewal<'a> {
    owner: CertificateSignatureOwner,
    template: &'a Signature,
    /// Newest retained, mathematically verified statement over this subject.
    newest_relevant_time: Option<u64>,
    subject: RenewalSubject<'a>,
    primary_marker: PrimaryMarker,
    operation: RenewalOperation,
}

#[derive(Clone, Copy)]
enum RenewalOperation {
    Replace,
    Add,
}

pub(crate) fn update_expiration_request(
    mut request: ExpirationUpdateInput,
) -> Result<ExpirationUpdateSuccess, ExpirationUpdateFailure> {
    if request.private_key.iter().all(u8::is_ascii_whitespace) {
        return Err(ExpirationUpdateFailure::EmptyPrivateKey);
    }
    validate_mutation_document_bounds(
        &request.private_key,
        &request.public_key,
        &request.candidate_revocation_keys,
    )?;
    if request.component_fingerprints.is_empty() {
        return Err(ExpirationUpdateFailure::NoComponentsSelected);
    }
    if request.component_fingerprints.len() > MAX_COMPONENTS {
        return Err(ExpirationUpdateFailure::ResourceLimit);
    }
    u32::try_from(request.reference_time_epoch_seconds)
        .map_err(|_| ExpirationUpdateFailure::InvalidExpiration)?;
    if let Some(expiration) = request.expires_at_epoch_seconds
        && (expiration <= request.reference_time_epoch_seconds || expiration > u64::from(u32::MAX))
    {
        return Err(ExpirationUpdateFailure::InvalidExpiration);
    }
    let selected = normalize_selected(&request.component_fingerprints)?;

    let private_key = Zeroizing::new(std::mem::take(&mut request.private_key));
    let preflight = MutationPreflight::open(
        &private_key,
        &request.public_key,
        &request.candidate_revocation_keys,
        &request.expected_primary_fingerprint,
        request.reference_time_epoch_seconds,
    )?;
    // Renewal addresses subkeys the composed view may have discarded, so an
    // unbound subkey packet has to fail the request rather than be skipped.
    preflight.require_bound_subkeys()?;

    let certificate = &preflight.canonical.semantic;
    let primary_fingerprint = preflight.canonical.fingerprint.clone();
    let policy = preflight.policy()?;
    // The primary key issues every replacement, so it is authorized even when
    // only subkeys were selected.
    if policy.primary.policy_conflict {
        return Err(ExpirationUpdateFailure::TimeConflict);
    }
    policy.authorize_primary_renewal()?;
    let primary_selected = selected.contains(&primary_fingerprint);
    validate_selection(certificate, &policy, &selected)?;

    let attribute_bodies =
        preflight.user_attribute_bodies(certificate.details.user_attributes.len())?;
    let secret_subkeys = secret_subkeys_by_fingerprint(&preflight)?;

    let mut renewals = Vec::new();
    if primary_selected {
        plan_primary_renewals(&preflight, &policy, &attribute_bodies, &mut renewals)?;
    }
    for subkey in &certificate.public_subkeys {
        let fingerprint = fingerprint_hex(&subkey.key);
        if !selected.contains(&fingerprint) {
            continue;
        }
        let component = policy
            .subkey(&subkey.key)
            .ok_or(ExpirationUpdateFailure::ComponentNotFound)?;
        let component_policy = component.policy();
        let template = renewal_template(
            component_policy.verified_bindings.iter().copied(),
            &component_policy.verified_templates,
            PolicyContext::Subkey,
            preflight.reference_time,
            |signature| {
                Ok(valid_embedded_signature(
                    signature,
                    &subkey.key,
                    &certificate.primary_key,
                    preflight.reference_time,
                )?
                .is_some())
            },
        )?
        .ok_or(ExpirationUpdateFailure::MissingSelfSignature)?;
        let embedded = valid_embedded_signature(
            template,
            &subkey.key,
            &certificate.primary_key,
            preflight.reference_time,
        )?;
        renewals.push(PlannedRenewal {
            owner: CertificateSignatureOwner::Subkey {
                fingerprint: subkey.key.fingerprint().as_bytes().to_vec(),
            },
            template,
            newest_relevant_time: component_policy.newest_conservative_binding_time(),
            subject: RenewalSubject::Subkey {
                subkey,
                secret: secret_subkeys.get(&fingerprint).copied(),
                embedded,
            },
            primary_marker: PrimaryMarker::Preserve,
            operation: renewal_operation(template),
        });
    }
    if renewals.is_empty() {
        return Err(ExpirationUpdateFailure::MissingSelfSignature);
    }

    let mutation_time = next_signature_time(
        preflight.reference_time,
        renewals
            .iter()
            .filter_map(|renewal| renewal.newest_relevant_time)
            .max(),
    )
    .ok_or(ExpirationUpdateFailure::TimeConflict)?;

    if preflight.secret.primary_key.secret_params().is_encrypted() {
        return Err(ExpirationUpdateFailure::ProtectedSecretKey);
    }
    let primary_signer = OpenPgpSecretSigner::new(
        SecretPacketRef::Primary(&preflight.secret.primary_key),
        &preflight.secret.primary_key as &dyn SigningKey,
    )
    .map_err(|_| ExpirationUpdateFailure::InternalFailure)?;
    let primary_signer = primary_signer.as_ref();

    let mut mutated = preflight.packet_set.clone();
    for renewal in &renewals {
        let replacement = issue_renewal(
            renewal,
            &certificate.primary_key,
            &primary_signer,
            mutation_time,
            request.expires_at_epoch_seconds,
        )?;
        let replacement_body = serialize_packet_body(&replacement)?;
        match renewal.operation {
            RenewalOperation::Replace => mutated.replace_signature(
                &renewal.owner,
                &serialize_packet_body(renewal.template)?,
                replacement_body,
            )?,
            RenewalOperation::Add => {
                mutated.apply_additions(&[CertificateAddition::Signature {
                    owner: renewal.owner.clone(),
                    body: replacement_body,
                }])?;
            }
        }
    }
    let pre_primary_user_id = policy
        .primary_user_id()
        .map(|user_id| user_id.packet_body().to_vec());
    let pre_revocation_authorities = policy
        .legacy_designated_revokers()
        .cloned()
        .collect::<Vec<_>>();
    drop(policy);

    let (output, ()) = preflight.finalize(
        &mutated,
        u64::from(mutation_time.as_secs()),
        false,
        |canonical, policy, secret_fingerprints| {
            validate_renewed_certificate(
                policy,
                &selected,
                request.expires_at_epoch_seconds,
                pre_primary_user_id.as_deref(),
                &pre_revocation_authorities,
            )?;
            Ok::<_, ExpirationUpdateFailure>((
                (),
                certificate_index(policy, canonical, secret_fingerprints),
            ))
        },
    )?;

    Ok(ExpirationUpdateSuccess {
        key_material: output.key_material,
        certificate_index: output.certificate_index,
    })
}

/// Plans the primary key's own renewals: its existing Direct-Key signature and
/// every live identity certification.
fn plan_primary_renewals<'a>(
    preflight: &'a MutationPreflight,
    policy: &ValidatedCertificate<'a>,
    attribute_bodies: &[&'a [u8]],
    renewals: &mut Vec<PlannedRenewal<'a>>,
) -> Result<(), ExpirationUpdateFailure> {
    let certificate = &preflight.canonical.semantic;
    let reference_time = preflight.reference_time;
    if let Some(template) = renewal_template(
        policy.primary.verified_bindings.iter().copied(),
        &policy.primary.verified_templates,
        PolicyContext::Direct,
        reference_time,
        |_| Ok(false),
    )? {
        renewals.push(PlannedRenewal {
            owner: CertificateSignatureOwner::Direct,
            template,
            newest_relevant_time: policy.primary.newest_conservative_binding_time(),
            subject: RenewalSubject::Direct,
            primary_marker: PrimaryMarker::Preserve,
            operation: renewal_operation(template),
        });
    }
    // Do not synthesize a Direct-Key signature for a V4 certificate. RFC 9580
    // makes it optional, and a mutation should update existing statement
    // families instead of introducing a new policy carrier with different
    // scope and precedence.

    // Renewing every certification at one instant flattens both timestamp
    // order and duplicate primary markers (merged certificates commonly carry
    // two IsPrimary certifications), so the renewal reproduces the policy
    // outcome explicitly: the current winner is pinned, exactly as User ID
    // replacement already does, and every other identity sheds stale markers
    // that could flip the post-renewal tie-break.
    let pin_current_primary =
        certificate.details.users.len() > 1 && policy.primary_user_id().is_some();

    for (index, user) in certificate.details.users.iter().enumerate() {
        let identity = policy
            .user_id_at(index)
            .ok_or(ExpirationUpdateFailure::InternalFailure)?;
        let Some(template) = live_identity_template(identity, reference_time)? else {
            continue;
        };
        // A User ID packet body is exactly its identifier, so the composed
        // view addresses the stored packet directly.
        let body = user.id.id();
        let primary_marker = if !pin_current_primary {
            PrimaryMarker::Preserve
        } else if policy
            .primary_user_id()
            .is_some_and(|primary| primary.index() == index)
        {
            PrimaryMarker::Pin
        } else {
            PrimaryMarker::Strip
        };
        renewals.push(PlannedRenewal {
            owner: CertificateSignatureOwner::Identity {
                tag: USER_ID_TAG,
                body: body.to_vec(),
            },
            template,
            newest_relevant_time: identity.newest_conservative_certification_time(),
            subject: RenewalSubject::UserId(user),
            primary_marker,
            operation: renewal_operation(template),
        });
    }

    for (index, attribute) in certificate.details.user_attributes.iter().enumerate() {
        let identity = policy
            .user_attribute_at(index)
            .ok_or(ExpirationUpdateFailure::InternalFailure)?;
        let Some(template) = live_identity_template(identity, reference_time)? else {
            continue;
        };
        let body = *attribute_bodies
            .get(index)
            .ok_or(ExpirationUpdateFailure::InternalFailure)?;
        // RFC 9580 §5.2.4 hashes the attribute's raw stored bytes, but rPGP
        // signs and verifies over its own normalized re-serialization.
        // Renewing a certification whose
        // attribute body rPGP does not reproduce would emit a signature only
        // rPGP can verify, so the existing certification is kept instead.
        if serialize_packet_body(&attribute.attr)? != body {
            continue;
        }
        renewals.push(PlannedRenewal {
            owner: CertificateSignatureOwner::Identity {
                tag: USER_ATTRIBUTE_TAG,
                body: body.to_vec(),
            },
            template,
            newest_relevant_time: identity.newest_conservative_certification_time(),
            subject: RenewalSubject::UserAttribute(attribute),
            primary_marker: PrimaryMarker::Preserve,
            operation: renewal_operation(template),
        });
    }
    Ok(())
}

/// Returns the certification a live identity may renew, or `None` when the
/// identity has none to renew.
///
/// Revocation is read straight off the policy view, so a weak-hash template can
/// never outrank a revocation and resurrect a revoked identity. An identity
/// whose revocation authority cannot be resolved fails the whole request.
fn live_identity_template<'a>(
    identity: &IdentityPolicy<'a>,
    reference_time: u64,
) -> Result<Option<&'a Signature>, ExpirationUpdateFailure> {
    if identity.policy_conflict {
        return Err(ExpirationUpdateFailure::TimeConflict);
    }
    if identity.revocation_status.is_indeterminate() {
        return Err(ExpirationUpdateFailure::UnresolvedRevocationAuthority);
    }
    if identity.revocation_status.is_revoked() {
        return Ok(None);
    }
    renewal_template(
        identity.verified_certifications.iter().copied(),
        &identity.verified_templates,
        PolicyContext::Identity,
        reference_time,
        |_| Ok(false),
    )
}

/// Picks the newest self-signature a renewal may copy.
///
/// The policy-active tier wins whenever it has a live member. Only when it has
/// none does the weak-hash template tier apply — the deliberate rescue that
/// keeps a legacy SHA-1-only certificate renewable, with the renewal itself
/// emitting the modern replacement. Neither tier can contain a future-dated
/// signature, so parking one in the future cannot block or hijack a renewal.
fn renewal_template<'a>(
    active: impl Iterator<Item = &'a Signature>,
    templates: &[PolicyInactiveTemplate<'a>],
    context: PolicyContext,
    reference_time: u64,
    mut cross_certified: impl FnMut(&Signature) -> Result<bool, ExpirationUpdateFailure>,
) -> Result<Option<&'a Signature>, ExpirationUpdateFailure> {
    let live = active.filter(|signature| !signature_expired(signature, reference_time));
    match select_newest_policy_signature_in(live, context, &mut cross_certified)? {
        PolicySelection::Selected { signature, .. } => return Ok(Some(signature)),
        PolicySelection::Conflict => return Err(ExpirationUpdateFailure::TimeConflict),
        PolicySelection::Missing => {}
    }
    let fallback = templates
        .iter()
        .map(|template| template.template_signature());
    match select_newest_policy_signature_in(fallback, context, &mut cross_certified)? {
        PolicySelection::Selected { signature, .. } => Ok(Some(signature)),
        PolicySelection::Missing => Ok(None),
        PolicySelection::Conflict => Err(ExpirationUpdateFailure::TimeConflict),
    }
}

/// Issues the replacement signature for one planned renewal.
fn issue_renewal(
    renewal: &PlannedRenewal<'_>,
    primary: &pgp::packet::PublicKey,
    signer: &SigningKeyRef<'_>,
    replacement_time: Timestamp,
    expires_at: Option<u64>,
) -> Result<Signature, ExpirationUpdateFailure> {
    match &renewal.subject {
        RenewalSubject::Direct => {
            let mut config = replacement_config(
                renewal.template,
                signer,
                primary,
                replacement_time,
                expires_at,
                None,
                PrimaryMarker::Strip,
            )?;
            config.typ = SignatureType::Key;
            let signature = config
                .sign_key(signer, &Password::empty(), primary)
                .map_err(|_| ExpirationUpdateFailure::InternalFailure)?;
            if !signature_verification_compatible(&signature, signer)
                || signature.verify_key(primary).is_err()
            {
                return Err(ExpirationUpdateFailure::SignatureVerificationFailed);
            }
            Ok(signature)
        }
        RenewalSubject::UserId(user) => {
            let config = replacement_config(
                renewal.template,
                signer,
                primary,
                replacement_time,
                expires_at,
                None,
                renewal.primary_marker,
            )?;
            config
                .sign_certification(signer, primary, &Password::empty(), user.id.tag(), &user.id)
                .map_err(|_| ExpirationUpdateFailure::InternalFailure)
        }
        RenewalSubject::UserAttribute(attribute) => {
            let config = replacement_config(
                renewal.template,
                signer,
                primary,
                replacement_time,
                expires_at,
                None,
                PrimaryMarker::Preserve,
            )?;
            config
                .sign_certification(
                    signer,
                    primary,
                    &Password::empty(),
                    attribute.attr.tag(),
                    &attribute.attr,
                )
                .map_err(|_| ExpirationUpdateFailure::InternalFailure)
        }
        RenewalSubject::Subkey {
            subkey,
            secret,
            embedded,
        } => {
            let signature_capable = binding_designates_signature_capable_subkey(
                renewal.template,
                subkey.key.algorithm(),
            );
            let embedded = if signature_capable {
                match embedded {
                    Some(signature) => Some(signature.clone()),
                    None => Some(create_primary_binding(
                        primary,
                        &subkey.key,
                        secret.ok_or(ExpirationUpdateFailure::MissingSecretKey)?,
                        replacement_time,
                    )?),
                }
            } else {
                None
            };
            let config = replacement_config(
                renewal.template,
                signer,
                &subkey.key,
                replacement_time,
                expires_at,
                embedded,
                PrimaryMarker::Preserve,
            )?;
            let signature = config
                .sign_subkey_binding(signer, primary, &Password::empty(), &subkey.key)
                .map_err(|_| ExpirationUpdateFailure::InternalFailure)?;
            if !signature_verification_compatible(&signature, signer)
                || signature
                    .verify_subkey_binding(primary, &subkey.key)
                    .is_err()
            {
                return Err(ExpirationUpdateFailure::SignatureVerificationFailed);
            }
            Ok(signature)
        }
    }
}

/// Chooses whether a renewed signature may replace its template packet.
///
/// RFC 9580 deprecates the Revocation Key subpacket and prohibits generating
/// it.  Retaining a template that carries one preserves the certificate's
/// historical declaration (including legacy User-ID interpretations) while
/// the newly added, sanitized signature changes only expiration policy.
fn renewal_operation(template: &Signature) -> RenewalOperation {
    if signature_contains_revocation_key(template) {
        RenewalOperation::Add
    } else {
        RenewalOperation::Replace
    }
}

fn signature_contains_revocation_key(signature: &Signature) -> bool {
    signature.config().is_some_and(|config| {
        config
            .hashed_subpackets
            .iter()
            .chain(&config.unhashed_subpackets)
            .any(|subpacket| matches!(subpacket.data, SubpacketData::RevocationKey(_)))
    })
}

fn normalize_selected(values: &[String]) -> Result<Vec<String>, ExpirationUpdateFailure> {
    let selected = values
        .iter()
        .map(|value| normalize_fingerprint(value))
        .collect::<Vec<_>>();
    if selected.iter().any(String::is_empty) {
        return Err(ExpirationUpdateFailure::ComponentNotFound);
    }
    let mut distinct = Vec::new();
    for value in &selected {
        if distinct.contains(value) {
            return Err(ExpirationUpdateFailure::ComponentNotFound);
        }
        distinct.push(value.clone());
    }
    Ok(selected)
}

/// Authorizes every selected component.
///
/// The primary key is skipped: it issues every replacement, so the caller has
/// already authorized it once for the whole request.
fn validate_selection(
    certificate: &SignedPublicKey,
    policy: &ValidatedCertificate<'_>,
    selected: &[String],
) -> Result<(), ExpirationUpdateFailure> {
    let primary_fingerprint = fingerprint_hex(&certificate.primary_key);
    for fingerprint in selected {
        if fingerprint == &primary_fingerprint {
            continue;
        }
        let Some(subkey) = certificate
            .public_subkeys
            .iter()
            .find(|subkey| fingerprint_hex(&subkey.key) == *fingerprint)
        else {
            return Err(ExpirationUpdateFailure::ComponentNotFound);
        };
        let component = policy
            .subkey(&subkey.key)
            .ok_or(ExpirationUpdateFailure::ComponentNotFound)?;
        if component.policy().policy_conflict {
            return Err(ExpirationUpdateFailure::TimeConflict);
        }
        component.authorize_renewal()?;
    }
    Ok(())
}

fn binding_designates_signature_capable_subkey(
    signature: &Signature,
    algorithm: PublicKeyAlgorithm,
) -> bool {
    // RFC 9580 requires the cross-certification for every subkey that can
    // issue signatures, including certifications and authentication.  When
    // the authenticated binding omits Key Flags, retain the traditional
    // algorithm-capability fallback used for legacy certificates.
    authenticated_key_flags(signature)
        .map(|flags| flags.certify() || flags.sign() || flags.authentication())
        .unwrap_or_else(|| algorithm.can_sign())
}

fn valid_embedded_signature(
    binding: &Signature,
    subkey: &pgp::packet::PublicSubkey,
    primary: &pgp::packet::PublicKey,
    reference_time: u64,
) -> Result<Option<Signature>, ExpirationUpdateFailure> {
    let Some(config) = binding.config() else {
        return Ok(None);
    };
    let mut selected: Option<(u32, Signature)> = None;
    for embedded in config
        .hashed_subpackets
        .iter()
        .chain(&config.unhashed_subpackets)
        .filter_map(|subpacket| match &subpacket.data {
            SubpacketData::EmbeddedSignature(signature) => Some(signature.as_ref()),
            _ => None,
        })
        .filter(|signature| {
            authentication_signature_acceptable(signature, reference_time)
                && signature_issuer_consistent(signature, subkey)
                && signature_verification_compatible(signature, subkey)
                && signature_ignoring_unhashed_issuer_hints(signature).is_some_and(|signature| {
                    signature
                        .verify_primary_key_binding(subkey, primary)
                        .is_ok()
                })
        })
    {
        // The newest back-signature is the subkey's current cross-certification
        // statement. Match the certificate-policy tie-break without consulting
        // mutable unhashed metadata.
        let Some(created) = signature_creation_time(embedded) else {
            continue;
        };
        if selected
            .as_ref()
            .is_none_or(|(selected_created, selected_signature)| {
                created > *selected_created
                    || (created == *selected_created
                        && cryptographic_signature_material_cmp(embedded, selected_signature)
                            .is_lt())
            })
        {
            selected = Some((created, embedded.clone()));
        }
    }
    Ok(selected.map(|(_, signature)| signature))
}

fn create_primary_binding(
    primary: &pgp::packet::PublicKey,
    subkey: &pgp::packet::PublicSubkey,
    secret_subkey: &SecretSubkey,
    replacement_time: Timestamp,
) -> Result<Signature, ExpirationUpdateFailure> {
    if secret_subkey.secret_params().is_encrypted() {
        return Err(ExpirationUpdateFailure::ProtectedSecretKey);
    }
    let packet = SecretPacketRef::Subkey(secret_subkey);
    let signer = OpenPgpSecretSigner::new(packet, secret_subkey as &dyn SigningKey)
        .map_err(|_| ExpirationUpdateFailure::InternalFailure)?;
    let signer = signer.as_ref();
    let hash_algorithm =
        replacement_hash(signer.algorithm(), signer.hash_alg(), signer.hash_alg())?;
    let mut config = SignatureConfig::v4(
        SignatureType::KeyBinding,
        signer.algorithm(),
        hash_algorithm,
    );
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(replacement_time))
            .map_err(|_| ExpirationUpdateFailure::InternalFailure)?,
        Subpacket::regular(SubpacketData::IssuerFingerprint(subkey.fingerprint()))
            .map_err(|_| ExpirationUpdateFailure::InternalFailure)?,
    ];
    config.unhashed_subpackets = vec![
        Subpacket::regular(SubpacketData::IssuerKeyId(subkey.legacy_key_id()))
            .map_err(|_| ExpirationUpdateFailure::InternalFailure)?,
    ];
    let signature = config
        .sign_primary_key_binding(&signer, subkey, &Password::empty(), primary)
        .map_err(|_| ExpirationUpdateFailure::InternalFailure)?;
    if !authentication_signature_acceptable(&signature, u64::from(replacement_time.as_secs()))
        || !signature_issuer_consistent(&signature, subkey)
        || !signature_verification_compatible(&signature, subkey)
        || signature
            .verify_primary_key_binding(subkey, primary)
            .is_err()
    {
        return Err(ExpirationUpdateFailure::SignatureVerificationFailed);
    }
    Ok(signature)
}

fn replacement_config<K: KeyDetails>(
    template: &Signature,
    signer: &SigningKeyRef<'_>,
    expiring_key: &K,
    replacement_time: Timestamp,
    expires_at: Option<u64>,
    embedded: Option<Signature>,
    primary_marker: PrimaryMarker,
) -> Result<SignatureConfig, ExpirationUpdateFailure> {
    let mut config = template
        .config()
        .cloned()
        .ok_or(ExpirationUpdateFailure::SignatureVerificationFailed)?;
    if config.version() != pgp::packet::SignatureVersion::V4 {
        return Err(ExpirationUpdateFailure::UnsupportedKeyVersion);
    }
    config.pub_alg = signer.algorithm();
    config.hash_alg = replacement_hash(signer.algorithm(), signer.hash_alg(), config.hash_alg)?;
    // RFC 9580 §5.2.3.23 requires applications not to generate this
    // deprecated subpacket.  A template carrying one is retained separately
    // by `renewal_operation`, so removing it here cannot revoke an existing
    // declaration or promote identity-scoped metadata to key-wide authority.
    config
        .hashed_subpackets
        .retain(|subpacket| !matches!(subpacket.data, SubpacketData::RevocationKey(_)));
    config
        .unhashed_subpackets
        .retain(|subpacket| !matches!(subpacket.data, SubpacketData::RevocationKey(_)));
    config.hashed_subpackets.retain(|subpacket| {
        !matches!(
            subpacket.data,
            SubpacketData::SignatureCreationTime(_)
                | SubpacketData::KeyExpirationTime(_)
                | SubpacketData::EmbeddedSignature(_)
                | SubpacketData::IssuerFingerprint(_)
                | SubpacketData::IssuerKeyId(_)
        )
    });
    // Rebuild embedded signatures and issuer hints from authenticated local
    // inputs. Retaining template copies would accumulate stale bindings or
    // attacker-controlled routing metadata across renewals.
    config.unhashed_subpackets.retain(|subpacket| {
        !matches!(
            subpacket.data,
            SubpacketData::EmbeddedSignature(_)
                | SubpacketData::IssuerFingerprint(_)
                | SubpacketData::IssuerKeyId(_)
        )
    });
    replace_creation_and_signature_expiration(&mut config, template, replacement_time)?;
    // Authenticate the full issuer fingerprint. The legacy key ID remains an
    // unhashed routing hint for V4 readers and is never treated as evidence.
    config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::IssuerFingerprint(signer.fingerprint()))
            .map_err(|_| ExpirationUpdateFailure::InternalFailure)?,
    );
    config.unhashed_subpackets.push(
        Subpacket::regular(SubpacketData::IssuerKeyId(signer.legacy_key_id()))
            .map_err(|_| ExpirationUpdateFailure::InternalFailure)?,
    );
    if let Some(expiration) = expires_at {
        let duration = expiration
            .checked_sub(u64::from(expiring_key.created_at().as_secs()))
            .filter(|duration| *duration > 0 && *duration <= u64::from(u32::MAX))
            .and_then(|duration| u32::try_from(duration).ok())
            .ok_or(ExpirationUpdateFailure::InvalidExpiration)?;
        config.hashed_subpackets.push(
            Subpacket::critical(SubpacketData::KeyExpirationTime(Duration::from_secs(
                duration,
            )))
            .map_err(|_| ExpirationUpdateFailure::InternalFailure)?,
        );
    }
    if let Some(signature) = embedded {
        config.hashed_subpackets.push(
            Subpacket::regular(SubpacketData::EmbeddedSignature(Box::new(signature)))
                .map_err(|_| ExpirationUpdateFailure::InternalFailure)?,
        );
    }
    match primary_marker {
        PrimaryMarker::Preserve => {}
        PrimaryMarker::Pin => {
            config
                .hashed_subpackets
                .retain(|subpacket| !matches!(subpacket.data, SubpacketData::IsPrimary(_)));
            config.hashed_subpackets.push(
                Subpacket::regular(SubpacketData::IsPrimary(true))
                    .map_err(|_| ExpirationUpdateFailure::InternalFailure)?,
            );
        }
        PrimaryMarker::Strip => {
            config
                .hashed_subpackets
                .retain(|subpacket| !matches!(subpacket.data, SubpacketData::IsPrimary(_)));
        }
    }
    Ok(config)
}

fn replace_creation_and_signature_expiration(
    config: &mut SignatureConfig,
    template: &Signature,
    replacement_time: Timestamp,
) -> Result<(), ExpirationUpdateFailure> {
    signature_creation_time(template).ok_or(ExpirationUpdateFailure::MissingSelfSignature)?;
    config.hashed_subpackets.retain(|subpacket| {
        !matches!(
            subpacket.data,
            SubpacketData::SignatureCreationTime(_) | SubpacketData::SignatureExpirationTime(_)
        )
    });
    config.hashed_subpackets.push(
        Subpacket::critical(SubpacketData::SignatureCreationTime(replacement_time))
            .map_err(|_| ExpirationUpdateFailure::InternalFailure)?,
    );
    if let Some(duration) = replacement_signature_expiration(template, replacement_time) {
        config.hashed_subpackets.push(
            Subpacket::critical(SubpacketData::SignatureExpirationTime(Duration::from_secs(
                duration,
            )))
            .map_err(|_| ExpirationUpdateFailure::InternalFailure)?,
        );
    }
    Ok(())
}

fn replacement_signature_expiration(
    template: &Signature,
    replacement_time: Timestamp,
) -> Option<u32> {
    let duration = signature_expiration_seconds(template)?;
    // The template's absolute expiration can exceed the u32 epoch range;
    // widen before adding, or a wrapped sum would silently turn a bounded
    // statement into a permanent or one-second one.  A remaining duration
    // past the representable range saturates to the farthest bounded value.
    let expiration = u64::from(signature_creation_time(template)?) + u64::from(duration);
    let remaining = expiration.saturating_sub(u64::from(replacement_time.as_secs()));
    Some(u32::try_from(remaining.max(1)).unwrap_or(u32::MAX))
}

fn replacement_hash(
    signing_algorithm: PublicKeyAlgorithm,
    signer_hash: HashAlgorithm,
    template: HashAlgorithm,
) -> Result<HashAlgorithm, ExpirationUpdateFailure> {
    select_signature_hash(signing_algorithm, signer_hash, template)
        .ok_or(ExpirationUpdateFailure::UnsupportedSigningHash)
}

/// Post-condition of one renewal, evaluated against the single post-mutation
/// policy view.
///
/// Every selected component must now be authenticated, unrevoked, and carry
/// exactly the requested expiration; the exact designated-revoker set must be
/// unchanged; and a certificate that had a primary User ID before must still
/// have the same one.
fn validate_renewed_certificate(
    policy: &ValidatedCertificate<'_>,
    selected: &[String],
    expires_at: Option<u64>,
    previous_primary_user_id: Option<&[u8]>,
    previous_revocation_authorities: &[DesignatedRevokerId],
) -> Result<(), ExpirationUpdateFailure> {
    validate_revocation_authority_unchanged(policy, previous_revocation_authorities)?;
    let certificate = policy.certificate();
    let primary_fingerprint = fingerprint_hex(&certificate.primary_key);
    for fingerprint in selected {
        let (created, effective) = if fingerprint == &primary_fingerprint {
            if policy.primary.policy_conflict {
                return Err(ExpirationUpdateFailure::TimeConflict);
            }
            policy.primary_component().authorize_mutation()?;
            (
                certificate.primary_key.created_at(),
                policy.primary.effective_signature,
            )
        } else {
            let subkey = certificate
                .public_subkeys
                .iter()
                .find(|subkey| fingerprint_hex(&subkey.key) == *fingerprint)
                .ok_or(ExpirationUpdateFailure::SignatureVerificationFailed)?;
            let component = policy
                .subkey(&subkey.key)
                .ok_or(ExpirationUpdateFailure::SignatureVerificationFailed)?;
            if component.policy().policy_conflict {
                return Err(ExpirationUpdateFailure::TimeConflict);
            }
            component.authorize_mutation()?;
            let component = component.policy();
            (component.key.created_at(), component.effective_signature)
        };
        let actual = effective
            .and_then(Signature::key_expiration_time)
            .map_or(0, |duration| u64::from(duration.as_secs()));
        let expected = expires_at
            .map(|expiration| expiration.saturating_sub(u64::from(created.as_secs())))
            .unwrap_or(0);
        if actual != expected {
            return Err(ExpirationUpdateFailure::SignatureVerificationFailed);
        }
    }
    if let Some(previous) = previous_primary_user_id
        && policy
            .primary_user_id()
            .map(|user_id| user_id.packet_body())
            != Some(previous)
    {
        return Err(ExpirationUpdateFailure::SignatureVerificationFailed);
    }
    Ok(())
}

/// Ensures an expiration-only mutation neither grants nor removes revocation
/// authority, including the sensitive bit on a legacy declaration.
fn validate_revocation_authority_unchanged(
    policy: &ValidatedCertificate<'_>,
    previous: &[DesignatedRevokerId],
) -> Result<(), ExpirationUpdateFailure> {
    let current = policy.legacy_designated_revokers().collect::<Vec<_>>();
    if current.len() == previous.len()
        && previous.iter().all(|expected| current.contains(&expected))
    {
        Ok(())
    } else {
        Err(ExpirationUpdateFailure::SignatureVerificationFailed)
    }
}

fn secret_subkeys_by_fingerprint(
    preflight: &MutationPreflight,
) -> Result<std::collections::BTreeMap<String, &SecretSubkey>, ExpirationUpdateFailure> {
    let mut result = std::collections::BTreeMap::new();
    for subkey in &preflight.secret.secret_subkeys {
        if result
            .insert(fingerprint_hex(&subkey.key), &subkey.key)
            .is_some()
        {
            return Err(ExpirationUpdateFailure::FingerprintMismatch);
        }
    }
    Ok(result)
}

#[cfg(test)]
mod tests;
