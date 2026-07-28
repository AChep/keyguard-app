package com.artemchep.keyguard.benchmark

import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.models.TimeData
import kotlin.uuid.Uuid

internal object BenchmarkVaultCorpus {
    private const val GROUP_SIZE = 100
    private val itemTime = TimeData.create()

    fun create(
        entryCount: Int,
        password: String,
    ): KeePassDatabase.Ver4x {
        require(entryCount > 0) { "The benchmark corpus must contain at least one entry." }

        val credentials = Credentials.from(EncryptedValue.fromString(password))
        val database = KeePassDatabase.Ver4x.create(
            rootName = "Benchmark vault",
            meta = Meta(
                generator = "Keyguard benchmark",
                name = "Benchmark vault ($entryCount items)",
                description = "Deterministic mixed test data for vault unlock benchmarks.",
            ),
            credentials = credentials,
        )
        val groups = (0 until entryCount step GROUP_SIZE).mapIndexed { groupIndex, startIndex ->
            val endIndex = minOf(startIndex + GROUP_SIZE, entryCount)
            Group(
                uuid = benchmarkUuid(namespace = 1, value = groupIndex),
                name = "Folder ${(groupIndex + 1).toString().padStart(2, '0')}",
                notes = if (groupIndex % 10 == 0) "Shared team folder" else "",
                times = itemTime,
                entries = (startIndex until endIndex).map(::createEntry),
            )
        }

        return database.copy(
            content = database.content.copy(
                group = database.content.group.copy(
                    groups = groups,
                ),
            ),
        )
    }

    private fun createEntry(index: Int): Entry {
        val kind = index % 100
        val fields = when (kind) {
            in 95..97 -> secureNoteFields(index)
            98 -> cardFields(index)
            99 -> identityFields(index)
            else -> loginFields(index)
        }
        val tags = buildList {
            if (index % 13 == 0) add("Favorite")
            if (index % 8 == 0) add("work")
            if (index % 21 == 0) add("shared")
        }
        return Entry(
            uuid = benchmarkUuid(namespace = 2, value = index),
            fields = fields,
            times = itemTime,
            tags = tags,
        )
    }

    private fun loginFields(index: Int): EntryFields {
        val domain = DOMAINS[index % DOMAINS.size]
        return commonFields(
            index = index,
            title = "Login ${(index + 1).toString().padStart(4, '0')}",
            notes = if (index % 6 == 0) {
                "Account used by the ${TEAMS[index % TEAMS.size]} team."
            } else {
                ""
            },
        ) + buildList {
            add(BasicField.UserName() to plain("user$index@$domain"))
            add(BasicField.Password() to concealed("correct-horse-$index-battery-staple"))
            add(BasicField.Url() to plain("https://$domain/account/$index"))
            if (index % 5 == 0) {
                add("URL 2" to plain("https://login.$domain/session/$index"))
            }
            if (index % 20 == 0) {
                add(
                    "otp" to concealed(
                        "otpauth://totp/Benchmark:user$index?" +
                            "secret=JBSWY3DPEHPK3PXP&issuer=Benchmark",
                    ),
                )
            }
            if (index % 7 == 0) {
                add("Recovery email" to plain("recovery$index@example.test"))
            }
            if (index % 11 == 0) {
                add("Environment" to plain(ENVIRONMENTS[index % ENVIRONMENTS.size]))
            }
        }
    }

    private fun secureNoteFields(index: Int): EntryFields = commonFields(
        index = index,
        title = "Secure note ${(index + 1).toString().padStart(4, '0')}",
        notes = "Recovery instructions for record $index.\n" +
            "Owner: ${TEAMS[index % TEAMS.size]}\n" +
            "Review every six months.",
    ) + listOf(
        "Reference" to plain("NOTE-${index.toString().padStart(6, '0')}"),
    )

    private fun cardFields(index: Int): EntryFields = commonFields(
        index = index,
        title = "Payment card ${(index + 1).toString().padStart(4, '0')}",
        notes = "Test purchasing card for ${TEAMS[index % TEAMS.size]}.",
    ) + listOf(
        "card_cardholderName" to plain("Benchmark User $index"),
        "card_brand" to plain(CARD_BRANDS[index % CARD_BRANDS.size]),
        "card_number" to concealed("41111111${index.toString().padStart(8, '0')}"),
        "card_expMonth" to plain(((index % 12) + 1).toString()),
        "card_expYear" to plain((2030 + index % 5).toString()),
        "card_code" to concealed((100 + index % 900).toString()),
    )

    private fun identityFields(index: Int): EntryFields = commonFields(
        index = index,
        title = "Identity ${(index + 1).toString().padStart(4, '0')}",
        notes = "Synthetic identity used only by the benchmark corpus.",
    ) + listOf(
        "identity_firstName" to plain("Benchmark"),
        "identity_lastName" to plain("User $index"),
        "identity_email" to plain("identity$index@example.test"),
        "identity_phone" to plain("+1-555-${(index % 10_000).toString().padStart(4, '0')}"),
        "identity_address1" to plain("${100 + index % 900} Test Avenue"),
        "identity_city" to plain(CITIES[index % CITIES.size]),
        "identity_country" to plain("Testland"),
        "identity_postalCode" to plain((10_000 + index % 90_000).toString()),
    )

    private fun commonFields(
        index: Int,
        title: String,
        notes: String,
    ): EntryFields = EntryFields.createDefault() + listOf(
        BasicField.Title() to plain(title),
        BasicField.Notes() to plain(notes),
        "Benchmark record" to plain(index.toString()),
    )

    private fun benchmarkUuid(
        namespace: Int,
        value: Int,
    ): Uuid = Uuid.parse(
        "00000000-0000-4000-${namespace.toString(16).padStart(4, '0')}-" +
            value.toString(16).padStart(12, '0'),
    )

    private fun plain(value: String) = EntryValue.Plain(value)

    private fun concealed(value: String) = EntryValue.Encrypted(
        EncryptedValue.fromString(value),
    )

    private val DOMAINS = listOf(
        "example.test",
        "mail.example.test",
        "cloud.example.test",
        "shop.example.test",
        "developer.example.test",
        "finance.example.test",
        "travel.example.test",
        "support.example.test",
    )
    private val TEAMS = listOf("engineering", "finance", "operations", "support", "sales")
    private val ENVIRONMENTS = listOf("production", "staging", "development")
    private val CARD_BRANDS = listOf("Visa", "Mastercard", "Amex")
    private val CITIES = listOf("North Teston", "Sample City", "Mock Harbor", "Fixture Hills")
}
