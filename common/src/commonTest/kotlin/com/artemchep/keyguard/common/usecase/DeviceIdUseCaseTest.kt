package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.id.IdRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class DeviceIdUseCaseTest {
    @Test
    fun `does not cache generated id when persisting fails`() = runTest {
        val repository = FakeIdRepository(storedId = "")
        val useCase = DeviceIdUseCase(repository)
        repository.failNextPut = IllegalStateException("disk")

        assertFailsWith<IllegalStateException> {
            useCase().bind()
        }

        val deviceId = useCase().bind()

        assertEquals(2, repository.putValues.size)
        assertEquals(deviceId, repository.storedId)
    }

    @Test
    fun `coalesces concurrent initialization and persists generated id once`() = runTest {
        val repository = FakeIdRepository(
            storedId = "",
            putDelayMillis = 50L,
        )
        val useCase = DeviceIdUseCase(repository)

        val deviceIds = List(8) {
            async {
                useCase().bind()
            }
        }.awaitAll()

        assertEquals(1, deviceIds.toSet().size)
        assertEquals(1, repository.getCount)
        assertEquals(1, repository.putValues.size)
        assertEquals(deviceIds.first(), repository.storedId)
    }

    @Test
    fun `clear removes cached id and next call generates a new persisted id`() = runTest {
        val repository = FakeIdRepository(storedId = "existing")
        val useCase = DeviceIdUseCase(repository)

        assertEquals("existing", useCase().bind())

        useCase.clear().bind()
        val rotatedDeviceId = useCase().bind()

        assertNotEquals("existing", rotatedDeviceId)
        assertEquals(rotatedDeviceId, repository.storedId)
        assertEquals(listOf("", rotatedDeviceId), repository.putValues)
    }

    @Test
    fun `clear restores cached id when repository write fails`() = runTest {
        val repository = FakeIdRepository(storedId = "existing")
        val useCase = DeviceIdUseCase(repository)

        assertEquals("existing", useCase().bind())
        repository.failNextPut = IllegalStateException("disk")

        assertFailsWith<IllegalStateException> {
            useCase.clear().bind()
        }

        assertEquals("existing", useCase().bind())
        assertEquals("existing", repository.storedId)
    }

    @Test
    fun `rotate clears device id cache before removing accounts`() = runTest {
        val repository = FakeIdRepository(storedId = "existing")
        val deviceIdUseCase = DeviceIdUseCase(repository)
        var removeAccountsCalls = 0
        val removeAccounts = object : RemoveAccounts {
            override fun invoke(): IO<Unit> = ioEffect {
                removeAccountsCalls++
            }
        }
        val rotateDeviceId = RotateDeviceIdUseCase(
            deviceIdUseCase = deviceIdUseCase,
            removeAccounts = removeAccounts,
        )

        assertEquals("existing", deviceIdUseCase().bind())

        rotateDeviceId().bind()
        val rotatedDeviceId = deviceIdUseCase().bind()

        assertEquals(1, removeAccountsCalls)
        assertNotEquals("existing", rotatedDeviceId)
        assertEquals(rotatedDeviceId, repository.storedId)
    }
}

private class FakeIdRepository(
    var storedId: String,
    private val putDelayMillis: Long = 0L,
) : IdRepository {
    var getCount = 0
    var failNextPut: Throwable? = null
    val putValues = mutableListOf<String>()

    override fun get(): IO<String> = ioEffect {
        getCount++
        storedId
    }

    override fun put(id: String): IO<Unit> = ioEffect {
        putValues += id
        if (putDelayMillis > 0L) {
            delay(putDelayMillis)
        }
        failNextPut?.let { e ->
            failNextPut = null
            throw e
        }
        storedId = id
    }
}
