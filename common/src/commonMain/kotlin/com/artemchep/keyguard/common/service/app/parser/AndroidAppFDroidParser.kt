package com.artemchep.keyguard.common.service.app.parser

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlHandler
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlParser
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * Parses Android app information (title, icon, and summary) from the
 * F-Droid package page by parsing HTML body elements.
 *
 * @param httpClient The HTTP client used to fetch the F-Droid page.
 */
class AndroidAppFDroidParser(
    private val httpClient: HttpClient,
) {
    companion object {
        private const val FDROID_URL = "https://f-droid.org/en/packages"
    }

    constructor(directDI: DirectDI) : this(
        httpClient = directDI.instance(tag = "curl"),
    )

    /**
     * Fetches and parses the app information for the given package name.
     *
     * @param packageName The Android package name (e.g., "com.example.app").
     * @return An [com.artemchep.keyguard.common.io.IO] that resolves to [AppStoreListingInfo] if parsing succeeds,
     *         or `null` if the app is not found or parsing fails.
     */
    operator fun invoke(
        packageName: String,
    ): IO<AppStoreListingInfo?> = ioEffect {
        // https://f-droid.org/en/packages/com.artemchep.keyguard/
        val url = "$FDROID_URL/$packageName/"
        val response = httpClient.get(url) {
            header("Accept", "text/html")
        }
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
        var iconUrl: String? = null
        var title: String? = null
        var summary: String? = null

        var isInPackageName = false
        var isInPackageSummary = false
        val textContent = StringBuilder()

        val handler = KsoupHtmlHandler.Builder()
            .onOpenTag { name, attributes, _ ->
                when {
                    // Extract icon URL from <img class="package-icon" src="...">
                    name == "img" && attributes["class"]?.contains("package-icon") == true -> {
                        iconUrl = attributes["src"]
                    }
                    // Start capturing text for <h3 class="package-name">
                    name == "h3" && attributes["class"]?.contains("package-name") == true -> {
                        isInPackageName = true
                        textContent.clear()
                    }
                    // Start capturing text for <div class="package-summary">
                    name == "div" && attributes["class"]?.contains("package-summary") == true -> {
                        isInPackageSummary = true
                        textContent.clear()
                    }
                }
            }
            .onText { text ->
                when {
                    isInPackageName -> textContent.append(text)
                    isInPackageSummary -> textContent.append(text)
                }
            }
            .onCloseTag { name, _ ->
                when {
                    name == "h3" && isInPackageName -> {
                        title = textContent.toString().trim()
                        isInPackageName = false
                    }

                    name == "div" && isInPackageSummary -> {
                        summary = textContent.toString().trim()
                        isInPackageSummary = false
                    }
                }
            }
            .build()

        val parser = KsoupHtmlParser(handler = handler)
        parser.write(html)
        parser.end()

        // Return null if any required field is missing
        val finalTitle = title?.takeIf { it.isNotEmpty() }
            ?: return null
        val finalIconUrl = iconUrl?.takeIf { it.isNotEmpty() }
            ?: return null
        val finalSummary = summary?.takeIf { it.isNotEmpty() }
            ?: return null

        return AppStoreListingInfo(
            title = finalTitle,
            iconUrl = finalIconUrl,
            summary = finalSummary,
        )
    }
}