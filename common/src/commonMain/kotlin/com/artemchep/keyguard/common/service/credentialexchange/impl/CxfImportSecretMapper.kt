package com.artemchep.keyguard.common.service.credentialexchange.impl

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredential
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfItem
import com.artemchep.keyguard.common.service.imports.ImportSkipReason
import com.artemchep.keyguard.common.service.imports.impl.ImportPlanBuilder
import com.artemchep.keyguard.common.service.imports.mapping.ImportedItemMeta
import com.artemchep.keyguard.common.service.imports.mapping.importedKeyguardTypeOrNull
import com.artemchep.keyguard.common.service.imports.mapping.importedLogin
import com.artemchep.keyguard.common.service.imports.mapping.importedRequest
import com.artemchep.keyguard.common.service.imports.mapping.orNullIfBlank
import com.artemchep.keyguard.provider.bitwarden.mapper.toBitwardenField
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import com.artemchep.keyguard.util.webauthn.crypto.PasskeyCrypto
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService
import com.artemchep.keyguard.util.webauthn.crypto.NativePasskeyCrypto
import kotlinx.collections.immutable.toPersistentList
import kotlin.time.Instant

/**
 * Combines the credentials of a single CXF item into Keyguard create
 * requests. The counterpart of the export-side [CxfSecretMapper].
 *
 * Combination rules:
 * - basic-auth, ALL passkeys and totp merge into ONE login, produced only when
 *   at least one of username / password / totp / passkey survived mapping.
 * - The identity-shaped credentials re-merge into ONE identity per item, which
 *   is what restores round-trip fidelity: Keyguard's own exporter splits a
 *   single identity item across those kinds.
 * - The first note attaches to every produced request; a standalone note
 *   becomes a secure note only when nothing else was produced.
 * - Custom fields not consumed by the identity merge attach to the first
 *   produced request, or become a standalone secure note — or the Keyguard
 *   type that note carries, as Keyguard's own exporter writes an environment.
 * - Extra credentials of any other single-instance kind are counted skips.
 */
internal class CxfImportSecretMapper(
    private val passkeyCrypto: PasskeyCrypto,
    private val sshKeyImportService: SshKeyImportService,
) {
    constructor(
        sshKeyImportService: SshKeyImportService,
    ) : this(
        passkeyCrypto = NativePasskeyCrypto,
        sshKeyImportService = sshKeyImportService,
    )

    /**
     * Maps one CXF item into zero or more create requests (ownership left
     * `null` for the commit step) and counts its **credential-level** skips
     * into the [plan]. The item-level [ImportSkipReason.Item] decision belongs
     * to the caller, which also knows about credentials the decoder rejected
     * before the mapper saw them — see `CxfImportServiceImpl.parseItems`.
     */
    fun mapItem(
        item: CxfItem,
        now: Instant,
        plan: ImportPlanBuilder,
    ): List<CreateRequest> {
        val grouped = groupCxfCredentials(item.credentials)
        // All of these are losses inside this one item, so the whole tally
        // is attributed to it.
        plan.skip(ImportSkipReason.DuplicateCredential, item.title, count = grouped.duplicateCount)
        return ItemBuilder(
            item = item,
            grouped = grouped,
            now = now,
            plan = plan,
            passkeyCrypto = passkeyCrypto,
            sshKeyImportService = sshKeyImportService,
        ).build()
    }
}

/**
 * The per-kind buckets of one item's credentials. Single-instance kinds keep
 * the first occurrence and count the rest as duplicates; passkeys keep every
 * occurrence, and the custom-field bags concatenate — Keyguard's own exporter
 * legitimately emits two of them for an identity item with custom fields.
 */
internal data class CxfGroupedCredentials(
    val basicAuth: CxfCredential.BasicAuth? = null,
    val passkeys: List<CxfCredential.Passkey> = emptyList(),
    val totp: CxfCredential.Totp? = null,
    val creditCard: CxfCredential.CreditCard? = null,
    val identity: CxfIdentityCredentials = CxfIdentityCredentials(),
    val note: CxfCredential.Note? = null,
    val customFields: List<CxfCredential.CustomFields> = emptyList(),
    val sshKey: CxfCredential.SshKey? = null,
    val duplicateCount: Int = 0,
)

internal fun groupCxfCredentials(
    credentials: List<CxfCredential>,
): CxfGroupedCredentials {
    val grouper = CxfCredentialGrouper()
    credentials.forEach(grouper::add)
    return grouper.build()
}

private class CxfCredentialGrouper {
    private var grouped = CxfGroupedCredentials()
    private var duplicateCount = 0

    fun add(credential: CxfCredential) {
        val identity = groupIdentity(grouped.identity, credential)
        grouped = when {
            identity != null -> grouped.copy(identity = identity)

            credential is CxfCredential.Passkey ->
                grouped.copy(passkeys = grouped.passkeys + credential)

            credential is CxfCredential.BasicAuth ->
                grouped.copy(basicAuth = first(grouped.basicAuth, credential))

            credential is CxfCredential.Totp ->
                grouped.copy(totp = first(grouped.totp, credential))

            credential is CxfCredential.CreditCard ->
                grouped.copy(creditCard = first(grouped.creditCard, credential))

            credential is CxfCredential.Note ->
                grouped.copy(note = first(grouped.note, credential))

            credential is CxfCredential.CustomFields ->
                grouped.copy(customFields = grouped.customFields + credential)

            credential is CxfCredential.SshKey ->
                grouped.copy(sshKey = first(grouped.sshKey, credential))

            else -> grouped
        }
    }

    fun build(): CxfGroupedCredentials = grouped.copy(duplicateCount = duplicateCount)

    private fun groupIdentity(
        identity: CxfIdentityCredentials,
        credential: CxfCredential,
    ): CxfIdentityCredentials? = when (credential) {
        is CxfCredential.PersonName ->
            identity.copy(personName = first(identity.personName, credential))

        is CxfCredential.Address ->
            identity.copy(address = first(identity.address, credential))

        is CxfCredential.Passport ->
            identity.copy(passport = first(identity.passport, credential))

        is CxfCredential.DriversLicense ->
            identity.copy(driversLicense = first(identity.driversLicense, credential))

        is CxfCredential.IdentityDocument ->
            identity.copy(identityDocument = first(identity.identityDocument, credential))

        else -> null
    }

    /**
     * Keeps one occurrence of a single-instance kind and counts the other.
     *
     * Arrival order is only the tie-break: every member of `basic-auth`,
     * `credit-card` and the five identity-shaped kinds is optional, so
     * `{"type":"basic-auth"}` is a conforming credential that carries nothing
     * and a populated sibling has to win over it. Otherwise a password or a
     * card number would vanish under
     * [ImportSkipReason.DuplicateCredential], whose contract says nothing
     * of value was lost.
     */
    private fun <T : CxfCredential> first(current: T?, candidate: T): T {
        if (current == null) {
            return candidate
        }
        duplicateCount++
        val displaced = current.isContentFree && !candidate.isContentFree
        return if (displaced) candidate else current
    }
}

/**
 * Whether the credential is the all-defaults instance of its kind, i.e. every
 * member the format lets a producer omit was omitted. The import-side mirror of
 * the exporter's own `takeUnless { it == CxfCredential.PersonName() }`
 * convention. Kinds with a required member cannot decode content-free, so they
 * are never displaced.
 */
private val CxfCredential.isContentFree: Boolean
    get() = when (this) {
        is CxfCredential.BasicAuth -> this == CxfCredential.BasicAuth()
        is CxfCredential.CreditCard -> this == CxfCredential.CreditCard()
        is CxfCredential.PersonName -> this == CxfCredential.PersonName()
        is CxfCredential.Address -> this == CxfCredential.Address()
        is CxfCredential.Passport -> this == CxfCredential.Passport()
        is CxfCredential.DriversLicense -> this == CxfCredential.DriversLicense()
        is CxfCredential.IdentityDocument -> this == CxfCredential.IdentityDocument()
        else -> false
    }

/**
 * Builds the create requests for one item; the skips count into the [plan]
 * as the credential mappers run.
 */
private class ItemBuilder(
    private val item: CxfItem,
    private val grouped: CxfGroupedCredentials,
    private val now: Instant,
    private val plan: ImportPlanBuilder,
    private val passkeyCrypto: PasskeyCrypto,
    private val sshKeyImportService: SshKeyImportService,
) {
    private val uris = mapImportUris(item.scope)
    private val allCustomFields = grouped.customFields.flatMap { it.fields }
    private val meta = ImportedItemMeta(
        now = now,
        title = item.title,
        note = grouped.note?.content?.value,
        favorite = item.favorite ?: false,
        uris = uris,
        tags = item.tags.orEmpty(),
    )

    fun build(): List<CreateRequest> {
        val requests = mutableListOf<CreateRequest>()
        buildLogin()?.let(requests::add)
        buildCard()?.let(requests::add)
        val identity = buildIdentity()
        identity?.request?.let(requests::add)
        buildSshKey()?.let(requests::add)
        return attachLeftovers(
            requests = requests,
            remainingCustomFields = identity?.remainingCustomFields
                ?: mapImportCustomFields(allCustomFields),
        )
    }

    private fun skip(reason: ImportSkipReason) = plan.skip(reason, item.title)

    /**
     * A cheap bail-out taken before anything is mapped: an item without a single
     * login-shaped credential cannot produce a login. Whether one *survives*
     * mapping is [buildMappedLogin]'s decision.
     */
    private fun buildLogin(): CreateRequest? {
        val hasLoginShapedCredential = grouped.basicAuth != null ||
            grouped.passkeys.isNotEmpty() ||
            grouped.totp != null
        if (!hasLoginShapedCredential) {
            return null
        }
        return buildMappedLogin()
    }

    private fun buildMappedLogin(): CreateRequest? {
        val basicAuth = grouped.basicAuth
        val passkeyCreationDate = item.creationAt
            ?.let(Instant::fromEpochSeconds)
            ?: now
        val passkeys = grouped.passkeys.mapNotNull { passkey ->
            mapImportPasskey(
                passkey = passkey,
                creationDate = passkeyCreationDate,
                passkeyCrypto = passkeyCrypto,
            )
                .also { if (it == null) skip(ImportSkipReason.Passkey) }
        }
        val totpUri = grouped.totp?.let { totp ->
            mapImportTotpUri(totp)
                .also { if (it == null) skip(ImportSkipReason.Otp) }
        }
        // Both fallbacks read the MAPPED passkeys: a credential that was thrown
        // away must not donate the identity or the url the item is judged by.
        val login = importedLogin(
            username = basicAuth?.username?.value.orNullIfBlank()
                ?: passkeys.firstNotNullOfOrNull { it.userName },
            // A password of only whitespace is a password, and `importedLogin`
            // keeps it. The export counterpart `mapBasicAuth` uses the same
            // predicate, so a Keyguard round trip does not destroy one.
            password = basicAuth?.password?.value,
            totp = totpUri,
        )
        // A login that carries nothing authenticable is not a login: the item
        // falls through to `attachLeftovers` and, when nothing else survived and
        // no credential reason already explains the loss, is counted as a
        // skipped item — the same shape as `buildCard`'s empty card and
        // `buildSshKey`'s failed key.
        if (login == CreateRequest.Login() && passkeys.isEmpty()) {
            return null
        }
        val loginUris = uris.ifEmpty { mapPasskeyFallbackUris(passkeys) }
        return importedRequest(DSecret.Type.Login, meta).copy(
            login = login,
            fido2Credentials = passkeys.toPersistentList(),
            uris = loginUris.toPersistentList(),
        )
    }

    private fun buildCard(): CreateRequest? {
        val imported = grouped.creditCard
            ?.let(::mapImportCreditCard)
            ?.takeIf { !it.isEmpty }
            ?: return null
        return importedRequest(DSecret.Type.Card, meta, imported.fields).copy(
            title = item.title.orNullIfBlank()
                ?: imported.card.cardholderName,
            card = imported.card,
        )
    }

    private class BuiltIdentity(
        val request: CreateRequest?,
        val remainingCustomFields: List<DSecret.Field>,
    )

    private fun buildIdentity(): BuiltIdentity? {
        if (grouped.identity.isEmpty) {
            return null
        }
        val imported = mapImportIdentity(
            credentials = grouped.identity,
            customFields = allCustomFields,
        )
        // The post-mapping gate `buildCard` and `buildMappedLogin` have:
        // `grouped.identity.isEmpty` above only asks whether an identity-shaped
        // credential OBJECT was present, and every member of all five of those
        // kinds is optional, so `{"type":"person-name"}` alone must not
        // materialise a wholly blank Identity cipher that would also mask the
        // item from the `Item` counter. The leftover custom fields survive
        // either way: `attachLeftovers` still places them.
        val empty = imported.identity == CreateRequest.Identity() && imported.fields.isEmpty()
        val request = importedRequest(DSecret.Type.Identity, meta, imported.fields)
            .copy(identity = imported.identity)
            .takeIf { !empty }
        return BuiltIdentity(
            request = request,
            remainingCustomFields = mapImportCustomFields(imported.remainingCustomFields),
        )
    }

    private fun buildSshKey(): CreateRequest? {
        val imported = grouped.sshKey
            ?.let { credential ->
                mapImportSshKey(credential, sshKeyImportService)
                    .also { if (it == null) skip(ImportSkipReason.SshKey) }
            }
            ?: return null
        return importedRequest(DSecret.Type.SshKey, meta, imported.fields).copy(
            sshKey = imported.sshKey,
        )
    }

    /**
     * Applies the leftover custom fields onto the first produced request. When
     * the item produced nothing else, the note and the fields collapse into a
     * standalone secure note.
     */
    private fun attachLeftovers(
        requests: MutableList<CreateRequest>,
        remainingCustomFields: List<DSecret.Field>,
    ): List<CreateRequest> {
        if (requests.isEmpty()) {
            if (meta.note.orNullIfBlank() != null || remainingCustomFields.isNotEmpty()) {
                buildStandaloneNote(remainingCustomFields)?.let(requests::add)
            }
            return requests
        }
        if (remainingCustomFields.isNotEmpty()) {
            val first = requests.first()
            requests[0] = first.copy(
                fields = (first.fields + remainingCustomFields).toPersistentList(),
            )
        }
        return requests
    }

    /**
     * A secure note, or the Keyguard type its custom fields carry: Keyguard's
     * own exporter writes an environment as a note with custom fields. `null`
     * for an environment the vault would not write, which is counted.
     */
    private fun buildStandaloneNote(
        fields: List<DSecret.Field>,
    ): CreateRequest? {
        val keyguard = importedKeyguardTypeOrNull(
            type = BitwardenCipher.Type.SecureNote,
            fields = fields.map(DSecret.Field::toBitwardenField),
        )
        if (keyguard == null) {
            skip(ImportSkipReason.Environment)
            return null
        }
        plan.skip(ImportSkipReason.EnvironmentReference, item.title, count = keyguard.clearedReferenceCount)
        return importedRequest(
            type = keyguard.type,
            meta = meta,
            fields = keyguard.fields.map(BitwardenCipher.Field::toDomain),
        ).copy(
            gpgKey = keyguard.gpgKey,
            environment = keyguard.environment,
        )
    }
}
