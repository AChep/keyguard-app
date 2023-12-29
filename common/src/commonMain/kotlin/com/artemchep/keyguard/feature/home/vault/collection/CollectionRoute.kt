package com.artemchep.keyguard.feature.home.vault.collection

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.DialogRoute

data class CollectionRoute(
    val args: Args,
) : DialogRoute {
    data class Args(
        val collectionId: String,
    )

    @Composable
    override fun Content() {
        CollectionScreen(
            args = args,
        )
    }
}
