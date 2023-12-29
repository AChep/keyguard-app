package com.artemchep.keyguard.feature.home.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.common.model.Loadable
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class SettingPaneState(
    val list: Loadable<ImmutableList<Component>> = Loadable.Loading,
) {
    @Immutable
    data class Component(
        val compositeKey: String,
        val itemKey: String,
        val groupKey: String,
        val args: Any?,
        val content: (@Composable () -> Unit)?,
    )
}
