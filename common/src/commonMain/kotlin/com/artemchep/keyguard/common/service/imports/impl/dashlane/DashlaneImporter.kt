package com.artemchep.keyguard.common.service.imports.impl.dashlane

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.imports.ImportError
import com.artemchep.keyguard.common.service.imports.ImportFormat
import com.artemchep.keyguard.common.service.imports.ImportFormatId
import com.artemchep.keyguard.common.service.imports.ImportInput
import com.artemchep.keyguard.common.service.imports.ImportResult
import com.artemchep.keyguard.common.service.imports.ImportSkipReason
import com.artemchep.keyguard.common.service.imports.impl.CsvRow
import com.artemchep.keyguard.common.service.imports.impl.IMPORT_EXTENSION_CSV
import com.artemchep.keyguard.common.service.imports.impl.ImportPlanBuilder
import com.artemchep.keyguard.common.service.imports.impl.Importer
import com.artemchep.keyguard.common.service.imports.impl.csvRow
import com.artemchep.keyguard.common.service.imports.impl.isZipArchive
import com.artemchep.keyguard.common.service.imports.impl.item
import com.artemchep.keyguard.common.service.imports.impl.login
import com.artemchep.keyguard.common.service.imports.impl.readUnencryptedArchiveEntries
import com.artemchep.keyguard.common.service.imports.impl.readCsvTable
import com.artemchep.keyguard.common.service.imports.impl.readText
import com.artemchep.keyguard.common.service.imports.mapping.ImportFieldBag
import com.artemchep.keyguard.common.service.imports.mapping.ImportedItemMeta
import com.artemchep.keyguard.common.service.imports.mapping.importedCard
import com.artemchep.keyguard.common.service.imports.mapping.importedExpiryOrNull
import com.artemchep.keyguard.common.service.imports.mapping.importedFieldOrNull
import com.artemchep.keyguard.common.service.imports.mapping.importedUriOrNull
import com.artemchep.keyguard.common.service.imports.mapping.orNullIfBlank
import com.artemchep.keyguard.util.io.decodeTextDetectingEncoding
import kotlin.time.Instant

/**
 * Dashlane's CSV export: `credentials.csv`, `securenotes.csv`,
 * `payments.csv`, `ids.csv` and `personalInfo.csv`, plus `wifi.csv` from
 * the Apple apps. The web app zips them; the mobile apps write them into a
 * folder, so a single file of any kind is read as well, told apart by its
 * header.
 *
 * The platforms spell the same columns differently (`username2`,
 * `user_name_2`), which the header matching absorbs. The Android app writes
 * an absent value as the text `null`.
 */
internal class DashlaneImporter : Importer {
    override val format = ImportFormat(
        id = ImportFormatId.Dashlane,
        title = "Dashlane",
        extensions = listOf("zip", IMPORT_EXTENSION_CSV),
    )

    override fun parse(
        input: ImportInput,
        password: Password?,
        now: Instant,
    ): ImportResult {
        val texts = if (input.isZipArchive()) {
            input.readUnencryptedArchiveEntries(accept = ::isExportEntry)
                .mapValues { (_, bytes) -> decodeTextDetectingEncoding(bytes) }
        } else {
            mapOf(input.name.orEmpty() to input.readText())
        }
        val tables = texts.mapValues { (_, text) -> readDashlaneTable(text) }
        if (tables.values.none { it != null }) {
            return ImportResult.Failure(ImportError.Parse)
        }
        val plan = ImportPlanBuilder(format)
        tables.forEach { (name, table) ->
            if (table == null) {
                plan.skip(ImportSkipReason.File, name)
                return@forEach
            }
            table.rows.forEach { row ->
                plan.csvRow(row.row, titleOf = { table.kind.titleOf(row) }) { title ->
                    table.kind.map(row, title, now, plan)
                }
            }
        }
        return ImportResult.Success(plan.build())
    }

    /**
     * A CSV of the export. Archive metadata that macOS adds when zipping a
     * folder is not.
     */
    private fun isExportEntry(
        name: String,
    ): Boolean = name.endsWith(".csv", ignoreCase = true) &&
        !name.startsWith("__MACOSX/") &&
        !name.substringAfterLast('/').startsWith("._")
}

private class DashlaneTable(
    val kind: DashlaneFile,
    val rows: Sequence<DashlaneRow>,
)

/**
 * A row whose values treat Android's `null` text as absent.
 */
internal class DashlaneRow(
    val row: CsvRow,
    /** The header names as written, for rows kept as custom fields. */
    val names: List<String>,
    private val androidNulls: Boolean,
) {
    operator fun get(column: String): String? = row[column]
        ?.takeUnless { androidNulls && it == ANDROID_NULL }

    /** The value as written, for a password that may be the text `null`. */
    fun raw(column: String): String? = row[column]

    private companion object {
        const val ANDROID_NULL = "null"
    }
}

private fun readDashlaneTable(
    text: String,
): DashlaneTable? {
    val table = readCsvTable(text)
        ?: return null
    return DashlaneFile.entries
        .firstOrNull { table.header.containsAll(it.columns) }
        ?.let { kind ->
            val androidNulls = kind.androidColumn?.let { column ->
                table.header.names.any { it.trim().equals(column, ignoreCase = true) }
            } == true
            DashlaneTable(
                kind = kind,
                rows = table.rows.map {
                    DashlaneRow(it, table.header.names, androidNulls)
                },
            )
        }
}

/**
 * The files of the export, recognized by the columns that only they have.
 * Declaration order matters: the notes file is the loosest match.
 */
private enum class DashlaneFile(
    val columns: Set<String>,
    /** Original spelling identifying Android. Ambiguous formats keep literal `null`. */
    val androidColumn: String? = null,
) {
    Credentials(setOf("username", "title", "password", "url"), androidColumn = "user_name") {
        override fun titleOf(row: DashlaneRow) = row["title"].orNullIfBlank()

        override fun map(row: DashlaneRow, title: String?, now: Instant, plan: ImportPlanBuilder) =
            mapDashlaneCredential(row, title, now, plan)
    },
    Payments(setOf("type", "cc_number", "account_number"), androidColumn = "ccNumber") {
        override fun titleOf(row: DashlaneRow) = row["name"].orNullIfBlank()
            ?: row["account_name"].orNullIfBlank()

        override fun map(row: DashlaneRow, title: String?, now: Instant, plan: ImportPlanBuilder) =
            mapDashlanePayment(row, title, now, plan)
    },
    Ids(setOf("type", "number", "issue_date"), androidColumn = "issueDate") {
        override fun titleOf(row: DashlaneRow): String? {
            val type = row["type"]?.let(::humanizeDashlaneType)
            val name = row["name"].orNullIfBlank()
            return when {
                type != null && name != null -> "$type ($name)"
                else -> type ?: name
            }
        }

        override fun map(row: DashlaneRow, title: String?, now: Instant, plan: ImportPlanBuilder) =
            plan.item(
                meta = ImportedItemMeta(now = now, title = title),
                type = DSecret.Type.SecureNote,
                fields = DashlaneFields(row).rest(hidden = setOf("number")),
            )
    },
    PersonalInfo(setOf("type", "first_name", "date_of_birth"), androidColumn = "firstName") {
        override fun titleOf(row: DashlaneRow): String? = row["item_name"].orNullIfBlank()
            ?: listOfNotNull(row["first_name"], row["middle_name"], row["last_name"])
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .orNullIfBlank()
            ?: row["type"]?.let(::humanizeDashlaneType)

        override fun map(row: DashlaneRow, title: String?, now: Instant, plan: ImportPlanBuilder) =
            plan.item(
                meta = ImportedItemMeta(now = now, title = title),
                type = DSecret.Type.SecureNote,
                fields = DashlaneFields(row).rest(),
            )
    },
    Wifi(setOf("ssid", "passphrase")) {
        override fun titleOf(row: DashlaneRow) = row["name"].orNullIfBlank()
            ?: row["ssid"].orNullIfBlank()

        override fun map(row: DashlaneRow, title: String?, now: Instant, plan: ImportPlanBuilder) {
            val fields = DashlaneFields(row)
            // The title.
            fields.drop("name")
            plan.item(
                meta = ImportedItemMeta(now = now, title = title, note = fields.take("note")),
                type = DSecret.Type.SecureNote,
                fields = listOfNotNull(
                    importedFieldOrNull("SSID", fields.take("ssid")),
                    importedFieldOrNull("Password", fields.takeRaw("passphrase"), hidden = true),
                    importedFieldOrNull("Hidden network", fields.take("hidden")),
                    importedFieldOrNull("Security", fields.take("encription_type")),
                ) + fields.rest(),
            )
        }
    },
    SecureNotes(setOf("title", "note")) {
        override fun titleOf(row: DashlaneRow) = row["title"].orNullIfBlank()

        override fun map(row: DashlaneRow, title: String?, now: Instant, plan: ImportPlanBuilder) {
            val fields = DashlaneFields(row)
            fields.drop("title")
            plan.item(
                meta = ImportedItemMeta(
                    now = now,
                    title = title,
                    note = fields.take("note"),
                    tags = dashlaneCollections(fields.take("category")),
                ),
                type = DSecret.Type.SecureNote,
                fields = fields.rest(),
            )
        }
    },
    ;

    abstract fun titleOf(row: DashlaneRow): String?

    abstract fun map(
        row: DashlaneRow,
        title: String?,
        now: Instant,
        plan: ImportPlanBuilder,
    )
}

/** `social_security` → `Social security`. */
private fun humanizeDashlaneType(
    type: String,
): String? = type
    .trim()
    .replace('_', ' ')
    .replaceFirstChar { it.uppercaseChar() }
    .orNullIfBlank()

/**
 * The collections of an item, which the web export joins with `, ` into the
 * `category` column. An item can be in several, so they become tags.
 */
private fun dashlaneCollections(
    category: String?,
): List<String> = category
    ?.split(", ")
    .orEmpty()

/**
 * The columns of one row. The ones read into a member are taken out; the
 * populated rest become custom fields.
 */
private class DashlaneFields(
    private val row: DashlaneRow,
) {
    private val columns = ImportFieldBag(row.names, keyOf = ::normalizeColumn)

    /** Takes [column] and returns its value. */
    fun take(column: String): String? {
        drop(column)
        return row[column]
    }

    /** Takes [column] and returns its value as written, see [DashlaneRow.raw]. */
    fun takeRaw(column: String): String? {
        drop(column)
        return row.raw(column)
    }

    /** Takes columns whose values are read elsewhere, such as the title. */
    fun drop(vararg columns: String) = columns.forEach { column ->
        this.columns.takeAll(normalizeColumn(column))
    }

    /** The populated columns not taken, concealing known secrets. */
    fun rest(
        hidden: Set<String> = emptySet(),
    ): List<DSecret.Field> {
        val concealed = (hidden + sensitiveColumns).map(::normalizeColumn).toSet()
        return columns.rest { name ->
            val column = normalizeColumn(name)
            importedFieldOrNull(
                name = name,
                value = if (column == "password" || column == "passphrase") row.raw(name) else row[name],
                hidden = column in concealed,
            )
        }
    }
}

private fun normalizeColumn(name: String): String = name.trim().replace("_", "").lowercase()

private val sensitiveColumns = setOf(
    "password", "passphrase", "cc_number", "code", "account_number", "pin",
    "otp", "otpUrl", "otpSecret", "address_door_code",
)

private fun mapDashlaneCredential(
    row: DashlaneRow,
    title: String?,
    now: Instant,
    plan: ImportPlanBuilder,
) {
    val fields = DashlaneFields(row)
    fields.drop("title")
    val usernames = listOf("username", "username2", "username3")
        .mapNotNull { fields.take(it).orNullIfBlank() }
    val otpColumn = listOf("otpurl", "otpsecret", "otp")
        .firstOrNull { row[it].orNullIfBlank() != null }
    val meta = ImportedItemMeta(
        now = now,
        title = title,
        note = fields.take("note"),
        uris = listOfNotNull(importedUriOrNull(fields.take("url"))),
        tags = dashlaneCollections(fields.take("category")),
    )
    plan.login(
        meta = meta,
        username = usernames.firstOrNull(),
        password = fields.takeRaw("password"),
        totp = otpColumn?.let(fields::take),
        fields = usernames.drop(1).mapNotNull { importedFieldOrNull("Username", it) } + fields.rest(),
    )
}

private fun mapDashlanePayment(
    row: DashlaneRow,
    title: String?,
    now: Instant,
    plan: ImportPlanBuilder,
) {
    val type = row["type"]?.trim()
    if (type == "bank") {
        mapDashlaneBank(row, title, now, plan)
        return
    }
    val fields = DashlaneFields(row)
    val meta = ImportedItemMeta(
        now = now,
        title = title,
        note = fields.take("note"),
    )
    if (type != "payment_card" && type != "credit_card") {
        plan.item(meta, DSecret.Type.SecureNote, fields.rest())
        return
    }
    fields.drop("type", "name")
    val expiry = importedExpiryOrNull(
        month = row["expiration_month"]?.let(::parseDashlaneMonth),
        year = row["expiration_year"]?.trim()?.toIntOrNull(),
    )
    // An expiry the card cannot hold stays in its columns.
    if (expiry != null) {
        fields.drop("expiration_month", "expiration_year")
    }
    val card = importedCard(
        // On a card the owner is in `account_name`; `account_holder`
        // belongs to bank accounts.
        cardholderName = fields.take("account_name").orNullIfBlank(),
        number = fields.take("cc_number").orNullIfBlank(),
        code = fields.take("code").orNullIfBlank(),
        expiry = expiry,
    )
    val cardFields = listOfNotNull(
        importedFieldOrNull("Bank", fields.take("issuing_bank")),
        importedFieldOrNull("Country", fields.take("country")),
    )
    plan.item(meta, DSecret.Type.Card, card.fields + cardFields + fields.rest()) {
        copy(card = card.card)
    }
}

private fun mapDashlaneBank(
    row: DashlaneRow,
    title: String?,
    now: Instant,
    plan: ImportPlanBuilder,
) {
    val fields = DashlaneFields(row)
    fields.drop("type", "name")
    // The title, when the bank account has no name of its own.
    if (row["name"].orNullIfBlank() == null) {
        fields.drop("account_name")
    }
    plan.item(
        meta = ImportedItemMeta(now = now, title = title, note = fields.take("note")),
        type = DSecret.Type.SecureNote,
        fields = listOfNotNull(
            importedFieldOrNull("Account holder", fields.take("account_holder")),
            importedFieldOrNull("Routing number", fields.take("routing_number")),
            importedFieldOrNull("Account number", fields.take("account_number"), hidden = true),
            importedFieldOrNull("Bank", fields.take("issuing_bank")),
            importedFieldOrNull("Country", fields.take("country")),
        ) + fields.rest(),
    )
}

private val englishMonths = listOf(
    "january", "february", "march", "april", "may", "june",
    "july", "august", "september", "october", "november", "december",
)

/** `3`, `03`, or the Android app's `MARCH`. */
private fun parseDashlaneMonth(
    raw: String,
): Int? = raw.trim().toIntOrNull()
    ?: englishMonths.indexOf(raw.trim().lowercase())
        .takeIf { it >= 0 }
        ?.plus(1)
