package com.artemchep.keyguard.feature.credentialexchange.export

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.common.service.credentialexchange.CxfAccountResult
import com.artemchep.keyguard.common.service.credentialexchange.CxfExportService
import com.artemchep.keyguard.common.service.credentialexchange.CxfExportSkipReason
import com.artemchep.keyguard.common.service.credentialexchange.CxfExportSkips
import com.artemchep.keyguard.common.service.credentialexchange.cxfExportSkips
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredential
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import com.artemchep.keyguard.feature.credentialexchange.CredentialExchangeItem
import com.artemchep.keyguard.feature.credentialexchange.sortedCredentialExchangeItemsBy
import com.artemchep.keyguard.feature.credentialexchange.toggleNote
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.items
import com.artemchep.keyguard.res.result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance
import kotlin.invoke
import kotlin.text.map
import kotlin.text.orEmpty

@Composable
fun produceCredentialExchangeExportScreenState(
    args: CredentialExchangeExportRoute.Args,
): Loadable<CredentialExchangeExportState> = with(localDI().direct) {
    produceCredentialExchangeExportScreenState(
        args = args,
        getProfiles = instance(),
        getCiphers = instance(),
        getFolders = instance(),
        cxfExportService = instance(),
    )
}

@Composable
fun produceCredentialExchangeExportScreenState(
    args: CredentialExchangeExportRoute.Args,
    getProfiles: GetProfiles,
    getCiphers: GetCiphers,
    getFolders: GetFolders,
    cxfExportService: CxfExportService,
): Loadable<CredentialExchangeExportState> = produceScreenState(
    key = "credential_exchange_export",
    initial = Loadable.Loading,
) {
    credentialExchangeExportScreenStateProducer(
        args = args,
        getProfiles = getProfiles,
        getCiphers = getCiphers,
        getFolders = getFolders,
        cxfExportService = cxfExportService,
    )
}

/**
 * A single profile paired with its already-built CXF account (or `null` when
 * nothing was exportable) and the counts of credentials that had to be skipped.
 *
 * `internal` so `CredentialExchangeExportStateProducerTest` can drive the
 * per-account isolation and the error-stage derivation without Compose.
 */
internal data class ProfileAccount(
    val profile: DProfile,
    val result: CxfAccountResult,
)

/**
 * The internal step behind the review screen. The mapping either produced a
 * list of accounts or it did not; there is no third, still-loading outcome —
 * the wait before the first step exists as a stage
 * ([CredentialExchangeExportState.Stage.Mapping]), which is where it belongs,
 * because only the stage layer can offer the user a way out of it.
 */
internal sealed interface Step {
    data class Review(val profileAccounts: List<ProfileAccount>) : Step

    data object Error : Step

    /**
     * The account the picked entry addresses cannot be exported from: it has been
     * deleted, or hidden since the entry was registered.
     *
     * Distinct from [Error] on purpose. Nothing malfunctioned, so the transfer is
     * reported as the user's own cancellation rather than an internal failure.
     */
    data object Unavailable : Step
}


suspend fun RememberStateFlowScope.credentialExchangeExportScreenStateProducer(
    args: CredentialExchangeExportRoute.Args,
    getProfiles: GetProfiles,
    getCiphers: GetCiphers,
    getFolders: GetFolders,
    cxfExportService: CxfExportService,
): Flow<Loadable<CredentialExchangeExportState>> {
    // Only a single result may be reported back to the host. Guard against
    // double-invocation (e.g. deny after confirm, or a rapid double tap).
    var completed = false
    fun complete(
        result: CredentialExchangeExportResult,
    ) {
        if (completed) {
            return
        }
        completed = true
        args.onComplete(result)
    }

    // User-presence gate. Until the user verifies, the screen shows the gate instead
    // of the review and the mapping behind it does not even start. Persisted so that
    // a recomposition does not make the user verify twice.
    //
    // Seeded from the host: when the vault was unlocked as part of this very request
    // the user typed their password seconds ago, so asking again is asking twice.
    val verifiedSink = mutablePersistedFlow(
        key = "verified",
    ) { args.userVerified }

    val errorMessage = translate(Res.string.credential_exchange_export_error_unknown)
    val unavailableMessage =
        translate(Res.string.credential_exchange_export_error_account_unavailable)

    val stepFlow = combine(
        getProfiles(),
        filterHiddenProfiles(
            getProfiles = getProfiles,
            getCiphers = getCiphers,
        ),
        getFolders(),
    ) { profiles, ciphers, folders ->
        // The ciphers and folders flows stay whole-vault: `buildProfileAccounts`
        // groups them by account id and reads only the scoped profile's bucket, so
        // the surplus is ignored and the grouping is trivial next to the passkey and
        // SSH mapping it feeds.
        val scoped = scopeProfiles(profiles, args.accountId)
            ?: return@combine Step.Unavailable
        buildStep(
            profiles = scoped,
            ciphers = ciphers,
            folders = folders,
            allowedTypes = args.requestedTypes,
            cxfExportService = cxfExportService,
        )
    }.catch { e ->
        // A vault read or decrypt failure upstream lands here. `catch` is
        // terminal: the error stage is final and a later vault emission will
        // not revive the screen, which is what a one-shot consent flow wants.
        e.throwIfFatalOrCancellation()
        recordException(e)
        emit(Step.Error)
    }

    // Flipped when the user confirms the transfer, so the UI can disable the
    // confirm action and show progress while the host builds the response.
    val exportingSink = MutableStateFlow(false)

    // Which skipped-items notes are open. Owned here rather than by the row,
    // because the rows are lazy and would forget the moment one scrolled out.
    val expandedNotesSink = mutablePersistedFlow<Set<String>>(
        key = "skipped.expanded",
    ) { emptySet() }

    // The account mapping decodes every passkey key and converts every SSH
    // key of the vault, so it must not run — and its result must not be
    // handed out — until the user passes the gate. `flatMapLatest` is what
    // enforces that: while unverified the whole upstream is the `flowOf` below
    // and `stepFlow` is never subscribed to.
    return verifiedSink.flatMapLatest { verified ->
        if (!verified) {
            // Deliberately a rendered stage, not `Loadable.Loading`: this stage
            // carries the verification form, and a spinner cannot collect a
            // password.
            return@flatMapLatest flowOf(
                lockedLoadableState(
                    onAuthenticated = { verifiedSink.value = true },
                ),
            )
        }
        combine(
            stepFlow,
            exportingSink,
            expandedNotesSink,
        ) { step, exporting, expandedNotes ->
            step.toLoadableState(
                exporting = exporting,
                errorMessage = errorMessage,
                unavailableMessage = unavailableMessage,
                onExporting = { exportingSink.value = true },
                complete = ::complete,
                expandedNoteIds = expandedNotes,
                onToggleNote = expandedNotesSink::toggleNote,
            )
        }
            // The `combine` above emits nothing until every vault source has emitted
            // and the mapping has finished, so without this the screen kept showing
            // the retained gate — a password form the user had already satisfied.
            .startWithMapping(complete = ::complete)
    }
}

/**
 * Wraps a [Step] into the loadable screen state, wiring the confirm/deny
 * callbacks to [complete].
 */
internal fun Step.toLoadableState(
    exporting: Boolean,
    errorMessage: String,
    unavailableMessage: String,
    onExporting: () -> Unit,
    complete: (CredentialExchangeExportResult) -> Unit,
    expandedNoteIds: Set<String> = emptySet(),
    onToggleNote: (String) -> Unit = {},
): Loadable<CredentialExchangeExportState> {
    val profileAccounts = (this as? Step.Review)?.profileAccounts.orEmpty()
    val skips = profileAccounts.fold(cxfExportSkips()) { acc, pa -> acc + pa.result.skips }
    val hasExportableAccount = profileAccounts.any { it.result.account != null }
    // A vault whose every account failed to map is a failure, not an empty
    // review, which would report an internal error to the requesting app as if
    // the user had declined. The skip counter is the only evidence that the
    // accounts existed at all, so the error stage is derived from it.
    val failed = this is Step.Error ||
            (!hasExportableAccount && skips[CxfExportSkipReason.Account] > 0)
    val onConfirm = if (hasExportableAccount && !exporting) {
        // lambda
        {
            val accounts = profileAccounts.mapNotNull { it.result.account }
            onExporting()
            complete(CredentialExchangeExportResult.Complete(accounts))
        }
    } else {
        null
    }
    val stage = when {
        // Deliberately reports NOTHING, unlike the error branch. The activity armed
        // a cancellation in `onCreate` and never cleared it, so every exit — the
        // close button, back, a system finish — already tells the requesting app the
        // user withdrew, which is the honest answer when nothing malfunctioned.
        this is Step.Unavailable -> CredentialExchangeExportState.Stage.Unavailable(
            message = unavailableMessage,
            onClose = {
                complete(CredentialExchangeExportResult.Cancel)
            },
        )

        failed -> {
            complete(CredentialExchangeExportResult.Fail)
            CredentialExchangeExportState.Stage.Error(
                message = errorMessage,
            )
        }

        else -> profileAccounts.toReviewStage(
            skips = skips,
            exporting = exporting,
            onConfirm = onConfirm,
            onDeny = {
                complete(CredentialExchangeExportResult.Cancel)
            },
            expandedNoteIds = expandedNoteIds,
            onToggleNote = onToggleNote,
        )
    }
    return Loadable.Ok(CredentialExchangeExportState(stage = stage))
}

/**
 * Maps the vault snapshot into the next [Step].
 *
 * The service boundary already turns a per-account failure into a counted skip;
 * this is the backstop for everything else on the path. Anything that escaped
 * would strand the screen on the mapping stage, whose only exit reports the
 * transfer to the requesting app as a cancellation the user never performed.
 */
internal fun buildStep(
    profiles: List<DProfile>,
    ciphers: List<DSecret>,
    folders: List<DFolder>,
    allowedTypes: Set<CxfCredentialType>,
    cxfExportService: CxfExportService,
): Step = runCatchingNonFatal {
    buildProfileAccounts(
        profiles = profiles,
        ciphers = ciphers,
        folders = folders,
        allowedTypes = allowedTypes,
        cxfExportService = cxfExportService,
    )
}.fold(
    onSuccess = Step::Review,
    onFailure = { e ->
        recordException(e)
        Step.Error
    },
)

/**
 * Builds one [ProfileAccount] per profile that has something exportable or
 * something that had to be skipped — a profile whose matching credentials were
 * all skipped still contributes its counts to the review screen's warning
 * notes. Ciphers and folders are scoped by account id.
 *
 * The `totalCount == 0` filter below is what makes the account-level skip
 * load-bearing: a profile whose mapping failed comes back with no account, and
 * only its skip contributing to the total keeps it in the list at all.
 */
internal fun buildProfileAccounts(
    profiles: List<DProfile>,
    ciphers: List<DSecret>,
    folders: List<DFolder>,
    allowedTypes: Set<CxfCredentialType>,
    cxfExportService: CxfExportService,
): List<ProfileAccount> {
    val ciphersByAccount = ciphers.groupBy { it.accountId }
    val foldersByAccount = folders.groupBy { it.accountId }
    return profiles
        .mapNotNull { profile ->
            val result = cxfExportService.buildAccountResult(
                profile = profile,
                ciphers = ciphersByAccount[profile.accountId].orEmpty(),
                allowedTypes = allowedTypes,
                folders = foldersByAccount[profile.accountId].orEmpty(),
            )
            if (result.account == null && result.skips.totalCount == 0) {
                return@mapNotNull null
            }
            ProfileAccount(
                profile = profile,
                result = result,
            )
        }
}

/**
 * Assembles the review stage. [skips] is one number per reason across the whole
 * export, not per account, and is summed by the caller because the same total
 * decides whether there is a review to show at all.
 */
private fun List<ProfileAccount>.toReviewStage(
    skips: CxfExportSkips,
    exporting: Boolean,
    onConfirm: (() -> Unit)?,
    onDeny: () -> Unit,
    expandedNoteIds: Set<String>,
    onToggleNote: (String) -> Unit,
): CredentialExchangeExportState.Stage.Review {
    val items = flatMap { profileAccount ->
        profileAccount.result.account
            ?.items
            .orEmpty()
    }.sortedCredentialExchangeItemsBy { item ->
        item.title
    }
    return CredentialExchangeExportState.Stage.Review(
        items = items.mapIndexed { index, item ->
            val shapeState = getShapeState(
                list = items,
                index = index,
                predicate = { _, _ -> true },
            )
            CredentialExchangeItem(
                title = item.title,
                shapeState = shapeState,
                credentials = item.credentials
                    .map { it.toUiKind() }
                    .distinct(),
            )
        },
        skipped = skips.toNotes(
            expandedIds = expandedNoteIds,
            onToggle = onToggleNote,
        ),
        isExporting = exporting,
        onConfirm = onConfirm,
        onDeny = onDeny,
    )
}

private fun CxfCredential.toUiKind(): CredentialExchangeItem.Kind = when (this) {
    is CxfCredential.Passkey -> CredentialExchangeItem.Kind.Passkey
    is CxfCredential.BasicAuth -> CredentialExchangeItem.Kind.Password
    is CxfCredential.Totp -> CredentialExchangeItem.Kind.Totp
    is CxfCredential.CreditCard -> CredentialExchangeItem.Kind.Card
    // A postal address, a person name and the identity documents are all facets
    // of an identity item. Only the import mapper builds the document
    // credentials, so those branches never fire on the export path.
    is CxfCredential.Address -> CredentialExchangeItem.Kind.Identity
    is CxfCredential.PersonName -> CredentialExchangeItem.Kind.Identity
    is CxfCredential.Passport -> CredentialExchangeItem.Kind.Identity
    is CxfCredential.DriversLicense -> CredentialExchangeItem.Kind.Identity
    is CxfCredential.IdentityDocument -> CredentialExchangeItem.Kind.Identity
    is CxfCredential.Note -> CredentialExchangeItem.Kind.Note
    is CxfCredential.CustomFields -> CredentialExchangeItem.Kind.Fields
    is CxfCredential.SshKey -> CredentialExchangeItem.Kind.SshKey
}
