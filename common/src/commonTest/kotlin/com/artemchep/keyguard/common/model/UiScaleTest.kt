package com.artemchep.keyguard.common.model

import kotlin.test.Test
import kotlin.test.assertEquals

class UiScaleTest {
    @Test
    fun `steps cover 0_5x to 1_5x in 0_05x increments`() {
        assertEquals(0.5f, uiScaleFromStep(0))
        assertEquals(1f, uiScaleFromStep(10))
        assertEquals(1.05f, uiScaleFromStep(11))
        assertEquals(1.5f, uiScaleFromStep(UI_SCALE_MAX_STEP))
    }

    @Test
    fun `scale round trips through its step`() {
        for (step in 0..UI_SCALE_MAX_STEP) {
            assertEquals(step, uiScaleToStep(uiScaleFromStep(step)))
        }
    }

    @Test
    fun `out of range scales are clamped`() {
        assertEquals(0, uiScaleToStep(0.1f))
        assertEquals(UI_SCALE_MAX_STEP, uiScaleToStep(2f))
    }

    @Test
    fun `scale is formatted without a trailing zero`() {
        assertEquals("0.5x", formatUiScale(0))
        assertEquals("1.0x", formatUiScale(10))
        assertEquals("1.05x", formatUiScale(11))
        assertEquals("1.1x", formatUiScale(12))
        assertEquals("1.25x", formatUiScale(15))
        assertEquals("1.5x", formatUiScale(UI_SCALE_MAX_STEP))
    }
}
