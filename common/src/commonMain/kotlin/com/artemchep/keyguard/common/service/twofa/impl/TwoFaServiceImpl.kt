package com.artemchep.keyguard.common.service.twofa.impl

import arrow.core.partially1
import com.artemchep.keyguard.build.FileHashes
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.shared
import com.artemchep.keyguard.common.io.sharedSoftRef
import com.artemchep.keyguard.common.model.FileResource
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.common.service.text.readFromResourcesAsText
import com.artemchep.keyguard.common.service.twofa.TwoFaService
import com.artemchep.keyguard.common.service.twofa.TwoFaServiceInfo
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

@Serializable
data class TfaEntity(
    val name: String,
    val domain: String,
    @SerialName("additional-domains")
    val domains: Set<String> = emptySet(),
    val url: String? = null,
    val documentation: String? = null,
    val notes: String? = null,
    val tfa: Set<String> = emptySet(),
)

fun TfaEntity.toDomain() = kotlin.run {
    TwoFaServiceInfo(
        name = name,
        domain = domain,
        domains = domains + domain,
        url = url,
        documentation = documentation,
        notes = notes,
        tfa = tfa,
    )
}

class TwoFaServiceImpl(
    private val textService: TextService,
    private val json: Json,
) : TwoFaService {
    companion object {
        private const val TAG = "TwoFaService"
    }

    private val listIo = ::loadTfaRawData
        .partially1(textService)
        .effectMap { jsonString ->
            val entities = json.decodeFromString<List<TfaEntity>>(jsonString)
            val models = entities.map(TfaEntity::toDomain)
            models
        }
        .sharedSoftRef(TAG)

    override val version: String
        get() = FileHashes.tfa

    constructor(
        directDI: DirectDI,
    ) : this(
        textService = directDI.instance(),
        json = directDI.instance(),
    )

    override fun get() = listIo
}

private suspend fun loadTfaRawData(
    textService: TextService,
) = textService.readFromResourcesAsText(FileResource.tfa)
