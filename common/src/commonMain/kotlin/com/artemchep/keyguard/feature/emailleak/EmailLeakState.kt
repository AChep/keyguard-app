package com.artemchep.keyguard.feature.emailleak

import com.artemchep.keyguard.feature.favicon.RemoteIconUrl
import androidx.compose.runtime.Immutable
import arrow.core.Either
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class EmailLeakState(
    val username: String,
    val content: Either<Throwable, Content>,
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
        val icon: RemoteIconUrl?,
        val count: Int?,
        /** Time when this breach has occurred */
        val occurredAt: String?,
        /** Time when this breach was acknowledged */
        val reportedAt: String?,
        val dataClasses: List<String>,
    )
}
