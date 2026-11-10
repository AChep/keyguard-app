package com.artemchep.keyguard.common.util

import kotlin.test.Test
import kotlin.test.assertEquals

class FileNameStemTest {
    @Test
    fun dropsTheExtension() {
        assertEquals("report", "report.pdf".fileNameStem())
    }

    @Test
    fun dropsOnlyTheLastExtension() {
        assertEquals("archive.tar", "archive.tar.gz".fileNameStem())
    }

    @Test
    fun keepsANameWithoutAnExtension() {
        assertEquals("report", "report".fileNameStem())
        assertEquals("", "".fileNameStem())
    }

    @Test
    fun aLeadingDotIsNotAnExtension() {
        assertEquals(".env", ".env".fileNameStem())
        assertEquals(".env", ".env.local".fileNameStem())
    }
}
