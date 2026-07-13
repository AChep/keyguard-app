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
    /**
     * Builds an index for [items], which must contain
     * at most one secret for each ID.
     */
    suspend fun build(
        items: List<DSecret>,
        metadata: VaultSearchIndexMetadata = VaultSearchIndexMetadata(),
        surface: String? = null,
        dataRevCounters: Map<String, Long> = emptyMap(),
    ): VaultSearchIndex
}
