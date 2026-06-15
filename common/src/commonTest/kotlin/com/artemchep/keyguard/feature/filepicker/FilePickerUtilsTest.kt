package com.artemchep.keyguard.feature.filepicker

import kotlin.test.Test
import kotlin.test.assertEquals

class FilePickerUtilsTest {
    @Test
    fun `binary byte count keeps bytes below one kibibyte`() {
        assertEquals("0 B", humanReadableByteCountBin(0))
        assertEquals("1 B", humanReadableByteCountBin(1))
        assertEquals("1023 B", humanReadableByteCountBin(1023))
        assertEquals("-1023 B", humanReadableByteCountBin(-1023))
    }

    @Test
    fun `binary byte count formats powers of 1024`() {
        val cases = listOf(
            1_024L to "1.0 KiB",
            1_048_576L to "1.0 MiB",
            1_073_741_824L to "1.0 GiB",
            1_099_511_627_776L to "1.0 TiB",
            1_125_899_906_842_624L to "1.0 PiB",
            1_152_921_504_606_846_976L to "1.0 EiB",
        )

        cases.forEach { (bytes, expected) ->
            assertEquals(expected, humanReadableByteCountBin(bytes))
        }
    }

    @Test
    fun `binary byte count rounds to one decimal and promotes near next unit`() {
        val cases = listOf(
            1_536L to "1.5 KiB",
            1_048_524L to "1023.9 KiB",
            1_048_525L to "1.0 MiB",
            -1_536L to "-1.5 KiB",
            -1_048_524L to "-1023.9 KiB",
            -1_048_525L to "-1.0 MiB",
        )

        cases.forEach { (bytes, expected) ->
            assertEquals(expected, humanReadableByteCountBin(bytes))
        }
    }

    @Test
    fun `binary byte count handles long extremes`() {
        assertEquals("8.0 EiB", humanReadableByteCountBin(Long.MAX_VALUE))
        assertEquals("-8.0 EiB", humanReadableByteCountBin(Long.MIN_VALUE))
    }

    @Test
    fun `si byte count keeps bytes below one kilobyte`() {
        assertEquals("0 B", humanReadableByteCountSI(0))
        assertEquals("1 B", humanReadableByteCountSI(1))
        assertEquals("999 B", humanReadableByteCountSI(999))
        assertEquals("-999 B", humanReadableByteCountSI(-999))
    }

    @Test
    fun `si byte count formats powers of 1000`() {
        val cases = listOf(
            1_000L to "1.0 kB",
            1_000_000L to "1.0 MB",
            1_000_000_000L to "1.0 GB",
            1_000_000_000_000L to "1.0 TB",
            1_000_000_000_000_000L to "1.0 PB",
            1_000_000_000_000_000_000L to "1.0 EB",
        )

        cases.forEach { (bytes, expected) ->
            assertEquals(expected, humanReadableByteCountSI(bytes))
        }
    }

    @Test
    fun `si byte count rounds to one decimal and promotes near next unit`() {
        val cases = listOf(
            1_500L to "1.5 kB",
            999_949L to "999.9 kB",
            999_950L to "1.0 MB",
            -1_500L to "-1.5 kB",
            -999_949L to "-999.9 kB",
            -999_950L to "-1.0 MB",
        )

        cases.forEach { (bytes, expected) ->
            assertEquals(expected, humanReadableByteCountSI(bytes))
        }
    }

    @Test
    fun `si byte count handles long extremes`() {
        assertEquals("9.2 EB", humanReadableByteCountSI(Long.MAX_VALUE))
        assertEquals("-9.2 EB", humanReadableByteCountSI(Long.MIN_VALUE))
    }
}
