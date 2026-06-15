package com.artemchep.keyguard.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

@Composable
fun RequestLazyListScrollOnRevision(
    listState: LazyListState,
    revision: Any?,
    index: Int = 0,
    offset: Int = 0,
    enabled: Boolean = true,
) {
    val lastRevision = remember(listState) {
        RevisionState(RevisionNotApplied)
    }
    SideEffect {
        if (!enabled) {
            lastRevision.value = RevisionNotApplied
            return@SideEffect
        }

        if (lastRevision.value != revision) {
            lastRevision.value = revision
            listState.requestScrollToItem(index, offset)
        }
    }
}

private class RevisionState(
    var value: Any?,
)

private object RevisionNotApplied
