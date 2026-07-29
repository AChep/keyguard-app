package com.artemchep.keyguard.common.service.credentialexchange

typealias CxfExportSkips = CxfSkips<CxfExportSkipReason>

/**
 * Builds an export tally; zero and negative pairs are dropped.
 */
fun cxfExportSkips(vararg entries: Pair<CxfExportSkipReason, Int>): CxfExportSkips =
    CxfSkips.of(CxfExportSkipReason::class, entries)

/**
 * Everything in the vault that could not be turned into CXF data. The mirror
 * image of [CxfImportSkipReason] and under the same contract — see its KDoc;
 * the difference between the two reason sets is pinned by
 * `CxfSkipReasonRegistryTest`.
 *
 * **Declaration order is user-visible**: it is the order the review screen
 * renders the warning rows in.
 */
enum class CxfExportSkipReason {
    /**
     * A passkey that could not be exported: a field could not be encoded (an
     * empty or undecodable credential id or private key, an absent, empty or
     * undecodable user handle, or a blank relying-party id), or the passkey
     * uses a non-zero signature counter, which CXF v1.0 §3.3.12 requires to be
     * excluded. Surfacing this is what implements the same section's "SHOULD
     * inform the user" clause.
     *
     * Unlike the import side, an empty user handle is a skip here: the CXF
     * member is required, so a passkey the vault holds without one has no valid
     * encoding and must not be emitted with a fabricated value.
     */
    Passkey,

    /**
     * A one-time-password credential that is not representable as a CXF TOTP:
     * HOTP, mOTP, a period outside `1..Int.MAX_VALUE`, a digit count outside
     * `1..9`, or a secret that is not base32. Steam is deliberately absent —
     * it is emitted as the `steam` extension value rather than skipped.
     */
    Otp,

    /**
     * An SSH key that could not be converted into the PKCS#8 DER the format
     * requires — an encrypted or otherwise unconvertible private key, a missing
     * public key type, or an unavailable native crypto backend.
     */
    SshKey,

    /**
     * A GPG key the item holds. CXF v1.0 §3.3 defines no OpenPGP credential —
     * the ssh-key credential carries a PKCS#8 private key of an SSH key type,
     * not an armored PGP block — so the key has no encoding at all.
     *
     * Counted once per item that holds an armored key block, private or public.
     * A gpg member holding neither, i.e. only a fingerprint or agent metadata
     * for a key kept outside the vault, has no key material for the wire to
     * have lost and stays silent.
     */
    GpgKey,

    /**
     * A file attached to the item. Keyguard's exporter emits no `file`
     * credential: an attachment's bytes live outside the vault record, behind a
     * download and a per-attachment key, where the pure, synchronous mapper
     * cannot reach them.
     *
     * Counted per attachment rather than per item, because each file is a loss
     * of its own.
     */
    Attachment,

    /**
     * A previous password the item retained. CXF v1.0 §3.3 has no member for a
     * password an item no longer uses, and a basic-auth of its own would arrive
     * in the receiving app as a credential to sign in with — the same "dropped,
     * never downgraded" rule [CxfImportSkipReason] states, applied outwards.
     *
     * Counted per retained password rather than per item.
     */
    PasswordHistory,

    /**
     * An archived item, withheld on purpose.
     *
     * The only reason here that is a *policy* choice rather than a
     * representability failure: the item would encode fine, but archived means
     * "kept, not in use" — Keyguard already withholds these from autofill — and
     * the format has no archive member, so an exported one would arrive active
     * in the receiving app and be offered for sign-in again.
     *
     * Counted only when the item would otherwise have been transferred, i.e. it
     * yielded at least one credential under the requested types. An archived
     * item that the requester's filter would have emptied anyway, or that holds
     * nothing this format supports, is no loss to report and stays silent — the
     * same rule [Item] follows. Its credential- and member-level skips — every
     * reason above — are dropped with it rather than reported against an item
     * that is not going anywhere.
     */
    Archived,

    /**
     * A vault item that yielded nothing this format can carry, and whose loss no
     * reason above already explains — an item whose every payload is empty, say.
     *
     * Representability is decided against
     * [CxfCredentialType.ALL][com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType.ALL],
     * so an item emptied purely by the requester's credential-type filter is
     * not counted here, and neither is a trashed item nor one whose only content
     * a reason above already counted: an item holding only a GPG key, only
     * attachments or only retained passwords is reported by that reason instead.
     */
    Item,

    /**
     * A whole account that could not be mapped: everything in it is lost, and
     * the count is one for unknowably many items.
     *
     * Raised only at the [CxfExportService][com.artemchep.keyguard.common.service.credentialexchange.CxfExportService]
     * boundary, never by the mapper: `CxfSecretMapper` reads a non-zero total on
     * the per-item tally as "something of this item was already counted", so an
     * account-level reason leaking into that tally would suppress a real item
     * count.
     */
    Account,
}
