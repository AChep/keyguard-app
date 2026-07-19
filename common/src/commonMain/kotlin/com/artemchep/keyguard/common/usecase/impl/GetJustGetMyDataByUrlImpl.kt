package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.service.justgetmydata.JustGetMyDataService
import com.artemchep.keyguard.common.service.justgetmydata.JustGetMyDataServiceInfo
import com.artemchep.keyguard.common.usecase.GetJustGetMyDataByUrl
import com.artemchep.keyguard.common.util.parseHttpUrlHostOrNull
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetJustGetMyDataByUrlImpl(
    private val justGetMyDataService: JustGetMyDataService,
) : GetJustGetMyDataByUrl {
    constructor(directDI: DirectDI) : this(
        justGetMyDataService = directDI.instance(),
    )

    override fun invoke(
        url: String,
    ): IO<JustGetMyDataServiceInfo?> = justGetMyDataService.get()
        .effectMap { list ->
            match(url, list)
        }

    fun match(url: String, list: List<JustGetMyDataServiceInfo>) = kotlin.run {
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
