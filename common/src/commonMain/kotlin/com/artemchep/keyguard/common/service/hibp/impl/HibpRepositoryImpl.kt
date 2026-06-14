package com.artemchep.keyguard.common.service.hibp.impl

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.hibp.HibpRepository
import com.artemchep.keyguard.provider.bitwarden.api.builder.bodyOrApiException
import com.artemchep.keyguard.provider.bitwarden.api.builder.routeAttribute
import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachGroup
import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import io.ktor.http.userAgent
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.kodein.di.DirectDI
import org.kodein.di.instance

class HibpRepositoryImpl(
    private val httpClient: HttpClient,
) : HibpRepository {
    companion object {
        const val ROUTE_GET_BREACHES = "get-hibp-breaches"
        const val ROUTE_GET_BREACHED_ACCOUNT = "get-hibp-breached-account"
        const val ROUTE_GET_SUBSCRIPTION_STATUS = "get-hibp-subscription-status"
        const val ROUTE_GET_PWNED_PASSWORD_RANGE = "get-hibp-pwned-password-range"

        private const val HIBP_API_BASE_URL = "https://haveibeenpwned.com/api/v3"
        private const val PWNED_PASSWORDS_API_BASE_URL = "https://api.pwnedpasswords.com"
        private const val USER_AGENT = "Keyguard"
    }

    constructor(directDI: DirectDI) : this(
        httpClient = directDI.instance(),
    )

    override fun getBreaches(): IO<HibpBreachGroup> = ioEffect(Dispatchers.IO) {
        val url = "$HIBP_API_BASE_URL/breaches"
        val breaches = httpClient
            .get(url) {
                // https://haveibeenpwned.com/API/v3#UserAgent
                userAgent(USER_AGENT)
                attributes.put(routeAttribute, ROUTE_GET_BREACHES)
            }
            .bodyOrApiException<List<HibpBreachResponse>>()

        HibpBreachGroup(breaches)
    }

    override fun getBreachedAccount(
        username: String,
        apiToken: String,
    ): IO<List<HibpBreachResponse>> = ioEffect(Dispatchers.IO) {
        val url = URLBuilder(Url(HIBP_API_BASE_URL)).apply {
            appendPathSegments(
                "breachedaccount",
                username,
            )
        }.build()
        try {
            httpClient
                .get(url) {
                    // https://haveibeenpwned.com/API/v3#UserAgent
                    userAgent(USER_AGENT)
                    header("hibp-api-key", apiToken)
                    parameter("truncateResponse", false)
                    parameter("includeUnverified", false)
                    attributes.put(routeAttribute, ROUTE_GET_BREACHED_ACCOUNT)
                }
                .bodyOrApiException<List<HibpBreachResponse>>()
        } catch (e: HttpException) {
            if (e.statusCode == HttpStatusCode.NotFound) {
                emptyList()
            } else {
                throw e
            }
        }
    }

    override fun getSubscriptionStatus(
        apiToken: String,
    ): IO<Unit> = ioEffect(Dispatchers.IO) {
        val url = "$HIBP_API_BASE_URL/subscription/status"
        httpClient
            .get(url) {
                // https://haveibeenpwned.com/API/v3#UserAgent
                userAgent(USER_AGENT)
                header("hibp-api-key", apiToken)
                attributes.put(routeAttribute, ROUTE_GET_SUBSCRIPTION_STATUS)
            }
            .bodyOrApiException<Unit>()
    }

    override fun getPwnedPasswordOccurrences(
        passwordSha1Hash: String,
    ): IO<Int> = ioEffect(Dispatchers.IO) {
        val prefix = passwordSha1Hash.take(5)
        val suffix = passwordSha1Hash.drop(5)
        val url = "$PWNED_PASSWORDS_API_BASE_URL/range/$prefix"

        val response = httpClient
            .get(url) {
                // https://haveibeenpwned.com/API/v3#UserAgent
                userAgent(USER_AGENT)
                attributes.put(routeAttribute, ROUTE_GET_PWNED_PASSWORD_RANGE)
            }
        if (!response.status.isSuccess()) {
            response.bodyOrApiException<Unit>()
        }

        val channel = response.bodyAsChannel()
        try {
            // Stream the response so we can stop as soon as the matching suffix is found.
            // HIBP range responses can be large, and loading the whole body is unnecessary.
            while (!channel.isClosedForRead) {
                val line = channel.readLine()
                if (line != null && line.startsWith(suffix)) {
                    return@ioEffect line
                        .substringAfter(':')
                        .toInt()
                }
            }
        } finally {
            if (!channel.isClosedForRead) {
                channel.cancel()
            }
        }

        0
    }
}
