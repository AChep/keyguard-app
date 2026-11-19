package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.feature.browserextension.protocol.BrowserError
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The windows of the save requests of the extensions, each open until the
 * user closes it; one per session at a time. App-scoped.
 */
internal class BrowserSaveWindows(
    /** How long a window may take to show. */
    private val approvalTimeout: suspend () -> Duration,
) {
    private val mutableSaves = MutableStateFlow<List<BrowserSaveRequest>>(emptyList())
    val saves = mutableSaves.asStateFlow()

    /** The save requests whose window is on screen. */
    private val shownSaves = MutableStateFlow<Set<String>>(emptySet())

    /** Shows the window of the save [request]; `false` while its session has one open. */
    fun open(request: BrowserSaveRequest): Boolean = mutableSaves.addOnePerSession(request) { it.session }

    /** The window of the save request [id] is on screen. */
    fun onShown(id: String) {
        if (saves.value.any { it.id == id }) shownSaves.update { it + id }
    }

    /** The window of the save request [id] closed. */
    fun close(id: String) {
        mutableSaves.update { list -> list.filterNot { it.id == id } }
        shownSaves.update { it - id }
    }

    /**
     * Waits until the window of the save request [id] is on screen.
     *
     * @return `null` once it is, otherwise why it is not.
     */
    suspend fun awaitShown(id: String): BrowserError? {
        val shown = withTimeoutOrNull(approvalTimeout()) {
            combine(saves, shownSaves) { list, shown -> id in shown || list.none { it.id == id } }
                .first { it }
            id in shownSaves.value
        }
        return when (shown) {
            true -> null
            // The window closed before it showed.
            false -> BrowserError.USER_DENIED
            null -> {
                close(id)
                BrowserError.APPROVAL_TIMEOUT
            }
        }
    }
}

/**
 * Adds [item], unless the list holds one of the same [session][sessionOf]:
 * a session shows one window of a kind at a time.
 *
 * @return Whether it was added.
 */
internal fun <T> MutableStateFlow<List<T>>.addOnePerSession(
    item: T,
    sessionOf: (T) -> BrowserExtensionSession,
): Boolean {
    val sessionId = sessionOf(item).id
    var added = false
    update { list ->
        added = list.none { sessionOf(it).id == sessionId }
        if (added) list + item else list
    }
    return added
}
