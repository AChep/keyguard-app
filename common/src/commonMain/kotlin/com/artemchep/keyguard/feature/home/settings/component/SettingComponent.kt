package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.KgPicker
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.pref_item_color_scheme_title
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.theme.Dimens
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.stringResource
import kotlin.reflect.KClass

typealias SettingComponent = Flow<SettingIi?>

data class SettingIi(
    val platformClasses: List<KClass<out Platform>> = emptyList(),
    val search: Search? = null,
    val content: @Composable () -> Unit,
) {
    data class Search(
        val group: String,
        val tokens: List<String>,
    )
}

@Composable
fun getSettingsButtonStartPadding(): Dp {
    return Dimens.contentPadding + 28.dp
}

@Composable
fun SettingListItem(
    title: String,
    text: String? = null,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = null,
        title = title,
        text = text,
    )
}
