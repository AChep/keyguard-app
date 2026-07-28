package com.artemchep.keyguard.copy

import com.artemchep.keyguard.platform.Platform
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.SystemFlavorMap
import java.awt.datatransfer.Transferable
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ClipboardServiceJvmTest {
    @Test
    fun `windows concealed selection excludes history and cloud clipboard`() {
        val selection = SensitiveStringSelection(
            value = "password",
            platform = Platform.Desktop.Windows,
        )

        assertEquals(
            "password",
            selection.getTransferData(DataFlavor.stringFlavor),
        )
        assertContentEquals(
            byteArrayOf(1),
            selection.markerData("ExcludeClipboardContentFromMonitorProcessing"),
        )
        assertContentEquals(
            ByteArray(Int.SIZE_BYTES),
            selection.markerData("CanIncludeInClipboardHistory"),
        )
        assertContentEquals(
            ByteArray(Int.SIZE_BYTES),
            selection.markerData("CanUploadToCloudClipboard"),
        )
    }

    @Test
    fun `marker data is returned as a copy`() {
        val selection = SensitiveStringSelection(
            value = "password",
            platform = Platform.Desktop.Windows,
        )

        val first = selection.markerData("CanIncludeInClipboardHistory")
        first[0] = 1

        assertContentEquals(
            ByteArray(Int.SIZE_BYTES),
            selection.markerData("CanIncludeInClipboardHistory"),
        )
    }

    @Test
    fun `macos fallback selection contains concealed marker`() {
        val selection = SensitiveStringSelection(
            value = "password",
            platform = Platform.Desktop.MacOS,
        )

        assertContentEquals(
            byteArrayOf(),
            selection.markerData("org.nspasteboard.ConcealedType"),
        )
    }

    @Test
    fun `linux selection keeps kde password manager hint`() {
        val selection = SensitiveStringSelection(
            value = "password",
            platform = Platform.Desktop.Linux.native,
        )

        assertContentEquals(
            "secret".encodeToByteArray(),
            selection.markerData("x-kde-passwordManagerHint"),
        )
    }

    private fun Transferable.markerData(native: String): ByteArray {
        val flavorMap = SystemFlavorMap.getDefaultFlavorMap() as SystemFlavorMap
        val flavor = transferDataFlavors.single { flavor ->
            native in flavorMap.getNativesForFlavor(flavor)
        }
        return getTransferData(flavor) as ByteArray
    }
}
