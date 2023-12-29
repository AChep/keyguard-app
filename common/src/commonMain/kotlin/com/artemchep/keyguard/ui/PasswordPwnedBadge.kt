package com.artemchep.keyguard.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.CheckPasswordLeakRequest
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.usecase.CheckPasswordLeak
import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.compose.stringResource
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

private data class PasswordPwnedBadgeState(
    val password: String,
    val content: Loadable<Int?>,
)

@Composable
fun PasswordPwnedBadge(
    modifier: Modifier = Modifier,
    password: String,
) {
    val state = producePasswordPwnedBadgeState(password)
    val visible by remember(state) {
        derivedStateOf {
            val strength = state.value.content.getOrNull()
            strength
                ?.let { it > 0 }
                ?: false
        }
    }
    ExpandedIfNotEmptyForRow(
        modifier = modifier,
        valueOrNull = Unit.takeIf { visible && state.value.password == password },
    ) {
        AhContainer(
            score = 0f,
        ) {
            Text(
                modifier = Modifier
                    .padding(horizontal = 4.dp),
                text = stringResource(Res.strings.password_pwned_label),
            )
        }
    }
}

@Composable
private fun producePasswordPwnedBadgeState(
    password: String,
): State<PasswordPwnedBadgeState> {
    val checkPasswordLeak: CheckPasswordLeak by rememberInstance()

    val sink = remember {
        MutableStateFlow(password)
    }

    val initialState = PasswordPwnedBadgeState(
        password = password,
        content = Loadable.Loading,
    )
    val state = remember(sink) {
        sink
            .debounce(500L)
            .onStart {
                emit(password)
            }
            .distinctUntilChanged()
            .flatMapLatest { pw ->
                flowOf(Unit)
                    .map {
                        coroutineScope {
                            val deferredScore = async(Dispatchers.Default) {
                                val request = CheckPasswordLeakRequest(
                                    password = pw,
                                    cache = false,
                                )
                                checkPasswordLeak(request)
                                    .attempt()
                                    .bind()
                                    .getOrNull()
                            }
                            delay(250L)
                            deferredScore.await()
                        }
                    }
                    .map { score ->
                        val content: Loadable<Int?> = Loadable.Ok(score)
                        PasswordPwnedBadgeState(
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
