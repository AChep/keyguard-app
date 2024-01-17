package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreditCardOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artemchep.keyguard.feature.auth.common.VisibilityToggle
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatDropdown
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.monoFontFamily
import dev.icerock.moko.resources.compose.stringResource
import kotlin.math.roundToInt

const val ObscureChar = '•'

val ObscureCharBlock = "".padStart(6, ObscureChar)

val FormatCardGroupLength = 4

fun obscurePassword(password: String): String {
    if (password.length < 2) return "".padStart(8, ObscureChar)
    return password.take(1) + ObscureCharBlock + password.takeLast(1)
}

fun formatCardNumber(
    cardNumber: String,
) = buildString {
    var count = 0
    var shouldAddSpace = false
    cardNumber.forEach { char ->
        if (char.isLetterOrDigit()) {
            count++
        }
        if (shouldAddSpace) {
            append(' ')
            shouldAddSpace = false
        }
        append(char)
        shouldAddSpace = count.rem(FormatCardGroupLength) == 0
    }
}

fun obscureCardNumber(
    cardNumber: String,
): String {
    val cardNumberFormatted = formatCardNumber(cardNumber)
    val obscureToExclusive = run {
        var i = 0
        var count = 0
        for (j in (cardNumberFormatted.length - 1) downTo 0) {
            // Increment the counter of meaningful
            // characters.
            if (cardNumberFormatted[j].isLetterOrDigit()) {
                count++
            }
            if (count == FormatCardGroupLength) {
                i = j
                break
            }
        }
        i
    }
    val sb = StringBuilder()
    // Form a new card number that is semi-obscure.
    cardNumberFormatted.forEachIndexed { i, char ->
        val finalChar = if (
            i < obscureToExclusive && char.isLetterOrDigit()
        ) {
            ObscureChar
        } else {
            char
        }
        sb.append(finalChar)
    }
    return sb.toString()
}

@Composable
fun VaultViewCardItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Card,
) {
    val cardNumber = item.data.number

    val updatedVerify by rememberUpdatedState(item.verify)
    val visibilityState = remember(
        item.concealFields,
    ) { mutableStateOf(!item.concealFields) }
    FlatDropdown(
        modifier = modifier,
        elevation = item.elevation,
        content = {
            val brand = item.data.brand
                ?: item.data.creditCardType?.name
            if (brand != null) {
                Text(
                    text = brand,
                    color = MaterialTheme.colorScheme.secondary
                        .combineAlpha(MediumEmphasisAlpha),
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(
                    modifier = Modifier
                        .height(8.dp),
                )
            }
            Row {
                if (cardNumber != null) {
                    val progress by animateFloatAsState(
                        targetValue = if (visibilityState.value) {
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
                    val obscureFrom by derivedStateOf {
                        val indexFloat = cardNumberFormatted.length.toFloat() * progress
                        indexFloat.roundToInt()
                    }
                    val finalCardNumber = remember(
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
                    Text(
                        text = finalCardNumber,
                        style = MaterialTheme.typography.titleLarge,
                        fontSize = 18.sp,
                        fontFamily = monoFontFamily,
                    )
                } else {
                    val contentColor = LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CreditCardOff,
                            tint = contentColor,
                            contentDescription = null,
                        )
                        Spacer(
                            modifier = Modifier
                                .width(8.dp),
                        )
                        Text(
                            text = stringResource(Res.strings.card_number_empty_label),
                            color = contentColor,
                            style = MaterialTheme.typography.titleLarge,
                            fontSize = 18.sp,
                            fontFamily = monoFontFamily,
                        )
                    }
                }
            }
            if (
                item.data.fromMonth != null ||
                item.data.fromYear != null ||
                item.data.expMonth != null ||
                item.data.expYear != null
            ) {
                Spacer(
                    modifier = Modifier
                        .height(16.dp),
                )
                Row {
                    DateLabel(
                        label = stringResource(Res.strings.card_valid_from),
                        month = item.data.fromMonth,
                        year = item.data.fromYear,
                    )
                    DateLabel(
                        label = stringResource(Res.strings.card_valid_to),
                        month = item.data.expMonth,
                        year = item.data.expYear,
                    )
                }
            }
            val cardholderName = item.data.cardholderName
            if (cardholderName != null) {
                Spacer(
                    modifier = Modifier
                        .height(8.dp),
                )
                Text(
                    text = cardholderName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = monoFontFamily,
                )
            }
        },
        trailing = {
            if (item.concealFields && cardNumber != null) {
                VisibilityToggle(
                    visible = visibilityState.value,
                    onVisibleChange = { shouldBeConcealed ->
                        val verify = updatedVerify
                        if (
                            verify != null &&
                            shouldBeConcealed
                        ) {
                            verify.invoke {
                                visibilityState.value = true
                            }
                            return@VisibilityToggle
                        }

                        visibilityState.value = shouldBeConcealed
                    },
                )
            }
            val onCopyAction = remember(item.dropdown) {
                item.dropdown
                    .firstNotNullOfOrNull {
                        val action = it as? FlatItemAction
                        action?.takeIf { it.type == FlatItemAction.Type.COPY }
                    }
            }
            if (onCopyAction != null) {
                val onCopy = onCopyAction.onClick
                IconButton(
                    enabled = onCopy != null,
                    onClick = {
                        onCopy?.invoke()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                    )
                }
            }
        },
        dropdown = item.dropdown,
    )
}

@Composable
private fun RowScope.DateLabel(
    label: String,
    month: String?,
    year: String?,
) {
    if (
        month == null &&
        year == null
    ) {
        return
    }

    val monthFormatted = month ?: "mm"
    val yearFormatted = year ?: "yyyy"
    DateLabelContent(
        text = label,
        date = "$monthFormatted/$yearFormatted",
    )
    Spacer(modifier = Modifier.width(24.dp))
}

@Composable
private fun DateLabelContent(
    modifier: Modifier = Modifier,
    text: String,
    date: String,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = text,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            text = date,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = monoFontFamily,
        )
    }
}
