package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec

import app.keemobile.kotpass.cryptography.EncryptedValue
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
 * Parser contract for KeePass credit-card custom fields.
 *
 * Field families:
 *
 * | Projection             | Fields parsed or written                                      |
 * |------------------------|---------------------------------------------------------------|
 * | Keyguard               | `card_cardholderName`, `card_brand`, `card_number`,           |
 * |                        | `card_expMonth`, `card_expYear`, `card_code`.                 |
 * | Strongbox              | `CardholderName`, `CardType`, `CardNumber`, `ExpiryDate`,     |
 * |                        | `CVV`; aliases include `Cardholder Name`, `Card Holder`,      |
 * |                        | `Card Type`, `Card Number`, `Expiry Date`.                    |
 * | KeePassium             | `Cardholder Name`, `Brand`, `Number`, `Expiration Month`,     |
 * |                        | `Expiration Year`, `Security Code`.                           |
 * | KeePassDX              | `Credit Card` marker plus `Holder`, `Number`, `CVV`.          |
 *
 * Decode picks one field per card slot by priority: Keyguard, Strongbox,
 * KeePassium, then KeePassDX. Strongbox `ExpiryDate` parses `MM/YYYY` or
 * `MM/YY` into separate Keyguard month/year slots.
 *
 * Encode writes back through existing source bindings when possible so foreign
 * templates keep their field names and concealment. Fresh writes use Keyguard
 * fields, except Strongbox-bound expiry can stay as `ExpiryDate`. Card number
 * and security code are always written concealed.
 */
internal class KeePassCardCodec {
    fun hasCardFields(remote: Entry): Boolean {
        val keys = remote.fields.keys
        if (keys.any { it.startsWith(KEYGUARD_CARD_PREFIX) }) return true
        if (strongboxCanonicalCount(remote) >= STRONGBOX_MIN_CANONICAL_MATCHES) return true
        if (hasKeePassiumBitwardenCard(remote)) return true
        if (hasKeePassDxCard(remote, strict = true)) return true
        return false
    }

    fun decode(
        scope: DecodeToCipherScope,
        remote: Entry,
    ): DecodedCard {
        val sourceFields = selectSourceFields(
            decodeKeyguard(remote) +
                    decodeStrongbox(remote) +
                    decodeKeePassiumBitwarden(remote) +
                    decodeKeePassDx(remote),
        )

        sourceFields.forEach { field ->
            scope.consumeField(field.key)
        }

        return DecodedCard(
            card = BitwardenCipher.Card(
                cardholderName = sourceFields.valueFor(CardSlot.CARDHOLDER_NAME),
                brand = sourceFields.valueFor(CardSlot.BRAND),
                number = sourceFields.valueFor(CardSlot.NUMBER),
                expMonth = sourceFields.valueFor(CardSlot.EXP_MONTH),
                expYear = sourceFields.valueFor(CardSlot.EXP_YEAR),
                code = sourceFields.valueFor(CardSlot.CODE),
            ),
            sourceBindings = sourceFields.map { field -> field.toBinding(remote) },
        )
    }

    fun encode(
        card: BitwardenCipher.Card,
        sourceData: CipherSourceData?,
    ): EncodedCard {
        if (!card.hasAnyValue()) {
            return EncodedCard(
                writes = emptyList(),
                sourceData = sourceData.rebuildKeepass(
                    stripPaths = CipherSourceCanonicalPaths.CARD_PATHS,
                    newBindings = emptyList(),
                ),
            )
        }

        val sourceBindings = sourceData.keepassBindingsFor(CipherSourceCanonicalPaths.CARD_PATHS)
        val preferredProjectionId = sourceBindings
            .firstNotNullOfOrNull { it.projection.id }
            ?: KeePassCardProjectionIds.KEYGUARD

        val writes = mutableListOf<KeePassFieldWrite>()
        val bindings = mutableListOf<SourceBinding>()
        val encodedSlots = mutableSetOf<CardSlot>()

        val expiryBinding = sourceBindings.expiryBinding()
        val expMonth = card.expMonth
        val expYear = card.expYear
        if (
            expMonth != null &&
            expYear != null &&
            (expiryBinding?.projection?.id == KeePassCardProjectionIds.STRONGBOX ||
                    preferredProjectionId == KeePassCardProjectionIds.STRONGBOX)
        ) {
            val sourceField = expiryBinding?.sourceFields?.firstOrNull()
            val key = sourceField?.key ?: STRONGBOX_EXPIRY_DATE_FIELD_KEY
            val value = formatStrongboxExpiryDate(expMonth, expYear)
            writes += keepassFieldWrite(
                key = key,
                value = value,
                concealed = sourceField.isConcealedByDefault(default = false),
            )
            bindings += encodedBinding(
                canonicalPaths = listOf(
                    CipherSourceCanonicalPaths.CARD_EXP_MONTH,
                    CipherSourceCanonicalPaths.CARD_EXP_YEAR,
                ),
                projectionId = KeePassCardProjectionIds.STRONGBOX,
                sourceField = sourceField,
                key = key,
                role = SOURCE_ROLE_EXPIRY_DATE,
                representationId = KeePassCardRepresentationIds.EXPIRY_DATE,
                concealed = sourceField.isConcealedByDefault(default = false),
            )
            encodedSlots += CardSlot.EXP_MONTH
            encodedSlots += CardSlot.EXP_YEAR
        }

        CardSlot.entries.forEach { slot ->
            if (slot in encodedSlots) return@forEach
            val value = card.valueFor(slot) ?: return@forEach
            val binding = sourceBindings.bindingFor(slot.path)
            val source = sourceForSlot(
                slot = slot,
                binding = binding,
                preferredProjectionId = preferredProjectionId,
            )
            val concealed = if (slot.sensitive) {
                true
            } else {
                source.sourceField.isConcealedByDefault(default = false)
            }
            writes += keepassFieldWrite(source.key, value, concealed)
            bindings += encodedBinding(
                canonicalPaths = listOf(slot.path),
                projectionId = source.projectionId,
                sourceField = source.sourceField,
                key = source.key,
                role = slot.role,
                representationId = KeePassCardRepresentationIds.TEXT,
                concealed = concealed,
            )
        }

        return EncodedCard(
            writes = writes,
            sourceData = sourceData.rebuildKeepass(
                stripPaths = CipherSourceCanonicalPaths.CARD_PATHS,
                newBindings = bindings,
            ),
        )
    }

    private fun decodeKeyguard(remote: Entry): List<DecodedCardSourceField> =
        decodeSimple(remote, KeePassCardProjectionIds.KEYGUARD, priority = 10)

    /** Decode candidates for a projection whose slots map 1:1 onto single fields. */
    private fun decodeSimple(
        remote: Entry,
        projectionId: SourceProjectionId,
        priority: Int,
    ): List<DecodedCardSourceField> =
        CARD_PROJECTION_FIELDS.getValue(projectionId).mapNotNull { (slot, key) ->
            decodedField(
                remote = remote,
                key = key,
                slots = slotMap(slot),
                projectionId = projectionId,
                priority = priority,
            )
        }

    private fun decodeStrongbox(remote: Entry): List<DecodedCardSourceField> = buildList {
        val hasStrongboxCard = strongboxCanonicalCount(remote) >= STRONGBOX_MIN_CANONICAL_MATCHES ||
                hasStrongboxAliasCard(remote)
        if (!hasStrongboxCard) return@buildList

        addAll(decodeSimple(remote, KeePassCardProjectionIds.STRONGBOX, priority = 20))
        decodedStrongboxExpiryDate(
            remote = remote,
            keys = listOf(STRONGBOX_EXPIRY_DATE_FIELD_KEY),
            priority = 20,
        )?.let(::add)

        decodedField(
            remote = remote,
            keys = listOf("Cardholder Name", "Card Holder"),
            slots = slotMap(CardSlot.CARDHOLDER_NAME),
            projectionId = KeePassCardProjectionIds.STRONGBOX,
            priority = 40,
        )?.let(::add)
        decodedField(
            remote = remote,
            key = "Card Type",
            slots = slotMap(CardSlot.BRAND),
            projectionId = KeePassCardProjectionIds.STRONGBOX,
            priority = 40,
        )?.let(::add)
        decodedField(
            remote = remote,
            key = "Card Number",
            slots = slotMap(CardSlot.NUMBER),
            projectionId = KeePassCardProjectionIds.STRONGBOX,
            priority = 40,
        )?.let(::add)
        decodedStrongboxExpiryDate(
            remote = remote,
            keys = listOf("Expiry Date"),
            priority = 40,
        )?.let(::add)
    }

    private fun decodeKeePassiumBitwarden(remote: Entry): List<DecodedCardSourceField> {
        if (!hasKeePassiumBitwardenCard(remote)) return emptyList()
        return decodeSimple(remote, KeePassCardProjectionIds.KEEPASSIUM_BITWARDEN, priority = 30)
    }

    private fun decodeKeePassDx(remote: Entry): List<DecodedCardSourceField> {
        if (!hasKeePassDxCard(remote, strict = false)) return emptyList()
        return decodeSimple(remote, KeePassCardProjectionIds.KEEPASS_DX, priority = 50)
    }

    private fun decodedStrongboxExpiryDate(
        remote: Entry,
        keys: List<String>,
        priority: Int,
    ): DecodedCardSourceField? {
        val source = remote.firstField(keys) ?: return null
        val expiry = parseStrongboxExpiryDate(source.value.content) ?: return null
        return DecodedCardSourceField(
            key = source.key,
            value = source.value,
            values = mapOf(
                CardSlot.EXP_MONTH to expiry.month,
                CardSlot.EXP_YEAR to expiry.year,
            ),
            projectionId = KeePassCardProjectionIds.STRONGBOX,
            role = SOURCE_ROLE_EXPIRY_DATE,
            representationId = KeePassCardRepresentationIds.EXPIRY_DATE,
            priority = priority,
        )
    }

    private fun decodedField(
        remote: Entry,
        key: String,
        slots: Map<CardSlot, String?>,
        projectionId: SourceProjectionId,
        priority: Int,
    ): DecodedCardSourceField? = decodedField(
        remote = remote,
        keys = listOf(key),
        slots = slots,
        projectionId = projectionId,
        priority = priority,
    )

    private fun decodedField(
        remote: Entry,
        keys: List<String>,
        slots: Map<CardSlot, String?>,
        projectionId: SourceProjectionId,
        priority: Int,
    ): DecodedCardSourceField? {
        val source = remote.firstField(keys) ?: return null
        return DecodedCardSourceField(
            key = source.key,
            value = source.value,
            values = slots.mapValues { (_, value) -> value ?: source.value.content },
            projectionId = projectionId,
            role = slots.keys.singleOrNull()?.role,
            representationId = KeePassCardRepresentationIds.TEXT,
            priority = priority,
        )
    }

    private fun selectSourceFields(
        candidates: List<DecodedCardSourceField>,
    ): List<DecodedCardSourceField> {
        val selectedSlots = mutableSetOf<CardSlot>()
        return candidates
            .sortedBy { it.priority }
            .filter { candidate ->
                val slots = candidate.values.keys
                val shouldSelect = slots.all { it !in selectedSlots }
                if (shouldSelect) {
                    selectedSlots += slots
                }
                shouldSelect
            }
    }

    private fun List<DecodedCardSourceField>.valueFor(slot: CardSlot): String? =
        firstNotNullOfOrNull { field -> field.values[slot] }

    private fun DecodedCardSourceField.toBinding(remote: Entry): SourceBinding =
        sourceBinding(
            canonicalPaths = values.keys.map { it.path },
            sourceFields = listOf(
                decodedSourceFieldRef(
                    remote = remote,
                    key = key,
                    value = value,
                    role = role,
                    representationId = representationId,
                ),
            ),
            projectionId = projectionId,
        )

    private fun encodedBinding(
        canonicalPaths: List<String>,
        projectionId: SourceProjectionId,
        sourceField: SourceFieldRef?,
        key: String,
        role: SourceRole,
        representationId: SourceRepresentation,
        concealed: Boolean,
    ): SourceBinding = sourceBinding(
        canonicalPaths = canonicalPaths,
        sourceFields = listOf(
            encodedSourceFieldRef(
                key = key,
                concealed = concealed,
                role = role,
                representationId = representationId,
                existing = sourceField,
            ),
        ),
        projectionId = projectionId,
    )

    private fun sourceForSlot(
        slot: CardSlot,
        binding: SourceBinding?,
        preferredProjectionId: SourceProjectionId,
    ): EncodedSourceField {
        val sourceField = binding
            ?.sourceFields
            ?.firstOrNull()
        val bindingProjectionId = binding?.projection?.id
        if (
            sourceField != null &&
            !(slot.isExpiry && bindingProjectionId == KeePassCardProjectionIds.STRONGBOX)
        ) {
            return EncodedSourceField(
                key = sourceField.key,
                projectionId = bindingProjectionId ?: KeePassCardProjectionIds.KEYGUARD,
                sourceField = sourceField,
            )
        }

        val projectionId = preferredProjectionId
            .takeIf { projection -> supportsSlot(projection, slot) }
            ?: KeePassCardProjectionIds.KEYGUARD
        return EncodedSourceField(
            key = defaultFieldKey(projectionId, slot),
            projectionId = projectionId,
            sourceField = null,
        )
    }

    private fun List<SourceBinding>.expiryBinding(): SourceBinding? =
        firstOrNull { binding ->
            binding.projection.id == KeePassCardProjectionIds.STRONGBOX &&
                    binding.canonicalFields.any { it.path == CipherSourceCanonicalPaths.CARD_EXP_MONTH } &&
                    binding.canonicalFields.any { it.path == CipherSourceCanonicalPaths.CARD_EXP_YEAR }
        }

    // Strongbox stores expiry as a single combined "ExpiryDate" field, handled
    // bespoke (see encode/decodedStrongboxExpiryDate); the per-slot table below
    // therefore omits Strongbox EXP_MONTH/EXP_YEAR.
    private fun supportsSlot(
        projectionId: SourceProjectionId,
        slot: CardSlot,
    ): Boolean = CARD_PROJECTION_FIELDS[projectionId]?.containsKey(slot) == true

    private fun defaultFieldKey(
        projectionId: SourceProjectionId,
        slot: CardSlot,
    ): String = CARD_PROJECTION_FIELDS[projectionId]?.get(slot)
        ?: CARD_PROJECTION_FIELDS.getValue(KeePassCardProjectionIds.KEYGUARD).getValue(slot)

    private fun BitwardenCipher.Card.valueFor(slot: CardSlot): String? = when (slot) {
        CardSlot.CARDHOLDER_NAME -> cardholderName
        CardSlot.BRAND -> brand
        CardSlot.NUMBER -> number
        CardSlot.EXP_MONTH -> expMonth
        CardSlot.EXP_YEAR -> expYear
        CardSlot.CODE -> code
    }

    private fun BitwardenCipher.Card.hasAnyValue(): Boolean =
        CardSlot.entries.any { slot -> !valueFor(slot).isNullOrEmpty() }

    private fun slotMap(slot: CardSlot): Map<CardSlot, String?> = mapOf(slot to null)

    private fun Entry.firstField(keys: List<String>): FieldSource? =
        keys.firstNotNullOfOrNull { key ->
            fields[key]?.let { value ->
                FieldSource(key = key, value = value)
            }
        }

    private fun strongboxCanonicalCount(remote: Entry): Int =
        STRONGBOX_CANONICAL_FIELD_KEYS.count { key -> key in remote.fields.keys }

    private fun hasStrongboxAliasCard(remote: Entry): Boolean {
        val keys = remote.fields.keys
        val familyCount = STRONGBOX_ALIAS_FIELD_KEYS.count { key -> key in keys }
        return familyCount >= STRONGBOX_MIN_CANONICAL_MATCHES
    }

    private fun hasKeePassDxCard(
        remote: Entry,
        strict: Boolean,
    ): Boolean {
        val fields = remote.fields
        val hasCardFields = fields[KEEPASS_DX_HOLDER_FIELD_KEY] != null &&
                fields[KEEPASS_DX_NUMBER_FIELD_KEY] != null
        if (!hasCardFields) return false
        if (!strict) return true
        return KEEPASS_DX_CREDIT_CARD_MARKER in remote.tags
    }

    private fun hasKeePassiumBitwardenCard(remote: Entry): Boolean {
        val keys = remote.fields.keys
        val familyCount = KEEPASSIUM_BITWARDEN_FIELD_KEYS.count { it in keys }
        if (familyCount < 2) return false
        return KEEPASSIUM_CARDHOLDER_NAME_FIELD_KEY in keys ||
                KEEPASSIUM_EXPIRATION_MONTH_FIELD_KEY in keys ||
                KEEPASSIUM_EXPIRATION_YEAR_FIELD_KEY in keys ||
                KEEPASSIUM_SECURITY_CODE_FIELD_KEY in keys
    }

    private fun parseStrongboxExpiryDate(value: String): ExpiryDate? {
        val match = EXPIRY_DATE_REGEX.matchEntire(value.trim()) ?: return null
        val monthRaw = match.groupValues[1]
        val yearRaw = match.groupValues[2]
        val month = monthRaw.toIntOrNull()
            ?.takeIf { it in 1..12 }
            ?: return null
        val year = if (yearRaw.length == 2) {
            "20$yearRaw"
        } else {
            yearRaw
        }
        return ExpiryDate(
            month = month.toString().padStart(2, '0'),
            year = year,
        )
    }

    private fun formatStrongboxExpiryDate(
        month: String,
        year: String,
    ): String {
        val month2 = month.toIntOrNull()
            ?.takeIf { it in 1..12 }
            ?.toString()
            ?.padStart(2, '0')
            ?: month
        return "$month2/$year"
    }

    data class DecodedCard(
        val card: BitwardenCipher.Card,
        val sourceBindings: List<SourceBinding>,
    )

    data class EncodedCard(
        val writes: List<KeePassFieldWrite>,
        val sourceData: CipherSourceData?,
    )

    private enum class CardSlot(
        val path: String,
        val role: SourceRole,
        val sensitive: Boolean = false,
    ) {
        CARDHOLDER_NAME(
            path = CipherSourceCanonicalPaths.CARD_CARDHOLDER_NAME,
            role = SOURCE_ROLE_CARDHOLDER_NAME,
        ),
        BRAND(
            path = CipherSourceCanonicalPaths.CARD_BRAND,
            role = SOURCE_ROLE_BRAND,
        ),
        NUMBER(
            path = CipherSourceCanonicalPaths.CARD_NUMBER,
            role = SOURCE_ROLE_NUMBER,
            sensitive = true,
        ),
        EXP_MONTH(
            path = CipherSourceCanonicalPaths.CARD_EXP_MONTH,
            role = SOURCE_ROLE_EXP_MONTH,
        ),
        EXP_YEAR(
            path = CipherSourceCanonicalPaths.CARD_EXP_YEAR,
            role = SOURCE_ROLE_EXP_YEAR,
        ),
        CODE(
            path = CipherSourceCanonicalPaths.CARD_CODE,
            role = SOURCE_ROLE_CODE,
            sensitive = true,
        ),
        ;

        val isExpiry: Boolean
            get() = this == EXP_MONTH || this == EXP_YEAR
    }

    private data class DecodedCardSourceField(
        val key: String,
        val value: EntryValue,
        val values: Map<CardSlot, String>,
        val projectionId: SourceProjectionId,
        val role: SourceRole?,
        val representationId: SourceRepresentation,
        val priority: Int,
    )

    private data class EncodedSourceField(
        val key: String,
        val projectionId: SourceProjectionId,
        val sourceField: SourceFieldRef?,
    )

    private data class FieldSource(
        val key: String,
        val value: EntryValue,
    )

    private data class ExpiryDate(
        val month: String,
        val year: String,
    )

    companion object {
        private const val KEYGUARD_CARD_PREFIX = "card_"
        private const val STRONGBOX_MIN_CANONICAL_MATCHES = 2

        private const val STRONGBOX_CARDHOLDER_NAME_FIELD_KEY = "CardholderName"
        private const val STRONGBOX_CARD_TYPE_FIELD_KEY = "CardType"
        private const val STRONGBOX_CARD_NUMBER_FIELD_KEY = "CardNumber"
        private const val STRONGBOX_EXPIRY_DATE_FIELD_KEY = "ExpiryDate"
        private const val STRONGBOX_CVV_FIELD_KEY = "CVV"

        private const val KEEPASS_DX_CREDIT_CARD_MARKER = "Credit Card"
        private const val KEEPASS_DX_HOLDER_FIELD_KEY = "Holder"
        private const val KEEPASS_DX_NUMBER_FIELD_KEY = "Number"
        private const val KEEPASS_DX_CVV_FIELD_KEY = "CVV"

        private const val KEEPASSIUM_CARDHOLDER_NAME_FIELD_KEY = "Cardholder Name"
        private const val KEEPASSIUM_BRAND_FIELD_KEY = "Brand"
        private const val KEEPASSIUM_NUMBER_FIELD_KEY = "Number"
        private const val KEEPASSIUM_EXPIRATION_MONTH_FIELD_KEY = "Expiration Month"
        private const val KEEPASSIUM_EXPIRATION_YEAR_FIELD_KEY = "Expiration Year"
        private const val KEEPASSIUM_SECURITY_CODE_FIELD_KEY = "Security Code"

        // Single source of truth for each projection's slot -> field-key mapping.
        // Drives decode candidates (decodeSimple), defaultFieldKey, and supportsSlot.
        // Strongbox omits EXP_MONTH/EXP_YEAR: it stores a combined "ExpiryDate" field
        // handled bespoke in encode()/decodedStrongboxExpiryDate().
        private val CARD_PROJECTION_FIELDS: Map<SourceProjectionId, Map<CardSlot, String>> = mapOf(
            KeePassCardProjectionIds.KEYGUARD to mapOf(
                CardSlot.CARDHOLDER_NAME to KeePassFieldKey.CARD_CARDHOLDER_NAME,
                CardSlot.BRAND to KeePassFieldKey.CARD_BRAND,
                CardSlot.NUMBER to KeePassFieldKey.CARD_NUMBER,
                CardSlot.EXP_MONTH to KeePassFieldKey.CARD_EXP_MONTH,
                CardSlot.EXP_YEAR to KeePassFieldKey.CARD_EXP_YEAR,
                CardSlot.CODE to KeePassFieldKey.CARD_CODE,
            ),
            KeePassCardProjectionIds.STRONGBOX to mapOf(
                CardSlot.CARDHOLDER_NAME to STRONGBOX_CARDHOLDER_NAME_FIELD_KEY,
                CardSlot.BRAND to STRONGBOX_CARD_TYPE_FIELD_KEY,
                CardSlot.NUMBER to STRONGBOX_CARD_NUMBER_FIELD_KEY,
                CardSlot.CODE to STRONGBOX_CVV_FIELD_KEY,
            ),
            KeePassCardProjectionIds.KEEPASSIUM_BITWARDEN to mapOf(
                CardSlot.CARDHOLDER_NAME to KEEPASSIUM_CARDHOLDER_NAME_FIELD_KEY,
                CardSlot.BRAND to KEEPASSIUM_BRAND_FIELD_KEY,
                CardSlot.NUMBER to KEEPASSIUM_NUMBER_FIELD_KEY,
                CardSlot.EXP_MONTH to KEEPASSIUM_EXPIRATION_MONTH_FIELD_KEY,
                CardSlot.EXP_YEAR to KEEPASSIUM_EXPIRATION_YEAR_FIELD_KEY,
                CardSlot.CODE to KEEPASSIUM_SECURITY_CODE_FIELD_KEY,
            ),
            KeePassCardProjectionIds.KEEPASS_DX to mapOf(
                CardSlot.CARDHOLDER_NAME to KEEPASS_DX_HOLDER_FIELD_KEY,
                CardSlot.NUMBER to KEEPASS_DX_NUMBER_FIELD_KEY,
                CardSlot.CODE to KEEPASS_DX_CVV_FIELD_KEY,
            ),
        )

        private val SOURCE_ROLE_CARDHOLDER_NAME = SourceRole("cardholderName")
        private val SOURCE_ROLE_BRAND = SourceRole("brand")
        private val SOURCE_ROLE_NUMBER = SourceRole("number")
        private val SOURCE_ROLE_EXP_MONTH = SourceRole("expMonth")
        private val SOURCE_ROLE_EXP_YEAR = SourceRole("expYear")
        private val SOURCE_ROLE_EXPIRY_DATE = SourceRole("expiryDate")
        private val SOURCE_ROLE_CODE = SourceRole("code")

        private val EXPIRY_DATE_REGEX = Regex("""([0-9]{1,2})\s*/\s*([0-9]{2}|[0-9]{4})""")
        private val STRONGBOX_CANONICAL_FIELD_KEYS = setOf(
            "CreditCardName",
            STRONGBOX_CARDHOLDER_NAME_FIELD_KEY,
            STRONGBOX_CARD_TYPE_FIELD_KEY,
            STRONGBOX_CARD_NUMBER_FIELD_KEY,
            STRONGBOX_EXPIRY_DATE_FIELD_KEY,
            "ValidFrom",
            STRONGBOX_CVV_FIELD_KEY,
            "PIN",
            "CreditLimit",
            "CashWithdrawalLimit",
            "InterestRate",
            "IssueNumber",
        )
        private val STRONGBOX_ALIAS_FIELD_KEYS = setOf(
            "Credit Card Name",
            "Cardholder Name",
            "Card Holder",
            "Card Type",
            "Card Number",
            "Expiry Date",
            "Valid From",
            "Credit Limit",
            "Cash Withdrawal Limit",
            "Interest Rate",
            "Issue Number",
        )
        private val KEEPASSIUM_BITWARDEN_FIELD_KEYS = setOf(
            KEEPASSIUM_CARDHOLDER_NAME_FIELD_KEY,
            KEEPASSIUM_BRAND_FIELD_KEY,
            KEEPASSIUM_NUMBER_FIELD_KEY,
            KEEPASSIUM_EXPIRATION_MONTH_FIELD_KEY,
            KEEPASSIUM_EXPIRATION_YEAR_FIELD_KEY,
            KEEPASSIUM_SECURITY_CODE_FIELD_KEY,
        )
    }
}

internal object KeePassCardProjectionIds {
    val KEYGUARD = SourceProjectionId("keepass.card.keyguard")
    val STRONGBOX = SourceProjectionId("keepass.card.strongbox")
    val KEEPASS_DX = SourceProjectionId("keepass.card.keepassdx")
    val KEEPASSIUM_BITWARDEN = SourceProjectionId("keepass.card.keepassium.bitwarden")
}

internal object KeePassCardRepresentationIds {
    val TEXT = SourceRepresentation("keepass.card.text")
    val EXPIRY_DATE = SourceRepresentation("keepass.card.expiry-date")
}
