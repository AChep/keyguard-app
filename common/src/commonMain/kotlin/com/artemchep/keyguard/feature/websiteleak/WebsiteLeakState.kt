package com.artemchep.keyguard.feature.websiteleak

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class WebsiteLeakState(
    val content: Content,
    val onClose: (() -> Unit)? = null,
) {
    @Immutable
    data class Content(
        val breaches: ImmutableList<Breach>,
    )

    @Immutable
    data class Breach(
        val title: String,
        val domain: String,
        val description: String,
        val icon: String?,
        val count: Int?,
        /** Time when this breach has occurred */
        val occurredAt: String?,
        /** Time when this breach was acknowledged */
        val reportedAt: String?,
        val dataClasses: List<String>,
    )
}
