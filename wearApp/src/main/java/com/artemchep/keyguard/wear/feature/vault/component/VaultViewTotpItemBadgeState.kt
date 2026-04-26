package com.artemchep.keyguard.wear.feature.vault.component

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProvideTextStyle
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.artemchep.keyguard.common.model.TotpCode
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.common.usecase.GetTotpCodeWithOffset
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.error_invalid_key
import com.artemchep.keyguard.ui.AhContainer
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.KeyguardTwoFa
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.monoFontFamily
import com.artemchep.keyguard.ui.totp.formatCode2
import com.artemchep.keyguard.wear.ui.ProxyMaterial3Styles
import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.rememberInstance
import kotlin.math.roundToInt
import kotlin.time.Clock

private sealed interface VaultViewTotpCodeState {
    data object Loading : VaultViewTotpCodeState

    data object Error : VaultViewTotpCodeState

    data class Success(
        val codes: PersistentList<List<String>>,
        val codeRaw: String,
    ) : VaultViewTotpCodeState
}

private sealed interface VaultViewTotpCounterState {
    data object Loading : VaultViewTotpCounterState

    data object Error : VaultViewTotpCounterState

    data class Success(
        val counter: Counter,
    ) : VaultViewTotpCounterState
}

private sealed interface Counter {
    val text: String

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

private data class VaultViewTotpState(
    val code: State<VaultViewTotpCodeState?>,
    val counter: State<VaultViewTotpCounterState?>,
)

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
                        copyText = item.copy,
                        totpToken = item.totp,
                    )
                }
            }
        }
    }
}

@Composable
fun RowScope.VaultViewTotpBadge(
    totpToken: TotpToken,
) {
    val state = rememberTotpCodeState(
        totpToken = totpToken,
    )

    VaultViewTotpRemainderBadge(
        state = state.counter,
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
    val state = rememberTotpCodeState(
        totpToken = totpToken,
        offset = offset,
    )
    Column(
        modifier = modifier
            .then(contentModifier),
    ) {
        VaultViewTotpCodeContent(
            totp = totpToken,
            state = state.code,
        )
        ExpandedIfNotEmptyForRow(
            Unit.takeIf { showTimeout },
        ) {
            Row {
                VaultViewTotpRemainderBadge(
                    state = state.counter,
                )
            }
        }
    }
}

@Composable
private fun VaultViewTotpCodeContent(
    totp: TotpToken,
    state: State<VaultViewTotpCodeState?>,
) {
    when (val value = state.value) {
        is VaultViewTotpCodeState.Error -> {
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
        }

        is VaultViewTotpCodeState.Loading,
        null,
        -> {
            Row {
                VaultViewTotpCodeDigits(
                    totp = totp,
                    codes = null,
                )
            }
        }

        is VaultViewTotpCodeState.Success -> {
            Row {
                VaultViewTotpCodeDigits(
                    totp = totp,
                    codes = value.codes,
                )
            }
        }
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
    state: State<VaultViewTotpCounterState?>,
) {
    ExpandedIfNotEmptyForRow(
        valueOrNull = state.value,
    ) {
        VaultViewTotpRemainderBadgeLayout(
            state = it,
        )
    }
}

@Composable
private fun VaultViewTotpRemainderBadgeLayout(
    state: VaultViewTotpCounterState,
) {
    val score = when (state) {
        is VaultViewTotpCounterState.Loading -> null
        is VaultViewTotpCounterState.Error -> null

        is VaultViewTotpCounterState.Success -> when (val counter = state.counter) {
            is Counter.TimeBasedCounter -> counter.progress.coerceIn(
                0f,
                1f,
            )

            is Counter.IncrementBasedCounter -> null
        }
    }
    AhContainer(
        score = score
            ?: 1f,
    ) {
        val time = (state as? VaultViewTotpCounterState.Success)?.counter?.text.orEmpty()
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
private fun rememberTotpCodeState(
    totpToken: TotpToken,
    offset: Int = 0,
): VaultViewTotpState {
    val getTotpCode by rememberInstance<GetTotpCodeWithOffset>()
    val codeState = remember(totpToken, offset) {
        mutableStateOf<VaultViewTotpCodeState?>(VaultViewTotpCodeState.Loading)
    }
    val counterState = remember(totpToken, offset) {
        mutableStateOf<VaultViewTotpCounterState?>(VaultViewTotpCounterState.Loading)
    }
    LaunchedEffect(getTotpCode, totpToken, offset) {
        getTotpCode(totpToken, offset)
            .collectLatest { result ->
                val totpCode = result.getOrNull()
                if (totpCode == null) {
                    codeState.value = VaultViewTotpCodeState.Error
                    counterState.value = VaultViewTotpCounterState.Error
                    return@collectLatest
                }

                codeState.value = VaultViewTotpCodeState.Success(
                    codes = totpCode.formatCode2(),
                    codeRaw = totpCode.code,
                )
                when (val counter = totpCode.counter) {
                    is TotpCode.TimeBasedCounter -> {
                        while (true) {
                            counterState.value = VaultViewTotpCounterState.Success(
                                counter = counter.toCounterState(),
                            )

                            val remainingDuration = counter.expiration - Clock.System.now()
                            val dt = remainingDuration
                                .inWholeMilliseconds
                                .rem(1000L)
                                .coerceAtLeast(0L)
                            delay(dt + 1L)
                        }
                    }

                    is TotpCode.IncrementBasedCounter -> {
                        counterState.value = VaultViewTotpCounterState.Success(
                            counter = Counter.IncrementBasedCounter(
                                counter = counter.counter.toString(),
                            ),
                        )
                    }
                }
            }
    }
    return remember(codeState, counterState) {
        VaultViewTotpState(
            code = codeState,
            counter = counterState,
        )
    }
}

private fun TotpCode.TimeBasedCounter.toCounterState(): Counter.TimeBasedCounter {
    val now = Clock.System.now()
    val remainingDuration = expiration - now
    val elapsedDuration = duration - remainingDuration
    val time = remainingDuration
        .inWholeMilliseconds
        .toFloat()
        .div(1000F)
        .roundToInt()
        .coerceAtLeast(0)
        .toString()
    val progress = 1f - elapsedDuration.inWholeSeconds.toFloat() / duration.inWholeSeconds.toFloat()
    return Counter.TimeBasedCounter(
        time = time,
        progress = progress,
    )
}
