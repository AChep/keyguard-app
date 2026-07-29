package com.artemchep.keyguard.feature.credentialexchange.imports

import com.artemchep.keyguard.common.service.credentialexchange.CxfImportSkipReason
import com.artemchep.keyguard.common.service.credentialexchange.CxfImportSkips
import com.artemchep.keyguard.feature.credentialexchange.CredentialExchangeSkippedNote
import com.artemchep.keyguard.feature.credentialexchange.toSkippedNotes
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.PluralStringResource

/**
 * Turns an import tally into the review screen's warning rows.
 *
 * `internal` so `CxfSkipNoteMappingTest` can cover the reason-to-row mapping.
 */
internal fun CxfImportSkips.toNotes(
    expandedIds: Set<String> = emptySet(),
    onToggle: (String) -> Unit = {},
): List<CredentialExchangeSkippedNote> = toSkippedNotes(
    expandedIds = expandedIds,
    onToggle = onToggle,
    labelRes = CxfImportSkipReason::labelRes,
)

/**
 * The review-screen label for a skip reason. Exhaustive on purpose: a new
 * reason does not compile until someone has chosen the label the user reads.
 */
internal fun CxfImportSkipReason.labelRes(): PluralStringResource = when (this) {
    CxfImportSkipReason.Passkey -> Res.plurals.skipped_passkeys_note
    CxfImportSkipReason.Otp -> Res.plurals.skipped_otp_note
    CxfImportSkipReason.SshKey -> Res.plurals.skipped_ssh_keys_note
    CxfImportSkipReason.UnknownCredential ->
        Res.plurals.skipped_unsupported_credentials_note
    CxfImportSkipReason.DuplicateCredential ->
        Res.plurals.skipped_duplicate_credentials_note
    CxfImportSkipReason.Item -> Res.plurals.skipped_items_note
    CxfImportSkipReason.Collection ->
        Res.plurals.skipped_folders_note
    CxfImportSkipReason.Account -> Res.plurals.skipped_accounts_note
}
