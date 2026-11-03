package com.artemchep.keyguard.feature.export

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExportWizardTest {
    private fun data(
        step: ExportStep,
        itemsCount: Int = 1,
        passwordValid: Boolean = true,
        passwordMatches: Boolean = true,
    ) = ExportWizardData(
        step = step,
        itemsCount = itemsCount,
        passwordValid = passwordValid,
        passwordMatches = passwordMatches,
    )

    @Test
    fun `format always continues and back leaves the screen`() {
        val wizard = data(ExportStep.Format, itemsCount = 0, passwordValid = false)
            .toState(MutableStateFlow(ExportStep.Format))

        assertNull(wizard.onBack)
        assertNotNull(wizard.onContinue)
    }

    @Test
    fun `items continue only with items to export`() {
        assertFalse(data(ExportStep.Items, itemsCount = 0).canContinue)
        assertTrue(data(ExportStep.Items, itemsCount = 1).canContinue)
    }

    @Test
    fun `password continues only when it is valid and confirmed`() {
        assertFalse(data(ExportStep.Password, passwordValid = false).canContinue)
        assertFalse(data(ExportStep.Password, passwordMatches = false).canContinue)
        assertTrue(data(ExportStep.Password).canContinue)
    }

    @Test
    fun `review ends with the export and goes back to the password`() {
        val sink = MutableStateFlow(ExportStep.Review)
        val wizard = data(ExportStep.Review).toState(sink)

        assertNull(wizard.onContinue)
        wizard.onBack!!.invoke()
        assertEquals(ExportStep.Password, sink.value)
    }

    @Test
    fun `steps go forward in order`() {
        val sink = MutableStateFlow(ExportStep.Format)
        val steps = mutableListOf(sink.value)
        while (true) {
            val onContinue = data(sink.value).toState(sink).onContinue
                ?: break
            onContinue()
            steps += sink.value
        }

        assertEquals<List<ExportStep>>(ExportStep.entries, steps)
    }

    @Test
    fun `a stale click does not skip a step`() {
        val sink = MutableStateFlow(ExportStep.Format)
        val onContinue = data(ExportStep.Format).toState(sink).onContinue!!

        onContinue()
        onContinue()

        assertEquals(ExportStep.Items, sink.value)
    }
}
