package com.artemchep.keyguard.common.service.export

import com.artemchep.keyguard.common.model.CipherFilterContext
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetOrganizations
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class ExportVaultData(
    val ciphers: List<DSecret>,
    val folders: List<DFolder>,
    val collections: List<DCollection>,
    val organizations: List<DOrganization>,
)

interface ExportVaultDataService {
    suspend fun create(
        filter: DFilter,
    ): ExportVaultData

    fun exportJson(
        data: ExportVaultData,
    ): String
}

class ExportVaultDataServiceImpl(
    private val filterContext: CipherFilterContext,
    private val jsonExportService: JsonExportService,
    private val getOrganizations: GetOrganizations,
    private val getCollections: GetCollections,
    private val getFolders: GetFolders,
    private val getCiphers: GetCiphers,
) : ExportVaultDataService {
    override suspend fun create(
        filter: DFilter,
    ): ExportVaultData {
        val ciphers = getCiphers()
            .map { ciphers ->
                val predicate = filter.prepare(filterContext, ciphers)
                ciphers
                    .filter(predicate)
            }
            .first()
        val folders = kotlin.run {
            val folderIds = ciphers
                .asSequence()
                .map { it.folderId }
                .toSet()
            getFolders()
                .map { folders ->
                    folders.filter { it.id in folderIds }
                }
                .first()
        }
        val collections = kotlin.run {
            val collectionIds = ciphers
                .asSequence()
                .flatMap { it.collectionIds }
                .toSet()
            getCollections()
                .map { collections ->
                    collections.filter { it.id in collectionIds }
                }
                .first()
        }
        val organizations = kotlin.run {
            val organizationIds = ciphers
                .asSequence()
                .map { it.organizationId }
                .toSet()
            getOrganizations()
                .map { organizations ->
                    organizations.filter { it.id in organizationIds }
                }
                .first()
        }
        return ExportVaultData(
            ciphers = ciphers,
            folders = folders,
            collections = collections,
            organizations = organizations,
        )
    }

    override fun exportJson(
        data: ExportVaultData,
    ): String = jsonExportService.export(
        organizations = data.organizations,
        collections = data.collections,
        folders = data.folders,
        ciphers = data.ciphers,
    )
}
