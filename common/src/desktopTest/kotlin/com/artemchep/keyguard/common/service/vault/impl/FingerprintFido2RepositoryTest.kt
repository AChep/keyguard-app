package com.artemchep.keyguard.common.service.vault.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.FingerprintFido2
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.copy.Base64ServiceJvm
import com.artemchep.keyguard.util.fido2.FIDO2_RP_ID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FingerprintFido2RepositoryTest {
    @Test
    fun oldAndMalformedOptionalFactorsPreservePasswordRecovery() = runTest {
        val store = JsonKeyValueStore()
        val repository = FingerprintRepositoryImpl(store, Json, Base64ServiceJvm())
        for (factor in
            listOf(
                "",
                ",\"fido2\":null",
                ",\"fido2\":42",
                ",\"fido2\":{}",
                ",\"fido2\":{\"version\":999}",
            )) {
            store
                .getString("data", "")
                .setAndCommit(
                    """{"version":0,"master":{"hashBase64":"AQ==","saltBase64":"Ag=="},"biometric":null$factor}"""
                )
                .bind()
            val tokens = assertNotNull(repository.get().first())
            assertContentEquals(byteArrayOf(1), tokens.master.hash.byteArray)
            assertNull(tokens.fido2)
        }
    }

    @Test
    fun storesExplicitVersionAndPreservesMasterDuringAtomicUpdate() = runTest {
        val store = JsonKeyValueStore()
        val repository = FingerprintRepositoryImpl(store, Json, Base64ServiceJvm())
        val preference = store.getString("data", "")
        preference
            .setAndCommit(
                """{"version":0,"master":{"hashBase64":"AQ==","saltBase64":"Ag=="},"biometric":null}"""
            )
            .bind()
        val before = assertNotNull(repository.get().first())
        val protector =
            FingerprintFido2(
                "AQ==",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "2.test",
            )
        repository.update { requireNotNull(it).copy(fido2 = protector) }.bind()
        val stored =
            Json.parseToJsonElement(preference.first()).jsonObject.getValue("fido2").jsonObject
        assertEquals("1", stored.getValue("version").jsonPrimitive.content)
        assertEquals(FIDO2_RP_ID, stored.getValue("rpId").jsonPrimitive.content)
        val after = assertNotNull(repository.get().first())
        assertEquals(before.master, after.master)
        assertEquals(protector, after.fido2)
    }
}
