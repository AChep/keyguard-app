package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.test.FakeSshKeyImportService
import com.artemchep.keyguard.test.sshKeyImportFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Every fixture here is written by hand.
 */
class PasswordManagerImportersTest {
    @Test
    fun `lastpass maps logins, cards, addresses and multi-line notes`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.LastPass,
            text = LASTPASS_EXPORT,
            sshKeyImportService = FakeSshKeyImportService(result = sshKeyImportFailure()),
        )
        plan.assertVaultStorable()
        assertEquals(
            listOf("Personal", "Email", "Shared-Family", "Finance", "Servers", "Archive"),
            plan.folders.map { it.title },
        )
        val byTitle = plan.items.associateBy { it.request.title }

        val login = byTitle.getValue("Ana & Co Mail")
        assertEquals(plan.folders[1].key, login.folderKey)
        assertEquals(true, login.request.favorite)
        assertEquals("p@ss,word", login.request.login.password)
        assertEquals("JBSWY3DPEHPK3PXP", login.request.login.totp)

        val card = byTitle.getValue("Travel Card").request
        assertEquals(DSecret.Type.Card, card.type)
        assertEquals("Ana Lima", card.card.cardholderName)
        assertEquals("Mastercard", card.card.brand)
        assertEquals("321", card.card.code)
        assertEquals("11", card.card.expMonth)
        assertEquals("2029", card.card.expYear)
        assertEquals("travel card\nbackup in the drawer", card.note)
        // An empty start date and the form's language are no data.
        assertTrue(card.fields.isEmpty(), card.fields.toString())

        val ssh = byTitle.getValue("Build key").request
        assertEquals(DSecret.Type.SecureNote, ssh.type)
        val privateKey = ssh.fields.single { it.name == "Private Key" }
        assertEquals(DSecret.Field.Type.Hidden, privateKey.type)
        assertEquals(
            "-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\n" +
                "DEK-Info: AES-128-CBC,00112233445566778899AABBCCDDEEFF\n" +
                "AAAAfakebase64bodyAAAA\n-----END RSA PRIVATE KEY-----",
            privateKey.value,
        )
        assertEquals("ssh-rsa AAAAfakeAAAA ana@laptop", ssh.fields.single { it.name == "Public Key" }.value)

        val address = byTitle.getValue("Home").request
        assertEquals(DSecret.Type.Identity, address.type)
        assertEquals("Ana", address.identity.firstName)
        assertEquals("Springfield", address.identity.city)
        assertEquals("+1 555 0100 ext. 12", address.identity.phone)

        // A folder row and a `(none)` group plan no item and no folder.
        assertNull(byTitle["Archive"])
        assertNull(byTitle.getValue("Loose").folderKey)
    }

    @Test
    fun `1password csv maps favorites, tags and archived items`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.OnePassword,
            text = """
                Title,Url,Username,Password,OTPAuth,Favorite,Archived,Tags,Notes
                Mail,https://mail.example.org/,sam,s3cret-pass,otpauth://totp/Mail:sam?secret=JBSWY3DPEHPK3PXP&issuer=Mail,true,false,"work;personal, home","line one
                line two"
                Router admin,,,only-a-password,,false,false,,
                Old forum,forum.example.org,bob,older-pass,,false,true,Starter Kit,
            """.trimIndent(),
        )
        plan.assertVaultStorable()
        val (mail, router, forum) = plan.items
        assertEquals(true, mail.request.favorite)
        assertEquals(listOf("work", "personal, home"), mail.request.tags)
        assertEquals("line one\nline two", mail.request.note)
        assertEquals(false, mail.archived)
        assertEquals("only-a-password", router.request.login.password)
        assertEquals(true, forum.archived)
    }

    @Test
    fun `a 1password 7 csv is refused rather than read as empty logins`() = runTest {
        val result = parseImport(
            format = ImportFormatId.OnePassword,
            text = """
                Title,Type,URL,Username,Password,Notes,Tags,cardholder name(cardholder)
                Visa,Credit Card,,,,,,Ana
            """.trimIndent(),
        )
        assertEquals(ImportResult.Failure(ImportError.Parse), result)
    }

    @Test
    fun `nordpass maps every row type`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.NordPass,
            text = """
                name,url,additional_urls,username,password,note,cardholdername,cardnumber,cvc,pin,expirydate,zipcode,folder,full_name,phone_number,email,address1,address2,city,country,state,type,custom_fields
                Finance,,,,,,,,,,,,,,,,,,,,,folder,
                Legacy folder,,,,,,,,,,,,,,,,,,,,,,
                Example Bank,https://bank.example.com,"[""https://m.bank.example.com""]",ana.lima,s3cret!,Branch 12,,,,,,,Finance,,,,,,,,,password,"[{""type"":""hidden"",""label"":""Security answer"",""value"":""blue""},{""type"":""date"",""label"":""Opened"",""value"":""1704067200""}]"
                Debit card,,,,,,Ana Lima,4000056655665556,123,9876,07/28,62701,Finance,,,,,,,,,credit_card,
                Ana Lima,,,,,Home,,,,,,62701,,Ana Lima,+15550100,ana@example.org,12 Sample Road,Apt 3,Springfield,United States,Illinois,identity,
                Passport,,,,,Renew in 2030,,,,,,,,,,,,,,,,document,"[{""type"":""text"",""label"":""Document number"",""value"":""X1234567""}]"
            """.trimIndent(),
        )
        plan.assertVaultStorable()
        assertEquals(listOf("Finance", "Legacy folder"), plan.folders.map { it.title })
        assertTrue(plan.skips.isEmpty, plan.skips.toString())
        val requests = plan.items.map { it.request }
        val (login, card, identity) = requests
        val passport = requests[3]
        assertEquals(
            listOf("https://bank.example.com", "https://m.bank.example.com"),
            login.uris.map { it.uri },
        )
        assertEquals(
            listOf(
                DSecret.Field("Security answer", "blue", type = DSecret.Field.Type.Hidden),
                DSecret.Field("Opened", "2024-01-01", type = DSecret.Field.Type.Text),
            ),
            login.fields,
        )
        assertEquals("7", card.card.expMonth)
        assertEquals("2028", card.card.expYear)
        assertEquals(listOf("PIN", "ZIP code"), card.fields.map { it.name })
        assertEquals("Ana", identity.identity.firstName)
        assertEquals("Lima", identity.identity.lastName)
        assertEquals("Springfield", identity.identity.city)
        assertEquals(DSecret.Type.SecureNote, passport.type)
        assertEquals("Renew in 2030", passport.note)
        assertEquals(listOf("Document number" to "X1234567"), passport.fields.map { it.name to it.value })
    }

    @Test
    fun `keeper maps shared folders, one-time passwords and typed fields`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.Keeper,
            text = """
                "Personal\Email","Mail","ana@example.org","s3cret","https://mail.example.org","","","TFC:Keeper","otpauth://totp/Mail:ana?secret=JBSWY3DPEHPK3PXP&issuer=Mail"
                "Projects","Build","ci","pw","https://ci.example.org","","Team Vault#edit#reshare","${'$'}oneTimeCode","otpauth://totp/CI?secret=JBSWY3DPEHPK3PXP","${'$'}text:Environment","prod","${'$'}pinCode","1234"
                "Back\\slash","Odd","u","p","","","",
                Archive,Old Forum,ana,pw2,https://forum.example.net,"line one
                line two",,${'$'}record_uid,AbCdEf123456,${'$'}type,login
                short,row
            """.trimIndent(),
        )
        plan.assertVaultStorable()
        assertEquals(
            listOf("Personal", "Email", "Team Vault", "Projects", "Back\\slash", "Archive"),
            plan.folders.map { it.title },
        )
        val byTitle = plan.items.associateBy { it.request.title }
        assertEquals(
            "otpauth://totp/Mail:ana?secret=JBSWY3DPEHPK3PXP&issuer=Mail",
            byTitle.getValue("Mail").request.login.totp,
        )
        val build = byTitle.getValue("Build")
        assertEquals(plan.folders[3].key, build.folderKey)
        assertEquals("otpauth://totp/CI?secret=JBSWY3DPEHPK3PXP", build.request.login.totp)
        assertEquals(
            listOf(
                DSecret.Field("Environment", "prod", type = DSecret.Field.Type.Text),
                DSecret.Field("pinCode", "1234", type = DSecret.Field.Type.Hidden),
            ),
            build.request.fields,
        )
        val forum = byTitle.getValue("Old Forum").request
        assertTrue(forum.fields.isEmpty(), forum.fields.toString())
        assertEquals("line one\nline two", forum.note)
        // The short row is counted; the file still imports.
        assertEquals(1, plan.skips[ImportSkipReason.Item])
    }

    @Test
    fun `keeper refuses a file of too few columns`() = runTest {
        assertEquals(
            ImportResult.Failure(ImportError.Parse),
            parseImport(ImportFormatId.Keeper, "name,url\nsite,https://site.test\n"),
        )
    }

    @Test
    fun `enpass maps logins, cards and keeps other items as notes`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.Enpass,
            text = ENPASS_EXPORT,
        )
        plan.assertVaultStorable()
        assertEquals(listOf("Work", "Servers"), plan.folders.map { it.title })
        val byTitle = plan.items.associateBy { it.request.title }

        val login = byTitle.getValue("Example")
        assertEquals(plan.folders[1].key, login.folderKey)
        assertEquals(true, login.request.favorite)
        assertEquals("ana", login.request.login.username)
        assertEquals("s3cret", login.request.login.password)
        assertEquals("JBSWY3DPEHPK3PXP", login.request.login.totp)
        assertEquals(
            listOf("https://example.org", "androidapp://org.example.app", "androidapp://org.example.other"),
            login.request.uris.map { it.uri },
        )
        assertEquals(listOf("Work"), login.request.tags)
        assertEquals(1, plan.skips[ImportSkipReason.PasswordHistory])
        assertEquals(1, plan.skips[ImportSkipReason.Attachment])

        val card = byTitle.getValue("Visa").request
        assertEquals("4111111111111111", card.card.number)
        assertEquals("2030", card.card.expYear)
        assertEquals(listOf("Valid from" to "01/2024"), card.fields.map { it.name to it.value })

        // An identity holds no password: it is a note, not a login with the
        // email as its username.
        val identity = byTitle.getValue("Me").request
        assertEquals(DSecret.Type.SecureNote, identity.type)
        assertEquals(listOf("First name", "E-mail"), identity.fields.map { it.name })

        val bank = byTitle.getValue("Bank")
        assertEquals(DSecret.Type.SecureNote, bank.request.type)
        assertEquals(true, bank.archived)

        assertEquals(listOf("Deleted"), plan.items.filter { it.trashed }.map { it.request.title })
        assertEquals(0, plan.skips[ImportSkipReason.Item])
    }

    @Test
    fun `enpass preserves trashed items across categories`() = runTest {
        val original = parseImportPlan(ImportFormatId.Enpass, ENPASS_EXPORT)
        val trashed = parseImportPlan(
            ImportFormatId.Enpass,
            ENPASS_EXPORT.replace("\"trashed\": 0", "\"trashed\": 1"),
        )
        assertEquals(5, trashed.items.size)
        assertEquals(original.items.map { it.copy(trashed = true) }, trashed.items)
        assertEquals(original.folders, trashed.folders)
        assertEquals(original.skips, trashed.skips)
        trashed.assertVaultStorable()
    }

    @Test
    fun `proton pass json maps vaults, folders and item types`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.ProtonPass,
            text = PROTON_EXPORT,
        )
        plan.assertVaultStorable()
        assertEquals(listOf("Personal", "Work", "Mail"), plan.folders.map { it.title })
        val byTitle = plan.items.associateBy { it.request.title }

        val mail = byTitle.getValue("Mail")
        assertEquals(plan.folders[2].key, mail.folderKey)
        val login = mail.request
        assertEquals("alice", login.login.username)
        assertEquals(true, login.favorite)
        // The content has no one-time password, so the custom field's is used.
        assertEquals("otpauth://totp/Mail?secret=JBSWY3DPEHPK3PXP", login.login.totp)
        assertEquals(
            listOf(
                DSecret.Uri("https://mail.test/", null),
                DSecret.Uri("https://login.mail.test/", DSecret.Uri.MatchType.Host),
            ),
            login.uris,
        )
        assertEquals(
            listOf(
                DSecret.Field("Email", "alice@mail.test", type = DSecret.Field.Type.Text),
                DSecret.Field("Backup", "1111", type = DSecret.Field.Type.Hidden),
                DSecret.Field("Since", "2024-05-01", type = DSecret.Field.Type.Text),
            ),
            login.fields,
        )

        val card = byTitle.getValue("Visa").request
        assertEquals("123", card.card.code)
        assertEquals("Visa", card.card.brand)
        assertEquals("1", card.card.expMonth)
        assertEquals("2031", card.card.expYear)

        val custom = byTitle.getValue("Server").request
        assertEquals(DSecret.Type.SecureNote, custom.type)
        assertEquals(
            listOf(
                DSecret.Field("SSH: Host", "10.0.0.1", type = DSecret.Field.Type.Text),
                DSecret.Field("SSH: Root", "pw", type = DSecret.Field.Type.Hidden),
            ),
            custom.fields,
        )

        val wifi = byTitle.getValue("Home Wi-Fi").request
        assertEquals(
            listOf("SSID" to "home", "Password" to "wifipass", "Security" to "WPA2"),
            wifi.fields.map { it.name to it.value },
        )

        assertEquals(listOf("Gone"), plan.items.filter { it.trashed }.map { it.request.title })
        assertEquals(0, plan.skips[ImportSkipReason.Item])
        assertEquals(1, plan.skips[ImportSkipReason.Attachment])
    }

    @Test
    fun `a malformed proton pass login counts only an item skip`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.ProtonPass,
            text = """
                {"vaults":{"v":{"name":"Personal","items":[
                  {"files":[{}],"data":{"metadata":{"name":"Broken"},"type":"login",
                    "content":{"passkeys":[{}],"autofillUrls":[[]]}}},
                  {"data":{"metadata":{"name":"Kept"},"type":"login",
                    "content":{"username":"alice"}}}
                ]}}}
            """.trimIndent(),
        )

        assertEquals(listOf("Kept"), plan.items.map { it.request.title })
        assertEquals(importSkips(ImportSkipReason.Item to 1), plan.skips)
        assertEquals(mapOf("Broken" to 1), plan.skips.titlesOf(ImportSkipReason.Item))
        assertEquals(emptyMap(), plan.skips.titlesOf(ImportSkipReason.Attachment))
        assertEquals(emptyMap(), plan.skips.titlesOf(ImportSkipReason.Passkey))
    }

    @Test
    fun `proton pass preserves trash state for every item type`() = runTest {
        val types = listOf("login", "alias", "creditCard", "identity", "sshKey", "wifi", "note", "custom")
        for (type in types) {
            val plan = parseImportPlan(
                ImportFormatId.ProtonPass,
                """
                    {"vaults": {"v": {"name": "Personal", "items": [
                      {"state": 1, "data": {"type": "$type", "metadata": {"name": "Live", "note": "Keep"}}},
                      {"state": 2, "data": {"type": "$type", "metadata": {"name": "Gone", "note": "Keep"}}}
                    ]}}}
                """.trimIndent(),
            )
            assertEquals(listOf("Live", "Gone"), plan.items.map { it.request.title }, type)
            assertEquals(listOf(false, true), plan.items.map { it.trashed }, type)
            assertEquals(listOf("Keep", "Keep"), plan.items.map { it.request.note }, type)
            assertTrue(plan.items.all { it.folderKey == plan.folders.single().key }, type)
            assertEquals(0, plan.skips[ImportSkipReason.Item], type)
            plan.assertVaultStorable()
        }
    }

    @Test
    fun `a decrypted legacy proton pass export still says encrypted and imports`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.ProtonPass,
            text = """
                {"version": "1.10.0", "userId": "u", "encrypted": true, "vaults": {"s": {"name": "Personal",
                  "items": [{"state": 1, "data": {"metadata": {"name": "Site"}, "type": "login",
                    "extraFields": [], "content": {"username": "alice@site.test", "password": "p",
                    "urls": ["https://site.test"], "totpUri": ""}}}]}}}
            """.trimIndent(),
        )
        assertEquals("alice@site.test", plan.items.single().request.login.username)
    }

    @Test
    fun `an armored proton pass export is refused`() = runTest {
        assertEquals(
            ImportResult.Failure(ImportError.UnsupportedEncryption),
            parseImport(ImportFormatId.ProtonPass, "-----BEGIN PGP MESSAGE-----\n\nwcFMA\n"),
        )
    }

    @Test
    fun `proton pass csv maps logins and keeps other items as notes`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.ProtonPass,
            text = """
                type,name,url,autofillUrls,email,username,password,note,totp,createTime,modifyTime,vault
                login,Site,"https://site.test, https://www.site.test",,alice@site.test,alice,secret,n,,1,1,Personal
                creditCard,Visa,,,,,,"{""number"":""4111""} card note",,1,1,Personal
            """.trimIndent(),
        )
        plan.assertVaultStorable()
        val (login, card) = plan.items.map { it.request }
        assertEquals(listOf("https://site.test", "https://www.site.test"), login.uris.map { it.uri })
        assertEquals("alice", login.login.username)
        assertEquals(listOf("Email" to "alice@site.test"), login.fields.map { it.name to it.value })
        assertEquals(DSecret.Type.SecureNote, card.type)
        assertEquals("{\"number\":\"4111\"} card note", card.note)
        assertEquals(listOf("Personal"), plan.folders.map { it.title })
    }

    @Test
    fun `dashlane web credentials map collections to tags and the otp url`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.Dashlane,
            text = """
                username,username2,username3,title,password,note,url,category,otpUrl
                alice@site.test,alice,,Site,secret,"line 1
                line 2",https://site.test,"Work, Shared",otpauth://totp/?secret=jbswy3dpehpk3pxp&algorithm=SHA1&digits=6&period=30&lock=false
            """.trimIndent(),
        )
        plan.assertVaultStorable()
        val login = plan.items.single().request
        assertEquals("alice@site.test", login.login.username)
        assertEquals(listOf("Username" to "alice"), login.fields.map { it.name to it.value })
        assertEquals(listOf("Work", "Shared"), login.tags)
        assertEquals("line 1\nline 2", login.note)
        assertTrue(login.login.totp!!.startsWith("otpauth://totp/"))
        assertTrue(plan.folders.isEmpty())
    }

    @Test
    fun `dashlane android files read despite their own spelling and null values`() = runTest {
        val credentials = parseImportPlan(
            format = ImportFormatId.Dashlane,
            text = """
                user_name,user_name_2,user_name_3,title,password,note,url,category,otp
                alice@site.test,alice,,Site,secret,,https://site.test,,null
            """.trimIndent(),
        )
        val login = credentials.items.single().request
        assertEquals("alice@site.test", login.login.username)
        // The text `null` means absent, not a one-time password of "NULL".
        assertNull(login.login.totp)
        assertTrue(credentials.skips.isEmpty)

        val payments = parseImportPlan(
            format = ImportFormatId.Dashlane,
            text = """
                type,accountName,accountHolder,ccNumber,code,expirationMonth,expirationYear,routingNumber,accountNumber,country,issuingBank,note,name
                payment_card,Alice Example,,4111111111111111,123,MARCH,2030,,,,,null,Visa
            """.trimIndent(),
        )
        val card = payments.items.single().request
        assertEquals("Visa", card.title)
        assertEquals("Alice Example", card.card.cardholderName)
        assertEquals("3", card.card.expMonth)
        assertEquals("2030", card.card.expYear)
        assertNull(card.note)
    }
}

private val PROTON_EXPORT = """
{"version": "1.32.0", "userId": "u1", "vaults": {"sh1": {"name": "Personal", "description": "",
  "display": {"color": 0, "icon": 0},
  "folders": [
    {"folderId": "f1", "parentFolderId": null, "name": "Work"},
    {"folderId": "f2", "parentFolderId": "f1", "name": "Mail"}
  ],
  "items": [
    {"itemId": "i1", "shareId": "sh1", "folderId": "f2", "state": 1, "pinned": true, "aliasEmail": null,
     "contentFormatVersion": 6, "createTime": 1760000000, "modifyTime": 1760000100, "shareCount": 0, "files": [],
     "data": {"type": "login", "metadata": {"name": "Mail", "note": "n", "itemUuid": "u-1"},
      "extraFields": [
        {"fieldName": "Backup", "type": "hidden", "data": {"content": "1111"}},
        {"fieldName": "2FA", "type": "totp", "data": {"totpUri": "otpauth://totp/Mail?secret=JBSWY3DPEHPK3PXP"}},
        {"fieldName": "Since", "type": "timestamp", "data": {"timestamp": "2024-05-01"}}
      ],
      "content": {"itemEmail": "alice@mail.test", "itemUsername": "alice", "password": "secret", "totpUri": "",
        "passkeys": [], "urls": ["https://mail.test/"],
        "autofillUrls": [{"url": "https://mail.test/", "mode": 0}, {"url": "https://login.mail.test/", "mode": 1}]}}},
    {"itemId": "i2", "shareId": "sh1", "folderId": null, "state": 1, "pinned": false, "aliasEmail": null,
     "files": ["receipt.pdf"],
     "data": {"type": "creditCard", "metadata": {"name": "Visa", "note": "", "itemUuid": "u-2"}, "extraFields": [],
      "content": {"cardholderName": "Alice", "cardType": 2, "number": "4111111111111111",
        "verificationNumber": "123", "expirationDate": "2031-01", "pin": "0000"}}},
    {"itemId": "i3", "shareId": "sh1", "folderId": null, "state": 1, "pinned": false, "aliasEmail": null,
     "data": {"type": "custom", "metadata": {"name": "Server", "note": "", "itemUuid": "u-3"}, "extraFields": [],
      "content": {"sections": [{"sectionName": "SSH", "sectionFields": [
        {"fieldName": "Host", "type": "text", "data": {"content": "10.0.0.1"}},
        {"fieldName": "Root", "type": "hidden", "data": {"content": "pw"}}]}]}}},
    {"itemId": "i4", "shareId": "sh1", "folderId": null, "state": 1, "pinned": false, "aliasEmail": null,
     "data": {"type": "wifi", "metadata": {"name": "Home Wi-Fi", "note": "", "itemUuid": "u-4"}, "extraFields": [],
      "content": {"ssid": "home", "password": "wifipass", "security": 2, "sections": []}}},
    {"itemId": "i5", "shareId": "sh1", "folderId": null, "state": 2, "pinned": false, "aliasEmail": null,
     "data": {"type": "note", "metadata": {"name": "Gone", "note": "x", "itemUuid": "u-5"}, "extraFields": [],
      "content": {}}}
  ]}}}
""".trimIndent()

private val LASTPASS_EXPORT = """
url,username,password,totp,extra,name,grouping,fav
https://mail.example.org/login,ana@example.org,"p@ss,word",JBSWY3DPEHPK3PXP,recovery codes in the safe,Ana &amp; Co Mail,Personal\Email,1
http://sn,,,,"NoteType:Credit Card
Language:en-US
Name on Card:Ana Lima
Type:Mastercard
Number:5555555555554444
Security Code:321
Start Date:,
Expiration Date:November,2029
Notes:travel card
backup in the drawer",Travel Card,Shared-Family\Finance,0
http://sn,,,,"NoteType:SSH Key
Language:en-US
Bit Strength:2048
Format:RSA
Passphrase:hunter2
Private Key:-----BEGIN RSA PRIVATE KEY-----
Proc-Type: 4,ENCRYPTED
DEK-Info: AES-128-CBC,00112233445566778899AABBCCDDEEFF
AAAAfakebase64bodyAAAA
-----END RSA PRIVATE KEY-----
Public Key:ssh-rsa AAAAfakeAAAA ana@laptop
Hostname:build.example.org
Date:January,5,2024
Notes:",Build key,Servers,0
http://sn,,,,"NoteType:Address
Language:en-US
Title:ms
First Name:Ana
Last Name:Lima
City / Town:Springfield
Phone:{""num"":""+1 555 0100"",""ext"":""12""}
Notes:",Home,Personal,0
http://group,,,,,Archive,Archive,0
https://loose.example.org,u,p,,,Loose,(none),0
""".trimIndent()

private val ENPASS_EXPORT = """
{"folders": [
  {"icon": "1008", "parent_uuid": "", "title": "Work", "updated_at": 1717000000, "uuid": "f-work"},
  {"icon": "1008", "parent_uuid": "f-work", "title": "Servers", "updated_at": 1717000000, "uuid": "f-servers"}
 ],
 "items": [
  {"archived": 0, "auto_submit": 1, "category": "login", "favorite": 1, "folders": ["f-servers", "f-work"], "note": "",
   "template_type": "login.default", "title": "Example", "trashed": 0, "uuid": "i-1",
   "fields": [
    {"deleted": 0, "label": "Username", "order": 1, "sensitive": 0, "type": "username", "uid": 10, "value": "ana"},
    {"deleted": 0, "label": "Password", "order": 3, "sensitive": 1, "type": "password", "uid": 11, "value": "s3cret",
     "history": [{"encrypted": false, "updated_at": 1700000000, "value": "old-pass"}]},
    {"deleted": 0, "label": "Website", "order": 4, "sensitive": 0, "type": "url", "uid": 12, "value": "https://example.org"},
    {"deleted": 0, "label": "One-time code", "order": 5, "sensitive": 1, "type": "totp", "uid": 14, "value": "jbsw y3dp ehpk 3pxp"},
    {"deleted": 0, "label": "Autofill Info", "order": 6, "sensitive": 0, "type": ".Android#", "uid": 15, "value": "androidapp://org.example.app"},
    {"deleted": 0, "label": "Autofill Info", "order": 7, "sensitive": 0, "type": ".Android#", "uid": 16, "value": "org.example.other"},
    {"deleted": 0, "label": "ADDITIONAL DETAILS", "order": 8, "sensitive": 0, "type": "section", "uid": 17, "value": ""}
   ],
   "attachments": [{"data": "aGVsbG8=", "kind": "text/plain", "name": "recovery.txt", "order": 0, "updated": 1717000000, "uuid": "a-1"}]},
  {"archived": 0, "category": "creditcard", "favorite": 0, "template_type": "creditcard.default", "title": "Visa", "trashed": 0, "uuid": "i-2",
   "fields": [
    {"deleted": 0, "label": "Cardholder", "sensitive": 0, "type": "ccName", "value": "Ana Lima"},
    {"deleted": 0, "label": "Number", "sensitive": 0, "type": "ccNumber", "value": "4111111111111111"},
    {"deleted": 0, "label": "CVC", "sensitive": 1, "type": "ccCvc", "value": "123"},
    {"deleted": 0, "label": "Expiry date", "sensitive": 0, "type": "ccExpiry", "value": "03/30"},
    {"deleted": 0, "label": "Valid from", "sensitive": 0, "type": "ccValidfrom", "value": "01/24"}
   ]},
  {"archived": 0, "category": "identity", "favorite": 0, "template_type": "identity.default", "title": "Me", "trashed": 0, "uuid": "i-3",
   "fields": [
    {"deleted": 0, "label": "First name", "sensitive": 0, "type": "text", "value": "Ana"},
    {"deleted": 0, "label": "E-mail", "sensitive": 0, "type": "email", "value": "ana@example.org"}
   ]},
  {"archived": 1, "category": "finance", "favorite": 0, "template_type": "finance.bankaccount", "title": "Bank", "trashed": 0, "uuid": "i-4",
   "fields": [{"deleted": 0, "label": "Account number", "sensitive": 1, "type": "numeric", "value": "1234"}]},
  {"archived": 0, "category": "login", "favorite": 0, "title": "Deleted", "trashed": 1, "uuid": "i-5", "fields": []}
 ]}
""".trimIndent()
