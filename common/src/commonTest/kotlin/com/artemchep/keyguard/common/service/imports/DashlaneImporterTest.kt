package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.model.DSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Independently authored cases for Dashlane's export variants. */
class DashlaneImporterTest {
    @Test
    fun `web values containing literal null survive`() = runTest {
        val plan = parse(
            "username,title,password,url,note,category,extra\n" +
                "null,null,null,https://null.example,null,null,null",
        )
        val item = plan.items.single().request
        assertEquals("null", item.title)
        assertEquals("null", item.login.username)
        assertEquals("null", item.login.password)
        assertEquals("null", item.note)
        assertEquals(listOf("null"), item.tags)
        assertEquals(listOf(DSecret.Field("extra", "null", type = DSecret.Field.Type.Text)), item.fields)
        assertTrue(plan.skips.isEmpty)
    }

    @Test
    fun `android credentials omit null sentinels but keep literal passwords`() = runTest {
        val plan = parse(
            "user_name,user_name_2,title,password,url,note,otp,extra\n" +
                "null,morgan,Portal,null,https://portal.example,null,null,null",
        )
        val item = plan.items.single().request
        assertEquals("morgan", item.login.username)
        assertEquals("null", item.login.password)
        assertNull(item.note)
        assertNull(item.login.totp)
        assertTrue(item.fields.isEmpty())
        assertTrue(plan.skips.isEmpty)
    }

    @Test
    fun `ambiguous note headers do not imply android nulls`() = runTest {
        val item = parse("title,note,category,firstName\nnull,null,null,null").items.single().request
        assertEquals("null", item.title)
        assertEquals("null", item.note)
        assertEquals(listOf("null"), item.tags)
        assertEquals(listOf(DSecret.Field("firstName", "null", type = DSecret.Field.Type.Text)), item.fields)
    }

    @Test
    fun `android ids and personal information use their original header spelling`() = runTest {
        val id = parse(
            "type,number,name,issueDate,extra\npassport,P000042,null,null,null",
        ).items.single().request
        assertEquals("Passport", id.title)
        assertEquals(listOf("type", "number"), id.fields.map { it.name })
        assertEquals(DSecret.Field.Type.Hidden, id.fields.single { it.name == "number" }.type)

        val person = parse(
            "type,firstName,dateOfBirth,itemName,extra\nname,Morgan,null,null,null",
        ).items.single().request
        assertEquals("Morgan", person.title)
        assertEquals(listOf("type", "firstName"), person.fields.map { it.name })
    }

    @Test
    fun `login extras and additional otp columns survive without duplicating mapped values`() = runTest {
        val secret = "GEZDGNBVGY3TQOJQ"
        val otp = "otpauth://totp/Portal?secret=$secret"
        val item = parse(
            "username,user_name_2,title,password,url,otpUrl,otpSecret,recovery_hint\n" +
                "morgan,morgan.alt,Portal,pw,https://portal.example,$otp,$secret,blue notebook",
        ).items.single().request
        assertEquals(otp, item.login.totp)
        assertEquals(
            listOf(
                DSecret.Field("Username", "morgan.alt", type = DSecret.Field.Type.Text),
                DSecret.Field("otpSecret", secret, type = DSecret.Field.Type.Hidden),
                DSecret.Field("recovery_hint", "blue notebook", type = DSecret.Field.Type.Text),
            ),
            item.fields,
        )
    }

    @Test
    fun `bank labels are neutral and additional account fields survive`() = runTest {
        val item = parse(
            "type,cc_number,account_number,name,account_name,account_holder,routing_number,branch\n" +
                "bank,,0000042,Household,Checking,Morgan,001122334,North",
        ).items.single().request
        assertEquals(DSecret.Type.SecureNote, item.type)
        assertEquals("Household", item.title)
        assertEquals(
            listOf(
                DSecret.Field("Account holder", "Morgan", type = DSecret.Field.Type.Text),
                DSecret.Field("Routing number", "001122334", type = DSecret.Field.Type.Text),
                DSecret.Field("Account number", "0000042", type = DSecret.Field.Type.Hidden),
                DSecret.Field("account_name", "Checking", type = DSecret.Field.Type.Text),
                DSecret.Field("branch", "North", type = DSecret.Field.Type.Text),
            ),
            item.fields,
        )
    }

    @Test
    fun `android cards preserve extras while normalizing headers and nulls`() = runTest {
        val item = parse(
            "type,ccNumber,accountNumber,accountName,name,expirationMonth,expirationYear,note,rewardsId\n" +
                "payment_card,4000000000000042,null,Morgan,Travel,JUNE,2034,null,R42",
        ).items.single().request
        assertEquals(DSecret.Type.Card, item.type)
        assertEquals("4000000000000042", item.card.number)
        assertEquals("Morgan", item.card.cardholderName)
        assertEquals("6", item.card.expMonth)
        assertEquals("2034", item.card.expYear)
        assertNull(item.note)
        assertEquals(listOf(DSecret.Field("rewardsId", "R42", type = DSecret.Field.Type.Text)), item.fields)
    }

    @Test
    fun `card expiry that cannot be mapped stays in custom fields`() = runTest {
        val item = parse(
            "type,cc_number,account_number,expiration_month,expiration_year,extra\n" +
                "credit_card,4000000000000042,,13,2034,keep",
        ).items.single().request
        assertNull(item.card.expMonth)
        assertNull(item.card.expYear)
        assertEquals(
            listOf("expiration_month" to "13", "expiration_year" to "2034", "extra" to "keep"),
            item.fields.map { it.name to it.value },
        )
    }

    @Test
    fun `unknown and missing payment types retain fields with secrets concealed`() = runTest {
        listOf("future_payment", "").forEach { type ->
            val plan = parse(
                "type,cc_number,account_number,name,code,note,expiration_year,provider\n" +
                    "$type,4000000000000042,0000042,Reserve,042,Keep this,2034,Future bank",
            )
            val item = plan.items.single().request
            assertEquals(DSecret.Type.SecureNote, item.type)
            assertEquals("Reserve", item.title)
            assertEquals("Keep this", item.note)
            val fields = item.fields.associateBy { it.name }
            assertEquals(type.takeIf { it.isNotEmpty() }, fields["type"]?.value)
            assertEquals("Reserve", fields["name"]?.value)
            assertEquals("2034", fields["expiration_year"]?.value)
            assertEquals("Future bank", fields["provider"]?.value)
            mapOf("cc_number" to "4000000000000042", "account_number" to "0000042", "code" to "042")
                .forEach { (name, value) ->
                    assertEquals(DSecret.Field(name, value, type = DSecret.Field.Type.Hidden), fields[name])
                }
            assertNull(fields["note"])
            assertTrue(plan.skips.isEmpty)
        }
    }

    @Test
    fun `wifi extras and literal passphrases survive`() = runTest {
        val item = parse(
            "name,ssid,passphrase,encription_type,note,access_point\n" +
                "Workshop,Workshop LAN,null,wpa2,Upstairs,west",
        ).items.single().request
        assertEquals("Upstairs", item.note)
        assertEquals(
            listOf(
                DSecret.Field("SSID", "Workshop LAN", type = DSecret.Field.Type.Text),
                DSecret.Field("Password", "null", type = DSecret.Field.Type.Hidden),
                DSecret.Field("Security", "wpa2", type = DSecret.Field.Type.Text),
                DSecret.Field("access_point", "west", type = DSecret.Field.Type.Text),
            ),
            item.fields,
        )
    }

    private suspend fun parse(text: String): ImportPlan =
        parseImportPlan(ImportFormatId.Dashlane, text).also { it.assertVaultStorable() }
}
