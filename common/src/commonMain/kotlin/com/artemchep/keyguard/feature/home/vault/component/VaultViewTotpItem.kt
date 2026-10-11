package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.shortcut.ShortcutTooltip
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.monoFontFamily
import com.artemchep.keyguard.ui.totp.TOTP_CODE_GROUP_SIZE
import com.artemchep.keyguard.ui.totp.TotpCodeState
import com.artemchep.keyguard.ui.totp.TotpRemainderBadge
import com.artemchep.keyguard.ui.totp.produceTotpCode
import kotlinx.collections.immutable.PersistentList
import org.jetbrains.compose.resources.stringResource

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

    TotpRemainderBadge(
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

    val tintColor = MaterialTheme.colorScheme
        .surfaceColorAtElevationSemi(1.dp)
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(tintColor)
            .clickable(enabled = codes != null) {
                val code = (state.value as? TotpCodeState.Success)
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
            return@Row
        }

        VaultViewTotpCodeContent(
            totp = totpToken,
            codes = codes,
        )
        ExpandedIfNotEmptyForRow(
            Unit.takeIf { showTimeout },
        ) {
            Row {
                Spacer(
                    modifier = Modifier
                        .width(Dimens.buttonIconPadding),
                )
                TotpRemainderBadge(
                    state = state.value,
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
