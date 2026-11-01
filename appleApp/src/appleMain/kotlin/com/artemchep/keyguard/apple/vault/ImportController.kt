package com.artemchep.keyguard.apple.vault

import com.artemchep.keyguard.apple.add.AddFilePickerRequest
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.filePickerResultOf
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.core.onFilePickerResult
import com.artemchep.keyguard.apple.core.toFilePickerRequest
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.transfer.TransferSkippedNote
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.feature.imports.ImportCountLine
import com.artemchep.keyguard.feature.imports.ImportRoute
import com.artemchep.keyguard.feature.imports.ImportState
import com.artemchep.keyguard.feature.imports.countLines
import com.artemchep.keyguard.feature.imports.importScreenStateProducer
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.scope.Scope

/** The step of the import flow an [ImportSnapshot] renders. */
enum class ImportStageKind {
    START,
    PASSWORD,
    LOADING,
    SNAPSHOTS,
    REVIEW,
    EMPTY,
    DONE,
    ERROR,
}

/** One file format; [actionId] opens the file picker, or is `null` when the row is inert. */
data class ImportFormatSnapshot(
    val key: String,
    val title: String,
    val text: String?,
    val actionId: String?,
)

/**
 * One kind of location a Keyguard backup is read from: [kind] is `local`, `webdav` or `s3`, the
 * kinds of the backup wizard; [actionId] asks for the location.
 */
data class ImportBackupLocationSnapshot(
    val kind: String,
    val actionId: String?,
)

/** One snapshot of an open backup, newest first; [actionId] selects it. */
data class ImportBackupSnapshotRow(
    val key: String,
    val title: String,
    val text: String,
    val selected: Boolean,
    val actionId: String?,
)

/**
 * One count line of the review or the result. [kind] picks the icon: `login`, `passkey`, `otp`,
 * `card`, `identity`, `note`, `sshKey`, `gpgKey`, `environment` or `folder`.
 */
data class ImportCountSnapshot(
    val kind: String,
    val text: String,
)

/**
 * One item about to be imported. [index] addresses the item in
 * [com.artemchep.keyguard.apple.KeyguardCore.setImportItemSelected]; [credentials] are
 * `TransferItem.Kind` names, in badge order.
 */
data class ImportItemSnapshot(
    val index: Int,
    val title: String,
    val credentials: List<String>,
    val selected: Boolean,
    val canToggle: Boolean,
)

/** One item behind an expanded skipped note; [count] is above one when it lost several things. */
data class ImportSkippedTitleSnapshot(
    val text: String,
    val count: Int,
)

/**
 * One warning row: what was lost and how much of it. [titles] are listed only while [expanded];
 * [remainingText] covers what the titles do not account for.
 */
data class ImportSkippedNoteSnapshot(
    val id: String,
    val text: String,
    val expanded: Boolean,
    val toggleActionId: String?,
    val titles: List<ImportSkippedTitleSnapshot>,
    val remainingText: String?,
)

/**
 * The flat model of one import entry. Only the fields of [stage] carry content. [filePickerRequest]
 * is the file the producer waits for; Swift presents it once and answers through
 * [com.artemchep.keyguard.apple.KeyguardCore.resolveImportFilePicker] or
 * [com.artemchep.keyguard.apple.KeyguardCore.cancelImportFilePicker].
 */
data class ImportSnapshot(
    val loaded: Boolean,
    val title: String,
    val stage: ImportStageKind,
    val formats: List<ImportFormatSnapshot>,
    val backupLocations: List<ImportBackupLocationSnapshot>,
    val canLearnMore: Boolean,
    val passwordText: String?,
    val passwordValue: String,
    val passwordRevision: Int,
    val passwordError: String?,
    val canConfirmPassword: Boolean,
    val canCancelPassword: Boolean,
    val snapshots: List<ImportBackupSnapshotRow>,
    val canConfirmSnapshot: Boolean,
    val canCancelSnapshot: Boolean,
    /** The review counts, or the folders line of the result; only the non-zero ones. */
    val counts: List<ImportCountSnapshot>,
    val items: List<ImportItemSnapshot>,
    val selectedCount: Int,
    val importing: Boolean,
    val canImport: Boolean,
    val skipped: List<ImportSkippedNoteSnapshot>,
    /** The empty or error message, or the item sentence of the result. */
    val message: String?,
    val canClose: Boolean,
    val canRetry: Boolean,
    val filePickerRequest: AddFilePickerRequest?,
) {
    companion object {
        val empty = ImportSnapshot(
            loaded = false,
            title = "",
            stage = ImportStageKind.LOADING,
            formats = emptyList(),
            backupLocations = emptyList(),
            canLearnMore = false,
            passwordText = null,
            passwordValue = "",
            passwordRevision = 0,
            passwordError = null,
            canConfirmPassword = false,
            canCancelPassword = false,
            snapshots = emptyList(),
            canConfirmSnapshot = false,
            canCancelSnapshot = false,
            counts = emptyList(),
            items = emptyList(),
            selectedCount = 0,
            importing = false,
            canImport = false,
            skipped = emptyList(),
            message = null,
            canClose = false,
            canRetry = false,
            filePickerRequest = null,
        )
    }
}

private const val IMPORT_ACTION_LEARN_MORE = "import:learnMore"
private const val IMPORT_ACTION_PASSWORD_CONFIRM = "import:password:confirm"
private const val IMPORT_ACTION_PASSWORD_CANCEL = "import:password:cancel"
private const val IMPORT_ACTION_CONFIRM = "import:confirm"
private const val IMPORT_ACTION_CLOSE = "import:close"
private const val IMPORT_ACTION_RETRY = "import:retry"
private const val IMPORT_ACTION_FORMAT_PREFIX = "import:format:"
private const val IMPORT_ACTION_BACKUP_PREFIX = "import:backup:"
private const val IMPORT_ACTION_SNAPSHOT_PREFIX = "import:snapshot:"
private const val IMPORT_ACTION_SNAPSHOT_CONFIRM = "import:snapshots:confirm"
private const val IMPORT_ACTION_SNAPSHOT_CANCEL = "import:snapshots:cancel"
private const val IMPORT_ACTION_SKIPPED_PREFIX = "import:skipped:"

/**
 * The input side of one import entry: the live state for the password and selection edits,
 * which carry values the action-handler map cannot, and the file request the producer waits on.
 */
internal class ImportInput {
    private class PendingPick(
        val request: AddFilePickerRequest,
        val onResult: (FilePickerResult?) -> Unit,
    )

    /** The latest producer state; the producer is cold, so it is collected once into here. */
    val state = MutableStateFlow<ImportState?>(null)

    private val pendingPick = MutableStateFlow<PendingPick?>(null)
    private var pickCounter = 0

    val filePickerRequestFlow = pendingPick.map { it?.request }

    /** Replaces the pending request; the replaced one is answered as cancelled. */
    fun offer(intent: FilePickerIntent<*>) {
        val requestId = "ifp:${pickCounter++}"
        val pick = PendingPick(
            request = intent.toFilePickerRequest(requestId, ::AddFilePickerRequest),
            onResult = intent.onFilePickerResult,
        )
        pendingPick.getAndUpdate { pick }?.onResult?.invoke(null)
    }

    fun resolve(requestId: String, uri: String, name: String?, size: Long, accessToken: String?) {
        take(requestId)?.onResult?.invoke(filePickerResultOf(uri, name, size, accessToken))
    }

    fun cancel(requestId: String) {
        take(requestId)?.onResult?.invoke(null)
    }

    fun setPassword(text: String) {
        val stage = state.value?.stage as? ImportState.Stage.Password
            ?: return
        stage.password.onChange?.invoke(text)
    }

    fun setItemSelected(index: Int, selected: Boolean) {
        val stage = state.value?.stage as? ImportState.Stage.Review
            ?: return
        stage.items.getOrNull(index)?.onSelectedChange?.invoke(selected)
    }

    private fun take(requestId: String): PendingPick? {
        val pick = pendingPick.value
            ?.takeIf { it.request.requestId == requestId }
            ?: return null
        return pick.takeIf { pendingPick.compareAndSet(it, null) }
    }
}

/**
 * Runs the shared import producer for one navigation entry. Strings and plurals are resolved
 * here, so the Swift view only lays the snapshot out.
 */
internal class ImportController(
    private val ctx: CoreContext,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun produceImportInto(
        scope: CoroutineScope,
        sessionKoin: Scope,
        accountId: String,
        configuredBackup: Boolean,
        interceptor: (NavigationIntent) -> Boolean,
        publish: suspend (ImportSnapshot, Map<String, () -> Unit>, ImportInput) -> Unit,
    ) {
        val input = ImportInput()
        val stateScope = ctx.koin.newHeadlessStateFlowScope(
            name = "import",
            scope = scope,
            navigationInterceptor = interceptor,
        )
        val leContext = ctx.koin.get<LeContext>()
        val title = stateScope.translate(Res.string.import_header_title)
        val producerFlow = sessionKoin.importProducer(
            stateScope = stateScope,
            args = ImportRoute.Args(
                accountId = AccountId(accountId),
                configuredBackup = configuredBackup,
            ),
        )
        scope.launch {
            producerFlow.collect { loadable ->
                input.state.value = loadable.getOrNull()
            }
        }
        scope.launch {
            input.state.filterNotNull()
                .map { it.sideEffects.filePickerIntentFlow }
                .distinctUntilChanged()
                .flatMapLatest { it }
                .collect(input::offer)
        }
        combine(
            input.state,
            input.filePickerRequestFlow,
        ) { state, request ->
            if (state == null) {
                ImportSnapshot.empty to emptyMap<String, () -> Unit>()
            } else {
                val handlers = LinkedHashMap<String, () -> Unit>()
                val snapshot = stateScope.buildSnapshot(
                    state = state,
                    title = title,
                    leContext = leContext,
                    handlers = handlers,
                ).copy(
                    filePickerRequest = request,
                )
                snapshot to handlers
            }
        }.collectOnMain { (snapshot, handlers) ->
            publish(snapshot, handlers, input)
        }
    }
}

/** The shared producer, with its dependencies from the vault session. */
private suspend fun Scope.importProducer(
    stateScope: RememberStateFlowScope,
    args: ImportRoute.Args,
) = stateScope.importScreenStateProducer(
    args = args,
    getProfiles = get(),
    cxfImportService = get(),
    commitImportPlan = get(),
    transport = getOrNull(),
    importService = get(),
    backupImportService = get(),
    backupConfigRepository = get(),
    fileService = get(),
    dirsService = get(),
    dateFormatter = get(),
)

private suspend fun TranslatorScope.buildSnapshot(
    state: ImportState,
    title: String,
    leContext: LeContext,
    handlers: MutableMap<String, () -> Unit>,
): ImportSnapshot {
    val base = ImportSnapshot.empty.copy(
        loaded = true,
        title = title,
    )
    return when (val stage = state.stage) {
        is ImportState.Stage.Start -> startSnapshot(base, stage, handlers)
        is ImportState.Stage.Password -> passwordSnapshot(base, stage, handlers)
        is ImportState.Stage.Snapshots -> snapshotsSnapshot(base, stage, handlers)
        is ImportState.Stage.Loading -> base.copy(stage = ImportStageKind.LOADING)
        is ImportState.Stage.Review -> reviewSnapshot(base, stage, handlers)
        is ImportState.Stage.Empty -> {
            stage.onClose?.let { handlers[IMPORT_ACTION_CLOSE] = it }
            base.copy(
                stage = ImportStageKind.EMPTY,
                message = stage.message,
                skipped = skippedNotes(stage.skipped, handlers),
                canClose = stage.onClose != null,
            )
        }

        is ImportState.Stage.Done -> doneSnapshot(base, stage, handlers)
        is ImportState.Stage.Error -> {
            stage.onRetry?.let { onRetry ->
                handlers[IMPORT_ACTION_RETRY] = { onRetry(leContext) }
            }
            base.copy(
                stage = ImportStageKind.ERROR,
                message = stage.message,
                canRetry = stage.onRetry != null,
            )
        }
    }
}

private fun startSnapshot(
    base: ImportSnapshot,
    stage: ImportState.Stage.Start,
    handlers: MutableMap<String, () -> Unit>,
): ImportSnapshot {
    stage.onLearnMore?.let { handlers[IMPORT_ACTION_LEARN_MORE] = it }
    return base.copy(
        stage = ImportStageKind.START,
        formats = stage.formats.map { format ->
            val actionId = format.onClick?.let { onClick ->
                val id = IMPORT_ACTION_FORMAT_PREFIX + format.key
                handlers[id] = onClick
                id
            }
            ImportFormatSnapshot(
                key = format.key,
                title = format.title,
                text = format.text,
                actionId = actionId,
            )
        },
        backupLocations = stage.backupLocations.map { location ->
            val kind = location.kind.name.lowercase()
            val actionId = location.onClick?.let { onClick ->
                val id = IMPORT_ACTION_BACKUP_PREFIX + kind
                handlers[id] = onClick
                id
            }
            ImportBackupLocationSnapshot(
                kind = kind,
                actionId = actionId,
            )
        },
        canLearnMore = stage.onLearnMore != null,
    )
}

private suspend fun TranslatorScope.passwordSnapshot(
    base: ImportSnapshot,
    stage: ImportState.Stage.Password,
    handlers: MutableMap<String, () -> Unit>,
): ImportSnapshot {
    stage.onConfirm?.let { handlers[IMPORT_ACTION_PASSWORD_CONFIRM] = it }
    stage.onCancel?.let { handlers[IMPORT_ACTION_PASSWORD_CANCEL] = it }
    return base.copy(
        stage = ImportStageKind.PASSWORD,
        passwordText = stage.text,
        passwordValue = stage.password.text,
        passwordRevision = stage.password.textRevision,
        passwordError = stage.password.error,
        canConfirmPassword = stage.onConfirm != null,
        canCancelPassword = stage.onCancel != null,
    )
}

private suspend fun TranslatorScope.snapshotsSnapshot(
    base: ImportSnapshot,
    stage: ImportState.Stage.Snapshots,
    handlers: MutableMap<String, () -> Unit>,
): ImportSnapshot {
    stage.onConfirm?.let { handlers[IMPORT_ACTION_SNAPSHOT_CONFIRM] = it }
    stage.onCancel?.let { handlers[IMPORT_ACTION_SNAPSHOT_CANCEL] = it }
    return base.copy(
        stage = ImportStageKind.SNAPSHOTS,
        snapshots = stage.items.map { item ->
            val actionId = item.onClick?.let { onClick ->
                val id = IMPORT_ACTION_SNAPSHOT_PREFIX + item.key
                handlers[id] = onClick
                id
            }
            val itemsText = translate(
                Res.plurals.import_backup_snapshot_items,
                item.itemCount,
                item.itemCount,
            )
            val text = if (item.attachmentCount > 0) {
                val attachmentsText = translate(
                    Res.plurals.import_backup_snapshot_attachments,
                    item.attachmentCount,
                    item.attachmentCount,
                )
                "$itemsText · $attachmentsText"
            } else {
                itemsText
            }
            ImportBackupSnapshotRow(
                key = item.key,
                title = item.createdAt,
                text = text,
                selected = item.selected,
                actionId = actionId,
            )
        },
        canConfirmSnapshot = stage.onConfirm != null,
        canCancelSnapshot = stage.onCancel != null,
    )
}

private suspend fun TranslatorScope.reviewSnapshot(
    base: ImportSnapshot,
    stage: ImportState.Stage.Review,
    handlers: MutableMap<String, () -> Unit>,
): ImportSnapshot {
    // Like the Compose screen, the review offers no cancel, and the debug payload
    // dump only exists for a credential transfer, which Apple does not offer.
    stage.onConfirm?.let { handlers[IMPORT_ACTION_CONFIRM] = it }
    return base.copy(
        stage = ImportStageKind.REVIEW,
        counts = countSnapshots(stage.countLines()),
        items = stage.items.mapIndexed { index, item ->
            ImportItemSnapshot(
                index = index,
                title = item.item.title,
                credentials = item.item.credentials.map { it.name },
                selected = item.selected,
                canToggle = item.onSelectedChange != null,
            )
        },
        selectedCount = stage.items.count { it.selected },
        importing = stage.isImporting,
        canImport = stage.onConfirm != null,
        skipped = skippedNotes(stage.skipped, handlers),
    )
}

private suspend fun TranslatorScope.doneSnapshot(
    base: ImportSnapshot,
    stage: ImportState.Stage.Done,
    handlers: MutableMap<String, () -> Unit>,
): ImportSnapshot {
    stage.onClose?.let { handlers[IMPORT_ACTION_CLOSE] = it }
    return base.copy(
        stage = ImportStageKind.DONE,
        message = translate(
            Res.plurals.import_success_text,
            stage.itemCount,
            stage.itemCount,
        ),
        counts = countSnapshots(stage.countLines()),
        canClose = stage.onClose != null,
    )
}

private suspend fun TranslatorScope.countSnapshots(
    lines: List<ImportCountLine>,
): List<ImportCountSnapshot> = lines.map { line ->
    ImportCountSnapshot(
        kind = line.kind.snapshotKind(),
        text = translate(line.res, line.count, line.count),
    )
}

private fun ImportCountLine.Kind.snapshotKind(): String = when (this) {
    ImportCountLine.Kind.Login -> "login"
    ImportCountLine.Kind.Passkey -> "passkey"
    ImportCountLine.Kind.Otp -> "otp"
    ImportCountLine.Kind.Card -> "card"
    ImportCountLine.Kind.Identity -> "identity"
    ImportCountLine.Kind.Note -> "note"
    ImportCountLine.Kind.SshKey -> "sshKey"
    ImportCountLine.Kind.GpgKey -> "gpgKey"
    ImportCountLine.Kind.Environment -> "environment"
    ImportCountLine.Kind.Folder -> "folder"
}

private suspend fun TranslatorScope.skippedNotes(
    notes: List<TransferSkippedNote>,
    handlers: MutableMap<String, () -> Unit>,
): List<ImportSkippedNoteSnapshot> = notes.map { note ->
    val toggleActionId = note.onToggle?.let { onToggle ->
        val id = IMPORT_ACTION_SKIPPED_PREFIX + note.id
        handlers[id] = onToggle
        id
    }
    val remaining = note.remainingCount
    ImportSkippedNoteSnapshot(
        id = note.id,
        text = translate(note.res, note.count, note.count),
        expanded = note.expanded,
        toggleActionId = toggleActionId,
        titles = if (note.expanded) {
            note.titles.map { ImportSkippedTitleSnapshot(text = it.text, count = it.count) }
        } else {
            emptyList()
        },
        remainingText = if (note.expanded && remaining > 0) {
            translate(Res.plurals.skipped_and_more_note, remaining, remaining)
        } else {
            null
        },
    )
}
