package com.artemchep.keyguard.feature.tfa.directory

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.URL_2FA
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.EmptySearchView
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.navigation.navigationNextEntryOrNull
import com.artemchep.keyguard.feature.servicedirectory.ServiceDirectoryListScaffold
import com.artemchep.keyguard.feature.twopane.LocalHasDetailPane
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.tfa_directory_search_placeholder
import com.artemchep.keyguard.res.tfa_directory_title
import com.artemchep.keyguard.ui.theme.selectedContainer
import org.jetbrains.compose.resources.stringResource

@Composable
fun TwoFaServiceListScreen(
) {
    val loadableState = produceTwoFaServiceListState()
    TwoFaServiceListScreen(
        loadableState = loadableState,
    )
}

@Composable
fun TwoFaServiceListScreen(
    loadableState: Loadable<TwoFaServiceListState>,
) {
    ServiceDirectoryListScaffold(
        loadableState = loadableState,
        title = stringResource(Res.string.tfa_directory_title),
        url = URL_2FA,
        searchPlaceholder = stringResource(Res.string.tfa_directory_search_placeholder),
        errorText = "Failed to load app list!",
        filter = TwoFaServiceListState::filter,
        content = TwoFaServiceListState::content,
        filterRevision = TwoFaServiceListState.Filter::revision,
        filterQuery = TwoFaServiceListState.Filter::query,
        contentRevision = TwoFaServiceListState.Content::revision,
        contentItems = TwoFaServiceListState.Content::items,
        itemKey = { it.key },
        itemContentType = { it.contentType },
        sectionNameOrNull = { item ->
            (item as? TwoFaServiceListState.Item.Section)?.name
        },
        contentItemOrNull = { item ->
            item as? TwoFaServiceListState.Item.Content
        },
        noItems = { modifier ->
            NoItemsPlaceholder(
                modifier = modifier,
            )
        },
        contentItem = { modifier, item ->
            AppItem(
                modifier = modifier,
                item = item,
            )
        },
    )
}

@Composable
private fun NoItemsPlaceholder(
    modifier: Modifier = Modifier,
) {
    EmptySearchView(
        modifier = modifier,
    )
}

@Composable
private fun AppItem(
    modifier: Modifier,
    item: TwoFaServiceListState.Item.Content,
) {
    val backgroundColor = run {
        if (LocalHasDetailPane.current) {
            val nextEntry = navigationNextEntryOrNull()
            val nextRoute = nextEntry?.route as? TwoFaServiceViewFullRoute

            val selected = nextRoute?.args?.model?.name == item.name.text
            if (selected) {
                return@run MaterialTheme.colorScheme.selectedContainer
            }
        }

        Color.Unspecified
    }
    FlatItemSimpleExpressive(
        modifier = modifier,
        backgroundColor = backgroundColor,
        shapeState = item.shapeState,
        leading = {
            item.icon()
        },
        title = {
            Text(item.name)
        },
        onClick = item.onClick,
    )
}
