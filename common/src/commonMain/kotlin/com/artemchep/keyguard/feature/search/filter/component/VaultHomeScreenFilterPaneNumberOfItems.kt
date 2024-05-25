package com.artemchep.keyguard.feature.search.filter.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.animatedNumberText
import org.jetbrains.compose.resources.stringResource

@Composable
fun VaultHomeScreenFilterPaneNumberOfItems(
    count: Int?,
    modifier: Modifier = Modifier,
) {
    ExpandedIfNotEmpty(
        modifier = modifier,
        valueOrNull = count,
    ) { n ->
        val animatedCount = animatedNumberText(n)
        Text(
            modifier = Modifier
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 8.dp,
                ),
            text = stringResource(Res.string.items_n, animatedCount),
            style = MaterialTheme.typography.titleSmall,
        )
    }
}
