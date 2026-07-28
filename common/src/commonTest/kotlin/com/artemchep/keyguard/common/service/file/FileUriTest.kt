package com.artemchep.keyguard.common.service.file

import com.artemchep.keyguard.platform.LocalPath
import kotlin.test.Test
import kotlin.test.assertEquals

class FileUriTest {
    @Test
    fun `encodes reserved path characters and round trips`() {
        val path = LocalPath("/tmp/keyguard files/#hash?.txt")

        val uri = path.toFileUriString()

        assertEquals("file:///tmp/keyguard%20files/%23hash%3F.txt", uri)
        assertEquals(path, uri.toLocalPathFromFileUriOrNull())
    }

    @Test
    fun `keeps safe path characters unescaped`() {
        val path = LocalPath("/tmp/keyguard/a:b-._~/file.txt")

        val uri = path.toFileUriString()

        assertEquals("file:///tmp/keyguard/a:b-._~/file.txt", uri)
        assertEquals(path, uri.toLocalPathFromFileUriOrNull())
    }
}
