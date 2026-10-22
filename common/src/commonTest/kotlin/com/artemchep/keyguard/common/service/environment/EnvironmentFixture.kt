package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.test.environmentPayload
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy
import com.artemchep.keyguard.common.service.agent.CallerIdentity
import com.artemchep.keyguard.common.service.agent.fixedAgentApprovalCacheConfig
import com.artemchep.keyguard.common.service.cli.CliCommandDispatcher
import com.artemchep.keyguard.common.service.cli.CliServiceImpl
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages.Request
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages.Response
import com.artemchep.keyguard.common.service.cli.agent.CliAgentRequestHandler
import com.artemchep.keyguard.common.service.cli.agent.error
import com.artemchep.keyguard.common.service.cli.agent.manageRequest
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStoreFactory
import com.artemchep.keyguard.common.service.keyvalue.RealKeyValuePreference
import com.artemchep.keyguard.common.service.vault.VaultSession
import com.artemchep.keyguard.common.usecase.AddCipher
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.TrashCipherById
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import com.artemchep.keyguard.feature.environments.EnvironmentVariable
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.test.TestCryptoGenerator
import com.artemchep.keyguard.test.createProfile
import com.artemchep.keyguard.test.createSecret
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.TimeSource

/** The environment components over a fake vault, wired like the application wires them. */
internal class EnvironmentFixture(
    private val scope: CoroutineScope,
    initiallyLocked: Boolean = false,
    private val configuredMounts: List<EnvironmentMount> = emptyList(),
    timeSource: TimeSource = TimeSource.Monotonic,
    mcpApprovalWindow: Duration = Duration.INFINITE,
    cliApprovalWindow: Duration = Duration.INFINITE,
) {
    private val vaultScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val vault = object : VaultSession {
        override val id = "session-1"
        override val active = MutableStateFlow(true)
        override fun retire() { active.value = false }
        override fun close() {
            retire()
            vaultScope.cancel()
        }
    }
    private val unlockedSession = MasterSession.Key(
        MasterKey(MasterKdfVersion.V0, ByteArray(32)), vault,
        MasterSession.Key.Authenticated, Instant.fromEpochSeconds(0),
    )
    val session = MutableStateFlow<MasterSession>(
        if (initiallyLocked) MasterSession.Empty() else unlockedSession,
    )
    val secret = createSecret(
        "environment", accountId = "account", type = DSecret.Type.Environment, notes = "secret description",
    ).copy(environment = environmentPayload(EnvironmentDefinition(listOf(
        EnvironmentVariable("TOKEN", EnvironmentValueSource.Literal(SECRET)),
    ))))
    val ciphers = MutableStateFlow(listOf(secret))
    val profiles = MutableStateFlow(listOf(createProfile("account")))
    val saved = mutableListOf<CreateRequest>()
    val savedIds = mutableListOf<String?>()
    val approvedCapabilities = MutableStateFlow<List<String>>(emptyList())
    val approvedEnvironmentIds = mutableListOf<String?>()
    val approvedPaths = mutableListOf<String?>()
    var beforeSave: suspend () -> Unit = {}
    val collections = MutableStateFlow<List<DCollection>>(emptyList())
    private val dependencies = EnvironmentSessionDependencies(
        getCiphers = object : GetCiphers { override fun invoke(): Flow<List<DSecret>> = ciphers },
        getProfiles = object : GetProfiles {
            override fun invoke(): Flow<List<DProfile>> = profiles
        },
        getCollections = object : GetCollections { override fun invoke(): Flow<List<DCollection>> = collections },
        addCipher = object : AddCipher {
            override fun invoke(cipherIdsToRequests: Map<String?, CreateRequest>): IO<List<String>> = {
                beforeSave()
                saved += cipherIdsToRequests.values
                savedIds += cipherIdsToRequests.keys
                listOf("environment")
            }
        },
        trashCipher = object : TrashCipherById { override fun invoke(ids: Set<String>): IO<Unit> = { Unit } },
        scope = vaultScope,
    )
    private val getVaultSession = object : GetVaultSession {
        override val valueOrNull: MasterSession get() = session.value
        override fun invoke(): Flow<MasterSession> = session
    }
    private val cryptoGenerator = object : TestCryptoGenerator() {
        private var index = 0
        override fun uuid(): String = "id-${index++}"
    }
    private val storeFactory = KeyValueStoreFactory { MemoryStore(configuredMounts) }
    private val sessionAccess = EnvironmentSessionAccess { dependencies }
    val environmentVault = EnvironmentVault(getVaultSession = getVaultSession, sessionAccess = sessionAccess)
    val access = EnvironmentAccessImpl(
        vault = environmentVault,
        getVaultSession = getVaultSession,
        cryptoGenerator = cryptoGenerator,
        scope = scope,
        timeSource = timeSource,
        mcpApprovalCacheConfig = fixedAgentApprovalCacheConfig(mcpApprovalWindow, AgentApprovalCachePolicy.Default),
    )
    val mounts = EnvironmentMountsImpl(
        vault = environmentVault,
        access = access,
        cryptoGenerator = cryptoGenerator,
        storeFactory = storeFactory,
        json = Json,
        scope = scope,
    )
    val mcp = EnvironmentMcpImpl(access = access, storeFactory = storeFactory, json = Json, scope = scope)
    val repository = EnvironmentRepositoryImpl(
        vault = environmentVault,
        access = access,
        mounts = mounts,
        tracker = EnvironmentSessionTracker(
            getVaultSession = getVaultSession,
            sessionAccess = sessionAccess,
            access = access,
            mounts = mounts,
            scope = scope,
        ),
    )
    val cli = CliServiceImpl(
        getVaultSession = getVaultSession,
        cryptoGenerator = object : TestCryptoGenerator() {
            private var index = 0
            override fun uuid(): String = "cli-id-${index++}"
        },
        storeFactory = KeyValueStoreFactory { MemoryStore(emptyList()) },
        scope = scope,
        timeSource = timeSource,
        approvalCacheConfig = fixedAgentApprovalCacheConfig(cliApprovalWindow, AgentApprovalCachePolicy.Default),
    )

    /** The handler of the private environment requests of one agent connection. */
    fun broker(
        directoryAccess: EnvironmentDirectoryAccess = EnvironmentDirectoryAccess.ByPath,
    ) = EnvironmentBrokerHandler(
        mcp = EnvironmentMcpBrokerHandler(environmentVault, repository, access, mounts, mcp),
        mounts = EnvironmentMountBrokerHandler(environmentVault, repository, access, mounts, directoryAccess),
    )

    /** The handler of the private requests of one agent connection. */
    fun agent() = CliAgentRequestHandler(
        cli = CliCommandDispatcher(
            cli,
            listOf(EnvironmentRunCliCommand(cli, environmentVault, repository, access)),
        ),
        environments = broker(),
    )

    fun mountStatus(id: String): EnvironmentMountStatus =
        mounts.state.value.statuses[id] ?: EnvironmentMountStatus.Pending

    suspend fun ready() {
        repository.state.first { it.unlocked }
        // The stored mounts load on another thread, and revoke the grants once they do.
        mounts.state.first { it.mounts == configuredMounts }
        scope.coroutineContext[TestCoroutineScheduler]?.runCurrent()
        mcp.setEnabled(true)
        mcp.state.first { it.enabled }
        scope.coroutineContext[TestCoroutineScheduler]?.runCurrent()
    }

    fun autoApprove(scope: CoroutineScope) = scope.launch {
        launch {
            access.approvals.collect { requests -> requests.forEach {
                approvedCapabilities.value += it.capability.name
                approvedEnvironmentIds += it.environmentId
                approvedPaths += it.path
                access.respondToApproval(it.id, true)
            } }
        }
        launch {
            cli.approvals.collect { requests -> requests.forEach {
                approvedCapabilities.value += "cli"
                cli.respondToApproval(it.id, true)
            } }
        }
    }

    fun replaceSession() {
        val old = session.value as MasterSession.Key
        session.value = old.copy(session = object : VaultSession {
            override val id = "session-2"
            override val active = MutableStateFlow(true)
            override fun retire() { active.value = false }
            override fun close() = retire()
        })
    }

    fun lock() {
        vault.retire()
        session.value = MasterSession.Empty()
        vault.close()
    }

    fun unlock() {
        session.value = unlockedSession
    }
}

private class MemoryStore(private val configuredMounts: List<EnvironmentMount>) : KeyValueStore {
    override fun getFile(): IO<LocalPath> = { error("unused") }
    override fun getAll(): IO<Map<String, Any?>> = { emptyMap() }
    override fun getKeys(): IO<Set<String>> = { emptySet() }
    override fun getInt(key: String, defaultValue: Int) = Preference(key, defaultValue)
    override fun getFloat(key: String, defaultValue: Float) = Preference(key, defaultValue)
    override fun getBoolean(key: String, defaultValue: Boolean) = Preference(key, defaultValue)
    override fun getLong(key: String, defaultValue: Long) = Preference(key, defaultValue)
    override fun getString(key: String, defaultValue: String) = Preference(
        key, if (key == "environments.mounts.v1") Json.encodeToString(configuredMounts) else defaultValue,
    )
}

private class Preference<T : Any>(
    override val key: String,
    private val initial: T,
    private val state: MutableStateFlow<T> = MutableStateFlow(initial),
) : RealKeyValuePreference<T>, Flow<T> by state {
    override val clazz: KClass<*> = initial::class
    override fun setAndCommit(value: T): IO<Unit> = { state.value = value }
    override fun deleteAndCommit(): IO<Unit> = { state.value = initial }
}

internal const val SECRET = "never-send-this-secret-to-mcp"

internal fun DSecret.withRemoteId(id: String) = copy(
    service = BitwardenService(
        remote = BitwardenService.Remote(
            id = id,
            revisionDate = Instant.fromEpochSeconds(0),
            deletedDate = null,
        ),
    ),
)

internal fun manage(
    tool: String,
    name: String? = null,
    caller: CallerIdentity = CallerIdentity(processName = "test-agent"),
    clientName: String = "",
) = manageRequest(
    tool = tool,
    arguments = buildMap {
        put("environment_id", "environment")
        if (name != null) put("name", name)
    },
    caller = caller,
    clientName = clientName,
)

internal fun resolveMount(generation: String) = Request.Kind.ResolveMount(
    CliAgentMessages.ResolveMountRequest(mountId = "mount", generation = generation),
)

internal fun Response.Kind.resolved() = assertIs<Response.Kind.ResolveMount>(this).value

internal fun collection(hidden: Boolean = false, readOnly: Boolean = false) = DCollection(
    id = "collection", externalId = null, organizationId = "organization", accountId = "account",
    revisionDate = Instant.fromEpochSeconds(0), name = "Collection", readOnly = readOnly, hidePasswords = hidden,
)
