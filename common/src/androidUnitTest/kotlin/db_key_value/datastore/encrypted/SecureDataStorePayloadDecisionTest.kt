package db_key_value.datastore.encrypted

import androidx.datastore.core.CorruptionException
import com.artemchep.keyguard.test.withTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SecureDataStorePayloadDecisionTest {
    @Test
    fun `missing payload does not require a probe`() {
        withTempDirectory("secure-datastore-payload-test") { dir ->
            val directory = dir.toFile()
            val payload = directory.resolve("missing.preferences_pb")

            assertFalse(encryptedPayloadRequiresProbe(payload))
        }
    }

    @Test
    fun `existing empty payload is corruption`() {
        withTempDirectory("secure-datastore-payload-test") { dir ->
            val directory = dir.toFile()
            val payload = directory.resolve("empty.preferences_pb").apply { createNewFile() }

            assertFailsWith<CorruptionException> {
                encryptedPayloadRequiresProbe(payload)
            }
        }
    }

    @Test
    fun `existing non-empty payload requires a probe`() {
        withTempDirectory("secure-datastore-payload-test") { dir ->
            val directory = dir.toFile()
            val payload = directory.resolve("data.preferences_pb").apply { writeText("ciphertext") }

            assertTrue(encryptedPayloadRequiresProbe(payload))
        }
    }
}
