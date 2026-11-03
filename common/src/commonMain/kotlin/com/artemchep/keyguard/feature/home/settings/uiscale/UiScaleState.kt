package com.artemchep.keyguard.feature.home.settings.uiscale

data class UiScaleState(
    /** Selected step, see [com.artemchep.keyguard.common.model.uiScaleFromStep]. */
    val step: Int,
    /** The scale the window is drawn with right now. */
    val appliedScale: Float,
    val canSave: Boolean,
    val onStepChange: (Int) -> Unit,
    val onSave: () -> Unit,
    val onDismiss: () -> Unit,
)
