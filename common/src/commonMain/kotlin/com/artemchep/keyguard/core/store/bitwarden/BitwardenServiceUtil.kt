package com.artemchep.keyguard.core.store.bitwarden

import com.artemchep.keyguard.common.util.canRetry
import io.ktor.http.HttpStatusCode
import kotlin.time.Instant

fun BitwardenService.Error.canRetry(revisionDate: Instant): Boolean =
    expired(revisionDate) || code.canRetry()

fun BitwardenService.Error.expired(revisionDate: Instant) =
    revisionDate > this.revisionDate

fun BitwardenService.Error?.exists(revisionDate: Instant) =
    this != null && !this.expired(revisionDate)

fun BitwardenService.Error.message() = message
    ?: code
        .takeIf { it > 0 }
        ?.let { HttpStatusCode.fromValue(it) }
        ?.description
    ?: when (code) {
        BitwardenService.Error.CODE_DECODING_FAILED -> "Failed to decode cipher text"
        else -> "Unknown error"
    }
