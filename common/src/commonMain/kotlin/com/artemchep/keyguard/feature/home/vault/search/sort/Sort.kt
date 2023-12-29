package com.artemchep.keyguard.feature.home.vault.search.sort

import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.platform.parcelize.LeParcelable

interface PureSort : Comparator<VaultItem2.Item> {
    val id: String
}

interface Sort : PureSort, LeParcelable {
    companion object {
        fun valueOf(
            name: String,
        ): Sort? = when (name) {
            AlphabeticalSort.id -> AlphabeticalSort
            LastCreatedSort.id -> LastCreatedSort
            LastModifiedSort.id -> LastModifiedSort
            PasswordSort.id -> PasswordSort
            PasswordLastModifiedSort.id -> PasswordLastModifiedSort
            PasswordStrengthSort.id -> PasswordStrengthSort
            else -> null
        }
    }
}
