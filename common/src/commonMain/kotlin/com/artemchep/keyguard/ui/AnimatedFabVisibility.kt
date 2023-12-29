package com.artemchep.keyguard.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopAppBarScrollBehavior.rememberFabExpanded() = remember(this) {
    derivedStateOf {
        state.overlappedFraction < 0.01f
    }
}
