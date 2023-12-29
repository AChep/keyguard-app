package com.artemchep.keyguard.common.service.passkey.impl

import arrow.core.partially1
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.shared
import com.artemchep.keyguard.common.service.passkey.PassKeyService
import com.artemchep.keyguard.common.service.passkey.PassKeyServiceInfo
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.common.service.text.readFromResourcesAsText
import com.artemchep.keyguard.res.Res
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

@Serializable
data class PassKeyEntity(
    val name: String,
    val domain: String,
    @SerialName("additional-domains")
    val domains: Set<String> = emptySet(),
    val setup: String? = null,
    val documentation: String? = null,
    val notes: String? = null,
    val features: Set<String> = emptySet(),
)

fun PassKeyEntity.toDomain() = kotlin.run {
    PassKeyServiceInfo(
        name = name,
        domain = domain,
        domains = domains + domain,
        setup = setup,
        documentation = documentation,
        notes = notes,
        features = features,
    )
}

class PassKeyServiceImpl(
    private val textService: TextService,
    private val json: Json,
) : PassKeyService {
    private val listIo = ::loadPassKeyRawData
        .partially1(textService)
        .effectMap { jsonString ->
            val entities = json.decodeFromString<List<PassKeyEntity>>(jsonString)
            val models = entities.map(PassKeyEntity::toDomain)
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

private suspend fun loadPassKeyRawData(
    textService: TextService,
) = textService.readFromResourcesAsText(Res.files.passkeys)
