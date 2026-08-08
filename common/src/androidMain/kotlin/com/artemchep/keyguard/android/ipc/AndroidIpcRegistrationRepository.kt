package com.artemchep.keyguard.android.ipc

import android.content.Context
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegisteredApp
import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegistrationService
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.getSerializable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Clock

internal class AndroidIpcRegistrationRepository(
    private val store: KeyValueStore,
    json: Json,
    private val context: Context? = null,
    private val nowEpochMilliseconds: () -> Long = {
        Clock.System.now().toEpochMilliseconds()
    },
) : AndroidIpcRegistrationService {
    companion object {
        private const val KEY_REGISTRY = "registered_apps"
        private const val SCHEMA_VERSION = 1
        private const val LAST_USED_WRITE_INTERVAL_MS = 60L * 60L * 1000L
    }

    enum class Status {
        REGISTERED,
        NOT_REGISTERED,
        SIGNER_MISMATCH,
    }

    data class Registration(
        val packageName: String,
        val certificateDigests: List<String>,
        val appLabel: String,
        val registeredAtEpochMilliseconds: Long,
        val lastUsedAtEpochMilliseconds: Long,
    )

    private val mutex = Mutex()
    private val preference = store.getSerializable(
        json = json,
        key = KEY_REGISTRY,
        defaultValue = RegistryEntity(),
    )

    constructor(directDI: DirectDI) : this(
        store = directDI.instance<Files, KeyValueStore>(arg = Files.ANDROID_IPC),
        json = directDI.instance(),
        context = directDI.instance(),
    )

    override fun registrations(): Flow<List<AndroidIpcRegisteredApp>> =
        preference.map { entity ->
            decode(entity).map { registration ->
                val identity = context?.let {
                    resolveAndroidIpcPackageIdentity(
                        context = it,
                        packageName = registration.packageName,
                    )
                }
                AndroidIpcRegisteredApp(
                    packageName = registration.packageName,
                    appLabel = identity?.appLabel ?: registration.appLabel,
                    certificateDigests = registration.certificateDigests,
                    registeredAtEpochMilliseconds =
                    registration.registeredAtEpochMilliseconds,
                    lastUsedAtEpochMilliseconds =
                    registration.lastUsedAtEpochMilliseconds,
                    installed = identity != null,
                    signerMismatch = identity != null &&
                            identity.certificateDigests !=
                            registration.certificateDigests,
                )
            }
        }

    suspend fun status(caller: AndroidIpcCaller): Status = mutex.withLock {
        statusLocked(
            registrations = readLocked(),
            caller = caller,
        )
    }

    suspend fun register(caller: AndroidIpcCaller): Boolean = mutex.withLock {
        val registrations = readLocked().toMutableList()
        when (statusLocked(registrations, caller)) {
            Status.REGISTERED -> return@withLock true
            Status.SIGNER_MISMATCH -> return@withLock false
            Status.NOT_REGISTERED -> Unit
        }
        val now = nowEpochMilliseconds()
        registrations += Registration(
            packageName = caller.packageName,
            certificateDigests = caller.certificateDigests.normalizedSignerSet(),
            appLabel = caller.appLabel,
            registeredAtEpochMilliseconds = now,
            lastUsedAtEpochMilliseconds = now,
        )
        writeLocked(registrations)
        true
    }

    suspend fun recordUse(caller: AndroidIpcCaller) = mutex.withLock {
        val registrations = readLocked()
        val index = registrations.indexOfFirst {
            it.packageName == caller.packageName &&
                    it.certificateDigests == caller.certificateDigests.normalizedSignerSet()
        }
        if (index < 0) {
            return@withLock
        }
        val now = nowEpochMilliseconds()
        val current = registrations[index]
        if (now - current.lastUsedAtEpochMilliseconds < LAST_USED_WRITE_INTERVAL_MS) {
            return@withLock
        }
        writeLocked(
            registrations.toMutableList().apply {
                this[index] = current.copy(
                    appLabel = caller.appLabel,
                    lastUsedAtEpochMilliseconds = now,
                )
            },
        )
    }

    override fun revoke(packageName: String) = suspend {
        mutex.withLock {
            writeLocked(
                readLocked().filterNot { it.packageName == packageName },
            )
        }
        AndroidIpcApprovalCoordinator.invalidateCaller(packageName)
    }

    suspend fun revokeAll() = mutex.withLock {
        writeLocked(emptyList())
    }

    private fun statusLocked(
        registrations: List<Registration>,
        caller: AndroidIpcCaller,
    ): Status {
        val registration = registrations
            .singleOrNull { it.packageName == caller.packageName }
            ?: return Status.NOT_REGISTERED
        return if (
            registration.certificateDigests ==
            caller.certificateDigests.normalizedSignerSet()
        ) {
            Status.REGISTERED
        } else {
            Status.SIGNER_MISMATCH
        }
    }

    private suspend fun readLocked(): List<Registration> =
        decode(preference.first())

    private suspend fun writeLocked(registrations: List<Registration>) {
        preference.setAndCommit(
            RegistryEntity(
                schemaVersion = SCHEMA_VERSION,
                registrations = registrations
                    .distinctBy(Registration::packageName)
                    .sortedBy(Registration::packageName)
                    .map { registration ->
                        RegistrationEntity(
                            packageName = registration.packageName,
                            certificateDigests =
                            registration.certificateDigests.normalizedSignerSet(),
                            appLabel = registration.appLabel,
                            registeredAtEpochMilliseconds =
                            registration.registeredAtEpochMilliseconds,
                            lastUsedAtEpochMilliseconds =
                            registration.lastUsedAtEpochMilliseconds,
                        )
                    },
            ),
        ).bind()
    }

    private fun decode(entity: RegistryEntity): List<Registration> {
        if (entity.schemaVersion != SCHEMA_VERSION) {
            return emptyList()
        }
        return entity.registrations
            .mapNotNull { registration ->
                val packageName = registration.packageName
                    .takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val signers = registration.certificateDigests
                    .normalizedSignerSet()
                    .takeIf(List<String>::isNotEmpty)
                    ?: return@mapNotNull null
                Registration(
                    packageName = packageName,
                    certificateDigests = signers,
                    appLabel = registration.appLabel
                        .takeIf(String::isNotBlank)
                        ?: packageName,
                    registeredAtEpochMilliseconds =
                    registration.registeredAtEpochMilliseconds,
                    lastUsedAtEpochMilliseconds =
                    registration.lastUsedAtEpochMilliseconds,
                )
            }
            .distinctBy(Registration::packageName)
    }

    @Serializable
    private data class RegistryEntity(
        val schemaVersion: Int = SCHEMA_VERSION,
        val registrations: List<RegistrationEntity> = emptyList(),
    )

    @Serializable
    private data class RegistrationEntity(
        val packageName: String,
        val certificateDigests: List<String>,
        val appLabel: String,
        val registeredAtEpochMilliseconds: Long,
        val lastUsedAtEpochMilliseconds: Long,
    )
}

private fun List<String>.normalizedSignerSet(): List<String> =
    asSequence()
        .map(String::lowercase)
        .distinct()
        .sorted()
        .toList()
