package com.artemchep.keyguard

import com.artemchep.keyguard.common.service.http.KeyguardHttpUserAgent
import com.artemchep.keyguard.common.service.http.configureKeyguardHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.serialization.json.Json

internal fun appleHttpClient(
    json: Json,
    userAgent: KeyguardHttpUserAgent,
) = HttpClient(Darwin) {
    configureKeyguardHttpClient(
        json = json,
        userAgent = userAgent,
    )
}
