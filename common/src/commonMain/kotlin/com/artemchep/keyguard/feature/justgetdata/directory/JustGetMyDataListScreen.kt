package com.artemchep.keyguard.feature.justgetdata.directory

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.URL_JUST_GET_MY_DATA
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.EmptySearchView
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.justgetdata.AhDifficulty
import com.artemchep.keyguard.feature.navigation.navigationNextEntryOrNull
import com.artemchep.keyguard.feature.servicedirectory.ServiceDirectoryListScaffold
import com.artemchep.keyguard.feature.twopane.LocalHasDetailPane
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.justgetmydata_empty_label
import com.artemchep.keyguard.res.justgetmydata_search_placeholder
import com.artemchep.keyguard.res.justgetmydata_title
import com.artemchep.keyguard.ui.theme.selectedContainer
import org.jetbrains.compose.resources.stringResource

@Composable
fun JustGetMyDataListScreen(
) {
    val loadableState = produceJustGetMyDataListState()
    JustGetMyDataListScreen(
        loadableState = loadableState,
    )
}

@Composable
fun JustGetMyDataListScreen(
    loadableState: Loadable<JustGetMyDataListState>,
) {
    ServiceDirectoryListScaffold(
        loadableState = loadableState,
        title = stringResource(Res.string.justgetmydata_title),
        url = URL_JUST_GET_MY_DATA,
        searchPlaceholder = stringResource(Res.string.justgetmydata_search_placeholder),
        errorText = "Failed to load just-get-my-data list!",
        filter = JustGetMyDataListState::filter,
        content = JustGetMyDataListState::content,
        filterRevision = JustGetMyDataListState.Filter::revision,
        filterQuery = JustGetMyDataListState.Filter::query,
        contentRevision = JustGetMyDataListState.Content::revision,
        contentItems = JustGetMyDataListState.Content::items,
        itemKey = { it.key },
        itemContentType = { it.contentType },
        sectionNameOrNull = { item ->
            (item as? JustGetMyDataListState.Item.Section)?.name
        },
        contentItemOrNull = { item ->
            item as? JustGetMyDataListState.Item.Content
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
        text = {
            Text(
                text = stringResource(Res.string.justgetmydata_empty_label),
            )
        },
    )
}

@Composable
private fun AppItem(
    modifier: Modifier,
    item: JustGetMyDataListState.Item.Content,
) {
    val backgroundColor = run {
        if (LocalHasDetailPane.current) {
            val nextEntry = navigationNextEntryOrNull()
            val nextRoute = nextEntry?.route as? JustGetMyDataViewFullRoute

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
        trailing = {
            AhDifficulty(
                modifier = Modifier,
                model = item.data,
            )
        },
        onClick = item.onClick,
    )
}
