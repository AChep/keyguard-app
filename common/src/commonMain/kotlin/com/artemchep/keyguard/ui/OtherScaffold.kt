package com.artemchep.keyguard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.platform.leIme
import com.artemchep.keyguard.platform.leSystemBars
import com.artemchep.keyguard.ui.theme.Dimens

@Composable
fun OtherScaffold(
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier,
    ) {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val insets = WindowInsets.leSystemBars
                .union(WindowInsets.leIme)
            Column(
                modifier = Modifier
                    .windowInsetsPadding(insets)
                    .padding(
                        vertical = Dimens.verticalPadding,
                        horizontal = Dimens.horizontalPadding,
                    )
                    .widthIn(max = 280.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                content()
            }
        }

        val insets = WindowInsets.leSystemBars
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(insets)
                .height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            actions?.invoke(this)
            Spacer(Modifier.width(4.dp))
        }
    }
}
