package com.artemchep.keyguard.common.service.passkey.impl

import com.artemchep.keyguard.build.FileHashes
import com.artemchep.keyguard.common.model.FileResource
import com.artemchep.keyguard.common.service.json.jsonResourceListIo
import com.artemchep.keyguard.common.service.passkey.PassKeyService
import com.artemchep.keyguard.common.service.passkey.PassKeyServiceInfo
import com.artemchep.keyguard.common.service.text.TextService
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

@Serializable
data class PassKeyEntity(
    val id: String,
    val name: String,
    val domain: String,
    @SerialName("additional-domains")
    val domains: Set<String> = emptySet(),
    val setup: String? = null,
    val documentation: String? = null,
    val notes: String? = null,
    val category: String? = null,
    @SerialName("created_at")
    val createdAt: Instant? = null,
    val features: Set<String> = emptySet(),
)

fun PassKeyEntity.toDomain() = kotlin.run {
    PassKeyServiceInfo(
        id = id,
        name = name,
        domain = domain,
        domains = domains + domain,
        setup = setup,
        documentation = documentation,
        notes = notes,
        category = category,
        addedAt = createdAt,
        features = features,
    )
}

class PassKeyServiceImpl(
    private val textService: TextService,
    private val json: Json,
) : PassKeyService {
    companion object {
        private const val TAG = "PassKeyService"
    }

    private val listIo = jsonResourceListIo(
        textService = textService,
        json = json,
        resource = FileResource.passkeys,
        tag = TAG,
    ) { entity: PassKeyEntity ->
        entity.toDomain()
    }

    override val version: String
        get() = FileHashes.passkeys

    constructor(
        directDI: DirectDI,
    ) : this(
        textService = directDI.instance(),
        json = directDI.instance(),
    )

    override fun get() = listIo
}
