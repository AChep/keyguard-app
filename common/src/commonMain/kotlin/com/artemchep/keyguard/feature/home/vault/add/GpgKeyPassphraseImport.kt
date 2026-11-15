package com.artemchep.keyguard.feature.home.vault.add

import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.service.crypto.GpgKeyImportComponent
import com.artemchep.keyguard.common.service.crypto.GpgKeyImportError
import com.artemchep.keyguard.common.service.crypto.GpgKeyImportResult
import com.artemchep.keyguard.common.service.crypto.gpgShortKeyId
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.confirmation.awaitConfirmation
import com.artemchep.keyguard.feature.confirmation.awaitConfirmationDialog
import com.artemchep.keyguard.feature.gpgkey.formatGpgAlgorithm
import com.artemchep.keyguard.feature.gpgkey.translateGpgKeyCapabilities
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*

/**
 * Unlocks an encrypted GPG key. Asks for the key passphrase first, then, if parts of the key
 * are still locked, for the passphrase of each of those parts. Every passphrase is tried on
 * every locked part, so a passphrase shared by several subkeys is entered once.
 *
 * A prompt returns `null` when the user cancels it; the import then stops silently.
 * [promptComponentPassphrases] returns the entered values keyed by component fingerprint.
 */
internal suspend fun importGpgKeyWithPassphrases(
    initial: GpgKeyImportResult.NeedsPassphrase,
    importGpgKey: suspend (passphrases: List<String>) -> GpgKeyImportResult,
    promptPassphrase: suspend (formatLabel: String, invalid: Boolean) -> String?,
    promptComponentPassphrases: suspend (
        components: List<GpgKeyImportComponent>,
        invalidFingerprints: Set<String>,
    ) -> Map<String, String>?,
    onSuccess: suspend (GeneratedGpgKey) -> Unit,
    onError: suspend (GpgKeyImportError) -> Unit,
) {
    // Components the user typed a passphrase for in the last component prompt.
    var filledFingerprints = emptySet<String>()
    var passphrases = promptPassphrase(initial.formatLabel, false)?.let(::listOf)
    while (passphrases != null) {
        val tried = passphrases
        passphrases = when (val result = importGpgKey(tried)) {
            is GpgKeyImportResult.Success -> {
                onSuccess(result.gpgKey)
                null
            }

            // Only the first step tries a single passphrase. If
            // it unlocked nothing, it is most likely a typo.
            is GpgKeyImportResult.Error -> if (
                result.reason == GpgKeyImportError.InvalidPassphrase && tried.size == 1
            ) {
                promptPassphrase(initial.formatLabel, true)?.let(::listOf)
            } else {
                onError(result.reason)
                null
            }

            // Keeps only the passphrases that unlocked something, then appends the new ones.
            is GpgKeyImportResult.NeedsPassphrase -> {
                val carried = tried
                    .filterIndexed { index, _ -> index in result.unlockingPassphraseIndexes }
                val lockedFingerprints = result.lockedComponents
                    .mapTo(mutableSetOf()) { it.fingerprint }
                var entered: Map<String, String>?
                var next: List<String>
                // Every carried passphrase was already tried on every locked
                // component, so ask again until there is something new.
                do {
                    entered = promptComponentPassphrases(
                        result.lockedComponents,
                        filledFingerprints intersect lockedFingerprints,
                    )
                    val filled = entered.orEmpty().filterValues { it.isNotEmpty() }
                    filledFingerprints = filled.keys
                    next = (carried + filled.values).distinct()
                } while (entered != null && next.size == carried.size)
                next.takeIf { entered != null }
            }
        }
    }
}

/** Asks for the passphrase of the whole key, with an error after a wrong one. */
internal suspend fun RememberStateFlowScope.awaitGpgKeyPassphrase(
    confirmationRouteFactory: ConfirmationRouteFactory,
    key: String,
    formatLabel: String,
    invalid: Boolean,
): String? {
    val protocol = translate(Res.string.protocol_gpg)
    val hint = translate(Res.string.key_import_passphrase_hint, protocol)
    return awaitConfirmationDialog(
        confirmationRouteFactory = confirmationRouteFactory,
        item = ConfirmationRoute.Args.Item.StringItem(
            key = key,
            title = translate(Res.string.key_import_passphrase_title),
            hint = hint,
            description = if (invalid) {
                translate(Res.string.key_import_error_invalid_passphrase, protocol)
            } else {
                null
            },
            type = ConfirmationRoute.Args.Item.StringItem.Type.Password,
            canBeEmpty = false,
            ephemeral = true,
        ),
        title = hint,
        message = translate(
            Res.string.gpg_key_import_passphrase_dialog_message,
            formatLabel,
        ),
    )
}

/**
 * Asks for the passphrases of the still locked [components], one field each.
 * Returns the entered values keyed by component fingerprint.
 */
internal suspend fun RememberStateFlowScope.awaitGpgKeyComponentPassphrases(
    confirmationRouteFactory: ConfirmationRouteFactory,
    keyPrefix: String,
    components: List<GpgKeyImportComponent>,
    invalidFingerprints: Set<String>,
): Map<String, String>? {
    fun itemKey(component: GpgKeyImportComponent) = "$keyPrefix.${component.fingerprint}"

    val invalidText = translate(Res.string.gpg_key_import_component_passphrase_invalid)
    val canBeEmpty = components.size > 1
    val items = components.map { component ->
        val shortKeyId = component.keyId.gpgShortKeyId()
        val title = if (component.primary) {
            translate(Res.string.gpg_key_import_component_primary_key, shortKeyId)
        } else {
            translate(Res.string.gpg_key_expiry_subkey, shortKeyId)
        }
        val capabilities = translateGpgKeyCapabilities(
            canSign = component.canSign,
            canEncrypt = component.canEncrypt,
        )
        val algorithm = formatGpgAlgorithm(
            algorithm = component.algorithm,
            bitStrength = component.bitStrength,
        )
        val summary = (capabilities + listOfNotNull(algorithm))
            .joinToString(separator = " · ")
        val description = listOfNotNull(
            summary.takeIf { it.isNotEmpty() },
            invalidText.takeIf { component.fingerprint in invalidFingerprints },
        ).joinToString(separator = "\n")
        ConfirmationRoute.Args.Item.StringItem(
            key = itemKey(component),
            title = title,
            description = description.takeIf { it.isNotEmpty() },
            type = ConfirmationRoute.Args.Item.StringItem.Type.Password,
            canBeEmpty = canBeEmpty,
            ephemeral = true,
        )
    }
    val result = awaitConfirmation(
        confirmationRouteFactory = confirmationRouteFactory,
        args = ConfirmationRoute.Args(
            title = translate(Res.string.gpg_key_import_component_passphrases_title),
            message = translate(Res.string.gpg_key_import_component_passphrases_dialog_message),
            items = items,
        ),
    ) as? ConfirmationResult.Confirm
        ?: return null
    return components.associate { component ->
        component.fingerprint to (result.data[itemKey(component)] as? String ?: "")
    }
}
