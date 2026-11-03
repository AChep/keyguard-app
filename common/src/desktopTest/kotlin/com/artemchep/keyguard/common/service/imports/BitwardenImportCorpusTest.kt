package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.model.DSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * The Bitwarden files of the [ImportCorpus], written by the Bitwarden CLI.
 */
class BitwardenImportCorpusTest {
    @Test
    fun `json keeps a login with every member`() = runTest {
        val plan = ImportCorpus.successPlan("bitwarden-json")
        assertEquals(listOf("Work", "Email"), plan.folders.map { it.title })
        assertEquals(plan.folders[0].key, plan.folders[1].parentKey)

        val mail = plan.items.single { it.request.title == "Mail" }
        assertEquals(plan.folders[1].key, mail.folderKey)
        with(mail.request) {
            assertEquals(DSecret.Type.Login, type)
            assertEquals(true, favorite)
            assertEquals(true, reprompt)
            assertEquals("First line\nSecond line", note)
            assertEquals(
                listOf(
                    DSecret.Uri("https://mail.example.com", DSecret.Uri.MatchType.Host),
                    DSecret.Uri("androidapp://com.example.mail", null),
                ),
                uris,
            )
            assertEquals("alice@example.com", login.username)
            assertEquals("correct horse battery staple", login.password)
            assertEquals(MAIL_TOTP, login.totp)
            assertEquals(listOf("mail.example.com"), fido2Credentials.map { it.rpId })
            assertEquals(
                listOf(
                    DSecret.Field(name = "PIN", value = "1234", type = DSecret.Field.Type.Text),
                    DSecret.Field(name = "Recovery", value = "abcd-efgh", type = DSecret.Field.Type.Hidden),
                    DSecret.Field(name = "Remember me", value = "true", type = DSecret.Field.Type.Boolean),
                    DSecret.Field(
                        name = "Login",
                        linkedId = DSecret.Field.LinkedId.Login_Username,
                        type = DSecret.Field.Type.Linked,
                    ),
                ),
                fields,
            )
        }
    }

    @Test
    fun `json keeps notes, cards, identities and ssh keys`() = runTest {
        val plan = ImportCorpus.successPlan("bitwarden-json")
        val byTitle = plan.items.associateBy { it.request.title }

        val wifi = byTitle.getValue("Wi-Fi")
        assertEquals(plan.folders[0].key, wifi.folderKey)
        assertEquals(DSecret.Type.SecureNote, wifi.request.type)
        assertEquals("The password is \"42\", with a comma.", wifi.request.note)

        with(byTitle.getValue("Visa").request) {
            assertEquals(DSecret.Type.Card, type)
            assertEquals("Alice Example", card.cardholderName)
            assertEquals("4111111111111111", card.number)
            assertEquals("12", card.expMonth)
            assertEquals("2030", card.expYear)
            assertEquals("123", card.code)
        }
        with(byTitle.getValue("Home").request) {
            assertEquals(DSecret.Type.Identity, type)
            assertEquals("Alice", identity.firstName)
            assertEquals("Springfield", identity.city)
            assertEquals("+1 555 0100", identity.phone)
        }
        with(byTitle.getValue("Server key").request) {
            assertEquals(DSecret.Type.SshKey, type)
            assertEquals(sshCorpusText("openssh/id_ed25519.pub").trim(), sshKey.publicKey)
        }
        with(byTitle.getValue("Untitled site").request) {
            assertEquals(" spaced \"and\" quoted, ", login.password)
            assertEquals("Ünïcödé — 日本語", note)
        }
    }

    @Test
    fun `csv keeps the logins and notes it carries`() = runTest {
        val plan = ImportCorpus.successPlan("bitwarden-csv")
        assertEquals(listOf("Work", "Email"), plan.folders.map { it.title })
        val byTitle = plan.items.associateBy { it.request.title }

        val mail = byTitle.getValue("Mail")
        assertEquals(plan.folders[1].key, mail.folderKey)
        with(mail.request) {
            assertEquals(true, favorite)
            assertEquals(true, reprompt)
            assertEquals("First line\nSecond line", note)
            assertEquals(
                listOf("https://mail.example.com", "androidapp://com.example.mail"),
                uris.map { it.uri },
            )
            assertEquals("correct horse battery staple", login.password)
            assertEquals(MAIL_TOTP, login.totp)
            assertEquals(
                listOf(
                    "PIN" to "1234",
                    "Recovery" to "abcd-efgh",
                    "Remember me" to "true",
                    "Login" to "undefined",
                ),
                fields.map { it.name to it.value },
            )
        }
        assertEquals(plan.folders[0].key, byTitle.getValue("Wi-Fi").folderKey)
        assertEquals(" spaced \"and\" quoted, ", byTitle.getValue("Untitled site").request.login.password)
    }

    @Test
    fun `archived items stay archived`() = runTest {
        listOf("bitwarden-json", "bitwarden-csv").forEach { id ->
            val plan = ImportCorpus.successPlan(id)
            assertEquals(
                setOf("Old forum", "Old note"),
                plan.items.filter { it.archived }.map { it.request.title }.toSet(),
                id,
            )
        }
    }

    @Test
    fun `protected exports decrypt to the same vault`() = runTest {
        val plain = ImportCorpus.successPlan("bitwarden-json")
        val protected = ImportCorpus.successPlan("bitwarden-protected-pbkdf2")
        assertEquals(plain.folders.map { it.title }, protected.folders.map { it.title })
        assertEquals(plain.items.map { it.request }, protected.items.map { it.request })

        val argon2 = ImportCorpus.successPlan("bitwarden-protected-argon2id")
        assertEquals(listOf("Argon2 account note"), argon2.items.map { it.request.title })
    }

    private fun sshCorpusText(path: String): String = javaClass.classLoader
        .getResourceAsStream("ssh-import-corpus/$path")
        .let { requireNotNull(it) { "Missing SSH corpus resource $path" } }
        .use { it.readBytes().decodeToString() }
}

private const val MAIL_TOTP = "otpauth://totp/Example:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example"
