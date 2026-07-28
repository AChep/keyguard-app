package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.PasswordStrength
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestLogRepository
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestVaultDatabaseManager
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import com.artemchep.keyguard.provider.bitwarden.sync.v2.testCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class GetCiphersImplTest {
    @Test
    fun `get ciphers reuses the shared decoded snapshot`() = runTest {
        val db = createUploadTestDatabase()
        val stored = testCipher(
            localId = "cipher-1",
            remoteId = "remote-cipher-1",
            localRevisionDate = T0,
            remoteRevisionDate = T0,
            attachments = emptyList(),
        ).copy(
            type = BitwardenCipher.Type.Login,
            secureNote = null,
            login = BitwardenCipher.Login(
                password = "password",
                uris = emptyList(),
            ),
        )
        db.cipherQueries.insert(
            cipherId = stored.cipherId,
            accountId = stored.accountId,
            folderId = stored.folderId,
            data = stored,
            updatedAt = T0,
        )

        var passwordStrengthCalls = 0
        val getPasswordStrength = object : GetPasswordStrength {
            override fun invoke(password: String) = ioEffect {
                passwordStrengthCalls += 1
                PasswordStrength(
                    crackTimeSeconds = 1_000_000L,
                    version = 1L,
                )
            }
        }
        val windowScope = object : WindowCoroutineScope, CoroutineScope by backgroundScope {}
        val dispatcher = StandardTestDispatcher(testScheduler)
        val getCipherSnapshots = GetCipherSnapshotsImpl(
            logRepository = UploadTestLogRepository,
            databaseManager = UploadTestVaultDatabaseManager(db),
            getPasswordStrength = getPasswordStrength,
            windowCoroutineScope = windowScope,
            dbDispatcher = dispatcher,
            defaultDispatcher = dispatcher,
        )
        val getCiphers = GetCiphersImpl(
            getCipherSnapshots = getCipherSnapshots,
            windowCoroutineScope = windowScope,
        )

        val snapshotDeferred = async { getCipherSnapshots().first().single() }
        val cipherDeferred = async { getCiphers().first().single() }
        val snapshot = snapshotDeferred.await()
        val cipher = cipherDeferred.await()

        assertSame(snapshot.cipher, cipher)
        assertEquals(1, passwordStrengthCalls)
        assertEquals(0L, snapshot.key.dataRevCounter)

        db.watchtowerThreatQueries.upsert(
            value = null,
            threat = false,
            cipherId = stored.cipherId,
            type = 1L,
            reportedAt = T1,
            version = "1",
            cipherDataRevCounter = snapshot.key.dataRevCounter,
        )
        runCurrent()

        assertEquals(1, passwordStrengthCalls)
    }

    @Test
    fun `get cipher snapshots reuses unchanged domain objects`() = runTest {
        val db = createUploadTestDatabase()
        val storedCiphers = listOf(
            loginCipher(
                localId = "cipher-1",
                remoteId = "remote-cipher-1",
                password = "password-1",
            ),
            loginCipher(
                localId = "cipher-2",
                remoteId = "remote-cipher-2",
                password = "password-2",
            ),
        )
        storedCiphers.forEach { stored ->
            db.cipherQueries.insert(
                cipherId = stored.cipherId,
                accountId = stored.accountId,
                folderId = stored.folderId,
                data = stored,
                updatedAt = T0,
            )
        }

        var passwordStrengthCalls = 0
        val getPasswordStrength = object : GetPasswordStrength {
            override fun invoke(password: String) = ioEffect {
                passwordStrengthCalls += 1
                PasswordStrength(
                    crackTimeSeconds = 1_000_000L,
                    version = 1L,
                )
            }
        }
        val windowScope = object : WindowCoroutineScope, CoroutineScope by backgroundScope {}
        val dispatcher = StandardTestDispatcher(testScheduler)
        val getCipherSnapshots = GetCipherSnapshotsImpl(
            logRepository = UploadTestLogRepository,
            databaseManager = UploadTestVaultDatabaseManager(db),
            getPasswordStrength = getPasswordStrength,
            windowCoroutineScope = windowScope,
            dbDispatcher = dispatcher,
            defaultDispatcher = dispatcher,
        )

        val initial = getCipherSnapshots().first()
            .associateBy { snapshot -> snapshot.key.cipherId }
        assertEquals(2, passwordStrengthCalls)

        val updatedDeferred = async {
            getCipherSnapshots().first { snapshots ->
                snapshots.any { snapshot ->
                    snapshot.key.cipherId == "cipher-1" &&
                        snapshot.key.dataRevCounter == 1L
                }
            }
        }
        runCurrent()
        val changed = storedCiphers.first().copy(
            name = "Updated cipher",
            revisionDate = T1,
        )
        db.cipherQueries.insert(
            cipherId = changed.cipherId,
            accountId = changed.accountId,
            folderId = changed.folderId,
            data = changed,
            updatedAt = T1,
        )
        runCurrent()
        val updated = updatedDeferred.await()
            .associateBy { snapshot -> snapshot.key.cipherId }

        assertEquals(3, passwordStrengthCalls)
        assertNotSame(initial.getValue("cipher-1"), updated.getValue("cipher-1"))
        assertNotSame(initial.getValue("cipher-1").cipher, updated.getValue("cipher-1").cipher)
        assertSame(initial.getValue("cipher-2"), updated.getValue("cipher-2"))
        assertSame(initial.getValue("cipher-2").cipher, updated.getValue("cipher-2").cipher)
    }

    private fun loginCipher(
        localId: String,
        remoteId: String,
        password: String,
    ) = testCipher(
        localId = localId,
        remoteId = remoteId,
        localRevisionDate = T0,
        remoteRevisionDate = T0,
        attachments = emptyList(),
    ).copy(
        type = BitwardenCipher.Type.Login,
        secureNote = null,
        login = BitwardenCipher.Login(
            password = password,
            uris = emptyList(),
        ),
    )

    private companion object {
        val T0 = Instant.fromEpochMilliseconds(1_000L)
        val T1 = Instant.fromEpochMilliseconds(2_000L)
    }
}
