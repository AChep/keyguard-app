package com.artemchep.keyguard.feature.environments

/** Three-way merge of environment definitions, variable by variable. */
object EnvironmentMerge {
    /**
     * Keeps a variable change made on one side, and an edit over a deletion.
     *
     * Returns `null` if both sides set a variable to different values: variables
     * often form one credential set, so picking a winner per variable could build
     * a set that never existed. Also `null` if a definition repeats a name or the
     * merged one exceeds the variable or payload limits. The caller then keeps one payload.
     */
    @Suppress("ReturnCount")
    fun merge(
        base: EnvironmentDefinition,
        local: EnvironmentDefinition,
        remote: EnvironmentDefinition,
    ): EnvironmentDefinition? {
        val baseSources = base.sourcesByName() ?: return null
        val localSources = local.sourcesByName() ?: return null
        val remoteSources = remote.sourcesByName() ?: return null
        // Literals are never interpolated, so the order only affects display.
        val names = remoteSources.keys + localSources.keys
        val variables = names.mapNotNull { name ->
            val baseSource = baseSources[name]
            val localSource = localSources[name]
            val remoteSource = remoteSources[name]
            val source = when {
                localSource == remoteSource -> localSource
                localSource == baseSource -> remoteSource
                remoteSource == baseSource -> localSource
                localSource == null -> remoteSource
                remoteSource == null -> localSource
                else -> return null
            }
            source?.let { EnvironmentVariable(name, it) }
        }
        return EnvironmentDefinition(variables)
            .takeIf(EnvironmentResolver::fitsLimits)
            ?.takeIf(::fitsPayloadLimit)
    }

    private fun EnvironmentDefinition.sourcesByName(): Map<String, EnvironmentValueSource>? = variables
        .associate { it.name to it.source }
        .takeIf { it.size == variables.size }

    // Additions from both sides can outgrow the limit.
    private fun fitsPayloadLimit(definition: EnvironmentDefinition): Boolean = try {
        EnvironmentFormat.encode(definition)
        true
    } catch (_: IllegalArgumentException) {
        false
    }
}
