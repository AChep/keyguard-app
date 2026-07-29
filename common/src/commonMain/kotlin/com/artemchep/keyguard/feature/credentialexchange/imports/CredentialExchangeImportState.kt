package com.artemchep.keyguard.feature.credentialexchange.imports

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.feature.credentialexchange.CredentialExchangeItem
import com.artemchep.keyguard.feature.credentialexchange.CredentialExchangeSkippedNote
import com.artemchep.keyguard.platform.LeContext

/**
 * The immutable UI model of the credential-exchange import flow. The flow is
 * a linear state machine; [stage] carries the current step and its callbacks.
 */
@Immutable
data class CredentialExchangeImportState(
    /**
     * The name of the account the items are imported into.
     */
    val accountTitle: String? = null,
    val stage: Stage,
) {
    @Immutable
    sealed interface Stage {
        /**
         * The idle explanation step. The platform call is anchored to the
         * activity of the [LeContext] captured at the click site, so a tap
         * always captures a fresh one — after a rotation, a failure or a
         * cancelled picker the button simply re-offers the flow.
         */
        @Immutable
        data class Start(
            val onImport: ((LeContext) -> Unit)?,
            val onLearnMore: (() -> Unit)?,
        ) : Stage

        /**
         * The platform provider picker is open, or the received payload is
         * being parsed.
         */
        @Immutable
        data object Loading : Stage

        /**
         * The received payload has been parsed; the user reviews what is
         * about to be created before anything is written to the vault.
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
            val skipped: List<CredentialExchangeSkippedNote>,
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
                val item: CredentialExchangeItem,
                val selected: Boolean,
                val onSelectedChange: ((Boolean) -> Unit)?,
            )
        }

        /**
         * Everything parsed, but there is nothing to import.
         */
        @Immutable
        data class Empty(
            val skipped: List<CredentialExchangeSkippedNote>,
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
    )
}
