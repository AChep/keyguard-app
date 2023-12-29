package com.artemchep.keyguard.feature.home.vault.search.sort

import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.platform.parcelize.LeIgnoredOnParcel
import com.artemchep.keyguard.platform.parcelize.LeParcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@LeParcelize
@Serializable
object PasswordStrengthSort : Sort, PureSort {
    private object PasswordStrengthRawComparator : Comparator<VaultItem2.Item> {
        override fun compare(
            a: VaultItem2.Item,
            b: VaultItem2.Item,
        ): Int = -compareValues(b.score?.score, a.score?.score)
    }

    @LeIgnoredOnParcel
    @Transient
    override val id: String = "password_strength"

    @LeIgnoredOnParcel
    private val comparator = PasswordStrengthRawComparator
        .thenBy(AlphabeticalSort) { it }

    override fun compare(
        a: VaultItem2.Item,
        b: VaultItem2.Item,
    ): Int = comparator.compare(a, b)
}
