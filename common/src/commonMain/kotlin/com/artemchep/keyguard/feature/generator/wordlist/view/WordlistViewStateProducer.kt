package com.artemchep.keyguard.feature.generator.wordlist.view

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import arrow.core.partially1
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.EditWordlist
import com.artemchep.keyguard.common.usecase.GetWordlistPrimitive
import com.artemchep.keyguard.common.usecase.GetWordlists
import com.artemchep.keyguard.common.usecase.RemoveWordlistById
import com.artemchep.keyguard.feature.crashlytics.crashlyticsAttempt
import com.artemchep.keyguard.feature.generator.wordlist.util.WordlistUtil
import com.artemchep.keyguard.feature.home.vault.search.IndexedText
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.search.search.IndexedModel
import com.artemchep.keyguard.feature.search.search.mapSearch
import com.artemchep.keyguard.feature.search.search.searchFilter
import com.artemchep.keyguard.feature.search.search.searchQueryHandle
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.autoclose.launchAutoPopSelfHandler
import com.artemchep.keyguard.ui.buildContextItems
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

private class WordlistViewUiException(
    msg: String,
    cause: Throwable,
) : RuntimeException(msg, cause)

@Composable
fun produceWordlistViewState(
    args: WordlistViewRoute.Args,
) = with(localDI().direct) {
    produceWordlistViewState(
        args = args,
        editWordlist = instance(),
        removeWordlistById = instance(),
        getWordlists = instance(),
        getWordlistPrimitive = instance(),
    )
}

@Composable
fun produceWordlistViewState(
    args: WordlistViewRoute.Args,
    editWordlist: EditWordlist,
    removeWordlistById: RemoveWordlistById,
    getWordlists: GetWordlists,
    getWordlistPrimitive: GetWordlistPrimitive,
): Loadable<WordlistViewState> = produceScreenState(
    key = "wordlist_view",
    initial = Loadable.Loading,
    args = arrayOf(
        args,
    ),
) {
    val queryHandle = searchQueryHandle("query")
    val queryFlow = searchFilter(queryHandle) { model, revision ->
        WordlistViewState.Filter(
            revision = revision,
            query = model,
        )
    }

    val wordlistFlow = getWordlists()
        .map { wordlists ->
            val wordlist = wordlists
                .firstOrNull { it.idRaw == args.wordlistId }
            if (wordlist != null) {
                val actions = buildContextItems {
                    section {
                        this += FlatItemAction(
                            icon = Icons.Outlined.Edit,
                            title = translate(Res.strings.edit),
                            onClick = WordlistUtil::onEdit
                                .partially1(this@produceScreenState)
                                .partially1(editWordlist)
                                .partially1(wordlist),
                        )
                    }
                    section {
                        val wordlistAsItems = listOf(wordlist)
                        this += FlatItemAction(
                            icon = Icons.Outlined.Delete,
                            title = translate(Res.strings.delete),
                            onClick = WordlistUtil::onDeleteByItems
                                .partially1(this@produceScreenState)
                                .partially1(removeWordlistById)
                                .partially1(wordlistAsItems),
                        )
                    }
                }
                WordlistViewState.Wordlist(
                    wordlist = wordlist,
                    actions = actions,
                )
            } else {
                null
            }
        }
        .stateIn(screenScope)
    launchAutoPopSelfHandler(wordlistFlow)

    fun onClick(model: String) {
    }

    fun List<String>.toItems(): List<WordlistViewState.Item> {
        val nameCollisions = mutableMapOf<String, Int>()
        return this
            .map { word ->
                val key = kotlin.run {
                    val newPackageNameCollisionCounter = nameCollisions
                        .getOrDefault(word, 0) + 1
                    nameCollisions[word] =
                        newPackageNameCollisionCounter
                    word + ":" + newPackageNameCollisionCounter
                }
                WordlistViewState.Item(
                    key = key,
                    name = AnnotatedString(word),
                    onClick = ::onClick
                        .partially1(word),
                )
            }
    }

    val itemsFlow = getWordlistPrimitive(args.wordlistId)
        .map { words ->
            words
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
    val contentFlow = itemsFlow
        .crashlyticsAttempt { e ->
            val msg = "Failed to get the wordlist primitive list!"
            WordlistViewUiException(
                msg = msg,
                cause = e,
            )
        }
        .map { result ->
            val contentOrException = result
                .map { (items, revision) ->
                    WordlistViewState.Content(
                        revision = revision,
                        items = items,
                    )
                }
            Loadable.Ok(contentOrException)
        }
    contentFlow
        .map { content ->
            val state = WordlistViewState(
                wordlist = wordlistFlow,
                filter = queryFlow,
                content = content,
            )
            Loadable.Ok(state)
        }
}
