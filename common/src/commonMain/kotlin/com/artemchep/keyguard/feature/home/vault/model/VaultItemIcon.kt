package com.artemchep.keyguard.feature.home.vault.model

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.common.util.nextSymbol
import com.artemchep.keyguard.feature.favicon.AppIconUrl
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import org.jetbrains.compose.resources.DrawableResource

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
        val imageRes: DrawableResource,
    ) : VaultItemIcon

    @Immutable
    data class TextIcon(
        val text: String,
    ) : VaultItemIcon {
        companion object
    }
}

fun VaultItemIcon.TextIcon.Companion.short(text: String): VaultItemIcon.TextIcon {
    val abbr = kotlin.run {
        val words = text.split(" ")
        if (words.size <= 1) {
            val word = words.firstOrNull()
                ?: return@run ""
            return@run (0 until 2)
                .fold("") { str, _ ->
                    str + word.nextSymbol(index = str.length)
                }
        }

        words
            .take(2)
            .joinToString("") {
                it.nextSymbol()
            }
    }
    return VaultItemIcon.TextIcon(
        text = abbr,
    )
}
