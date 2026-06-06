package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.ui.FingerprintPlaneta

@Composable
fun VaultViewPlanetaItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Planeta,
) {
    Box(
        modifier = modifier
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        FingerprintPlaneta(
            modifier = Modifier
                .size(144.dp),
            fingerprint = item.fingerprint,
        )
    }
}
