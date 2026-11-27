package com.artemchep.keyguard.feature.secretscan

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService
import com.artemchep.keyguard.common.service.secretscan.PlaintextSecretScanner
import com.artemchep.keyguard.common.service.secretscan.SecretScanItem
import com.artemchep.keyguard.common.service.secretscan.SecretScanKind
import com.artemchep.keyguard.common.usecase.CommitImportPlan
import com.artemchep.keyguard.common.usecase.CommitImportPlanRequest
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.confirmation.awaitConfirmationDialog
import com.artemchep.keyguard.feature.confirmation.organization.OrganizationConfirmationResult
import com.artemchep.keyguard.feature.confirmation.organization.OrganizationConfirmationRoute
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.util.io.toLocalPathFromFileUriOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import org.koin.compose.currentKoinScope

/** The home folder in the list of roots. */
private const val HOME_ROOT = "~"

@Composable
fun produceSecretScanState(
    kind: SecretScanKind,
): Loadable<SecretScanState> = with(currentKoinScope()) {
    val scanner: PlaintextSecretScanner = get()
    val getCiphers: GetCiphers = get()
    val commitImportPlan: CommitImportPlan = get()
    val sshKeyImportService: SshKeyImportService = get()
    val cryptoGenerator: CryptoGenerator = get()
    val confirmationRouteFactory: ConfirmationRouteFactory = get()
    produceScreenState(
        key = "secret_scan",
        initial = Loadable.Loading,
        args = arrayOf(
            kind,
            scanner,
            getCiphers,
            commitImportPlan,
            sshKeyImportService,
            cryptoGenerator,
            confirmationRouteFactory,
        ),
    ) {
        secretScanStateProducer(
            kind = kind,
            scanner = scanner,
            getCiphers = getCiphers,
            commitImportPlan = commitImportPlan,
            sshKeyImportService = sshKeyImportService,
            cryptoGenerator = cryptoGenerator,
            confirmationRouteFactory = confirmationRouteFactory,
        )
    }
}

/** The findings of a scan with their rows, all unchecked. */
private data class SecretScanListing(
    val snapshot: ScanSnapshot,
    val rows: List<SecretScanState.Item> = emptyList(),
)

// The local actions share the screen's flows; splitting them out would
// need a bag of those flows and callbacks.
@Suppress("LongMethod", "CyclomaticComplexMethod")
@OptIn(ExperimentalCoroutinesApi::class)
private fun RememberStateFlowScope.secretScanStateProducer(
    kind: SecretScanKind,
    scanner: PlaintextSecretScanner,
    getCiphers: GetCiphers,
    commitImportPlan: CommitImportPlan,
    sshKeyImportService: SshKeyImportService,
    cryptoGenerator: CryptoGenerator,
    confirmationRouteFactory: ConfirmationRouteFactory,
): Flow<Loadable<SecretScanState>> {
    val filePickerIntentFlow = EventFlow<FilePickerIntent<*>>()
    // The run of the scan; a new one restarts it. Idle until the first scan.
    val generationFlow = MutableStateFlow<Int?>(null)
    // Folders added on this screen; they last for the screen session.
    val extraRootsFlow = MutableStateFlow<List<String>>(emptyList())
    val checkedFlow = MutableStateFlow(0)
    // Choices by path; unset rows are unchecked.
    val selectionFlow = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val importFlow = MutableStateFlow(ImportStatus())

    fun rescan() {
        selectionFlow.value = emptyMap()
        generationFlow.update { (it ?: 0) + 1 }
    }

    val snapshotFlow = generationFlow
        .flatMapLatest { generation ->
            generation
                ?: return@flatMapLatest flowOf(ScanSnapshot(phase = SecretScanState.Phase.Idle))
            scanner.scanSnapshots(
                kind = kind,
                extraRoots = extraRootsFlow.value,
                checked = checkedFlow,
            )
        }
    // Collected from the first finished scan on.
    val vaultIndexFlow = getCiphers()
        .secretScanVaultIndex(kind, cryptoGenerator)
        .shareIn(screenScope, SharingStarted.Lazily, replay = 1)
    val importedSshFingerprintsFlow = importFlow
        .map { it.sshFingerprints }
        .distinctUntilChanged()
    val listingFlow = snapshotFlow
        .flatMapLatest { snapshot ->
            if (snapshot.phase !is SecretScanState.Phase.Done) {
                return@flatMapLatest flowOf(SecretScanListing(snapshot))
            }
            combine(
                vaultIndexFlow,
                importedSshFingerprintsFlow,
            ) { vaultIndex, importedSshFingerprints ->
                val rows = secretScanRows(
                    items = snapshot.items,
                    vaultIndex = vaultIndex,
                    importedSshFingerprints = importedSshFingerprints,
                )
                SecretScanListing(snapshot, rows)
            }
        }

    fun onAddFolder() {
        val intent = FilePickerIntent.OpenDirectory { result ->
            val directory = result?.uri?.toString()
                ?.toLocalPathFromFileUriOrNull()
                ?.value
            if (directory != null && directory !in extraRootsFlow.value) {
                extraRootsFlow.update { it + directory }
                // Before the first scan, the folder only joins the list.
                if (generationFlow.value != null) {
                    rescan()
                }
            }
        }
        filePickerIntentFlow.emit(intent)
    }

    suspend fun commit(
        items: List<SecretScanItem>,
        result: OrganizationConfirmationResult.Confirm,
    ): ToastMessage {
        val prepared = buildSecretScanImportPlan(
            items = items,
            scanner = scanner,
            sshKeyImportService = sshKeyImportService,
            askPassphrase = { item, wrong ->
                askSshKeyPassphrase(
                    confirmationRouteFactory = confirmationRouteFactory,
                    item = item,
                    wrong = wrong,
                )
            },
        )
        val itemCount = if (prepared.items.isNotEmpty()) {
            val request = CommitImportPlanRequest(
                plan = prepared.plan,
                accountId = AccountId(result.accountId),
                untitledTitle = translate(Res.string.untitled),
                attachments = null,
                folder = result.folderId,
            )
            commitImportPlan(request)
                .bind()
                .itemCount
        } else {
            0
        }
        importFlow.update {
            it.copy(
                sshFingerprints = it.sshFingerprints + prepared.sshFingerprints,
            )
        }
        // Uncheck the imported rows, so that they are not imported twice.
        val importedPaths = prepared.items.map { it.path }.toSet()
        selectionFlow.update { selection ->
            selection - importedPaths
        }
        return createImportMessage(itemCount, prepared)
    }

    suspend fun runImport(
        items: List<SecretScanItem>,
        result: OrganizationConfirmationResult.Confirm,
    ) {
        importFlow.update { it.copy(importing = true) }
        try {
            val toast = runCatchingNonFatal {
                commit(items, result)
            }.getOrElse {
                ToastMessage(
                    type = ToastMessage.Type.ERROR,
                    title = translate(Res.string.import_error_save),
                )
            }
            message(toast)
        } finally {
            importFlow.update { it.copy(importing = false) }
        }
    }

    suspend fun onImport(items: List<SecretScanItem>) {
        val route = registerRouteResultReceiver(
            route = OrganizationConfirmationRoute(
                args = OrganizationConfirmationRoute.Args(
                    decor = OrganizationConfirmationRoute.Args.Decor(
                        title = translate(Res.string.secret_scan_import_account_title),
                        icon = Icons.Outlined.SystemUpdateAlt,
                    ),
                    flags = OrganizationConfirmationRoute.Args.HIDE_ORGANIZATION or
                        OrganizationConfirmationRoute.Args.HIDE_COLLECTION,
                ),
            ),
        ) { result ->
            if (result is OrganizationConfirmationResult.Confirm) {
                action {
                    runImport(items, result)
                }
            }
        }
        navigate(NavigationIntent.NavigateToRoute(route))
    }

    // Created once, so that the rows stay stable across emissions.
    val onScan: () -> Unit = { rescan() }
    val onAddFolderClick: () -> Unit = ::onAddFolder
    val onSelectedChange: (String, Boolean) -> Unit = { path, value ->
        selectionFlow.update { it + (path to value) }
    }
    val importingFlow = importFlow
        .map { it.importing }
        .distinctUntilChanged()
    return combine(
        listingFlow,
        extraRootsFlow,
        selectionFlow,
        importingFlow,
    ) { listing, extraRoots, selection, importing ->
        val selected = listing.snapshot.items
            .filter { it.isSelected(selection) }
        val roots = buildList {
            if (scanner.defaultRootsAvailable) add(HOME_ROOT)
            addAll(extraRoots)
        }
        val state = SecretScanState(
            kind = kind,
            checkedFlow = checkedFlow,
            phase = listing.snapshot.phase,
            roots = roots,
            defaultRootsAvailable = scanner.defaultRootsAvailable,
            items = listing.rows.withSelection(selection),
            selectedCount = selected.size,
            importing = importing,
            onSelectedChange = onSelectedChange.takeUnless { importing },
            onAddFolder = onAddFolderClick.takeUnless { importing },
            onScan = onScan.takeUnless { importing },
            onImport = if (importing) null else onClick { onImport(selected) },
            filePickerIntentFlow = filePickerIntentFlow,
        )
        Loadable.Ok(state)
    }.stateIn(screenScope, SharingStarted.Eagerly, Loadable.Loading)
}

private suspend fun TranslatorScope.createImportMessage(
    itemCount: Int,
    prepared: SecretScanImportPlan,
): ToastMessage {
    val text = buildList {
        add(translate(Res.plurals.import_success_text, itemCount, itemCount))
        if (prepared.skipped > 0) {
            add(translate(Res.plurals.secret_scan_skipped_plural, prepared.skipped, prepared.skipped))
        }
        if (prepared.changed > 0) {
            add(translate(Res.plurals.secret_scan_changed_files_plural, prepared.changed, prepared.changed))
        }
    }.joinToString(separator = "\n")
    return ToastMessage(
        type = if (itemCount > 0) ToastMessage.Type.SUCCESS else ToastMessage.Type.INFO,
        title = translate(Res.string.import_success_title),
        text = text,
    )
}

private suspend fun RememberStateFlowScope.askSshKeyPassphrase(
    confirmationRouteFactory: ConfirmationRouteFactory,
    item: SecretScanItem.SshKey,
    wrong: Boolean,
): String? {
    val messageRes = if (wrong) {
        Res.string.secret_scan_passphrase_wrong_message
    } else {
        Res.string.secret_scan_passphrase_message
    }
    return awaitConfirmationDialog(
        confirmationRouteFactory = confirmationRouteFactory,
        item = ConfirmationRoute.Args.Item.StringItem(
            key = "passphrase",
            title = translate(Res.string.key_import_passphrase_title),
            type = ConfirmationRoute.Args.Item.StringItem.Type.Password,
            canBeEmpty = false,
            ephemeral = true,
        ),
        title = translate(Res.string.key_import_passphrase_hint, translate(Res.string.protocol_ssh)),
        message = translate(messageRes, item.displayPath),
    )
}
