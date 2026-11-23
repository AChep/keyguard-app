package com.artemchep.keyguard.util.instance

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class InstanceOwnershipTest {
    @Test
    fun ownershipOnlyNeedsNoShortRuntimePathAndSharesFullProtocolLock() {
        val temporary = if (System.getProperty("os.name").startsWith("Windows")) {
            Paths.get(System.getProperty("java.io.tmpdir"))
        } else {
            Paths.get("/tmp")
        }
        val root = Files.createTempDirectory(temporary, "ki-").toRealPath()
        try {
            val runtime = Files.createDirectory(root.resolve("ipc"))
            val directory = root.resolve("long-group-container-".repeat(8)).toString()
            val config = InstanceConfig(directory, runtime.toString(), "ownership-test", timeoutMillis = 50)
            val ownershipConfig = InstanceOwnershipConfig(directory, config.identity, timeoutMillis = 50)
            val owner = assertNotNull(InstanceCoordinator.acquireOwnership(ownershipConfig))
            owner.use {
                assertNull(InstanceCoordinator.acquireOwnership(ownershipConfig))
                assertNull(InstanceCoordinator.acquire(config))
            }
            owner.close()
            assertNotNull(InstanceCoordinator.acquire(config)).close()
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
