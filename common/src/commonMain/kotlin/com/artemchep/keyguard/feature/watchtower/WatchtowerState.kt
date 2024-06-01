package com.artemchep.keyguard.feature.watchtower

import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.home.vault.model.FilterItem
import com.artemchep.keyguard.ui.ContextItem
import kotlinx.coroutines.flow.StateFlow

data class WatchtowerState(
    val revision: Int = 0,
    val filter: Filter = Filter(),
    val actions: List<ContextItem> = emptyList(),
    val content: Loadable<Content> = Loadable.Loading,
) {
    data class Filter(
        val items: List<FilterItem> = emptyList(),
        val onClear: (() -> Unit)? = null,
        val onSave: (() -> Unit)? = null,
    )

    data class Content(
        val unreadThreats: StateFlow<Loadable<UnreadThreats?>>,
        val unsecureWebsites: StateFlow<Loadable<UnsecureWebsites?>>,
        val duplicateWebsites: StateFlow<Loadable<DuplicateWebsites?>>,
        val inactiveTwoFactorAuth: StateFlow<Loadable<InactiveTwoFactorAuth?>>,
        val inactivePasskey: StateFlow<Loadable<InactivePasskey?>>,
        val accountCompromised: StateFlow<Loadable<CompromisedAccounts?>>,
        val pwned: StateFlow<Loadable<PwnedPasswords?>>,
        val pwnedWebsites: StateFlow<Loadable<PwnedWebsites?>>,
        val reused: StateFlow<Loadable<ReusedPasswords?>>,
        val incompleteItems: StateFlow<Loadable<IncompleteItems?>>,
        val expiringItems: StateFlow<Loadable<ExpiringItems?>>,
        val duplicateItems: StateFlow<Loadable<DuplicateItems?>>,
        val trashedItems: StateFlow<Loadable<TrashedItems?>>,
        val emptyItems: StateFlow<Loadable<EmptyItems?>>,
        val strength: StateFlow<Loadable<PasswordStrength?>>,
    ) {
        data class UnsecureWebsites(
            val revision: Int,
            val count: Int,
            val new: Int,
            val onClick: (() -> Unit)? = null,
        )

        data class DuplicateWebsites(
            val revision: Int,
            val count: Int,
            val new: Int,
            val onClick: (() -> Unit)? = null,
        )

        data class InactiveTwoFactorAuth(
            val revision: Int,
            val count: Int,
            val new: Int,
            val onClick: (() -> Unit)? = null,
        )

        data class InactivePasskey(
            val revision: Int,
            val count: Int,
            val new: Int,
            val onClick: (() -> Unit)? = null,
        )

        data class PwnedPasswords(
            val revision: Int,
            val count: Int,
            val new: Int,
            val onClick: (() -> Unit)? = null,
        )

        data class PwnedWebsites(
            val revision: Int,
            val count: Int,
            val new: Int,
            val onClick: (() -> Unit)? = null,
        )

        data class CompromisedAccounts(
            val revision: Int,
            val count: Int,
            val new: Int,
            val onClick: (() -> Unit)? = null,
        )

        data class ReusedPasswords(
            val revision: Int,
            val count: Int,
            val new: Int,
            val onClick: (() -> Unit)? = null,
        )

        data class IncompleteItems(
            val revision: Int,
            val count: Int,
            val new: Int,
            val onClick: (() -> Unit)? = null,
        )

        data class ExpiringItems(
            val revision: Int,
            val count: Int,
            val new: Int,
            val onClick: (() -> Unit)? = null,
        )

        data class DuplicateItems(
            val revision: Int,
            val count: Int,
            val new: Int,
            val onClick: (() -> Unit)? = null,
        )

        data class TrashedItems(
            val revision: Int,
            val count: Int,
            val onClick: (() -> Unit)? = null,
        )

        data class EmptyItems(
            val revision: Int,
            val count: Int,
            val new: Int,
            val onClick: (() -> Unit)? = null,
        )

        data class PasswordStrength(
            val revision: Int,
            val items: List<Item>,
        ) {
            data class Item(
                val score: com.artemchep.keyguard.common.model.PasswordStrength.Score,
                val count: Int,
                val new: Int,
                val onClick: (() -> Unit)?,
            )
        }

        data class UnreadThreats(
            val revision: Int,
            val count: Int,
            val onClick: (() -> Unit)? = null,
        )
    }
}
