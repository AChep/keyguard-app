package com.artemchep.keyguard.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.ui.theme.badgeContainer

@Composable
inline fun AnimatedNewCounterBadge(
    count: Int?,
    predicate: (Int) -> Boolean = { it > 0 },
) = AnimatedCounterBadge(
    text = count?.takeIf(predicate)?.let { "+$it" },
)

@Composable
inline fun AnimatedTotalCounterBadge(
    count: Int?,
    predicate: (Int) -> Boolean = { it >= 0 },
) = AnimatedCounterBadge(
    text = count?.takeIf(predicate)?.toString(),
)

@Composable
fun AnimatedCounterBadge(
    text: String?,
) {
    ExpandedIfNotEmpty(
        valueOrNull = text,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
    ) {
        Badge(
            containerColor = MaterialTheme.colorScheme.badgeContainer,
        ) {
            Text(
                text = it,
            )
        }
    }
}
