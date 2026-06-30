package com.artemchep.keyguard.feature.navigation

import com.artemchep.keyguard.common.model.DFilter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A data-only, serializable identity for a [Route] — the "what + args" of a
 * navigation destination, decoupled from its `@Composable` rendering.
 */
@Serializable
sealed interface RouteDescriptor {
    @Serializable
    @SerialName("vault.cipher.view")
    data class VaultCipherView(
        val itemId: String,
        val accountId: String,
    ) : RouteDescriptor

    @Serializable
    @SerialName("vault.list")
    data class VaultList(
        val title: String? = null,
        val filter: DFilter? = null,
        val sortId: String? = null,
        val main: Boolean = false,
        // VaultRoute.Args.SearchBy enum name (defaults to ALL on an unknown value).
        val searchBy: String = "ALL",
        val trash: Boolean? = false,
        val archive: Boolean? = false,
        val preselect: Boolean = true,
        val canAddSecrets: Boolean = true,
        // false = VaultRoute; true = the archive/trash/* VaultListRoute
        val stacked: Boolean = false,
    ) : RouteDescriptor

    @Serializable
    @SerialName("send.view")
    data class SendView(
        val sendId: String,
        val accountId: String,
    ) : RouteDescriptor

    @Serializable
    @SerialName("vault.cipher.password_history")
    data class PasswordHistory(
        val itemId: String,
    ) : RouteDescriptor

    @Serializable
    @SerialName("generator.wordlist.view")
    data class WordlistView(
        val wordlistId: Long,
    ) : RouteDescriptor

    @Serializable
    @SerialName("vault.organizations")
    data class Organizations(
        val accountId: String,
    ) : RouteDescriptor

    @Serializable
    @SerialName("vault.collections")
    data class Collections(
        val accountId: String,
        val organizationId: String?,
    ) : RouteDescriptor

    @Serializable
    @SerialName("vault.equivalent_domains")
    data class EquivalentDomains(
        val accountId: String,
    ) : RouteDescriptor

    @Serializable
    @SerialName("vault.folders")
    data class Folders(
        val filter: DFilter? = null,
        val empty: Boolean = false,
    ) : RouteDescriptor

    @Serializable
    @SerialName("vault.duplicates")
    data class Duplicates(
        val filter: DFilter? = null,
    ) : RouteDescriptor

    @Serializable
    @SerialName("vault.export")
    data class Export(
        val title: String? = null,
        val filter: DFilter? = null,
    ) : RouteDescriptor

    @Serializable
    @SerialName("vault.cipher_filter.view")
    data class CipherFilterView(
        val filterId: String,
        val title: String,
    ) : RouteDescriptor

    @Serializable
    @SerialName("vault.attachments")
    data object Downloads : RouteDescriptor

    @Serializable
    @SerialName("watchtower.alerts")
    data object WatchtowerAlerts : RouteDescriptor

    @Serializable
    @SerialName("vault.cipher_filters")
    data object CipherFilters : RouteDescriptor

    @Serializable
    @SerialName("generator.history")
    data object GeneratorHistory : RouteDescriptor

    @Serializable
    @SerialName("generator.email_relay")
    data object EmailRelayList : RouteDescriptor

    @Serializable
    @SerialName("generator.wordlist.list")
    data object WordlistList : RouteDescriptor

    @Serializable
    @SerialName("feedback")
    data object Feedback : RouteDescriptor

    @Serializable
    @SerialName("directory.two_fa")
    data object TwoFaServices : RouteDescriptor

    @Serializable
    @SerialName("directory.passkeys")
    data object PasskeysServices : RouteDescriptor

    @Serializable
    @SerialName("directory.get_my_data")
    data object JustGetMyDataServices : RouteDescriptor

    @Serializable
    @SerialName("directory.delete_me")
    data object JustDeleteMeServices : RouteDescriptor

    /**
     * Fallback for a route that has not been
     * given a stable descriptor yet.
     */
    @Serializable
    @SerialName("unmapped")
    data class Unmapped(
        val type: String,
    ) : RouteDescriptor
}
