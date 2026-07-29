package com.artemchep.keyguard.util.io.atomic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AtomicRelativePathTest {
    @Test
    fun acceptsPortableDescendantFilePaths() {
        val path = AtomicRelativePath.parse("year/month/object.bin")

        assertEquals("year/month/object.bin", path.value)
    }

    @Test
    fun composesValidatedUnicodeComponentsWithoutReinterpretingThem() {
        val path = AtomicRelativePath.fromComponents(
            AtomicPathComponent.parse("сховище"),
            AtomicPathComponent.parse("日本語.bin"),
        )

        assertEquals("сховище/日本語.bin", path.value)
    }

    @Test
    fun componentRejectsEmbeddedHierarchy() {
        listOf(
            "",
            "account/escape",
            "account\\escape",
            "account:stream",
            ".",
            "..",
            "account\u0000tail",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException>(message = value) {
                AtomicPathComponent.parse(value)
            }
        }
    }

    @Test
    fun directoryDestinationResolvesValidatedChildren() {
        val directory = AtomicDirectoryDestination(
            root = com.artemchep.keyguard.util.io.LocalPath("/trusted"),
            relativePath = AtomicRelativePath.fromComponents(
                AtomicPathComponent.parse("accounts"),
            ),
        )
        val nested = directory.resolveDirectory(
            AtomicPathComponent.parse("alice"),
        )
        val file = nested.resolve(
            AtomicPathComponent.parse("日本語.bin"),
        )

        assertEquals("/trusted/accounts/alice", nested.path.value)
        assertEquals("/trusted/accounts/alice/日本語.bin", file.path.value)
    }

    @Test
    fun retainedDestinationRejectsContradictoryLinkPolicyBeforeWriting() {
        var invoked = false
        val destination = AtomicFileDestination(
            root = com.artemchep.keyguard.util.io.LocalPath("/not-opened"),
            relativePath = AtomicRelativePath.parse("payload.bin"),
        )

        assertFailsWith<IllegalArgumentException> {
            writeFileAtomically(
                destination = destination,
                options = AtomicWriteOptions(
                    publication = AtomicPublicationPolicy.Create(
                        permissions = AtomicFilePermissions.ProcessDefault,
                    ),
                    parentDirectories = ParentDirectoryPolicy.RequireExisting,
                    existingParentLinks = ExistingParentLinkPolicy.FollowAndPin,
                    synchronization = SynchronizationPolicy.Required(
                        level = SyncLevel.ProcessAtomic,
                    ),
                ),
            ) {
                invoked = true
            }
        }

        assertEquals(false, invoked)
    }

    @Test
    fun destinationExposesItsLexicalDiagnosticPath() {
        val destination = AtomicFileDestination(
            root = com.artemchep.keyguard.util.io.LocalPath("/trusted/root"),
            relativePath = AtomicRelativePath.parse("nested/payload.bin"),
        )

        assertEquals("/trusted/root/nested/payload.bin", destination.path.value)
    }

    @Test
    fun rejectsAmbiguousAbsoluteAndEscapingSpellings() {
        listOf(
            "",
            "/object",
            "\\\\server\\share\\object",
            "C:/object",
            "dir/",
            "dir\\object",
            "dir//object",
            "./object",
            "dir/../object",
            "dir/./object",
            "object:stream",
            "object\u0000tail",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException>(message = value) {
                AtomicRelativePath.parse(value)
            }
        }
    }
}
