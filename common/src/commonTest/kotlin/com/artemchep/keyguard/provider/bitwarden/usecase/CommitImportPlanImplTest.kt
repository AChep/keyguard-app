package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.FolderHierarchyMode
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.imports.ImportAttachmentSink
import com.artemchep.keyguard.common.service.imports.ImportAttachmentSource
import com.artemchep.keyguard.common.service.imports.ImportPlan
import com.artemchep.keyguard.common.service.imports.importSkips
import com.artemchep.keyguard.common.usecase.AddCipher
import com.artemchep.keyguard.common.usecase.AddFolder
import com.artemchep.keyguard.common.usecase.AddFolderRequest
import com.artemchep.keyguard.common.usecase.CommitImportPlanRequest
import com.artemchep.keyguard.common.usecase.CommitImportPlanResult
import com.artemchep.keyguard.common.usecase.ResolveFolderHierarchyMode
import com.artemchep.keyguard.feature.confirmation.organization.FolderInfo
import com.artemchep.keyguard.provider.bitwarden.upload.FailingPendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadTarget
import com.artemchep.keyguard.test.IdentityBase64Service
import com.artemchep.keyguard.test.TestCryptoGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readString
import kotlinx.io.writeString

class CommitImportPlanImplTest {
    private val addFolder = CommitRecordingAddFolder()
    private val addCipher = CommitRecordingAddCipher()
    private val coordinator = CommitRecordingPendingUploadCoordinator()

    private val commitImportPlan = CommitImportPlanImpl(
        resolveFolderHierarchyMode = object : ResolveFolderHierarchyMode {
            override fun invoke(accountId: AccountId): IO<FolderHierarchyMode> = {
                FolderHierarchyMode.ParentId
            }
        },
        addFolder = addFolder,
        addCipher = addCipher,
        cryptoGenerator = CommitSequentialCryptoGenerator(),
        pendingUploadCoordinator = coordinator,
        base64Service = IdentityBase64Service,
    )

    @Test
    fun `items are written to the account, filed and titled`() = runTest {
        val plan = plan(
            folders = listOf(ImportPlan.Folder("work", null, "Work")),
            items = listOf(
                item(title = " ", folderKey = "work"),
                item(title = "Root"),
            ),
        )

        val result = commit(plan)

        assertEquals(CommitImportPlanResult(itemCount = 2, folderCount = 1), result)
        val folderId = addFolder.ids.single()
        val written = addCipher.requests.single()
        assertEquals(listOf(UNTITLED, "Root"), written.map { it.title })
        assertEquals(
            listOf(FolderInfo.Id(folderId), FolderInfo.None),
            written.map { it.ownership2?.folder },
        )
        assertTrue(written.all { it.ownership2?.accountId == ACCOUNT_ID })
    }

    @Test
    fun `archived and trashed items are created archived and trashed in the one write`() = runTest {
        val plan = plan(
            items = listOf(
                item(title = "Live"),
                item(title = "Archived").copy(archived = true),
                item(title = "Trashed").copy(trashed = true),
            ),
        )

        commit(plan)

        val written = addCipher.requests.single()
        assertEquals(listOf(null, TEST_INSTANT, null), written.map { it.archivedDate })
        assertEquals(listOf(null, null, TEST_INSTANT), written.map { it.deletedDate })
    }

    @Test
    fun `a failed folder write discards the staged attachments and writes no items`() = runTest {
        addFolder.fail = true
        val plan = plan(
            folders = listOf(ImportPlan.Folder("work", null, "Work")),
            items = listOf(
                item(title = "With file", folderKey = "work").copy(
                    attachments = listOf(ImportPlan.Attachment("a", "a.txt", null)),
                ),
            ),
        )

        assertFailsWith<IllegalStateException> {
            commit(plan, attachments = CommitMemoryAttachmentSource("a" to "alpha"))
        }

        assertEquals(1, coordinator.staged.size)
        assertEquals(coordinator.staged, coordinator.deleted)
        assertTrue(addCipher.requests.isEmpty())
    }

    @Test
    fun `a plan with attachments and no source fails before any write`() = runTest {
        val plan = plan(
            folders = listOf(ImportPlan.Folder("work", null, "Work")),
            items = listOf(
                item(title = "With file").copy(
                    attachments = listOf(ImportPlan.Attachment("a", "a.txt", null)),
                ),
            ),
        )

        assertFailsWith<IllegalStateException> {
            commit(plan, attachments = null)
        }

        assertTrue(addFolder.ids.isEmpty())
        assertTrue(addCipher.requests.isEmpty())
    }

    private suspend fun commit(
        plan: ImportPlan,
        attachments: ImportAttachmentSource? = null,
    ): CommitImportPlanResult = commitImportPlan(
        CommitImportPlanRequest(
            plan = plan,
            accountId = AccountId(ACCOUNT_ID),
            untitledTitle = UNTITLED,
            attachments = attachments,
        ),
    ).bind()
}

private const val ACCOUNT_ID = "account-1"

private const val UNTITLED = "Untitled"

private val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")

private fun plan(
    folders: List<ImportPlan.Folder> = emptyList(),
    items: List<ImportPlan.Item>,
) = ImportPlan(
    source = ImportPlan.Source(),
    folders = folders,
    items = items,
    skips = importSkips(),
)

private fun item(
    title: String,
    folderKey: String? = null,
) = ImportPlan.Item(
    request = CreateRequest(
        type = DSecret.Type.SecureNote,
        title = title,
        now = TEST_INSTANT,
    ),
    folderKey = folderKey,
)

private class CommitRecordingAddFolder : AddFolder {
    var fail = false

    val ids = mutableListOf<String>()

    override fun invoke(requests: Collection<AddFolderRequest>): IO<List<String>> = {
        check(!fail) { "AddFolder failed" }
        requests.map { "folder-${ids.size}" }
            .also { ids += it }
    }
}

private class CommitRecordingAddCipher : AddCipher {
    val requests = mutableListOf<List<CreateRequest>>()

    val ids = mutableListOf<List<String>>()

    override fun invoke(requests: Map<String?, CreateRequest>): IO<List<String>> = {
        this.requests += requests.values.toList()
        requests.keys.map { "cipher-$it" }
            .also { ids += it }
    }
}

/**
 * A source of the given files.
 */
private class CommitMemoryAttachmentSource(
    vararg files: Pair<String, String>,
) : ImportAttachmentSource {
    private val files = files.toMap()

    override suspend fun <T> extract(
        refs: Set<String>,
        sink: ImportAttachmentSink<T>,
    ): Map<String, T> = refs.associateWith { ref ->
        val source = Buffer().apply { writeString(files.getValue(ref)) }
        sink.write(ref, source)
    }
}

private class CommitRecordingPendingUploadCoordinator : PendingUploadCoordinator by FailingPendingUploadCoordinator {
    val staged = mutableListOf<PendingUploadFile>()

    val deleted = mutableListOf<PendingUploadFile>()

    override suspend fun stage(
        target: PendingUploadTarget,
        source: Source,
        fileKey: ByteArray,
    ): PendingUploadFile {
        val content = source.readString()
        return PendingUploadFile(
            path = "/pending/${target.namespace}/${target.fileId}.bin",
            plainSize = content.length.toLong(),
            encryptedSize = content.length.toLong() + 32L,
        ).also { staged += it }
    }

    override suspend fun delete(
        pendingUpload: PendingUploadFile,
    ) {
        deleted += pendingUpload
    }
}

private class CommitSequentialCryptoGenerator : TestCryptoGenerator() {
    private var next = 0

    override fun uuid(): String = "id-${next++}"

    override fun seed(length: Int): ByteArray = "key-${next++}".encodeToByteArray()
}
