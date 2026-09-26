package com.artemchep.keyguard.util.webauthn

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode

internal fun createWebAuthnRpIdTestValidator(
    responseBody: String = """{"origins":[]}""",
    status: HttpStatusCode = HttpStatusCode.OK,
    contentType: String? = ContentType.Application.Json.toString(),
    responses: List<WebAuthnMockHttpResponse> = listOf(
        WebAuthnMockHttpResponse(
            responseBody = responseBody,
            status = status,
            contentType = contentType,
        ),
    ),
    onRequest: (String) -> Unit = {},
): WebAuthnRpIdValidator {
    var responseIndex = 0
    val engine = MockEngine { request ->
        onRequest(request.url.toString())
        val response = responses.getOrElse(responseIndex) {
            responses.last()
        }
        responseIndex++
        respond(
            content = response.responseBody,
            status = response.status,
            headers = response.toHeaders(),
        )
    }
    return WebAuthnRpIdValidator(FakeDomainLookup, HttpClient(engine))
}

internal data class WebAuthnMockHttpResponse(
    val responseBody: String = """{"origins":[]}""",
    val status: HttpStatusCode = HttpStatusCode.OK,
    val contentType: String? = ContentType.Application.Json.toString(),
    val headers: Headers = Headers.Empty,
) {
    fun toHeaders(): Headers = Headers.build {
        headers.entries().forEach { (name, values) ->
            appendAll(name, values)
        }
        contentType?.let {
            append(HttpHeaders.ContentType, it)
        }
    }
}

private object FakeDomainLookup : WebAuthnDomainLookup {
    private val publicSuffixes = setOf(
        "com",
        "co.uk",
        "example",
    )
    private val wildcardPublicSuffixBases = setOf(
        "compute.amazonaws.com",
    )

    override suspend fun getDomainName(
        host: String,
    ): String = run {
        val normalizedHost = host
            .trim()
            .lowercase()
        val wildcardDomainName = wildcardPublicSuffixBases
            .mapNotNull { base ->
                val suffix = ".$base"
                val prefix = normalizedHost
                    .takeIf { it.endsWith(suffix) }
                    ?.removeSuffix(suffix)
                    ?: return@mapNotNull null
                val prefixLabels = prefix
                    .split('.')
                    .filter(String::isNotEmpty)
                when (prefixLabels.size) {
                    0 -> null
                    1 -> normalizedHost
                    else ->
                        prefixLabels
                            .takeLast(2)
                            .joinToString(separator = ".") + suffix
                }
            }
            .maxByOrNull { domainName ->
                domainName.count { it == '.' }
            }
        if (wildcardDomainName != null) {
            wildcardDomainName
        } else {
            val publicSuffix = publicSuffixes
                .filter { suffix ->
                    normalizedHost == suffix ||
                        normalizedHost.endsWith(".$suffix")
                }
                .maxByOrNull { suffix ->
                    suffix.count { it == '.' }
                }
            if (publicSuffix == null) {
                normalizedHost
            } else {
                val hostLabels = normalizedHost.split('.')
                val suffixLabelCount = publicSuffix.split('.').size
                if (hostLabels.size <= suffixLabelCount) {
                    normalizedHost
                } else {
                    hostLabels
                        .takeLast(suffixLabelCount + 1)
                        .joinToString(separator = ".")
                }
            }
        }
    }
}
