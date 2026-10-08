package com.artemchep.keyguard.common.service.keyvalue.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.dispatchOn
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.handleError
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.service.keyvalue.KeyValuePreference
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.RealKeyValuePreference
import com.artemchep.keyguard.common.service.keyvalue.SecureKeyValueStore
import com.artemchep.keyguard.common.service.state.impl.toJson
import com.artemchep.keyguard.common.service.state.impl.toMap
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.util.io.atomic.AtomicDirectoryPermissions
import com.artemchep.keyguard.util.io.atomic.AtomicFileDestination
import com.artemchep.keyguard.util.io.atomic.AtomicFilePermissions
import com.artemchep.keyguard.util.io.atomic.AtomicFileWriteException
import com.artemchep.keyguard.util.io.atomic.AtomicPublicationPolicy
import com.artemchep.keyguard.util.io.atomic.AtomicPublicationState
import com.artemchep.keyguard.util.io.atomic.AtomicWriteOptions
import com.artemchep.keyguard.util.io.atomic.ExistingParentLinkPolicy
import com.artemchep.keyguard.util.io.atomic.ParentDirectoryPolicy
import com.artemchep.keyguard.util.io.atomic.ReplacementAccessPolicy
import com.artemchep.keyguard.util.io.atomic.SyncLevel
import com.artemchep.keyguard.util.io.atomic.SynchronizationPolicy
import com.artemchep.keyguard.util.io.atomic.writeFileAtomically
import com.artemchep.keyguard.util.io.readTextIfExists
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.reflect.KClass

class DefaultJsonKeyValueStoreStore : JsonKeyValueStoreStore {
    override fun read(): IO<PersistentMap<String, Any?>> =
        ioEffect {
            persistentMapOf<String, Any?>()
        }

    override fun write(state: PersistentMap<String, Any?>): IO<Unit> = ioUnit()
}

class FileJsonKeyValueStoreStore(
    private val fileIo: IO<AtomicFileDestination>,
    private val json: Json,
) : JsonKeyValueStoreStore {
    override fun read(): IO<PersistentMap<String, Any?>> = fileIo
        .effectMap { destination ->
            // A cancelled first write may leave no file to reconcile. Absence is
            // an empty store; unreadable or malformed existing files still fail.
            destination.path.readTextIfExists()
                ?.let { json.decodeFromString<JsonObject>(it).toMap().toPersistentMap() }
                ?: persistentMapOf<String, Any?>()
        }
        .dispatchOn(Dispatchers.IO)

    override fun write(state: PersistentMap<String, Any?>): IO<Unit> = fileIo
        .effectMap { destination ->
            val text = json.encodeToString(state.toJson())
            writeFileAtomically(
                destination = destination,
                options = AtomicWriteOptions(
                    publication = AtomicPublicationPolicy.Replace(
                        access = ReplacementAccessPolicy.UseRequestedPermissions(
                            permissions = AtomicFilePermissions.OwnerOnly,
                        ),
                    ),
                    parentDirectories = ParentDirectoryPolicy.CreateMissing(
                        permissions = AtomicDirectoryPermissions.OwnerOnly,
                    ),
                    existingParentLinks = ExistingParentLinkPolicy.Reject,
                    synchronization = SynchronizationPolicy.Required(
                        SyncLevel.FileSynchronized,
                    ),
                ),
            ) { sink ->
                sink.write(text.encodeToByteArray())
            }.receipt.requireCleanupComplete()
            Unit
        }
        .dispatchOn(Dispatchers.IO)
}

interface JsonKeyValueStoreStore {
    fun read(): IO<PersistentMap<String, Any?>>

    fun write(state: PersistentMap<String, Any?>): IO<Unit>
}

class JsonKeyValueStore(
    private val str: JsonKeyValueStoreStore = DefaultJsonKeyValueStoreStore(),
) : KeyValueStore,
    SecureKeyValueStore {
    class SharedPrefsKeyValuePreference<T : Any>(
        override val key: String,
        override val clazz: KClass<*>,
        private val default: T,
        private val update: suspend ((PersistentMap<String, Any?>) -> PersistentMap<String, Any?>) -> Unit,
        private val flow: Flow<PersistentMap<String, Any?>>,
    ) : RealKeyValuePreference<T> {
        override fun setAndCommit(value: T): IO<Unit> = ioEffect {
            update { state ->
                state.put(key, value)
            }
        }

        override fun deleteAndCommit(): IO<Unit> = ioEffect {
            update { state ->
                state.remove(key)
            }
        }

        override suspend fun collect(collector: FlowCollector<T>) {
            flow
                .map {
                    val value = it[key] as? T
                    value ?: default
                }
                // This should never happen. If it does it would crash the
                // app, so instead we just fall back to the default value.
                .catch {
                    emit(default)
                }
                .distinctUntilChanged()
                .collect(collector)
        }
    }

    private val mutex = Mutex()

    private val sink = MutableStateFlow(persistentMapOf<String, Any?>())

    private val flow: Flow<PersistentMap<String, Any?>> = flow {
        ensureInit()
        sink.collect(this)
    }

    private var init = false
    private var needsReload = false

    private suspend fun ensureInit(): PersistentMap<String, Any?> = mutex.withLock {
        initializeLocked()
        sink.value
    }

    private suspend fun initializeLocked() {
        // Initialize the sink with data from the external
        // database.
        if (!init) {
            sink.value = str.read()
                .handleError { persistentMapOf<String, Any?>() }
                .bind()
            init = true
        }
        if (needsReload) {
            // Do not overwrite a possibly published commit using stale memory.
            sink.value = str.read().bind()
            needsReload = false
        }
    }

    // The backend may throw any failure, including cancellation after publication.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun update(transform: (PersistentMap<String, Any?>) -> PersistentMap<String, Any?>) {
        mutex.withLock {
            initializeLocked()
            val value = transform(sink.value)
            try {
                str.write(value).bind()
            } catch (error: Throwable) {
                // A failed flush/cleanup or cancellation can follow publication.
                // Reconcile with disk, but never turn the failed commit into success.
                val notPublished = error is AtomicFileWriteException &&
                    error.publicationState == AtomicPublicationState.NotPublished
                if (!notPublished) {
                    withContext(NonCancellable) {
                        try {
                            sink.value = str.read().bind()
                        } catch (readError: Throwable) {
                            needsReload = true
                            error.addSuppressed(readError)
                        }
                    }
                }
                throw error
            }
            sink.value = value
        }
    }

    private inline fun <reified T : Any> getFlowPrefs(
        key: String,
        defaultValue: T,
    ) = getFlowPrefs(
        key = key,
        clazz = T::class,
        defaultValue = defaultValue,
    )

    private fun <T : Any> getFlowPrefs(
        key: String,
        clazz: KClass<T>,
        defaultValue: T,
    ) = SharedPrefsKeyValuePreference(
        key = key,
        clazz = clazz,
        default = defaultValue,
        update = ::update,
        flow = flow,
    )

    /** Clears persisted preferences and updates existing preference collectors. */
    fun clearAndCommit(): IO<Unit> = ioEffect {
        update { persistentMapOf() }
    }

    override fun getFile(): IO<LocalPath> = ioEffect {
        throw NotImplementedError()
    }

    override fun getAll(): IO<Map<String, Any?>> = ioEffect {
        ensureInit()
    }

    override fun getKeys(): IO<Set<String>> = getAll()
        .map { it.keys }

    override fun getInt(key: String, defaultValue: Int): KeyValuePreference<Int> =
        getFlowPrefs(
            key = key,
            defaultValue = defaultValue,
        )

    override fun getFloat(key: String, defaultValue: Float): KeyValuePreference<Float> =
        getFlowPrefs(
            key = key,
            defaultValue = defaultValue,
        )

    override fun getBoolean(key: String, defaultValue: Boolean): KeyValuePreference<Boolean> =
        getFlowPrefs(
            key = key,
            defaultValue = defaultValue,
        )

    override fun getLong(key: String, defaultValue: Long): KeyValuePreference<Long> =
        getFlowPrefs(
            key = key,
            defaultValue = defaultValue,
        )

    override fun getString(key: String, defaultValue: String): KeyValuePreference<String> =
        getFlowPrefs(
            key = key,
            defaultValue = defaultValue,
        )
}
