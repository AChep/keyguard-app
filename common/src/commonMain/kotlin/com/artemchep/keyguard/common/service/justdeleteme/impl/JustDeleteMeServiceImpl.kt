package com.artemchep.keyguard.common.service.justdeleteme.impl

import com.artemchep.keyguard.common.model.FileResource
import com.artemchep.keyguard.common.service.json.jsonResourceListIo
import com.artemchep.keyguard.common.service.justdeleteme.JustDeleteMeService
import com.artemchep.keyguard.common.service.justdeleteme.JustDeleteMeServiceInfo
import com.artemchep.keyguard.common.service.text.TextService
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
    companion object {
        private const val TAG = "JustDeleteMeService"
    }

    private val listIo = jsonResourceListIo(
        textService = textService,
        json = json,
        resource = FileResource.justDeleteMe,
        tag = TAG,
    ) { entity: JustDeleteMeEntity ->
        entity.toDomain()
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        textService = directDI.instance(),
        json = directDI.instance(),
    )

    override fun get() = listIo
}
