package app.keemobile.kotpass.database

import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.cryptography.format.CipherProvider
import app.keemobile.kotpass.cryptography.format.TwofishCipher
import app.keemobile.kotpass.database.KdbxTestFixtures.GroupsAndEntries
import app.keemobile.kotpass.database.KdbxTestFixtures.InvalidReferences
import app.keemobile.kotpass.database.KdbxTestFixtures.Ver3Aes
import app.keemobile.kotpass.database.KdbxTestFixtures.Ver4Aes
import app.keemobile.kotpass.database.KdbxTestFixtures.Ver4Argon2
import app.keemobile.kotpass.database.KdbxTestFixtures.Ver4Twofish
import app.keemobile.kotpass.database.KdbxTestFixtures.Ver4WithBinaries
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.io.decodeBase64ToArray
import app.keemobile.kotpass.models.DatabaseElement
import app.keemobile.kotpass.models.Entry
import kotlinx.io.Buffer
import kotlinx.io.write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class KeePassDatabaseFixtureTest {
    @Test
    fun decodesAesTwofishAndArgon2Fixtures() {
        val fixtures = listOf(
            KdbxFixture(Ver3Aes),
            KdbxFixture(Ver4Aes),
            KdbxFixture(Ver4Twofish, cipherProviders = BaseCiphers.entries + TwofishCipher),
            KdbxFixture(Ver4Argon2),
        )

        fixtures.forEach { fixture ->
            val database = loadDatabase(fixture.content, fixture.cipherProviders)

            assertEquals("New", database.content.group.name)
        }
    }

    @Test
    fun roundTripsAesFixturesThroughSourceAndSinkApi() {
        val fixtures = listOf(
            Ver3Aes,
            Ver4Aes,
            Ver4Argon2,
        )

        fixtures.forEach { fixture ->
            val database = loadDatabase(fixture)
            val sink = Buffer()

            database.encodeTo(sink)

            val decoded = KeePassDatabase.decode(sink, credentials())
            assertEquals(database.content.group.name, decoded.content.group.name)
        }
    }

    @Test
    fun exportsFixtureBinariesToPlainXml() {
        val database = loadDatabase(Ver4WithBinaries)

        assertEquals(2, database.binaries.size)

        val rawXml = database.encodeAsXml()
        assertTrue(rawXml.contains("Binary ID=\"0\""))
        assertTrue(rawXml.contains("Binary ID=\"1\""))
    }

    @Test
    fun decodesInvalidBinaryReferencesWithoutDroppingContent() {
        val database = loadDatabase(InvalidReferences)

        assertEquals("New", database.content.group.name)
        assertTrue(database.binaries.isEmpty())
        database.traverse { element ->
            if (element is Entry) {
                assertTrue(element.binaries.isEmpty())
            }
        }
    }

    @Test
    fun preservesGroupsAndEntriesFixtureIds() {
        val database = loadDatabase(GroupsAndEntries)
        val seenUuids = mutableSetOf<Uuid>()

        database.traverse { element: DatabaseElement ->
            seenUuids += element.uuid
        }

        val expectedUuids = setOf(
            Uuid.parse("c997344c-952b-e02b-06a6-29510ce71a12"),
            Uuid.parse("928f39d5-e1b6-88a9-f4f1-b60b36399186"),
            Uuid.parse("36023bf1-4278-b680-ee34-653e7a1348bc"),
            Uuid.parse("ba06b36c-c7c8-8f8c-655f-a39dc403c6fa"),
            Uuid.parse("208e2034-9fc5-c955-5cbf-46d892123316"),
            Uuid.parse("4e805fdc-8305-7909-2574-d8e2ae2e520a"),
        )
        assertTrue(seenUuids.containsAll(expectedUuids))

        val matchingEntries = database
            .findEntries { entry ->
                entry[BasicField.Title]?.content?.contains("Entry") == true
            }
            .sumOf { (_, entries) -> entries.size }
        assertEquals(3, matchingEntries)
    }

    private fun loadDatabase(
        fixture: String,
        cipherProviders: List<CipherProvider> = BaseCiphers.entries,
    ): KeePassDatabase {
        val source = Buffer().apply {
            write(fixture.decodeBase64ToArray())
        }
        return KeePassDatabase.decode(
            source = source,
            credentials = credentials(),
            cipherProviders = cipherProviders,
        )
    }

    private fun credentials() = Credentials.from(EncryptedValue.fromString("1"))

    private data class KdbxFixture(
        val content: String,
        val cipherProviders: List<CipherProvider> = BaseCiphers.entries,
    )
}
