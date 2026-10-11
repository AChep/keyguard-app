package com.artemchep.keyguard.apple.gpgtools

import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.completeOnPop
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.apple.core.filePickerResultOf
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParser
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.gpgagent.tools.GpgToolsOperation
import com.artemchep.keyguard.feature.gpgagent.tools.GpgToolsPublicKeyValidationResult
import com.artemchep.keyguard.feature.gpgagent.tools.GpgToolsScope
import com.artemchep.keyguard.feature.gpgagent.tools.GpgToolsSignMode
import com.artemchep.keyguard.feature.gpgagent.tools.GpgToolsState
import com.artemchep.keyguard.feature.gpgagent.tools.GpgToolsVerifyMode
import com.artemchep.keyguard.feature.gpgagent.tools.gpgToolsStateProducer
import com.artemchep.keyguard.feature.gpgagent.tools.validateGpgToolsPublicKeys
import com.artemchep.keyguard.feature.gpgagent.tools.keypicker.GpgToolsKeyPickerRoute
import com.artemchep.keyguard.feature.gpgagent.tools.keypicker.GpgToolsKeyPickerState
import com.artemchep.keyguard.feature.gpgagent.tools.keypicker.gpgToolsKeyPickerStateProducer
import com.artemchep.keyguard.feature.gpgagent.tools.publickey.GpgToolsPublicKeyResult
import com.artemchep.keyguard.feature.gpgagent.tools.publickey.GpgToolsPublicKeyRoute
import com.artemchep.keyguard.feature.gpgagent.tools.result.GpgToolsResultRoute
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultReceiver
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_tools_encrypted_text_label
import com.artemchep.keyguard.res.gpg_tools_signed_text_label
import com.artemchep.keyguard.res.input
import com.artemchep.keyguard.res.gpg_key_status_revoked_text
import com.artemchep.keyguard.res.gpg_key_status_expired_text
import com.artemchep.keyguard.res.gpg_key_status_unauthenticated_self_signature_title
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.tabs.TabItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid
import kotlin.time.Clock
import org.koin.core.scope.Scope

/** Native UI bridges operation-owned resources; cryptography stays in the shared producer. */
class GpgToolsSession internal constructor(private val ctx: CoreContext) {
    internal var navigationInterceptorProvider: ((Scope) -> ((NavigationIntent) -> Boolean))? = null

    private class Operation(
        val files: GpgToolsFiles,
        val parser: GpgPublicKeyParser,
        val kind: GpgToolsOperation,
        val onResult: (GpgToolsResultSnapshot) -> Unit,
        val onFilePicker: (GpgToolsFilePickerRequest?) -> Unit,
        val onPublicKey: (GpgToolsPublicKeyRequest?) -> Unit,
        val onKeyPicker: (GpgToolsKeyPickerSnapshot?) -> Unit,
    ) {
        var scope: CoroutineScope? = null
        var result: GpgToolsResultRoute.Args? = null
        var resultId = ""
        var preparedFileId: String? = null
        var exportPreparing = false
        var keyRequest: PublicKeyRequest? = null
        var keyPicker: KeyPicker? = null
        val parsed = mutableMapOf<String, List<GpgPublicKeyInfo>>()
    }
    private class PublicKeyRequest(
        val id: String,
        val transmitter: RouteResultTransmitter<GpgToolsPublicKeyResult>,
        var validated: List<GpgPublicKeyInfo>? = null,
        var revision: Int = 0,
    )
    private class KeyPicker(val id: String) {
        var job: Job? = null
        var state: GpgToolsKeyPickerState? = null
    }
    private class ImportRequest(
        val owner: Operation,
        val file: GpgToolsFiles.File,
        val intent: FilePickerIntent.OpenDocument,
    )

    // All UI state and continuation maps are main-confined. File-store leases are thread safe.
    private var closed = false
    private var observation: KeyguardCancellable? = null
    private var generation = 0
    private var active: Operation? = null
    private var current: GpgToolsState? = null
    private val imports = mutableMapOf<String, ImportRequest>()
    private val exports = mutableMapOf<String, GpgToolsFiles>()

    fun close() {
        if (closed) return
        closed = true
        stopGpgTools()
    }

    fun stopGpgTools() {
        generation++
        observation?.cancel()
        observation = null
        invalidate()
    }

    private fun invalidate() {
        val owner = active
        active = null
        current = null
        owner?.keyRequest?.transmitter?.invoke(GpgToolsPublicKeyResult.Deny)
        owner?.keyRequest = null
        owner?.onPublicKey?.invoke(null)
        owner?.keyPicker = null
        owner?.onKeyPicker?.invoke(null)
        owner?.onFilePicker?.invoke(null)
        owner?.scope?.cancel()
        // Active crypto/import/export leases defer deletion until the real IO finishes.
        owner?.files?.close()
    }

    fun observeGpgTools(
        operation: String,
        onChange: (GpgToolsSnapshot) -> Unit,
        onResult: (GpgToolsResultSnapshot) -> Unit,
        onFilePicker: (GpgToolsFilePickerRequest?) -> Unit,
        onPublicKey: (GpgToolsPublicKeyRequest?) -> Unit,
        onKeyPicker: (GpgToolsKeyPickerSnapshot?) -> Unit,
    ): KeyguardCancellable {
        check(!closed) { "A closed GPG tools session cannot be observed" }
        stopGpgTools()
        val version = generation
        val kind = GpgToolsOperation.entries.firstOrNull { it.key == operation } ?: GpgToolsOperation.SIGN
        val context = ctx.koin.get<LeContext>()
        val subscription = ctx.launchSessionObserver(onLocked = {
            if (version == generation) {
                invalidate()
                onChange(GpgToolsSnapshot.empty)
            }
        }) { state ->
            val service = state.sessionKoin.get<FileService>()
            val owner = Operation(
                GpgToolsFiles(service),
                state.sessionKoin.get(),
                kind,
                onResult,
                onFilePicker,
                onPublicKey,
                onKeyPicker,
            )
            try {
                coroutineScope {
                    // UI callbacks acquire file leases synchronously before yielding.
                    // Give their coroutine a Main dispatcher so run()/export's
                    // withContext(Default) always dispatches expensive work off Main.
                    // The session Job remains shared; teardown still awaits every worker.
                    val operationScope = CoroutineScope(coroutineContext + Dispatchers.Main)
                    owner.scope = operationScope
                    ctx.publishOnMain {
                        if (version != generation) throw CancellationException()
                        active = owner
                    }
                    val wrapped = navigationInterceptorProvider?.invoke(state.sessionKoin)
                    val interceptor: (NavigationIntent) -> Boolean = { intent ->
                        val route = (intent as? NavigationIntent.NavigateToRoute)?.route
                        when {
                            route is GpgToolsResultRoute -> {
                                ctx.scope.launch {
                                    if (active === owner) {
                                        discardResult(owner)
                                        owner.result = route.args
                                        owner.resultId = Uuid.random().toString()
                                        onResult(route.args.toSnapshot(owner.resultId))
                                    } else route.args.fileOutput?.let { owner.files.discard(it.id) }
                                }
                                true
                            }
                            route is RouteResultReceiver<*> && route.innerRoute is GpgToolsPublicKeyRoute -> {
                                val publicKeyRoute = route.innerRoute as GpgToolsPublicKeyRoute
                                @Suppress("UNCHECKED_CAST")
                                val transmitter = route.resultTransmitter as RouteResultTransmitter<
                                    GpgToolsPublicKeyResult,
                                >
                                ctx.scope.launch {
                                    if (active !== owner) transmitter(GpgToolsPublicKeyResult.Deny)
                                    else {
                                        owner.keyRequest?.transmitter?.invoke(GpgToolsPublicKeyResult.Deny)
                                        val request = PublicKeyRequest(Uuid.random().toString(), transmitter)
                                        owner.keyRequest = request
                                        onPublicKey(GpgToolsPublicKeyRequest(request.id, publicKeyRoute.args.publicKey))
                                    }
                                }
                                true
                            }
                            route is RouteResultReceiver<*> && route.innerRoute is GpgToolsKeyPickerRoute -> {
                                val keyPickerRoute = route.innerRoute as GpgToolsKeyPickerRoute
                                @Suppress("UNCHECKED_CAST")
                                val transmitter = route.resultTransmitter as RouteResultTransmitter<Set<String>>
                                ctx.scope.launch {
                                    if (active === owner) presentKeyPicker(owner, keyPickerRoute.args, transmitter)
                                }
                                true
                            }
                            else -> wrapped?.invoke(intent) ?: false
                        }
                    }
                    val producer = with(state.sessionKoin) {
                        ctx.koin.newHeadlessStateFlowScope(
                            "gpg_tools_${kind.key}", this@coroutineScope, interceptor,
                        )
                            .gpgToolsStateProducer(
                                operation = kind, getCiphers = get(), fileService = service,
                                keyMetadataResolver = get(),
                                openPgpService = get(),
                                openPgpVerifier = get(),
                                outputCoordinator = owner.files, operationScope = operationScope,
                            )
                    }
                    var pickerFlow: Flow<FilePickerIntent<*>>? = null
                    producer.collect { loadable ->
                        val value = loadable.getOrNull() ?: return@collect
                        if (pickerFlow !== value.sideEffects.filePickerIntentFlow) {
                            check(pickerFlow == null) { "Unexpected GPG picker flow replacement." }
                            pickerFlow = value.sideEffects.filePickerIntentFlow
                            launch { value.sideEffects.filePickerIntentFlow.collect { receivePicker(owner, it) } }
                        }
                        val snapshot = snapshot(owner, value, context)
                        ctx.publishOnMain {
                            if (active !== owner) return@publishOnMain
                            val old = current
                            current = value
                            if (old?.inputFile?.uri != value.inputFile?.uri) {
                                old?.inputFile?.let { owner.files.discardUri(it.uri) }
                            }
                            if (old?.signatureFile?.uri != value.signatureFile?.uri) {
                                old?.signatureFile?.let { owner.files.discardUri(it.uri) }
                            }
                            onChange(snapshot)
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    ctx.publishOnMain { if (active === owner) invalidate() }
                    owner.files.close()
                }
            }
        }
        if (version == generation && !closed) observation = subscription else subscription.cancel()
        return KeyguardCancellable {
            if (version == generation) stopGpgTools() else subscription.cancel()
        }
    }

    private suspend fun receivePicker(owner: Operation, intent: FilePickerIntent<*>) {
        if (intent !is FilePickerIntent.OpenDocument) {
            // Apple output uses the completed-artifact coordinator, never NewDocument placeholders.
            when (intent) {
                is FilePickerIntent.NewDocument -> intent.onResult(null)
                is FilePickerIntent.OpenDirectory -> intent.onResult(null)
                else -> Unit
            }
            return
        }
        owner.files.retain()
        var delivered = false
        var file: GpgToolsFiles.File? = null
        try {
            val createdFile = owner.files.create("input")
            file = createdFile
            ctx.publishOnMain {
                if (active !== owner) { intent.onResult(null); return@publishOnMain }
                val id = Uuid.random().toString()
                imports[id] = ImportRequest(owner, createdFile, intent)
                delivered = true
                owner.onFilePicker(GpgToolsFilePickerRequest(id, createdFile.uri))
            }
        } finally {
            if (!delivered) {
                file?.let { owner.files.discard(it.id) }
                owner.files.release()
            }
        }
    }

    fun resolveGpgToolsFilePicker(id: String, name: String?, size: Long) {
        val request = imports.remove(id) ?: return
        try {
            if (active === request.owner && name != null) {
                request.intent.onResult(filePickerResultOf(request.file.uri, name, size))
            } else {
                request.intent.onResult(null)
                request.owner.files.discard(request.file.id)
            }
            if (active === request.owner) request.owner.onFilePicker(null)
        } finally { request.owner.files.release() }
    }

    private suspend fun snapshot(owner: Operation, state: GpgToolsState, context: LeContext): GpgToolsSnapshot {
        suspend fun TabItem.tab() = GpgToolsTabSnapshot(key, textResource(title, context))
        fun GpgToolsState.FileRef.file() = GpgToolsFileSnapshot(owner.files.find(uri)?.id ?: uri, name ?: "", size)
        val custom = state.customPublicKeys.flatMap { item ->
            val keys = owner.parsed.getOrPut(item.publicKey) {
                (owner.parser.parse(item.publicKey) as? GpgPublicKeyParseResult.Success)?.keys.orEmpty()
            }
            keys.map { key -> key.toPublicKeySnapshot(item.id, context) }
        }
        owner.parsed.keys.retainAll(state.customPublicKeys.map { it.publicKey }.toSet())
        return GpgToolsSnapshot(
            loaded = true, operation = state.operation.key, scope = state.scope.key,
            scopes = state.scopes.map { it.tab() }, signMode = state.signMode.key,
            signModes = state.signModes.map { it.tab() }, verifyMode = state.verifyMode.key,
            verifyModes = state.verifyModes.map { it.tab() }, armor = state.armor, showArmor = state.showArmor,
            inputText = state.inputText.text, inputTextRevision = state.inputText.textRevision,
            inputLabel = textResource(when (state.operation) {
                GpgToolsOperation.ENCRYPT, GpgToolsOperation.SIGN -> Res.string.input
                GpgToolsOperation.DECRYPT -> Res.string.gpg_tools_encrypted_text_label
                GpgToolsOperation.VERIFY -> if (state.verifyMode == GpgToolsVerifyMode.INLINE) {
                    Res.string.gpg_tools_signed_text_label
                } else Res.string.input
            }, context), showSignatureField = state.operation == GpgToolsOperation.VERIFY &&
                state.scope == GpgToolsScope.TEXT && state.verifyMode == GpgToolsVerifyMode.DETACHED,
            signatureText = state.detachedSignatureText.text,
            signatureTextRevision = state.detachedSignatureText.textRevision,
            storedKeys = state.storedKeys.map { it.toSnapshot() },
            selectedPrivateKeyId = state.selectedPrivateKeyId,
            selectedEncryptSigningKeyId = state.selectedEncryptSigningKeyId,
            selectedRecipientIds = state.selectedRecipientIds.toList(),
            busy = state.busy,
            canRun = state.onRun != null && !state.busy,
            inputFile = state.inputFile?.file(), signatureFile = state.signatureFile?.file(), customPublicKeys = custom,
        )
    }

    fun addGpgToolsPublicKey() { current?.takeUnless { it.busy }?.onAddPublicKey?.invoke() }
    fun removeGpgToolsPublicKey(id: String) { current?.takeUnless { it.busy }?.onRemovePublicKey?.invoke(id) }

    fun validateGpgToolsPublicKey(id: String, text: String, onResult: (GpgToolsPublicKeyValidationSnapshot) -> Unit) {
        val owner = active ?: return
        val request = owner.keyRequest?.takeIf { it.id == id } ?: return
        request.validated = null
        val revision = ++request.revision
        owner.scope?.launch {
            val context = ctx.koin.get<LeContext>()
            val result = withContext(Dispatchers.Default) {
                validateGpgToolsPublicKeys(text, owner.parser, owner.kind == GpgToolsOperation.ENCRYPT)
            }
            val error = (result as? GpgToolsPublicKeyValidationResult.Error)?.let {
                textResource(it.resource, context)
            }
            val keys = (result as? GpgToolsPublicKeyValidationResult.Success)?.keys.orEmpty()
            val snapshots = keys.map { it.toPublicKeySnapshot(it.fingerprint, context) }
            ctx.publishOnMain {
                if (active !== owner || owner.keyRequest !== request || request.revision != revision) {
                        return@publishOnMain
                    }
                request.validated = keys.takeIf { it.isNotEmpty() }
                onResult(GpgToolsPublicKeyValidationSnapshot(
                    snapshots, error,
                ))
            }
        }
    }

    fun finishGpgToolsPublicKey(id: String, confirm: Boolean) {
        val owner = active ?: return
        val request = owner.keyRequest?.takeIf { it.id == id } ?: return
        if (confirm && request.validated == null) return
        owner.keyRequest = null
        request.transmitter(if (confirm) GpgToolsPublicKeyResult.Confirm(
            publicKeys = request.validated!!.map { it.publicKeyArmored },
        ) else GpgToolsPublicKeyResult.Deny)
        owner.onPublicKey(null)
    }

    /** Runs the shared picker headlessly; it pops itself after delivering a confirmed selection. */
    private fun presentKeyPicker(
        owner: Operation,
        args: GpgToolsKeyPickerRoute.Args,
        transmitter: RouteResultTransmitter<Set<String>>,
    ) {
        owner.keyPicker?.job?.cancel()
        val picker = KeyPicker(Uuid.random().toString())
        owner.keyPicker = picker
        picker.job = owner.scope?.launch(Dispatchers.Default) {
            val interceptor = completeOnPop(ctx.scope) { closeKeyPicker(owner, picker) }
            ctx.koin.newHeadlessStateFlowScope("gpg_tools_key_picker", this, interceptor)
                .gpgToolsKeyPickerStateProducer(args, transmitter)
                .collect { state ->
                    val snapshot = GpgToolsKeyPickerSnapshot(
                        id = picker.id,
                        multiple = args.mode == GpgToolsKeyPickerRoute.Mode.Recipients,
                        query = state.query.text,
                        queryRevision = state.query.textRevision,
                        items = state.items.map { GpgToolsKeyPickerItemSnapshot(it.key.toSnapshot(), it.selected) },
                        showNone = state.onSelectNone != null,
                        noneSelected = state.noneSelected,
                        hasKeys = args.keys.isNotEmpty(),
                        canConfirm = state.onConfirm != null,
                    )
                    ctx.publishOnMain {
                        if (owner.keyPicker !== picker) return@publishOnMain
                        picker.state = state
                        owner.onKeyPicker(snapshot)
                    }
                }
        }
    }

    private fun closeKeyPicker(owner: Operation, picker: KeyPicker) {
        if (owner.keyPicker !== picker) return
        owner.keyPicker = null
        picker.job?.cancel()
        owner.onKeyPicker(null)
    }

    private fun keyPickerState(id: String) = active?.keyPicker?.takeIf { it.id == id }?.state

    fun chooseGpgToolsRecipients() { current?.takeUnless { it.busy }?.onChooseRecipients?.invoke() }
    fun chooseGpgToolsSigningKey() { current?.takeUnless { it.busy }?.onChooseSigningKey?.invoke() }
    fun setGpgToolsKeyPickerQuery(id: String, text: String) { keyPickerState(id)?.query?.onChange?.invoke(text) }
    fun toggleGpgToolsKeyPickerKey(id: String, keyId: String) {
        keyPickerState(id)?.items?.firstOrNull { it.key.id == keyId }?.onSelect?.invoke()
    }
    fun selectGpgToolsKeyPickerNone(id: String) { keyPickerState(id)?.onSelectNone?.invoke() }

    fun finishGpgToolsKeyPicker(id: String, confirm: Boolean) {
        val owner = active ?: return
        val picker = owner.keyPicker?.takeIf { it.id == id } ?: return
        val state = picker.state
        if (confirm) {
            state?.onConfirm?.invoke()
        } else {
            // A picker that has not emitted yet has no deny callback to pop it.
            state?.onDeny?.invoke() ?: closeKeyPicker(owner, picker)
        }
    }

    fun prepareGpgToolsExport(resultId: String, onResult: (GpgToolsExportSnapshot?) -> Unit) {
        val owner = active?.takeIf { it.resultId == resultId } ?: run { onResult(null); return }
        val result = owner.result ?: run { onResult(null); return }
        val scope = owner.scope ?: run { onResult(null); return }
        if (owner.exportPreparing) { onResult(null); return }
        owner.exportPreparing = true
        val existingFileId = result.fileOutput?.id ?: owner.preparedFileId
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var leaseHeld = false
            var createdFileId: String? = null
            var delivered = false
            try {
                owner.files.retain()
                leaseHeld = true
                val fileId = withContext(Dispatchers.Default) {
                    existingFileId ?: run {
                        val output = result.output ?: error("No GPG output.")
                        owner.files.write("gpg-output.txt", output.incognito) { uri ->
                            val bytes = output.text.encodeToByteArray()
                            try {
                                ctx.koin.get<FileService>().writeToFile(uri).use { it.write(bytes) }
                            } finally {
                                bytes.fill(0)
                            }
                        }.id.also { createdFileId = it }
                    }
                }
                val file = owner.files.get(fileId) ?: error("GPG result has expired.")
                ctx.publishOnMain {
                    if (active !== owner || owner.resultId != resultId) {
                        delivered = true
                        onResult(null)
                        return@publishOnMain
                    }
                    owner.preparedFileId = fileId
                    createdFileId = null // The result now owns the reusable text artifact.
                    val id = Uuid.random().toString()
                    exports[id] = owner.files
                    leaseHeld = false // Native exporter owns this lease until its completion callback.
                    delivered = true
                    onResult(GpgToolsExportSnapshot(id, file.uri, file.name))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // The finally block completes even a failed or cancelled preparation.
            } finally {
                createdFileId?.let { owner.files.discard(it) }
                if (leaseHeld) owner.files.release()
                withContext(NonCancellable) {
                    ctx.publishOnMain {
                        owner.exportPreparing = false
                        if (!delivered) onResult(null)
                    }
                }
            }
        }
    }

    fun finishGpgToolsExport(id: String) { exports.remove(id)?.release() }
    fun dismissGpgToolsResult() { active?.let(::discardResult) }
    private fun discardResult(owner: Operation) {
        owner.result?.fileOutput?.let { owner.files.discard(it.id) }
        owner.preparedFileId?.let { owner.files.discard(it) }
        owner.preparedFileId = null
        owner.result = null
        owner.resultId = ""
    }

    fun setGpgToolsScope(
        key: String,
    ) { GpgToolsScope.entries.firstOrNull { it.key == key }?.let { current?.onScopeChange?.invoke(
        it,
    ) } }
    fun setGpgToolsSignMode(
        key: String,
    ) { GpgToolsSignMode.entries.firstOrNull { it.key == key }?.let { current?.onSignModeChange?.invoke(
        it,
    ) } }
    fun setGpgToolsVerifyMode(
        key: String,
    ) { GpgToolsVerifyMode.entries.firstOrNull { it.key == key }?.let { current?.onVerifyModeChange?.invoke(
        it,
    ) } }
    fun setGpgToolsArmor(value: Boolean) { current?.onArmorChange?.invoke(value) }
    fun setGpgToolsInputText(text: String) { current?.inputText?.onChange?.invoke(text) }
    fun setGpgToolsSignatureText(text: String) { current?.detachedSignatureText?.onChange?.invoke(text) }
    fun selectGpgToolsPrivateKey(id: String) { current?.onSelectPrivateKey?.invoke(id) }
    fun selectGpgToolsEncryptSigningKey(id: String?) { current?.onSelectEncryptSigningKey?.invoke(id) }
    fun toggleGpgToolsRecipient(id: String) { current?.onToggleRecipient?.invoke(id) }
    fun selectGpgToolsInputFile() { current?.takeUnless { it.busy }?.onSelectInputFile?.invoke() }
    fun clearGpgToolsInputFile() { current?.takeUnless { it.busy }?.onClearInputFile?.invoke() }
    fun selectGpgToolsSignatureFile() { current?.takeUnless { it.busy }?.onSelectSignatureFile?.invoke() }
    fun clearGpgToolsSignatureFile() { current?.takeUnless { it.busy }?.onClearSignatureFile?.invoke() }
    fun runGpgTools() { current?.onRun?.invoke() }
    fun invokeGpgToolsResultCopy() { active?.result?.output?.onCopy?.invoke() }

    private fun GpgToolsState.KeyItem.toSnapshot() = GpgToolsKeySnapshot(
        id,
        title,
        description,
        canSign,
        canDecrypt,
        publicKeyAvailable,
    )

    private suspend fun GpgPublicKeyInfo.toPublicKeySnapshot(
        id: String,
        context: LeContext,
    ): GpgToolsPublicKeySnapshot {
        val notes = buildList {
            if (revoked) add(
                GpgToolsNoteSnapshot(textResource(Res.string.gpg_key_status_revoked_text, context), "warning"),
            )
            if (expiresAt?.let { it <= Clock.System.now() } == true) {
                add(GpgToolsNoteSnapshot(textResource(Res.string.gpg_key_status_expired_text, context), "warning"))
            }
            if (!authenticated) {
                add(
                    GpgToolsNoteSnapshot(
                        textResource(Res.string.gpg_key_status_unauthenticated_self_signature_title, context),
                        "warning",
                    ),
                )
            }
        }
        return GpgToolsPublicKeySnapshot(id, userIds.firstOrNull() ?: fingerprint, fingerprint, notes)
    }

    private fun GpgToolsResultRoute.Args.toSnapshot(id: String) = GpgToolsResultSnapshot(
        title, notes.map { GpgToolsNoteSnapshot(it.text, when (it.type) {
            SimpleNote.Type.OK -> "ok"
            SimpleNote.Type.ERROR -> "error"
            else -> "warning"
        }) }, output?.label, output?.text, fileOutput?.incognito ?: output?.incognito ?: false,
        output?.onCopy != null, fileOutput != null || output != null, id,
        fileOutput?.let { GpgToolsFileSnapshot(it.id, it.name, it.size) },
    )
}
