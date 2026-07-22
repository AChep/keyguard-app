package com.artemchep.keyguard.util.signalr

import io.ktor.http.HttpStatusCode

class HubConnectionHttpException(
    val statusCode: HttpStatusCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
