package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.test.withTempDirectory
import org.junit.Assume.assumeTrue
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.test.Test
import kotlin.test.assertEquals

class ManagedGpgHomeUidTest {
    /** The managed home is checked against the owner of the files that this process creates. */
    @Test
    fun `unix user ID owns the files this process creates`() {
        // Windows has no Unix user IDs, and never prepares a home with one.
        assumeTrue("unix" in FileSystems.getDefault().supportedFileAttributeViews())
        withTempDirectory("keyguard-gpg-uid") { directory ->
            val owner = Files.getAttribute(directory, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Number
            assertEquals(owner.toLong(), currentUnixUid())
        }
    }
}
