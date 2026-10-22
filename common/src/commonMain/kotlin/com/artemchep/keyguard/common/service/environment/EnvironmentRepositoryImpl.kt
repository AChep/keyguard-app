package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.canEdit
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentFolder
import com.artemchep.keyguard.feature.environments.EnvironmentFormat
import com.artemchep.keyguard.feature.environments.EnvironmentOwnership
import com.artemchep.keyguard.feature.environments.EnvironmentPayload
import com.artemchep.keyguard.feature.environments.EnvironmentResolutionResult
import com.artemchep.keyguard.feature.environments.EnvironmentResolvedSnapshot
import com.artemchep.keyguard.feature.environments.EnvironmentResolver
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import com.artemchep.keyguard.feature.environments.EnvironmentVariable
import com.artemchep.keyguard.feature.environments.editableDefinition
import com.artemchep.keyguard.feature.environments.runnableDefinition
import com.artemchep.keyguard.feature.environments.toFolderInfo
import com.artemchep.keyguard.platform.leParseUri
import kotlin.time.Clock
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

class EnvironmentRepositoryImpl(
    private val vault: EnvironmentVault,
    private val access: EnvironmentAccessImpl,
    private val mounts: EnvironmentMountsImpl,
    tracker: EnvironmentSessionTracker,
) : EnvironmentRepository {
    override val state = tracker.catalog

    override suspend fun load(id: String): EnvironmentDocument = vault.withVault { dependencies ->
        val secret = dependencies.environmentSecret(id)
        val permissions = environmentPermissions(secret, dependencies.getCollections().first())
        access.checkReadAccess(secret, permissions)
        val definition = (secret.environment as? EnvironmentPayload.Decoded)?.definition
        EnvironmentDocument(secret.id, secret.accountId, secret.name, secret.notes, definition,
            secret.isEditable(permissions), unsupported = secret.environment.editableDefinition() == null,
            ownership = secret.environmentOwnership())
    }

    /**
     * Resolves the values of an environment for its delivery, including the
     * values of the items it references. Rejects over-limit definitions before resolving references.
     */
    internal suspend fun resolveForDelivery(id: String): EnvironmentResolvedSnapshot = vault.withVault { dependencies ->
        val secret = dependencies.environmentSecret(id)
        val collections = dependencies.getCollections().first()
        access.checkReadAccess(secret, environmentPermissions(secret, collections))
        val definition = secret.environment.runnableDefinition()
            ?: throw EnvironmentServiceException("unsupported_environment")
        if (!EnvironmentResolver.fitsLimits(definition)) {
            throw EnvironmentServiceException("invalid_environment")
        }
        val lookup = environmentReferenceLookup(secret, dependencies.getCiphers().first(), collections)
        when (val result = EnvironmentResolver.resolve(definition, lookup)) {
            is EnvironmentResolutionResult.Resolved -> result.snapshot
            is EnvironmentResolutionResult.Unresolved -> throw EnvironmentServiceException("unresolved_environment")
        }
    }

    /** The name of an environment the vault holds. */
    internal suspend fun environmentName(id: String): String = vault.withVault { it.environmentSecret(id).name }

    override suspend fun save(
        id: String?, accountId: String, name: String, definition: EnvironmentDefinition, description: String,
        ownership: EnvironmentOwnership?,
    ): String = vault.writeLock.withLock { saveLocked(id, accountId, name, definition, description, ownership) }

    @Suppress("ThrowsCount") // Distinct validation gates retain safe, actionable failure codes.
    private suspend fun saveLocked(
        id: String?, accountId: String, name: String, definition: EnvironmentDefinition, description: String,
        ownership: EnvironmentOwnership? = null,
    ): String {
        checkEnvironmentOperation()
        if (name.isBlank() || name.length > MAX_NAME_LENGTH) throw EnvironmentServiceException("invalid_name")
        if (!EnvironmentResolver.fitsLimits(definition) || EnvironmentResolver.validate(definition).isNotEmpty()) {
            throw EnvironmentServiceException("invalid_environment")
        }
        try {
            EnvironmentFormat.encode(definition)
        } catch (_: IllegalArgumentException) {
            // The checks above cover everything but the serialized-size bound.
            throw EnvironmentServiceException("environment_too_large")
        }
        return vault.withVault { dependencies ->
            if (dependencies.getProfiles().first().none { it.accountId == accountId }) {
                throw EnvironmentServiceException("not_found")
            }
            val old = id?.let { dependencies.environmentSecret(it) }
            if (old != null) {
                val permissions = environmentPermissions(old, dependencies.getCollections().first())
                if (!permissions.canWrite) throw EnvironmentServiceException("permission_denied")
                access.checkElevatedAccess(old)
                checkEditableEnvironment(old, accountId, ownership)
            }
            val target = ownership ?: old?.environmentOwnership() ?: EnvironmentOwnership()
            val request = createEnvironmentRequest(old, accountId, name, definition, description, target)
            checkEnvironmentOperation()
            dependencies.addCipher(mapOf(id to request)).bind().single()
        }
    }

    override suspend fun rename(id: String, name: String) {
        vault.writeLock.withLock {
            val document = load(id)
            val definition = document.definition ?: throw EnvironmentServiceException("unsupported_environment")
            saveLocked(id, document.accountId, name, definition, document.description)
        }
    }

    internal suspend fun appendVariables(id: String, updates: List<Pair<String, String?>>) = vault.writeLock.withLock {
        val document = load(id)
        val definition = document.definition ?: throw EnvironmentServiceException("unsupported_environment")
        val variables = definition.variables.associateByTo(linkedMapOf()) { it.name }
        // A reference is only ever chosen in Keyguard; a client can not
        // replace it with a value of its own.
        val overwritesReference = updates.any { (name, value) ->
            value != null && variables[name]?.source is EnvironmentValueSource.Reference
        }
        if (overwritesReference) throw EnvironmentServiceException("variable_is_reference")
        updates.forEach { (name, value) ->
            if (value != null) variables[name] = EnvironmentVariable(name, EnvironmentValueSource.Literal(value))
            else if (name !in variables) variables[name] = EnvironmentVariable(name, EnvironmentValueSource.Unset)
        }
        saveLocked(
            id, document.accountId, document.name,
            EnvironmentDefinition(variables.values.toList()), document.description,
        )
    }

    override suspend fun trash(id: String) {
        vault.writeLock.withLock {
            vault.withVault { dependencies ->
                val secret = dependencies.environmentSecret(id)
                if (!environmentPermissions(secret, dependencies.getCollections().first()).canWrite) {
                    throw EnvironmentServiceException("permission_denied")
                }
                access.checkElevatedAccess(secret)
                checkEnvironmentOperation()
                dependencies.trashCipher(setOf(id)).bind()
            }
        }
        mounts.disableAll(id)
    }
}

private const val MAX_NAME_LENGTH = 256

/** An existing environment can not move to another account, organization or collections. */
private fun checkEditableEnvironment(secret: DSecret, accountId: String, ownership: EnvironmentOwnership?) {
    val supported = secret.environment.editableDefinition() != null
    if (secret.accountId != accountId || !secret.canEdit() || !supported) {
        throw EnvironmentServiceException("unsupported_environment")
    }
    val moved = ownership != null && (
        ownership.organizationId != secret.organizationId || ownership.collectionIds != secret.collectionIds
    )
    if (moved) throw EnvironmentServiceException("unsupported_environment")
}

internal fun DSecret.isEditable(permissions: EnvironmentPermissions) =
    environment.editableDefinition() != null && permissions.canWrite

private fun DSecret.environmentOwnership() = EnvironmentOwnership(
    folder = folderId?.let(EnvironmentFolder::Id) ?: EnvironmentFolder.None,
    organizationId = organizationId,
    collectionIds = collectionIds,
)

private fun createEnvironmentRequest(
    old: DSecret?, accountId: String, name: String, definition: EnvironmentDefinition, description: String,
    ownership: EnvironmentOwnership,
): CreateRequest = CreateRequest(
    ownership = CreateRequest.Ownership(
        accountId, (ownership.folder as? EnvironmentFolder.Id)?.id,
        ownership.organizationId, ownership.collectionIds,
    ),
    // Takes precedence: creates a new folder on save.
    ownership2 = CreateRequest.Ownership2(
        accountId, ownership.folder.toFolderInfo(), ownership.organizationId, ownership.collectionIds,
    ),
    title = name, note = description, favorite = old?.favorite ?: false, reprompt = old?.reprompt ?: false,
    uris = old?.uris.orEmpty().toPersistentList(), links = old?.links.orEmpty().toPersistentList(),
    fields = old?.fields.orEmpty().toPersistentList(), tags = old?.tags.orEmpty().toPersistentList(),
    attachments = old?.attachments.orEmpty().map { attachment ->
        when (attachment) {
            is DSecret.Attachment.Remote -> CreateRequest.Attachment.Remote(attachment.id, attachment.fileName)
            is DSecret.Attachment.Local -> CreateRequest.Attachment.Local(
                attachment.id, leParseUri(attachment.url), attachment.fileName, attachment.size, attachment.keyBase64,
            )
        }
    }.toPersistentList(),
    type = DSecret.Type.Environment, environment = definition, now = Clock.System.now(),
)
