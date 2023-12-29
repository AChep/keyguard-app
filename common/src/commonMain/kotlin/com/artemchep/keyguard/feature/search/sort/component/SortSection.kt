package com.artemchep.keyguard.feature.search.sort.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.feature.home.vault.component.Section

@Composable
fun SortSectionComposable(
    modifier: Modifier = Modifier,
    text: String?,
) {
    Section(
        modifier = modifier,
        text = text,
    )
}
