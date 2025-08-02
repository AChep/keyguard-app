package com.artemchep.keyguard.feature.loading

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator

@Composable
fun LoadingScreen() {
    LoadingScreenContent()
}

@Composable
private fun LoadingScreenContent() {
    Box(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        KeyguardLoadingIndicator(
            contained = true,
        )
    }
}
