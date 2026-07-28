package app.keemobile.kotpass.database

import app.keemobile.kotpass.constants.AutoTypeObfuscation
import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.constants.GroupOverride
import app.keemobile.kotpass.constants.MemoryProtectionFlag
import app.keemobile.kotpass.constants.PredefinedIcon
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.cryptography.format.CipherProvider
import app.keemobile.kotpass.cryptography.format.TwofishCipher
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.io.decodeBase64ToArray
import app.keemobile.kotpass.models.AutoTypeData
import app.keemobile.kotpass.models.AutoTypeItem
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.CustomDataValue
import app.keemobile.kotpass.models.CustomIcon
import app.keemobile.kotpass.models.DeletedObject
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.models.TimeData
import kotlinx.io.Buffer
import kotlinx.io.write
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Golden tests pinning the XML marshaling behavior of the database
 * content across full binary and plain-XML round-trips. Runs in
 * common tests so every platform exercises the same XML pipeline.
 */
class KeePassDatabaseRoundTripTest {
    @Test
    fun binaryRoundTripPreservesContentForAllFixtures() {
        val fixtures = mapOf(
            "Ver3Aes" to KdbxFixture(KdbxTestFixtures.Ver3Aes),
            "Ver4Aes" to KdbxFixture(KdbxTestFixtures.Ver4Aes),
            "Ver4Twofish" to KdbxFixture(
                KdbxTestFixtures.Ver4Twofish,
                cipherProviders = BaseCiphers.entries + TwofishCipher,
            ),
            "Ver4Argon2" to KdbxFixture(KdbxTestFixtures.Ver4Argon2),
            "Ver4WithBinaries" to KdbxFixture(KdbxTestFixtures.Ver4WithBinaries),
            "GroupsAndEntries" to KdbxFixture(KdbxTestFixtures.GroupsAndEntries),
        )

        fixtures.forEach { (label, fixture) ->
            val original = loadDatabase(fixture)
            val sink = Buffer()
            original.encodeTo(sink, cipherProviders = fixture.cipherProviders)
            val decoded = KeePassDatabase.decode(
                source = sink,
                credentials = credentials(),
                cipherProviders = fixture.cipherProviders,
            )

            assertDatabaseContentEquals(original, decoded, label)
        }
    }

    @Test
    fun xmlExportImportRoundTripPreservesContentForVer4Fixtures() {
        val fixtures = mapOf(
            "Ver4Aes" to KdbxFixture(KdbxTestFixtures.Ver4Aes),
            "Ver4Argon2" to KdbxFixture(KdbxTestFixtures.Ver4Argon2),
            "Ver4WithBinaries" to KdbxFixture(KdbxTestFixtures.Ver4WithBinaries),
            "GroupsAndEntries" to KdbxFixture(KdbxTestFixtures.GroupsAndEntries),
        )

        fixtures.forEach { (label, fixture) ->
            val original = loadDatabase(fixture)
            val xml = original.encodeAsXml()
            val decoded = KeePassDatabase.decodeFromXml(
                xmlData = xml.encodeToByteArray(),
                credentials = credentials(),
            )

            assertDatabaseContentEquals(original, decoded, label)
        }
    }

    @Test
    fun binaryRoundTripPreservesEdgeCaseContent() {
        val credentials = Credentials.from(EncryptedValue.fromString("test-password"))
        val original = buildEdgeCaseDatabase(credentials)
        val sink = Buffer()
        original.encodeTo(sink)
        val decoded = KeePassDatabase.decode(sink, credentials)

        assertDatabaseContentEquals(original, decoded, "edge-cases")
    }

    @Test
    fun xmlExportImportRoundTripPreservesEdgeCaseContent() {
        val credentials = Credentials.from(EncryptedValue.fromString("test-password"))
        val original = buildEdgeCaseDatabase(credentials)
        val xml = original.encodeAsXml()
        val decoded = KeePassDatabase.decodeFromXml(
            xmlData = xml.encodeToByteArray(),
            credentials = credentials,
        )

        assertDatabaseContentEquals(original, decoded, "edge-cases-xml")
    }

    @Test
    fun sparseXmlBinaryIdsSurviveKdbx4SaveAndReopen() {
        val credentials = Credentials.from(EncryptedValue.fromString("test-password"))
        val secondContent = "second attachment".encodeToByteArray()
        val xml = """
            <KeePassFile>
              <Meta>
                <Binaries>
                  <Binary ID="0">Zmlyc3Q=</Binary>
                  <Binary ID="2">c2Vjb25kIGF0dGFjaG1lbnQ=</Binary>
                </Binaries>
              </Meta>
              <Root>
                <Group>
                  <UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID>
                  <Name>Root</Name>
                  <Entry>
                    <UUID>AQAAAAAAAAAAAAAAAAAAAA==</UUID>
                    <Binary>
                      <Key>second.txt</Key>
                      <Value Ref="2"/>
                    </Binary>
                  </Entry>
                </Group>
                <DeletedObjects/>
              </Root>
            </KeePassFile>
        """.trimIndent()
        val imported = assertIs<KeePassDatabase.Ver4x>(
            KeePassDatabase.decodeFromXml(
                xmlData = xml.encodeToByteArray(),
                credentials = credentials,
            ),
        ).let { database ->
            database.copy(
                header = database.header.copy(
                    kdfParameters = KdfParameters.Aes(
                        rounds = 1U,
                        seed = ByteArray(32) { it.toByte() }.toByteString(),
                    ),
                ),
            )
        }

        val encoded = Buffer()
        imported.encodeTo(encoded)
        val reopened = KeePassDatabase.decode(encoded, credentials)
        val reference = reopened.content.group.entries.single().binaries.single()

        assertEquals("second.txt", reference.name)
        assertEquals(2, reopened.binaries.size)
        assertContentEquals(secondContent, reopened.binaries[reference.hash]?.getContent())
    }

    private fun buildEdgeCaseDatabase(credentials: Credentials): KeePassDatabase.Ver4x {
        fun secret(value: String): EntryValue =
            EntryValue.Encrypted(EncryptedValue.fromString(value))

        val attachment = BinaryData.Uncompressed(
            memoryProtection = false,
            rawContent = ByteArray(64) { (it * 7).toByte() },
        )
        val historyEntry = Entry(
            uuid = Uuid.parse("00000000-0000-0000-0000-00000000000a"),
            times = timeData(),
            fields = EntryFields.of(
                BasicField.Title() to EntryValue.Plain("Old title"),
                BasicField.Password() to secret("old secret"),
            ),
        )
        val entry = Entry(
            uuid = Uuid.parse("00000000-0000-0000-0000-00000000000b"),
            icon = PredefinedIcon.Warning,
            foregroundColor = "#FF0000",
            backgroundColor = "#00FF00",
            overrideUrl = "cmd://firefox \"{URL}\"",
            times = timeData(
                expiryTime = Instant.fromEpochSeconds(1893456000),
                expires = true,
                usageCount = 42,
            ),
            autoType = AutoTypeData(
                enabled = true,
                obfuscation = AutoTypeObfuscation.UseClipboard,
                defaultSequence = "{USERNAME}{TAB}{PASSWORD}{ENTER}",
                items = listOf(
                    AutoTypeItem(
                        window = "Login — *",
                        keystrokeSequence = "{PASSWORD}{ENTER}",
                    ),
                ),
            ),
            fields = EntryFields.of(
                BasicField.Title() to EntryValue.Plain(
                    "Ampersand & <angle> \"quotes\" 'apostrophe'",
                ),
                BasicField.UserName() to EntryValue.Plain("emoji 🔑 user 𝔘𝔫𝔦𝔠𝔬𝔡𝔢"),
                BasicField.Password() to secret("p@ss <&> 🗝 word"),
                BasicField.Url() to EntryValue.Plain("https://example.com/?a=1&b=2"),
                BasicField.Notes() to EntryValue.Plain(
                    "line1\nline2 with  double  spaces\n\ttabbed line",
                ),
                "Custom <Field> & Name" to EntryValue.Plain("custom & value"),
                "Empty Field" to EntryValue.Plain(""),
                "Whitespace Field" to EntryValue.Plain("  leading and trailing  "),
                "CRLF Field" to EntryValue.Plain("windows line1\r\nwindows line2"),
                "Protected Custom" to secret("hidden > value"),
            ),
            tags = listOf("tag one", "tag&two", "tag<3>"),
            binaries = listOf(
                BinaryReference(
                    hash = attachment.hash,
                    name = "attachment <&> 🎁.bin",
                ),
            ),
            history = listOf(historyEntry),
            customData = mapOf(
                "entry-data & key" to CustomDataValue("entry <data> value"),
            ),
            previousParentGroup = Uuid.parse("00000000-0000-0000-0000-00000000000c"),
            qualityCheck = false,
        )
        val childGroup = Group(
            uuid = Uuid.parse("00000000-0000-0000-0000-00000000000d"),
            name = "Child & <Group> 🚀",
            notes = "group notes\nsecond line",
            icon = PredefinedIcon.Folder,
            times = timeData(),
            expanded = false,
            defaultAutoTypeSequence = "{USERNAME}{ENTER}",
            enableAutoType = GroupOverride.Disabled,
            enableSearching = GroupOverride.Enabled,
            lastTopVisibleEntry = entry.uuid,
            entries = listOf(entry),
            customData = mapOf(
                "group-data" to CustomDataValue("group value"),
            ),
        )
        val customIconUuid = Uuid.parse("00000000-0000-0000-0000-00000000000e")

        val database = KeePassDatabase.Ver4x.create(
            rootName = "Root & <Name>",
            meta = Meta(
                generator = "Round-trip test",
                settingsChanged = Instant.fromEpochSeconds(1700000001),
                name = "Test database & <friends>",
                nameChanged = Instant.fromEpochSeconds(1700000002),
                description = "Description with entities & <tags>\nand a second line",
                descriptionChanged = Instant.fromEpochSeconds(1700000003),
                defaultUser = "default & user",
                defaultUserChanged = Instant.fromEpochSeconds(1700000004),
                memoryProtection = setOf(MemoryProtectionFlag.Password),
                color = "#AABBCC",
                masterKeyChanged = Instant.fromEpochSeconds(1700000005),
                recycleBinEnabled = true,
                recycleBinUuid = Uuid.parse("00000000-0000-0000-0000-00000000000f"),
                recycleBinChanged = Instant.fromEpochSeconds(1700000006),
                customIcons = mapOf(
                    customIconUuid to CustomIcon(
                        data = ByteArray(16) { it.toByte() },
                        name = "icon & name",
                        lastModified = Instant.fromEpochSeconds(1700000007),
                    ),
                ),
                customData = mapOf(
                    "meta-data & key" to CustomDataValue("meta <data> value"),
                ),
            ),
            credentials = credentials,
        )
        return database.copy(
            header = database.header.copy(
                kdfParameters = KdfParameters.Aes(
                    rounds = 1U,
                    seed = ByteArray(32) { it.toByte() }.toByteString(),
                ),
            ),
            content = database.content.copy(
                group = database.content.group.copy(
                    times = timeData(),
                    groups = listOf(childGroup),
                ),
                deletedObjects = listOf(
                    DeletedObject(
                        id = Uuid.parse("00000000-0000-0000-0000-000000000010"),
                        deletionTime = Instant.fromEpochSeconds(1700000008),
                    ),
                ),
            ),
            innerHeader = database.innerHeader.copy(
                binaries = linkedMapOf(attachment.hash to attachment),
            ),
        )
    }

    /**
     * KDBX stores timestamps with seconds precision, so the fixtures
     * use whole-second instants to survive round-trips unchanged.
     */
    private fun timeData(
        expiryTime: Instant? = null,
        expires: Boolean = false,
        usageCount: Int = 0,
    ) = TimeData(
        creationTime = Instant.fromEpochSeconds(1690000001),
        lastAccessTime = Instant.fromEpochSeconds(1690000002),
        lastModificationTime = Instant.fromEpochSeconds(1690000003),
        locationChanged = Instant.fromEpochSeconds(1690000004),
        expiryTime = expiryTime,
        expires = expires,
        usageCount = usageCount,
    )

    private fun assertDatabaseContentEquals(
        expected: KeePassDatabase,
        actual: KeePassDatabase,
        label: String,
    ) {
        assertEquals(
            expected.content.meta.normalize(),
            actual.content.meta.normalize(),
            "Meta mismatch for $label",
        )
        assertEquals(
            expected.content.group.normalize(),
            actual.content.group.normalize(),
            "Group tree mismatch for $label",
        )
        assertEquals(
            expected.content.deletedObjects,
            actual.content.deletedObjects,
            "Deleted objects mismatch for $label",
        )
        assertEquals(
            expected.binaries.keys.toList(),
            actual.binaries.keys.toList(),
            "Binary hashes mismatch for $label",
        )
        expected.binaries.values.zip(actual.binaries.values).forEach { (a, b) ->
            assertContentEquals(
                a.getContent(),
                b.getContent(),
                "Binary content mismatch for $label",
            )
        }
    }

    /**
     * Header hash and binaries are transport-level state which is
     * legitimately volatile across re-encoding.
     */
    private fun Meta.normalize() = copy(
        headerHash = null,
        binaries = linkedMapOf(),
    )

    private fun Group.normalize(): Group = copy(
        groups = groups.map { it.normalize() },
        entries = entries.map { it.normalize() },
    )

    /**
     * Encrypted values carry unique stream-cipher salts, and plain-XML
     * round-trips wrap memory-protected plain values into encrypted
     * ones, so equality is only meaningful over the decrypted content.
     */
    private fun Entry.normalize(): Entry = copy(
        fields = fields.mapValues { (_, value) ->
            when (value) {
                is EntryValue.Plain -> value
                is EntryValue.Encrypted -> EntryValue.Plain(value.content)
            }
        },
        history = history.map { it.normalize() },
    )

    private fun loadDatabase(fixture: KdbxFixture): KeePassDatabase {
        val source = Buffer().apply {
            write(fixture.content.decodeBase64ToArray())
        }
        return KeePassDatabase.decode(
            source = source,
            credentials = credentials(),
            cipherProviders = fixture.cipherProviders,
        )
    }

    private fun credentials() = Credentials.from(EncryptedValue.fromString("1"))

    private data class KdbxFixture(
        val content: String,
        val cipherProviders: List<CipherProvider> = BaseCiphers.entries,
    )
}
