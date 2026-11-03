package com.artemchep.keyguard.feature.imports

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.common.service.backup.BackupStoreKind
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.transfer.TransferItem
import com.artemchep.keyguard.feature.transfer.TransferSkippedNote
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.platform.LeContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The immutable UI model of the import flow. The flow is a linear state
 * machine; [stage] carries the current step and its callbacks.
 */
@Immutable
data class ImportState(
    /**
     * The name of the account the items are imported into.
     */
    val accountTitle: String? = null,
    val stage: Stage,
    val sideEffects: SideEffect = SideEffect(),
) {
    @Immutable
    data class SideEffect(
        val filePickerIntentFlow: Flow<FilePickerIntent<*>> = emptyFlow(),
    )

    @Immutable
    sealed interface Stage {
        /**
         * The idle step that lists where the items can come from.
         *
         * The platform credential transfer is anchored to the activity of the
         * [LeContext] captured at the click site, so a tap always captures a
         * fresh one — after a rotation, a failure or a cancelled picker the
         * row simply re-offers the flow.
         */
        @Immutable
        data class Start(
            /**
             * Starts a transfer from another app on this device, or `null`
             * on a platform without a credential-transfer broker.
             */
            val onCredentialExchange: ((LeContext) -> Unit)?,
            val formats: List<Format>,
            /**
             * The kinds of location a Keyguard backup can be read from.
             */
            val backupLocations: List<BackupLocation>,
            val onLearnMore: (() -> Unit)?,
        ) : Stage {
            /**
             * One file format to import. [title] and [text] are product and
             * file-type names, which are not translated.
             */
            @Immutable
            data class Format(
                val key: String,
                val title: String,
                val text: String?,
                val onClick: (() -> Unit)?,
            )

            /**
             * Asks for a Keyguard backup in a location of this [kind].
             */
            @Immutable
            data class BackupLocation(
                val kind: BackupStoreKind,
                val onClick: (() -> Unit)?,
            )
        }

        /**
         * The picked file or backup is password-protected; the user enters
         * its password. [text] says which password that is.
         */
        @Immutable
        data class Password(
            val text: String,
            val password: TextFieldModel,
            val onConfirm: (() -> Unit)?,
            val onCancel: (() -> Unit)?,
        ) : Stage

        /**
         * A Keyguard backup is open; the user picks the snapshot to import.
         */
        @Immutable
        data class Snapshots(
            val items: List<Snapshot>,
            val onConfirm: (() -> Unit)?,
            val onCancel: (() -> Unit)?,
        ) : Stage {
            /**
             * One snapshot, newest first. [createdAt] is already formatted.
             */
            @Immutable
            data class Snapshot(
                val key: String,
                val createdAt: String,
                val itemCount: Int,
                val attachmentCount: Int,
                val selected: Boolean,
                val onClick: (() -> Unit)?,
            )
        }

        /**
         * The source is being read: the platform provider picker is open, or
         * a received payload, a file or a backup snapshot is being parsed.
         */
        @Immutable
        data object Loading : Stage

        /**
         * The source has been parsed; the user reviews what is about to be
         * created before anything is written to the vault.
         */
        @Immutable
        data class Review(
            /**
             * The human-readable name of the exporting application, falling
             * back to its relying-party id or package name.
             */
            val exporterName: String,
            /**
             * The number of accounts the source document contained, when
             * greater than one — everything is merged into the single
             * target account.
             */
            val sourceAccountCount: Int,
            val folderCount: Int,
            val counts: Counts,
            /**
             * Every importable received item, in plan order, rendered the same way
             * the export review renders it and carrying its current selection.
             *
             * [counts] summarises only the selected items; the complete list remains
             * visible so a skipped item can be selected again.
             */
            val items: List<Item>,
            val skipped: List<TransferSkippedNote>,
            val isImporting: Boolean = false,
            val onConfirm: (() -> Unit)? = null,
            val onCancel: (() -> Unit)? = null,
            /**
             * Writes the raw received document to the downloads folder, for turning a
             * real-world payload into a test fixture.
             *
             * A debug affordance, and `null` in a release build — where the payload is
             * not even retained. Its absence is the only gate: the screen renders the
             * row when this is non-null.
             */
            val onSaveDebugPayload: (() -> Unit)? = null,
        ) : Stage {
            /**
             * One received item together with its import selection state.
             *
             * The callback is absent once the commit has been claimed, which keeps
             * the visible selection stable while the vault writes are running.
             */
            @Immutable
            data class Item(
                val item: TransferItem,
                val selected: Boolean,
                val onSelectedChange: ((Boolean) -> Unit)?,
            )
        }

        /**
         * Everything parsed, but there is nothing to import.
         */
        @Immutable
        data class Empty(
            val message: String,
            val skipped: List<TransferSkippedNote>,
            val onClose: (() -> Unit)?,
        ) : Stage

        /**
         * Everything the confirmed plan asked for has been written.
         */
        @Immutable
        data class Done(
            val itemCount: Int,
            /**
             * The number of folders created, reported next to [itemCount] because a
             * folders-only plan is importable: without it such an import ends on
             * "0 items have been added" while three folders were in fact created.
             */
            val folderCount: Int,
            val onClose: (() -> Unit)?,
        ) : Stage

        @Immutable
        data class Error(
            val message: String,
            val onRetry: ((LeContext) -> Unit)?,
        ) : Stage
    }

    /**
     * The number of vault items about to be created, per kind, plus the
     * passkey/one-time-password totals across the logins.
     */
    @Immutable
    data class Counts(
        val loginCount: Int = 0,
        val passkeyCount: Int = 0,
        val otpCount: Int = 0,
        val cardCount: Int = 0,
        val identityCount: Int = 0,
        val noteCount: Int = 0,
        val sshKeyCount: Int = 0,
        val gpgKeyCount: Int = 0,
        val environmentCount: Int = 0,
    )
}
