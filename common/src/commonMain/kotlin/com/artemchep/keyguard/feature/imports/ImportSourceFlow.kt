package com.artemchep.keyguard.feature.imports

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.common.service.file.FileAccessToken
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.imports.BackupImportError
import com.artemchep.keyguard.common.service.imports.BackupImportService
import com.artemchep.keyguard.common.service.imports.FileImportInput
import com.artemchep.keyguard.common.service.imports.ImportError
import com.artemchep.keyguard.common.service.imports.ImportFormat
import com.artemchep.keyguard.common.service.imports.ImportOpenError
import com.artemchep.keyguard.common.service.imports.ImportOpenResult
import com.artemchep.keyguard.common.service.imports.ImportPlan
import com.artemchep.keyguard.common.service.imports.ImportResult
import com.artemchep.keyguard.common.service.imports.ImportService
import com.artemchep.keyguard.common.service.imports.OpenedImportSource
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.feature.auth.common.TextFieldHandle
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/**
 * A source the user picked, before it is opened: what a password prompt
 * opens again, and what a retry picks another one like.
 */
internal sealed interface ImportSourceRef {
    data class File(
        val format: ImportFormat,
        val file: FilePickerResult,
    ) : ImportSourceRef

    data class Backup(
        val store: BackupStoreConfig,
    ) : ImportSourceRef
}

/**
 * The picked file's copy, if [this] is a file: what the steps hold from the
 * pick until the flow is done reading the file.
 */
internal val ImportSourceRef.fileCopy: Held.FileCopy?
    get() = (this as? ImportSourceRef.File)
        ?.let { Held.FileCopy(it.file.uri.toString()) }

/**
 * The file and backup side of the import flow: opens a picked source —
 * asking for its password when it needs one — lists its snapshots if it has
 * several, and parses one.
 *
 * A backup asks for its password as it opens, a file as it parses; either
 * way the prompt opens the source again with the password. The steps hold
 * the source while the flow may still read from it.
 */
internal class ImportSourceFlow(
    private val scope: RememberStateFlowScope,
    private val steps: ImportSteps,
    private val importService: ImportService,
    private val backupImportService: BackupImportService,
    private val fileService: FileService,
    private val dateFormatter: DateFormatter,
    /** The password field, shared with the other sources. */
    private val passwordHandle: TextFieldHandle,
    /** Runs whenever a source starts opening. */
    private val onOpen: () -> Unit,
) {
    /**
     * Opens [ref] with [password], then lists its snapshots, or parses the
     * only one. A failure offers [retry].
     */
    fun open(
        ref: ImportSourceRef,
        password: Password?,
        retry: Retry = Retry.Pick(ref),
        expectedStep: Step = steps.value,
    ) {
        lateinit var loading: Step.Loading
        // Started once its loading step is current, so that the step exists
        // before the job can land on it.
        val job = scope.screenScope.launch(start = CoroutineStart.LAZY) {
            when (val result = openSource(ref, password)) {
                is ImportOpenResult.Failure -> land(
                    loading = loading,
                    step = scope.failureStep(ref, result.error, retry),
                )

                is ImportOpenResult.Success -> {
                    val source = Held.Source(result.source)
                    if (result.source.snapshots.isEmpty()) {
                        // Held by a step before it is read, so that whatever
                        // replaces the step closes it.
                        val parsing = Step.Loading(held = loading.held + source)
                        if (!steps.move(loading, parsing)) return@launch
                        land(
                            loading = parsing,
                            step = parse(ref, source, snapshotId = null, retry = retry),
                        )
                    } else {
                        land(
                            loading = loading,
                            step = result.source.toSnapshotsStep(
                                dateFormatter = dateFormatter,
                                ref = ref,
                                source = source,
                            ),
                        )
                    }
                }
            }
        }
        loading = Step.Loading(
            held = setOfNotNull(
                Held.Opening(job),
                ref.fileCopy,
            ),
        )
        if (!steps.move(expectedStep, loading)) return
        onOpen()
        job.start()
    }

    fun confirmPassword(step: Step.Password) {
        val text = passwordHandle.sink.value.text
        open(
            ref = step.source,
            password = Password(text),
            expectedStep = step,
        )
    }

    fun importSnapshot(step: Step.Snapshots) {
        val source = step.held.firstNotNullOfOrNull { it as? Held.Source }
            ?: return
        lateinit var loading: Step.Loading
        val job = scope.screenScope.launch(start = CoroutineStart.LAZY) {
            land(
                loading = loading,
                step = parse(
                    ref = step.source,
                    source = source,
                    snapshotId = step.selectedId,
                    retry = Retry.Snapshots(step),
                ),
            )
        }
        loading = Step.Loading(held = step.held + Held.Opening(job))
        if (!steps.move(step, loading)) return
        job.start()
    }

    /**
     * Leaves the password prompt or the snapshot list for the start.
     */
    fun cancel() {
        passwordHandle.setText("")
        steps.set(Step.Start)
    }

    private suspend fun openSource(
        ref: ImportSourceRef,
        password: Password?,
    ): ImportOpenResult = when (ref) {
        is ImportSourceRef.File -> {
            val input = FileImportInput(
                fileService = fileService,
                uri = ref.file.uri.toString(),
                accessToken = ref.file.accessToken?.let(::FileAccessToken),
                name = ref.file.name,
            )
            val source = importService.open(
                format = ref.format.id,
                input = input,
                password = password,
            )
            ImportOpenResult.Success(source)
        }

        is ImportSourceRef.Backup -> backupImportService.open(ref.store, password)
    }

    /**
     * Parses the snapshot [snapshotId] of the [source] picked as [ref];
     * never throws: a source that cannot be read becomes an error step that
     * offers [retry].
     */
    private suspend fun parse(
        ref: ImportSourceRef,
        source: Held.Source,
        snapshotId: String?,
        retry: Retry,
    ): Step {
        val result = runCatchingNonFatal {
            source.source.parse(
                snapshotId = snapshotId,
                now = Clock.System.now(),
            )
        }.getOrElse {
            ImportResult.Failure(ImportError.Unreadable)
        }
        return when (result) {
            is ImportResult.Success -> scope.planStep(
                plan = result.plan,
                sourcePackageName = null,
                emptyMessage = scope.translate(ref.emptyRes()),
                // Only a commit of attachments reads the source again.
                held = if (result.plan.items.any { it.attachments.isNotEmpty() }) {
                    setOfNotNull(source, ref.fileCopy)
                } else {
                    emptySet()
                },
            )

            is ImportResult.Failure -> scope.failureStep(ref, result.error, retry)
        }
    }

    /**
     * Moves from [loading] to [step], unless something newer took over.
     */
    private fun land(
        loading: Step.Loading,
        step: Step,
    ) {
        if (!steps.move(loading, step)) return
        if (step !is Step.Password) {
            passwordHandle.setText("")
        }
    }
}

private fun OpenedImportSource.toSnapshotsStep(
    dateFormatter: DateFormatter,
    ref: ImportSourceRef,
    source: Held.Source,
) = Step.Snapshots(
    source = ref,
    snapshots = snapshots.map { snapshot ->
        Step.Snapshots.Snapshot(
            id = snapshot.id,
            createdAt = dateFormatter.formatDateTime(snapshot.createdAt),
            itemCount = snapshot.itemCount,
            attachmentCount = snapshot.attachmentCount,
        )
    },
    selectedId = snapshots.first().id,
    held = setOf(source),
)

private suspend fun TranslatorScope.failureStep(
    ref: ImportSourceRef,
    error: ImportOpenError,
    retry: Retry,
): Step = when (error) {
    ImportError.PasswordRequired -> Step.Password(
        source = ref,
    )

    ImportError.WrongPassword -> Step.Password(
        source = ref,
        wrong = true,
    )

    else -> Step.Error(
        message = translateError(ref, error),
        retry = retry,
    )
}

/** What the password of [this] source unlocks. */
internal suspend fun TranslatorScope.passwordText(
    source: ImportSourceRef,
): String = when (source) {
    is ImportSourceRef.File -> translate(Res.string.import_password_text, source.format.title)
    is ImportSourceRef.Backup -> translate(Res.string.import_backup_password_text)
}

internal suspend fun RememberStateFlowScope.planStep(
    plan: ImportPlan,
    sourcePackageName: String?,
    emptyMessage: String,
    /** What the review holds, see [Step.Review.held]. */
    held: Set<Held> = emptySet(),
): Step = if (!plan.isImportable) {
    Step.Empty(
        skips = plan.skips,
        message = emptyMessage,
    )
} else {
    Step.Review(
        plan = plan,
        sourcePackageName = sourcePackageName,
        untitledLabel = translate(importUntitledRes),
        held = held,
    )
}

/**
 * A format row: the product name, then the other products that export the
 * same file and the file type, e.g. `Edge, Brave · CSV`.
 */
internal fun ImportFormat.toUiFormat(
    onClick: () -> Unit,
) = ImportState.Stage.Start.Format(
    key = id.name,
    title = title,
    text = listOfNotNull(
        aliases
            .takeIf { it.isNotEmpty() }
            ?.joinToString(),
        extensions
            .takeIf { it.isNotEmpty() }
            ?.joinToString { it.uppercase() },
    )
        .takeIf { it.isNotEmpty() }
        ?.joinToString(separator = " · "),
    onClick = onClick,
)

internal fun Step.Snapshots.toSnapshotsStage(
    actions: ImportStageActions,
) = ImportState.Stage.Snapshots(
    items = snapshots.map { snapshot ->
        ImportState.Stage.Snapshots.Snapshot(
            key = snapshot.id,
            createdAt = snapshot.createdAt,
            itemCount = snapshot.itemCount,
            attachmentCount = snapshot.attachmentCount,
            selected = snapshot.id == selectedId,
            onClick = {
                actions.onSnapshotSelect(this, snapshot.id)
            },
        )
    },
    onConfirm = {
        actions.onSnapshotConfirm(this)
    },
    onCancel = actions.onSnapshotCancel,
)

private fun ImportSourceRef.emptyRes(): StringResource = when (this) {
    is ImportSourceRef.File -> Res.string.import_empty_file_label
    is ImportSourceRef.Backup -> Res.string.import_backup_empty_label
}

private suspend fun TranslatorScope.translateError(
    ref: ImportSourceRef,
    error: ImportOpenError,
): String = when (ref) {
    // The file messages name the format the file was read as.
    is ImportSourceRef.File -> translate(error.messageRes(backup = false), ref.format.title)
    is ImportSourceRef.Backup -> translate(error.messageRes(backup = true))
}

private fun ImportOpenError.messageRes(
    backup: Boolean,
): StringResource = when (this) {
    BackupImportError.NotFound -> Res.string.import_backup_error_not_found
    BackupImportError.AccessDenied -> Res.string.import_backup_error_access_denied
    BackupImportError.Unreachable -> Res.string.import_backup_error_unreachable
    BackupImportError.Empty -> Res.string.import_backup_error_empty
    ImportError.TooLarge -> Res.string.import_error_too_large
    ImportError.UnsupportedEncryption -> Res.string.import_error_unsupported_encryption
    // A file format has no versions to tell apart; a backup tells neither
    // apart from a damaged one.
    ImportError.Parse,
    ImportError.UnsupportedVersion,
    -> if (backup) Res.string.import_backup_error_unreadable else Res.string.import_error_parse

    ImportError.Unreadable -> if (backup) {
        Res.string.import_backup_error_unreadable
    } else {
        Res.string.import_error_unreadable
    }
    // Answered with a password prompt rather than a message.
    ImportError.PasswordRequired,
    ImportError.WrongPassword,
    -> Res.string.import_error_wrong_password
}
