package com.artemchep.keyguard.test.nativebundle

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.artemchep.keyguard.nativebundle.NativeBundleProbe
import com.artemchep.keyguard.util.io.LocalPath
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
@SmallTest
class NativeBundleSmokeTest {
    @Test
    fun loadsPackagedLibrariesAndExecutesNativeOperations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "native-bundle-").toRealPath()
        try {
            NativeBundleProbe.run(LocalPath(directory.toString()))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
