package com.artemchep.keyguard.common.service.hibp.breaches.all.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.service.hibp.breaches.all.BreachesRemoteDataSource
import com.artemchep.keyguard.provider.bitwarden.api.builder.bodyOrApiException
import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachGroup
import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.userAgent
import kotlinx.coroutines.Dispatchers
import org.kodein.di.DirectDI
import org.kodein.di.instance

class BreachesRemoteDataSourceImpl(
    private val httpClient: HttpClient,
) : BreachesRemoteDataSource {
    constructor(directDI: DirectDI) : this(
        httpClient = directDI.instance(),
    )

    override fun get(): IO<HibpBreachGroup> = breachesRequestIo()
        .map {
            HibpBreachGroup(it)
        }

    private fun breachesRequestIo(
    ): IO<List<HibpBreachResponse>> = ioEffect(Dispatchers.IO) {
        // https://haveibeenpwned.com/API/v3#AllBreaches
        val url = "https://haveibeenpwned.com/api/v3/breaches"
        val breaches = httpClient
            .get(url) {
                // https://haveibeenpwned.com/API/v3#UserAgent
                userAgent("Keyguard")
            }
            .bodyOrApiException<List<HibpBreachResponse>>()
        breaches
    }
}