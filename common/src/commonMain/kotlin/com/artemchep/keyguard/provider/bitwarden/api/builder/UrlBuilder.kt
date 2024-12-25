package com.artemchep.keyguard.provider.bitwarden.api.builder

import arrow.core.identity
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import io.ktor.http.Url

private const val BITWARDEN_DOMAIN_US = "bitwarden.com"
private const val BITWARDEN_DOMAIN_EU = "bitwarden.eu"

fun ServerEnv.buildHost() = buildWebVaultUrl()
    .let(::Url)
    .host

fun ServerEnv.buildWebVaultUrl() = buildUrl(
    url = webVaultUrl,
    default = when (region) {
        ServerEnv.Region.US -> "https://vault.$BITWARDEN_DOMAIN_US/"
        ServerEnv.Region.EU -> "https://vault.$BITWARDEN_DOMAIN_EU/"
    },
)

fun ServerEnv.buildApiUrl() = buildUrl(
    url = apiUrl,
    baseUrlModifier = { "${it}api/" },
    default = when (region) {
        ServerEnv.Region.US -> "https://vault.$BITWARDEN_DOMAIN_US/api/"
        ServerEnv.Region.EU -> "https://vault.$BITWARDEN_DOMAIN_EU/api/"
    },
)

fun ServerEnv.buildIdentityUrl() = buildUrl(
    url = identityUrl,
    baseUrlModifier = { "${it}identity/" },
    default = when (region) {
        ServerEnv.Region.US -> "https://identity.$BITWARDEN_DOMAIN_US/"
        ServerEnv.Region.EU -> "https://identity.$BITWARDEN_DOMAIN_EU/"
    },
)

fun ServerEnv.buildIconsUrl() = buildUrl(
    url = iconsUrl,
    baseUrlModifier = { "${it}icons/" },
    default = "https://icons.bitwarden.net/",
)

fun ServerEnv.buildNotificationsUrl() = buildUrl(
    url = notificationsUrl,
    baseUrlModifier = { "${it}notifications/" },
    default = when (region) {
        ServerEnv.Region.US -> "https://notifications.$BITWARDEN_DOMAIN_US/"
        ServerEnv.Region.EU -> "https://notifications.$BITWARDEN_DOMAIN_EU/"
    },
)

fun ServerEnv.buildSendUrl() = run {
    fun urlModifier(url: String): String = "$url#/send/"

    buildUrl(
        url = webVaultUrl,
        urlModifier = ::urlModifier,
        baseUrlModifier = ::urlModifier,
        default = when (region) {
            ServerEnv.Region.US -> "https://send.$BITWARDEN_DOMAIN_US/#"
            ServerEnv.Region.EU -> "https://send.$BITWARDEN_DOMAIN_EU/#"
        },
    )
}

fun ServerEnv.buildIconsRequestUrl(domain: String) = kotlin.run {
    val baseUrl = buildIconsUrl()
    "$baseUrl$domain/icon.png"
}

private fun ServerEnv.buildUrl(
    url: String,
    urlModifier: (String) -> String = ::identity,
    baseUrlModifier: (String) -> String = ::identity,
    default: String,
) = ensureValidBaseUrlOrNull(url)?.let(urlModifier)
    ?: ensureValidBaseUrlOrNull(baseUrl)?.let(baseUrlModifier)
    ?: default

private fun ensureValidBaseUrlOrNull(url: String) = url
    .takeUnless { it.isBlank() }
    ?.ensureSuffix("/")

fun String.ensureSuffix(suffix: String) = if (endsWith(suffix)) this else this + suffix
