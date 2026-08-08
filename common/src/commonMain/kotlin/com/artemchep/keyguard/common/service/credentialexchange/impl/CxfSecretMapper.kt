package com.artemchep.keyguard.common.service.credentialexchange.impl

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.model.displayName
import com.artemchep.keyguard.common.service.credentialexchange.CxfAccountResult
import com.artemchep.keyguard.common.service.credentialexchange.CxfExportSkipReason
import com.artemchep.keyguard.common.service.credentialexchange.CxfExportSkips
import com.artemchep.keyguard.common.service.credentialexchange.cxfExportSkips
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfAccount
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredential
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfItem
import com.artemchep.keyguard.common.service.crypto.PasskeyCrypto
import com.artemchep.keyguard.common.service.crypto.SshKeyPkcs8Exporter
import com.artemchep.keyguard.common.service.webauthn.PasskeyBase64
import com.artemchep.keyguard.crypto.NativePasskeyCrypto
import com.artemchep.keyguard.nativecrypto.NativeCryptoSsh

/**
 * Pure, side-effect-free mapping from Keyguard vault models to CXF models.
 *
 * This class orchestrates the per-item and per-account mapping; the
 * purely-functional per-credential mappers live as top-level functions in the
 * sibling `Cxf*Mappers.kt` files. Together they form the unit-test surface of
 * the export feature.
 */
internal class CxfSecretMapper(
    private val passkeyCrypto: PasskeyCrypto,
    private val sshKeyPkcs8Exporter: SshKeyPkcs8Exporter,
) : CxfAccountMapper {
    constructor(
        sshKeyPkcs8Exporter: SshKeyPkcs8Exporter,
    ) : this(
        passkeyCrypto = NativePasskeyCrypto,
        sshKeyPkcs8Exporter = sshKeyPkcs8Exporter,
    )

    /**
     * Builds a single CXF account from a [profile], its [ciphers] and its
     * [folders], emitting only the credential kinds in [allowedTypes] — an
     * exact filter: an empty set exports nothing, an unfiltered export must be
     * requested with [CxfCredentialType.ALL] (see [isAllowed]). When nothing
     * is exportable the result's account is `null` but the skipped counts are
     * preserved — a vault whose only matching credentials all had to be skipped
     * must still surface the counts, not silently show an empty review (an
     * exporter that excludes a passkey "SHOULD inform the user", CXF v1.0
     * §3.3.12).
     */
    override fun buildAccountResult(
        profile: DProfile,
        ciphers: List<DSecret>,
        allowedTypes: Set<CxfCredentialType>,
        folders: List<DFolder>,
    ): CxfAccountResult {
        val items = mutableListOf<CxfItem>()
        val itemsByFolderId = mutableMapOf<String, MutableList<String>>()
        var skips = cxfExportSkips()
        ciphers.forEach { cipher ->
            val result = buildItem(
                secret = cipher,
                allowedTypes = allowedTypes,
            )
            // Every reason in this sub-tally provably belongs to this one cipher,
            // so the whole thing is attributed here rather than at each of the
            // eight sites that raised it.
            skips += result.skips.titled(cipher.name)
            val item = result.item
                ?: return@forEach
            items += item
            cipher.folderId?.let { folderId ->
                itemsByFolderId.getOrPut(folderId) { mutableListOf() } += item.id
            }
        }
        val account = if (items.isEmpty()) {
            null
        } else {
            val accountFolders = folders.filter { folder ->
                folder.accountId == profile.accountId && !folder.deleted
            }
            CxfAccount(
                id = encodeIdToB64Url(profile.accountId),
                username = profile.displayName,
                email = profile.email,
                fullName = profile.name.takeIf { it.isNotBlank() },
                collections = buildCollections(
                    folders = accountFolders,
                    itemsByFolderId = itemsByFolderId,
                ),
                items = items,
            )
        }
        return CxfAccountResult(
            account = account,
            skips = skips,
        )
    }

    /**
     * Maps a single vault item. Every item that is neither trashed nor archived
     * and yields at least one credential (after the [allowedTypes] filter) is
     * exported. The returned [CxfItemResult.item] is `null` when the item is
     * trashed, archived, or empty.
     */
    fun buildItem(
        secret: DSecret,
        allowedTypes: Set<CxfCredentialType>,
    ): CxfItemResult {
        if (secret.deletedDate != null) {
            return CxfItemResult(item = null)
        }
        val collected = collectCredentials(
            secret = secret,
            allowedTypes = allowedTypes,
        )
        return if (secret.archived) {
            archivedResult(collected)
        } else {
            exportedResult(secret, allowedTypes, collected)
        }
    }

    /**
     * The result for an item that is eligible to travel: the mapped item when
     * anything survived the [allowedTypes] filter, plus whatever its credentials
     * had to skip.
     */
    private fun exportedResult(
        secret: DSecret,
        allowedTypes: Set<CxfCredentialType>,
        collected: CollectedCredentials,
    ): CxfItemResult {
        val item = collected.credentials
            .takeIf { it.isNotEmpty() }
            ?.let { credentials ->
                CxfItem(
                    id = encodeIdToB64Url(secret.id),
                    creationAt = secret.createdDate?.epochSeconds,
                    modifiedAt = secret.revisionDate.epochSeconds,
                    title = secret.name,
                    favorite = secret.favorite,
                    scope = mapScope(secret.uris),
                    credentials = credentials,
                    tags = secret.tags.takeIf { it.isNotEmpty() },
                )
            }
        // An item disappears either because something it held was already
        // counted as a skip above, or because nothing in it maps to this format
        // at all. Only the second case still needs a count of its own.
        val skips = if (isUnrepresentable(secret, allowedTypes, item, collected)) {
            collected.skips + CxfExportSkipReason.Item
        } else {
            collected.skips
        }
        return CxfItemResult(item = item, skips = skips)
    }

    /**
     * The result for an archived item: never exported, and counted only when
     * something was actually withheld — see [CxfExportSkipReason.Archived].
     *
     * [collected]'s own skips are deliberately discarded. They describe the
     * credentials and members of an item that is not being transferred either
     * way, so reporting them would blame the format for a loss the archive rule
     * already caused.
     */
    private fun archivedResult(
        collected: CollectedCredentials,
    ): CxfItemResult {
        val skips = if (collected.credentials.isNotEmpty()) {
            cxfExportSkips(CxfExportSkipReason.Archived to 1)
        } else {
            cxfExportSkips()
        }
        return CxfItemResult(item = null, skips = skips)
    }

    /**
     * Whether the item vanished because this format cannot carry any of its
     * content — the only thing [CxfExportSkipReason.Item] claims.
     *
     * An item emptied purely by the requester's [allowedTypes] filter is not
     * that: the format does represent it, the importer simply did not ask for
     * it, and the credential level stays silent for the same reason.
     */
    private fun isUnrepresentable(
        secret: DSecret,
        allowedTypes: Set<CxfCredentialType>,
        item: CxfItem?,
        collected: CollectedCredentials,
    ): Boolean {
        val unexplained = item == null && collected.skips.totalCount == 0
        return unexplained && !isRepresentableUnfiltered(secret, allowedTypes)
    }

    /**
     * Whether the item would have yielded something — a credential, or a skip
     * counted against one of its credentials — had the requester asked for every
     * kind. Only consulted for an item that already came back empty *and* raised
     * no skip of its own, so the filter-independent member reasons cannot be
     * what it finds.
     */
    private fun isRepresentableUnfiltered(
        secret: DSecret,
        allowedTypes: Set<CxfCredentialType>,
    ): Boolean {
        if (allowedTypes.containsAll(CxfCredentialType.ALL)) {
            return false
        }
        val unfiltered = collectCredentials(
            secret = secret,
            allowedTypes = CxfCredentialType.ALL,
        )
        return unfiltered.credentials.isNotEmpty() || unfiltered.skips.totalCount > 0
    }

    private fun collectCredentials(
        secret: DSecret,
        allowedTypes: Set<CxfCredentialType>,
    ): CollectedCredentials {
        val credentials = mutableListOf<CxfCredential>()
        var skips = collectLoginCredentials(secret.login, allowedTypes, credentials)

        if (secret.card != null && isAllowed(allowedTypes, CxfCredentialType.CreditCard)) {
            mapCreditCard(secret.card)?.let(credentials::add)
        }
        secret.identity?.let { identity ->
            credentials += mapIdentityCredentials(identity, allowedTypes)
        }
        if (isAllowed(allowedTypes, CxfCredentialType.Note)) {
            mapNote(secret.notes)?.let(credentials::add)
        }
        if (isAllowed(allowedTypes, CxfCredentialType.CustomFields)) {
            mapCustomFields(secret.fields)?.let(credentials::add)
        }
        val sshKey = secret.sshKey
        if (sshKey != null && isAllowed(allowedTypes, CxfCredentialType.SshKey)) {
            val credential = mapSshKey(sshKey)
            if (credential != null) {
                credentials += credential
            } else {
                skips += CxfExportSkipReason.SshKey
            }
        }
        return CollectedCredentials(
            credentials = credentials,
            skips = skips + unrepresentableMemberSkips(secret),
        )
    }

    private fun collectLoginCredentials(
        login: DSecret.Login?,
        allowedTypes: Set<CxfCredentialType>,
        into: MutableList<CxfCredential>,
    ): CxfExportSkips {
        if (login == null) {
            return cxfExportSkips()
        }
        var skips = cxfExportSkips()
        if (isAllowed(allowedTypes, CxfCredentialType.Passkey)) {
            login.fido2Credentials.forEach { credential ->
                val passkey = mapPasskey(credential)
                if (passkey != null) {
                    into += passkey
                } else {
                    skips += CxfExportSkipReason.Passkey
                }
            }
        }
        if (isAllowed(allowedTypes, CxfCredentialType.BasicAuth)) {
            mapBasicAuth(login)?.let(into::add)
        }
        if (isAllowed(allowedTypes, CxfCredentialType.Totp)) {
            val token = login.totp?.token
            if (token != null) {
                val totp = mapTotp(token, fallbackUsername = login.username)
                if (totp != null) {
                    into += totp
                } else {
                    skips += CxfExportSkipReason.Otp
                }
            }
        }
        return skips
    }

    /**
     * Maps a single FIDO2 credential to a passkey, or returns `null` (a counted
     * skip) when the credential id, key, rp id or user handle is missing, blank
     * or undecodable, or when the passkey uses a non-zero signature counter.
     */
    fun mapPasskey(
        credential: DSecret.Login.Fido2Credentials,
    ): CxfCredential.Passkey? {
        // CXF v1.0 §3.3.12: "Passkeys using a non-zero signature counter MUST
        // be excluded from the export and the exporter SHOULD inform the user
        // that such passkeys are excluded from the export." The review screen's
        // skipped count is that notice. Keyguard itself never increments
        // counters (see PasskeyProviderGetRequest), but imported or synced
        // credentials can carry a non-zero legacy value.
        val counter = credential.counter ?: 0
        if (counter != 0) {
            return null
        }
        return mapDecodedPasskey(credential)
    }

    private fun mapDecodedPasskey(
        credential: DSecret.Login.Fido2Credentials,
    ): CxfCredential.Passkey? {
        val decoded = decodePasskeyMembers(credential, passkeyCrypto)
            ?: return null
        return CxfCredential.Passkey(
            credentialId = decoded.credentialId,
            rpId = decoded.rpId,
            username = pickNonBlank(credential.userName, credential.userDisplayName),
            userDisplayName = pickNonBlank(credential.userDisplayName, credential.userName),
            userHandle = decoded.userHandle,
            key = decoded.key,
        )
    }

    /**
     * Maps a Keyguard SSH key into an ssh-key credential, or returns `null`
     * (a counted skip) when either half of the pair is missing or the pair
     * cannot be validated and converted to PKCS#8 DER.
     */
    fun mapSshKey(
        sshKey: DSecret.SshKey,
    ): CxfCredential.SshKey? {
        val publicKey = sshKey.publicKey?.takeIf { it.isNotBlank() }
        val privateKey = sshKey.privateKey?.takeIf { it.isNotBlank() }
        val export = if (privateKey != null && publicKey != null) {
            // SshKeyPkcs8Exporter is an injected interface, not a promise:
            // the production adapter folds its own failures into null, but a
            // throwing implementation must cost one counted skip, not the
            // whole export.
            runCatchingNonFatal {
                sshKeyPkcs8Exporter.exportPkcs8(
                    privateKeyPem = privateKey,
                    publicKeyOpenSsh = publicKey,
                )
            }.getOrNull()
        } else {
            null
        }
        return if (export == null) {
            null
        } else {
            try {
                CxfCredential.SshKey(
                    keyType = export.type.toCxfSshKeyType(),
                    privateKey = PasskeyBase64.encodeToString(export.der),
                )
            } finally {
                export.der.fill(0)
            }
        }
    }

    /**
     * The result of mapping one vault item: the item (or `null` when dropped)
     * together with the counts of everything it had to leave behind.
     */
    data class CxfItemResult(
        val item: CxfItem?,
        val skips: CxfExportSkips = cxfExportSkips(),
    )

    private data class CollectedCredentials(
        val credentials: List<CxfCredential>,
        /**
         * Credential- and member-level reasons only —
         * [CxfExportSkipReason.Passkey], [CxfExportSkipReason.Otp],
         * [CxfExportSkipReason.SshKey], [CxfExportSkipReason.GpgKey],
         * [CxfExportSkipReason.Attachment] and
         * [CxfExportSkipReason.PasswordHistory]. Never
         * [CxfExportSkipReason.Item], and never a whole-account or
         * whole-collection reason: [isUnrepresentable] reads a non-zero
         * `totalCount` here as "something of this item was already counted",
         * so a reason raised at any other granularity leaking into this tally
         * would silently suppress a real item count.
         */
        val skips: CxfExportSkips,
    )
}

/**
 * The item members CXF has nowhere to put at all — see
 * [CxfExportSkipReason.GpgKey], [CxfExportSkipReason.Attachment] and
 * [CxfExportSkipReason.PasswordHistory]. Counting them is what keeps a GPG-key
 * item that also holds a note from travelling with its key silently stripped:
 * the item is exported, so no item-level reason fires, and only these say what
 * stayed behind.
 *
 * Deliberately not gated by `allowedTypes`: no credential type maps to any of
 * these members, so no requested-type filter can be read as "the importer did
 * not ask for this" — they are lost whatever was requested. A trashed item never
 * reaches here, and an archived item's tally is discarded whole, so neither
 * counts.
 */
private fun unrepresentableMemberSkips(
    secret: DSecret,
): CxfExportSkips = cxfExportSkips()
    .plus(CxfExportSkipReason.GpgKey, count = gpgKeyLossCount(secret.gpgKey))
    .plus(CxfExportSkipReason.Attachment, count = secret.attachments.size)
    .plus(CxfExportSkipReason.PasswordHistory, count = secret.passwordHistory.size)

/**
 * `1` when the item holds an armored OpenPGP key block, `0` otherwise — the gpg
 * member is not itself the loss, the key material in it is. One holding neither
 * block has nothing the wire could have carried: a bare fingerprint or agent
 * metadata describes a key kept outside the vault, and an all-empty gpg member
 * is reachable too (`CipherMerge` substitutes one).
 */
private fun gpgKeyLossCount(
    gpgKey: DSecret.GpgKey?,
): Int {
    val holdsKeyMaterial = !gpgKey?.privateKeyArmored.isNullOrBlank() ||
        !gpgKey?.publicKeyArmored.isNullOrBlank()
    return if (holdsKeyMaterial) 1 else 0
}

private fun KeyPair.Type.toCxfSshKeyType(): String = when (this) {
    KeyPair.Type.RSA -> NativeCryptoSsh.ALGORITHM_SSH_RSA
    KeyPair.Type.ED25519 -> NativeCryptoSsh.ALGORITHM_SSH_ED25519
}

internal fun encodeIdToB64Url(
    id: String,
): String = PasskeyBase64.encodeToString(id.encodeToByteArray())

/**
 * A credential kind may be exported only when the importer explicitly asked
 * for it. There is deliberately no "empty set means everything" sentinel: per
 * CXP §3.2 an exporter MUST ignore unknown requested type values, so a request
 * whose types are all unrecognized filters down to an empty set — for which the
 * same section has the exporter send only the account, never the whole vault.
 * Callers that want an unfiltered export pass [CxfCredentialType.ALL].
 */
internal fun isAllowed(
    allowedTypes: Set<CxfCredentialType>,
    type: CxfCredentialType,
): Boolean = type in allowedTypes

private fun pickNonBlank(
    primary: String?,
    fallback: String?,
): String = primary?.takeIf { it.isNotBlank() }
    ?: fallback?.takeIf { it.isNotBlank() }
    ?: ""

/**
 * The four passkey members that have to decode for an assertion to be possible.
 * Any one of them can independently refuse the credential.
 */
private class DecodedPasskeyMembers(
    val credentialId: String,
    val key: String,
    val rpId: String,
    val userHandle: String,
)

private fun decodePasskeyMembers(
    credential: DSecret.Login.Fido2Credentials,
    passkeyCrypto: PasskeyCrypto,
): DecodedPasskeyMembers? = credential
    .takeIf(::isExportablePasskeyProfile)
    ?.let { supported -> decodeSupportedPasskeyMembers(supported, passkeyCrypto) }

/**
 * CXF v1.0 §3.3.12 carries the private key as bare PKCS#8 with no algorithm
 * member beside it, so a reader has to assume the one profile the format
 * describes. Anything else the vault holds is unrepresentable and is refused
 * before the key is decoded — and before the native seam is touched at all.
 */
private fun isExportablePasskeyProfile(
    credential: DSecret.Login.Fido2Credentials,
): Boolean = credential.keyType == "public-key" &&
    credential.keyAlgorithm == "ECDSA" &&
    credential.keyCurve == "P-256"

private fun decodeSupportedPasskeyMembers(
    credential: DSecret.Login.Fido2Credentials,
    passkeyCrypto: PasskeyCrypto,
): DecodedPasskeyMembers? {
    val credentialId = mapCredentialId(credential.credentialId)
    val key = mapKey(credential.keyValue, passkeyCrypto)
    val rpId = mapRpId(credential.rpId)
    if (credentialId == null || key == null || rpId == null) {
        return null
    }
    // Keyguard can legitimately hold a credential with no user handle at all —
    // a non-discoverable credential synced from a server that models the field
    // as optional. CXF has no member for "absent", so the only honest answer
    // is a counted skip rather than a document we know is invalid.
    val userHandle = credential.userHandle?.let(::mapUserHandle)
    return userHandle?.let { handle ->
        DecodedPasskeyMembers(
            credentialId = credentialId,
            key = key,
            rpId = rpId,
            userHandle = handle,
        )
    }
}
