package com.artemchep.keyguard.wear.feature.vault.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProvideTextStyle
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.error_invalid_key
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.KeyguardTwoFa
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.monoFontFamily
import com.artemchep.keyguard.ui.totp.TOTP_CODE_GROUP_SIZE
import com.artemchep.keyguard.ui.totp.TotpCodeState
import com.artemchep.keyguard.ui.totp.TotpRemainderBadge
import com.artemchep.keyguard.ui.totp.produceTotpCode
import com.artemchep.keyguard.wear.ui.ProxyMaterial3Styles
import kotlinx.collections.immutable.PersistentList
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WearVaultViewTotpItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Totp,
    transformation: SurfaceTransformation? = null,
) {
    val state by item.localStateFlow.collectAsStateWithLifecycle()
    val dropdown = state.dropdown
    Card(
        modifier = modifier
            .fillMaxWidth(),
        transformation = transformation,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .size(16.dp),
                    ) {
                        ProxyMaterial3Styles {
                            Icon(
                                imageVector = Icons.Outlined.KeyguardTwoFa,
                                contentDescription = null,
                            )
                        }
                    }
                    Spacer(
                        modifier = Modifier
                            .width(8.dp),
                    )
                    Text(
                        text = item.title,
                        color = LocalContentColor.current
                            .combineAlpha(MediumEmphasisAlpha),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                ProvideTextStyle(MaterialTheme.typography.displayMedium) {
                    VaultViewTotpBadge2(
                        totpToken = item.totp,
                    )
                }
            }
        }
    }
}

@Composable
fun VaultViewTotpBadge2(
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    totpToken: TotpToken,
    showTimeout: Boolean = true,
    offset: Int = 0,
) {
    val state = produceTotpCode(
        totpToken = totpToken,
        offset = offset,
    )
    // The state ticks every second, but the code changes once
    // per period. Only the remainder badge reads every tick.
    val isError by remember(state) {
        derivedStateOf {
            state.value is TotpCodeState.Error
        }
    }
    val codes by remember(state) {
        derivedStateOf {
            (state.value as? TotpCodeState.Success)?.codes
        }
    }
    Column(
        modifier = modifier
            .then(contentModifier),
    ) {
        VaultViewTotpCodeContent(
            totp = totpToken,
            isError = isError,
            codes = codes,
        )
        ExpandedIfNotEmptyForRow(
            Unit.takeIf { showTimeout },
        ) {
            Row {
                TotpRemainderBadge(
                    state = state.value,
                    textStyle = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun VaultViewTotpCodeContent(
    totp: TotpToken,
    isError: Boolean,
    codes: PersistentList<List<String>>?,
) {
    if (isError) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(
            modifier = Modifier
                .width(8.dp),
        )
        Text(
            text = stringResource(Res.string.error_invalid_key),
        )
        return
    }

    Row {
        VaultViewTotpCodeDigits(
            totp = totp,
            codes = codes,
        )
    }
}

@Composable
private fun RowScope.VaultViewTotpCodeDigits(
    totp: TotpToken,
    codes: PersistentList<List<String>>?,
) {
    val symbolColor = LocalContentColor.current
        .combineAlpha(DisabledEmphasisAlpha)
    if (codes == null) {
        repeat(totp.digits) { index ->
            if (index != 0 && index.rem(TOTP_CODE_GROUP_SIZE) == 0) {
                Spacer(
                    modifier = Modifier
                        .padding(
                            horizontal = 5.dp,
                        )
                        .size(3.dp),
                )
            }
            Text(
                text = " ",
                fontFamily = monoFontFamily,
            )
        }
        return
    }
    codes.forEachIndexed { index, text ->
        if (index > 0) {
            Box(
                modifier = Modifier
                    .padding(
                        horizontal = 5.dp,
                    )
                    .size(3.dp)
                    .background(symbolColor, CircleShape)
                    .align(Alignment.CenterVertically),
            )
        }
        key(index) {
            text.forEachIndexed { cpIndex, cpText ->
                key(cpIndex) {
                    VaultViewTotpCodeDigit(
                        modifier = Modifier
                            .align(Alignment.CenterVertically),
                        text = cpText,
                    )
                }
            }
        }
    }
}

@Composable
private fun VaultViewTotpCodeDigit(
    modifier: Modifier = Modifier,
    text: String,
) {
    AnimatedContent(
        modifier = modifier,
        targetState = text,
        transitionSpec = {
            val from = this.initialState
            if (from == " ") {
                return@AnimatedContent EnterTransition.None togetherWith
                        ExitTransition.None
            }

            val to = this.targetState
            val currentFavorite = from > to
            val enter = slideInVertically {
                if (currentFavorite) -it else it
            } + scaleIn()
            val exit = slideOutVertically {
                if (currentFavorite) it else -it
            } + scaleOut()
            enter togetherWith exit
        },
    ) { digit ->
        Text(
            text = digit,
            fontFamily = monoFontFamily,
        )
    }
}
