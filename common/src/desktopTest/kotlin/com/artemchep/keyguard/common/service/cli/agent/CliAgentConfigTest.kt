package com.artemchep.keyguard.common.service.cli.agent

import com.artemchep.keyguard.build.BuildKonfig
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class CliAgentConfigTest {
    @Test
    fun `macOS development agent stays separate from the production socket`() {
        val socket = cliAgentConfig().defaultAgentSocketPath
        if (BuildKonfig.buildType == "DEV" && CurrentPlatform is Platform.Desktop.MacOS) {
            val home = Path.of(System.getProperty("user.home"))
            val uid = Files.getAttribute(home, "unix:uid") as Number
            assertEquals(Path.of("/tmp/keyguard-${uid.toLong()}/cli.sock"), socket)
            assertNotEquals(home.resolve(".keyguard/cli.sock"), socket)
        } else {
            assertNull(socket)
        }
    }
}
