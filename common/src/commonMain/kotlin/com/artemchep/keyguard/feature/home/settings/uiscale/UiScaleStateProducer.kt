package com.artemchep.keyguard.feature.home.settings.uiscale

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.UI_SCALE_MAX
import com.artemchep.keyguard.common.model.UI_SCALE_MAX_STEP
import com.artemchep.keyguard.common.model.UI_SCALE_MIN
import com.artemchep.keyguard.common.model.uiScaleFromStep
import com.artemchep.keyguard.common.model.uiScaleToStep
import com.artemchep.keyguard.common.usecase.GetUiScale
import com.artemchep.keyguard.common.usecase.PutUiScale
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.koin.compose.currentKoinScope

@Composable
fun produceUiScaleState(): Loadable<UiScaleState> =
    with(currentKoinScope()) {
        val getUiScale = get<GetUiScale>()
        val putUiScale = get<PutUiScale>()
        produceScreenState(
            key = "ui_scale",
            initial = Loadable.Loading,
            args = arrayOf(getUiScale, putUiScale),
        ) {
            uiScaleStateProducer(getUiScale, putUiScale)
                .map { Loadable.Ok(it) }
        }
    }

suspend fun RememberStateFlowScope.uiScaleStateProducer(
    getUiScale: GetUiScale,
    putUiScale: PutUiScale,
): Flow<UiScaleState> {
    // Matches the clamping of the desktop window density.
    val appliedScaleFlow = getUiScale()
        .map { it.coerceIn(UI_SCALE_MIN, UI_SCALE_MAX) }
    val initialStep = uiScaleToStep(appliedScaleFlow.first())
    val stepSink = mutablePersistedFlow("step") { initialStep }

    fun onStepChange(step: Int) {
        stepSink.value = step.coerceIn(0, UI_SCALE_MAX_STEP)
    }

    fun onSave() {
        putUiScale(uiScaleFromStep(stepSink.value))
            .launchIn(appScope)
        navigate(NavigationIntent.Pop)
    }

    fun onDismiss() {
        navigate(NavigationIntent.Pop)
    }

    return combine(
        stepSink,
        appliedScaleFlow,
    ) { step, appliedScale ->
        UiScaleState(
            step = step,
            appliedScale = appliedScale,
            canSave = step != uiScaleToStep(appliedScale),
            onStepChange = ::onStepChange,
            onSave = ::onSave,
            onDismiss = ::onDismiss,
        )
    }
}
