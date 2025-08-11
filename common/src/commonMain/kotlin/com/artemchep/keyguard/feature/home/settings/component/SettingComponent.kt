package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.theme.Dimens
import kotlinx.coroutines.flow.Flow
import kotlin.reflect.KClass

typealias SettingComponent = Flow<SettingIi?>

data class SettingIi(
    val platformClass: KClass<out Platform>? = null,
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
    FlatItemLayoutExpressive(
        shapeState = LocalSettingItemShape.current,
        content = {
            FlatItemTextContent(
                title = {
                    Text(title)
                },
                text = if (text != null) {
                    // composable
                    {
                        Text(text)
                    }
                } else {
                    null
                },
            )
        },
    )
}
