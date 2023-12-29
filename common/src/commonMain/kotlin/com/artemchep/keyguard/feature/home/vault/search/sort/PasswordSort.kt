package com.artemchep.keyguard.feature.home.vault.search.sort

import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.platform.parcelize.LeIgnoredOnParcel
import com.artemchep.keyguard.platform.parcelize.LeParcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@LeParcelize
@Serializable
object PasswordSort : Sort, PureSort {
    @LeIgnoredOnParcel
    @Transient
    override val id: String = "password"

    override fun compare(
        a: VaultItem2.Item,
        b: VaultItem2.Item,
    ): Int = kotlin.run {
        val aTitle = a.password
        val bTitle = b.password
        // check if null
        if (aTitle === bTitle) return 0
        if (aTitle == null) return -1
        if (bTitle == null) return 1

        aTitle.compareTo(bTitle, ignoreCase = false)
    }
}
