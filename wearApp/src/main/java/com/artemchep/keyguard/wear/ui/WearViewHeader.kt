package com.artemchep.keyguard.wear.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.artemchep.keyguard.feature.home.vault.component.VaultItemIcon2
import com.artemchep.keyguard.feature.home.vault.component.rememberSecretAccentColor
import com.artemchep.keyguard.feature.home.vault.component.surfaceColorAtElevationSemi
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.ui.Avatar
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.shimmer.shimmer
import com.artemchep.keyguard.ui.theme.combineAlpha

/**
 * Shared header composable for detail/view screens (vault view, send view).
 *
 * Renders an avatar with optional shimmer loading state, the item icon,
 * and the item name in a [ListHeader].
 *
 * @param isLoading Whether the screen is still loading data.
 * @param icon The vault item icon, or null if not yet available.
 * @param name The display name of the item, or null if not yet available.
 * @param accentLight Light accent color int for the avatar background.
 * @param accentDark Dark accent color int for the avatar background.
 * @param hasRemoteService Whether the item has a remote service (affects avatar color).
 */
@Composable
fun WearViewHeader(
    modifier: Modifier = Modifier,
    isLoading: Boolean,
    icon: VaultItemIcon?,
    name: String?,
    accentLight: Color,
    accentDark: Color,
    hasRemoteService: Boolean,
    transformation: SurfaceTransformation? = null,
) {
    ListHeader(
        modifier = modifier,
        transformation = transformation,
    ) {
        val shimmerColor = LocalContentColor.current
            .combineAlpha(DisabledEmphasisAlpha)

        val avatarBackground = if (isLoading || icon == null) {
            shimmerColor
        } else {
            if (
                (icon !is VaultItemIcon.VectorIcon &&
                        icon !is VaultItemIcon.TextIcon) ||
                !hasRemoteService
            ) {
                val elevation = LocalAbsoluteTonalElevation.current + 8.dp
                androidx.compose.material3.MaterialTheme.colorScheme
                    .surfaceColorAtElevationSemi(elevation = elevation)
                    .combineAlpha(LocalContentColor.current.alpha)
            } else {
                rememberSecretAccentColor(
                    accentLight = accentLight,
                    accentDark = accentDark,
                )
            }
        }
        Avatar(
            modifier = Modifier
                .size(24.dp)
                .then(
                    if (isLoading) {
                        Modifier
                            .shimmer()
                    } else {
                        Modifier
                    },
                ),
            color = avatarBackground,
        ) {
            if (icon != null) {
                VaultItemIcon2(
                    icon,
                    modifier = Modifier
                        .alpha(androidx.compose.material3.LocalContentColor.current.alpha),
                )
            }
        }

        if (name != null) {
            Spacer(
                modifier = Modifier
                    .width(6.dp),
            )
            Text(
                modifier = Modifier
                    .align(Alignment.CenterVertically),
                text = name,
            )
        }
    }
}
