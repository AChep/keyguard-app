package com.artemchep.keyguard.feature.home.vault.organization

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.common.model.Loadable

/**
 * @author Artem Chepurnyi
 */
@Immutable
data class OrganizationState(
    val content: Loadable<Content?> = Loadable.Loading,
    val onClose: (() -> Unit)? = null,
) {
    @Immutable
    data class Content(
        val title: String,
        val config: Config,
    ) {
        @Immutable
        data class Config(
            val selfHost: Boolean,
        )
    }
}
