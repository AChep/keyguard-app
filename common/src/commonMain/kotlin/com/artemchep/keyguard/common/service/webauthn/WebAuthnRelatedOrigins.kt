package com.artemchep.keyguard.common.service.webauthn

import com.artemchep.keyguard.common.service.tld.TldService
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.takeFrom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val WEBAUTHN_RELATED_ORIGINS_PATH = "/.well-known/webauthn"
private const val WEBAUTHN_RELATED_ORIGINS_MAX_REDIRECTS = 20
private const val WEBAUTHN_RELATED_ORIGINS_MAX_LABELS = 5

internal class WebAuthnRelatedOrigins(
    private val tldService: TldService,
    httpClient: HttpClient,
) {
    private val httpClientNoRedirect = httpClient.config {
        followRedirects = false
    }
    private val json = Json

    suspend fun requireRpMatchesRelatedOrigin(
        rpId: String,
        origin: WebAuthnOrigin,
    ) {
        val response = fetchDocument(rpId)
        // WebAuthn L3 5.11.1 validates the status after following redirects and
        // requires exactly 200. See https://www.w3.org/TR/webauthn-3/#sctn-related-origins
        require(response.status == HttpStatusCode.OK) {
            "Related origins document must return 200 OK; got " +
                    "${response.status.value} ${response.status.description}."
        }
        require(response.isApplicationJson()) {
            "Related origins document has invalid content type."
        }

        val body = runCatching {
            json.parseToJsonElement(response.bodyAsText())
        }.getOrElse {
            throw IllegalStateException("Related origins document is not valid JSON.")
        }
        val origins = (body as? JsonObject)
            ?.get("origins") as? JsonArray
        require(!origins.isNullOrEmpty()) {
            "Related origins document does not contain origins."
        }

        // WebAuthn L3 5.11.1 rejects a document whose `origins` value is
        // missing or is not an array of strings.
        val rawOrigins = origins.map { element ->
            val primitive = element as? JsonPrimitive
            val rawOrigin = primitive
                ?.takeIf { it.isString }
                ?.contentOrNull
            requireNotNull(rawOrigin) {
                "Related origins document contains a non-string origin."
            }
            rawOrigin
        }

        val labelsSeen = mutableSetOf<String>()
        var validOriginCount = 0
        rawOrigins.forEach { rawOrigin ->
            val relatedOrigin = parseRelatedOrigin(rawOrigin)
                ?: return@forEach
            val label = relatedOriginLabel(relatedOrigin.url)
                ?: return@forEach
            if (
                labelsSeen.size >= WEBAUTHN_RELATED_ORIGINS_MAX_LABELS &&
                label !in labelsSeen
            ) {
                return@forEach
            }

            validOriginCount++
            if (origin.serialized == relatedOrigin.origin.serialized) {
                return
            }
            if (labelsSeen.size < WEBAUTHN_RELATED_ORIGINS_MAX_LABELS) {
                labelsSeen += label
            }
        }
        require(validOriginCount > 0) {
            "Related origins document does not contain valid origins."
        }
        throw IllegalStateException(
            "Request origin '${origin.serialized}' is not associated with the relying party.",
        )
    }

    private suspend fun fetchDocument(
        rpId: String,
    ): HttpResponse {
        var url = "https://$rpId$WEBAUTHN_RELATED_ORIGINS_PATH"
        var redirectCount = 0
        while (true) {
            val response = httpClientNoRedirect.get(url)
            if (!response.status.isRedirect()) {
                return response
            }
            if (redirectCount >= WEBAUTHN_RELATED_ORIGINS_MAX_REDIRECTS) {
                throw IllegalStateException("Related origins document redirects too many times.")
            }

            url = requireHttpsRedirectUrl(
                currentUrl = url,
                location = response.headers[HttpHeaders.Location],
            )
            redirectCount++
        }
    }

    private fun requireHttpsRedirectUrl(
        currentUrl: String,
        location: String?,
    ): String {
        require(!location.isNullOrBlank()) {
            "Related origins document redirect is missing Location."
        }

        val redirectUrl = runCatching {
            val builder = URLBuilder()
                .takeFrom(currentUrl)
            builder.parameters.clear()
            builder
                .takeFrom(location)
                .build()
        }.getOrElse {
            throw IllegalStateException("Related origins document redirect has invalid Location.")
        }

        // WebAuthn L3 5.11 requires the well-known request to use HTTPS and
        // explicitly requires every followed redirect to also use HTTPS.
        require(redirectUrl.protocol.name == "https") {
            "Related origins document redirect must use HTTPS."
        }
        return redirectUrl.toString()
    }

    private fun HttpStatusCode.isRedirect(): Boolean = when (this) {
        HttpStatusCode.MovedPermanently,
        HttpStatusCode.Found,
        HttpStatusCode.SeeOther,
        HttpStatusCode.TemporaryRedirect,
        HttpStatusCode.PermanentRedirect -> true

        else -> false
    }

    private data class RelatedOrigin(
        val origin: WebAuthnOrigin,
        val url: Url,
    )

    private fun parseRelatedOrigin(
        value: String,
    ): RelatedOrigin? = runCatching {
        val url = Url(value)
        val origin = parseWebAuthnOrigin(url)
            ?: return null
        RelatedOrigin(
            origin = origin,
            url = url,
        )
    }.getOrNull()

    private suspend fun relatedOriginLabel(
        url: Url,
    ): String? {
        val host = canonicalizeWebAuthnRpIdOrNull(url.host)
            ?: return null
        if (isWebAuthnLocalhost(host)) {
            // WebAuthn L3 related-origin validation skips origins whose
            // registrable origin label is null or empty before same-origin
            // matching. `localhost` has no registrable domain; the RP ID
            // definition's `http://localhost` exception applies only to direct
            // RP ID scoping.
            // See https://www.w3.org/TR/webauthn-3/#sctn-related-origins
            // See https://www.w3.org/TR/webauthn-3/#rp-id
            return null
        }

        val domainName = getWebAuthnRegistrableDomain(
            tldService = tldService,
            host = host,
        )
        if (domainName == null) {
            // TldService returns the input when it cannot derive a registrable
            // domain. WebAuthn L3 requires a non-null registrable origin label,
            // so public suffixes and non-registrable hosts must be skipped.
            // See https://www.w3.org/TR/webauthn-3/#sctn-related-origins
            return null
        }
        return domainName
            .substringBefore('.')
            .takeIf { it.isNotBlank() }
    }

    private fun HttpResponse.isApplicationJson(): Boolean {
        val contentType = headers[HttpHeaders.ContentType]
            ?.substringBefore(';')
            ?.trim()
            ?: return false
        return contentType.equals(
            other = ContentType.Application.Json.toString(),
            ignoreCase = true,
        )
    }
}
