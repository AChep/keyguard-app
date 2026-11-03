package com.artemchep.keyguard.integration.bitwarden.cli

import com.artemchep.keyguard.integration.bitwarden.TestKdf
import com.artemchep.keyguard.integration.bitwarden.VaultwardenAccounts
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The command line parser of `bw`; needs no server. */
class BwArgsE2eTest {
    @Test
    fun `register defaults to pbkdf2 and the harness name`() {
        assertEquals(
            BwCommand.Register(ACCOUNT, name = VaultwardenAccounts.DEFAULT_NAME, kdf = TestKdf.Pbkdf2()),
            parse("register", *ACCOUNT_ARGS),
        )
    }

    @Test
    fun `register takes a name and a kdf`() {
        assertEquals(
            BwCommand.Register(ACCOUNT, name = "Alice", kdf = TestKdf.Argon2id(3, 64, 4), json = true),
            parse("register", *ACCOUNT_ARGS, "--name", "Alice", "--argon2", "--json"),
        )
        assertEquals(
            BwCommand.Register(ACCOUNT, kdf = TestKdf.Pbkdf2(iterations = 700_000)),
            parse("register", *ACCOUNT_ARGS, "--pbkdf2-iterations", "700000"),
        )
        assertInvalid("either", "register", *ACCOUNT_ARGS, "--argon2", "--pbkdf2-iterations", "700000")
        assertInvalid("positive", "register", *ACCOUNT_ARGS, "--pbkdf2-iterations", "0")
    }

    @Test
    fun `seed has defaults and takes every option`() {
        assertEquals(
            BwCommand.Seed(ACCOUNT, folders = 1, logins = 3, notes = 1, attachment = false, send = null),
            parse("seed", *ACCOUNT_ARGS),
        )
        assertEquals(
            BwCommand.Seed(
                account = ACCOUNT,
                folders = 0,
                logins = 5,
                notes = 2,
                attachment = true,
                send = BwCommand.SendType.File,
                json = true,
            ),
            parse(
                "seed", *ACCOUNT_ARGS, "--folders", "0", "--logins", "5", "--notes", "2",
                "--attachment", "--send", "file", "--json",
            ),
        )
        assertEquals(BwCommand.SendType.Text, (parse("seed", *ACCOUNT_ARGS, "--send", "text") as BwCommand.Seed).send)
        assertInvalid("text or file", "seed", *ACCOUNT_ARGS, "--send", "image")
        assertInvalid("whole number", "seed", *ACCOUNT_ARGS, "--logins", "-1")
        assertInvalid("at least one login", "seed", *ACCOUNT_ARGS, "--logins", "0", "--attachment")
    }

    @Test
    fun `totp code org send and attach parse`() {
        assertEquals(BwCommand.Totp(ACCOUNT), parse("totp", *ACCOUNT_ARGS))
        assertEquals(
            BwCommand.Code("JBSWY3DPEHPK3PXP", json = true),
            parse("code", "--secret", "JBSWY3DPEHPK3PXP", "--json"),
        )
        assertEquals(BwCommand.Org(ACCOUNT, name = "Acme"), parse("org", *ACCOUNT_ARGS, "--name", "Acme"))
        assertEquals(
            BwCommand.Org(ACCOUNT, name = "Acme", member = "bob@keyguard.test"),
            parse("org", *ACCOUNT_ARGS, "--name", "Acme", "--member", "bob@keyguard.test"),
        )
        assertEquals(BwCommand.Send(ACCOUNT, text = "Hello"), parse("send", *ACCOUNT_ARGS, "--text", "Hello"))
        assertEquals(
            BwCommand.Send(ACCOUNT, text = "Hello", sendPassword = "open sesame"),
            parse("send", *ACCOUNT_ARGS, "--text", "Hello", "--password-protect", "open sesame"),
        )
        assertEquals(
            BwCommand.Attach(ACCOUNT, cipherId = "c1", file = "/tmp/a.txt"),
            parse("attach", *ACCOUNT_ARGS, "--cipher", "c1", "--file", "/tmp/a.txt"),
        )
    }

    @Test
    fun `server loses its trailing slash and must be http`() {
        val command = parse("totp", "--server", "http://127.0.0.1:8000/", "--email", EMAIL, "--password", PASSWORD)
        assertEquals(BwCommand.Totp(ACCOUNT), command)
        assertInvalid("http", "totp", "--server", "127.0.0.1:8000", "--email", EMAIL, "--password", PASSWORD)
    }

    @Test
    fun `missing and unknown input is a usage error`() {
        assertInvalid("Missing a command", *emptyArray())
        assertInvalid("Unknown command: launch", "launch")
        assertInvalid("needs --password", "seed", "--server", SERVER, "--email", EMAIL)
        assertInvalid("needs --name", "org", *ACCOUNT_ARGS)
        assertInvalid("needs --secret", "code")
        assertInvalid("needs a value", "code", "--secret")
        assertInvalid("Unknown option for code: --server", "code", "--secret", "S", "--server", SERVER)
        assertInvalid("Unknown option for totp: --argon2", "totp", *ACCOUNT_ARGS, "--argon2")
        assertInvalid("Unexpected argument: extra", "totp", *ACCOUNT_ARGS, "extra")
    }

    @Test
    fun `help is not an error`() {
        listOf("help", "--help", "-h").forEach { arg ->
            assertEquals(BwCommand.Help, parse(arg))
        }
    }

    @Test
    fun `cli exits with 2 and the usage on a usage error`() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val status = BwCli.run(listOf("launch"), PrintStream(out), PrintStream(err))
        assertEquals(BwCli.EXIT_USAGE, status)
        assertEquals("", out.toString())
        assertContains(err.toString(), "Unknown command: launch")
        assertContains(err.toString(), "Usage: bw")
    }

    private fun parse(vararg args: String): BwCommand = BwArgs.parse(args.toList())

    private fun assertInvalid(message: String, vararg args: String) {
        val command = assertIs<BwCommand.Invalid>(parse(*args), args.joinToString(" "))
        assertContains(command.message, message)
    }

    private companion object {
        const val SERVER = "http://127.0.0.1:8000"
        const val EMAIL = "alice@keyguard.test"
        const val PASSWORD = "correct horse battery staple"
        val ACCOUNT = BwAccount(server = SERVER, email = EMAIL, password = PASSWORD)
        val ACCOUNT_ARGS = arrayOf("--server", SERVER, "--email", EMAIL, "--password", PASSWORD)
    }
}
