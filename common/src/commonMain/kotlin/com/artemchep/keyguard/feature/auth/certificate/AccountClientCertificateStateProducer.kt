package com.artemchep.keyguard.feature.auth.certificate

import androidx.compose.runtime.Composable
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.home.vault.model.transformShapes
import com.artemchep.keyguard.feature.largetype.LargeTypeRoute
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.isCustom
import com.artemchep.keyguard.provider.bitwarden.transport.MutualTlsCertificateMetadata
import com.artemchep.keyguard.provider.bitwarden.transport.MutualTlsService
import com.artemchep.keyguard.provider.bitwarden.transport.describe
import com.artemchep.keyguard.provider.bitwarden.usecase.AccountCertificateConflictException
import com.artemchep.keyguard.provider.bitwarden.usecase.PutAccountMutualTls
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.addaccount_mutual_tls_issuer_label
import com.artemchep.keyguard.res.addaccount_mutual_tls_status_failed
import com.artemchep.keyguard.res.client_certificate
import com.artemchep.keyguard.res.client_certificate_conflict
import com.artemchep.keyguard.res.client_certificate_removed
import com.artemchep.keyguard.res.client_certificate_saved
import com.artemchep.keyguard.res.client_certificate_serial_number
import com.artemchep.keyguard.res.client_certificate_subject
import com.artemchep.keyguard.res.client_certificate_unavailable
import com.artemchep.keyguard.res.copy
import com.artemchep.keyguard.res.error_failed_unknown
import com.artemchep.keyguard.res.client_certificate_fingerprint_sha256
import com.artemchep.keyguard.res.key_type
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.buildContextItems
import kotlinx.coroutines.IO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.koin.compose.currentKoinScope
import kotlin.time.Clock

@Composable
fun produceAccountClientCertificateState(accountId: String): Loadable<AccountClientCertificateState> =
    with(currentKoinScope()) {
        val db = get<VaultDatabaseManager>()
        val save = get<PutAccountMutualTls>()
        val files = get<FileService>()
        val certificates = get<MutualTlsService>()
        val confirmations = get<ConfirmationRouteFactory>()
        val dates = get<DateFormatter>()
        produceScreenState(key = "account_client_certificate", initial = Loadable.Loading, args = arrayOf(accountId)) {
            accountClientCertificateStateProducer(accountId, db, save, files, certificates, confirmations, dates)
        }
    }

@Suppress("LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
suspend fun RememberStateFlowScope.accountClientCertificateStateProducer(
    accountId: String,
    db: VaultDatabaseManager,
    putAccountMutualTls: PutAccountMutualTls,
    fileService: FileService,
    mutualTlsService: MutualTlsService,
    confirmationRouteFactory: ConfirmationRouteFactory,
    dateFormatter: DateFormatter,
): Flow<Loadable<AccountClientCertificateState>> {
    val accounts = db.get().bind().accountQueries.getByAccountId(accountId).asFlow()
        .mapToOneOrNull(Dispatchers.IO)
        .map { it?.data_ as? BitwardenToken }
    val account = accounts.first()
    var expected = account?.env?.mutualTls
    val draft = MutableStateFlow(expected)
    val status = MutableStateFlow(EditorStatus(available = account?.env?.back()?.isCustom() == true))
    val filePicker = EventFlow<FilePickerIntent<*>>()
    val copy = copier()
    var closed = false
    var importJob: Job? = null
    fun canEdit() = !closed && screenScope.isActive && status.value.editable
    fun clear() {
        closed = true
        draft.value = null
        expected = null
    }
    fun close() {
        clear()
        importJob?.cancel()
        navigatePopSelf()
    }
    screenScope.coroutineContext[Job]?.invokeOnCompletion { clear() }
    accounts.map { it?.env }.distinctUntilChanged().onEach { env ->
        status.update { it.copy(available = env?.back()?.isCustom() == true) }
        if (env == null) close()
        else status.update { it.copy(conflict = env.mutualTls != expected) }
    }.launchIn(screenScope)

    fun commit(replacement: ServerEnv.MutualTls?) {
        status.update { it.copy(busy = true) }
        screenScope.launch {
            try {
                putAccountMutualTls(AccountId(accountId), expected, replacement).bind()
                message(ToastMessage(
                    type = ToastMessage.Type.SUCCESS,
                    title = translate(
                        if (replacement == null) Res.string.client_certificate_removed
                        else Res.string.client_certificate_saved,
                    ),
                ))
                close()
            } catch (e: Exception) {
                e.throwIfFatalOrCancellation()
                if (e is AccountCertificateConflictException) status.update { it.copy(conflict = true) }
                message(ToastMessage(type = ToastMessage.Type.ERROR, title = translate(
                    if (e is AccountCertificateConflictException) Res.string.client_certificate_conflict
                    else Res.string.error_failed_unknown,
                )))
            } finally {
                status.update { it.copy(busy = false) }
            }
        }
    }
    fun save() {
        if (!canEdit() || draft.value == expected) return
        commit(draft.value)
    }
    // Nothing saved yet means there is nothing to confirm: drop the draft.
    // Otherwise removal is destructive, so it asks first and then applies at once.
    fun remove() {
        if (!canEdit()) return
        if (expected == null) draft.value = null
        else status.update { it.copy(confirmation = true) }
    }
    fun confirmRemoval() {
        status.update { it.copy(confirmation = false) }
        if (canEdit() && expected != null) commit(null)
    }
    fun importFile(info: FilePickerResult) {
        if (!canEdit()) return
        status.update { it.copy(busy = true) }
        importJob = screenScope.launch {
            try {
                val imported = importClientCertificate(
                    info, fileService, mutualTlsService, confirmationRouteFactory,
                )?.imported
                if (imported != null) {
                    val current = status.value
                    if (!closed && current.available && !current.conflict) draft.value = imported
                }
            } finally {
                status.update { it.copy(busy = false) }
            }
        }
    }
    fun pick() {
        if (!canEdit()) return
        filePicker.emit(FilePickerIntent.OpenDocument(
            mimeTypes = FilePickerIntent.mimeTypesPkcs12,
            readUriPermission = false,
        ) { info ->
            if (info != null) importFile(info)
        })
    }
    val metadata = draft.mapLatest { certificate ->
        certificate to mutualTlsService.describe(certificate)
    }
    return combine(metadata, status) { (certificate, details), statusValue ->
        val error = when {
            !statusValue.available -> Res.string.client_certificate_unavailable
            statusValue.conflict -> Res.string.client_certificate_conflict
            certificate != null && details == null -> Res.string.addaccount_mutual_tls_status_failed
            else -> null
        }
        val editable = !closed && statusValue.editable
        Loadable.Ok(AccountClientCertificateState(
            certificate = certificate?.let { certificateState(details, copy, dateFormatter) },
            unsaved = certificate != expected,
            note = error?.let { SimpleNote(translate(it), SimpleNote.Type.ERROR) },
            busy = statusValue.busy,
            onImport = (::pick).takeIf { editable },
            onFileDrop = (::importFile).takeIf { editable },
            onRemove = (::remove).takeIf { editable && certificate != null },
            onSave = (::save).takeIf { editable && certificate != expected && details != null },
            confirmRemoval = statusValue.confirmation,
            onConfirmRemoval = ::confirmRemoval,
            onDismissRemoval = { status.update { it.copy(confirmation = false) } },
            filePickerIntents = filePicker,
        ))
    }
}

private suspend fun RememberStateFlowScope.certificateState(
    metadata: MutualTlsCertificateMetadata?,
    copy: CopyText,
    dateFormatter: DateFormatter,
): AccountClientCertificateState.Certificate {
    if (metadata == null) {
        return AccountClientCertificateState.Certificate(
            displayName = translate(Res.string.client_certificate),
            issuerName = null,
            status = null,
            validFrom = null,
            expiresOn = null,
            progress = null,
            details = emptyList(),
        )
    }
    val now = Clock.System.now()
    return AccountClientCertificateState.Certificate(
        displayName = metadata.displayName,
        issuerName = metadata.issuerName,
        status = metadata.status(now),
        validFrom = dateFormatter.formatDate(metadata.validFrom),
        expiresOn = dateFormatter.formatDate(metadata.expiresAt),
        progress = metadata.lifetimeProgress(now),
        details = certificateDetails(metadata, copy),
    )
}

private suspend fun RememberStateFlowScope.certificateDetails(
    metadata: MutualTlsCertificateMetadata,
    copy: CopyText,
): List<VaultViewItem> {
    suspend fun row(
        key: String,
        title: StringResource,
        value: String,
        monospace: Boolean = false,
    ) = VaultViewItem.Value(
        id = "certificate.$key",
        title = translate(title),
        // Copies keep the plain `openssl` form; the shown one wraps between
        // groups and stays left-to-right in a right-to-left layout.
        value = if (monospace) "\u2066${value.chunkedColonHex()}\u2069" else value,
        monospace = monospace,
        dropdown = buildContextItems {
            section {
                this += copy.FlatItemAction(
                    id = "certificate.$key.copy",
                    title = Res.string.copy.wrap(),
                    value = value,
                )
            }
            if (monospace) section {
                this += LargeTypeRoute.showInLargeTypeActionOrNull(
                    translator = this@certificateDetails,
                    text = value,
                    navigate = ::navigate,
                )
            }
        },
    ).takeIf { value.isNotBlank() }
    return listOfNotNull(
        row("subject", Res.string.client_certificate_subject, metadata.subject),
        row("issuer", Res.string.addaccount_mutual_tls_issuer_label, metadata.issuer),
        row("serial", Res.string.client_certificate_serial_number, metadata.serialNumber, monospace = true),
        row(
            "fingerprint",
            Res.string.client_certificate_fingerprint_sha256,
            metadata.sha256Fingerprint,
            monospace = true,
        ),
        row("key_type", Res.string.key_type, metadata.keyType),
    ).transformShapes()
}

private const val COLON_HEX_GROUP_BYTES = 4

private fun String.chunkedColonHex() = split(':')
    .chunked(COLON_HEX_GROUP_BYTES) { it.joinToString(":") }
    .joinToString(" ")

private data class EditorStatus(
    val available: Boolean,
    val busy: Boolean = false,
    val conflict: Boolean = false,
    val confirmation: Boolean = false,
) {
    val editable get() = available && !conflict && !busy
}
