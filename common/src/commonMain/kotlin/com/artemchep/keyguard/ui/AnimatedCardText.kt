package com.artemchep.keyguard.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.artemchep.keyguard.feature.home.vault.component.ObscureChar
import com.artemchep.keyguard.feature.home.vault.component.formatCardNumber
import kotlin.math.roundToInt

@Composable
fun animatedCardNumberText(
    visible: Boolean,
    cardNumber: String,
): String {
    val progress by animateFloatAsState(
        targetValue = if (visible) {
            1f
        } else {
            0f
        },
    )

    val cardNumberFormatted = remember(cardNumber) {
        formatCardNumber(cardNumber)
    }

    val obscureToExclusive = remember(cardNumberFormatted) {
        var i = 0
        var count = 0
        for (j in (cardNumberFormatted.length - 1) downTo 0) {
            // Increment the counter of meaningful
            // characters.
            if (cardNumberFormatted[j].isLetterOrDigit()) {
                count++
            }
            if (count == 4) {
                i = j
                break
            }
        }
        i
    }
    val obscureFrom by remember(cardNumberFormatted) {
        derivedStateOf {
            val indexFloat = cardNumberFormatted.length.toFloat() * progress
            indexFloat.roundToInt()
        }
    }
    return remember(
        obscureFrom,
        obscureToExclusive,
        cardNumberFormatted,
    ) {
        val sb = StringBuilder()
        // Form a new card number that is semi-obscure.
        cardNumberFormatted.forEachIndexed { i, char ->
            val finalChar = if (
                i in obscureFrom until obscureToExclusive && char.isLetterOrDigit()
            ) {
                ObscureChar
            } else {
                char
            }
            sb.append(finalChar)
        }
        sb.toString()
    }
}
