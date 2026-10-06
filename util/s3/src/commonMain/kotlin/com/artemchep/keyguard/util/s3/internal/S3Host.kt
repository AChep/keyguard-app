package com.artemchep.keyguard.util.s3.internal

/** Returns the ASCII host to put in both the URL and the signature, without IPv6 brackets. */
internal expect fun s3CanonicalHost(
    host: String,
): String?
