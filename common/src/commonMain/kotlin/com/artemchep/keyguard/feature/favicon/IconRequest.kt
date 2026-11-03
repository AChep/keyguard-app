package com.artemchep.keyguard.feature.favicon

import com.artemchep.keyguard.common.service.icon.toWebsiteRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * An icon the icon loader resolves and downloads. The loader decides
 * which server to ask and whether the icon settings allow it at all.
 */
@Serializable
sealed interface IconRequest

/**
 * The icon of the website at [url]. If the [serverId] account has an
 * icon server, the icon is requested from it; otherwise from the website.
 */
@Serializable
@SerialName("favicon")
data class FaviconUrl(
    val serverId: String? = null,
    val url: String,
) : IconRequest

@Serializable
@SerialName("app")
data class AppIconUrl(
    val packageName: String,
) : IconRequest

@Serializable
@SerialName("ios_app")
data class IosAppIconUrl(
    val bundleId: String,
) : IconRequest

/** A direct link to a third-party icon image, such as a store or a breach logo. */
@Serializable
@SerialName("remote")
data class RemoteIconUrl(
    val url: String,
) : IconRequest

private val iconKeyJson = Json

/**
 * An opaque, stable key that platform image loaders pass back to the
 * icon loader. Pages of a website have the same key, so they share a load.
 */
fun IconRequest.toIconKey(): String {
    val request = (this as? FaviconUrl)?.toWebsiteRequest() ?: this
    return iconKeyJson.encodeToString(IconRequest.serializer(), request)
}

fun iconRequestOrNull(key: String): IconRequest? = runCatching {
    iconKeyJson.decodeFromString(IconRequest.serializer(), key)
}.getOrNull()
