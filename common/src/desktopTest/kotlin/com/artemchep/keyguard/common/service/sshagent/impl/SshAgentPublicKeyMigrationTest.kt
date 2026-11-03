package com.artemchep.keyguard.common.service.sshagent.impl

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.dataexposed.DatabaseExposed
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The migration that mirrors the certificate of an SSH key.
 */
@Suppress("FunctionNaming")
class SshAgentPublicKeyMigrationTest {
    @Test
    fun `migration keeps cached keys and adds an empty certificate`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.sql(
            """
            CREATE TABLE sshAgentPublicKey (
              accountId TEXT NOT NULL,
              cipherId TEXT NOT NULL,
              publicKey TEXT NOT NULL,
              publicKeyBlobSha256 TEXT NOT NULL,
              keyType TEXT NOT NULL,
              fingerprint TEXT NOT NULL,
              canSign INTEGER NOT NULL,
              name TEXT,
              PRIMARY KEY (accountId, cipherId)
            )
            """.trimIndent(),
        )
        driver.sql(
            "INSERT INTO sshAgentPublicKey VALUES " +
                "('a', 'c', 'ssh-ed25519 AAAA', 'hash', 'ssh-ed25519', 'SHA256:a', 1, 'Key')",
        )

        DatabaseExposed.Schema.migrate(
            driver = driver,
            oldVersion = 7,
            newVersion = 8,
        )

        assertEquals(
            listOf("a:c:ssh-ed25519 AAAA:null"),
            driver.strings(
                "SELECT accountId || ':' || cipherId || ':' || publicKey || ':' || " +
                    "IFNULL(certificate, 'null') FROM sshAgentPublicKey",
            ),
        )
    }
}

private fun SqlDriver.sql(sql: String) = execute(null, sql, 0).value

private fun SqlDriver.strings(sql: String): List<String> = executeQuery(
    null,
    sql,
    { cursor ->
        QueryResult.Value(
            buildList {
                while (cursor.next().value) add(requireNotNull(cursor.getString(0)))
            },
        )
    },
    0,
).value
