package com.artemchep.keyguard.ui.button

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import com.artemchep.keyguard.ui.icons.KeyguardFavourite
import com.artemchep.keyguard.ui.icons.KeyguardFavouriteOutline
import com.artemchep.keyguard.ui.theme.combineAlpha

@Composable
fun FavouriteToggleButton(
    modifier: Modifier = Modifier,
    favorite: Boolean,
    onChange: ((Boolean) -> Unit)?,
) {
    val updatedOnChange by rememberUpdatedState(onChange)
    val enabled by remember {
        derivedStateOf {
            updatedOnChange != null
        }
    }
    IconToggleButton(
        modifier = modifier,
        checked = favorite,
        onCheckedChange = {
            updatedOnChange?.invoke(it)
        },
        enabled = enabled,
    ) {
        val contentColor = LocalContentColor.current
        val motionScheme = MaterialTheme.motionScheme
        val tint by animateColorAsState(
            targetValue = if (favorite) {
                MaterialTheme.colorScheme.primary
                    .combineAlpha(contentColor.alpha)
            } else {
                contentColor
            },
            animationSpec = motionScheme.fastEffectsSpec(),
            label = "FavouriteToggleTint",
        )
        AnimatedContent(
            targetState = favorite,
            transitionSpec = {
                val currentFavorite = this.targetState
                val slideSpec = motionScheme.fastSpatialSpec<IntOffset>()
                val scaleSpec = motionScheme.fastSpatialSpec<Float>()
                val enter = slideInVertically(
                    animationSpec = slideSpec,
                ) {
                    if (currentFavorite) -it else it
                } + scaleIn(
                    animationSpec = scaleSpec,
                )
                val exit = slideOutVertically(
                    animationSpec = slideSpec,
                ) {
                    if (currentFavorite) it else -it
                } + scaleOut(
                    animationSpec = scaleSpec,
                )
                enter togetherWith exit
            },
            label = "FavouriteToggleIcon",
        ) { currentFavorite ->
            val vector = if (currentFavorite) {
                Icons.Outlined.KeyguardFavourite
            } else {
                Icons.Outlined.KeyguardFavouriteOutline
            }
            Icon(
                imageVector = vector,
                contentDescription = null,
                tint = tint,
            )
        }
    }
}
