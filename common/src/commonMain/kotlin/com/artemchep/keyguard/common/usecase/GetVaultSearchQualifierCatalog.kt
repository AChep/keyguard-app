package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.feature.home.vault.search.query.VaultSearchQualifierCatalog
import kotlinx.coroutines.flow.Flow

interface GetVaultSearchQualifierCatalog : () -> Flow<VaultSearchQualifierCatalog>
