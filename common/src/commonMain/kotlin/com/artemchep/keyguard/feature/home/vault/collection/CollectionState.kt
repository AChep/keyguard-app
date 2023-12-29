package com.artemchep.keyguard.feature.home.vault.collection

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.model.Loadable

/**
 * @author Artem Chepurnyi
 */
@Immutable
data class CollectionState(
    val content: Loadable<Content?> = Loadable.Loading,
    val onClose: (() -> Unit)? = null,
) {
    @Immutable
    data class Content(
        val title: String,
        val organization: DOrganization?,
        val config: Config,
    ) {
        @Immutable
        data class Config(
            val readOnly: Boolean,
            val hidePasswords: Boolean,
        )
    }
}
