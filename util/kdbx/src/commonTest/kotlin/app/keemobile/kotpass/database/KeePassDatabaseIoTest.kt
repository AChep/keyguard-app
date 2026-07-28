package app.keemobile.kotpass.database

import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.models.DatabaseContent
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.xml.DefaultXmlContentParser
import app.keemobile.kotpass.xml.XmlContentParser
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.io.write
import okio.BufferedSink
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class KeePassDatabaseIoTest {
    @Test
    fun encodeToSinkAndDecodeFromSourceRoundTripsVer4xDatabase() {
        val credentials = Credentials.from(EncryptedValue.fromString("test-password"))
        val database = buildDatabase(credentials)
        val sink = Buffer()

        database.encodeTo(sink)
        val encoded = sink.readByteArray()
        val source = Buffer().apply {
            write(encoded)
        }

        val decoded = KeePassDatabase.decode(
            source = source,
            credentials = credentials,
        )

        val decodedVer4 = assertIs<KeePassDatabase.Ver4x>(decoded)
        assertEquals("Test database", decodedVer4.content.meta.name)
        assertEquals("Root", decodedVer4.content.group.name)

        val entry = decodedVer4.getEntryBy {
            fields.title?.content == "Example"
        }
        assertEquals("user", entry?.fields?.userName?.content)
        assertEquals("secret", entry?.fields?.password?.content)
    }

    @Test
    fun decodeFromSourceDoesNotCloseCallerOwnedSource() {
        val credentials = Credentials.from(EncryptedValue.fromString("test-password"))
        val encoded = buildDatabase(credentials).encode()
        val rawSource = TrackingRawSource(encoded)

        KeePassDatabase.decode(
            source = rawSource.buffered(),
            credentials = credentials,
        )

        assertFalse(rawSource.closed)
    }

    @Test
    fun encodeToSinkDoesNotCloseCallerOwnedSink() {
        val credentials = Credentials.from(EncryptedValue.fromString("test-password"))
        val rawSink = TrackingRawSink()

        buildDatabase(credentials).encodeTo(rawSink.buffered())

        assertFalse(rawSink.closed)
        assertTrue(rawSink.bytes().isNotEmpty())
    }

    @Test
    fun encodeFailureClosesGzipLayerWithoutClosingCallerOwnedSink() {
        val credentials = Credentials.from(EncryptedValue.fromString("test-password"))
        val writeFailure = IllegalStateException("XML write failed")
        val closeFailure = IllegalArgumentException("Gzip close failed")
        val rawSink = TrackingRawSink(flushFailure = closeFailure)
        val contentParser =
            object : XmlContentParser by DefaultXmlContentParser {
                override fun marshalContentTo(
                    context: XmlContext.Encode,
                    content: DatabaseContent,
                    sink: BufferedSink,
                    pretty: Boolean,
                ) {
                    sink.writeUtf8("<partial>")
                    throw writeFailure
                }
            }
        val database = buildDatabase(credentials).let { database ->
            database.copy(
                header = database.header.copy(
                    compression = DatabaseHeader.Compression.GZip,
                ),
            )
        }

        val actual = assertFailsWith<IllegalStateException> {
            database.encodeTo(
                sink = rawSink.buffered(),
                contentParser = contentParser,
            )
        }

        assertSame(writeFailure, actual)
        assertEquals(listOf(closeFailure), actual.suppressedExceptions)
        assertTrue(rawSink.flushCalls > 0)
        assertFalse(rawSink.closed)
    }

    private fun buildDatabase(
        credentials: Credentials,
    ): KeePassDatabase.Ver4x {
        val database = KeePassDatabase.Ver4x.create(
            rootName = "Root",
            meta = Meta(name = "Test database"),
            credentials = credentials,
        )
        val entry = Entry(
            uuid = Uuid.parse("00000000-0000-0000-0000-000000000001"),
            fields = EntryFields.of(
                BasicField.Title() to EntryValue.Plain("Example"),
                BasicField.UserName() to EntryValue.Plain("user"),
                BasicField.Password() to EntryValue.Encrypted(EncryptedValue.fromString("secret")),
            ),
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
                    entries = listOf(entry),
                ),
            ),
        )
    }
}

private class TrackingRawSource(
    bytes: ByteArray,
) : RawSource {
    private val buffer = Buffer().apply {
        write(bytes)
    }
    var closed = false

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long = buffer.readAtMostTo(sink, byteCount)

    override fun close() {
        closed = true
    }
}

private class TrackingRawSink(
    private val flushFailure: Throwable? = null,
) : RawSink {
    private val buffer = Buffer()
    var closed = false
    var flushCalls = 0

    override fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        buffer.write(source, byteCount)
    }

    override fun flush() {
        flushCalls++
        flushFailure?.let { throw it }
    }

    override fun close() {
        closed = true
    }

    fun bytes(): ByteArray = buffer.readByteArray()
}
