package com.artemchep.keyguard.common.service.icon

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.feature.auth.common.util.verifyIsLocalUrl
import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.SendingRequest
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.util.AttributeKey
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray

class IconResponseTooLargeException(
    val maxBytes: Long,
) : IllegalStateException("The response is larger than $maxBytes bytes.")

class IconRedirectToLocalUrlException : IllegalStateException(
    "A public address can not redirect to a local one.",
)

private val StartsAtLocalUrl = AttributeKey<Boolean>("IconStartsAtLocalUrl")

/**
 * Checks every hop of a redirect: a request that starts at a public
 * address can not be taken to a local one. A request that starts at
 * a local address, such as an icon of a local website, may stay local.
 */
internal val IconLocalRedirectGuard = createClientPlugin("IconLocalRedirectGuard") {
    // Runs once per call; the redirects copy the attributes.
    onRequest { request, _ ->
        request.attributes.put(StartsAtLocalUrl, verifyIsLocalUrl(request.url.build()))
    }
    // Runs once per hop.
    on(SendingRequest) { request, _ ->
        val startsAtLocalUrl = request.attributes.getOrNull(StartsAtLocalUrl) ?: false
        if (!startsAtLocalUrl && verifyIsLocalUrl(request.url.build())) {
            throw IconRedirectToLocalUrlException()
        }
    }
}

class IconResponse(
    /** The address that answered, after the redirects. */
    val url: Url,
    val bytes: ByteArray,
)

/**
 * Downloads at most [maxBytes] of the [url]. Fails on an unsuccessful
 * status, and on a body larger than the limit without reading all of it;
 * if [truncate] is set, returns the first [maxBytes] of such a body.
 */
suspend fun HttpClient.readIconResponse(
    url: Url,
    maxBytes: Long,
    accept: String? = null,
    truncate: Boolean = false,
): IconResponse = prepareGet(url) {
    if (accept != null) {
        header(HttpHeaders.Accept, accept)
    }
}.execute { response ->
    if (!response.status.isSuccess()) {
        throw HttpException(
            statusCode = response.status,
            m = response.status.description,
            e = null,
        )
    }
    val contentLength = response.contentLength()
    if (!truncate && contentLength != null && contentLength > maxBytes) {
        throw IconResponseTooLargeException(maxBytes)
    }
    val bytes = response.bodyAsChannel()
        .readBuffer(if (truncate) maxBytes else maxBytes + 1)
        .readByteArray()
    if (bytes.size > maxBytes) {
        throw IconResponseTooLargeException(maxBytes)
    }
    IconResponse(
        url = response.call.request.url,
        bytes = bytes,
    )
}
