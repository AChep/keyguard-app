package com.artemchep.keyguard.common.service.justgetmydata.impl

import arrow.core.partially1
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.shared
import com.artemchep.keyguard.common.model.FileResource
import com.artemchep.keyguard.common.service.justgetmydata.JustGetMyDataService
import com.artemchep.keyguard.common.service.justgetmydata.JustGetMyDataServiceInfo
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.common.service.text.readFromResourcesAsText
import com.artemchep.keyguard.common.service.tld.TldService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

@Serializable
data class JustGetMyDataEntity(
    val name: String,
    @SerialName("domains")
    val domains: Set<String> = emptySet(),
    val url: String? = null,
    val difficulty: String? = null,
    val notes: String? = null,
    val email: String? = null,
    @SerialName("email_subject")
    val emailSubject: String? = null,
    @SerialName("email_body")
    val emailBody: String? = null,
)

fun JustGetMyDataEntity.toDomain(
    additionalDomain: String?,
) = kotlin.run {
    val newDomains = if (additionalDomain != null) {
        domains + additionalDomain
    } else {
        domains
    }
    JustGetMyDataServiceInfo(
        name = name,
        domains = newDomains,
        url = url,
        difficulty = difficulty,
        notes = notes,
        email = email,
        emailSubject = emailSubject,
        emailBody = emailBody,
    )
}

class JustGetMyDataServiceImpl(
    private val textService: TextService,
    private val tldService: TldService,
    private val json: Json,
) : JustGetMyDataService {
    private val hostRegex = "://(.*@)?([^/]+)".toRegex()

    private val listIo = ::loadJustGetMyDataRawData
        .partially1(textService)
        .effectMap { jsonString ->
            val entities = json.decodeFromString<List<JustGetMyDataEntity>>(jsonString)
            val models = entities
                .map { entity ->
                    val host = entity.url
                        ?.let { url ->
                            val result = hostRegex.find(url)
                            result?.groupValues?.getOrNull(2) // get the host
                        }
                    val domain = host?.let {
                        tldService.getDomainName(host)
                            .attempt()
                            .bind()
                            .getOrNull()
                    }
                    entity.toDomain(
                        additionalDomain = domain,
                    )
                }
            models
        }
        .shared()

    constructor(
        directDI: DirectDI,
    ) : this(
        textService = directDI.instance(),
        tldService = directDI.instance(),
        json = directDI.instance(),
    )

    override fun get() = listIo
}

private suspend fun loadJustGetMyDataRawData(
    textService: TextService,
) = textService.readFromResourcesAsText(FileResource.justGetMyData)
