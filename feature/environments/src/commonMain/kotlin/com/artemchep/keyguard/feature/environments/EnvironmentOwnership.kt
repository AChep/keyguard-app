package com.artemchep.keyguard.feature.environments

/** Where an environment is stored within its account. */
data class EnvironmentOwnership(
    val folder: EnvironmentFolder = EnvironmentFolder.None,
    val organizationId: String? = null,
    val collectionIds: Set<String> = emptySet(),
)

sealed interface EnvironmentFolder {
    data object None : EnvironmentFolder

    data class Id(
        val id: String,
    ) : EnvironmentFolder

    /** A folder that is created on save. */
    data class New(
        val name: String,
    ) : EnvironmentFolder
}
