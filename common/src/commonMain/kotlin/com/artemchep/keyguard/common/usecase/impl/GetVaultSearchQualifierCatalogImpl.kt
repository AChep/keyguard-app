package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.usecase.GetLocale
import com.artemchep.keyguard.common.usecase.GetVaultSearchQualifierCatalog
import com.artemchep.keyguard.feature.home.vault.search.query.VaultSearchQualifierCatalog
import com.artemchep.keyguard.feature.home.vault.search.query.buildVaultSearchQualifierCatalog
import com.artemchep.keyguard.feature.home.vault.search.query.localizableVaultSearchQualifierKeys
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetVaultSearchQualifierCatalogImpl(
    private val getLocale: GetLocale,
    private val context: LeContext,
) : GetVaultSearchQualifierCatalog {
    constructor(directDI: DirectDI) : this(
        getLocale = directDI.instance(),
        context = directDI.instance(),
    )

    override fun invoke(): Flow<VaultSearchQualifierCatalog> = getLocale()
        .mapLatest {
            val localizedAliasesByCanonicalName =
                buildMap {
                    localizableVaultSearchQualifierKeys.forEach { (canonicalName, resource) ->
                        val localizedAlias = textResource(resource, context).trim()
                        if (localizedAlias.isNotEmpty()) {
                            put(canonicalName, setOf(localizedAlias))
                        }
                    }
                }
            buildVaultSearchQualifierCatalog(localizedAliasesByCanonicalName)
        }
}
