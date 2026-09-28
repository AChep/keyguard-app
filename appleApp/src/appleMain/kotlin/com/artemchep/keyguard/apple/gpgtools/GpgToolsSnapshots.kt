package com.artemchep.keyguard.apple.gpgtools

/** Swift-friendly projections of the shared GPG Tools state. */
data class GpgToolsSnapshot(
    val loaded: Boolean,
    /** encrypt | decrypt | sign | verify (the operation being edited). */
    val operation: String,
    /** text | file. */
    val scope: String,
    val scopes: List<GpgToolsTabSnapshot>,
    val signMode: String,
    val signModes: List<GpgToolsTabSnapshot>,
    val verifyMode: String,
    val verifyModes: List<GpgToolsTabSnapshot>,
    val armor: Boolean,
    val showArmor: Boolean,
    /** Current input-text value + revision (bumps only on programmatic writes; see BridgedText). */
    val inputText: String,
    val inputTextRevision: Int,
    /** Localized label for the input field, per operation. */
    val inputLabel: String,
    /** True for VERIFY + TEXT + DETACHED: a second field for the pasted signature. */
    val showSignatureField: Boolean,
    val signatureText: String,
    val signatureTextRevision: Int,
    /** GPG keys drawn from the vault (signing / recipient / decrypt candidates). */
    val storedKeys: List<GpgToolsKeySnapshot>,
    val selectedPrivateKeyId: String?,
    /** null == "do not sign" (ENCRYPT). */
    val selectedEncryptSigningKeyId: String?,
    val selectedRecipientIds: List<String>,
    val busy: Boolean,
    /** onRun is available and not busy. */
    val canRun: Boolean,
    val inputFile: GpgToolsFileSnapshot? = null,
    val signatureFile: GpgToolsFileSnapshot? = null,
    val customPublicKeys: List<GpgToolsPublicKeySnapshot> = emptyList(),
) {
    companion object {
        val empty = GpgToolsSnapshot(
            loaded = false,
            operation = "sign",
            scope = "text",
            scopes = emptyList(),
            signMode = "cleartext",
            signModes = emptyList(),
            verifyMode = "inline",
            verifyModes = emptyList(),
            armor = true,
            showArmor = false,
            inputText = "",
            inputTextRevision = 0,
            inputLabel = "",
            showSignatureField = false,
            signatureText = "",
            signatureTextRevision = 0,
            storedKeys = emptyList(),
            selectedPrivateKeyId = null,
            selectedEncryptSigningKeyId = null,
            selectedRecipientIds = emptyList(),
            busy = false,
            canRun = false,
        )
    }
}

/** A tab / segmented-control option (scope, sign mode, verify mode). */
data class GpgToolsTabSnapshot(
    val key: String,
    val title: String,
)

/** A vault GPG key offered as a signing key, recipient, or decrypt key. */
data class GpgToolsKeySnapshot(
    val id: String,
    val title: String,
    val description: String?,
    val canSign: Boolean,
    val canDecrypt: Boolean,
    val publicKeyAvailable: Boolean,
)

/**
 * A single note of a run's outcome: the signature check plus any warning the OpenPGP
 * pipeline raised (expired or revoked key, weak digest, policy conflict, ...).
 * [kind] is one of ok | error | warning.
 */
data class GpgToolsNoteSnapshot(
    val text: String,
    val kind: String,
)

/**
 * The outcome of a run, surfaced separately (the shared producer delivers it via a result
 * dialog navigation intent, which the controller intercepts). [outputText] is the produced
 * signed / encrypted / decrypted text (absent for verify-only); [notes] describe the
 * signature check and any warnings, in the order the shared screen renders them.
 */
data class GpgToolsResultSnapshot(
    val title: String,
    val notes: List<GpgToolsNoteSnapshot>,
    val outputLabel: String?,
    val outputText: String?,
    val incognito: Boolean,
    val canCopy: Boolean,
    val canSave: Boolean,
    val id: String = "",
    val file: GpgToolsFileSnapshot? = null,
)

/** Opaque file identity and display metadata; bytes never travel through snapshots. */
data class GpgToolsFileSnapshot(val id: String, val name: String, val size: Long?)
data class GpgToolsFilePickerRequest(val id: String, val destinationUri: String)
data class GpgToolsPublicKeyRequest(val id: String, val text: String)
data class GpgToolsPublicKeySnapshot(
    val id: String,
    val title: String,
    val fingerprint: String,
    val notes: List<GpgToolsNoteSnapshot> = emptyList(),
)
data class GpgToolsPublicKeyValidationSnapshot(
    val keys: List<GpgToolsPublicKeySnapshot>,
    val error: String?,
)
data class GpgToolsExportSnapshot(val id: String, val uri: String, val name: String)
