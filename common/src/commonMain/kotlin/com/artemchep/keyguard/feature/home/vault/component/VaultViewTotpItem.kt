package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.common.usecase.GetTotpCodeWithOffset
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.AhContainer
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.shortcut.ShortcutTooltip
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.monoFontFamily
import com.artemchep.keyguard.ui.totp.TotpCodeState
import com.artemchep.keyguard.ui.totp.totpCodeFlow
import kotlinx.collections.immutable.PersistentList
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.rememberInstance

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VaultViewTotpItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Totp,
) {
    val state by item.localStateFlow.collectAsState()
    val dropdown = state.dropdown
    FlatDropdownSimpleExpressive(
        modifier = modifier,
        elevation = item.elevation,
        shapeState = item.shapeState,
        content = {
            FlatItemTextContent2(
                title = {
                    Text(item.title)
                },
            )
            Spacer(
                modifier = Modifier
                    .height(8.dp),
            )
            FlowRow(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VaultViewTotpBadge2(
                    copyText = item.copy,
                    totpToken = item.totp,
                )
                ProvideTextStyle(MaterialTheme.typography.titleSmall) {
                    VaultViewTotpBadge2(
                        modifier = Modifier
                            .heightIn(min = 32.dp),
                        contentModifier = Modifier
                            .alpha(MediumEmphasisAlpha),
                        copyText = item.copy,
                        totpToken = item.totp,
                        showTimeout = false,
                        offset = 1,
                    )
                }
            }
        },
        trailing = {
            val onCopyAction = remember(dropdown) {
                dropdown
                    .firstNotNullOfOrNull {
                        val action = it as? FlatItemAction
                        action?.takeIf { it.type == FlatItemAction.Type.COPY }
                    }
            }
            if (onCopyAction != null) {
                val onCopy = onCopyAction.onClick
                Spacer(Modifier.width(16.dp))
                IconButton(
                    enabled = onCopy != null,
                    onClick = {
                        onCopy?.invoke()
                    },
                ) {
                    ShortcutTooltip(
                        valueOrNull = item.shortcut,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = null,
                        )
                    }
                }
            }
        },
        dropdown = dropdown,
    )
}

@Composable
fun RowScope.VaultViewTotpBadge(
    totpToken: TotpToken,
) {
    val state by produceTotpCode(
        totpToken = totpToken,
    )

    VaultViewTotpRemainderBadge(
        state = state,
    )
}

@Composable
fun VaultViewTotpBadge2(
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    copyText: CopyText,
    totpToken: TotpToken,
    showTimeout: Boolean = true,
    offset: Int = 0,
) {
    val state by produceTotpCode(
        totpToken = totpToken,
        offset = offset,
    )

    val tintColor = MaterialTheme.colorScheme
        .surfaceColorAtElevationSemi(1.dp)
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(tintColor)
            .clickable(enabled = state is TotpCodeState.Success) {
                val code = (state as? TotpCodeState.Success)
                    ?.codeRaw ?: return@clickable
                copyText.copy(
                    text = code,
                    hidden = false,
                    type = CopyText.Type.OTP,
                )
            }
            .padding(
                start = 8.dp,
                end = 4.dp,
                top = 4.dp,
                bottom = 4.dp,
            )
            .then(contentModifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Draw an explicit error state. This should only happen if the
        // TOTP token is invalid.
        if (state is TotpCodeState.Error) {
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
            return@Row
        }

        VaultViewTotpCodeContent(
            totp = totpToken,
            codes = (state as? TotpCodeState.Success?)
                ?.codes,
        )
        ExpandedIfNotEmptyForRow(
            Unit.takeIf { showTimeout },
        ) {
            Row {
                Spacer(
                    modifier = Modifier
                        .width(Dimens.buttonIconPadding),
                )
                VaultViewTotpRemainderBadge(
                    state = state,
                )
            }
        }
        ExpandedIfNotEmptyForRow(
            Unit.takeIf { !showTimeout },
        ) {
            Spacer(
                modifier = Modifier
                    .width(4.dp),
            )
        }
    }
}

@Composable
private fun RowScope.VaultViewTotpCodeContent(
    totp: TotpToken,
    codes: PersistentList<PersistentList<String>>?,
) {
    val symbolColor = LocalContentColor.current
        .combineAlpha(DisabledEmphasisAlpha)
    if (codes == null) {
        repeat(totp.digits) { index ->
            if (index != 0 && index.rem(3) == 0) {
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
    val motionScheme = MaterialTheme.motionScheme
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
                    AnimatedContent(
                        modifier = Modifier
                            .align(Alignment.CenterVertically),
                        targetState = cpText,
                        transitionSpec = {
                            val from = this.initialState
                            if (from == " ") {
                                return@AnimatedContent EnterTransition.None togetherWith
                                        ExitTransition.None
                            }

                            val to = this.targetState
                            val currentFavorite = from > to
                            val slideSpec = motionScheme.fastSpatialSpec<IntOffset>()
                            val scaleSpec = motionScheme.fastSpatialSpec<Float>()
                            val enter = slideInVertically(
                                animationSpec = slideSpec,
                            ) {
                                if (currentFavorite) -it else it
                            } + scaleIn(
                                animationSpec = scaleSpec,
                            )
                            val exit = slideOutVertically(
                                animationSpec = slideSpec,
                            ) {
                                if (currentFavorite) it else -it
                            } + scaleOut(
                                animationSpec = scaleSpec,
                            )
                            enter togetherWith exit
                        },
                        label = "TotpCode",
                    ) { text ->
                        Text(
                            text = text,
                            fontFamily = monoFontFamily,
                        )
                    }
                }
            }
        }
    }
}

//
// Remainder
//

@Composable
private fun VaultViewTotpRemainderBadge(
    state: TotpCodeState?,
) {
    ExpandedIfNotEmptyForRow(
        valueOrNull = state,
    ) {
        VaultViewTotpRemainderBadgeLayout(
            state = it,
        )
    }
}

@Composable
private fun VaultViewTotpRemainderBadgeLayout(
    state: TotpCodeState,
) {
    val score = when (state) {
        is TotpCodeState.Loading -> null
        is TotpCodeState.Error -> null

        is TotpCodeState.Success -> when (val counter = state.counter) {
            is TotpCodeState.Success.TimeBasedCounter -> counter.progress.coerceIn(
                0f,
                1f,
            )

            is TotpCodeState.Success.IncrementBasedCounter -> null
        }
    }
    AhContainer(
        score = score
            ?: 1f,
    ) {
        val time = (state as? TotpCodeState.Success)?.counter?.text.orEmpty()
        VaultViewTotpRemainderBadgeContent(
            progress = it.takeIf { score != null },
            text = time,
        )
    }
}

@Composable
private fun RowScope.VaultViewTotpRemainderBadgeContent(
    progress: Float?,
    text: String,
) {
    val circularProgressModifier = Modifier.size(16.dp)
    when (progress) {
        null -> {
            // Do nothing.
        }

        else -> {
            CircularProgressIndicator(
                modifier = circularProgressModifier,
                progress = { progress },
                trackColor = Color.Transparent,
                color = LocalContentColor.current,
            )
            Spacer(Modifier.width(4.dp))
        }
    }
    Spacer(Modifier.width(4.dp))
    Text(
        modifier = Modifier
            .animateContentSize(),
        text = text,
        fontFamily = monoFontFamily,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelMedium,
    )
    Spacer(Modifier.width(4.dp))
}

//
// State
//

@Composable
private fun produceTotpCode(
    totpToken: TotpToken,
    offset: Int = 0,
): State<TotpCodeState?> {
    val getTotpCode by rememberInstance<GetTotpCodeWithOffset>()
    return remember(totpToken, offset) {
        totpCodeFlow(
            getTotpCode = getTotpCode,
            totpToken = totpToken,
            offset = offset,
        )
    }.collectAsState(initial = TotpCodeState.Loading)
}
