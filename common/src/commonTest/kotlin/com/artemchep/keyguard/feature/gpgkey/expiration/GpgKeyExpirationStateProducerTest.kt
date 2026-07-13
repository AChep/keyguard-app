package com.artemchep.keyguard.feature.gpgkey.expiration

import com.artemchep.keyguard.feature.gpgkey.GpgKeyExpiryPreset
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpgKeyExpirationStateProducerTest {
    @Test
    fun `custom preset exposes the formatted date as supporting text`() {
        val customDate = LocalDate(2030, 3, 15)

        val state = createGpgKeyExpirationPresetState(
            item = GpgKeyExpiryPreset.Custom,
            title = "Custom date",
            selected = true,
            customDate = customDate,
            formatDate = { date -> "formatted:$date" },
            onClick = {},
        )

        assertEquals(GpgKeyExpiryPreset.Custom.key, state.key)
        assertEquals("Custom date", state.title)
        assertEquals("formatted:2030-03-15", state.text)
        assertTrue(state.selected)
    }

    @Test
    fun `unselected custom preset does not expose a retained custom date`() {
        val state = createGpgKeyExpirationPresetState(
            item = GpgKeyExpiryPreset.Custom,
            title = "Custom date",
            selected = false,
            customDate = LocalDate(2030, 3, 15),
            formatDate = { date -> "formatted:$date" },
            onClick = {},
        )

        assertNull(state.text)
    }
}
