package com.artemchep.keyguard.feature.imports

import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.imports.BackupImportService
import com.artemchep.keyguard.common.service.imports.ImportAttachmentSink
import com.artemchep.keyguard.common.service.imports.ImportError
import com.artemchep.keyguard.common.service.imports.ImportFormat
import com.artemchep.keyguard.common.service.imports.ImportFormatId
import com.artemchep.keyguard.common.service.imports.ImportInput
import com.artemchep.keyguard.common.service.imports.ImportOpenResult
import com.artemchep.keyguard.common.service.imports.ImportPlan
import com.artemchep.keyguard.common.service.imports.ImportResult
import com.artemchep.keyguard.common.service.imports.ImportService
import com.artemchep.keyguard.common.service.imports.ImportSourceSnapshot
import com.artemchep.keyguard.common.service.imports.OpenedImportSource
import com.artemchep.keyguard.common.service.imports.importSkips
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.feature.auth.common.TextCell
import com.artemchep.keyguard.feature.auth.common.TextFieldHandle
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.platform.leParseUri
import java.lang.reflect.Proxy
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

internal class ImportFlowTestSupport(testScope: CoroutineScope) {
    val password = TextFieldHandle(MutableStateFlow(TextCell("secret")))
    val format = ImportFormat(ImportFormatId.Bitwarden, "Bitwarden", extensions = listOf("json"))
    val requests = mutableListOf<CompletableDeferred<ImportResult>>()
    val deletedFiles = mutableListOf<String>()
    val opened = mutableListOf<TestBackup>()
    val result = ImportResult.Success(
        ImportPlan(
            source = ImportPlan.Source(),
            folders = listOf(ImportPlan.Folder("folder", null, "Folder")),
            items = emptyList(),
            skips = importSkips(),
        ),
    )
    val attachmentResult = ImportResult.Success(
        ImportPlan(
            source = ImportPlan.Source(),
            folders = emptyList(),
            items = listOf(
                ImportPlan.Item(
                    request = CreateRequest(
                        title = "Item",
                        now = Instant.fromEpochSeconds(0),
                    ),
                    folderKey = null,
                    attachments = listOf(ImportPlan.Attachment("ref", "file.txt", 1)),
                ),
            ),
            skips = importSkips(),
        ),
    )
    val unreadable = ImportResult.Failure(ImportError.Unreadable)
    private val scope = proxy<RememberStateFlowScope> { name, _ ->
        when (name) {
            "getScreenScope", "getAppScope" -> testScope
            "translate" -> "Translated"
            else -> error("Unexpected scope call: $name")
        }
    }
    private val fileService = proxy<FileService> { name, args ->
        check(name == "deleteManagedSourceFile")
        deletedFiles += args[0] as String
        Unit
    }
    val steps = scope.importSteps(fileService)
    val sources = ImportSourceFlow(
        scope = scope,
        steps = steps,
        importService = object : ImportService {
            override val formats = listOf(format)
            override fun open(
                format: ImportFormatId,
                input: ImportInput,
                password: Password?,
            ): OpenedImportSource = TestFile()
        },
        backupImportService = object : BackupImportService {
            override suspend fun open(
                store: BackupStoreConfig,
                password: Password?,
            ): ImportOpenResult {
                val backup = TestBackup()
                opened += backup
                return ImportOpenResult.Success(backup)
            }
        },
        fileService = fileService,
        dateFormatter = proxy<DateFormatter> { _, _ -> "Date" },
        passwordHandle = password,
        onOpen = {},
    )

    fun filePrompt(name: String = "export.json") = Step.Password(
        ImportSourceRef.File(
            format,
            FilePickerResult(leParseUri("file:///tmp/$name"), name, null),
        ),
    )

    fun backupPrompt() = Step.Password(ImportSourceRef.Backup(BackupStoreConfig.Local("/tmp/backup")))

    /** The file of a [filePrompt]. */
    fun uriOf(prompt: Step.Password) = (prompt.source as ImportSourceRef.File).file.uri.toString()

    private suspend fun awaitParse(): ImportResult {
        val request = CompletableDeferred<ImportResult>()
        requests += request
        return request.await()
    }

    inner class TestFile : OpenedImportSource {
        override val snapshots = emptyList<ImportSourceSnapshot>()
        override suspend fun parse(snapshotId: String?, now: Instant): ImportResult = awaitParse()
        override suspend fun <T> extract(
            refs: Set<String>,
            sink: ImportAttachmentSink<T>,
        ): Map<String, T> = error("Unexpected extraction")

        override suspend fun close() = Unit
    }

    inner class TestBackup : OpenedImportSource {
        var closed = false
        override val snapshots = listOf(
            ImportSourceSnapshot("snapshot", Instant.fromEpochSeconds(0), 1, 0),
        )
        override suspend fun parse(snapshotId: String?, now: Instant): ImportResult = awaitParse()
        override suspend fun <T> extract(
            refs: Set<String>,
            sink: ImportAttachmentSink<T>,
        ): Map<String, T> = error("Unexpected extraction")

        override suspend fun close() {
            closed = true
        }
    }
}

private inline fun <reified T> proxy(
    crossinline call: (String, Array<out Any?>) -> Any?,
): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
    call(method.name, args ?: emptyArray())
} as T
