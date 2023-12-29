package com.artemchep.keyguard.ui.selection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.platform.leNavigationBars
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.util.HorizontalDivider

@Composable
fun SelectionBar(
    title: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClear: (() -> Unit)?,
) {
    Column {
        Surface(
            modifier = Modifier
                .fillMaxWidth(),
            tonalElevation = 3.dp,
        ) {
            val navBarInsets = WindowInsets.leNavigationBars
                .only(WindowInsetsSides.Bottom)
            Row(
                modifier = Modifier
                    .heightIn(min = 56.dp)
                    .windowInsetsPadding(navBarInsets),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    enabled = onClear != null,
                    onClick = {
                        onClear?.invoke()
                    },
                ) {
                    // TODO: Consider using Icons.Outlined.Deselect
                    Icon(
                        Icons.Outlined.Clear,
                        null,
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f),
                ) {
                    Button(
                        onClick = { /*TODO*/ },
                    ) {
                        IconBox(
                            main = Icons.Outlined.ViewList,
                            secondary = null, // Icons.Outlined.PanoramaFishEye,
                        )
                        if (title != null) {
                            Spacer(
                                modifier = Modifier
                                    .width(Dimens.buttonIconPadding),
                            )
                            title.invoke(this)
                        }
                    }
                    val textStyle = MaterialTheme.typography.titleMedium
                    CompositionLocalProvider(
                        LocalTextStyle provides textStyle,
                    ) {
                    }
                }
                Spacer(
                    modifier = Modifier
                        .width(16.dp),
                )
                trailing?.invoke(this)
            }
        }
        HorizontalDivider(
            transparency = false,
        )
    }
}
