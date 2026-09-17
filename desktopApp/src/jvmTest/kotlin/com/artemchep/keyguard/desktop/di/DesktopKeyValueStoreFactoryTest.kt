package com.artemchep.keyguard.desktop.di

import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.copy.DataDirectory
import com.artemchep.keyguard.copy.DesktopKeyValueStoreFactory
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlinx.serialization.json.Json

class DesktopKeyValueStoreFactoryTest {
    @Test
    fun `stores are shared per file without sharing unrelated files`() {
        val factory = DesktopKeyValueStoreFactory(DataDirectory(), Json)

        val window = factory.get(Files.WINDOW_STATE)
        val device = factory.get(Files.DEVICE_ID)

        assertSame(window, factory.get(Files.WINDOW_STATE))
        assertSame(device, factory.get(Files.DEVICE_ID))
        assertNotSame(window, device)
    }
}
