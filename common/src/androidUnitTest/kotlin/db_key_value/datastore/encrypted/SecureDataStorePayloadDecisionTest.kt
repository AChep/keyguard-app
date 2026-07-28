package db_key_value.datastore.encrypted

import androidx.datastore.core.CorruptionException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SecureDataStorePayloadDecisionTest {
    @Test
    fun `missing payload does not require a probe`() {
        val directory = createTempDirectory("secure-datastore-payload-test").toFile()
        try {
            val payload = directory.resolve("missing.preferences_pb")

            assertFalse(encryptedPayloadRequiresProbe(payload))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `existing empty payload is corruption`() {
        val directory = createTempDirectory("secure-datastore-payload-test").toFile()
        try {
            val payload = directory.resolve("empty.preferences_pb").apply { createNewFile() }

            assertFailsWith<CorruptionException> {
                encryptedPayloadRequiresProbe(payload)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `existing non-empty payload requires a probe`() {
        val directory = createTempDirectory("secure-datastore-payload-test").toFile()
        try {
            val payload = directory.resolve("data.preferences_pb").apply { writeText("ciphertext") }

            assertTrue(encryptedPayloadRequiresProbe(payload))
        } finally {
            directory.deleteRecursively()
        }
    }
}
