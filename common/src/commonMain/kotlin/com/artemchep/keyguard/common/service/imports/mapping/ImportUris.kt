package com.artemchep.keyguard.common.service.imports.mapping

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.util.PROTOCOL_ANDROID_APP
import com.artemchep.keyguard.common.util.normalizeSha256FingerprintOrNull
import com.artemchep.keyguard.common.util.parseHttpUrlHostOrNull
import com.artemchep.keyguard.common.util.toHex
import kotlin.io.encoding.Base64

private const val CHROMIUM_ANDROID_PREFIX = "android://"

private val base64UrlSafe = Base64.UrlSafe
    .withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

/**
 * Reads one website or app address of an export, or returns `null` for a
 * blank one.
 *
 * Chromium-based browsers store an Android app login as
 * `android://<base64 certificate hash>@<package name>/`; it becomes Keyguard's
 * `androidapp://<package name>` with the certificate as the signature. Only
 * a SHA-256 certificate hash is kept; any other leaves the app unpinned.
 */
internal fun importedUriOrNull(
    raw: String?,
): DSecret.Uri? {
    val value = raw
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return chromiumAndroidUriOrNull(value)
        ?: DSecret.Uri(
            uri = value,
        )
}

private fun chromiumAndroidUriOrNull(
    value: String,
): DSecret.Uri? {
    val authority = value
        .takeIf { it.startsWith(CHROMIUM_ANDROID_PREFIX, ignoreCase = true) }
        ?.substring(CHROMIUM_ANDROID_PREFIX.length)
        ?.substringBefore('/')
    val packageName = authority
        ?.substringAfter('@')
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val signature = authority
        .substringBefore('@', missingDelimiterValue = "")
        .let(::importedCertificateSignatureOrNull)
    return DSecret.Uri(
        uri = PROTOCOL_ANDROID_APP + packageName,
        signatures = listOfNotNull(signature),
    )
}

/**
 * The signature of an Android app whose certificate hash is [encoded] in
 * base64, in the standard or the URL-safe alphabet. Only a SHA-256 hash is
 * kept; `null` for anything else.
 */
internal fun importedCertificateSignatureOrNull(
    encoded: String,
): DSecret.Uri.Signature? = encoded
    .takeIf { it.isNotEmpty() }
    ?.let {
        val urlSafe = it
            .replace('+', '-')
            .replace('/', '_')
        runCatchingNonFatal { base64UrlSafe.decode(urlSafe) }.getOrNull()
    }
    ?.toHex()
    ?.normalizeSha256FingerprintOrNull()
    ?.let { fingerprint ->
        DSecret.Uri.Signature(
            certFingerprintSha256 = fingerprint,
        )
    }

/**
 * The host of a web address, for exports that have no item title. `null`
 * when [uri] is not an http(s) address.
 */
internal fun importedTitleFromUriOrNull(
    uri: String?,
): String? = uri
    ?.trim()
    ?.let { parseHttpUrlHostOrNull(it, removeWww = true) }
    ?.takeIf { it.isNotBlank() }
