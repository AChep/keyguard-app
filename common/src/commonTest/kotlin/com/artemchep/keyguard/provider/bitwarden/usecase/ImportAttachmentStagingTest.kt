package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.imports.ImportAttachmentSink
import com.artemchep.keyguard.common.service.imports.ImportAttachmentSource
import com.artemchep.keyguard.common.service.imports.ImportPlan
import com.artemchep.keyguard.common.service.imports.importSkips
import com.artemchep.keyguard.common.service.imports.impl.ImportTooLargeException
import com.artemchep.keyguard.provider.bitwarden.upload.FailingPendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadTarget
import com.artemchep.keyguard.provider.bitwarden.upload.assertKeyCleared
import com.artemchep.keyguard.test.IdentityBase64Service
import com.artemchep.keyguard.test.TestCryptoGenerator
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readString
import kotlinx.io.writeString

class ImportAttachmentStagingTest {
    private val coordinator = RecordingPendingUploadCoordinator()

    private val staging = ImportAttachmentStaging(
        accountId = AccountId(ACCOUNT_ID),
        pendingUploadCoordinator = coordinator,
        cryptoGenerator = SequentialCryptoGenerator(),
        base64Service = IdentityBase64Service,
    )

    @Test
    fun `every attachment is encrypted into a pending upload of its own`() = runTest {
        val plan = plan(
            listOf(attachment("a", "a.txt"), attachment("b", "b.txt")),
            emptyList(),
            listOf(attachment("c", "c.txt")),
        )
        val source = MemoryAttachmentSource(
            "a" to "alpha",
            "b" to "bravo",
            "c" to "charlie",
        )

        val staged = staging.discardOnFailure {
            staging.stage(plan, source)
        }

        assertEquals(listOf(setOf("a", "b", "c")), source.reads)
        assertEquals(
            listOf(listOf("a.txt", "b.txt"), emptyList(), listOf("c.txt")),
            staged.map { attachments -> attachments.map { it.name } },
        )
        val byName = staged.flatten().associateBy { it.name }
        listOf("a.txt" to "alpha", "b.txt" to "bravo", "c.txt" to "charlie")
            .forEach { (name, content) ->
                val attachment = byName.getValue(name)
                val call = coordinator.stageCalls.single { it.file == attachment.file }
                assertEquals(content, call.content)
                assertEquals(
                    PendingUploadTarget.NewCipherAttachment(
                        accountId = ACCOUNT_ID,
                        attachmentId = attachment.id,
                    ),
                    call.target,
                )
                // The key the file is encrypted with, which the save stores.
                assertEquals(call.fileKey, attachment.keyBase64)
            }
        assertEquals(3, staged.flatten().map { it.id }.toSet().size)
        assertEquals(3, staged.flatten().map { it.keyBase64 }.toSet().size)
        coordinator.fileKeyRefs.forEach(::assertKeyCleared)
        // Handed to the vault, not deleted.
        assertEquals(emptyList(), coordinator.deleteCalls)
    }

    @Test
    fun `a file planned for two items is read and staged once per item`() = runTest {
        val plan = plan(
            listOf(attachment("a", "first.txt")),
            listOf(attachment("a", "second.txt")),
        )
        val source = MemoryAttachmentSource("a" to "alpha")

        val staged = staging.stage(plan, source)

        // An archive streams an entry only once per read.
        assertEquals(listOf(setOf("a"), setOf("a")), source.reads)
        val (first, second) = staged.map { it.single() }
        assertEquals("first.txt", first.name)
        assertEquals("second.txt", second.name)
        assertNotEquals(first.file, second.file)
        assertNotEquals(first.keyBase64, second.keyBase64)
        assertEquals(listOf("alpha", "alpha"), coordinator.stageCalls.map { it.content })
    }

    @Test
    fun `a plan without attachments never reads its source`() = runTest {
        val plan = plan(emptyList(), emptyList())

        val staged = staging.stage(plan, MemoryAttachmentSource())

        assertEquals(listOf(emptyList<CreateRequest.Attachment.Staged>(), emptyList()), staged)
        assertEquals(emptyList(), coordinator.stageCalls)
    }

    @Test
    fun `a failed read deletes every file staged before it`() = runTest {
        val plan = plan(
            listOf(attachment("a", "a.txt"), attachment("missing", "missing.txt")),
        )
        // Holds only the first of the two files the plan lists.
        val source = MemoryAttachmentSource("a" to "alpha")

        assertFailsWith<NoSuchElementException> {
            staging.discardOnFailure {
                staging.stage(plan, source)
            }
        }

        assertEquals(coordinator.stageCalls.map { it.file }, coordinator.deleteCalls)
        assertEquals(1, coordinator.deleteCalls.size)
    }

    @Test
    fun `a cancelled commit deletes every file staged so far`() = runTest {
        val plan = plan(listOf(attachment("a", "a.txt")))
        val source = MemoryAttachmentSource("a" to "alpha")

        assertFailsWith<CancellationException> {
            staging.discardOnFailure {
                staging.stage(plan, source)
                throw CancellationException("The vault locked.")
            }
        }

        assertEquals(coordinator.stageCalls.map { it.file }, coordinator.deleteCalls)
        assertEquals(1, coordinator.deleteCalls.size)
    }

    @Test
    fun `an attachment of exactly the limit is staged`() = runTest {
        val staging = cappedStaging(maxAttachmentBytes = 5)
        val plan = plan(listOf(attachment("a", "a.txt")))

        staging.stage(plan, MemoryAttachmentSource("a" to "alpha"))

        assertEquals(listOf("alpha"), coordinator.stageCalls.map { it.content })
    }

    @Test
    fun `an attachment over the limit fails the commit whatever its listed size`() = runTest {
        val staging = cappedStaging(maxAttachmentBytes = 5)
        val plan = plan(
            listOf(attachment("a", "a.txt")),
            // An archive lists the size of an entry, but only inflating it
            // tells how large it is.
            listOf(attachment("b", "b.txt", size = 1)),
        )
        val source = MemoryAttachmentSource(
            "a" to "alpha",
            "b" to "bravo!",
        )

        assertFailsWith<ImportTooLargeException> {
            staging.discardOnFailure {
                staging.stage(plan, source)
            }
        }

        assertEquals(coordinator.stageCalls.map { it.file }, coordinator.deleteCalls)
        assertEquals(1, coordinator.deleteCalls.size)
    }

    private fun cappedStaging(
        maxAttachmentBytes: Long,
    ) = ImportAttachmentStaging(
        accountId = AccountId(ACCOUNT_ID),
        pendingUploadCoordinator = coordinator,
        cryptoGenerator = SequentialCryptoGenerator(),
        base64Service = IdentityBase64Service,
        maxAttachmentBytes = maxAttachmentBytes,
    )
}

private const val ACCOUNT_ID = "account-1"

private val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")

private fun attachment(
    ref: String,
    name: String,
    size: Long? = null,
) = ImportPlan.Attachment(
    ref = ref,
    name = name,
    size = size,
)

private fun plan(
    vararg attachments: List<ImportPlan.Attachment>,
) = ImportPlan(
    source = ImportPlan.Source(),
    folders = emptyList(),
    items = attachments.map { list ->
        ImportPlan.Item(
            request = CreateRequest(
                type = DSecret.Type.SecureNote,
                now = TEST_INSTANT,
            ),
            folderKey = null,
            attachments = list,
        )
    },
    skips = importSkips(),
)

/**
 * A source of the given files, recording the refs of every read.
 */
private class MemoryAttachmentSource(
    vararg files: Pair<String, String>,
) : ImportAttachmentSource {
    private val files = files.toMap()

    val reads = mutableListOf<Set<String>>()

    override suspend fun <T> extract(
        refs: Set<String>,
        sink: ImportAttachmentSink<T>,
    ): Map<String, T> {
        reads += refs
        return refs.associateWith { ref ->
            val source = Buffer().apply { writeString(files.getValue(ref)) }
            sink.write(ref, source)
        }
    }
}

private class RecordingPendingUploadCoordinator : PendingUploadCoordinator by FailingPendingUploadCoordinator {
    data class StageCall(
        val target: PendingUploadTarget,
        val content: String,
        val fileKey: String,
        val file: PendingUploadFile,
    )

    val stageCalls = mutableListOf<StageCall>()
    val fileKeyRefs = mutableListOf<ByteArray>()
    val deleteCalls = mutableListOf<PendingUploadFile>()

    override suspend fun stage(
        target: PendingUploadTarget,
        source: Source,
        fileKey: ByteArray,
    ): PendingUploadFile {
        val content = source.readString()
        val file = PendingUploadFile(
            path = "/pending/${target.namespace}/${target.fileId}.bin",
            plainSize = content.length.toLong(),
            encryptedSize = content.length.toLong() + 32L,
        )
        stageCalls += StageCall(
            target = target,
            content = content,
            fileKey = fileKey.decodeToString(),
            file = file,
        )
        fileKeyRefs += fileKey
        return file
    }

    override suspend fun delete(
        pendingUpload: PendingUploadFile,
    ) {
        deleteCalls += pendingUpload
    }
}

private class SequentialCryptoGenerator : TestCryptoGenerator() {
    private var next = 0

    override fun uuid(): String = "id-${next++}"

    override fun seed(length: Int): ByteArray = "key-${next++}".encodeToByteArray()
}
