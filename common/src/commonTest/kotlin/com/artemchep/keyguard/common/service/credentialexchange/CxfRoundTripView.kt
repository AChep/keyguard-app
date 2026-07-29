package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.model.create.CreateRequest
import kotlin.test.assertTrue

/**
 * The shape a vault item and the create requests it round-trips into are both
 * projected onto, so a single `assertEquals` can compare them.
 *
 * Leaves reuse the production types ([DSecret.Uri], [DSecret.Field],
 * [DSecret.Login.Fido2Credentials], [CreateRequest.Card], …) so the import side
 * of the projection is a member *read* rather than a rebuild, and the export side
 * reads as a `copy(...)` of erasures.
 *
 * Members that differ between the requests of one item are keyed by
 * [DSecret.Type]. Within one item the produced types are pairwise distinct —
 * the importer builds at most one login, card, identity and ssh key, and a
 * secure note only when nothing else was produced — so the key is total.
 */
@Suppress("LongParameterList")
data class CxfRoundTripView(
    /**
     * The produced request types, in production order. Empty means the item
     * vanished entirely — trashed, or nothing in it maps to this format.
     */
    val types: List<DSecret.Type> = emptyList(),
    /** Per type, because a card's title falls back to the cardholder name. */
    val titles: Map<DSecret.Type, String?> = emptyMap(),
    /** Per type, because a login's uris may be fabricated from a passkey. */
    val uris: Map<DSecret.Type, List<DSecret.Uri>> = emptyMap(),
    /** Per type: identity overflow lands on the identity, leftovers on the first. */
    val fields: Map<DSecret.Type, List<DSecret.Field>> = emptyMap(),
    val note: String? = null,
    val favorite: Boolean = false,
    val tags: List<String> = emptyList(),
    val folderTitle: String? = null,
    val login: CxfRoundTripLoginView? = null,
    val passkeys: List<DSecret.Login.Fido2Credentials> = emptyList(),
    val card: CreateRequest.Card? = null,
    val identity: CreateRequest.Identity? = null,
    val sshKey: CreateRequest.SshKey? = null,
)

/**
 * The login facet. [CreateRequest.Login] is deliberately not reused: it carries
 * the otpauth uri as a raw string and the round trip rebuilds that string, so
 * only the *configuration* survives and only the configuration is compared.
 */
data class CxfRoundTripLoginView(
    val username: String? = null,
    val password: String? = null,
    val totp: CxfRoundTripTotpView? = null,
)

data class CxfRoundTripTotpView(
    val secretBase32: String,
    val digits: Int,
    val period: Long,
    val algorithm: CryptoHashAlgorithm,
    val issuer: String? = null,
    val username: String? = null,
    /**
     * A Steam token is its own uri scheme with a fixed configuration, so it is
     * a distinct shape rather than a TotpAuth with unusual members.
     */
    val steam: Boolean = false,
)

/**
 * Projects the create requests one source item round-tripped into.
 *
 * Purely mechanical: every member is a read, a concatenation, or a
 * [TotpToken.parse] of the artefact the importer produced. Everything the format
 * loses is applied on the other side, by `CxfRoundTripNormalizer`.
 *
 * The assertions are preconditions of the projection: they make "the note is
 * copied onto every produced request" and "the produced types are pairwise
 * distinct" invariants of the whole suite rather than a per-case check.
 */
internal fun List<CreateRequest>.toCxfRoundTripView(
    folderTitle: String? = null,
): CxfRoundTripView {
    val types = map { it.type ?: DSecret.Type.None }
    assertTrue(
        types.size == types.distinct().size,
        "the requests of one item must have pairwise distinct types, got $types",
    )
    assertUniform("note") { it.note }
    assertUniform("favorite") { it.favorite }
    assertUniform("tags") { it.tags.toList() }

    val first = firstOrNull()
    return CxfRoundTripView(
        types = types,
        titles = associate { requestType(it) to it.title },
        uris = associate { requestType(it) to it.uris.toList() },
        fields = associate { requestType(it) to it.fields.toList() },
        note = first?.note,
        favorite = first?.favorite == true,
        tags = first?.tags?.toList().orEmpty(),
        folderTitle = folderTitle,
        login = firstOrNull { it.type == DSecret.Type.Login }?.toLoginView(),
        passkeys = flatMap { it.fido2Credentials },
        card = firstOrNull { it.type == DSecret.Type.Card }?.card,
        identity = firstOrNull { it.type == DSecret.Type.Identity }?.identity,
        sshKey = firstOrNull { it.type == DSecret.Type.SshKey }?.sshKey,
    )
}

private fun requestType(request: CreateRequest): DSecret.Type =
    request.type ?: DSecret.Type.None

private fun <T> List<CreateRequest>.assertUniform(
    member: String,
    selector: (CreateRequest) -> T,
) {
    val values = map(selector).distinct()
    assertTrue(
        values.size <= 1,
        "every request of one item must carry the same $member, got $values",
    )
}

/**
 * Reads the login facet back. The otpauth uri is *parsed*, not re-derived, so
 * this stays a read of the produced artefact.
 */
private fun CreateRequest.toLoginView(): CxfRoundTripLoginView = CxfRoundTripLoginView(
    username = login.username,
    password = login.password,
    totp = login.totp?.let { uri ->
        when (val token = TotpToken.parse(uri).getOrNull()) {
            is TotpToken.TotpAuth -> CxfRoundTripTotpView(
                secretBase32 = token.keyBase32,
                digits = token.digits,
                period = token.period,
                algorithm = token.algorithm,
                issuer = token.issuer,
                username = token.username,
            )

            // Without this arm a steam round trip would read as a *loss*
            // rather than failing loudly.
            is TotpToken.SteamAuth -> CxfRoundTripTotpView(
                secretBase32 = token.keyBase32,
                digits = token.digits,
                period = token.period,
                algorithm = token.algorithm,
                steam = true,
            )

            else -> null
        }
    },
)
