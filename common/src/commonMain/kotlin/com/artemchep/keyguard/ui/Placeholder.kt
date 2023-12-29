package com.artemchep.keyguard.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.res.Res
import compose.icons.FeatherIcons
import compose.icons.feathericons.Package
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun Placeholder(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
) {
    Column(
        modifier = modifier
            .padding(horizontal = 16.dp),
    ) {
        Placeholder(
            icon = icon,
            title = title,
        )
    }
}

@Composable
fun Placeholder(
    modifier: Modifier = Modifier,
) {
    Placeholder(
        modifier = modifier,
        icon = FeatherIcons.Package,
        title = stringResource(Res.strings.coming_soon),
    )
}

@Composable
fun ColumnScope.Placeholder(
    icon: ImageVector,
    title: String,
) {
    Placeholder(
        icon = { m ->
            Icon(
                modifier = m,
                imageVector = icon,
                contentDescription = null,
            )
        },
        title = { m ->
            Text(
                modifier = m,
                text = title,
                textAlign = TextAlign.Center,
            )
        },
    )
}

@Composable
fun ColumnScope.Placeholder(
    icon: @Composable (Modifier) -> Unit,
    title: @Composable (Modifier) -> Unit,
) {
    icon(
        Modifier
            .size(96.dp)
            .alpha(MediumEmphasisAlpha)
            .align(Alignment.CenterHorizontally),
    )
    Spacer(
        modifier = Modifier
            .height(16.dp),
    )
    ProvideTextStyle(MaterialTheme.typography.labelLarge) {
        title(
            Modifier
                .widthIn(max = 196.dp)
                .alpha(MediumEmphasisAlpha)
                .align(Alignment.CenterHorizontally),
        )
    }
}
