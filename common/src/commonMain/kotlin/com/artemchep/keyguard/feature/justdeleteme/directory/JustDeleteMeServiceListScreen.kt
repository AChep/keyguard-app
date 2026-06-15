package com.artemchep.keyguard.feature.justdeleteme.directory

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.URL_JUST_DELETE_ME
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.EmptySearchView
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.justdeleteme.AhDifficulty
import com.artemchep.keyguard.feature.navigation.navigationNextEntryOrNull
import com.artemchep.keyguard.feature.servicedirectory.ServiceDirectoryListScaffold
import com.artemchep.keyguard.feature.twopane.LocalHasDetailPane
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.justdeleteme_empty_label
import com.artemchep.keyguard.res.justdeleteme_search_placeholder
import com.artemchep.keyguard.res.justdeleteme_title
import com.artemchep.keyguard.ui.theme.selectedContainer
import org.jetbrains.compose.resources.stringResource

@Composable
fun JustDeleteMeListScreen(
) {
    val loadableState = produceJustDeleteMeServiceListState()
    JustDeleteMeListScreen(
        loadableState = loadableState,
    )
}

@Composable
fun JustDeleteMeListScreen(
    loadableState: Loadable<JustDeleteMeServiceListState>,
) {
    ServiceDirectoryListScaffold(
        loadableState = loadableState,
        title = stringResource(Res.string.justdeleteme_title),
        url = URL_JUST_DELETE_ME,
        searchPlaceholder = stringResource(Res.string.justdeleteme_search_placeholder),
        errorText = "Failed to load app list!",
        filter = JustDeleteMeServiceListState::filter,
        content = JustDeleteMeServiceListState::content,
        filterRevision = JustDeleteMeServiceListState.Filter::revision,
        filterQuery = JustDeleteMeServiceListState.Filter::query,
        contentRevision = JustDeleteMeServiceListState.Content::revision,
        contentItems = JustDeleteMeServiceListState.Content::items,
        itemKey = { it.key },
        itemContentType = { it.contentType },
        sectionNameOrNull = { item ->
            (item as? JustDeleteMeServiceListState.Item.Section)?.name
        },
        contentItemOrNull = { item ->
            item as? JustDeleteMeServiceListState.Item.Content
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
                text = stringResource(Res.string.justdeleteme_empty_label),
            )
        },
    )
}

@Composable
private fun AppItem(
    modifier: Modifier,
    item: JustDeleteMeServiceListState.Item.Content,
) {
    val backgroundColor = run {
        if (LocalHasDetailPane.current) {
            val nextEntry = navigationNextEntryOrNull()
            val nextRoute = nextEntry?.route as? JustDeleteMeServiceViewFullRoute

            val selected = nextRoute?.args?.justDeleteMe?.name == item.name.text
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
