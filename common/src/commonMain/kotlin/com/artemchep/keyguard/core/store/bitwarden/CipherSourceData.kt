package com.artemchep.keyguard.core.store.bitwarden

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * Provider that produced a set of [SourceBinding]s (e.g. KeePass). A value class
 * so call sites are type-checked; serializes transparently as the wrapped string.
 */
@JvmInline
@Serializable
value class SourceProviderId(val raw: String)

/** Semantic role a source field plays within its canonical projection (e.g. "token"). */
@JvmInline
@Serializable
value class SourceRole(val raw: String)

/** How a source field encodes its value (e.g. base32 secret, otpauth URI). */
@JvmInline
@Serializable
value class SourceRepresentation(val raw: String)

/** Identifies the foreign-client convention a binding reproduces (e.g. Strongbox card). */
@JvmInline
@Serializable
value class SourceProjectionId(val raw: String)

@Serializable
data class CipherSourceData(
    val providerId: SourceProviderId,
    val bindings: List<SourceBinding> = emptyList(),
)

@Serializable
data class SourceBinding(
    val canonicalFields: List<CanonicalFieldRef> = emptyList(),
    val sourceFields: List<SourceFieldRef> = emptyList(),
    val projection: SourceProjection = SourceProjection(),
    /** Disambiguates multiple bindings of the same projection on one entry. */
    val groupId: String? = null,
    val parameters: Map<String, String> = emptyMap(),
)

@Serializable
data class SourceFieldRef(
    val key: String,
    val role: SourceRole? = null,
    val representationId: SourceRepresentation? = null,
    /** Tri-state: true = concealed, false = plain, null = inherit the codec default. */
    val concealed: Boolean? = null,
    val order: Int? = null,
    val parameters: Map<String, String> = emptyMap(),
)

@Serializable
data class CanonicalFieldRef(
    val path: String,
    val selector: Map<String, String> = emptyMap(),
)

@Serializable
data class SourceProjection(
    val id: SourceProjectionId? = null,
    val parameters: Map<String, String> = emptyMap(),
)

object CipherSourceProviderIds {
    val KEEPASS = SourceProviderId("keepass")
}

object CipherSourceCanonicalPaths {
    const val LOGIN_TOTP = "login.totp"
    const val LOGIN_FIDO2_CREDENTIALS = "login.fido2Credentials"
    const val CARD_CARDHOLDER_NAME = "card.cardholderName"
    const val CARD_BRAND = "card.brand"
    const val CARD_NUMBER = "card.number"
    const val CARD_EXP_MONTH = "card.expMonth"
    const val CARD_EXP_YEAR = "card.expYear"
    const val CARD_CODE = "card.code"
    const val IDENTITY_TITLE = "identity.title"
    const val IDENTITY_FIRST_NAME = "identity.firstName"
    const val IDENTITY_MIDDLE_NAME = "identity.middleName"
    const val IDENTITY_LAST_NAME = "identity.lastName"
    const val IDENTITY_ADDRESS1 = "identity.address1"
    const val IDENTITY_ADDRESS2 = "identity.address2"
    const val IDENTITY_ADDRESS3 = "identity.address3"
    const val IDENTITY_CITY = "identity.city"
    const val IDENTITY_STATE = "identity.state"
    const val IDENTITY_POSTAL_CODE = "identity.postalCode"
    const val IDENTITY_COUNTRY = "identity.country"
    const val IDENTITY_COMPANY = "identity.company"
    const val IDENTITY_EMAIL = "identity.email"
    const val IDENTITY_PHONE = "identity.phone"
    const val IDENTITY_SSN = "identity.ssn"
    const val IDENTITY_USERNAME = "identity.username"
    const val IDENTITY_PASSPORT_NUMBER = "identity.passportNumber"
    const val IDENTITY_LICENSE_NUMBER = "identity.licenseNumber"

    val CARD_PATHS = setOf(
        CARD_CARDHOLDER_NAME,
        CARD_BRAND,
        CARD_NUMBER,
        CARD_EXP_MONTH,
        CARD_EXP_YEAR,
        CARD_CODE,
    )

    val IDENTITY_PATHS = setOf(
        IDENTITY_TITLE,
        IDENTITY_FIRST_NAME,
        IDENTITY_MIDDLE_NAME,
        IDENTITY_LAST_NAME,
        IDENTITY_ADDRESS1,
        IDENTITY_ADDRESS2,
        IDENTITY_ADDRESS3,
        IDENTITY_CITY,
        IDENTITY_STATE,
        IDENTITY_POSTAL_CODE,
        IDENTITY_COUNTRY,
        IDENTITY_COMPANY,
        IDENTITY_EMAIL,
        IDENTITY_PHONE,
        IDENTITY_SSN,
        IDENTITY_USERNAME,
        IDENTITY_PASSPORT_NUMBER,
        IDENTITY_LICENSE_NUMBER,
    )
}

object CipherSourceDataReconciler {
    fun onEdit(
        old: BitwardenCipher?,
        new: BitwardenCipher,
    ): CipherSourceData? {
        val sourceData = old?.sourceData ?: return null
        if (old.accountId != new.accountId) return null
        if (old.organizationId != new.organizationId) return null

        val sourceData2 = sourceData
            .let { data ->
                if (new.type == BitwardenCipher.Type.Login && !new.login?.totp.isNullOrEmpty()) {
                    data
                } else {
                    data.withoutCanonicalPath(CipherSourceCanonicalPaths.LOGIN_TOTP)
                }
            }
            .let { data ->
                if (new.type == BitwardenCipher.Type.Login && new.login?.fido2Credentials.orEmpty().isNotEmpty()) {
                    data
                } else {
                    data.withoutCanonicalPath(CipherSourceCanonicalPaths.LOGIN_FIDO2_CREDENTIALS)
                }
            }
            .let { data ->
                if (new.type == BitwardenCipher.Type.Card && new.card.hasAnyCardValue()) {
                    data
                } else {
                    data.withoutCardCanonicalPaths()
                }
            }
            .let { data ->
                val newIdentity = new.identity
                if (new.type == BitwardenCipher.Type.Identity && newIdentity != null) {
                    val oldIdentity = old.identity
                    CipherSourceCanonicalPaths.IDENTITY_PATHS.fold(data) { acc, path ->
                        if (oldIdentity.identityValueFor(path) == newIdentity.identityValueFor(path)) {
                            acc
                        } else {
                            acc.withoutCanonicalPath(path)
                        }
                    }
                } else {
                    data.withoutIdentityCanonicalPaths()
                }
            }
        return sourceData2.takeUnless { it.bindings.isEmpty() }
    }

    fun onCopy(
        source: BitwardenCipher,
        target: BitwardenCipher,
    ): CipherSourceData? {
        val sourceData = source.sourceData ?: return null
        if (source.accountId != target.accountId) return null
        if (source.organizationId != target.organizationId) return null
        return sourceData
    }
}

fun CipherSourceData.withoutCanonicalPath(path: String): CipherSourceData =
    copy(
        bindings = bindings.filterNot { binding ->
            binding.canonicalFields.any { it.path == path }
        },
    )

fun CipherSourceData.withoutCanonicalPaths(paths: Set<String>): CipherSourceData =
    copy(
        bindings = bindings.filterNot { binding ->
            binding.canonicalFields.any { it.path in paths }
        },
    )

fun CipherSourceData.withoutCardCanonicalPaths(): CipherSourceData =
    withoutCanonicalPaths(CipherSourceCanonicalPaths.CARD_PATHS)

fun CipherSourceData.withoutIdentityCanonicalPaths(): CipherSourceData =
    withoutCanonicalPaths(CipherSourceCanonicalPaths.IDENTITY_PATHS)

fun SourceBinding.targets(path: String): Boolean =
    canonicalFields.any { it.path == path }

private fun BitwardenCipher.Card?.hasAnyCardValue(): Boolean {
    if (this == null) return false
    return !cardholderName.isNullOrEmpty() ||
            !brand.isNullOrEmpty() ||
            !number.isNullOrEmpty() ||
            !expMonth.isNullOrEmpty() ||
            !expYear.isNullOrEmpty() ||
            !code.isNullOrEmpty()
}

private fun BitwardenCipher.Identity?.identityValueFor(path: String): String? = when (path) {
    CipherSourceCanonicalPaths.IDENTITY_TITLE -> this?.title
    CipherSourceCanonicalPaths.IDENTITY_FIRST_NAME -> this?.firstName
    CipherSourceCanonicalPaths.IDENTITY_MIDDLE_NAME -> this?.middleName
    CipherSourceCanonicalPaths.IDENTITY_LAST_NAME -> this?.lastName
    CipherSourceCanonicalPaths.IDENTITY_ADDRESS1 -> this?.address1
    CipherSourceCanonicalPaths.IDENTITY_ADDRESS2 -> this?.address2
    CipherSourceCanonicalPaths.IDENTITY_ADDRESS3 -> this?.address3
    CipherSourceCanonicalPaths.IDENTITY_CITY -> this?.city
    CipherSourceCanonicalPaths.IDENTITY_STATE -> this?.state
    CipherSourceCanonicalPaths.IDENTITY_POSTAL_CODE -> this?.postalCode
    CipherSourceCanonicalPaths.IDENTITY_COUNTRY -> this?.country
    CipherSourceCanonicalPaths.IDENTITY_COMPANY -> this?.company
    CipherSourceCanonicalPaths.IDENTITY_EMAIL -> this?.email
    CipherSourceCanonicalPaths.IDENTITY_PHONE -> this?.phone
    CipherSourceCanonicalPaths.IDENTITY_SSN -> this?.ssn
    CipherSourceCanonicalPaths.IDENTITY_USERNAME -> this?.username
    CipherSourceCanonicalPaths.IDENTITY_PASSPORT_NUMBER -> this?.passportNumber
    CipherSourceCanonicalPaths.IDENTITY_LICENSE_NUMBER -> this?.licenseNumber
    else -> null
}
