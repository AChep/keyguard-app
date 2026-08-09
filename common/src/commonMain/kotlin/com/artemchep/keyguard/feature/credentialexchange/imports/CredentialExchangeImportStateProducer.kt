package com.artemchep.keyguard.feature.credentialexchange.imports

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.FolderHierarchyMode
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.model.displayName
import com.artemchep.keyguard.common.service.credentialexchange.CredentialExchangeImportTransport
import com.artemchep.keyguard.common.service.credentialexchange.CredentialExchangeImportTransportResult
import com.artemchep.keyguard.common.service.credentialexchange.CxfImportPlan
import com.artemchep.keyguard.common.service.credentialexchange.CxfImportResult
import com.artemchep.keyguard.common.service.credentialexchange.CxfImportService
import com.artemchep.keyguard.common.service.credentialexchange.CxfImportSkips
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.common.usecase.AddCipher
import com.artemchep.keyguard.common.usecase.AddFolder
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.ResolveFolderHierarchyMode
import com.artemchep.keyguard.common.usecase.createCiphers
import com.artemchep.keyguard.feature.credentialexchange.toggleNote
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance
import org.kodein.di.instanceOrNull

/**
 * The CXF credential types requested from the source provider: everything the
 * import mapper can represent.
 */
private val requestedCredentialTypes = CxfCredentialType.IMPORTABLE
    .mapTo(mutableSetOf()) { it.serialName }

internal const val CREDENTIAL_EXCHANGE_IMPORT_DOCS_URL =
    "https://keyguard.dev/docs/credential-exchange/#importing-from-another-app"

@Composable
fun produceCredentialExchangeImportScreenState(
    args: CredentialExchangeImportRoute.Args,
): Loadable<CredentialExchangeImportState> = with(localDI().direct) {
    produceCredentialExchangeImportScreenState(
        args = args,
        resolveFolderHierarchyMode = instance(),
        getProfiles = instance(),
        cxfImportService = instance(),
        addFolder = instance(),
        addCipher = instance(),
        cryptoGenerator = instance(),
        transport = instanceOrNull(),
        dirsService = instance(),
        dateFormatter = instance(),
    )
}

@Composable
fun produceCredentialExchangeImportScreenState(
    args: CredentialExchangeImportRoute.Args,
    resolveFolderHierarchyMode: ResolveFolderHierarchyMode,
    getProfiles: GetProfiles,
    cxfImportService: CxfImportService,
    addFolder: AddFolder,
    addCipher: AddCipher,
    cryptoGenerator: CryptoGenerator,
    transport: CredentialExchangeImportTransport?,
    dirsService: DirsService,
    dateFormatter: DateFormatter,
): Loadable<CredentialExchangeImportState> = produceScreenState(
    key = "credential_exchange_import",
    initial = Loadable.Loading,
    args = arrayOf(
        args,
    ),
) {
    credentialExchangeImportScreenStateProducer(
        args = args,
        resolveFolderHierarchyMode = resolveFolderHierarchyMode,
        getProfiles = getProfiles,
        cxfImportService = cxfImportService,
        addFolder = addFolder,
        addCipher = addCipher,
        cryptoGenerator = cryptoGenerator,
        transport = transport,
        dirsService = dirsService,
        dateFormatter = dateFormatter,
    )
}

/**
 * The internal step of the linear import flow. UI callbacks are attached when
 * the step is mapped to a [CredentialExchangeImportState.Stage].
 */
internal sealed interface Step {
    data object Start : Step

    data object Loading : Step

    data class Review(
        val plan: CxfImportPlan,
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
    ) : Step {
        // Derived once per step instance instead of on every state emission —
        // the plan is immutable while the review sits on screen. This matters more
        // for the rows than for the counts: a document can carry thousands of items.
        val selectedPlan = plan.selectItems(selectedItemIndexes)
        val counts = selectedPlan.toUiCounts()
        val items = plan.toUiItems(untitledLabel)
    }

    data class Empty(
        val skips: CxfImportSkips,
    ) : Step

    data class Done(
        val itemCount: Int,
        val folderCount: Int,
    ) : Step

    data class Error(
        val message: String,
        val retryable: Boolean,
    ) : Step
}

suspend fun RememberStateFlowScope.credentialExchangeImportScreenStateProducer(
    args: CredentialExchangeImportRoute.Args,
    resolveFolderHierarchyMode: ResolveFolderHierarchyMode,
    getProfiles: GetProfiles,
    cxfImportService: CxfImportService,
    addFolder: AddFolder,
    addCipher: AddCipher,
    cryptoGenerator: CryptoGenerator,
    transport: CredentialExchangeImportTransport?,
    dirsService: DirsService,
    dateFormatter: DateFormatter,
): Flow<Loadable<CredentialExchangeImportState>> {
    val stepSink = MutableStateFlow<Step>(Step.Start)
    val commitExecutor = screenExecutor()

    // Debug-only. Held beside the step rather than inside it: `Step.Review` is a data
    // class that `claimCommit` copies and compare-and-sets, so a payload member would
    // put a whole plaintext vault into its `toString()` — one stray log away from the
    // leak the "never logged" rule exists to prevent. Empty in a release build.
    val debugPayloadSink = MutableStateFlow<String?>(null)

    // Which skipped-items notes are open. Owned here rather than by the row,
    // because the rows are lazy and would forget the moment one scrolled out.
    val expandedNotesSink = mutablePersistedFlow<Set<String>>(
        key = "skipped.expanded",
    ) { emptySet() }

    fun launchSavePayload(payload: String) {
        screenScope.launch {
            val message = runCatchingNonFatal {
                val fileName = saveCxfImportPayload(
                    payload = payload,
                    dirsService = dirsService,
                    dateFormatter = dateFormatter,
                ).bind()
                ToastMessage(title = "Saved $fileName")
            }.getOrElse { e ->
                // The exception itself, not the payload: a storage failure can quote
                // back what it was asked to write.
                ToastMessage(
                    title = "Failed to save the payload: ${e::class.simpleName}",
                    type = ToastMessage.Type.ERROR,
                )
            }
            message(message)
        }
    }

    fun launchTransfer(context: LeContext) {
        stepSink.value = Step.Loading
        // Don't let the previous attempt's vault sit in memory across the next one.
        debugPayloadSink.value = null
        screenScope.launch {
            // Anything that escapes here would leave `stepSink` on
            // `Step.Loading` for good, so every outcome has to resolve to a
            // step — including the ones the transport and the parser do not
            // promise to handle.
            val step = runCatchingNonFatal {
                if (transport == null) {
                    Step.Error(
                        message = translate(Res.string.credential_exchange_import_error_unavailable),
                        retryable = false,
                    )
                } else {
                    val result = transport.importCredentials(
                        context = context,
                        credentialTypes = requestedCredentialTypes,
                    )
                    debugPayloadSink.value = result.debugPayloadOrNull()
                    handleTransportResult(
                        result = result,
                        cxfImportService = cxfImportService,
                    )
                }
            }.getOrElse {
                Step.Error(
                    message = translate(Res.string.credential_exchange_import_error_unknown),
                    retryable = true,
                )
            }
            stepSink.value = step
        }
    }

    fun launchCommit(review: Step.Review) {
        val importingReview = stepSink.claimCommit(review)
            ?: return
        // The review is over, so the dump action is gone; drop the vault with it.
        debugPayloadSink.value = null
        val io = ioEffect {
            val step = runCatchingNonFatal {
                commitPlan(
                    plan = review.selectedPlan,
                    accountId = args.accountId,
                    folderHierarchyMode = resolveFolderHierarchyMode(args.accountId)
                        .bind(),
                    addFolder = addFolder,
                    addCipher = addCipher,
                    cryptoGenerator = cryptoGenerator,
                    // The very string the review showed for a blank title, rather
                    // than a second translation of a second resource.
                    untitledTitle = review.untitledLabel,
                )
            }.getOrElse {
                Step.Error(
                    message = translate(Res.string.credential_exchange_import_error_save),
                    retryable = false,
                )
            }
            stepSink.value = step
        }
        if (!commitExecutor.execute(io)) {
            // This executor is dedicated to commits, so a refusal should only
            // be possible if an earlier commit still owns it. Do not strand the
            // review on a spinner when that invariant is violated.
            stepSink.compareAndSet(importingReview, review)
        }
    }

    val profileFlow = getProfiles()
        .map { profiles ->
            profiles.firstOrNull { profile ->
                profile.accountId == args.accountId.id
            }
        }
        // Any vault change re-emits the profile list; don't rebuild the whole
        // stage tree unless the target profile itself changed.
        .distinctUntilChanged()

    return combine(
        profileFlow,
        stepSink,
        debugPayloadSink,
        expandedNotesSink,
    ) { profile, step, debugPayload, expandedNotes ->
        val stage = step.toStage(
            onImport = ::launchTransfer,
            onLearnMore = {
                navigate(
                    NavigationIntent.NavigateToBrowser(
                        url = CREDENTIAL_EXCHANGE_IMPORT_DOCS_URL,
                    ),
                )
            },
            onConfirm = ::launchCommit,
            onCancel = { review ->
                debugPayloadSink.value = null
                stepSink.cancelReview(review)
            },
            onClose = ::navigatePopSelf,
            onSavePayload = debugPayload?.let { payload ->
                // lambda
                {
                    launchSavePayload(payload)
                }
            },
            expandedNoteIds = expandedNotes,
            onToggleNote = expandedNotesSink::toggleNote,
            onItemSelectionChange = { review, index, selected ->
                stepSink.setReviewItemSelected(
                    review = review,
                    index = index,
                    selected = selected,
                )
            },
        )
        val state = CredentialExchangeImportState(
            accountTitle = profile?.displayName,
            stage = stage,
        )
        Loadable.Ok(state)
    }
}

/**
 * The placeholder shown and written for a CXF item whose own title is blank.
 *
 * One resource, translated once per import and then carried on the review step, so the
 * row and the created vault item cannot disagree. They did: the row translated
 * `empty_value` ("Empty") while the commit wrote this one ("Untitled"), which made the
 * review name a title no item would ever carry.
 */
internal val cxfImportUntitledRes: StringResource =
    Res.string.credential_exchange_import_untitled

/**
 * `true` when committing the plan would write something — an item, a folder, or both.
 *
 * The gate into the review and the gate on the Import button must read the same
 * predicate. They did not: a folders-only plan (an app whose every item was of an
 * unsupported kind, say) passed the empty gate, so the review promised "3 folders", and
 * then failed the confirm gate, so the only offered action was Cancel.
 */
internal val CxfImportPlan.isImportable: Boolean
    get() = items.isNotEmpty() || folders.isNotEmpty()

/**
 * Turns the transport outcome into the next flow step: a cancellation quietly
 * returns to the idle step, a failure explains itself, and a payload is
 * parsed off the main thread into either the review or the empty step.
 */
private suspend fun RememberStateFlowScope.handleTransportResult(
    result: CredentialExchangeImportTransportResult,
    cxfImportService: CxfImportService,
): Step = when (result) {
    is CredentialExchangeImportTransportResult.Cancelled -> Step.Start

    is CredentialExchangeImportTransportResult.Failure -> Step.Error(
        message = translate(result.kind.messageRes()),
        retryable = true,
    )

    is CredentialExchangeImportTransportResult.Success -> {
        val parsed = withContext(Dispatchers.Default) {
            cxfImportService.parse(
                payload = result.payload,
                now = Clock.System.now(),
            )
        }
        when (parsed) {
            is CxfImportResult.Failure -> Step.Error(
                message = translate(Res.string.credential_exchange_import_error_parse),
                retryable = true,
            )

            is CxfImportResult.Success -> {
                val plan = parsed.plan
                if (!plan.isImportable) {
                    Step.Empty(
                        skips = plan.skips,
                    )
                } else {
                    Step.Review(
                        plan = plan,
                        sourcePackageName = result.sourcePackageName,
                        untitledLabel = translate(cxfImportUntitledRes),
                    )
                }
            }
        }
    }
}

/**
 * Attaches the flow's callbacks to a step. `internal` so the gates it wires — in
 * particular whether the review offers an Import at all — can be driven without
 * Compose.
 */
internal fun Step.toStage(
    onImport: (LeContext) -> Unit,
    onLearnMore: () -> Unit,
    onConfirm: (Step.Review) -> Unit,
    onCancel: (Step.Review) -> Unit,
    onClose: () -> Unit,
    /**
     * Writes the raw payload to a file. `null` outside a debug build, and `null` once
     * the payload has been dropped — its absence is the whole gate.
     */
    onSavePayload: (() -> Unit)? = null,
    /** The skipped-items notes the user has opened, keyed by reason name. */
    expandedNoteIds: Set<String> = emptySet(),
    onToggleNote: (String) -> Unit = {},
    onItemSelectionChange: (Step.Review, Int, Boolean) -> Unit = { _, _, _ -> },
): CredentialExchangeImportState.Stage = when (this) {
    is Step.Start -> CredentialExchangeImportState.Stage.Start(
        onImport = onImport,
        onLearnMore = onLearnMore,
    )

    is Step.Loading -> CredentialExchangeImportState.Stage.Loading

    is Step.Review -> toReviewStage(
        onConfirm = { onConfirm(this) },
        onCancel = { onCancel(this) },
        onSaveDebugPayload = onSavePayload,
        expandedNoteIds = expandedNoteIds,
        onToggleNote = onToggleNote,
        onItemSelectionChange = { index, selected ->
            onItemSelectionChange(this, index, selected)
        },
    )

    is Step.Empty -> CredentialExchangeImportState.Stage.Empty(
        skipped = skips.toNotes(
            expandedIds = expandedNoteIds,
            onToggle = onToggleNote,
        ),
        onClose = onClose,
    )

    is Step.Done -> CredentialExchangeImportState.Stage.Done(
        itemCount = itemCount,
        folderCount = folderCount,
        onClose = onClose,
    )

    is Step.Error -> CredentialExchangeImportState.Stage.Error(
        message = message,
        onRetry = onImport.takeIf { retryable },
    )
}

/**
 * Creates the planned folders and items in the target account: folders one
 * batch per hierarchy level (parents resolve before children), then every
 * item in a single [createCiphers] transaction.
 *
 * A plan with folders and no items is a legitimate commit — the review offered it,
 * see [isImportable] — and [createCiphers] answers an empty request list without
 * touching the vault.
 */
private suspend fun commitPlan(
    plan: CxfImportPlan,
    accountId: AccountId,
    folderHierarchyMode: FolderHierarchyMode,
    addFolder: AddFolder,
    addCipher: AddCipher,
    cryptoGenerator: CryptoGenerator,
    untitledTitle: String,
): Step {
    val folderIdByKey = createPlannedFolders(
        folders = plan.folders,
        accountId = accountId,
        addFolder = addFolder,
        hierarchyMode = folderHierarchyMode,
    )
    val requests = plan.toCreateRequests(
        accountId = accountId,
        folderIdByKey = folderIdByKey,
        untitledTitle = untitledTitle,
    )
    addCipher.createCiphers(
        requests = requests,
        cryptoGenerator = cryptoGenerator,
    ).bind()
    return Step.Done(
        itemCount = requests.size,
        folderCount = folderIdByKey.size,
    )
}

private fun Step.Review.toReviewStage(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onSaveDebugPayload: (() -> Unit)?,
    expandedNoteIds: Set<String>,
    onToggleNote: (String) -> Unit,
    onItemSelectionChange: (Int, Boolean) -> Unit,
): CredentialExchangeImportState.Stage.Review {
    val exporterName = plan.exporterDisplayName.takeIf { it.isNotBlank() }
        ?: plan.exporterRpId.takeIf { it.isNotBlank() }
        ?: sourcePackageName.orEmpty()
    return CredentialExchangeImportState.Stage.Review(
        exporterName = exporterName,
        sourceAccountCount = plan.sourceAccountCount,
        folderCount = selectedPlan.folders.size,
        counts = counts,
        items = items.map { reviewItem ->
            val sourceIndex = reviewItem.sourceIndex
            CredentialExchangeImportState.Stage.Review.Item(
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

private fun CxfImportPlan.toUiCounts(): CredentialExchangeImportState.Counts {
    val requests = items.map { it.request }
    return CredentialExchangeImportState.Counts(
        loginCount = requests.count { it.type == DSecret.Type.Login },
        passkeyCount = requests.sumOf { it.fido2Credentials.size },
        otpCount = requests.count { it.login.totp != null },
        cardCount = requests.count { it.type == DSecret.Type.Card },
        identityCount = requests.count { it.type == DSecret.Type.Identity },
        noteCount = requests.count { it.type == DSecret.Type.SecureNote },
        sshKeyCount = requests.count { it.type == DSecret.Type.SshKey },
    )
}

private fun CredentialExchangeImportTransportResult.Failure.Kind.messageRes() = when (this) {
    CredentialExchangeImportTransportResult.Failure.Kind.NoExportingProviders ->
        Res.string.credential_exchange_import_error_no_providers

    CredentialExchangeImportTransportResult.Failure.Kind.Unavailable ->
        Res.string.credential_exchange_import_error_unavailable

    CredentialExchangeImportTransportResult.Failure.Kind.InvalidData ->
        Res.string.credential_exchange_import_error_parse

    CredentialExchangeImportTransportResult.Failure.Kind.Unknown ->
        Res.string.credential_exchange_import_error_unknown
}
