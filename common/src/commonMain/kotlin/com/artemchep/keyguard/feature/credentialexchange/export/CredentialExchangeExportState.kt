package com.artemchep.keyguard.feature.credentialexchange.export

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.feature.credentialexchange.CredentialExchangeItem
import com.artemchep.keyguard.feature.credentialexchange.CredentialExchangeSkippedNote

/**
 * The immutable UI model of the credential-exchange review screen.
 */
@Immutable
data class CredentialExchangeExportState(
    val stage: Stage,
) {
    /**
     * Every outcome resolves to a rendered stage, and on each of them the requesting
     * app has either already been answered ([Error]) or can be answered by the user,
     * so the screen can never sit on something it has no way to leave. [Mapping] is
     * the only stage that is still working, and it carries a deny for that reason.
     *
     * [Locked] is the exception to the "can be answered by the user" clause, and
     * deliberately so: it renders as the whole screen, and the host's own chrome —
     * the consent header's Cancel, or back — is what answers on its behalf.
     */
    @Immutable
    sealed interface Stage {
        /**
         * The user-verification gate has not been passed, so nothing has been
         * mapped and nothing can be handed over yet.
         *
         * Rendered as its own surface rather than as review content: this is the
         * same inline password/biometric/YubiKey form the passkey provider flow
         * shows, and it replaces the review scaffold entirely while it is up. It
         * carries no deny of its own because it is not a dialog — there is nothing
         * to dismiss, and the host's consent header already offers the way out.
         */
        @Immutable
        data class Locked(
            /**
             * Opens the gate. Called once the user has proven presence.
             */
            val onAuthenticated: () -> Unit,
        ) : Stage

        /**
         * Every stage the review scaffold can render, i.e. everything after the
         * gate. Separating them is what lets the scaffold's `when`s stay exhaustive
         * without carrying a dead [Locked] branch that can never reach them.
         */
        @Immutable
        sealed interface Reviewable : Stage

        /**
         * The gate has been passed and the vault is being mapped: every passkey
         * key is decoded and every SSH key converted, which takes seconds on a
         * large vault.
         *
         * A stage of its own rather than staying on [Locked], because the gate is a
         * different surface: leaving its password form on screen after verification
         * succeeded reads as a prompt that failed, and a user who read it that way
         * cancelled a transfer that was on its way.
         */
        @Immutable
        data class Mapping(
            /**
             * Declines the transfer. The mapping is the only stage that is still
             * working, so this is what keeps it from being a dead end.
             */
            val onDeny: () -> Unit,
        ) : Reviewable

        /**
         * The vault has been mapped and the user reviews what is about to be
         * handed over.
         */
        @Immutable
        data class Review(
            /**
             * Every credential about to be handed over, flat.
             *
             * Not grouped per account: the screen is scoped to the single account
             * the picked entry addresses, and the activity's own consent subtitle
             * already names it.
             */
            val items: List<CredentialExchangeItem>,
            /**
             * The warning rows explaining what will not be transferred, already
             * ordered and already worded; empty when nothing is lost.
             */
            val skipped: List<CredentialExchangeSkippedNote>,
            /**
             * `true` once the user has confirmed and the host is building the
             * response; the confirm action stays disabled while it is set.
             */
            val isExporting: Boolean = false,
            /**
             * Confirms the transfer, or `null` when there is nothing to
             * transfer or a transfer is already in progress.
             */
            val onConfirm: (() -> Unit)? = null,
            /**
             * Denies the transfer.
             */
            val onDeny: (() -> Unit)? = null,
        ) : Reviewable

        /**
         * The account the picked entry addresses is gone — deleted, or hidden since
         * the entry was registered.
         *
         * Unlike [Error] this arms no failure: nothing malfunctioned, so closing
         * reports the user's own cancellation. That asymmetry is the point of having
         * a separate stage rather than reusing the error one.
         */
        @Immutable
        data class Unavailable(
            val message: String,
            val onClose: () -> Unit,
        ) : Reviewable

        /**
         * The vault could not be mapped. The failure result is already armed
         * with the host, so every exit path — the toolbar close, back, or the
         * system finishing the activity — reports the failure rather than a
         * cancellation the user never performed. Offers no retry: the mapper is
         * deterministic over the same vault.
         */
        @Immutable
        data class Error(
            val message: String,
        ) : Reviewable
    }
}
