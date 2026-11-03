package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.TotpToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CsvImportersTest {
    @Test
    fun `chromium android apps keep only a sha256 fingerprint`() = runTest {
        // Base64url encoding of 64 zero bytes, a SHA-512 length, with and
        // without padding.
        listOf("A".repeat(86), "A".repeat(86) + "==").forEach { encoded ->
            val plan = parseImportPlan(
                format = ImportFormatId.Chromium,
                text = "name,url,username,password\nApp,android://$encoded@com.example.app/,alice,secret\n",
            )
            plan.assertVaultStorable()
            val app = plan.items.single().request.uris.single()
            assertEquals("androidapp://com.example.app", app.uri)
            assertEquals(emptyList(), app.signatures)
        }
    }

    @Test
    fun `chromium export maps logins, notes and android apps`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.Chromium,
            text = """
                name,url,username,password,note
                example.com,https://example.com/login,alice,secret,"multi
                line note"
                ,https://www.example.org/,bob,hunter2,
                Example App,android://kCyQDzpaoAX2gs-1zdGPKNAeICb8LzRFOxa4NCq0jO8=@com.example.app/,carol,pa55,
            """.trimIndent(),
        )
        plan.assertVaultStorable()
        assertEquals("Chrome", plan.source.displayName)
        assertTrue(plan.skips.isEmpty)

        val (first, second, third) = plan.items.map { it.request }
        assertEquals(DSecret.Type.Login, first.type)
        assertEquals("example.com", first.title)
        assertEquals(listOf("https://example.com/login"), first.uris.map { it.uri })
        assertEquals("alice", first.login.username)
        assertEquals("secret", first.login.password)
        assertEquals("multi\nline note", first.note)

        // No name: titled after the host.
        assertEquals("example.org", second.title)
        assertNull(second.note)

        assertEquals("Example App", third.title)
        val app = third.uris.single()
        assertEquals("androidapp://com.example.app", app.uri)
        assertEquals(
            "90:2C:90:0F:3A:5A:A0:05:F6:82:CF:B5:CD:D1:8F:28:D0:1E:20:26:FC:2F:34:45:3B:16:B8:34:2A:B4:8C:EF",
            app.signatures.single().certFingerprintSha256,
        )
    }

    @Test
    fun `chromium export without the note column still imports`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.Chromium,
            text = "name,url,username,password\nsite,https://site.test,u,p\n",
        )
        assertEquals(1, plan.items.size)
    }

    @Test
    fun `a file of another format fails the import`() = runTest {
        val result = parseImport(
            format = ImportFormatId.Chromium,
            text = "Title,Website,Login\nsite,https://site.test,u\n",
        )
        assertEquals(ImportResult.Failure(ImportError.Parse), result)
    }

    @Test
    fun `an empty file fails the import`() = runTest {
        val result = parseImport(
            format = ImportFormatId.Firefox,
            text = "",
        )
        assertEquals(ImportResult.Failure(ImportError.Parse), result)
    }

    @Test
    fun `firefox export names items after their host and skips internal logins`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.Firefox,
            text = """
                "url","username","password","httpRealm","formActionOrigin","guid","timeCreated","timeLastUsed","timePasswordChanged"
                "https://www.example.com","alice","secret",,"https://login.example.com","{1}","1","2","3"
                "https://example.org","bob","hunter2",,"https://example.org","{2}","1","2","3"
                "chrome://FirefoxAccounts","account","token","Firefox Accounts credentials",,"{3}","1","2","3"
            """.trimIndent(),
        )
        plan.assertVaultStorable()
        assertEquals(2, plan.items.size)
        assertTrue(plan.skips.isEmpty)

        val (first, second) = plan.items.map { it.request }
        assertEquals("example.com", first.title)
        assertEquals(
            listOf("https://www.example.com", "https://login.example.com"),
            first.uris.map { it.uri },
        )
        // The same origin is not repeated.
        assertEquals(listOf("https://example.org"), second.uris.map { it.uri })
    }

    @Test
    fun `safari export keeps one-time passwords and counts broken ones`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.Safari,
            text = """
                Title,URL,Username,Password,Notes,OTPAuth
                Example,https://example.com/,alice,secret,note,otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP&issuer=Example
                Broken,https://broken.test/,bob,hunter2,,otpauth://totp/Broken?secret=not-base32!
            """.trimIndent(),
        )
        plan.assertVaultStorable()
        assertEquals(2, plan.items.size)

        val (first, second) = plan.items.map { it.request }
        assertEquals(
            "otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP&issuer=Example",
            first.login.totp,
        )
        assertEquals("note", first.note)
        // The login survives; only its one-time password is dropped and counted.
        assertNull(second.login.totp)
        assertEquals("hunter2", second.login.password)
        assertEquals(1, plan.skips[ImportSkipReason.Otp])
        assertEquals(mapOf("Broken" to 1), plan.skips.titlesOf(ImportSkipReason.Otp))
    }

    @Test
    fun `bitwarden csv maps folders, flags, fields and notes`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.Bitwarden,
            text = """
                folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp
                Work/Email,1,login,Mail,a note,"PIN: 1234
                Recovery: abcd",1,"https://mail.test,androidapp://com.mail",alice,secret,JBSWY3DPEHPK3PXP
                Work,,note,Wi-Fi,the password is 42,,,,,,
                ,,card,Visa,,,,,,,
            """.trimIndent(),
        )
        plan.assertVaultStorable()
        assertEquals(listOf("Work", "Email"), plan.folders.map { it.title })
        assertEquals(plan.folders[0].key, plan.folders[1].parentKey)

        val (login, note) = plan.items
        // An export from before item archiving has no archivedDate column.
        assertEquals(listOf(false, false), plan.items.map { it.archived })
        assertEquals(plan.folders[1].key, login.folderKey)
        assertEquals(true, login.request.favorite)
        assertEquals(true, login.request.reprompt)
        assertEquals(
            listOf("https://mail.test", "androidapp://com.mail"),
            login.request.uris.map { it.uri },
        )
        assertEquals("JBSWY3DPEHPK3PXP", login.request.login.totp)
        assertEquals(
            listOf("PIN" to "1234", "Recovery" to "abcd"),
            login.request.fields.map { it.name to it.value },
        )

        assertEquals(plan.folders[0].key, note.folderKey)
        assertEquals(DSecret.Type.SecureNote, note.request.type)
        assertEquals("the password is 42", note.request.note)
        assertEquals(false, note.request.favorite)

        // The CSV format cannot carry a card; it is counted, not guessed.
        assertEquals(2, plan.items.size)
        assertEquals(1, plan.skips[ImportSkipReason.Item])
        assertEquals(mapOf("Visa" to 1), plan.skips.titlesOf(ImportSkipReason.Item))
    }

    @Test
    fun `bitwarden csv keeps archived items archived`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.Bitwarden,
            text = """
                folder,favorite,type,name,notes,fields,reprompt,archivedDate,login_uri,login_username,login_password,login_totp
                ,,login,Old forum,,,0,2026-10-26T10:48:12.345Z,https://forum.example.org,bob,older,
                ,,note,Old note,kept for later,,0,2026-10-26T10:48:13.000Z,,,,
                ,,login,Mail,,,0,,https://mail.example.com,alice,secret,
            """.trimIndent(),
        )
        plan.assertVaultStorable()
        assertEquals(
            listOf("Old forum" to true, "Old note" to true, "Mail" to false),
            plan.items.map { it.request.title to it.archived },
        )
    }

    @Test
    fun `keepassxc export drops the root group from folder paths`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.KeePassXc,
            text = """
                "Group","Title","Username","Password","URL","Notes","TOTP","Icon","Last Modified","Created"
                "Root","Top","u1","p1","https://top.test","","","0","2024-01-01T00:00:00Z","2024-01-01T00:00:00Z"
                "Root/Banking/Cards","Bank","u2","p2","https://bank.test","n","otpauth://totp/Bank?secret=JBSWY3DPEHPK3PXP","0","2024-01-01T00:00:00Z","2024-01-01T00:00:00Z"
            """.trimIndent(),
        )
        plan.assertVaultStorable()
        assertEquals(listOf("Banking", "Cards"), plan.folders.map { it.title })

        val (top, bank) = plan.items
        assertNull(top.folderKey)
        assertEquals(plan.folders[1].key, bank.folderKey)
        assertEquals("otpauth://totp/Bank?secret=JBSWY3DPEHPK3PXP", bank.request.login.totp)
    }

    @Test
    fun `keepassxc export preserves steam otp configuration`() = runTest {
        val raw = "otpauth://totp/Steam:alice?secret=JBSWY3DPEHPK3PXP&period=30&digits=5&issuer=Steam&encoder=steam"
        val plan = parseImportPlan(
            format = ImportFormatId.KeePassXc,
            text = "Group,Title,Username,Password,URL,Notes,TOTP\n" +
                "Root,Steam,alice,secret,https://store.steampowered.com,,$raw\n",
        )
        plan.assertVaultStorable()
        assertTrue(plan.skips.isEmpty)
        val imported = assertNotNull(plan.items.single().request.login.totp)
        assertEquals(raw, imported)
        assertIs<TotpToken.SteamAuth>(TotpToken.parse(imported).getOrNull())
    }

    @Test
    fun `keepassxc export counts unusable steam otps and keeps their logins`() = runTest {
        val raw = "otpauth://totp/Account?encoder=steam&secret=JBSWY3DPEHPK3PXP"
        val plan = parseImportPlan(
            format = ImportFormatId.KeePassXc,
            text = """
                Group,Title,Username,Password,URL,Notes,TOTP
                Root,Digits,alice,secret,https://example.com,,$raw&digits=6
                Root,Period,alice,secret,https://example.com,,$raw&period=60
                Root,Secret,alice,secret,https://example.com,,otpauth://totp/Account?encoder=steam&secret=not-base32!
            """.trimIndent(),
        )
        plan.assertVaultStorable()
        assertEquals(3, plan.items.size)
        assertEquals(3, plan.skips[ImportSkipReason.Otp])
        plan.items.forEach {
            assertNull(it.request.login.totp)
            assertEquals("secret", it.request.login.password)
        }
    }

    @Test
    fun `semicolon separated files from spreadsheets are read`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.Chromium,
            text = "name;url;username;password;note\nsite;https://site.test;u;p;\n",
        )
        assertEquals("site", plan.items.single().request.title)
    }

    @Test
    fun `a record with an unterminated quote is counted, earlier ones survive`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.Chromium,
            text = "name,url,username,password,note\nok,https://ok.test,u,p,\nbad,https://bad.test,u,\"p,\n",
        )
        assertEquals(listOf("ok"), plan.items.map { it.request.title })
        assertEquals(1, plan.skips[ImportSkipReason.MalformedRecord])
        assertTrue(plan.skips.titlesOf(ImportSkipReason.MalformedRecord).isEmpty())
    }

    @Test
    fun `rows of empty cells are ignored without a count`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.Chromium,
            text = "name,url,username,password,note\nsite,https://site.test,u,p,\n,,,,\n,,,,\n",
        )
        assertEquals(1, plan.items.size)
        assertTrue(plan.skips.isEmpty)
    }

    @Test
    fun `passwords keep their surrounding whitespace`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.Chromium,
            text = "name,url,username,password,note\nsite,https://site.test,u,\" spaced \",\n",
        )
        assertEquals(" spaced ", plan.items.single().request.login.password)
    }

    @Test
    fun `every format is listed once, in declaration order`() {
        val formats = importTestService().formats
        assertEquals(ImportFormatId.entries.toList(), formats.map { it.id })
        formats.forEach { format ->
            assertTrue(format.title.isNotBlank())
            assertTrue(format.extensions.isNotEmpty())
        }
    }
}
