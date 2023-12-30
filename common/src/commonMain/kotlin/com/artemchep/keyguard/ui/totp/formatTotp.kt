package com.artemchep.keyguard.ui.totp

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import com.artemchep.keyguard.common.model.TotpCode
import com.artemchep.keyguard.common.util.asCodePointsSequence
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList

fun TotpCode.formatCode(): AnnotatedString = buildAnnotatedString {
    code
        .windowed(
            size = 3,
            step = 3,
            partialWindows = true,
        )
        .forEachIndexed { index, text ->
            if (index != 0) {
                append(" ")
            }
            append(text)
        }
}

fun TotpCode.formatCode2(): PersistentList<List<String>> = code
    .windowed(
        size = 3,
        step = 3,
        partialWindows = true,
    )
    .map {
        it.asCodePointsSequence()
            .toList()
    }
    .toPersistentList()

fun TotpCode.formatCodeStr(): String = buildString {
    code
        .windowed(
            size = 3,
            step = 3,
            partialWindows = true,
        )
        .forEachIndexed { index, text ->
            if (index != 0) {
                append(" ")
            }
            append(text)
        }
}
