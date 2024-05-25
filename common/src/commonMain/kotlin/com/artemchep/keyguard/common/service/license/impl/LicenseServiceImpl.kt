package com.artemchep.keyguard.common.service.license.impl

import arrow.core.partially1
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.FileResource
import com.artemchep.keyguard.common.service.license.LicenseService
import com.artemchep.keyguard.common.service.license.model.License
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.common.service.text.readFromResourcesAsText
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

@Serializable
data class LicenseEntity(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val name: String? = null,
    val spdxLicenses: List<SpdxLicense> = emptyList(),
    val scm: Scm? = null,
) {
    @Serializable
    data class SpdxLicense(
        val identifier: String,
        val name: String,
        val url: String? = null,
    )

    @Serializable
    data class Scm(
        val url: String? = null,
    )
}

private fun LicenseEntity.toDomain(): License = License(
    groupId = groupId,
    artifactId = artifactId,
    version = version,
    name = name,
    spdxLicenses = spdxLicenses.map(LicenseEntity.SpdxLicense::toDomain),
    scm = scm?.let(LicenseEntity.Scm::toDomain),
)

private fun LicenseEntity.SpdxLicense.toDomain(): License.SpdxLicense = License.SpdxLicense(
    identifier = identifier,
    name = name,
    url = url,
)

private fun LicenseEntity.Scm.toDomain(): License.Scm = License.Scm(
    url = url,
)

class LicenseServiceImpl(
    private val textService: TextService,
    private val json: Json,
) : LicenseService {
    constructor(
        directDI: DirectDI,
    ) : this(
        textService = directDI.instance(),
        json = directDI.instance(),
    )

    override fun get(): IO<List<License>> = ::loadLicensesRawData
        .partially1(textService)
        .effectMap { jsonString ->
            val entities = json.decodeFromString<List<LicenseEntity>>(jsonString)
            val models = entities.map(LicenseEntity::toDomain)
            models
        }
}

private suspend fun loadLicensesRawData(
    textService: TextService,
) = textService.readFromResourcesAsText(FileResource.licenses)
