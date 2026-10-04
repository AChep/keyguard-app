package com.artemchep.keyguard.feature.s3

import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import io.ktor.http.Url

/** Formats a bucket path as `s3://bucket/path`. */
fun s3LocationUri(
    bucket: String,
    path: String?,
): String = "s3://$bucket/${path.orEmpty()}"

/** Formats the store as `s3://bucket/prefix`, or returns null without a bucket. */
fun BackupStoreConfig.S3.locationUriOrNull(): String? = bucket
    ?.takeIf { it.isNotBlank() }
    ?.let { s3LocationUri(it, prefix) }

/** Returns the host of a custom endpoint, or null for Amazon S3. */
fun s3EndpointHostOrNull(
    endpoint: String?,
): String? {
    val value = endpoint
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return runCatching { Url(value) }.getOrNull()
        ?.let { url -> if (url.port == url.protocol.defaultPort) url.host else "${url.host}:${url.port}" }
        ?: value
}
