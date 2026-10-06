package com.artemchep.keyguard.util.s3.internal

import okhttp3.HttpUrl

internal actual fun s3CanonicalHost(
    host: String,
): String? = runCatching {
    // Use the same IDNA and IP formatting rules as the HTTP engine. Java's
    // older IDN implementation maps some names to a different domain.
    HttpUrl.Builder()
        .scheme("https")
        .host(host)
        .build()
        .host
}.getOrNull()
