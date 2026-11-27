package com.artemchep.keyguard.feature.secretscan

import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.common.service.crypto.normalizeSshFingerprint
import com.artemchep.keyguard.common.service.secretscan.PlaintextSecretScanner
import com.artemchep.keyguard.common.service.secretscan.SecretScanEvent
import com.artemchep.keyguard.common.service.secretscan.SecretScanItem
import com.artemchep.keyguard.common.service.secretscan.SecretScanKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/** One run of the scan, or none yet. */
internal data class ScanSnapshot(
    val phase: SecretScanState.Phase,
    /** The findings sorted by path; empty until the scan is done. */
    val items: List<SecretScanItem> = emptyList(),
)

internal data class ImportStatus(
    val importing: Boolean = false,
    /** The fingerprints of the keys imported on this screen, by path. */
    val sshFingerprints: Map<String, String> = emptyMap(),
)

/**
 * Runs the scan. The progress goes to [checked] rather than to the
 * snapshots, and the findings come at once when the scan is done, so that
 * neither rebuilds the rows while the scan runs.
 */
internal fun PlaintextSecretScanner.scanSnapshots(
    kind: SecretScanKind,
    extraRoots: List<String>,
    checked: MutableStateFlow<Int>,
): Flow<ScanSnapshot> = flow {
    checked.value = 0
    emit(ScanSnapshot(phase = SecretScanState.Phase.Scanning))
    val items = mutableListOf<SecretScanItem>()
    scan(kind, extraRoots).collect { event ->
        when (event) {
            is SecretScanEvent.Progress -> checked.value = event.checked
            is SecretScanEvent.Found -> items += event.item
            is SecretScanEvent.Done -> {
                val snapshot = ScanSnapshot(
                    phase = SecretScanState.Phase.Done(event.scannedRoots),
                    items = items.sortedBy { it.path },
                )
                emit(snapshot)
            }
        }
    }
}

/**
 * The rows of the [items], in their order, all unchecked; see
 * [withSelection].
 *
 * @param vaultIndex see [secretScanVaultIndex].
 * @param importedSshFingerprints the fingerprints of the keys imported on
 *   this screen, by path.
 */
internal fun secretScanRows(
    items: List<SecretScanItem>,
    vaultIndex: Set<String>,
    importedSshFingerprints: Map<String, String>,
): List<SecretScanState.Item> = items.mapIndexed { index, item ->
    SecretScanState.Item(
        key = item.path,
        title = item.displayPath,
        shapeState = getShapeState(
            list = items,
            index = index,
            predicate = { _, _ -> true },
        ),
        chips = item.chips(item.isInVault(vaultIndex, importedSshFingerprints)),
        selected = false,
    )
}

/** The rows with the [selection] applied. */
internal fun List<SecretScanState.Item>.withSelection(
    selection: Map<String, Boolean>,
): List<SecretScanState.Item> = map { row ->
    val selected = selection[row.key] ?: false
    if (row.selected == selected) row else row.copy(selected = selected)
}

/** Whether the finding is picked for import. Rows start unchecked. */
internal fun SecretScanItem.isSelected(
    selection: Map<String, Boolean>,
): Boolean = selection[path] ?: false

private fun SecretScanItem.isInVault(
    vaultIndex: Set<String>,
    importedSshFingerprints: Map<String, String>,
): Boolean = when (this) {
    is SecretScanItem.SshKey -> {
        val fingerprint = fingerprint
            ?: importedSshFingerprints[path]
        fingerprint?.normalizeSshFingerprint() in vaultIndex
    }

    is SecretScanItem.DotEnv -> literalDigest in vaultIndex
}

private fun SecretScanItem.chips(
    inVault: Boolean,
): List<SecretScanState.Chip> = buildList {
    when (val item = this@chips) {
        is SecretScanItem.SshKey -> add(
            if (item.encrypted) {
                SecretScanState.Chip.Passphrase
            } else {
                SecretScanState.Chip.NoPassphrase
            },
        )

        is SecretScanItem.DotEnv -> add(
            SecretScanState.Chip.Variables(item.variableCount),
        )
    }
    if (inVault) add(SecretScanState.Chip.InVault)
}
