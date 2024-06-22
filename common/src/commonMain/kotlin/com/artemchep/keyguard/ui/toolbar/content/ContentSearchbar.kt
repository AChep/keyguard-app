package com.artemchep.keyguard.ui.toolbar.content

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.home.vault.component.SearchTextField
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun CustomSearchbarContent(
    modifier: Modifier = Modifier,
    searchFieldModifier: Modifier = Modifier,
    searchFieldModel: TextFieldModel2,
    searchFieldPlaceholder: String,
    title: String? = null,
    subtitle: String? = null,
    icon: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier,
    ) {
        val hasTopBar = title != null || subtitle != null
        if (hasTopBar) {
            CustomToolbarContent(
                modifier = Modifier,
                title = title,
                subtitle = subtitle,
                icon = icon,
                actions = actions,
            )
        }
        SearchTextField(
            modifier = searchFieldModifier,
            text = searchFieldModel.state.value,
            placeholder = searchFieldPlaceholder,
            searchIcon = !hasTopBar,
            leading = {
                if (!hasTopBar) {
                    icon()
                }
            },
            trailing = {
                if (!hasTopBar) {
                    actions()
                }
            },
            onTextChange = searchFieldModel.onChange,
        )
    }
}
