package com.artemchep.keyguard.feature.home.vault.model

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.feature.favicon.AppIconUrl
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import dev.icerock.moko.resources.ImageResource

@Immutable
sealed interface VaultItemIcon {
    @Immutable
    data class AppIcon(
        val data: AppIconUrl,
        val fallback: VaultItemIcon?,
    ) : VaultItemIcon

    @Immutable
    data class WebsiteIcon(
        val data: FaviconUrl,
        val fallback: VaultItemIcon?,
    ) : VaultItemIcon

    @Immutable
    data class VectorIcon(
        val imageVector: ImageVector,
    ) : VaultItemIcon

    @Immutable
    data class ImageIcon(
        val imageRes: ImageResource,
    ) : VaultItemIcon

    @Immutable
    data class TextIcon(
        val text: String,
    ) : VaultItemIcon
}
