package com.artemchep.keyguard.feature.credentialexchange.export

import com.artemchep.keyguard.common.service.credentialexchange.CxfExportSkipReason
import com.artemchep.keyguard.common.service.credentialexchange.CxfExportSkips
import com.artemchep.keyguard.feature.credentialexchange.CredentialExchangeSkippedNote
import com.artemchep.keyguard.feature.credentialexchange.toSkippedNotes
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.PluralStringResource

/**
 * Turns an export tally into the review screen's warning rows.
 *
 * `internal` so `CxfSkipNoteMappingTest` can cover the reason-to-row mapping.
 */
internal fun CxfExportSkips.toNotes(
    expandedIds: Set<String> = emptySet(),
    onToggle: (String) -> Unit = {},
): List<CredentialExchangeSkippedNote> = toSkippedNotes(
    expandedIds = expandedIds,
    onToggle = onToggle,
    labelRes = CxfExportSkipReason::labelRes,
)

/**
 * The review-screen label for a skip reason. Exhaustive on purpose: a new
 * reason does not compile until someone has chosen the label the user reads.
 */
internal fun CxfExportSkipReason.labelRes(): PluralStringResource = when (this) {
    CxfExportSkipReason.Passkey -> Res.plurals.skipped_passkeys_note
    CxfExportSkipReason.Otp -> Res.plurals.skipped_otp_note
    CxfExportSkipReason.SshKey -> Res.plurals.skipped_ssh_keys_note
    CxfExportSkipReason.GpgKey -> Res.plurals.skipped_gpg_keys_note
    CxfExportSkipReason.Attachment -> Res.plurals.skipped_attachments_note
    CxfExportSkipReason.PasswordHistory ->
        Res.plurals.skipped_password_history_note
    CxfExportSkipReason.Archived -> Res.plurals.skipped_archived_items_note
    CxfExportSkipReason.Item -> Res.plurals.skipped_items_note
    CxfExportSkipReason.Account -> Res.plurals.skipped_accounts_note
}
