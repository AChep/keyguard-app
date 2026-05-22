package com.artemchep.keyguard.feature.attachmentpreview

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString

@Immutable
class AttachmentPreviewLineIndex private constructor(
    private val starts: IntArray,
    private val ends: IntArray,
    val maxLineLength: Int,
) {
    val size: Int
        get() = starts.size

    fun lineLengthAt(
        index: Int,
    ): Int = ends[index] - starts[index]

    fun lineAt(
        text: AnnotatedString,
        index: Int,
    ): AnnotatedString = text.subSequence(
        startIndex = starts[index],
        endIndex = ends[index],
    )

    fun lineAt(
        text: String,
        index: Int,
    ): String = text.substring(
        startIndex = starts[index],
        endIndex = ends[index],
    )

    companion object {
        fun of(text: String): AttachmentPreviewLineIndex {
            val lineCount = text.count { it == '\n' } + 1
            val starts = IntArray(lineCount)
            val ends = IntArray(lineCount)

            var lineIndex = 0
            var lineStart = 0
            var maxLineLength = 0
            text.forEachIndexed { index, char ->
                if (char == '\n') {
                    val lineEnd = if (index > lineStart && text[index - 1] == '\r') {
                        index - 1
                    } else {
                        index
                    }
                    starts[lineIndex] = lineStart
                    ends[lineIndex] = lineEnd
                    maxLineLength = maxOf(
                        maxLineLength,
                        lineEnd - lineStart,
                    )
                    lineIndex += 1
                    lineStart = index + 1
                }
            }

            starts[lineIndex] = lineStart
            ends[lineIndex] = text.length
            maxLineLength = maxOf(
                maxLineLength,
                text.length - lineStart,
            )

            return AttachmentPreviewLineIndex(
                starts = starts,
                ends = ends,
                maxLineLength = maxLineLength,
            )
        }
    }
}
