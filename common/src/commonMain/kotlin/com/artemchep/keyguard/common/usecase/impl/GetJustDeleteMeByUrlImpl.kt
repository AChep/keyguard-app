package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.service.justdeleteme.JustDeleteMeService
import com.artemchep.keyguard.common.service.justdeleteme.JustDeleteMeServiceInfo
import com.artemchep.keyguard.common.usecase.GetJustDeleteMeByUrl
import com.artemchep.keyguard.common.util.parseHttpUrlHostOrNull

class GetJustDeleteMeByUrlImpl(
    private val justDeleteMeService: JustDeleteMeService,
) : GetJustDeleteMeByUrl {
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

    // The "www" subdomain is ignored in the database, however
    // it's only "www". Other subdomains, such as "photos",
    // should be respected.
    private fun parseHost(url: String) = parseHttpUrlHostOrNull(
        url = url,
        removeWww = true,
    )
}
