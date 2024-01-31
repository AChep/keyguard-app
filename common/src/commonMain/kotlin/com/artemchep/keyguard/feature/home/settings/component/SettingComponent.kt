package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.ui.FlatItem
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
fun SettingListItem(
    title: String,
    text: String? = null,
) {
    FlatItem(
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
}
