package com.artemchep.keyguard.feature.environments.mounts

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.common.model.GroupableShapeItem
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.service.environment.EnvironmentMountStatus
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.SimpleNote
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class EnvironmentMountsState(
    val notes: ImmutableList<SimpleNote>,
    val items: ImmutableList<Item>,
    /**
     * Adds a local `.env` file, `null` if there
     * is no environment to add it for.
     */
    val primaryAction: (() -> Unit)?,
) {
    @Immutable
    data class Item(
        val key: String,
        val path: String,
        /** The environment name, `null` if it is not available. */
        val environmentName: String?,
        val status: EnvironmentMountStatus,
        val dropdown: ImmutableList<ContextItem>,
        val shapeState: Int = ShapeState.ALL,
    ) : GroupableShapeItem<Item> {
        override fun withShape(shape: Int) = copy(shapeState = shape)
    }
}
