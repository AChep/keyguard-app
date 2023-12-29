package com.artemchep.keyguard.feature.home.vault.search.filter

import arrow.core.partially1
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.platform.parcelize.LeIgnoredOnParcel
import com.artemchep.keyguard.platform.parcelize.LeParcelize

@LeParcelize
object PasswordDuplicateFilter : Filter, PureFilter {
    @LeIgnoredOnParcel
    override val id: String = "duplicate"

    override fun invoke(
        list: List<Any>,
        getter: (Any) -> VaultItem2.Item,
    ): (VaultItem2.Item) -> Boolean = kotlin.run {
        val map = mutableMapOf<String, Int>()
        list.forEach { wrapper ->
            val item = getter(wrapper)
            val password = item.password
            if (password != null) {
                val oldValue = map.getOrElse(password) { 0 }
                map[password] = oldValue + 1
            }
        }
        val set = map
            .asSequence()
            .mapNotNull { entry ->
                entry.key.takeIf { entry.value > 1 }
            }
            .toSet()
        ::internalFilter.partially1(set)
    }

    private fun internalFilter(passwords: Set<String>, item: VaultItem2.Item) =
        item.password in passwords
}
