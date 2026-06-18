package com.artemchep.keyguard.feature.passkeys.directory

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.URL_PASSKEYS
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.EmptySearchView
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.navigation.navigationNextEntryOrNull
import com.artemchep.keyguard.feature.servicedirectory.ServiceDirectoryListScaffold
import com.artemchep.keyguard.feature.twopane.LocalHasDetailPane
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.passkeys_directory_search_placeholder
import com.artemchep.keyguard.res.passkeys_directory_title
import com.artemchep.keyguard.ui.theme.selectedContainer
import org.jetbrains.compose.resources.stringResource

@Composable
fun PasskeysListScreen(
) {
    val loadableState = producePasskeysListState()
    PasskeysListScreen(
        loadableState = loadableState,
    )
}

@Composable
fun PasskeysListScreen(
    loadableState: Loadable<PasskeysServiceListState>,
) {
    ServiceDirectoryListScaffold(
        loadableState = loadableState,
        title = stringResource(Res.string.passkeys_directory_title),
        url = URL_PASSKEYS,
        searchPlaceholder = stringResource(Res.string.passkeys_directory_search_placeholder),
        errorText = "Failed to load app list!",
        filter = PasskeysServiceListState::filter,
        content = PasskeysServiceListState::content,
        filterRevision = PasskeysServiceListState.Filter::revision,
        filterQuery = PasskeysServiceListState.Filter::query,
        contentRevision = PasskeysServiceListState.Content::revision,
        contentItems = PasskeysServiceListState.Content::items,
        itemKey = { it.key },
        itemContentType = { it.contentType },
        sectionNameOrNull = { item ->
            (item as? PasskeysServiceListState.Item.Section)?.name
        },
        contentItemOrNull = { item ->
            item as? PasskeysServiceListState.Item.Content
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
    item: PasskeysServiceListState.Item.Content,
) {
    val backgroundColor = run {
        if (LocalHasDetailPane.current) {
            val nextEntry = navigationNextEntryOrNull()
            val nextRoute = nextEntry?.route as? PasskeysServiceViewFullRoute

            val selected = nextRoute?.args?.model?.id == item.data.id
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
