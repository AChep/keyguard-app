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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import arrow.core.getOrElse
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.model.TotpCode
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.ui.AhContainer
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.FlatDropdown
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.icons.KeyguardTwoFa
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.monoFontFamily
import com.artemchep.keyguard.ui.totp.formatCode2
import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import org.kodein.di.compose.rememberInstance
import kotlin.math.roundToInt

private sealed interface VaultViewTotpItemBadgeState {
    data object Loading : VaultViewTotpItemBadgeState

    data class Success(
        val codes: PersistentList<List<String>>,
        val codeRaw: String,
        val counter: Counter,
    ) : VaultViewTotpItemBadgeState {
        sealed interface Counter {
            val text: String
        }

        data class TimeBasedCounter(
            val time: String,
            val progress: Float,
        ) : Counter {
            override val text: String
                get() = time
        }

        data class IncrementBasedCounter(
            val counter: String,
        ) : Counter {
            override val text: String
                get() = counter
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VaultViewTotpItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Totp,
) {
    val state by item.localStateFlow.collectAsState()
    val dropdown = state.dropdown
    FlatDropdown(
        modifier = modifier,
        elevation = item.elevation,
        content = {
            FlatItemTextContent2(
                title = {
                    Text(item.title)
                },
                text = {
                    FlowRow {
                        VaultViewTotpCodeContent(
                            totp = item.totp,
                            codes = state.codes,
                        )
                    }
                },
            )
        },
        leading = {
            IconBox(main = Icons.Outlined.KeyguardTwoFa)
        },
        trailing = {
            VaultViewTotpBadge(
                totpToken = item.totp,
            )

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
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                    )
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
    color: Color = Color.Unspecified,
    copyText: CopyText,
    totpToken: TotpToken,
) {
    val state by produceTotpCode(
        totpToken = totpToken,
    )

    Surface(
        modifier = modifier
            .padding(top = 8.dp),
        color = color.takeIf { it.isSpecified }
            ?: MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .clickable(enabled = state is VaultViewTotpItemBadgeState.Success) {
                    val code = (state as? VaultViewTotpItemBadgeState.Success)
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
                ),
        ) {
            VaultViewTotpCodeContent(
                totp = totpToken,
                codes = (state as? VaultViewTotpItemBadgeState.Success?)
                    ?.codes,
            )

            Spacer(
                modifier = Modifier
                    .width(16.dp),
            )

            VaultViewTotpRemainderBadge(
                state = state,
            )
        }
    }
}

@Composable
private fun RowScope.VaultViewTotpCodeContent(
    totp: TotpToken,
    codes: PersistentList<List<String>>?,
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
                            val enter = slideInVertically {
                                if (currentFavorite) -it else it
                            } + scaleIn()
                            val exit = slideOutVertically {
                                if (currentFavorite) it else -it
                            } + scaleOut()
                            enter togetherWith exit
                        },
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
    state: VaultViewTotpItemBadgeState?,
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
    state: VaultViewTotpItemBadgeState,
) {
    val score = when (state) {
        is VaultViewTotpItemBadgeState.Loading -> null

        is VaultViewTotpItemBadgeState.Success -> when (val counter = state.counter) {
            is VaultViewTotpItemBadgeState.Success.TimeBasedCounter -> counter.progress.coerceIn(
                0f,
                1f,
            )

            is VaultViewTotpItemBadgeState.Success.IncrementBasedCounter -> null
        }
    }
    AhContainer(
        score = score
            ?: 1f,
    ) {
        val time = (state as? VaultViewTotpItemBadgeState.Success)?.counter?.text.orEmpty()
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
                progress = progress,
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
): State<VaultViewTotpItemBadgeState?> {
    val getTotpCode by rememberInstance<GetTotpCode>()
    return remember(totpToken) {
        getTotpCode(totpToken)
            .flatMapLatest {
                // Format the totp code, so it's easier to
                // read for the user.
                val codes = it.formatCode2()
                when (val counter = it.counter) {
                    is TotpCode.TimeBasedCounter -> flow<VaultViewTotpItemBadgeState.Success> {
                        while (true) {
                            val now = Clock.System.now()
                            val totalDuration = counter.duration
                            val remainingDuration = counter.expiration - now
                            val elapsedDuration = totalDuration - remainingDuration

                            val time = remainingDuration
                                .inWholeMilliseconds
                                .toFloat()
                                .div(1000F)
                                .roundToInt()
                                .coerceAtLeast(0)
                                .toString()
                            val progress =
                                1f - elapsedDuration.inWholeSeconds.toFloat() / totalDuration.inWholeSeconds.toFloat()
                            val c = VaultViewTotpItemBadgeState.Success.TimeBasedCounter(
                                time = time,
                                progress = progress,
                            )
                            val s = VaultViewTotpItemBadgeState.Success(
                                codes = codes,
                                codeRaw = it.code,
                                counter = c,
                            )
                            emit(s)

                            val dt = remainingDuration
                                .inWholeMilliseconds
                                .rem(1000L)
                            delay(dt + 1L)
                        }
                    }

                    is TotpCode.IncrementBasedCounter -> {
                        val c = VaultViewTotpItemBadgeState.Success.IncrementBasedCounter(
                            counter = counter.counter.toString(),
                        )
                        val s = VaultViewTotpItemBadgeState.Success(
                            codes = codes,
                            codeRaw = it.code,
                            counter = c,
                        )
                        flowOf(s)
                    }
                }

            }
            .attempt()
            .map { either ->
                either
                    .getOrElse {
                        null
                    }
            }
    }.collectAsState(initial = VaultViewTotpItemBadgeState.Loading)
}
