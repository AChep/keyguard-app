package com.artemchep.keyguard.feature.gpgagent.tools

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpDecryptFileRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpDecryptTextRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpEncryptFileRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpEncryptTextRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPrivateKey
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpService
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpSignFileRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpSignTextRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerification
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerificationStatus
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerificationWarning
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerifier
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerifyDetachedTextRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerifyFileRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerifyTextRequest
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentSecret
import com.artemchep.keyguard.common.service.gpgagent.chunkedGpgFingerprint
import com.artemchep.keyguard.common.service.gpgagent.isUsableAgentKey
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.common.service.gpgagent.toGpgAgentSecretOrNull
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.common.textFieldHandle
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.feature.gpgagent.gpgKeyDescription
import com.artemchep.keyguard.feature.gpgagent.tools.publickey.GpgToolsPublicKeyRoute
import com.artemchep.keyguard.feature.gpgagent.tools.publickey.createGpgToolsPublicKeyDialogIntent
import com.artemchep.keyguard.feature.gpgagent.tools.result.GpgToolsResultRoute
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_tools_encrypted_text_label
import com.artemchep.keyguard.res.gpg_tools_invalid_signature
import com.artemchep.keyguard.res.gpg_tools_missing_public_key
import com.artemchep.keyguard.res.gpg_tools_run_success
import com.artemchep.keyguard.res.gpg_tools_signature_by
import com.artemchep.keyguard.res.gpg_tools_signature_created_at
import com.artemchep.keyguard.res.gpg_tools_signature_label
import com.artemchep.keyguard.res.gpg_tools_signed_text_label
import com.artemchep.keyguard.res.gpg_tools_valid_signature
import com.artemchep.keyguard.res.gpg_tools_warning_key_expired
import com.artemchep.keyguard.res.gpg_tools_warning_key_revoked
import com.artemchep.keyguard.res.gpg_tools_warning_signature_expired
import com.artemchep.keyguard.res.output
import com.artemchep.keyguard.res.result
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.util.foundation.io.writeText
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun produceGpgToolsState(
    operation: GpgToolsOperation,
): Loadable<GpgToolsState> = with(localDI().direct) {
    produceGpgToolsState(
        operation = operation,
        getCiphers = instance(),
        fileService = instance(),
        openPgpService = instance(),
        openPgpVerifier = instance(),
    )
}

@Composable
fun produceGpgToolsState(
    operation: GpgToolsOperation,
    getCiphers: GetCiphers,
    fileService: FileService,
    openPgpService: GpgOpenPgpService,
    openPgpVerifier: GpgOpenPgpVerifier,
): Loadable<GpgToolsState> = produceScreenState(
    key = "gpg_tools_${operation.key}",
    initial = Loadable.Loading,
    args = arrayOf(
        operation,
        getCiphers,
        fileService,
        openPgpService,
        openPgpVerifier,
    ),
) {
    val copyText = copier()
    val filePickerIntentSink = EventFlow<FilePickerIntent<*>>()
    val sideEffects = GpgToolsState.SideEffects(
        filePickerIntentFlow = filePickerIntentSink,
    )

    val scopeSink = mutablePersistedFlow("scope") {
        GpgToolsScope.TEXT.key
    }
    val scopeFlow = scopeSink
        .map { it.toGpgToolsScopeOrDefault() }
        .distinctUntilChanged()
    val signModeSink = mutablePersistedFlow("sign_mode") {
        GpgToolsSignMode.CLEAR_TEXT.key
    }
    val signModeFlow = signModeSink
        .map { it.toGpgToolsSignModeOrDefault() }
        .distinctUntilChanged()
    val verifyModeSink = mutablePersistedFlow("verify_mode") {
        GpgToolsVerifyMode.INLINE.key
    }
    val verifyModeFlow = verifyModeSink
        .map { it.toGpgToolsVerifyModeOrDefault() }
        .distinctUntilChanged()
    val armorSink = mutablePersistedFlow("armor") {
        true
    }
    val inputTextHandle = textFieldHandle("input_text")
    val detachedSignatureTextHandle = textFieldHandle("detached_signature_text")
    val customPublicKeysSink = MutableStateFlow<List<GpgToolsState.CustomPublicKeyItem>>(emptyList())
    var customPublicKeyCounter = 0
    val inputFileSink = MutableStateFlow<GpgToolsState.FileRef?>(null)
    val signatureFileSink = MutableStateFlow<GpgToolsState.FileRef?>(null)
    val selectedPrivateKeyIdSink = mutablePersistedFlow<String?>("selected_private_key_id") {
        null
    }
    val selectedEncryptSigningKeyIdSink = mutablePersistedFlow<String?>("selected_encrypt_signing_key_id") {
        null
    }
    val selectedRecipientIdsSink = mutablePersistedFlow<Set<String>, List<String>>(
        key = "selected_recipient_ids",
        serialize = { _, value -> value.toList() },
        deserialize = { _, value -> value.toSet() },
    ) { emptySet() }
    val busySink = MutableStateFlow(false)
    val hasArmorOption = operation == GpgToolsOperation.ENCRYPT ||
        operation == GpgToolsOperation.SIGN

    fun launchOperation(block: suspend () -> Unit) {
        if (busySink.value) {
            return
        }
        busySink.value = true
        action {
            try {
                block()
                message(
                    ToastMessage(
                        type = ToastMessage.Type.SUCCESS,
                        title = translate(Res.string.gpg_tools_run_success),
                    ),
                )
            } catch (e: Throwable) {
                message(e)
            } finally {
                busySink.value = false
            }
        }
    }

    fun FilePickerResult.toFileRef() = GpgToolsState.FileRef(
        uri = uri.toString(),
        name = name,
        size = size,
    )

    fun selectFile(target: MutableStateFlow<GpgToolsState.FileRef?>) {
        filePickerIntentSink.emit(
            FilePickerIntent.OpenDocument(
                mimeTypes = FilePickerIntent.mimeTypesAll,
                readUriPermission = true,
            ) { result ->
                if (result != null) {
                    target.value = result.toFileRef()
                }
            },
        )
    }

    fun saveAs(
        fileName: String,
        onFile: (GpgToolsState.FileRef) -> Unit,
    ) {
        filePickerIntentSink.emit(
            FilePickerIntent.NewDocument(
                fileName = fileName,
                mimeType = FilePickerIntent.MIME_TYPE_ALL,
                writeUriPermission = true,
            ) { result ->
                if (result != null) {
                    onFile(result.toFileRef())
                }
            },
        )
    }

    fun saveTextOutput(output: String) {
        if (output.isBlank()) {
            return
        }
        saveAs(fileName = "gpg-output.txt") { file ->
            launchOperation {
                withContext(Dispatchers.Default) {
                    val sink = fileService.writeToFile(file.uri)
                    try {
                        sink.writeText(output)
                    } finally {
                        sink.close()
                    }
                }
            }
        }
    }

    // The label mirrors the input's meaning for each operation, so the result
    // dialog reads the same way as the source screen.
    fun outputLabelResource(signMode: GpgToolsSignMode): StringResource = when (operation) {
        GpgToolsOperation.SIGN -> if (signMode == GpgToolsSignMode.DETACHED) {
            Res.string.gpg_tools_signature_label
        } else {
            Res.string.gpg_tools_signed_text_label
        }

        GpgToolsOperation.ENCRYPT -> Res.string.gpg_tools_encrypted_text_label
        GpgToolsOperation.VERIFY -> Res.string.output
        GpgToolsOperation.DECRYPT -> Res.string.output
    }

    suspend fun buildOutput(
        text: String,
        signMode: GpgToolsSignMode,
    ): GpgToolsResultRoute.Args.Output {
        val incognito = operation == GpgToolsOperation.DECRYPT
        return GpgToolsResultRoute.Args.Output(
            label = translate(outputLabelResource(signMode)),
            text = text,
            incognito = incognito,
            onCopy = {
                copyText.copy(
                    text = text,
                    hidden = incognito,
                    type = CopyText.Type.VALUE,
                )
            },
            onSave = {
                saveTextOutput(text)
            },
        )
    }

    suspend fun showResultDialog(
        output: GpgToolsResultRoute.Args.Output? = null,
        verification: SimpleNote? = null,
    ) {
        if (output == null && verification == null) {
            return
        }
        val route = GpgToolsResultRoute(
            args = GpgToolsResultRoute.Args(
                title = translate(Res.string.result),
                verification = verification,
                output = output,
            ),
        )
        navigate(NavigationIntent.NavigateToRoute(route))
    }

    fun requireInputText(form: GpgToolsForm): String =
        form.textFields.inputText.text
            .takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Enter text to process.")

    fun requireDetachedSignatureText(form: GpgToolsForm): String =
        form.textFields.detachedSignatureText.text
            .takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Enter a detached signature.")

    fun requireInputFile(form: GpgToolsForm): GpgToolsState.FileRef =
        form.files.inputFile
            ?: throw IllegalStateException("Choose an input file.")

    fun requireSignatureFile(form: GpgToolsForm): GpgToolsState.FileRef =
        form.files.signatureFile
            ?: throw IllegalStateException("Choose a signature file.")

    fun List<ResolvedGpgKey>.resolveSigningKey(
        selection: GpgToolsSelection,
    ): GpgOpenPgpPrivateKey {
        val selectedId = selection.selectedPrivateKeyId
        val key = firstOrNull { it.id == selectedId && it.canSign }
            ?: firstOrNull { it.canSign }
            ?: throw IllegalStateException("No signing-capable GPG key selected.")
        return key.toPrivateKey()
    }

    fun List<ResolvedGpgKey>.resolveOptionalEncryptionSigningKey(
        selection: GpgToolsSelection,
    ): GpgOpenPgpPrivateKey? {
        val selectedId = selection.selectedEncryptSigningKeyId
            ?: return null
        val key = firstOrNull { it.id == selectedId && it.canSign }
            ?: throw IllegalStateException("Selected signing-capable GPG key is no longer available.")
        return key.toPrivateKey()
    }

    fun List<ResolvedGpgKey>.resolveDecryptKeys(): List<GpgOpenPgpPrivateKey> {
        val keys = filter { it.canDecrypt }
            .map { it.toPrivateKey() }
        if (keys.isEmpty()) {
            throw IllegalStateException("No decryption-capable private key is available.")
        }
        return keys
    }

    fun List<ResolvedGpgKey>.resolveVerificationPublicKeys(
        customPublicKeys: List<GpgToolsState.CustomPublicKeyItem>,
    ): List<GpgOpenPgpPublicKey> =
        mapNotNull { it.toPublicKeyOrNull() } + customPublicKeys.toPublicKeyList()

    fun List<ResolvedGpgKey>.resolveEncryptionPublicKeys(
        selection: GpgToolsSelection,
        customPublicKeys: List<GpgToolsState.CustomPublicKeyItem>,
    ): List<GpgOpenPgpPublicKey> {
        val selectedIds = selection.selectedRecipientIds
        val keys = filter { it.id in selectedIds }
            .mapNotNull { it.toPublicKeyOrNull() } + customPublicKeys.toPublicKeyList()
        if (keys.isEmpty()) {
            throw IllegalStateException("Select a stored recipient or add a public key.")
        }
        return keys
    }

    fun handleTextOperation(
        keys: List<ResolvedGpgKey>,
        form: GpgToolsForm,
        selection: GpgToolsSelection,
        customPublicKeys: List<GpgToolsState.CustomPublicKeyItem>,
    ) {
        launchOperation {
            when (operation) {
                GpgToolsOperation.SIGN -> {
                    val text = requireInputText(form)
                    val privateKey = keys.resolveSigningKey(selection)
                    val output = withContext(Dispatchers.Default) {
                        val request = GpgOpenPgpSignTextRequest(
                            text = text,
                            privateKey = privateKey,
                        )
                        when (form.controls.signMode) {
                            GpgToolsSignMode.CLEAR_TEXT ->
                                openPgpService.clearSignText(request)

                            GpgToolsSignMode.DETACHED ->
                                openPgpService.signTextDetached(request)
                        }
                    }
                    showResultDialog(
                        output = buildOutput(
                            text = output,
                            signMode = form.controls.signMode,
                        ),
                    )
                }

                GpgToolsOperation.ENCRYPT -> {
                    val text = requireInputText(form)
                    val publicKeys = keys.resolveEncryptionPublicKeys(selection, customPublicKeys)
                    val signingPrivateKey = keys.resolveOptionalEncryptionSigningKey(selection)
                    val output = withContext(Dispatchers.Default) {
                        openPgpService.encryptText(
                            GpgOpenPgpEncryptTextRequest(
                                text = text,
                                publicKeys = publicKeys,
                                signingPrivateKey = signingPrivateKey,
                            ),
                        )
                    }
                    showResultDialog(
                        output = buildOutput(
                            text = output,
                            signMode = form.controls.signMode,
                        ),
                    )
                }

                GpgToolsOperation.VERIFY -> {
                    val text = requireInputText(form)
                    val publicKeys = keys.resolveVerificationPublicKeys(customPublicKeys)
                    val verification = withContext(Dispatchers.Default) {
                        when (form.controls.verifyMode) {
                            GpgToolsVerifyMode.INLINE -> openPgpVerifier.verifyClearSignedText(
                                GpgOpenPgpVerifyTextRequest(
                                    signedText = text,
                                    publicKeys = publicKeys,
                                ),
                            )

                            GpgToolsVerifyMode.DETACHED -> openPgpVerifier.verifyDetachedText(
                                GpgOpenPgpVerifyDetachedTextRequest(
                                    text = text,
                                    signature = requireDetachedSignatureText(form),
                                    publicKeys = publicKeys,
                                ),
                            )
                        }
                    }
                    showResultDialog(verification = toVerificationNote(verification))
                }

                GpgToolsOperation.DECRYPT -> {
                    val text = requireInputText(form)
                    val privateKeys = keys.resolveDecryptKeys()
                    val publicKeys = keys.resolveVerificationPublicKeys(customPublicKeys)
                    val result = withContext(Dispatchers.Default) {
                        openPgpService.decryptText(
                            GpgOpenPgpDecryptTextRequest(
                                encryptedText = text,
                                privateKeys = privateKeys,
                                publicKeys = publicKeys,
                            ),
                        )
                    }
                    showResultDialog(
                        output = buildOutput(
                            text = result.text,
                            signMode = form.controls.signMode,
                        ),
                        verification = result.verification?.let { toVerificationNote(it) },
                    )
                }
            }
        }
    }

    fun handleFileOperation(
        keys: List<ResolvedGpgKey>,
        form: GpgToolsForm,
        selection: GpgToolsSelection,
        customPublicKeys: List<GpgToolsState.CustomPublicKeyItem>,
    ) {
        try {
            when (operation) {
                GpgToolsOperation.SIGN -> {
                    val input = requireInputFile(form)
                    val privateKey = keys.resolveSigningKey(selection)
                    val armored = form.controls.armor
                    saveAs(fileName = input.signatureOutputName(armored)) { output ->
                        launchOperation {
                            withContext(Dispatchers.Default) {
                                openPgpService.signFile(
                                    GpgOpenPgpSignFileRequest(
                                        input = fileService.readFromFile(input.uri),
                                        signatureOutput = fileService.writeToFile(output.uri),
                                        privateKey = privateKey,
                                        armored = armored,
                                    ),
                                )
                            }
                        }
                    }
                }

                GpgToolsOperation.ENCRYPT -> {
                    val input = requireInputFile(form)
                    val publicKeys = keys.resolveEncryptionPublicKeys(selection, customPublicKeys)
                    val signingPrivateKey = keys.resolveOptionalEncryptionSigningKey(selection)
                    val armored = form.controls.armor
                    saveAs(fileName = input.encryptedOutputName(armored)) { output ->
                        launchOperation {
                            withContext(Dispatchers.Default) {
                                openPgpService.encryptFile(
                                    GpgOpenPgpEncryptFileRequest(
                                        input = fileService.readFromFile(input.uri),
                                        output = fileService.writeToFile(output.uri),
                                        publicKeys = publicKeys,
                                        fileName = input.name ?: "message",
                                        armored = armored,
                                        signingPrivateKey = signingPrivateKey,
                                    ),
                                )
                            }
                        }
                    }
                }

                GpgToolsOperation.VERIFY -> {
                    val input = requireInputFile(form)
                    val signature = requireSignatureFile(form)
                    val publicKeys = keys.resolveVerificationPublicKeys(customPublicKeys)
                    launchOperation {
                        val verification = withContext(Dispatchers.Default) {
                            openPgpVerifier.verifyFile(
                                GpgOpenPgpVerifyFileRequest(
                                    input = fileService.readFromFile(input.uri),
                                    signatureInput = fileService.readFromFile(signature.uri),
                                    publicKeys = publicKeys,
                                ),
                            )
                        }
                        showResultDialog(verification = toVerificationNote(verification))
                    }
                }

                GpgToolsOperation.DECRYPT -> {
                    val input = requireInputFile(form)
                    val privateKeys = keys.resolveDecryptKeys()
                    val publicKeys = keys.resolveVerificationPublicKeys(customPublicKeys)
                    saveAs(fileName = input.decryptedOutputName()) { output ->
                        launchOperation {
                            val result = withContext(Dispatchers.Default) {
                                openPgpService.decryptFile(
                                    GpgOpenPgpDecryptFileRequest(
                                        input = fileService.readFromFile(input.uri),
                                        output = fileService.writeToFile(output.uri),
                                        privateKeys = privateKeys,
                                        publicKeys = publicKeys,
                                    ),
                                )
                            }
                            showResultDialog(
                                verification = result.verification?.let { toVerificationNote(it) },
                            )
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            message(e)
        }
    }

    val keysFlow = getCiphers()
        .map { ciphers ->
            ciphers
                .mapNotNull { it.toGpgAgentSecretOrNull() }
                .sortedBy { it.cipher.name.lowercase() }
                .map { it.toResolvedGpgKey() }
        }
        .distinctUntilChanged()
        .shareInScreenScope()

    val controlsFlow = combine(
        scopeFlow,
        signModeFlow,
        verifyModeFlow,
        armorSink,
    ) { scope, signMode, verifyMode, armor ->
        GpgToolsControls(
            scope = scope,
            signMode = signMode,
            verifyMode = verifyMode,
            armor = armor,
        )
    }
        .distinctUntilChanged()

    val textFieldsFlow = combine(
        inputTextHandle.sink,
        detachedSignatureTextHandle.sink,
    ) { inputTextCell, detachedSignatureTextCell ->
        GpgToolsTextFields(
            inputText = TextFieldModel.of(
                cell = inputTextCell,
                handle = inputTextHandle,
                validated = Validated.Success(inputTextCell.text),
                hint = "",
            ),
            detachedSignatureText = TextFieldModel.of(
                cell = detachedSignatureTextCell,
                handle = detachedSignatureTextHandle,
                validated = Validated.Success(detachedSignatureTextCell.text),
                hint = "",
            ),
        )
    }
        .distinctUntilChanged()

    val filesFlow = combine(
        inputFileSink,
        signatureFileSink,
    ) { inputFile, signatureFile ->
        GpgToolsFiles(
            inputFile = inputFile,
            signatureFile = signatureFile,
        )
    }
        .distinctUntilChanged()

    val selectionFlow = combine(
        selectedPrivateKeyIdSink,
        selectedEncryptSigningKeyIdSink,
        selectedRecipientIdsSink,
    ) { selectedPrivateKeyId, selectedEncryptSigningKeyId, selectedRecipientIds ->
        GpgToolsSelection(
            selectedPrivateKeyId = selectedPrivateKeyId,
            selectedEncryptSigningKeyId = selectedEncryptSigningKeyId,
            selectedRecipientIds = selectedRecipientIds,
        )
    }
        .distinctUntilChanged()

    combine(
        keysFlow,
        selectionFlow,
    ) { keys, selection ->
        keys.resolveSelection(selection)
    }
        .distinctUntilChanged()
        .onEach { selection ->
            if (selectedPrivateKeyIdSink.value != selection.selectedPrivateKeyId) {
                selectedPrivateKeyIdSink.value = selection.selectedPrivateKeyId
            }
            if (selectedEncryptSigningKeyIdSink.value != selection.selectedEncryptSigningKeyId) {
                selectedEncryptSigningKeyIdSink.value = selection.selectedEncryptSigningKeyId
            }
            if (selectedRecipientIdsSink.value != selection.selectedRecipientIds) {
                selectedRecipientIdsSink.value = selection.selectedRecipientIds
            }
        }
        .launchIn(screenScope)

    val formFlow = combine(
        controlsFlow,
        textFieldsFlow,
        filesFlow,
        selectionFlow,
        busySink,
    ) { controls, textFields, files, selection, busy ->
        GpgToolsForm(
            controls = controls,
            textFields = textFields,
            files = files,
            selection = selection,
            busy = busy,
        )
    }
        .distinctUntilChanged()

    val scopes = GpgToolsScope.entries.toImmutableList()
    val signModes = GpgToolsSignMode.entries.toImmutableList()
    val verifyModes = GpgToolsVerifyMode.entries.toImmutableList()

    combine(
        formFlow,
        keysFlow,
        customPublicKeysSink,
    ) { form, keys, customPublicKeys ->
        val keyItems = keys
            .map { toStateItem(it) }
            .toImmutableList()
        val selection = keys.resolveSelection(form.selection)
        val showArmor = hasArmorOption && form.controls.scope == GpgToolsScope.FILE

        GpgToolsState(
            sideEffects = sideEffects,
            operation = operation,
            scope = form.controls.scope,
            scopes = scopes,
            signMode = form.controls.signMode,
            signModes = signModes,
            verifyMode = form.controls.verifyMode,
            verifyModes = verifyModes,
            armor = form.controls.armor,
            showArmor = showArmor,
            inputText = form.textFields.inputText,
            detachedSignatureText = form.textFields.detachedSignatureText,
            customPublicKeys = customPublicKeys.toImmutableList(),
            inputFile = form.files.inputFile,
            signatureFile = form.files.signatureFile,
            storedKeys = keyItems,
            selectedPrivateKeyId = selection.selectedPrivateKeyId,
            selectedEncryptSigningKeyId = selection.selectedEncryptSigningKeyId,
            selectedRecipientIds = selection.selectedRecipientIds,
            busy = form.busy,
            onScopeChange = {
                scopeSink.value = it.key
            },
            onSignModeChange = {
                signModeSink.value = it.key
            },
            onVerifyModeChange = {
                verifyModeSink.value = it.key
            },
            onArmorChange = {
                if (showArmor) {
                    armorSink.value = it
                }
            },
            onSelectPrivateKey = {
                selectedPrivateKeyIdSink.value = it
            },
            onSelectEncryptSigningKey = {
                selectedEncryptSigningKeyIdSink.value = it
            },
            onToggleRecipient = { id ->
                selectedRecipientIdsSink.update { current ->
                    if (id in current) {
                        current - id
                    } else {
                        current + id
                    }
                }
            },
            onSelectInputFile = {
                selectFile(inputFileSink)
            },
            onClearInputFile = {
                inputFileSink.value = null
            },
            onSelectSignatureFile = {
                selectFile(signatureFileSink)
            },
            onClearSignatureFile = {
                signatureFileSink.value = null
            },
            onAddPublicKey = {
                val intent = createGpgToolsPublicKeyDialogIntent(
                    args = GpgToolsPublicKeyRoute.Args(
                        publicKey = "",
                    ),
                ) { newValue ->
                    if (newValue.isBlank()) {
                        return@createGpgToolsPublicKeyDialogIntent
                    }

                    val nextId = customPublicKeyCounter + 1
                    customPublicKeyCounter = nextId
                    customPublicKeysSink.update { items ->
                        items + GpgToolsState.CustomPublicKeyItem(
                            id = "custom_public_key_$nextId",
                            publicKey = newValue,
                        )
                    }
                }
                navigate(intent)
            },
            onRemovePublicKey = { id ->
                customPublicKeysSink.update { items ->
                    items.filterNot { it.id == id }
                }
            },
            onRun = {
                if (form.controls.scope == GpgToolsScope.TEXT) {
                    handleTextOperation(
                        keys = keys,
                        form = form,
                        selection = selection,
                        customPublicKeys = customPublicKeys,
                    )
                } else {
                    handleFileOperation(
                        keys = keys,
                        form = form,
                        selection = selection,
                        customPublicKeys = customPublicKeys,
                    )
                }
            },
        )
    }
        .distinctUntilChanged()
        .map { Loadable.Ok(it) }
}

private data class GpgToolsControls(
    val scope: GpgToolsScope,
    val signMode: GpgToolsSignMode,
    val verifyMode: GpgToolsVerifyMode,
    val armor: Boolean,
)

private data class GpgToolsTextFields(
    val inputText: TextFieldModel,
    val detachedSignatureText: TextFieldModel,
)

private data class GpgToolsFiles(
    val inputFile: GpgToolsState.FileRef?,
    val signatureFile: GpgToolsState.FileRef?,
)

private data class GpgToolsSelection(
    val selectedPrivateKeyId: String?,
    val selectedEncryptSigningKeyId: String?,
    val selectedRecipientIds: Set<String>,
)

private data class GpgToolsForm(
    val controls: GpgToolsControls,
    val textFields: GpgToolsTextFields,
    val files: GpgToolsFiles,
    val selection: GpgToolsSelection,
    val busy: Boolean,
)

private data class ResolvedGpgKey(
    val id: String,
    val title: String,
    val privateKeyArmored: String?,
    val publicKeyArmored: String?,
    val fingerprint: String?,
    val metadataKeys: List<GpgAgentKeyMetadataKey>,
) {
    private val hasPrivateKey: Boolean
        get() = privateKeyArmored?.isNotBlank() == true

    val canSign: Boolean
        get() = hasPrivateKey && metadataKeys.any(GpgAgentKeyMetadataKey::canSign)

    val canDecrypt: Boolean
        get() = hasPrivateKey && metadataKeys.any(GpgAgentKeyMetadataKey::canDecrypt)

    fun toPrivateKey() = GpgOpenPgpPrivateKey(
        armored = privateKeyArmored
            ?: throw IllegalStateException("No private key material is available."),
        preferredFingerprint = fingerprint,
    )

    fun toPublicKeyOrNull() = publicKeyArmored
        ?.takeIf { it.isNotBlank() }
        ?.let(::GpgOpenPgpPublicKey)
}

private fun String.toGpgToolsScopeOrDefault(): GpgToolsScope =
    GpgToolsScope.entries.firstOrNull { it.key == this }
        ?: GpgToolsScope.TEXT

private fun String.toGpgToolsSignModeOrDefault(): GpgToolsSignMode =
    GpgToolsSignMode.entries.firstOrNull { it.key == this }
        ?: GpgToolsSignMode.CLEAR_TEXT

private fun String.toGpgToolsVerifyModeOrDefault(): GpgToolsVerifyMode =
    GpgToolsVerifyMode.entries.firstOrNull { it.key == this }
        ?: GpgToolsVerifyMode.INLINE

private fun List<ResolvedGpgKey>.resolveSelection(
    selection: GpgToolsSelection,
): GpgToolsSelection {
    val selectedPrivateKeyId = selection.selectedPrivateKeyId
        ?.takeIf { id -> any { it.id == id && it.canSign } }
        ?: firstOrNull { it.canSign }?.id
    val selectedEncryptSigningKeyId = selection.selectedEncryptSigningKeyId
        ?.takeIf { id -> any { it.id == id && it.canSign } }
    val availableRecipientIds = asSequence()
        .filter { it.publicKeyArmored?.isNotBlank() == true }
        .map { it.id }
        .toSet()
    return GpgToolsSelection(
        selectedPrivateKeyId = selectedPrivateKeyId,
        selectedEncryptSigningKeyId = selectedEncryptSigningKeyId,
        selectedRecipientIds = selection.selectedRecipientIds.intersect(availableRecipientIds),
    )
}

private fun GpgAgentSecret.toResolvedGpgKey(): ResolvedGpgKey {
    val metadataKeys = metadata.keys
        .filter { it.isUsableAgentKey }
    val fingerprint = fingerprint
        ?.normalizeGpgFingerprint()
        ?.takeIf { it.isNotBlank() }
        ?: metadataKeys.firstNotNullOfOrNull {
            it.fingerprint
                .normalizeGpgFingerprint()
                .takeIf(String::isNotBlank)
        }
    return ResolvedGpgKey(
        id = "${cipher.accountId}:${cipher.id}",
        title = cipher.name,
        privateKeyArmored = privateKeyArmored,
        publicKeyArmored = publicKeyArmored,
        fingerprint = fingerprint,
        metadataKeys = metadataKeys,
    )
}

private suspend fun RememberStateFlowScope.toStateItem(
    key: ResolvedGpgKey,
): GpgToolsState.KeyItem {
    val description = gpgKeyDescription(
        fingerprint = key.fingerprint,
    )
    return GpgToolsState.KeyItem(
        id = key.id,
        title = key.title,
        description = description,
        canSign = key.canSign,
        canDecrypt = key.canDecrypt,
        publicKeyAvailable = key.publicKeyArmored?.isNotBlank() == true,
    )
}

private fun List<GpgToolsState.CustomPublicKeyItem>.toPublicKeyList(): List<GpgOpenPgpPublicKey> =
    mapNotNull { item ->
        item.publicKey
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let(::GpgOpenPgpPublicKey)
    }

private fun GpgToolsState.FileRef.outputBaseName(): String =
    name?.takeIf { it.isNotBlank() } ?: "message"

private fun GpgToolsState.FileRef.encryptedOutputName(
    armored: Boolean,
): String = outputBaseName() + if (armored) {
    ".gpg.asc"
} else {
    ".gpg"
}

private fun GpgToolsState.FileRef.signatureOutputName(
    armored: Boolean,
): String = outputBaseName() + if (armored) {
    ".sig.asc"
} else {
    ".sig"
}

private fun GpgToolsState.FileRef.decryptedOutputName(): String {
    val fileName = outputBaseName()
    val lower = fileName.lowercase()
    val suffix = listOf(".gpg.asc", ".pgp.asc", ".asc", ".gpg", ".pgp")
        .firstOrNull { lower.endsWith(it) }
    return if (suffix != null) {
        fileName.dropLast(suffix.length)
            .takeIf { it.isNotBlank() }
            ?: "message.decrypted"
    } else {
        "$fileName.decrypted"
    }
}

private suspend fun RememberStateFlowScope.toVerificationNote(
    verification: GpgOpenPgpVerification,
): SimpleNote {
    val title = when (verification.status) {
        GpgOpenPgpVerificationStatus.VALID -> translate(Res.string.gpg_tools_valid_signature)
        GpgOpenPgpVerificationStatus.INVALID -> translate(Res.string.gpg_tools_invalid_signature)
        GpgOpenPgpVerificationStatus.MISSING_PUBLIC_KEY -> translate(Res.string.gpg_tools_missing_public_key)
    }
    val signer = verification.fingerprint
        ?.normalizeGpgFingerprint()
        ?.chunkedGpgFingerprint()
        ?: verification.keyId
    val lines = buildList {
        add(title)
        add(translate(Res.string.gpg_tools_signature_by, signer))
        verification.createdAt?.let {
            add(translate(Res.string.gpg_tools_signature_created_at, it.toString()))
        }
        if (verification.userIds.isNotEmpty()) {
            add(verification.userIds.joinToString(separator = "\n"))
        }
        verification.warnings
            .map { translateWarning(it) }
            .forEach(::add)
    }
    val noteType = when (verification.status) {
        GpgOpenPgpVerificationStatus.VALID -> SimpleNote.Type.OK
        GpgOpenPgpVerificationStatus.INVALID -> SimpleNote.Type.ERROR
        GpgOpenPgpVerificationStatus.MISSING_PUBLIC_KEY -> SimpleNote.Type.WARNING
    }
    return SimpleNote(
        type = noteType,
        text = lines.joinToString(separator = "\n"),
    )
}

private suspend fun RememberStateFlowScope.translateWarning(
    warning: GpgOpenPgpVerificationWarning,
): String = when (warning) {
    GpgOpenPgpVerificationWarning.KEY_REVOKED -> translate(Res.string.gpg_tools_warning_key_revoked)
    GpgOpenPgpVerificationWarning.KEY_EXPIRED -> translate(Res.string.gpg_tools_warning_key_expired)
    GpgOpenPgpVerificationWarning.SIGNATURE_EXPIRED -> translate(Res.string.gpg_tools_warning_signature_expired)
}
