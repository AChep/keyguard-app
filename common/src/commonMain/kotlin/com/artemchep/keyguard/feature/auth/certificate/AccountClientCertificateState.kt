package com.artemchep.keyguard.feature.auth.certificate

import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.ui.SimpleNote
import kotlinx.coroutines.flow.Flow

/** Contains display data only; the certificate and its private key stay in the producer. */
data class AccountClientCertificateState(
    /** The saved certificate, or the replacement waiting to be saved; `null` if none. */
    val certificate: Certificate?,
    /** The shown certificate differs from the saved one. */
    val unsaved: Boolean,
    val note: SimpleNote?,
    val busy: Boolean,
    val onImport: (() -> Unit)?,
    val onFileDrop: ((FilePickerResult) -> Unit)?,
    val onRemove: (() -> Unit)?,
    val onSave: (() -> Unit)?,
    val confirmRemoval: Boolean,
    val onConfirmRemoval: () -> Unit,
    val onDismissRemoval: () -> Unit,
    val filePickerIntents: Flow<FilePickerIntent<*>>,
) {
    data class Certificate(
        val displayName: String,
        val issuerName: String?,
        /** `null` when the stored bundle can't be read. */
        val status: ClientCertificateStatus?,
        val validFrom: String?,
        val expiresOn: String?,
        /** Elapsed share of the validity period. */
        val progress: Float?,
        /** Copyable rows: subject, issuer, serial number, fingerprint, key type. */
        val details: List<VaultViewItem>,
    )
}
