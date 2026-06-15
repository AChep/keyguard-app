package com.artemchep.dbus.portal

import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PortalSettingsTest {
    @Test
    fun `maps dark color scheme variant`() {
        val result = Variant(UInt32(1)).toPortalColorScheme()

        assertEquals(PortalColorScheme.PREFER_DARK, result)
    }

    @Test
    fun `maps light color scheme variant`() {
        val result = Variant(UInt32(2)).toPortalColorScheme()

        assertEquals(PortalColorScheme.PREFER_LIGHT, result)
    }

    @Test
    fun `maps no preference color scheme variant`() {
        val result = Variant(UInt32(0)).toPortalColorScheme()

        assertEquals(PortalColorScheme.NO_PREFERENCE, result)
    }

    @Test
    fun `maps nested deprecated read variant`() {
        val result = Variant(Variant(UInt32(2))).toPortalColorScheme()

        assertEquals(PortalColorScheme.PREFER_LIGHT, result)
    }

    @Test
    fun `maps unknown color scheme value as no preference`() {
        val result = Variant(UInt32(3)).toPortalColorScheme()

        assertEquals(PortalColorScheme.NO_PREFERENCE, result)
    }

    @Test
    fun `maps large color scheme value as no preference`() {
        val result = Variant(UInt32(UInt32.MAX_VALUE)).toPortalColorScheme()

        assertEquals(PortalColorScheme.NO_PREFERENCE, result)
    }

    @Test
    fun `ignores malformed color scheme value`() {
        val result = "1".toPortalColorScheme()

        assertNull(result)
    }

    @Test
    fun `maps color scheme setting changed value`() {
        val result = portalColorSchemeFromSettingChanged(
            namespace = "org.freedesktop.appearance",
            key = "color-scheme",
            value = Variant(UInt32(1)),
        )

        assertEquals(PortalColorScheme.PREFER_DARK, result)
    }

    @Test
    fun `ignores unrelated setting changed value`() {
        val result = portalColorSchemeFromSettingChanged(
            namespace = "org.freedesktop.interface",
            key = "accent-color",
            value = Variant(UInt32(1)),
        )

        assertNull(result)
    }
}
