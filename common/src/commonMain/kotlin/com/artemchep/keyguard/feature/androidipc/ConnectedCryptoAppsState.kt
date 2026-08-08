package com.artemchep.keyguard.feature.androidipc

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.common.model.GroupableShapeItem
import com.artemchep.keyguard.common.model.ShapeState

@Immutable
data class ConnectedCryptoAppsState(
    val apps: List<App>,
) {
    @Immutable
    data class App(
        val key: String,
        val packageName: String,
        val label: String,
        val signer: String,
        val registeredAt: String,
        val lastUsedAt: String,
        val installed: Boolean,
        val signerMismatch: Boolean,
        val onRevoke: () -> Unit,
        val shapeState: Int = ShapeState.ALL,
    ) : GroupableShapeItem<App> {
        override fun withShape(shape: Int) = copy(shapeState = shape)
    }
}
