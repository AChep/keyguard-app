package com.artemchep.keyguard.ui.totp

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import com.artemchep.keyguard.common.model.TotpCode
import com.artemchep.keyguard.common.util.asCodePointsSequence
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlin.time.Instant

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

fun TotpCode.formatCode2(): PersistentList<PersistentList<String>> = code
    .windowed(
        size = 3,
        step = 3,
        partialWindows = true,
    )
    .map {
        it.asCodePointsSequence()
            .toPersistentList()
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

fun TotpCode.TimeBasedCounter.remainingProgressAt(now: Instant): Float {
    if (!duration.isPositive()) {
        return 0f
    }

    val remainingDuration = expiration - now
    val elapsedDuration = duration - remainingDuration
    val progress = (1.0 - elapsedDuration / duration).toFloat()
    return progress
        .takeIf { it.isFinite() }
        ?.coerceIn(0f, 1f)
        ?: 0f
}
