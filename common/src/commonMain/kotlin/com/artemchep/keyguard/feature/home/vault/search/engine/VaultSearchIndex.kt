package com.artemchep.keyguard.feature.home.vault.search.engine

import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.search.query.VaultSearchQualifierCatalog
import com.artemchep.keyguard.feature.home.vault.search.query.defaultVaultSearchQualifierCatalog
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.CompiledQueryPlan
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultTextField

data class VaultSearchResult(
    val items: List<VaultItem2.Item>,
    val plan: CompiledQueryPlan?,
)

data class VaultSearchMatch(
    val item: VaultItem2.Item,
    val score: Double,
    val titleTerms: Set<String>,
    val context: Context?,
) {
    data class Context(
        val field: VaultTextField,
        val snippet: String,
        val score: Double,
    )
}

interface VaultSearchIndex {
    fun compile(
        query: String,
        searchBy: VaultRoute.Args.SearchBy = VaultRoute.Args.SearchBy.ALL,
        qualifierCatalog: VaultSearchQualifierCatalog = defaultVaultSearchQualifierCatalog,
    ): CompiledQueryPlan?

    suspend fun match(
        plan: CompiledQueryPlan?,
        candidates: List<VaultItem2.Item>,
    ): List<VaultSearchMatch>

    suspend fun evaluate(
        plan: CompiledQueryPlan?,
        candidates: List<VaultItem2.Item>,
        highlightBackgroundColor: Color,
        highlightContentColor: Color,
    ): List<VaultItem2.Item>
}

internal interface SurfaceAwareVaultSearchIndex : VaultSearchIndex {
    fun withSurface(
        surface: String?,
    ): VaultSearchIndex
}
