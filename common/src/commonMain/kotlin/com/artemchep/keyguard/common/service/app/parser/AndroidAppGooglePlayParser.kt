package com.artemchep.keyguard.common.service.app.parser

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlHandler
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlParser
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
 * Represents the parsed information about an Android app
 * from the Google Play Store.
 */
data class AppStoreListingInfo(
    val title: String,
    val iconUrl: String,
    val summary: String,
)

/**
 * Internal data class for parsing the JSON-LD structured data
 * from the Google Play Store page.
 */
@Serializable
private data class GooglePlayLdJson(
    @SerialName("@type")
    val type: String? = null,
    val name: String? = null,
    val description: String? = null,
    val image: String? = null,
)

/**
 * Parses Android app information (title, icon, and summary) from the
 * Google Play Store page using the JSON-LD structured data.
 *
 * @param httpClient The HTTP client used to fetch the Google Play Store page.
 * @param json The JSON parser used to parse the JSON-LD structured data.
 */
class AndroidAppGooglePlayParser(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    companion object {
        private const val PLAY_STORE_URL = "https://play.google.com/store/apps/details"
    }

    constructor(directDI: DirectDI) : this(
        httpClient = directDI.instance(tag = "curl"),
        json = directDI.instance(),
    )

    /**
     * Fetches and parses the app information for the given package name.
     *
     * @param packageName The Android package name (e.g., "com.example.app").
     * @return An [IO] that resolves to [AppStoreListingInfo] if parsing succeeds,
     *         or `null` if the app is not found or parsing fails.
     */
    operator fun invoke(packageName: String): IO<AppStoreListingInfo?> = ioEffect {
        val url = "$PLAY_STORE_URL?id=$packageName&hl=en"
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) {
            throw HttpException(
                statusCode = response.status,
                response.status.description,
                null,
            )
        }

        val html = response.bodyAsText()
        parseHtml(html)
    }

    private fun parseHtml(html: String): AppStoreListingInfo? {
        val ldJsonContent = extractLdJson(html)
            ?: return null

        val ldJson = runCatching {
            json.decodeFromString<GooglePlayLdJson>(ldJsonContent)
        }.getOrNull()
            ?: return null

        // Verify it's a SoftwareApplication type
        if (ldJson.type != "SoftwareApplication") {
            return null
        }

        val title = ldJson.name
            ?: return null
        val iconUrl = ldJson.image
            ?: return null
        val summary = ldJson.description
            ?: return null

        return AppStoreListingInfo(
            title = title,
            iconUrl = iconUrl,
            summary = summary,
        )
    }

    private fun extractLdJson(html: String): String? {
        var ldJsonContent: String? = null
        var isInLdJsonScript = false
        val scriptContent = StringBuilder()

        val handler = KsoupHtmlHandler.Builder()
            .onOpenTag { name, attributes, _ ->
                if (name == "script" && attributes["type"] == "application/ld+json") {
                    isInLdJsonScript = true
                    scriptContent.clear()
                }
            }
            .onText { text ->
                if (isInLdJsonScript) {
                    scriptContent.append(text)
                }
            }
            .onCloseTag { name, _ ->
                if (name == "script" && isInLdJsonScript) {
                    ldJsonContent = scriptContent.toString().trim()
                    isInLdJsonScript = false
                }
            }
            .build()

        val parser = KsoupHtmlParser(handler = handler)
        parser.write(html)
        parser.end()

        return ldJsonContent
    }
}
