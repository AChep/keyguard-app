package com.artemchep.keyguard.ui.toolbar.content

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.skeleton.SkeletonText
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha

private val toolbarMinHeight = 64.dp

@Composable
fun CustomToolbarContent(
    modifier: Modifier = Modifier,
    title: String?,
    icon: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .heightIn(min = toolbarMinHeight),
        verticalAlignment = Alignment.Top,
    ) {
        Row(
            modifier = Modifier
                .widthIn(min = Dimens.horizontalPadding),
        ) {
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .heightIn(min = toolbarMinHeight),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
            Spacer(Modifier.width(4.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .align(Alignment.CenterVertically)
                .padding(vertical = 4.dp),
        ) {
            val titleStyle = MaterialTheme.typography.titleLarge
            if (title != null) {
                Text(
                    text = title,
                    style = titleStyle,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 2,
                )
            } else {
                SkeletonText(
                    modifier = Modifier
                        .fillMaxWidth(0.4f),
                    style = titleStyle,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .heightIn(min = toolbarMinHeight),
            contentAlignment = Alignment.Center,
        ) {
            actions()
        }
        Spacer(Modifier.width(4.dp))
    }
}
