package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.feature.home.vault.search.engine.VaultSearchIndex
import kotlinx.coroutines.flow.Flow

interface GetVaultSearchIndex {
    operator fun invoke(
        surface: String? = null,
    ): Flow<VaultSearchIndex>
}
