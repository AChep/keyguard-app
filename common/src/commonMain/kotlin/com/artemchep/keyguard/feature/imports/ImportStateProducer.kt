package com.artemchep.keyguard.feature.imports

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.exception.PremiumException
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.displayName
import com.artemchep.keyguard.common.service.credentialexchange.CredentialExchangeImportTransport
import com.artemchep.keyguard.common.service.credentialexchange.CxfImportService
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.backup.BackupConfigRepository
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.common.service.imports.BackupImportService
import com.artemchep.keyguard.common.service.imports.ImportFormat
import com.artemchep.keyguard.common.service.imports.ImportPlan
import com.artemchep.keyguard.common.service.imports.ImportService
import com.artemchep.keyguard.common.service.imports.ImportSkips
import com.artemchep.keyguard.common.service.imports.impl.ImportTooLargeException
import com.artemchep.keyguard.common.usecase.CommitImportPlan
import com.artemchep.keyguard.common.usecase.CommitImportPlanRequest
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.common.textFieldHandle
import com.artemchep.keyguard.feature.auth.common.toModel
import com.artemchep.keyguard.feature.transfer.toggleNote
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.StringResource
import org.koin.compose.currentKoinScope

internal const val IMPORT_DOCS_URL = "https://keyguard.dev/docs/migrating/"

@Composable
fun produceImportScreenState(
    args: ImportRoute.Args,
): Loadable<ImportState> = with(currentKoinScope()) {
    produceImportScreenState(
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
}

@Composable
fun produceImportScreenState(
    args: ImportRoute.Args,
    getProfiles: GetProfiles,
    cxfImportService: CxfImportService,
    commitImportPlan: CommitImportPlan,
    transport: CredentialExchangeImportTransport?,
    importService: ImportService,
    backupImportService: BackupImportService,
    backupConfigRepository: BackupConfigRepository,
    fileService: FileService,
    dirsService: DirsService,
    dateFormatter: DateFormatter,
): Loadable<ImportState> = produceScreenState(
    key = "import",
    initial = Loadable.Loading,
    args = arrayOf(
        args,
    ),
) {
    importScreenStateProducer(
        args = args,
        getProfiles = getProfiles,
        cxfImportService = cxfImportService,
        commitImportPlan = commitImportPlan,
        transport = transport,
        importService = importService,
        backupImportService = backupImportService,
        backupConfigRepository = backupConfigRepository,
        fileService = fileService,
        dirsService = dirsService,
        dateFormatter = dateFormatter,
    )
}

/**
 * The internal step of the linear import flow. UI callbacks are attached when
 * the step is mapped to a [ImportState.Stage].
 */
internal sealed interface Step {
    /**
     * What the step keeps open, released by [ImportSteps] once a step that
     * does not hold it replaces this one.
     */
    val held: Set<Held> get() = emptySet()

    data object Start : Step

    // Each operation owns its loading step; an older result cannot replace a newer one.
    class Loading(
        /** The open in flight, and whatever it reads from. */
        override val held: Set<Held> = emptySet(),
    ) : Step

    /**
     * The picked [source] is password-protected. [wrong] marks a password
     * that did not unlock it.
     */
    data class Password(
        val source: ImportSourceRef,
        val wrong: Boolean = false,
    ) : Step {
        // The prompt opens the very file again.
        override val held: Set<Held> get() = setOfNotNull(source.fileCopy)
    }

    /**
     * A source with several snapshots is open, e.g. a backup; the user picks
     * the snapshot to import.
     */
    data class Snapshots(
        /** The source the snapshots belong to, e.g. a backup location. */
        val source: ImportSourceRef,
        /** Newest first, never empty. */
        val snapshots: List<Snapshot>,
        val selectedId: String,
        /** The open source the snapshots are parsed from. */
        override val held: Set<Held> = emptySet(),
    ) : Step {
        data class Snapshot(
            val id: String,
            /** Formatted when the backup was opened. */
            val createdAt: String,
            val itemCount: Int,
            val attachmentCount: Int,
        )
    }

    data class Review(
        val plan: ImportPlan,
        val sourcePackageName: String?,
        /**
         * Stands in for a blank item title, translated at parse time because the
         * item rows below are derived here rather than per emission.
         */
        val untitledLabel: String,
        /**
         * Plan indexes are stable for the lifetime of the review and let duplicate
         * items be selected independently without inventing a display-derived id.
         */
        val selectedItemIndexes: Set<Int> = plan.items.indices.toSet(),
        val importing: Boolean = false,
        /** The source the commit reads the attachments from, if the plan has any. */
        override val held: Set<Held> = emptySet(),
    ) : Step {
        // Derived once per step get instead of on every state emission —
        // the plan is immutable while the review sits on screen. This matters more
        // for the rows than for the counts: a document can carry thousands of items.
        val selectedPlan = plan.selectItems(selectedItemIndexes)
        val counts = selectedPlan.toUiCounts()
        val items = plan.toUiItems(untitledLabel)
    }

    data class Empty(
        val skips: ImportSkips,
        val message: String,
    ) : Step

    data class Done(
        val itemCount: Int,
        val folderCount: Int,
    ) : Step

    data class Error(
        val message: String,
        val retry: Retry?,
    ) : Step {
        // A retry that returns to the snapshots keeps their source.
        override val held: Set<Held>
            get() = (retry as? Retry.Snapshots)?.step?.held.orEmpty()
    }
}

/**
 * What a failed step offers to try again, if anything.
 */
internal sealed interface Retry {
    data object Transfer : Retry

    /**
     * Asks for a source like [source] again: another file of its format, or
     * a backup location starting from its values.
     */
    data class Pick(
        val source: ImportSourceRef,
    ) : Retry

    /**
     * Opens the backup configured in Settings › Backups again.
     */
    data object ConfiguredBackup : Retry

    /**
     * Returns to the snapshots of the source that is still open.
     */
    data class Snapshots(
        val step: Step.Snapshots,
    ) : Retry
}

// The sources live in their own flows; what stays here is the commit and the
// wiring of every callback into the stage tree.
@Suppress("LongMethod")
suspend fun RememberStateFlowScope.importScreenStateProducer(
    args: ImportRoute.Args,
    getProfiles: GetProfiles,
    cxfImportService: CxfImportService,
    commitImportPlan: CommitImportPlan,
    transport: CredentialExchangeImportTransport?,
    importService: ImportService,
    backupImportService: BackupImportService,
    backupConfigRepository: BackupConfigRepository,
    fileService: FileService,
    dirsService: DirsService,
    dateFormatter: DateFormatter,
): Flow<Loadable<ImportState>> {
    val steps = importSteps(fileService)
    val commitExecutor = screenExecutor()

    val formats = importService.formats

    val wrongPasswordLabel = translate(Res.string.import_error_wrong_password)

    // Which skipped-items notes are open. Owned here rather than by the row,
    // because the rows are lazy and would forget the moment one scrolled out.
    val expandedNotesSink = mutablePersistedFlow<Set<String>>(
        key = "skipped.expanded",
    ) { emptySet() }

    // In memory only, and never part of a step: a step is a data class whose
    // `toString()` would carry the password into any log of it.
    val passwordHandle = textFieldHandle(key = "file.password")

    val transferFlow = ImportTransferFlow(
        scope = this,
        steps = steps,
        cxfImportService = cxfImportService,
        transport = transport,
        dirsService = dirsService,
        dateFormatter = dateFormatter,
    )
    val sourceFlow = ImportSourceFlow(
        scope = this,
        steps = steps,
        importService = importService,
        backupImportService = backupImportService,
        fileService = fileService,
        dateFormatter = dateFormatter,
        passwordHandle = passwordHandle,
        onOpen = transferFlow::dropPayload,
    )
    val filePickerIntentSink = EventFlow<FilePickerIntent<*>>()

    fun launchTransfer(context: LeContext) {
        transferFlow.start(context)
    }

    fun launchCommit(review: Step.Review) {
        // The commit outlives the screen, so it takes the source over: a
        // screen closed mid-commit must not close the file or the backup
        // while the commit still reads its attachments.
        val (importingReview, lease) = steps.claimCommit(review)
            ?: return
        // The review is over, so the dump action is gone; drop the vault with it.
        transferFlow.dropPayload()
        val io = ioEffect {
            val step = try {
                runCatchingNonFatal {
                    val request = CommitImportPlanRequest(
                        plan = review.selectedPlan,
                        accountId = args.accountId,
                        // The very string the review showed for a blank title, rather
                        // than a second translation of a second resource.
                        untitledTitle = review.untitledLabel,
                        attachments = lease.source,
                    )
                    val result = commitImportPlan(request)
                        .bind()
                    Step.Done(
                        itemCount = result.itemCount,
                        folderCount = result.folderCount,
                    )
                }.getOrElse { e ->
                    Step.Error(
                        message = translate(e.commitErrorRes()),
                        retry = null,
                    )
                }
            } finally {
                lease.release()
            }
            steps.move(importingReview, step)
            // Nobody sees the step of a screen that closed mid-commit.
            if (!screenScope.isActive) {
                showClosedScreenCommitResult(step)
            }
        }
        if (!commitExecutor.execute(io)) {
            // This executor is dedicated to commits, so a refusal should only
            // be possible if an earlier commit still owns it. The source is
            // released, so the review can not be confirmed again: end it with
            // an error rather than strand it on a spinner.
            lease.release()
            action {
                val error = Step.Error(
                    message = translate(Res.string.import_error_save),
                    retry = null,
                )
                steps.move(importingReview, error)
            }
        }
    }

    val actions = ImportStageActions(
        onImport = ::launchTransfer,
        onLearnMore = {
            navigate(
                NavigationIntent.NavigateToBrowser(
                    url = IMPORT_DOCS_URL,
                ),
            )
        },
        onPickFile = { format ->
            sourceFlow.pickFile(format, filePickerIntentSink)
        },
        onPickBackup = { store ->
            pickBackup(sourceFlow, store, filePickerIntentSink)
        },
        onOpenConfiguredBackup = {
            openConfiguredBackup(sourceFlow, steps, backupConfigRepository)
        },
        onPasswordConfirm = sourceFlow::confirmPassword,
        onPasswordCancel = sourceFlow::cancel,
        onSnapshotSelect = { step, snapshotId ->
            steps.move(step, step.copy(selectedId = snapshotId))
        },
        onSnapshotConfirm = sourceFlow::importSnapshot,
        onSnapshotCancel = sourceFlow::cancel,
        onShowSnapshots = { step ->
            // Only from the error that offered them: the snapshots are only
            // readable while that error holds their source.
            val error = steps.value as? Step.Error
            if (error != null && (error.retry as? Retry.Snapshots)?.step == step) {
                steps.move(error, step)
            }
        },
        onConfirm = ::launchCommit,
        onCancel = { review ->
            transferFlow.dropPayload()
            steps.cancelReview(review)
        },
        onItemSelectionChange = { review, index, selected ->
            steps.setReviewItemSelected(
                review = review,
                index = index,
                selected = selected,
            )
        },
        onToggleNote = expandedNotesSink::toggleNote,
        onClose = ::navigatePopSelf,
    )

    val profileFlow = getProfiles()
        .map { profiles ->
            profiles.firstOrNull { profile ->
                profile.accountId == args.accountId.id
            }
        }
        // Any vault change re-emits the profile list; don't rebuild the whole
        // stage tree unless the target profile itself changed.
        .distinctUntilChanged()

    if (args.configuredBackup) {
        actions.onOpenConfiguredBackup()
    }

    return combine(
        profileFlow,
        steps.flow,
        transferFlow.savePayloadFlow,
        expandedNotesSink,
        passwordHandle.sink,
    ) { profile, step, onSavePayload, expandedNotes, _ ->
        val passwordText = (step as? Step.Password)
            ?.let { passwordText(it.source) }
        val wrongPassword = (step as? Step.Password)?.wrong == true
        val stage = step.toStage(
            actions = actions,
            credentialExchange = transferFlow.isOffered,
            formats = formats,
            password = passwordHandle.toModel(
                error = wrongPasswordLabel.takeIf { wrongPassword },
            ),
            passwordText = passwordText,
            expandedNoteIds = expandedNotes,
            onSavePayload = onSavePayload,
        )
        val state = ImportState(
            accountTitle = profile?.displayName,
            stage = stage,
            sideEffects = ImportState.SideEffect(
                filePickerIntentFlow = filePickerIntentSink,
            ),
        )
        Loadable.Ok(state)
    }
}

/**
 * The placeholder shown and written for an imported item whose own title is blank.
 *
 * One resource, translated once per import and then carried on the review step, so the
 * row and the created vault item cannot disagree.
 */
internal val importUntitledRes: StringResource =
    Res.string.untitled

/**
 * `true` when committing the plan would write something — an item, a folder, or both.
 *
 * The gate into the review and the gate on the Import button must read the same
 * predicate.
 */
internal val ImportPlan.isImportable: Boolean
    get() = items.isNotEmpty() || folders.isNotEmpty()

/**
 * The callbacks [Step.toStage] wires into the stage tree. None has a default,
 * so a missing one fails to compile instead of rendering an action that does
 * nothing.
 */
internal class ImportStageActions(
    /** Starts a transfer from another app on this device. */
    val onImport: (LeContext) -> Unit,
    val onLearnMore: () -> Unit,
    val onPickFile: (ImportFormat) -> Unit,
    /** Asks for a backup location like the given one, starting from its values. */
    val onPickBackup: (BackupStoreConfig) -> Unit,
    val onOpenConfiguredBackup: () -> Unit,
    val onPasswordConfirm: (Step.Password) -> Unit,
    val onPasswordCancel: () -> Unit,
    val onSnapshotSelect: (Step.Snapshots, String) -> Unit,
    val onSnapshotConfirm: (Step.Snapshots) -> Unit,
    val onSnapshotCancel: () -> Unit,
    /** Returns to the snapshots of the source that is still open. */
    val onShowSnapshots: (Step.Snapshots) -> Unit,
    val onConfirm: (Step.Review) -> Unit,
    val onCancel: (Step.Review) -> Unit,
    val onItemSelectionChange: (Step.Review, Int, Boolean) -> Unit,
    /** Opens or closes a skipped-items note, keyed by reason name. */
    val onToggleNote: (String) -> Unit,
    val onClose: () -> Unit,
)

/**
 * Attaches the flow's callbacks to a step. `internal` so the gates it wires — in
 * particular whether the review offers an Import at all — can be driven without
 * Compose.
 */
internal fun Step.toStage(
    actions: ImportStageActions,
    /** Whether the platform credential transfer is offered as a source. */
    credentialExchange: Boolean,
    formats: List<ImportFormat>,
    /** The password field; required on a [Step.Password]. */
    password: TextFieldModel?,
    /** What the password unlocks; required where [password] is. */
    passwordText: String?,
    /** The skipped-items notes the user has opened, keyed by reason name. */
    expandedNoteIds: Set<String>,
    /**
     * Writes the raw payload to a file. `null` outside a debug build, and `null` once
     * the payload has been dropped — its absence is the whole gate.
     */
    onSavePayload: (() -> Unit)?,
): ImportState.Stage = when (this) {
    is Step.Start -> startStage(
        actions = actions,
        credentialExchange = credentialExchange,
        formats = formats,
    )

    is Step.Loading -> ImportState.Stage.Loading

    is Step.Password -> passwordStage(
        text = requireNotNull(passwordText),
        password = requireNotNull(password),
        onConfirm = { actions.onPasswordConfirm(this) },
        onCancel = actions.onPasswordCancel,
    )

    is Step.Snapshots -> toSnapshotsStage(
        actions = actions,
    )

    is Step.Review -> toReviewStage(
        onConfirm = { actions.onConfirm(this) },
        onCancel = { actions.onCancel(this) },
        onSaveDebugPayload = onSavePayload,
        expandedNoteIds = expandedNoteIds,
        onToggleNote = actions.onToggleNote,
        onItemSelectionChange = { index, selected ->
            actions.onItemSelectionChange(this, index, selected)
        },
    )

    is Step.Empty -> ImportState.Stage.Empty(
        message = message,
        skipped = skips.toNotes(
            expandedIds = expandedNoteIds,
            onToggle = actions.onToggleNote,
        ),
        onClose = actions.onClose,
    )

    is Step.Done -> ImportState.Stage.Done(
        itemCount = itemCount,
        folderCount = folderCount,
        onClose = actions.onClose,
    )

    is Step.Error -> ImportState.Stage.Error(
        message = message,
        onRetry = retry?.toRetryAction(
            actions = actions,
        ),
    )
}

private fun startStage(
    actions: ImportStageActions,
    credentialExchange: Boolean,
    formats: List<ImportFormat>,
) = ImportState.Stage.Start(
    onCredentialExchange = actions.onImport.takeIf { credentialExchange },
    formats = formats.map { format ->
        format.toUiFormat(
            onClick = {
                actions.onPickFile(format)
            },
        )
    },
    backupLocations = backupLocationTemplates.map { store ->
        ImportState.Stage.Start.BackupLocation(
            kind = store.kind,
            onClick = {
                actions.onPickBackup(store)
            },
        )
    },
    onLearnMore = actions.onLearnMore,
)

private fun Retry.toRetryAction(
    actions: ImportStageActions,
): (LeContext) -> Unit = when (this) {
    Retry.Transfer -> actions.onImport
    is Retry.Pick -> { _: LeContext ->
        when (source) {
            is ImportSourceRef.File -> actions.onPickFile(source.format)
            is ImportSourceRef.Backup -> actions.onPickBackup(source.store)
        }
    }

    Retry.ConfiguredBackup -> { _: LeContext ->
        actions.onOpenConfiguredBackup()
    }

    is Retry.Snapshots -> { _: LeContext ->
        actions.onShowSnapshots(step)
    }
}

/**
 * One empty location per kind a backup can be read from; picking one asks
 * for the location itself.
 */
private val backupLocationTemplates = listOf(
    BackupStoreConfig.Local(),
    BackupStoreConfig.WebDav(),
    BackupStoreConfig.S3(),
)

private fun Throwable.commitErrorRes(): StringResource = when (this) {
    is PremiumException -> Res.string.import_error_premium
    is ImportTooLargeException -> Res.string.import_error_attachment_too_large
    else -> Res.string.import_error_save
}

private fun passwordStage(
    text: String,
    password: TextFieldModel,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) = ImportState.Stage.Password(
    text = text,
    password = password,
    onConfirm = onConfirm.takeIf { password.text.isNotEmpty() },
    onCancel = onCancel,
)

private fun Step.Review.toReviewStage(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onSaveDebugPayload: (() -> Unit)?,
    expandedNoteIds: Set<String>,
    onToggleNote: (String) -> Unit,
    onItemSelectionChange: (Int, Boolean) -> Unit,
): ImportState.Stage.Review {
    val exporterName = plan.source.displayName
        ?: plan.source.identifier
        ?: sourcePackageName.orEmpty()
    return ImportState.Stage.Review(
        exporterName = exporterName,
        sourceAccountCount = plan.sourceAccountCount,
        folderCount = selectedPlan.folders.size,
        counts = counts,
        items = items.map { reviewItem ->
            val sourceIndex = reviewItem.sourceIndex
            ImportState.Stage.Review.Item(
                item = reviewItem.item,
                selected = sourceIndex in selectedItemIndexes,
                onSelectedChange = if (importing) {
                    null
                } else {
                    { selected ->
                        onItemSelectionChange(sourceIndex, selected)
                    }
                },
            )
        },
        skipped = plan.skips.toNotes(
            expandedIds = expandedNoteIds,
            onToggle = onToggleNote,
        ),
        isImporting = importing,
        // A payload that genuinely contains only folders remains importable. If the
        // user removed every available item, selectedPlan is empty and withdraws the
        // action until at least one item is selected again.
        onConfirm = onConfirm.takeIf { !importing && selectedPlan.isImportable },
        onCancel = onCancel.takeIf { !importing },
        // Hidden while a commit is running, like every other action here — the
        // payload is already on its way into the vault at that point.
        onSaveDebugPayload = onSaveDebugPayload.takeIf { !importing },
    )
}

/** `internal` so `ImportReviewItemsTest` can cover it. */
internal fun ImportPlan.toUiCounts(): ImportState.Counts {
    val requests = items.map { it.request }
    return ImportState.Counts(
        loginCount = requests.count { it.type == DSecret.Type.Login },
        passkeyCount = requests.sumOf { it.fido2Credentials.size },
        otpCount = requests.count { it.login.totp != null },
        cardCount = requests.count { it.type == DSecret.Type.Card },
        identityCount = requests.count { it.type == DSecret.Type.Identity },
        noteCount = requests.count { it.type == DSecret.Type.SecureNote },
        sshKeyCount = requests.count { it.type == DSecret.Type.SshKey },
        gpgKeyCount = requests.count { it.type == DSecret.Type.GpgKey },
        environmentCount = requests.count { it.type == DSecret.Type.Environment },
    )
}
