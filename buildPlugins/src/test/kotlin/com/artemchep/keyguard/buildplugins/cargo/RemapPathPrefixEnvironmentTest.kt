package com.artemchep.keyguard.buildplugins.cargo

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class RemapPathPrefixEnvironmentTest {
    @Test
    fun `maps the paths in order in the encoded Rust flags`() {
        val repository = File("/work/keyguard").absoluteFile
        val cargoHome = File("/home/builder/.cargo").absoluteFile
        assertEquals(
            mapOf(
                "CARGO_ENCODED_RUSTFLAGS" to "--remap-path-prefix=${repository.path}=/keyguard" +
                    "\u001f--remap-path-prefix=${cargoHome.path}=/cargo",
            ),
            remapPathPrefixEnvironment(listOf(repository to "/keyguard", cargoHome to "/cargo")),
        )
    }

    @Test
    fun `keeps a path with whitespace whole`() {
        val repository = File("/work/my keyguard").absoluteFile
        val cargoHome = File("/home/builder/Build Tools/cargo").absoluteFile
        assertEquals(
            listOf(
                "--remap-path-prefix=${repository.path}=/keyguard",
                "--remap-path-prefix=${cargoHome.path}=/cargo",
            ),
            remapPathPrefixEnvironment(listOf(repository to "/keyguard", cargoHome to "/cargo"))
                .getValue("CARGO_ENCODED_RUSTFLAGS")
                .split('\u001f'),
        )
    }
}
