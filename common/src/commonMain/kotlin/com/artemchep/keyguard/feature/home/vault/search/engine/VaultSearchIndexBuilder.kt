package com.artemchep.keyguard.feature.home.vault.search.engine

import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DTag

data class VaultSearchIndexMetadata(
    val accounts: List<DAccount> = emptyList(),
    val folders: List<DFolder> = emptyList(),
    val tags: List<DTag> = emptyList(),
    val collections: List<DCollection> = emptyList(),
    val organizations: List<DOrganization> = emptyList(),
)

interface VaultSearchIndexBuilder {
    suspend fun build(
        items: List<DSecret>,
        metadata: VaultSearchIndexMetadata = VaultSearchIndexMetadata(),
        surface: String? = null,
    ): VaultSearchIndex
}
