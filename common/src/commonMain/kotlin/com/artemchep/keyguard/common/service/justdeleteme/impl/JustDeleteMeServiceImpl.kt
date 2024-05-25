package com.artemchep.keyguard.common.service.justdeleteme.impl

import arrow.core.partially1
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.shared
import com.artemchep.keyguard.common.model.FileResource
import com.artemchep.keyguard.common.service.justdeleteme.JustDeleteMeService
import com.artemchep.keyguard.common.service.justdeleteme.JustDeleteMeServiceInfo
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.common.service.text.readFromResourcesAsText
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

@Serializable
data class JustDeleteMeEntity(
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

fun JustDeleteMeEntity.toDomain() = kotlin.run {
    JustDeleteMeServiceInfo(
        name = name,
        domains = domains,
        url = url,
        difficulty = difficulty,
        notes = notes,
        email = email,
        emailSubject = emailSubject,
        emailBody = emailBody,
    )
}

class JustDeleteMeServiceImpl(
    private val textService: TextService,
    private val json: Json,
) : JustDeleteMeService {
    private val listIo = ::loadJustDeleteMeRawData
        .partially1(textService)
        .effectMap { jsonString ->
            val entities = json.decodeFromString<List<JustDeleteMeEntity>>(jsonString)
            val models = entities.map(JustDeleteMeEntity::toDomain)
            models
        }
        .shared()

    constructor(
        directDI: DirectDI,
    ) : this(
        textService = directDI.instance(),
        json = directDI.instance(),
    )

    override fun get() = listIo
}

private suspend fun loadJustDeleteMeRawData(
    textService: TextService,
) = textService.readFromResourcesAsText(FileResource.justDeleteMe)
