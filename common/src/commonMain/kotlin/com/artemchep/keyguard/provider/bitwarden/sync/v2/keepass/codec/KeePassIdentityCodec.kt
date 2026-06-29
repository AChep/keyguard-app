package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec

import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryValue
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.CipherSourceCanonicalPaths
import com.artemchep.keyguard.core.store.bitwarden.CipherSourceData
import com.artemchep.keyguard.core.store.bitwarden.SourceBinding
import com.artemchep.keyguard.core.store.bitwarden.SourceFieldRef
import com.artemchep.keyguard.core.store.bitwarden.SourceProjectionId
import com.artemchep.keyguard.core.store.bitwarden.SourceRepresentation
import com.artemchep.keyguard.core.store.bitwarden.SourceRole

/**
 * Parser contract for KeePass identity custom fields.
 *
 * Fields consumed or written:
 *
 * | Bitwarden slot       | KeePass field(s)                                      |
 * |----------------------|-------------------------------------------------------|
 * | username             | `UserName`.                                           |
 * | title                | `identity_title`.                                     |
 * | firstName            | `identity_name` alias, then `identity_firstName`.     |
 * | middleName           | `identity_middleName`.                                |
 * | lastName             | `identity_lastName`.                                  |
 * | address1             | `identity_address` alias, then `identity_address1`.   |
 * | address2/address3    | `identity_address2`, `identity_address3`.             |
 * | city/state/postal    | `identity_city`, `identity_state`,                    |
 * |                      | `identity_postalCode`.                                |
 * | country/company      | `identity_country`, `identity_company`.               |
 * | email/phone          | `identity_email`, `identity_phone`.                   |
 * | sensitive ids        | `identity_ssn`, `identity_passportNumber`,            |
 * |                      | `identity_licenseNumber`.                             |
 *
 * Decode tries aliases before canonical keys and consumes whichever key wins.
 * Any field whose key starts with `identity_` is enough for type detection.
 *
 * Encode writes `UserName` for username and, for other slots, uses the bound
 * source key if one exists; otherwise it writes the canonical key listed above.
 * SSN, passport number, and license number default to concealed, with bound
 * fields preserving their observed plain/concealed state.
 */
internal class KeePassIdentityCodec {
    fun encode(
        identity: BitwardenCipher.Identity,
        sourceData: CipherSourceData?,
    ): EncodedIdentity {
        val sourceBindings = sourceData.keepassBindingsFor(CipherSourceCanonicalPaths.IDENTITY_PATHS)
        val bindings = mutableListOf<SourceBinding>()
        val writes = buildList {
            addPlain(BasicField.UserName(), identity.username)
            IdentitySlot.entries.forEach { slot ->
                val value = slot.get(identity) ?: return@forEach
                val sourceField = sourceBindings.sourceFieldFor(slot.path)
                val key = sourceField?.key ?: slot.defaultKey
                val concealed = sourceField.isConcealedByDefault(default = slot.concealedDefault)
                add(keepassFieldWrite(key, value, concealed))
                bindings += encodedBinding(
                    slot = slot,
                    sourceField = sourceField,
                    key = key,
                    concealed = concealed,
                )
            }
        }
        return EncodedIdentity(
            writes = writes,
            sourceData = sourceData.rebuildKeepass(
                CipherSourceCanonicalPaths.IDENTITY_PATHS,
                bindings,
            ),
        )
    }

    fun decode(
        scope: DecodeToCipherScope,
        remote: Entry,
    ): DecodedIdentity {
        val username = scope.consumeFieldAndReturnContent(BasicField.UserName())
        val decodedSlots = buildMap {
            IdentitySlot.entries.forEach { slot ->
                val field = slot.decodeKeys.firstNotNullOfOrNull { key ->
                    scope.consumeField(key)?.let { value ->
                        DecodedIdentitySourceField(
                            key = key,
                            value = value,
                            slot = slot,
                        )
                    }
                }
                if (field != null) put(slot, field)
            }
        }

        fun valueOf(slot: IdentitySlot): String? = decodedSlots[slot]?.value?.content

        return DecodedIdentity(
            identity = BitwardenCipher.Identity(
                title = valueOf(IdentitySlot.TITLE),
                firstName = valueOf(IdentitySlot.FIRST_NAME),
                middleName = valueOf(IdentitySlot.MIDDLE_NAME),
                lastName = valueOf(IdentitySlot.LAST_NAME),
                address1 = valueOf(IdentitySlot.ADDRESS1),
                address2 = valueOf(IdentitySlot.ADDRESS2),
                address3 = valueOf(IdentitySlot.ADDRESS3),
                city = valueOf(IdentitySlot.CITY),
                state = valueOf(IdentitySlot.STATE),
                postalCode = valueOf(IdentitySlot.POSTAL_CODE),
                country = valueOf(IdentitySlot.COUNTRY),
                company = valueOf(IdentitySlot.COMPANY),
                email = valueOf(IdentitySlot.EMAIL),
                phone = valueOf(IdentitySlot.PHONE),
                ssn = valueOf(IdentitySlot.SSN),
                username = username,
                passportNumber = valueOf(IdentitySlot.PASSPORT_NUMBER),
                licenseNumber = valueOf(IdentitySlot.LICENSE_NUMBER),
            ),
            sourceBindings = decodedSlots.values.map { it.toBinding(remote) },
        )
    }

    /** Whether [remote] carries Keyguard identity custom fields. */
    fun detects(remote: Entry): Boolean =
        remote.fields.keys.any { it.startsWith(FIELD_PREFIX) }

    private fun DecodedIdentitySourceField.toBinding(remote: Entry): SourceBinding =
        encodedBinding(
            slot = slot,
            sourceField = decodedSourceFieldRef(
                remote = remote,
                key = key,
                value = value,
                role = slot.role,
                representationId = KeePassIdentityRepresentationIds.TEXT,
            ),
            key = key,
            concealed = value.isConcealed(),
        )

    private fun encodedBinding(
        slot: IdentitySlot,
        sourceField: SourceFieldRef?,
        key: String,
        concealed: Boolean,
    ): SourceBinding = sourceBinding(
        canonicalPaths = listOf(slot.path),
        sourceFields = listOf(
            encodedSourceFieldRef(
                key = key,
                concealed = concealed,
                role = slot.role,
                representationId = KeePassIdentityRepresentationIds.TEXT,
                existing = sourceField,
            ),
        ),
        projectionId = KeePassIdentityProjectionIds.KEYGUARD,
    )

    data class DecodedIdentity(
        val identity: BitwardenCipher.Identity,
        val sourceBindings: List<SourceBinding>,
    )

    data class EncodedIdentity(
        val writes: List<KeePassFieldWrite>,
        val sourceData: CipherSourceData?,
    )

    /**
     * One identity field's mapping. [decodeKeys] are tried in order on decode
     * (aliases first), and [defaultKey] (the canonical key) is what a fresh encode
     * writes when no source binding pins a foreign key.
     */
    private enum class IdentitySlot(
        val path: String,
        val decodeKeys: List<String>,
        val concealedDefault: Boolean = false,
        val get: (BitwardenCipher.Identity) -> String?,
    ) {
        TITLE(
            path = CipherSourceCanonicalPaths.IDENTITY_TITLE,
            decodeKeys = listOf(KeePassFieldKey.IDENTITY_TITLE),
            get = { it.title },
        ),
        FIRST_NAME(
            path = CipherSourceCanonicalPaths.IDENTITY_FIRST_NAME,
            decodeKeys = listOf(KeePassFieldKey.IDENTITY_NAME, KeePassFieldKey.IDENTITY_FIRST_NAME),
            get = { it.firstName },
        ),
        MIDDLE_NAME(
            path = CipherSourceCanonicalPaths.IDENTITY_MIDDLE_NAME,
            decodeKeys = listOf(KeePassFieldKey.IDENTITY_MIDDLE_NAME),
            get = { it.middleName },
        ),
        LAST_NAME(
            path = CipherSourceCanonicalPaths.IDENTITY_LAST_NAME,
            decodeKeys = listOf(KeePassFieldKey.IDENTITY_LAST_NAME),
            get = { it.lastName },
        ),
        ADDRESS1(
            path = CipherSourceCanonicalPaths.IDENTITY_ADDRESS1,
            decodeKeys = listOf(
                KeePassFieldKey.IDENTITY_ADDRESS,
                KeePassFieldKey.IDENTITY_ADDRESS1,
            ),
            get = { it.address1 },
        ),
        ADDRESS2(
            path = CipherSourceCanonicalPaths.IDENTITY_ADDRESS2,
            decodeKeys = listOf(KeePassFieldKey.IDENTITY_ADDRESS2),
            get = { it.address2 },
        ),
        ADDRESS3(
            path = CipherSourceCanonicalPaths.IDENTITY_ADDRESS3,
            decodeKeys = listOf(KeePassFieldKey.IDENTITY_ADDRESS3),
            get = { it.address3 },
        ),
        CITY(
            path = CipherSourceCanonicalPaths.IDENTITY_CITY,
            decodeKeys = listOf(KeePassFieldKey.IDENTITY_CITY),
            get = { it.city },
        ),
        STATE(
            path = CipherSourceCanonicalPaths.IDENTITY_STATE,
            decodeKeys = listOf(KeePassFieldKey.IDENTITY_STATE),
            get = { it.state },
        ),
        POSTAL_CODE(
            path = CipherSourceCanonicalPaths.IDENTITY_POSTAL_CODE,
            decodeKeys = listOf(KeePassFieldKey.IDENTITY_POSTAL_CODE),
            get = { it.postalCode },
        ),
        COUNTRY(
            path = CipherSourceCanonicalPaths.IDENTITY_COUNTRY,
            decodeKeys = listOf(KeePassFieldKey.IDENTITY_COUNTRY),
            get = { it.country },
        ),
        COMPANY(
            path = CipherSourceCanonicalPaths.IDENTITY_COMPANY,
            decodeKeys = listOf(KeePassFieldKey.IDENTITY_COMPANY),
            get = { it.company },
        ),
        EMAIL(
            path = CipherSourceCanonicalPaths.IDENTITY_EMAIL,
            decodeKeys = listOf(KeePassFieldKey.IDENTITY_EMAIL),
            get = { it.email },
        ),
        PHONE(
            path = CipherSourceCanonicalPaths.IDENTITY_PHONE,
            decodeKeys = listOf(KeePassFieldKey.IDENTITY_PHONE),
            get = { it.phone },
        ),
        SSN(
            path = CipherSourceCanonicalPaths.IDENTITY_SSN,
            decodeKeys = listOf(KeePassFieldKey.IDENTITY_SSN),
            concealedDefault = true,
            get = { it.ssn },
        ),
        PASSPORT_NUMBER(
            path = CipherSourceCanonicalPaths.IDENTITY_PASSPORT_NUMBER,
            decodeKeys = listOf(KeePassFieldKey.IDENTITY_PASSPORT_NUMBER),
            concealedDefault = true,
            get = { it.passportNumber },
        ),
        LICENSE_NUMBER(
            path = CipherSourceCanonicalPaths.IDENTITY_LICENSE_NUMBER,
            decodeKeys = listOf(KeePassFieldKey.IDENTITY_LICENSE_NUMBER),
            concealedDefault = true,
            get = { it.licenseNumber },
        ),
        ;

        /** Canonical (non-alias) field key written by a fresh encode. */
        val defaultKey: String get() = decodeKeys.last()

        /** Stable role label = the canonical path's leaf segment. */
        val role: SourceRole get() = SourceRole(path.substringAfterLast('.'))
    }

    private data class DecodedIdentitySourceField(
        val key: String,
        val value: EntryValue,
        val slot: IdentitySlot,
    )

    private companion object {
        const val FIELD_PREFIX = "identity_"
    }
}

internal object KeePassIdentityProjectionIds {
    val KEYGUARD = SourceProjectionId("keepass.identity.keyguard")
}

internal object KeePassIdentityRepresentationIds {
    val TEXT = SourceRepresentation("keepass.identity.text")
}
