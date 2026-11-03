package com.artemchep.keyguard.feature.environments

/** In-memory editor state. Neither drafts nor imported text belong in persisted navigation. */
@Suppress("TooManyFunctions") // Every edit is a pure transform of this state.
data class EnvironmentEditorState(
    val id: String? = null,
    val accountId: String = "",
    val ownership: EnvironmentOwnership = EnvironmentOwnership(),
    val name: String = "",
    val description: String = "",
    val definition: EnvironmentDefinition = EnvironmentDefinition(),
    val editable: Boolean = true,
    val unsupported: Boolean = false,
    val revealed: Set<Int> = emptySet(),
    val importText: String = "",
    val importPreview: EnvironmentDefinition? = null,
    val error: Boolean = false,
) {
    /** References belong to this account; changing it must never reinterpret their provider IDs. */
    val canChangeAccount: Boolean
        get() = editable && !unsupported && id == null &&
            definition.variables.none { it.source is EnvironmentValueSource.Reference }

    val exceedsLimits: Boolean
        get() = !EnvironmentResolver.fitsLimits(definition)

    // The state is immutable, so the validation and encoding
    // run at most once per state.
    val canSave: Boolean by lazy {
        editable && !unsupported && !exceedsLimits && name.isNotBlank() && accountId.isNotBlank() &&
            EnvironmentResolver.validate(definition).isEmpty() &&
            runCatching { EnvironmentFormat.encode(definition) }.isSuccess
    }

    fun updateVariable(index: Int, transform: (EnvironmentVariable) -> EnvironmentVariable): EnvironmentEditorState {
        if (!editable || index !in definition.variables.indices) return this
        return copy(definition = definition.copy(variables = definition.variables.mapIndexed { i, value ->
            if (i == index) transform(value) else value
        }), error = false)
    }

    fun withName(value: String): EnvironmentEditorState = copy(name = value)

    fun withDescription(value: String): EnvironmentEditorState = copy(description = value)

    fun withImportText(value: String): EnvironmentEditorState =
        copy(importText = value, importPreview = null, error = false)

    /**
     * Changes where the environment is stored. References pin a new draft to
     * its account until explicitly removed. Existing items can only change folder.
     */
    fun withOwnership(accountId: String, ownership: EnvironmentOwnership): EnvironmentEditorState = when {
        !editable -> this
        id != null -> copy(ownership = this.ownership.copy(folder = ownership.folder))
        accountId != this.accountId && !canChangeAccount -> this
        else -> copy(accountId = accountId, ownership = ownership)
    }

    fun addVariable(source: EnvironmentValueSource, name: String = ""): EnvironmentEditorState =
        if (!editable || source is EnvironmentValueSource.Unsupported) this else copy(
            definition = definition.copy(
                variables = definition.variables + EnvironmentVariable(name, source),
            ),
        )

    fun removeVariable(index: Int): EnvironmentEditorState = if (!editable) this else copy(
        definition = definition.copy(variables = definition.variables.filterIndexed { i, _ -> i != index }),
        revealed = emptySet(),
    )

    /** Moves the variable at [from] to [to]; the order is kept in the `.env` files. */
    fun moveVariable(from: Int, to: Int): EnvironmentEditorState {
        val variables = definition.variables
        return if (!editable || from !in variables.indices || to !in variables.indices) this else copy(
            definition = definition.copy(
                variables = variables.toMutableList().apply { add(to, removeAt(from)) },
            ),
            revealed = emptySet(),
        )
    }

    fun withVariableName(index: Int, value: String): EnvironmentEditorState =
        updateVariable(index) { it.copy(name = value) }

    fun withVariableValue(index: Int, value: String): EnvironmentEditorState =
        updateVariable(index) { it.copy(source = EnvironmentValueSource.Literal(value)) }

    /** Changes how the value of a variable is sourced; unsupported sources are never written. */
    fun withVariableSource(index: Int, source: EnvironmentValueSource): EnvironmentEditorState =
        if (source is EnvironmentValueSource.Unsupported) this else updateVariable(index) {
            it.copy(source = source)
        }.let { draft -> draft.copy(revealed = draft.revealed - index) }

    fun toggleRevealed(index: Int): EnvironmentEditorState =
        copy(revealed = if (index in revealed) revealed - index else revealed + index)

    /** The value of a literal variable, `null` for any other source. */
    fun literalValue(index: Int): String? =
        (definition.variables.getOrNull(index)?.source as? EnvironmentValueSource.Literal)?.value

    fun previewImport(): EnvironmentEditorState = if (!editable) this else try {
        copy(importPreview = EnvironmentDotenv.parse(importText), error = false)
    } catch (_: IllegalArgumentException) {
        copy(importPreview = null, error = true)
    }

    fun applyImport(): EnvironmentEditorState = if (!editable) this else importPreview?.let {
        copy(definition = it, importText = "", importPreview = null, revealed = emptySet(), error = false)
    } ?: this

    override fun toString(): String = "EnvironmentEditorState([redacted])"
}

