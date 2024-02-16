package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.usecase.ReadWordlistFromUrl
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import org.kodein.di.DirectDI
import org.kodein.di.instance

class ReadWordlistFromUrlImpl(
    private val httpClient: HttpClient,
) : ReadWordlistFromUrl {
    constructor(directDI: DirectDI) : this(
        httpClient = directDI.instance("curl"),
    )

    override fun invoke(
        url: String,
    ): IO<List<String>> = ioEffect {
        val request = httpClient
            .get(url)
        val content = request
            .bodyAsText()
        with(content) {
            ReadWordlistUtil.parseAsWordlist()
        }
    }
}
