package com.artemchep.keyguard.feature.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class CliCommandLineTest {
    @Test
    fun `arguments are quoted only when needed`() {
        assertEquals("npm run dev", cliCommandLine(listOf("npm", "run", "dev")))
        assertEquals(
            "sh -c 'echo \"\$TOKEN\"' '' 'it'\\''s'",
            cliCommandLine(listOf("sh", "-c", "echo \"\$TOKEN\"", "", "it's")),
        )
    }

    @Test
    fun `control characters can not hide an argument`() {
        assertEquals("$'a\\u000ab' $'c\\u001b[2Kd'", cliCommandLine(listOf("a\nb", "c\u001b[2Kd")))
    }

    @Test
    fun `direction and invisible characters can not hide an argument`() {
        assertEquals("$'a\\u202eb'", cliCommandLine(listOf("a‮b")))
        assertEquals("$'a\\u200bb'", cliCommandLine(listOf("a​b")))
        assertEquals("$'a\\u2028b'", cliCommandLine(listOf("a b")))
        assertEquals("$'\\udb40\\udc01'", cliCommandLine(listOf("󠀁")))
        assertEquals("$'\\ud800x'", cliCommandLine(listOf("\uD800x")))
    }

    @Test
    fun `an escape can not pass for literal text`() {
        assertEquals("'a\\u000ab'", cliCommandLine(listOf("a\\u000ab")))
        assertEquals("$'it\\'s\\u000a\\\\'", cliCommandLine(listOf("it's\n\\")))
    }

    @Test
    fun `visible non-ascii text is shown as is`() {
        assertEquals("'hi 😀'", cliCommandLine(listOf("hi 😀")))
    }

    @Test
    fun `usage connects to the configured agent socket`() {
        assertEquals(
            "KEYGUARD_CLI_SOCKET=/tmp/keyguard-501/cli.sock keyguard --help",
            cliUsage("/tmp/keyguard-501/cli.sock"),
        )
    }

    @Test
    fun `usage keeps the platform default without a socket override`() {
        assertEquals("keyguard --help", cliUsage(null))
    }

    @Test
    fun `usage quotes a socket path containing shell syntax`() {
        assertEquals(
            "KEYGUARD_CLI_SOCKET='/tmp/Keyguard user'\\''s/cli.sock' keyguard --help",
            cliUsage("/tmp/Keyguard user's/cli.sock"),
        )
    }
}
