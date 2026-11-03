package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.cipherlink.CipherLink
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentFieldReference
import com.artemchep.keyguard.feature.environments.EnvironmentPayload
import com.artemchep.keyguard.feature.environments.EnvironmentReferenceLookup
import com.artemchep.keyguard.feature.environments.EnvironmentReferenceResolution
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import com.artemchep.keyguard.feature.home.vault.link.CipherLinkTarget
import com.artemchep.keyguard.feature.home.vault.link.cipherLinkTargetsByRemoteId

/** The types of custom fields that hold a value a variable can reference. */
internal val environmentReferenceableFieldTypes = setOf(
    DSecret.Field.Type.Text,
    DSecret.Field.Type.Hidden,
    DSecret.Field.Type.Boolean,
)

/**
 * Returns the items that the references of an environment may point at,
 * keyed by their canonical provider id: the live, synced items of the same
 * account, but never the environment itself or another environment.
 */
fun environmentReferenceTargets(
    ciphers: List<DSecret>,
    accountId: String?,
    environmentId: String?,
): Map<String, DSecret> = environmentReferenceLinkTargets(
    ciphers = ciphers,
    accountId = accountId,
    environmentId = environmentId,
)
    .mapValues { (_, target) -> target.cipher }

/** The [environmentReferenceTargets] with the links that point at them. */
fun environmentReferenceLinkTargets(
    ciphers: List<DSecret>,
    accountId: String?,
    environmentId: String?,
): Map<String, CipherLinkTarget> = cipherLinkTargetsByRemoteId(
    ciphers = ciphers,
    accountId = accountId,
    excludedCipherId = environmentId,
)
    .filterValues { it.cipher.type != DSecret.Type.Environment }

internal fun environmentReferenceTargets(
    environment: DSecret,
    ciphers: List<DSecret>,
): Map<String, DSecret> = environmentReferenceTargets(
    ciphers = ciphers,
    accountId = environment.accountId,
    environmentId = environment.id,
)

/**
 * A field of an item that a variable can reference. A custom field whose
 * name is used more than once can not be told apart, so it is [ambiguous].
 */
internal data class EnvironmentReferenceableField(
    val reference: EnvironmentFieldReference,
    /** The type of a custom field, `null` for the password. */
    val type: DSecret.Field.Type?,
    val ambiguous: Boolean,
)

internal fun DSecret.environmentReferenceableFields(): List<EnvironmentReferenceableField> = buildList {
    if (login?.password != null) {
        this += EnvironmentReferenceableField(
            reference = EnvironmentFieldReference.Password,
            type = null,
            ambiguous = false,
        )
    }
    val customFields = fields.filter { !it.name.isNullOrBlank() && it.type in environmentReferenceableFieldTypes }
    val counts = customFields.groupingBy { it.name }.eachCount()
    customFields
        .distinctBy { it.name }
        .forEach { field ->
            val name = field.name.orEmpty()
            this += EnvironmentReferenceableField(
                reference = EnvironmentFieldReference.Custom(name),
                type = field.type,
                ambiguous = (counts[name] ?: 0) > 1,
            )
        }
}

/**
 * Resolves the references of an [environment]. A target hidden by its
 * collections is denied. Delivery ignores the master password prompt of
 * a target, the environment itself guards it; set [denyReprompt] to deny
 * such a target instead, as showing its value would skip its prompt.
 */
internal fun environmentReferenceLookup(
    environment: DSecret,
    ciphers: List<DSecret>,
    collections: List<DCollection>,
    denyReprompt: Boolean = false,
): EnvironmentReferenceLookup = environmentReferenceLookup(
    targets = environmentReferenceTargets(environment, ciphers),
    collections = collections,
    denyReprompt = denyReprompt,
)

/** @param targets The [environmentReferenceTargets] of the environment. */
internal fun environmentReferenceLookup(
    targets: Map<String, DSecret>,
    collections: List<DCollection>,
    denyReprompt: Boolean = false,
): EnvironmentReferenceLookup = EnvironmentReferenceLookup { reference ->
    val target = targets[reference.cipherId]
        ?: return@EnvironmentReferenceLookup EnvironmentReferenceResolution.NotFound
    val denied = denyReprompt && target.reprompt
    if (!denied && environmentPermissions(target, collections).canRead) {
        target.environmentReferenceValue(reference.field)
    } else {
        EnvironmentReferenceResolution.Denied
    }
}

/** The current value of the referenced [field] of this item. */
internal fun DSecret.environmentReferenceValue(
    field: EnvironmentFieldReference,
): EnvironmentReferenceResolution = when (field) {
    EnvironmentFieldReference.Password -> login?.password
        ?.let(EnvironmentReferenceResolution::Value)
        ?: EnvironmentReferenceResolution.NotFound

    is EnvironmentFieldReference.Custom -> {
        val matches = fields.filter { it.name == field.name && it.type in environmentReferenceableFieldTypes }
        when (matches.size) {
            0 -> EnvironmentReferenceResolution.NotFound
            1 -> EnvironmentReferenceResolution.Value(matches.single().value.orEmpty())
            else -> EnvironmentReferenceResolution.Ambiguous
        }
    }
}

/** The provider ids of the items an environment references. */
internal fun DSecret.environmentReferencedCipherIds(): Set<String> =
    (environment as? EnvironmentPayload.Decoded)
        ?.definition
        ?.referencedCipherIds()
        .orEmpty()

/** The provider ids of the items the variables reference. */
internal fun EnvironmentDefinition.referencedCipherIds(): Set<String> =
    variables.mapNotNullTo(mutableSetOf()) { (it.source as? EnvironmentValueSource.Reference)?.cipherId }

/** The items each environment references, keyed by the environment's local id. */
internal fun environmentReferencedSecretsByEnvironment(
    environments: List<DSecret>,
    ciphers: List<DSecret>,
): Map<String, List<DSecret>> {
    // The targets of an environment only differ from the targets of its
    // account in the provider id of the environment itself.
    val targetsByAccount = mutableMapOf<String, Map<String, DSecret>>()
    return environments
        .associate { environment ->
            val ids = environment.environmentReferencedCipherIds()
            if (ids.isEmpty()) return@associate environment.id to emptyList()
            val ownId = environment.service.remote?.id?.let(CipherLink::of)?.remoteCipherId
            val targets = if (ownId != null && ownId in ids) {
                environmentReferenceTargets(environment, ciphers)
            } else {
                targetsByAccount.getOrPut(environment.accountId) {
                    environmentReferenceTargets(ciphers, environment.accountId, environmentId = null)
                }
            }
            environment.id to ids.mapNotNull(targets::get)
        }
}
