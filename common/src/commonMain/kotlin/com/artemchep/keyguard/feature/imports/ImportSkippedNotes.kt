package com.artemchep.keyguard.feature.imports

import com.artemchep.keyguard.common.service.imports.ImportSkipReason
import com.artemchep.keyguard.common.service.imports.ImportSkips
import com.artemchep.keyguard.feature.transfer.TransferSkippedNote
import com.artemchep.keyguard.feature.transfer.toSkippedNotes
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.PluralStringResource

/**
 * Turns an import tally into the review screen's warning rows.
 *
 * `internal` so `CxfSkipNoteMappingTest` can cover the reason-to-row mapping.
 */
internal fun ImportSkips.toNotes(
    expandedIds: Set<String> = emptySet(),
    onToggle: (String) -> Unit = {},
): List<TransferSkippedNote> = toSkippedNotes(
    expandedIds = expandedIds,
    onToggle = onToggle,
    labelRes = ImportSkipReason::labelRes,
)

/**
 * The review-screen label for a skip reason. Exhaustive on purpose: a new
 * reason does not compile until someone has chosen the label the user reads.
 */
@Suppress("CyclomaticComplexMethod") // One branch per skip reason, exhaustive by design.
internal fun ImportSkipReason.labelRes(): PluralStringResource = when (this) {
    ImportSkipReason.Passkey -> Res.plurals.skipped_passkeys_note
    ImportSkipReason.Otp -> Res.plurals.skipped_otp_note
    ImportSkipReason.SshKey -> Res.plurals.skipped_ssh_keys_note
    ImportSkipReason.Environment -> Res.plurals.skipped_unsupported_environments_note
    ImportSkipReason.UnknownCredential ->
        Res.plurals.skipped_unsupported_credentials_note
    ImportSkipReason.DuplicateCredential ->
        Res.plurals.skipped_duplicate_credentials_note
    ImportSkipReason.Attachment -> Res.plurals.skipped_attachments_note
    ImportSkipReason.PasswordHistory -> Res.plurals.skipped_password_history_note
    ImportSkipReason.EnvironmentReference ->
        Res.plurals.skipped_environment_variable_references_note
    ImportSkipReason.Item -> Res.plurals.skipped_items_note
    ImportSkipReason.Collection ->
        Res.plurals.skipped_folders_note
    ImportSkipReason.Account -> Res.plurals.skipped_accounts_note
    ImportSkipReason.File -> Res.plurals.skipped_import_files_note
    ImportSkipReason.MalformedRecord -> Res.plurals.skipped_malformed_csv_records_note
}
