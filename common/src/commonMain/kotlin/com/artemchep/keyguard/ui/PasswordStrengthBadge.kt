package com.artemchep.keyguard.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.PasswordStrength
import com.artemchep.keyguard.common.model.alertScore
import com.artemchep.keyguard.common.model.formatLocalized
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.feature.crashlytics.crashlyticsTap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.kodein.di.compose.rememberInstance

private data class PasswordStrengthBadgeState(
    val password: String,
    val content: Loadable<PasswordStrength.Score?>,
)

@Composable
fun PasswordStrengthBadge(
    modifier: Modifier = Modifier,
    password: String,
) {
    val state = producePasswordStrengthBadgeState(password)
    val score by remember(state) {
        derivedStateOf {
            val strength = state.value.content.getOrNull()
            strength?.alertScore()
                ?: 1f
        }
    }
    val alpha by run {
        val enabled by remember(state, password) {
            derivedStateOf {
                state.value.password == password
            }
        }
        val targetAlpha = if (enabled) 1f else DisabledEmphasisAlpha
        animateFloatAsState(targetAlpha)
    }
    AhContainer(
        modifier = modifier
            .graphicsLayer {
                this.alpha = alpha
            },
        score = score,
    ) {
        Box(
            modifier = Modifier
                .animateContentSize(),
        ) {
            val hasStrength by remember(state) {
                derivedStateOf {
                    state.value.content is Loadable.Ok
                }
            }
            if (hasStrength) {
                val strength = state.value.content.getOrNull()
                if (strength != null) {
                    Text(
                        modifier = Modifier
                            .padding(horizontal = 4.dp),
                        text = strength.formatLocalized(),
                    )
                }
            } else {
                KeyguardLoadingIndicator(
                    modifier = Modifier
                        .size(17.dp),
                )
            }
        }
    }
}

@Composable
private fun producePasswordStrengthBadgeState(
    password: String,
): State<PasswordStrengthBadgeState> {
    val getPasswordStrength: GetPasswordStrength by rememberInstance()

    val sink = remember {
        MutableStateFlow(password)
    }

    val initialState = PasswordStrengthBadgeState(
        password = password,
        content = Loadable.Loading,
    )
    val state = remember(sink) {
        sink
            .debounce(250L)
            .onStart {
                emit(password)
            }
            .distinctUntilChanged()
            .flatMapLatest { pw ->
                flowOf(Unit)
                    .map {
                        coroutineScope {
                            val deferredScore = async(Dispatchers.Default) {
                                getPasswordStrength(pw)
                                    .map { pwStrength -> pwStrength.score }
                                    .crashlyticsTap()
                                    .attempt()
                                    .bind()
                                    .getOrNull()
                            }
                            delay(250L)
                            deferredScore.await()
                        }
                    }
                    .map { score ->
                        val content: Loadable<PasswordStrength.Score?> = Loadable.Ok(score)
                        PasswordStrengthBadgeState(
                            password = pw,
                            content = content,
                        )
                    }
                    .onStart {
                        emit(initialState)
                    }
            }
            .flowOn(Dispatchers.Default)
    }.collectAsState(initial = initialState)

    SideEffect {
        sink.value = password
    }

    return state
}
