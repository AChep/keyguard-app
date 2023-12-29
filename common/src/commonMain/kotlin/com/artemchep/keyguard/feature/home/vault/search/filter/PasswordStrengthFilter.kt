package com.artemchep.keyguard.feature.home.vault.search.filter

import com.artemchep.keyguard.common.model.PasswordStrength
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.platform.parcelize.LeParcelize

@LeParcelize
data class PasswordStrengthFilter(
    val score: PasswordStrength.Score,
    override val id: String = score.name,
) : Filter, PureFilter {
    override fun invoke(
        list: List<Any>,
        getter: (Any) -> VaultItem2.Item,
    ): (VaultItem2.Item) -> Boolean = ::internalFilter

    private fun internalFilter(item: VaultItem2.Item) = item.score?.score == score
}
