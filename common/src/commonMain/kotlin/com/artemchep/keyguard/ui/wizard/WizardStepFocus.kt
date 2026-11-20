package com.artemchep.keyguard.ui.wizard

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester

/**
 * Keyboard focus of one wizard step: the [group] around its content,
 * and the requester of its selected [WizardChoice], if any.
 */
internal class WizardStepFocus(
    val group: FocusRequester,
) {
    /** Set by the selected choice while it is composed. */
    var selectedChoice: FocusRequester? = null
}

/** The focus of the step whose content is composed. */
internal val LocalWizardStepFocus = staticCompositionLocalOf<WizardStepFocus?> { null }

/** The requester of the [WizardBottomBar] action. */
internal val LocalWizardPrimaryFocus = staticCompositionLocalOf<FocusRequester?> { null }
