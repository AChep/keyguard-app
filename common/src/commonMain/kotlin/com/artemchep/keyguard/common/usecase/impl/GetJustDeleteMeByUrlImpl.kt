package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.service.justdeleteme.JustDeleteMeService
import com.artemchep.keyguard.common.service.justdeleteme.JustDeleteMeServiceInfo
import com.artemchep.keyguard.common.usecase.GetJustDeleteMeByUrl
import io.ktor.http.Url
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetJustDeleteMeByUrlImpl(
    private val justDeleteMeService: JustDeleteMeService,
) : GetJustDeleteMeByUrl {
    constructor(directDI: DirectDI) : this(
        justDeleteMeService = directDI.instance(),
    )

    override fun invoke(
        url: String,
    ): IO<JustDeleteMeServiceInfo?> = justDeleteMeService.get()
        .effectMap { list ->
            match(url, list)
        }

    fun match(url: String, list: List<JustDeleteMeServiceInfo>) = kotlin.run {
        val host = parseHost(url)
            ?: return@run null
        val result = list
            .firstOrNull { host in it.domains }
        result
    }

    private fun parseHost(url: String) = if (
        url.startsWith("http://") ||
        url.startsWith("https://")
    ) {
        val parsedUri = kotlin.runCatching {
            Url(url)
        }.getOrElse {
            // can not get the domain
            null
        }
        parsedUri
            ?.host
            // The "www" subdomain is ignored in the database, however
            // it's only "www". Other subdomains, such as "photos",
            // should be respected.
            ?.removePrefix("www.")
    } else {
        // can not get the domain
        null
    }
}
