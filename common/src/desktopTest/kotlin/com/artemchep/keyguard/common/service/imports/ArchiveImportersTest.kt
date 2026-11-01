package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.util.zip.ZipConfig
import com.artemchep.keyguard.util.zip.ZipEntry
import com.artemchep.keyguard.util.zip.createZipService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import kotlinx.io.readString

/**
 * Every fixture here is written by hand.
 */
class ArchiveImportersTest {
    @Test
    fun `1password 1pux preserves trashed items and their attachments across categories`() = runTest {
        val archive = zip(
            "export.data" to ONE_PUX_EXPORT,
            "files/u2ofk6wq3hzr7mcx4tbj5pldna__codes.txt" to "backup codes",
            "files/g7vb3ksl5qwe2hxn6rzt4mcypa__passport.pdf" to "%PDF",
        )
        val original = parse(ImportFormatId.OnePassword, archive)
        val trashedExport = ONE_PUX_EXPORT
            .replace("\"state\": \"active\"", "\"state\": \"trashed\"")
            .replace("\"state\": \"archived\"", "\"state\": \"trashed\"")
        val trashedArchive = zip(
            "export.data" to trashedExport,
            "files/u2ofk6wq3hzr7mcx4tbj5pldna__codes.txt" to "backup codes",
            "files/g7vb3ksl5qwe2hxn6rzt4mcypa__passport.pdf" to "%PDF",
        )
        val trashed = parse(ImportFormatId.OnePassword, trashedArchive)
        assertEquals(7, trashed.items.size)
        assertTrue(original.items.none { it.trashed })
        assertEquals(original.items.map { it.copy(archived = false, trashed = true) }, trashed.items)
        assertEquals(original.folders, trashed.folders)
        assertEquals(original.skips, trashed.skips)
        trashed.assertVaultStorable()
    }

    @Test
    fun `1password 1pux maps vaults, categories, sections and files`() = runTest {
        val archive = zip(
            "export.attributes" to
                """{"version":3,"description":"1Password Unencrypted Export","createdAt":1700001000}""",
            "export.data" to ONE_PUX_EXPORT,
            "files/u2ofk6wq3hzr7mcx4tbj5pldna__codes.txt" to "backup codes",
            "files/g7vb3ksl5qwe2hxn6rzt4mcypa__passport.pdf" to "%PDF",
        )
        val plan = parse(ImportFormatId.OnePassword, archive)
        plan.assertVaultStorable()
        assertEquals(listOf("Personal"), plan.folders.map { it.title })
        val byTitle = plan.items.associateBy { it.request.title }

        val mail = byTitle.getValue("Mail")
        val login = mail.request
        assertEquals(DSecret.Type.Login, login.type)
        assertEquals("sam", login.login.username)
        assertEquals("s3cret-pass", login.login.password)
        // An empty one-time password field comes first and must not win.
        assertEquals("JBSWY3DPEHPK3PXP", login.login.totp)
        assertEquals(listOf("https://mail.example.org/"), login.uris.map { it.uri })
        assertEquals(true, login.favorite)
        assertEquals(listOf("work"), login.tags)
        assertTrue(login.fields.isEmpty(), login.fields.toString())
        assertEquals(
            listOf(ImportPlan.Attachment("files/u2ofk6wq3hzr7mcx4tbj5pldna__codes.txt", "codes.txt", 42)),
            mail.attachments,
        )

        val card = byTitle.getValue("Visa").request
        assertEquals(DSecret.Type.Card, card.type)
        assertEquals("Mastercard", card.card.brand)
        assertEquals("5555555555554444", card.card.number)
        assertEquals("12", card.card.expMonth)
        assertEquals("2030", card.card.expYear)
        assertEquals(
            listOf(DSecret.Field("PIN", "0000", type = DSecret.Field.Type.Hidden)),
            card.fields,
        )

        val identity = byTitle.getValue("Me").request
        assertEquals("Sam", identity.identity.firstName)
        assertEquals("Q", identity.identity.middleName)
        assertEquals("1 Main St", identity.identity.address1)
        assertEquals("Springfield", identity.identity.city)
        assertEquals("12345", identity.identity.postalCode)
        assertEquals("555-0100", identity.identity.phone)
        assertEquals("sam@example.org", identity.identity.email)
        assertEquals(
            listOf("birth date" to "1990-01-01"),
            identity.fields.map { it.name to it.value },
        )

        assertEquals("only-a-password", byTitle.getValue("Router admin").request.login.password)

        val document = byTitle.getValue("Passport scan")
        assertEquals(DSecret.Type.SecureNote, document.request.type)
        assertEquals("passport.pdf", document.attachments.single().name)

        val ssh = byTitle.getValue("Deploy key").request
        assertEquals(DSecret.Type.SshKey, ssh.type)
        assertTrue(ssh.sshKey.privateKey!!.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))

        assertEquals(true, byTitle.getValue("Old forum").archived)
        assertEquals(1, plan.skips[ImportSkipReason.PasswordHistory])
        assertTrue(plan.skips[ImportSkipReason.Attachment] == 0, plan.skips.toString())

    }

    @Test
    fun `1password keeps all non-login OTPs as hidden fields`() = runTest {
        val categories = mapOf(
            "002" to DSecret.Type.Card,
            "003" to DSecret.Type.SecureNote,
            "004" to DSecret.Type.Identity,
            "114" to DSecret.Type.SshKey,
            "006" to DSecret.Type.SecureNote,
        )
        for ((category, type) in categories) {
            val plan = parse(ImportFormatId.OnePassword, onePasswordOtpArchive(category))
            plan.assertVaultStorable()
            val request = plan.items.single().request
            assertEquals(type, request.type, category)
            assertNull(request.login.totp, category)
            assertEquals(
                listOf(
                    DSecret.Field("Primary OTP", "JBSWY3DPEHPK3PXP", type = DSecret.Field.Type.Hidden),
                    DSecret.Field("Backup OTP", "GEZDGNBVGY3TQOJQ", type = DSecret.Field.Type.Hidden),
                ),
                request.fields,
                category,
            )
            assertEquals(0, plan.skips[ImportSkipReason.Otp], category)
        }
    }

    @Test
    fun `1password login and password consume only the first nonblank OTP`() = runTest {
        for (category in listOf("001", "005")) {
            val plan = parse(ImportFormatId.OnePassword, onePasswordOtpArchive(category))
            val request = plan.items.single().request
            assertEquals(DSecret.Type.Login, request.type, category)
            assertEquals("JBSWY3DPEHPK3PXP", request.login.totp, category)
            assertEquals(
                listOf(DSecret.Field("Backup OTP", "GEZDGNBVGY3TQOJQ", type = DSecret.Field.Type.Hidden)),
                request.fields,
                category,
            )
            assertEquals(0, plan.skips[ImportSkipReason.Otp], category)
        }
    }

    @Test
    fun `1password counts an invalid first login OTP and preserves additional OTPs`() = runTest {
        for (category in listOf("001", "005")) {
            val archive = onePasswordOtpArchive(category, firstOtp = "invalid!")
            val plan = parse(ImportFormatId.OnePassword, archive)
            val request = plan.items.single().request
            assertNull(request.login.totp, category)
            assertEquals(
                listOf(DSecret.Field("Backup OTP", "GEZDGNBVGY3TQOJQ", type = DSecret.Field.Type.Hidden)),
                request.fields,
                category,
            )
            assertEquals(1, plan.skips[ImportSkipReason.Otp], category)
        }
    }

    @Test
    fun `1password attachments are extracted from the archive`() = runTest {
        val archive = zip(
            "export.data" to ONE_PUX_EXPORT,
            "files/u2ofk6wq3hzr7mcx4tbj5pldna__codes.txt" to "backup codes",
        )
        val service = importTestService()
        val ref = "files/u2ofk6wq3hzr7mcx4tbj5pldna__codes.txt"
        val files = service.extractAttachments(
            format = ImportFormatId.OnePassword,
            input = BytesImportInput(archive),
            password = null,
            refs = setOf(ref),
        ) { _, source -> source.readString() }
        assertEquals("backup codes", files.getValue(ref))
    }

    @Test
    fun `an archive without export data fails the 1password import`() = runTest {
        val result = importTestService().parse(
            format = ImportFormatId.OnePassword,
            input = BytesImportInput(zip("other.json" to "{}")),
            now = IMPORT_TEST_NOW,
        )
        assertEquals(ImportResult.Failure(ImportError.Parse), result)
    }

    @Test
    fun `proton pass reads the json inside its archive and refuses pgp`() = runTest {
        val plain = zip(
            "Proton Pass/data.json" to """
                {"encrypted": false, "vaults": {"s": {"name": "Personal", "items": [
                  {"state": 1, "data": {"metadata": {"name": "Note"}, "type": "note", "content": {}}}
                ]}}}
            """.trimIndent(),
        )
        assertEquals(listOf("Note"), parse(ImportFormatId.ProtonPass, plain).items.map { it.request.title })

        val encrypted = zip("Proton Pass/data.pgp" to "-----BEGIN PGP MESSAGE-----")
        assertEquals(
            ImportResult.Failure(ImportError.UnsupportedEncryption),
            importTestService().parse(
                format = ImportFormatId.ProtonPass,
                input = BytesImportInput(encrypted),
                now = IMPORT_TEST_NOW,
            ),
        )
    }

    @Test
    fun `dashlane reads every csv of its archive`() = runTest {
        val archive = zip(
            "credentials.csv" to "username,username2,username3,title,password,note,url,category,otpUrl\r\n" +
                "alice@site.test,,,Site,secret,,https://site.test,,\r\n",
            "securenotes.csv" to "title,note,category\r\nDiary,dear diary,Personal\r\n",
            "payments.csv" to
                "type,account_name,account_holder,cc_number,code,expiration_month,expiration_year," +
                "routing_number,account_number,country,issuing_bank,note,name\r\n" +
                "bank,Savings,Alice Example,,,,,DEUTDEFF,DE89370400440532013000,DE,DE-DEUTSCHE,,\r\n" +
                "payment_card,Alice Example,,4111111111111111,123,03,2030,,,US,,card note,Visa\r\n",
            "ids.csv" to "type,number,name,issue_date,expiration_date,place_of_issue,state\r\n" +
                "passport,X1234567,Alice Example,2020-1-5,2030-1-4,Berlin,\r\n",
            "personalInfo.csv" to
                "type,title,first_name,middle_name,last_name,login,date_of_birth,place_of_birth,email," +
                "email_type,item_name,phone_number,address,country,state,city,zip,address_recipient," +
                "address_building,address_apartment,address_floor,address_door_code,job_title,url\r\n" +
                "name,MS,Alice,,Example,alicex,1990-02-03,Paris,,,,,,,,,,,,,,,,\r\n",
            "__MACOSX/._credentials.csv" to "\u0000\u0005junk",
        )
        val plan = parse(ImportFormatId.Dashlane, archive)
        plan.assertVaultStorable()
        val byTitle = plan.items.associateBy { it.request.title }
        assertEquals("secret", byTitle.getValue("Site").request.login.password)
        assertEquals(listOf("Personal"), byTitle.getValue("Diary").request.tags)

        val card = byTitle.getValue("Visa").request
        assertEquals(DSecret.Type.Card, card.type)
        assertEquals("Alice Example", card.card.cardholderName)
        assertEquals("3", card.card.expMonth)
        assertEquals("card note", card.note)

        val bank = byTitle.getValue("Savings").request
        assertEquals(DSecret.Type.SecureNote, bank.type)
        assertEquals(
            DSecret.Field("Account number", "DE89370400440532013000", type = DSecret.Field.Type.Hidden),
            bank.fields.single { it.name == "Account number" },
        )

        val passport = byTitle.getValue("Passport (Alice Example)").request
        assertEquals(DSecret.Field.Type.Hidden, passport.fields.single { it.name == "number" }.type)

        assertEquals(DSecret.Type.SecureNote, byTitle.getValue("Alice Example").request.type)
        assertEquals(6, plan.items.size)
    }

    private suspend fun onePasswordOtpArchive(
        category: String,
        firstOtp: String = "JBSWY3DPEHPK3PXP",
    ): ByteArray {
        val sshField = if (category == "114") {
            """, {"value": {"sshKey": {"privateKey": "test key"}}}"""
        } else {
            ""
        }
        return zip(
            "export.data" to """
                {"accounts": [{"vaults": [{"items": [{
                  "categoryUuid": "$category", "overview": {"title": "OTP item"},
                  "details": {"sections": [{"fields": [
                    {"title": "Empty OTP", "value": {"totp": " "}},
                    {"title": "Primary OTP", "value": {"totp": "$firstOtp"}},
                    {"title": "Backup OTP", "value": {"totp": "GEZDGNBVGY3TQOJQ"}}
                    $sshField
                  ]}]}
                }]}]}]}
            """.trimIndent(),
        )
    }

}

private suspend fun parse(
    format: ImportFormatId,
    archive: ByteArray,
): ImportPlan {
    val result = importTestService().parse(
        format = format,
        input = BytesImportInput(archive),
        now = IMPORT_TEST_NOW,
    )
    return assertIs<ImportResult.Success>(result).plan
}

private suspend fun zip(
    vararg entries: Pair<String, String>,
): ByteArray {
    val buffer = Buffer()
    createZipService().zip(
        outputStream = buffer,
        config = ZipConfig(),
        entries = entries.map { (name, text) ->
            ZipEntry(
                name = name,
                data = ZipEntry.Data.In {
                    Buffer().apply { writeString(text) } as Source
                },
            )
        },
    )
    return buffer.readByteArray()
}

private val ONE_PUX_EXPORT = """
{"accounts": [{"attrs": {"accountName": "Sam", "name": "Sam", "avatar": "", "email": "sam@example.org",
   "uuid": "Q7XKM2PLRT4VB6NJWE3HZ5DCYA", "domain": "https://my.1password.com/"},
 "vaults": [{"attrs": {"uuid": "r4kq2m7xh3pwbn6tjc5lzdfgva", "desc": "", "avatar": "", "name": "Personal", "type": "P"},
  "items": [
   {"uuid": "i1", "favIndex": 1, "createdAt": 1700000000, "updatedAt": 1700000600, "state": "active", "categoryUuid": "001",
    "overview": {"subtitle": "sam", "title": "Mail", "url": "https://mail.example.org/",
     "urls": [{"label": "website", "url": "https://mail.example.org/", "mode": "default"}], "tags": ["work"], "ps": 74},
    "details": {"loginFields": [
       {"value": "s3cret-pass", "id": "", "name": "password", "fieldType": "P", "designation": "password"},
       {"value": "sam", "id": "", "name": "username", "fieldType": "T", "designation": "username"}],
     "notesPlain": "", "passwordHistory": [{"value": "older-pass", "time": 1690000000}],
     "sections": [{"title": "", "name": "add more", "fields": [
       {"title": "empty TOTP", "id": "TOTP_h4xq7mzk2pwr5tvc3ndj6lbsea", "value": {"totp": ""}},
       {"title": "one-time password", "id": "TOTP_c6wq2ztm5kxr3hvb7njd4lpsea", "value": {"totp": "JBSWY3DPEHPK3PXP"}},
       {"title": "backup codes", "id": "p3ow5ie7ru2ty4qa6sz3dx5fca",
        "value": {"file": {"fileName": "codes.txt", "documentId": "u2ofk6wq3hzr7mcx4tbj5pldna", "decryptedSize": 42}}}]}]}},
   {"uuid": "i2", "favIndex": 0, "createdAt": 1700000000, "updatedAt": 1700000000, "state": "active", "categoryUuid": "002",
    "overview": {"subtitle": "", "title": "Visa", "url": ""},
    "details": {"loginFields": [], "notesPlain": "", "passwordHistory": [], "sections": [
     {"title": "", "fields": [
       {"title": "cardholder name", "id": "cardholder", "value": {"string": ""}},
       {"title": "type", "id": "type", "value": {"menu": "mc"}},
       {"title": "number", "id": "ccnum", "value": {"creditCardNumber": "5555555555554444"}},
       {"title": "verification number", "id": "cvv", "value": {"concealed": "123"}},
       {"title": "expiry date", "id": "expiry", "value": {"monthYear": 203012}},
       {"title": "valid from", "id": "validFrom", "value": {"monthYear": null}}]},
     {"title": "Additional Details", "name": "details", "fields": [
       {"title": "PIN", "id": "pin", "value": {"concealed": "0000"}},
       {"title": "credit limit", "id": "creditLimit", "value": {"string": ""}}]}]}},
   {"uuid": "i3", "favIndex": 0, "createdAt": 1700000000, "updatedAt": 1700000000, "state": "active", "categoryUuid": "004",
    "overview": {"subtitle": "Sam Doe", "title": "Me", "url": ""},
    "details": {"loginFields": [], "notesPlain": "", "passwordHistory": [], "sections": [
     {"title": "Identification", "name": "name", "fields": [
       {"title": "first name", "id": "firstname", "value": {"string": "Sam"}},
       {"title": "initial", "id": "initial", "value": {"string": "Q"}},
       {"title": "last name", "id": "lastname", "value": {"string": "Doe"}},
       {"title": "birth date", "id": "birthdate", "value": {"date": 631152000}}]},
     {"title": "Address", "name": "address", "fields": [
       {"title": "address", "id": "address", "value": {"address": {"street": "1 Main St", "city": "Springfield",
        "country": "us", "zip": "12345", "state": "IL"}}},
       {"title": "default phone", "id": "defphone", "value": {"phone": "555-0100"}},
       {"title": "cell", "id": "cellphone", "value": {"phone": ""}}]},
     {"title": "Internet Details", "name": "internet", "fields": [
       {"title": "username", "id": "username", "value": {"string": "samdoe"}},
       {"title": "email", "id": "email", "value": {"string": "sam@example.org"}}]}]}},
   {"uuid": "i4", "favIndex": 0, "createdAt": 1700000000, "updatedAt": 1700000000, "state": "active", "categoryUuid": "005",
    "overview": {"subtitle": "", "title": "Router admin", "url": ""},
    "details": {"loginFields": [], "notesPlain": "", "password": "only-a-password", "passwordHistory": [], "sections": []}},
   {"uuid": "i5", "favIndex": 0, "createdAt": 1700000000, "updatedAt": 1700000000, "state": "active", "categoryUuid": "006",
    "overview": {"subtitle": "", "title": "Passport scan", "url": ""},
    "details": {"loginFields": [], "notesPlain": "", "passwordHistory": [], "sections": [],
     "documentAttributes": {"fileName": "passport.pdf", "documentId": "g7vb3ksl5qwe2hxn6rzt4mcypa", "decryptedSize": 2048}}},
   {"uuid": "i6", "favIndex": 0, "createdAt": 1700000000, "updatedAt": 1700000000, "state": "active", "categoryUuid": "114",
    "overview": {"subtitle": "", "title": "Deploy key", "url": ""},
    "details": {"loginFields": [], "passwordHistory": [], "sections": [{"title": "", "hideAddAnotherField": true, "fields": [
      {"title": "private key", "id": "private_key", "value": {"sshKey": {"privateKey": "-----BEGIN PRIVATE KEY-----\\nMC4CAQ\\n-----END PRIVATE KEY-----\\n",
       "metadata": {"privateKey": "-----BEGIN PRIVATE KEY-----\\nMC4CAQ\\n-----END PRIVATE KEY-----\\n",
        "publicKey": "ssh-ed25519 AAAAC3Nza", "fingerprint": "SHA256:abc", "keyType": {"ed25519": "Ed25519"}}}}}]}]}},
   {"uuid": "i7", "favIndex": 0, "createdAt": 1700000000, "updatedAt": 1700000000, "state": "archived", "categoryUuid": "001",
    "overview": {"subtitle": "bob", "title": "Old forum", "url": "forum.example.org"},
    "details": {"loginFields": [{"value": "bob", "id": "", "name": "username", "fieldType": "T", "designation": "username"}],
     "notesPlain": "", "passwordHistory": [], "sections": []}}
  ]}]}]}
""".trimIndent()
