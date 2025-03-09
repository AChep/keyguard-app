package com.artemchep.keyguard.feature.home.vault.search.sort

import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.platform.parcelize.LeIgnoredOnParcel
import com.artemchep.keyguard.platform.parcelize.LeParcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.text.Collator

@LeParcelize
@Serializable
object AlphabeticalSort : Sort, PureSort {
    /**
     * Collator is not thread safe, therefore each
     * thread must have its very own collator.
     */
    private val collatorThreadLocal = ThreadLocal.withInitial {
        Collator.getInstance().apply {
            strength = Collator.PRIMARY
            decomposition = Collator.CANONICAL_DECOMPOSITION
        }
    }

    fun compareStr(a: String, b: String) = kotlin.run {
        val collator = collatorThreadLocal.get()
        collator.compare(a, b)
    }

    @LeIgnoredOnParcel
    @Transient
    override val id: String = "alphabetical"

    override fun compare(
        a: VaultItem2.Item,
        b: VaultItem2.Item,
    ): Int = kotlin.run {
        val aTitle = a.title.text
        val bTitle = b.title.text
        compareStr(aTitle, bTitle)
    }
}
