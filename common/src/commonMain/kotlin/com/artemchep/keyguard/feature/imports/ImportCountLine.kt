package com.artemchep.keyguard.feature.imports

import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.PluralStringResource

/**
 * One count line of the review or the result. Every platform renders these
 * lines rather than its own list, so a new kind of item reaches all of them.
 */
data class ImportCountLine(
    val kind: Kind,
    val count: Int,
    val res: PluralStringResource,
) {
    enum class Kind {
        Login,
        Passkey,
        Otp,
        Card,
        Identity,
        Note,
        SshKey,
        GpgKey,
        Environment,
        Folder,
    }
}

/**
 * The count lines of the review, leaving out the kinds it has none of.
 */
fun ImportState.Stage.Review.countLines(): List<ImportCountLine> = listOf(
    ImportCountLine(
        kind = ImportCountLine.Kind.Login,
        count = counts.loginCount,
        res = Res.plurals.import_review_logins_note,
    ),
    ImportCountLine(
        kind = ImportCountLine.Kind.Passkey,
        count = counts.passkeyCount,
        res = Res.plurals.import_review_passkeys_note,
    ),
    ImportCountLine(
        kind = ImportCountLine.Kind.Otp,
        count = counts.otpCount,
        res = Res.plurals.import_review_otp_note,
    ),
    ImportCountLine(
        kind = ImportCountLine.Kind.Card,
        count = counts.cardCount,
        res = Res.plurals.import_review_cards_note,
    ),
    ImportCountLine(
        kind = ImportCountLine.Kind.Identity,
        count = counts.identityCount,
        res = Res.plurals.import_review_identities_note,
    ),
    ImportCountLine(
        kind = ImportCountLine.Kind.Note,
        count = counts.noteCount,
        res = Res.plurals.import_review_notes_note,
    ),
    ImportCountLine(
        kind = ImportCountLine.Kind.SshKey,
        count = counts.sshKeyCount,
        res = Res.plurals.import_review_ssh_keys_note,
    ),
    ImportCountLine(
        kind = ImportCountLine.Kind.GpgKey,
        count = counts.gpgKeyCount,
        res = Res.plurals.import_review_gpg_keys_note,
    ),
    ImportCountLine(
        kind = ImportCountLine.Kind.Environment,
        count = counts.environmentCount,
        res = Res.plurals.import_review_environments_note,
    ),
    folderCountLine(folderCount),
).filter { it.count > 0 }

/**
 * The count lines of the result, leaving out the kinds it has none of. The
 * folders line is the whole result of a folders-only import, whose item
 * sentence legitimately reads zero.
 */
fun ImportState.Stage.Done.countLines(): List<ImportCountLine> = listOf(
    folderCountLine(folderCount),
).filter { it.count > 0 }

private fun folderCountLine(count: Int) = ImportCountLine(
    kind = ImportCountLine.Kind.Folder,
    count = count,
    res = Res.plurals.import_review_folders_note,
)
