package com.artemchep.keyguard.wear.feature.vault.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreditCardOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.artemchep.keyguard.feature.auth.common.VisibilityToggle
import com.artemchep.keyguard.feature.home.vault.component.rememberVisibilityState
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.home.vault.model.Visibility
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.card_number_empty_label
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.animatedCardNumberText
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.monoFontFamily
import com.artemchep.keyguard.wear.ui.WearListCard
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

@Composable
fun WearVaultViewCardItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Card,
    transformation: SurfaceTransformation? = null,
) {
    val cardNumber = item.data.number

    val visibilityConfig = item.visibility
    val visibilityState = rememberVisibilityState(
        visibilityConfig,
    )

    val updatedVisibilityConfig by rememberUpdatedState(visibilityConfig)

    val title = item.data.brand
        ?: item.data.creditCardType?.name
    WearListCard(
        modifier = modifier
            .fillMaxWidth(),
        title = if (title != null) {
            // composable
            {
                Text(
                    text = title,
                )
            }
        } else {
            null
        },
        text = {
            Row {
                if (cardNumber != null) {
                    val finalCardNumber = animatedCardNumberText(
                        visible = visibilityState.value.value,
                        cardNumber = cardNumber,
                    )
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
                            text = stringResource(Res.string.card_number_empty_label),
                            color = contentColor,
                            style = MaterialTheme.typography.titleLarge,
                            fontSize = 18.sp,
                            fontFamily = monoFontFamily,
                        )
                    }
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
            if (visibilityConfig.concealed && !visibilityConfig.hidden) {
                val visible = visibilityState.value.value
                VisibilityToggle(
                    visible = visible,
                    onVisibleChange = { possibleNewValue ->
                        updatedVisibilityConfig.transformUserEvent(possibleNewValue) { newValue ->
                            visibilityState.value = Visibility.Event(
                                value = newValue,
                                timestamp = Clock.System.now(),
                            )
                        }
                    },
                )
            }
        },
        transformation = transformation,
    )
}
