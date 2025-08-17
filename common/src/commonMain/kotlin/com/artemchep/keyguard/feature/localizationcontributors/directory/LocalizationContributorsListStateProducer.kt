package com.artemchep.keyguard.feature.localizationcontributors.directory

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import arrow.core.partially1
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.common.service.localizationcontributors.LocalizationContributor
import com.artemchep.keyguard.common.service.localizationcontributors.LocalizationContributorsService
import com.artemchep.keyguard.feature.crashlytics.crashlyticsAttempt
import com.artemchep.keyguard.feature.generator.wordlist.view.WordlistViewState
import com.artemchep.keyguard.feature.home.vault.search.IndexedText
import com.artemchep.keyguard.feature.justgetdata.directory.JustGetMyDataListState
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.search.keyboard.searchQueryShortcuts
import com.artemchep.keyguard.feature.search.search.IndexedModel
import com.artemchep.keyguard.feature.search.search.mapSearch
import com.artemchep.keyguard.feature.search.search.mapShape
import com.artemchep.keyguard.feature.search.search.searchFilter
import com.artemchep.keyguard.feature.search.search.searchQueryHandle
import com.artemchep.keyguard.ui.Avatar
import com.artemchep.keyguard.ui.icons.UserIcon
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

private class LocalizationContributorListUiException(
    msg: String,
    cause: Throwable,
) : RuntimeException(msg, cause)

@Composable
fun produceLocalizationContributorsListState(
) = with(localDI().direct) {
    produceJustDeleteMeServiceListState(
        localizationContributorsService = instance(),
    )
}

@Composable
fun produceJustDeleteMeServiceListState(
    localizationContributorsService: LocalizationContributorsService,
): Loadable<LocalizationContributorsListState> = produceScreenState(
    key = "localization_contributors_list",
    initial = Loadable.Loading,
    args = arrayOf(),
) {
    val queryHandle = searchQueryHandle("query")
    searchQueryShortcuts(queryHandle)
    val queryFlow = searchFilter(queryHandle) { model, revision ->
        LocalizationContributorsListState.Filter(
            revision = revision,
            query = model,
        )
    }

    fun onClick(model: LocalizationContributor) {
        val url = "https://crowdin.com/profile/${model.user.username}"
        val intent = NavigationIntent.NavigateToBrowser(url)
        navigate(intent)
    }

    fun List<LocalizationContributor>.toItems(): List<LocalizationContributorsListState.Item> {
        val nameCollisions = mutableMapOf<String, Int>()
        return this
            .mapIndexed { index, contributor ->
                val key = kotlin.run {
                    val newNameCollisionCounter = nameCollisions
                        .getOrDefault(contributor.user.username, 0) + 1
                    nameCollisions[contributor.user.username] =
                        newNameCollisionCounter
                    contributor.user.username + ":" + newNameCollisionCounter
                }
                val score = contributor.translated
                LocalizationContributorsListState.Item(
                    key = key,
                    icon = {
                        Avatar {
                            UserIcon(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(3.dp)
                                    .clip(CircleShape),
                                pictureUrl = contributor.user.avatarUrl,
                            )
                        }
                    },
                    name = AnnotatedString(contributor.user.fullName),
                    score = score,
                    index = index,
                    data = contributor,
                    onClick = ::onClick
                        .partially1(contributor),
                )
            }
    }

    val itemsFlow = localizationContributorsService.get()
        .asFlow()
        .map { apps ->
            apps
                .toItems()
                // Index for the search.
                .map { item ->
                    IndexedModel(
                        model = item,
                        indexedText = IndexedText.invoke(item.name.text),
                    )
                }
        }
        .mapSearch(
            handle = queryHandle,
        ) { item, result ->
            // Replace the origin text with the one with
            // search decor applied to it.
            item.copy(name = result.highlightedText)
        }
        .mapShape()
    val contentFlow = itemsFlow
        .crashlyticsAttempt { e ->
            val msg = "Failed to get the contributors list!"
            LocalizationContributorListUiException(
                msg = msg,
                cause = e,
            )
        }
        .map { result ->
            val contentOrException = result
                .map { (items, revision) ->
                    LocalizationContributorsListState.Content(
                        revision = revision,
                        items = items,
                    )
                }
            Loadable.Ok(contentOrException)
        }
    contentFlow
        .map { content ->
            val state = LocalizationContributorsListState(
                filter = queryFlow,
                content = content,
            )
            Loadable.Ok(state)
        }
}
