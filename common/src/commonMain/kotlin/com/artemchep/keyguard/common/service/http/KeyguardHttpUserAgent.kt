package com.artemchep.keyguard.common.service.http

enum class KeyguardHttpUserAgent {
    /** Identifies as the Bitwarden client of the current platform. */
    Bitwarden,

    /** Identifies as curl, for requests to non-Bitwarden services. */
    Curl,
}
