package com.artemchep.keyguard.common.service.imports.mapping

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest

private const val MONTH_MIN = 1
private const val MONTH_MAX = 12
private const val TWO_DIGIT_YEAR_BASE = 2000
private const val TWO_DIGIT_YEAR_MAX = 99

/**
 * A card expiry date, the month `1..12` and a four-digit year, both as the
 * vault stores them. The parsers here read both; a source that lets either
 * be empty, such as LastPass, leaves that one `null`.
 */
internal data class ImportedExpiry(
    val month: String?,
    val year: String?,
)

/**
 * A card, and the fields that keep what the card cannot hold.
 */
internal data class ImportedCard(
    val card: CreateRequest.Card,
    val fields: List<DSecret.Field>,
)

/**
 * A card, with its PIN, its valid-from date, and an expiry date that could
 * not be read kept as fields. [expiry] and [validFrom] are [rawExpiry] and
 * [rawValidFrom] read by [importedExpiryOrNull]; a source with a spelling of
 * its own reads them itself.
 */
internal fun importedCard(
    cardholderName: String? = null,
    brand: String? = null,
    number: String? = null,
    code: String? = null,
    rawExpiry: String? = null,
    expiry: ImportedExpiry? = importedExpiryOrNull(rawExpiry),
    pin: String? = null,
    rawValidFrom: String? = null,
    validFrom: ImportedExpiry? = importedExpiryOrNull(rawValidFrom),
): ImportedCard = ImportedCard(
    card = CreateRequest.Card(
        cardholderName = cardholderName,
        brand = brand,
        number = number,
        code = code,
        expMonth = expiry?.month,
        expYear = expiry?.year,
    ),
    fields = listOfNotNull(
        importedFieldOrNull(IMPORT_CARD_PIN_LABEL, pin, hidden = true),
        importedFieldOrNull(IMPORT_CARD_EXPIRY_LABEL, rawExpiry.takeIf { expiry == null }),
        // The vault keeps no valid-from date on a card.
        importedFieldOrNull(IMPORT_CARD_VALID_FROM_LABEL, validFrom?.toFieldText() ?: rawValidFrom),
    ),
)

private val SEPARATED_MONTH_FIRST = Regex("""^(\d{1,2})\s*[/\-.\s]\s*(\d{2}|\d{4})$""")
private val SEPARATED_YEAR_FIRST = Regex("""^(\d{4})\s*[/\-.]\s*(\d{1,2})(?:[/\-.]\d{1,2})?$""")
private val COMPACT_MONTH_YEAR = Regex("""^(\d{2})(\d{2})$""")
private val COMPACT_YEAR_MONTH = Regex("""^(\d{4})(\d{2})$""")

/**
 * Reads the expiry spellings exports use: `MM/YY`, `MM/YYYY`, `MM-YYYY`,
 * `YYYY-MM`, `YYYY-MM-DD`, `MMYY` and `YYYYMM`. `null` for anything else, so
 * the caller can keep the raw value instead of guessing.
 */
internal fun importedExpiryOrNull(
    raw: String?,
): ImportedExpiry? {
    val value = raw?.trim().orEmpty()
    val parts = SEPARATED_MONTH_FIRST.matchEntire(value)
        ?.destructured
        ?.let { (m, y) -> m to y }
        ?: SEPARATED_YEAR_FIRST.matchEntire(value)
            ?.destructured
            ?.let { (y, m) -> m to y }
        ?: COMPACT_YEAR_MONTH.matchEntire(value)
            ?.destructured
            ?.let { (y, m) -> m to y }
        ?: COMPACT_MONTH_YEAR.matchEntire(value)
            ?.destructured
            ?.let { (m, y) -> m to y }
    return parts?.let { (month, year) ->
        importedExpiryOrNull(
            month = month.toIntOrNull(),
            year = year.toIntOrNull(),
        )
    }
}

/**
 * The expiry date of a [month] `1..12` and a [year], where a two-digit year
 * is one of 2000-2099.
 */
internal fun importedExpiryOrNull(
    month: Int?,
    year: Int?,
): ImportedExpiry? {
    if (month == null || year == null || month !in MONTH_MIN..MONTH_MAX) {
        return null
    }
    val fullYear = if (year <= TWO_DIGIT_YEAR_MAX) TWO_DIGIT_YEAR_BASE + year else year
    return ImportedExpiry(
        month = month.toString(),
        year = fullYear.toString(),
    )
}
