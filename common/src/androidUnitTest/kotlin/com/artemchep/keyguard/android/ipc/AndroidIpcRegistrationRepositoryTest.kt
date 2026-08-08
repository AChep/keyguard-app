package com.artemchep.keyguard.android.ipc

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidIpcRegistrationRepositoryTest {
    @Test
    fun `registration is shared persistent and exact-signer bound`() = runTest {
        val store = JsonKeyValueStore()
        var now = 1_000L
        val repository = AndroidIpcRegistrationRepository(
            store = store,
            json = Json,
            nowEpochMilliseconds = { now },
        )
        val caller = caller()

        assertEquals(
            AndroidIpcRegistrationRepository.Status.NOT_REGISTERED,
            repository.status(caller),
        )
        assertTrue(repository.register(caller))
        assertEquals(
            AndroidIpcRegistrationRepository.Status.REGISTERED,
            repository.status(caller),
        )
        assertEquals(
            AndroidIpcRegistrationRepository.Status.SIGNER_MISMATCH,
            repository.status(
                caller.copy(certificateDigests = listOf("rotated")),
            ),
        )
        assertFalse(
            repository.register(
                caller.copy(certificateDigests = listOf("rotated")),
            ),
        )

        val restored = AndroidIpcRegistrationRepository(
            store = store,
            json = Json,
            nowEpochMilliseconds = { now },
        )
        assertEquals(
            AndroidIpcRegistrationRepository.Status.REGISTERED,
            restored.status(caller),
        )
        restored.revoke(caller.packageName).bind()
        assertEquals(
            AndroidIpcRegistrationRepository.Status.NOT_REGISTERED,
            restored.status(caller),
        )
    }

    @Test
    fun `last use is coalesced to at most hourly`() = runTest {
        val store = JsonKeyValueStore()
        var now = 10_000L
        val repository = AndroidIpcRegistrationRepository(
            store = store,
            json = Json,
            nowEpochMilliseconds = { now },
        )
        val caller = caller()
        assertTrue(repository.register(caller))

        now += 30L * 60L * 1000L
        repository.recordUse(caller)
        assertEquals(
            10_000L,
            repository.registrations().first().single()
                .lastUsedAtEpochMilliseconds,
        )

        now += 31L * 60L * 1000L
        repository.recordUse(caller)
        assertEquals(
            now,
            repository.registrations().first().single()
                .lastUsedAtEpochMilliseconds,
        )
    }

    @Test
    fun `corrupt registry fails closed`() = runTest {
        val store = JsonKeyValueStore()
        store.getString("registered_apps", "")
            .setAndCommit("{not-json")
            .bind()
        val repository = AndroidIpcRegistrationRepository(
            store = store,
            json = Json,
        )

        assertEquals(emptyList(), repository.registrations().first())
        assertEquals(
            AndroidIpcRegistrationRepository.Status.NOT_REGISTERED,
            repository.status(caller()),
        )
    }

    private fun caller() = AndroidIpcCaller(
        uid = 10001,
        pid = 20001,
        packageName = "example.client",
        appLabel = "Example",
        certificateDigests = listOf("bb", "aa"),
    )
}
