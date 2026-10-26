package com.artemchep.keyguard.feature.export

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The steps of the export, in order. Apple keeps
 * its own step navigation in SwiftUI.
 */
enum class ExportStep {
    Format,
    Items,
    Password,
    Review,
}

internal data class ExportWizardData(
    val step: ExportStep,
    val itemsCount: Int,
    val passwordValid: Boolean,
    val passwordMatches: Boolean,
) {
    /** The last step ends with the export instead. */
    val canContinue: Boolean
        get() = when (step) {
            ExportStep.Format -> true
            ExportStep.Items -> itemsCount > 0
            ExportStep.Password -> passwordValid && passwordMatches
            ExportStep.Review -> false
        }

    val previousStep: ExportStep?
        get() = ExportStep.entries.getOrNull(step.ordinal - 1)

    val nextStep: ExportStep?
        get() = ExportStep.entries.getOrNull(step.ordinal + 1)
            ?.takeIf { canContinue }
}

/**
 * A transition only applies while the wizard is still on
 * its step, so a stale or repeated click can not skip one.
 */
internal fun ExportWizardData.toState(
    sink: MutableStateFlow<ExportStep>,
) = ExportState.Wizard(
    step = step,
    onBack = sink.transition(step, previousStep),
    onContinue = sink.transition(step, nextStep),
)

private fun MutableStateFlow<ExportStep>.transition(
    from: ExportStep,
    to: ExportStep?,
): (() -> Unit)? {
    to ?: return null
    return {
        compareAndSet(from, to)
    }
}
