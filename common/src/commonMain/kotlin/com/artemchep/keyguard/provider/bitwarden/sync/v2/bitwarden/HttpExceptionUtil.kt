package com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden

import com.artemchep.keyguard.common.exception.HttpException
import io.ktor.http.HttpStatusCode

internal fun Throwable.hasHttpStatusCode(
    statusCode: HttpStatusCode,
): Boolean =
    this is HttpException &&
        this.statusCode == statusCode

internal fun Throwable.hasHttpStatusCode(
    vararg statusCodes: HttpStatusCode,
): Boolean =
    this is HttpException &&
        this.statusCode in statusCodes
