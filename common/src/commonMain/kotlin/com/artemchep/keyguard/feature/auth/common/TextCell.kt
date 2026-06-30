package com.artemchep.keyguard.feature.auth.common

/**
 * The canonical text-field cell. The text and the revision live in a
 * single value so they can never tear apart: a user edit moves the text
 * and keeps the revision, a programmatic write (clear, prefill, insert,
 * restore) moves both.
 *
 * UI edge buffers adopt the remote text only when the revision changes —
 * echoes of the user's own keystrokes keep the revision and must never
 * overwrite the buffer.
 */
data class TextCell(
    val text: String,
    val revision: Int = 0,
)
