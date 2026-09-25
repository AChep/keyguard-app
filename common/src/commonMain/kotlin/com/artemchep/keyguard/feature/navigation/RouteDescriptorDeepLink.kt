package com.artemchep.keyguard.feature.navigation

const val DEEP_LINK_SCHEME = "keyguard"

fun RouteDescriptor.toDeepLink(): String? {
    fun url(path: String, vararg params: Pair<String, String?>): String {
        val query = params
            .mapNotNull { (k, v) -> v?.let { "$k=$it" } }
            .joinToString(separator = "&")
        return if (query.isEmpty()) "$DEEP_LINK_SCHEME://$path" else "$DEEP_LINK_SCHEME://$path?$query"
    }
    return when (this) {
        is RouteDescriptor.VaultCipherView -> url("cipher", "itemId" to itemId, "accountId" to accountId)
        is RouteDescriptor.SendView -> url("send", "sendId" to sendId, "accountId" to accountId)
        is RouteDescriptor.PasswordHistory -> url("cipher/password-history", "itemId" to itemId)
        is RouteDescriptor.WordlistView -> url("generator/wordlist", "wordlistId" to wordlistId.toString())
        is RouteDescriptor.Organizations -> url("organizations", "accountId" to accountId)
        is RouteDescriptor.Collections -> url(
            "collections",
            "accountId" to accountId,
            "organizationId" to organizationId,
        )
        is RouteDescriptor.EquivalentDomains -> url("equivalent-domains", "accountId" to accountId)
        is RouteDescriptor.CipherFilterView -> url("filter", "filterId" to filterId)
        RouteDescriptor.Downloads -> url("downloads")
        is RouteDescriptor.WatchtowerAlerts -> if (filter == null) url("watchtower/alerts") else null
        RouteDescriptor.CipherFilters -> url("filters")
        RouteDescriptor.GeneratorHistory -> url("generator/history")
        RouteDescriptor.EmailRelayList -> url("generator/email-relay")
        RouteDescriptor.WordlistList -> url("generator/wordlists")
        RouteDescriptor.Feedback -> url("feedback")
        RouteDescriptor.Subscriptions -> url("settings/subscriptions")
        RouteDescriptor.TwoFaServices -> url("directory/two-fa")
        RouteDescriptor.PasskeysServices -> url("directory/passkeys")
        RouteDescriptor.JustGetMyDataServices -> url("directory/get-my-data")
        RouteDescriptor.JustDeleteMeServices -> url("directory/delete-me")

        // Carry filters/sorts or no stable target → not meaningful as a deep link.
        RouteDescriptor.PasswordMemory,
        is RouteDescriptor.VaultList,
        is RouteDescriptor.SendList,
        is RouteDescriptor.Generator,
        is RouteDescriptor.Watchtower,
        RouteDescriptor.GpgTools,
        RouteDescriptor.Settings,
        is RouteDescriptor.Folders,
        is RouteDescriptor.Duplicates,
        is RouteDescriptor.Export,
        is RouteDescriptor.Unmapped,
        -> null
    }
}

fun routeDescriptorFromDeepLink(url: String): RouteDescriptor? {
    val body = url.substringAfter("$DEEP_LINK_SCHEME://", missingDelimiterValue = "")
    if (body.isEmpty()) return null
    val path = body.substringBefore('?').trim('/')
    val query = body.substringAfter('?', missingDelimiterValue = "")
    val params: Map<String, String> = if (query.isEmpty()) {
        emptyMap()
    } else {
        query.split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
        }.toMap()
    }
    return when (path) {
        "cipher" -> {
            val itemId = params["itemId"]
            val accountId = params["accountId"]
            if (itemId != null && accountId != null) {
                RouteDescriptor.VaultCipherView(itemId = itemId, accountId = accountId)
            } else {
                null
            }
        }

        "send" -> {
            val sendId = params["sendId"]
            val accountId = params["accountId"]
            if (sendId != null && accountId != null) {
                RouteDescriptor.SendView(sendId = sendId, accountId = accountId)
            } else {
                null
            }
        }

        "cipher/password-history" -> params["itemId"]?.let { RouteDescriptor.PasswordHistory(it) }
        "generator/wordlist" -> params["wordlistId"]?.toLongOrNull()?.let { RouteDescriptor.WordlistView(it) }
        "organizations" -> params["accountId"]?.let { RouteDescriptor.Organizations(it) }
        "collections" -> params["accountId"]?.let {
            RouteDescriptor.Collections(accountId = it, organizationId = params["organizationId"])
        }
        "equivalent-domains" -> params["accountId"]?.let { RouteDescriptor.EquivalentDomains(it) }
        // Title is loaded from the repository by id, so an empty placeholder is fine.
        "filter" -> params["filterId"]?.let { RouteDescriptor.CipherFilterView(filterId = it, title = "") }
        "downloads" -> RouteDescriptor.Downloads
        "watchtower/alerts" -> RouteDescriptor.WatchtowerAlerts()
        "filters" -> RouteDescriptor.CipherFilters
        "generator/history" -> RouteDescriptor.GeneratorHistory
        "generator/email-relay" -> RouteDescriptor.EmailRelayList
        "generator/wordlists" -> RouteDescriptor.WordlistList
        "feedback" -> RouteDescriptor.Feedback
        "settings/subscriptions" -> RouteDescriptor.Subscriptions
        "directory/two-fa" -> RouteDescriptor.TwoFaServices
        "directory/passkeys" -> RouteDescriptor.PasskeysServices
        "directory/get-my-data" -> RouteDescriptor.JustGetMyDataServices
        "directory/delete-me" -> RouteDescriptor.JustDeleteMeServices
        else -> null
    }
}
