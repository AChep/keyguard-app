package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.feature.localization.TextHolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DragOutExceptionTest {
    private val title = TextHolder.Value("Could not drag")

    @Test
    fun `text explains a plain cause by its message`() {
        val failure = DragOutException(IllegalStateException("Content offer has expired"), title)
        assertEquals(TextHolder.Value("Content offer has expired"), failure.text)
    }

    @Test
    fun `text uses a readable cause's title`() {
        val cause = object : RuntimeException(), Readable {
            override val title = TextHolder.Value("No connection")
        }
        assertEquals(TextHolder.Value("No connection"), DragOutException(cause, title).text)
    }

    @Test
    fun `text is absent when the cause has no message`() {
        assertNull(DragOutException(IllegalStateException(), title).text)
    }
}
