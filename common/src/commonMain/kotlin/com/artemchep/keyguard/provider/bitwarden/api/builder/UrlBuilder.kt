package com.artemchep.keyguard.provider.bitwarden.api.builder

import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import io.ktor.http.Url

private const val BITWARDEN_DOMAIN_US = "bitwarden.com"
private const val BITWARDEN_DOMAIN_EU = "bitwarden.eu"

fun ServerEnv.buildHost() = buildWebVaultUrl()
    .let(::Url)
    .host

fun ServerEnv.buildWebVaultUrl() = buildUrl(
    url = webVaultUrl,
    suffix = "",
    default = when (region) {
        ServerEnv.Region.US -> "https://vault.$BITWARDEN_DOMAIN_US/"
        ServerEnv.Region.EU -> "https://vault.$BITWARDEN_DOMAIN_EU/"
    },
)

fun ServerEnv.buildApiUrl() = buildUrl(
    url = apiUrl,
    suffix = "api/",
    default = when (region) {
        ServerEnv.Region.US -> "https://vault.$BITWARDEN_DOMAIN_US/api/"
        ServerEnv.Region.EU -> "https://vault.$BITWARDEN_DOMAIN_EU/api/"
    },
)

fun ServerEnv.buildIdentityUrl() = buildUrl(
    url = identityUrl,
    suffix = "identity/",
    default = when (region) {
        ServerEnv.Region.US -> "https://identity.$BITWARDEN_DOMAIN_US/"
        ServerEnv.Region.EU -> "https://identity.$BITWARDEN_DOMAIN_EU/"
    },
)

fun ServerEnv.buildIconsUrl() = buildUrl(
    url = iconsUrl,
    suffix = "icons/",
    default = "https://icons.bitwarden.net/",
)

fun ServerEnv.buildNotificationsUrl() = buildUrl(
    url = notificationsUrl,
    suffix = "notifications/",
    default = when (region) {
        ServerEnv.Region.US -> "https://notifications.$BITWARDEN_DOMAIN_US/"
        ServerEnv.Region.EU -> "https://notifications.$BITWARDEN_DOMAIN_EU/"
    },
)

fun ServerEnv.buildSendUrl() = buildUrl(
    url = webVaultUrl,
    suffix = "#/send/",
    default = when (region) {
        ServerEnv.Region.US -> "https://send.$BITWARDEN_DOMAIN_US/#"
        ServerEnv.Region.EU -> "https://send.$BITWARDEN_DOMAIN_EU/#"
    },
)

fun ServerEnv.buildIconsRequestUrl(domain: String) = kotlin.run {
    val baseUrl = buildIconsUrl()
    "$baseUrl$domain/icon.png"
}

private fun ServerEnv.buildUrl(
    url: String,
    suffix: String,
    default: String,
) = url
    .takeUnless { it.isBlank() }
    ?: baseUrl
        .takeUnless { it.isBlank() }
        ?.ensureSuffix("/")
        ?.let { it + suffix }
    ?: default

fun String.ensureSuffix(suffix: String) = if (endsWith(suffix)) this else this + suffix
