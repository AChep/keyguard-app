package com.artemchep.keyguard.common.service.app.parser

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * Internal data class for parsing the iTunes Lookup API response.
 */
@Serializable
private data class ItunesLookupResponse(
    val resultCount: Int = 0,
    val results: List<ItunesAppResult> = emptyList(),
)

/**
 * Internal data class for parsing an individual app result
 * from the iTunes Lookup API.
 */
@Serializable
private data class ItunesAppResult(
    val trackName: String? = null,
    @SerialName("artworkUrl512")
    val artworkUrl512: String? = null,
    val description: String? = null,
)

/**
 * Parses iOS app information (title, icon, and summary) from the
 * Apple App Store using the iTunes Lookup API.
 *
 * @param httpClient The HTTP client used to fetch the app information.
 * @param json The JSON parser used to parse the API response.
 */
class IosAppAppStoreParser(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    companion object {
        private const val ITUNES_LOOKUP_URL = "https://itunes.apple.com/lookup"
    }

    constructor(directDI: DirectDI) : this(
        httpClient = directDI.instance(tag = "curl"),
        json = directDI.instance(),
    )

    /**
     * Fetches and parses the app information for the given bundle ID.
     *
     * @param bundleId The iOS bundle ID (e.g., "com.example.app").
     * @return An [IO] that resolves to [AppStoreListingInfo] if parsing succeeds,
     *         or `null` if the app is not found or parsing fails.
     */
    operator fun invoke(bundleId: String): IO<AppStoreListingInfo?> = ioEffect {
        val url = "$ITUNES_LOOKUP_URL?bundleId=$bundleId"
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) {
            throw HttpException(
                statusCode = response.status,
                response.status.description,
                null,
            )
        }

        val text = response.bodyAsText()
        parseResponse(text)
    }

    private fun parseResponse(responseText: String): AppStoreListingInfo? {
        val response = runCatching {
            json.decodeFromString<ItunesLookupResponse>(responseText)
        }.getOrNull()
            ?: return null

        // Check if we got any results
        if (response.resultCount == 0 || response.results.isEmpty()) {
            return null
        }

        val appResult = response.results.first()

        val title = appResult.trackName
            ?: return null
        val iconUrl = appResult.artworkUrl512
            ?: return null
        val summary = appResult.description
            ?: return null

        return AppStoreListingInfo(
            title = title,
            iconUrl = iconUrl,
            summary = summary,
        )
    }
}
