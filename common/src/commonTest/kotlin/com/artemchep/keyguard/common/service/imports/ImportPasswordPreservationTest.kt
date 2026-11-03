package com.artemchep.keyguard.common.service.imports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

class ImportPasswordPreservationTest {
    @Test
    fun `CSV imports preserve nonempty passwords verbatim`() = runTest {
        for (password in listOf("   ", "\t", "  secret  ", ",,,", "")) {
            val cell = "\"$password\""
            val files = listOf(
                ImportFormatId.Chromium to "name,url,username,password\nExample,https://example.org,user,$cell",
                ImportFormatId.Firefox to "url,username,password\nhttps://example.org,user,$cell",
                ImportFormatId.Safari to "Title,URL,Username,Password\nExample,https://example.org,user,$cell",
                ImportFormatId.Bitwarden to "type,name,login_username,login_password\nlogin,Example,user,$cell",
                ImportFormatId.KeePassXc to
                    "Group,Title,Username,Password,URL\nRoot,Example,user,$cell,https://example.org",
                ImportFormatId.OnePassword to
                    "Title,Url,Username,Password,OTPAuth,Archived\nExample,,user,$cell,,false",
                ImportFormatId.Dashlane to "title,url,username,password\nExample,https://example.org,user,$cell",
                ImportFormatId.Keeper to "Work,Example,user,$cell,https://example.org,Note",
                ImportFormatId.LastPass to
                    "url,username,password,extra,name,grouping\nhttps://example.org,user,$cell,,Example,",
                ImportFormatId.NordPass to
                    "name,url,username,password,type\nExample,https://example.org,user,$cell,password",
                ImportFormatId.ProtonPass to "type,name,url,username,password,note\nlogin,Example,,user,$cell,",
            )
            for ((format, text) in files) {
                val plan = parseImportPlan(format, text)
                assertEquals(importSkips(), plan.skips, format.name)
                assertEquals(
                    password.takeIf { it.isNotEmpty() },
                    plan.items.single().request.login.password,
                    format.name,
                )
            }
        }
    }

    @Test
    fun `JSON imports preserve nonempty passwords verbatim`() = runTest {
        for (password in listOf("   ", "\t", "  secret  ", ",,,", "", null)) {
            val value = JsonPrimitive(password)
            val files = listOf(
                ImportFormatId.Bitwarden to """
                    {"items":[{"type":1,"name":"Example","login":{"password":$value}}]}
                """.trimIndent(),
                ImportFormatId.Enpass to """
                    {"items":[{"category":"login","title":"Example",
                      "fields":[{"type":"password","value":$value}]}]}
                """.trimIndent(),
                ImportFormatId.ProtonPass to """
                    {"vaults":{"v":{"items":[{"data":{"type":"login","metadata":{"name":"Example"},
                      "content":{"password":$value}}}]}}}
                """.trimIndent(),
            )
            for ((format, text) in files) {
                val plan = parseImportPlan(format, text)
                assertEquals(importSkips(), plan.skips, format.name)
                assertEquals(
                    password?.takeIf { it.isNotEmpty() },
                    plan.items.single().request.login.password,
                    format.name,
                )
            }
        }
    }
}
