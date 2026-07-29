package com.artemchep.keyguard.copy

import com.artemchep.keyguard.util.io.atomic.AtomicDirectoryPermissions
import com.artemchep.keyguard.util.io.atomic.AtomicFilePermissions
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.atomic.AtomicPublicationPolicy
import com.artemchep.keyguard.util.io.atomic.AtomicWriteOptions
import com.artemchep.keyguard.util.io.atomic.ExistingParentLinkPolicy
import com.artemchep.keyguard.util.io.atomic.ParentDirectoryPolicy
import com.artemchep.keyguard.util.io.atomic.SyncLevel
import com.artemchep.keyguard.util.io.atomic.SynchronizationPolicy
import com.artemchep.keyguard.util.io.atomic.writeFileAtomically
import kotlinx.io.writeString
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals

class DataDirectoryAtomicTest {
    @Test
    fun firstWriteCreatesTheCompleteAppOwnedSuffixBelowExistingRoot() {
        val root = createTempDirectory("atomic-app-root")
        val appDirectory = root.resolve("missing-platform-base/keyguard-dev")
        val directory = atomicDirectoryUnderExistingRoot(
            root = root,
            directory = appDirectory,
        )
        val destination = directory.resolve(
            AtomicPathComponent.parse("preferences.json"),
        )

        writeFileAtomically(
            destination = destination,
            options = AtomicWriteOptions(
                publication = AtomicPublicationPolicy.Create(
                    permissions = AtomicFilePermissions.OwnerOnly,
                ),
                parentDirectories = ParentDirectoryPolicy.CreateMissing(
                    permissions = AtomicDirectoryPermissions.OwnerOnly,
                ),
                existingParentLinks = ExistingParentLinkPolicy.Reject,
                synchronization = SynchronizationPolicy.Required(
                    SyncLevel.FileSynchronized,
                ),
            ),
        ) { sink ->
            sink.writeString("first write")
        }

        assertEquals(
            "first write",
            appDirectory.resolve("preferences.json").readText(),
        )
    }
}
