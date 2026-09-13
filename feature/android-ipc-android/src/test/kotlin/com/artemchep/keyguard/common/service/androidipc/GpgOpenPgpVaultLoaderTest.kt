package com.artemchep.keyguard.common.service.androidipc

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.GpgAgentFilter
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParser
import com.artemchep.keyguard.common.service.gpgagent.GpgCertificationAuthorityEntry
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyRepository
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyRepositoryEmpty
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyRow
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeySnapshot
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetGpgAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GpgOpenPgpVaultLoaderTest {
    @Test
    fun `load consumes public keys and authorities from one repository snapshot`() = runTest {
        val publicFingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF01"
        val authorityFingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF02"
        val repository = RecordingSnapshotRepository(
            GpgPublicKeySnapshot(
                publicKeys = listOf(
                    GpgPublicKeyRow(
                        accountId = "account",
                        cipherId = "public",
                        publicKeyArmored = "public-key",
                        primaryFingerprint = publicFingerprint,
                        canSign = false,
                        canDecrypt = false,
                        name = "Public key",
                    ),
                ),
                certificationAuthorities = listOf(
                    GpgCertificationAuthorityEntry(
                        accountId = "account",
                        cipherId = "authority",
                        publicKeyArmored = "authority-key",
                        primaryFingerprint = authorityFingerprint,
                    ),
                ),
            ),
        )
        val loader = GpgOpenPgpVaultLoader(
            getVaultSession = LockedVaultSession,
            getGpgAgentFilter = object : GetGpgAgentFilter {
                override fun invoke(): Flow<GpgAgentFilter> = flowOf(GpgAgentFilter())
            },
            publicKeyParser = FakePublicKeyParser(
                mapOf(
                    "public-key" to publicFingerprint,
                    "authority-key" to authorityFingerprint,
                ),
            ),
            publicKeyRepository = repository,
            logRepository = NoOpLogRepository,
        )

        val vault = loader.load("snapshot-test")

        assertEquals(1, repository.snapshotReads)
        assertEquals(listOf("public"), vault.rings.map { it.cipherId })
        assertEquals(
            listOf(authorityFingerprint),
            vault.certificationAuthorities.map { it.primaryFingerprint },
        )
    }

    private class RecordingSnapshotRepository(
        private val snapshot: GpgPublicKeySnapshot,
    ) : GpgPublicKeyRepository by GpgPublicKeyRepositoryEmpty {
        var snapshotReads: Int = 0
            private set

        override fun getSnapshot(): IO<GpgPublicKeySnapshot> = {
            snapshotReads += 1
            snapshot
        }
    }

    private class FakePublicKeyParser(
        private val fingerprintsByArmored: Map<String, String>,
    ) : GpgPublicKeyParser {
        override fun parse(
            armored: String,
        ): GpgPublicKeyParseResult {
            val fingerprint = fingerprintsByArmored.getValue(armored)
            return GpgPublicKeyParseResult.Success(
                keys = listOf(
                    GpgPublicKeyInfo(
                        fingerprint = fingerprint,
                        keyId = fingerprint.takeLast(16),
                        algorithm = "ED25519",
                        bitStrength = null,
                        userIds = listOf("User <user@example.com>"),
                        emails = listOf("user@example.com"),
                        createdAt = null,
                        expiresAt = null,
                        revoked = false,
                        canSign = true,
                        canEncrypt = true,
                        publicKeyArmored = armored,
                        subKeys = emptyList(),
                    ),
                ),
            )
        }
    }

    private object LockedVaultSession : GetVaultSession {
        override val valueOrNull: MasterSession? = null

        override fun invoke(): Flow<MasterSession> = emptyFlow()
    }

    private object NoOpLogRepository : LogRepository {
        override fun post(
            tag: String,
            message: String,
            level: LogLevel,
        ) = Unit

        override suspend fun add(
            tag: String,
            message: String,
            level: LogLevel,
        ) = Unit
    }
}
