package com.artemchep.keyguard.feature.secretscan

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.common.service.secretscan.SecretScanKind
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

@Immutable
data class SecretScanState(
    val kind: SecretScanKind,
    /**
     * The number of files checked so far. A flow, so that the progress
     * does not rebuild the rows.
     */
    val checkedFlow: StateFlow<Int>,
    val phase: Phase,
    /** The folders to scan for display: `~`, unless Flatpak, plus the added folders. */
    val roots: List<String>,
    /** `false` on Flatpak, where only added folders are scanned. */
    val defaultRootsAvailable: Boolean,
    val items: List<Item>,
    val selectedCount: Int,
    val importing: Boolean,
    /** Picks the row with the key for import, or not; `null` while busy. */
    val onSelectedChange: ((String, Boolean) -> Unit)?,
    val onAddFolder: (() -> Unit)?,
    /** Runs the scan, or runs it again; `null` while importing. */
    val onScan: (() -> Unit)?,
    /** Imports the selected rows; `null` while importing. */
    val onImport: (() -> Unit)?,
    val filePickerIntentFlow: Flow<FilePickerIntent<*>>,
) {
    sealed interface Phase {
        /** No scan has run yet. */
        data object Idle : Phase

        /** The disk walk runs. */
        data object Scanning : Phase

        data class Done(
            /** The scanned roots for display. */
            val scannedRoots: List<String>,
        ) : Phase
    }

    @Immutable
    data class Item(
        val key: String,
        /** The path, with `~` for the home folder. */
        val title: String,
        val shapeState: Int,
        val chips: List<Chip>,
        val selected: Boolean,
    )

    sealed interface Chip {
        data object NoPassphrase : Chip

        data object Passphrase : Chip

        data class Variables(val count: Int) : Chip

        data object InVault : Chip
    }
}
