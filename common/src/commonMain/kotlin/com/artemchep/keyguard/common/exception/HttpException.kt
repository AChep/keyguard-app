package com.artemchep.keyguard.common.exception

import io.ktor.http.HttpStatusCode

open class HttpException(
    val statusCode: HttpStatusCode,
    m: String?,
    e: Throwable?,
    val errorCode: String? = null,
    val route: String? = null,
) : Exception(m, e)
