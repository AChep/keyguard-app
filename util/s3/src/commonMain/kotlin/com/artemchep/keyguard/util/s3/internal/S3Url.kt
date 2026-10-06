package com.artemchep.keyguard.util.s3.internal

import com.artemchep.keyguard.util.s3.S3ClientConfig
import com.artemchep.keyguard.util.s3.S3Endpoints
import com.artemchep.keyguard.util.s3.isValidS3BucketName
import com.artemchep.keyguard.util.s3.isValidS3EndpointUrl
import com.artemchep.keyguard.util.s3.isValidS3ObjectKey
import com.artemchep.keyguard.util.s3.s3Utf8SizeOrNull
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter

/**
 * Percent-encodes a value the way SigV4 expects: unreserved characters are
 * kept, everything else is UTF-8 encoded as uppercase `%XX`. Slashes are
 * kept in paths and encoded in query values.
 */
internal fun s3UriEncode(
    value: String,
    encodeSlash: Boolean,
): String {
    require(s3Utf8SizeOrNull(value) != null) { "S3 URL values must contain valid Unicode." }
    return if (encodeSlash) {
        value.encodeURLParameter()
    } else {
        value.split('/').joinToString(separator = "/") { it.encodeURLParameter() }
    }
}

/** Builds a canonical query string: encoded pairs sorted by name, then value. */
internal fun s3CanonicalQuery(
    parameters: List<Pair<String, String>>,
): String = parameters
    .map { (name, value) ->
        s3UriEncode(name, encodeSlash = true) to s3UriEncode(value, encodeSlash = true)
    }
    .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
    .joinToString(separator = "&") { (name, value) -> "$name=$value" }

internal data class S3RequestTarget(
    val url: String,
    /** The value of the `Host` header the HTTP engine sends for [url]. */
    val host: String,
    val canonicalUri: String,
    val canonicalQuery: String,
)

/** Resolves bucket and object requests against the configured endpoint. */
internal class S3Addressing(
    config: S3ClientConfig,
) {
    val region: String = config.region
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: S3Endpoints.DEFAULT_REGION

    private val scheme: String

    val isHttps: Boolean
        get() = scheme == "https"

    /** The host that serves the bucket. */
    private val host: String

    /** The canonical URI of bucket requests. */
    private val bucketUri: String

    /** The canonical URI that object keys are appended to. */
    private val objectUriPrefix: String

    /** AWS requires path-style addressing for the exact object key `soap`. */
    private val soapObjectTarget: S3RequestTarget?

    init {
        val bucket = config.bucket
        val pathStyle = config.pathStyle
        val endpoint = config.endpoint
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: S3Endpoints.aws(region)
        require(isValidS3EndpointUrl(endpoint)) {
            "S3 endpoint must be an HTTP or HTTPS URL without credentials, a query, a fragment, or dot segments."
        }
        require(isValidS3BucketName(bucket, pathStyle = pathStyle)) {
            if (pathStyle) {
                "S3 bucket name is not valid."
            } else {
                "S3 bucket name is not valid for virtual-hosted addressing."
            }
        }

        val url = Url(endpoint)
        scheme = url.protocol.name
        val endpointHost = requireNotNull(s3CanonicalHost(url.host)).let { host ->
            if (':' in host && !host.startsWith('[')) "[$host]" else host
        }
        // Like the AWS endpoint rules: the bucket can only become a subdomain
        // of a domain name, and a wildcard certificate covers one label only.
        val virtualHosted = !pathStyle &&
            ':' !in endpointHost &&
            !IPV4_HOST_REGEX.matches(endpointHost) &&
            !(isHttps && '.' in bucket)
        // Engines omit the port from the Host header when it is the
        // scheme's default, and the signature must match what they send.
        val hostWithPort = if (url.port == url.protocol.defaultPort) {
            endpointHost
        } else {
            "$endpointHost:${url.port}"
        }
        // Url.segments excludes the boundary separators. Keep its empty
        // segments: additional slashes are meaningful to proxies.
        val basePath = url.segments
            .joinToString(separator = "") { "/" + s3UriEncode(it, encodeSlash = true) }
        val pathStyleBucketUri = "$basePath/${s3UriEncode(bucket, encodeSlash = true)}"
        if (!virtualHosted) {
            host = hostWithPort
            bucketUri = pathStyleBucketUri
            objectUriPrefix = "$bucketUri/"
        } else {
            host = "$bucket.$hostWithPort"
            bucketUri = "$basePath/"
            objectUriPrefix = bucketUri
        }
        soapObjectTarget = if (
            virtualHosted &&
            (endpointHost == Url(S3Endpoints.aws(region)).host || AWS_S3_HOST_REGEX.matches(endpointHost))
        ) {
            target(
                canonicalUri = "$pathStyleBucketUri/soap",
                query = emptyList(),
                host = hostWithPort,
            )
        } else {
            null
        }
    }

    fun bucketTarget(
        query: List<Pair<String, String>> = emptyList(),
    ): S3RequestTarget = target(
        canonicalUri = bucketUri,
        query = query,
    )

    fun objectTarget(
        key: String,
    ): S3RequestTarget {
        requireValidObjectKey(key)
        if (key == "soap" && soapObjectTarget != null) {
            return soapObjectTarget
        }
        return target(
            canonicalUri = objectUriPrefix + s3UriEncode(key, encodeSlash = false),
            query = emptyList(),
        )
    }

    private fun target(
        canonicalUri: String,
        query: List<Pair<String, String>>,
        host: String = this.host,
    ): S3RequestTarget {
        val canonicalQuery = s3CanonicalQuery(query)
        val url = buildString {
            append(scheme)
            append("://")
            append(host)
            append(canonicalUri)
            if (canonicalQuery.isNotEmpty()) {
                append('?')
                append(canonicalQuery)
            }
        }
        return S3RequestTarget(
            url = url,
            host = host,
            canonicalUri = canonicalUri,
            canonicalQuery = canonicalQuery,
        )
    }
}

private val IPV4_HOST_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
private val AWS_S3_HOST_REGEX = Regex(
    "^s3(?:\\.[a-z0-9-]+|-[a-z]{2}-[a-z0-9-]+-\\d+|-external-1)?\\.amazonaws\\.com(?:\\.cn)?$",
)

internal fun requireValidObjectKey(
    key: String,
) {
    require(isValidS3ObjectKey(key)) {
        "S3 object key must be valid Unicode, contain 1 to 1024 UTF-8 bytes, and have no '.' or '..' segments."
    }
}
