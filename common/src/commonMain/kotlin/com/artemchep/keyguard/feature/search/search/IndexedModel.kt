package com.artemchep.keyguard.feature.search.search

import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.common.io.parallelSearch
import com.artemchep.keyguard.feature.home.vault.search.IndexedText
import com.artemchep.keyguard.feature.home.vault.search.find
import kotlin.time.measureTimedValue

class IndexedModel<T>(
    val model: T,
    val indexedText: IndexedText,
)

data class FilteredModel<T>(
    val model: T,
    val result: IndexedText.FindResult,
)

@OptIn(kotlin.time.ExperimentalTime::class)
suspend fun <T> List<IndexedModel<T>>.search(
    query: IndexedText,
    highlightBackgroundColor: Color,
    highlightContentColor: Color,
    transform: (T, IndexedText.FindResult) -> T,
) = kotlin.run {
    // Fast path if there's nothing to search from.
    if (isEmpty()) {
        return@run this
            .map {
                it.model
            }
    }

    val timedValue = measureTimedValue {
        parallelSearch {
            val r = it.indexedText.find(
                query = query,
                colorBackground = highlightBackgroundColor,
                colorContent = highlightContentColor,
            ) ?: return@parallelSearch null
            FilteredModel(
                model = it.model,
                result = r,
            )
        }
            .sortedWith(
                compareBy(
                    { -it.result.score },
                ),
            )
            .map {
                // Replace the origin text with the one with
                // search decor applied to it.
                transform(it.model, it.result)
            }
    }
    timedValue.value
}