package com.artemchep.keyguard.apple.account

import com.artemchep.keyguard.apple.add.AddFilePickerRequest
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.FilePickerBridge
import com.artemchep.keyguard.apple.core.DetailSession
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.filePickerResultOf
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.apple.dialog.FormDialogsSession
import com.artemchep.keyguard.apple.model.VaultAlertSeverity
import com.artemchep.keyguard.apple.model.VaultItemSnapshot
import com.artemchep.keyguard.apple.model.buildVaultItemSnapshots
import com.artemchep.keyguard.apple.model.invokeAction
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.auth.certificate.AccountClientCertificateState
import com.artemchep.keyguard.feature.auth.certificate.ClientCertificateStatus
import com.artemchep.keyguard.feature.auth.certificate.accountClientCertificateStateProducer
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.client_certificate_drop_here
import com.artemchep.keyguard.ui.SimpleNote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Display-only projection: never exports certificate bytes, passwords, or file access tokens. */
data class AccountClientCertificateSnapshot(
    val configured: Boolean,
    val displayName: String?,
    val issuerName: String?,
    /** `null` when no certificate is set or its bundle cannot be read. */
    val status: ClientCertificateStatus?,
    val validFrom: String?,
    val expiresOn: String?,
    /** Elapsed share of the validity period, or `null` if unknown. */
    val progress: Double?,
    /** Copyable details; their actions go to [AccountClientCertificateSession.invokeAction]. */
    val details: List<VaultItemSnapshot>,
    /** The shown certificate differs from the saved one. */
    val unsaved: Boolean,
    val note: String?,
    val noteSeverity: VaultAlertSeverity?,
    val busy: Boolean,
    val canImport: Boolean,
    val canRemove: Boolean,
    val canSave: Boolean,
    val confirmRemoval: Boolean,
    /** Text over the screen while a file hovers it; `null` while drops are not accepted. */
    val dropText: String?,
)

private class AccountClientCertificateActions(
    val state: AccountClientCertificateState,
    val handlers: Map<String, () -> Unit>,
)

@Suppress("TooManyFunctions") // Mirrors the shared editor action contract for Swift.
class AccountClientCertificateSession internal constructor(
    private val ctx: CoreContext,
    private val accountId: String,
    val dialogs: FormDialogsSession,
) {
    private val session = DetailSession<AccountClientCertificateSnapshot, AccountClientCertificateActions>(onDispose = {
        filePicker.close()
        dialogs.close()
    })
    private val filePicker = FilePickerBridge()

    fun setFilePickerRequestHandler(handler: ((AddFilePickerRequest) -> Unit)?) = session.runIfOpen {
        filePicker.onRequest = handler
    }

    fun resolveFilePicker(
        requestId: String,
        uri: String,
        name: String?,
        size: Long,
        accessToken: String?,
    ) = session.runIfOpen { filePicker.resolve(requestId, uri, name, size, accessToken) }

    fun cancelFilePicker(requestId: String) = session.runIfOpen { filePicker.cancel(requestId) }

    fun observe(onChange: (AccountClientCertificateSnapshot) -> Unit, onClose: () -> Unit): KeyguardCancellable =
        session.observe(onChange, onClose) { publish, finish ->
            val leContext = ctx.koin.get<LeContext>()
            ctx.launchSessionObserver(onLocked = finish) { vault ->
                val interceptor = dialogs.controller.navigationInterceptor(vault.sessionKoin, formDialogsOnly = true)
                val producer = with(vault.sessionKoin) {
                    ctx.koin.newHeadlessStateFlowScope(
                        "account_client_certificate",
                        this@launchSessionObserver,
                        navigationInterceptor = { intent ->
                            if (intent is NavigationIntent.Pop || intent is NavigationIntent.PopById) {
                                ctx.scope.launch { finish() }
                                true
                            } else interceptor(intent)
                        },
                    ).accountClientCertificateStateProducer(
                        accountId = accountId,
                        db = get(),
                        putAccountMutualTls = get(),
                        fileService = get(),
                        mutualTlsService = get(),
                        confirmationRouteFactory = get(),
                        dateFormatter = get(),
                    )
                }
                val latest = MutableStateFlow<AccountClientCertificateState?>(null)
                launch { producer.collect { latest.value = it.getOrNull() } }
                launch {
                    latest.filterNotNull().map { it.filePickerIntents }.distinctUntilChanged()
                        .flatMapLatest { it }.collectOnMain(session.gated(filePicker::pick))
                }
                val dropText = textResource(Res.string.client_certificate_drop_here, leContext)
                latest.filterNotNull()
                    .map { state ->
                        val handlers = LinkedHashMap<String, () -> Unit>()
                        val snapshot = state.toSnapshot(dropText, leContext, handlers)
                        snapshot to AccountClientCertificateActions(state, handlers)
                    }
                    .collectOnMain { (snapshot, actions) -> publish(snapshot, actions) }
            }
        }

    fun importCertificate() = session.withActions { it.state.onImport?.invoke() }
    fun dropFile(uri: String, name: String?, size: Long) =
        session.withActions { it.state.onFileDrop?.invoke(filePickerResultOf(uri, name, size)) }
    fun removeCertificate() = session.withActions { it.state.onRemove?.invoke() }
    fun save() = session.withActions { it.state.onSave?.invoke() }
    fun confirmRemoval() = session.withActions { it.state.onConfirmRemoval() }
    fun dismissRemoval() = session.withActions { it.state.onDismissRemoval() }
    fun invokeAction(id: String) = session.withActions { it.handlers.invokeAction(id) }
    fun close() = session.close()
}

private suspend fun AccountClientCertificateState.toSnapshot(
    dropText: String,
    leContext: LeContext,
    handlers: MutableMap<String, () -> Unit>,
): AccountClientCertificateSnapshot {
    val certificate = certificate
    return AccountClientCertificateSnapshot(
        configured = certificate != null,
        displayName = certificate?.displayName,
        issuerName = certificate?.issuerName,
        status = certificate?.status,
        validFrom = certificate?.validFrom,
        expiresOn = certificate?.expiresOn,
        progress = certificate?.progress?.toDouble(),
        details = buildVaultItemSnapshots(
            items = certificate?.details.orEmpty(),
            notesText = null,
            leContext = leContext,
            actionHandlers = handlers,
        ),
        unsaved = unsaved,
        note = note?.text,
        noteSeverity = when (note?.type) {
            SimpleNote.Type.ERROR -> VaultAlertSeverity.ERROR
            SimpleNote.Type.WARNING -> VaultAlertSeverity.WARNING
            SimpleNote.Type.OK,
            SimpleNote.Type.INFO,
            -> VaultAlertSeverity.INFO
            null -> null
        },
        busy = busy,
        canImport = onImport != null,
        canRemove = onRemove != null,
        canSave = onSave != null,
        confirmRemoval = confirmRemoval,
        dropText = dropText.takeIf { onFileDrop != null },
    )
}
