package com.artemchep.keyguard.apple.generator

import com.artemchep.keyguard.feature.generator.GeneratorState
import com.artemchep.keyguard.apple.KeyguardCore
import com.artemchep.keyguard.apple.model.VaultActionSnapshot

internal data class GeneratorInner(
    val suggestions: List<GeneratorState.Suggestion>,
    val loading: GeneratorState.Loading,
    val type: GeneratorState.Type,
    val filter: GeneratorState.Filter,
    val value: GeneratorState.Value?,
)

/** [id] routes back to [KeyguardCore.invokeGeneratorAction]; [selected] marks the chosen entry. */
data class GeneratorActionSnapshot(
    val id: String,
    val title: String,
    val selected: Boolean,
)

enum class GeneratorTypeItemKind {
    TYPE,
    SECTION,
}

/** A TYPE entry's [id] routes back to [KeyguardCore.invokeGeneratorAction]. */
data class GeneratorTypeItemSnapshot(
    val id: String,
    val kind: GeneratorTypeItemKind,
    val title: String?,
    val selected: Boolean,
)

/** [actions] are extra menu actions; copy / refresh route through the fixed ids "value:copy" / "value:refresh". */
data class GeneratorValueSnapshot(
    val title: String?,
    val value: String,
    val showStrength: Boolean,
    val canCopy: Boolean,
    val canRefresh: Boolean,
    val actions: List<GeneratorActionSnapshot>,
)

/** [id] routes its copy action; [length] is -1 when not shown. */
data class GeneratorSuggestionSnapshot(
    val id: String,
    val value: String,
    val length: Int,
)

data class GeneratorTipSnapshot(
    val text: String,
    val canHide: Boolean,
    val canLearnMore: Boolean,
)

data class GeneratorLengthSnapshot(
    val value: Int,
    val min: Int,
    val max: Int,
)

/** A min-count stepper attached to a switch filter (e.g. minimum digits). */
data class GeneratorCounterSnapshot(
    val value: Int,
    val min: Int,
    val max: Int,
)

enum class GeneratorFilterKind {
    SWITCH_FIELD,
    TEXT_FIELD,
    ENUM_FIELD,
    SECTION,
}

/**
 * One row of the generator filter form. The populated fields depend on [kind]:
 *  - [GeneratorFilterKind.SWITCH_FIELD]: [switchValue] / [switchEnabled] (+ optional
 *    [counter]); mutate via [KeyguardCore.setGeneratorSwitch] (key) and
 *    [KeyguardCore.setGeneratorCounter] ("<key>:counter").
 *  - [GeneratorFilterKind.TEXT_FIELD]: [textValue] / [textPlaceholder] / [textError];
 *    mutate via [KeyguardCore.setGeneratorText].
 *  - [GeneratorFilterKind.ENUM_FIELD]: [enumValue] (current label) + [enumOptions];
 *    select via [KeyguardCore.invokeGeneratorAction] on an option id.
 *  - [GeneratorFilterKind.SECTION]: [text] only.
 */
data class GeneratorFilterSnapshot(
    val key: String,
    val kind: GeneratorFilterKind,
    val title: String?,
    val text: String?,
    val switchValue: Boolean,
    val switchEnabled: Boolean,
    val textValue: String,
    val textRevision: Int = 0,
    val textPlaceholder: String?,
    val textError: String?,
    val enumValue: String,
    val enumOptions: List<GeneratorActionSnapshot>,
    val counter: GeneratorCounterSnapshot?,
)

data class GeneratorSnapshot(
    val loaded: Boolean,
    val typeTitle: String,
    val types: List<GeneratorTypeItemSnapshot>,
    val value: GeneratorValueSnapshot?,
    val suggestions: List<GeneratorSuggestionSnapshot>,
    val tip: GeneratorTipSnapshot?,
    val length: GeneratorLengthSnapshot?,
    val filters: List<GeneratorFilterSnapshot>,
    val options: List<GeneratorActionSnapshot>,
    val canOpenHistory: Boolean,
) {
    companion object {
        val empty = GeneratorSnapshot(
            loaded = false,
            typeTitle = "",
            types = emptyList(),
            value = null,
            suggestions = emptyList(),
            tip = null,
            length = null,
            filters = emptyList(),
            options = emptyList(),
            canOpenHistory = false,
        )
    }
}

enum class EmailRelayLoadStatus { LOADING, READY, FAILED }

data class EmailRelayListItemSnapshot(
    val id: String,
    val title: String,
    val service: String,
    val selected: Boolean,
    val actions: List<VaultActionSnapshot>,
)

/** A single frame of live rows, action availability, and selection from the entry's session. */
data class EmailRelayListSnapshot(
    val status: EmailRelayLoadStatus,
    val items: List<EmailRelayListItemSnapshot>,
    val selectionCount: Int = 0,
    val selectionActions: List<VaultActionSnapshot> = emptyList(),
    val canSelectAll: Boolean = false,
) {
    companion object {
        val empty = EmailRelayListSnapshot(EmailRelayLoadStatus.LOADING, emptyList())
    }
}

enum class EmailRelayActionKind { EDIT, DUPLICATE, DELETE }

/** Validated metadata captured before showing a form or confirming a deletion. */
data class EmailRelayActionRequestSnapshot(
    val kind: EmailRelayActionKind,
    val items: List<EmailRelayActionTargetSnapshot>,
)

data class EmailRelayActionTargetSnapshot(val id: String, val name: String)

enum class GeneratorHistoryItemKind {
    SECTION,
    VALUE,
}

/**
 * [date] / [type] are null for SECTION headers; [type] is the `GeneratorHistoryItem.Value.Type` name
 * (PASSWORD, SSH_KEY, …), or null when ambiguous. [actions] ids route back through
 * [KeyguardCore.invokeGeneratorHistoryItemAction].
 */
data class GeneratorHistoryItemSnapshot(
    val id: String,
    val kind: GeneratorHistoryItemKind,
    val title: String,
    val date: String?,
    val type: String?,
    val actions: List<VaultActionSnapshot> = emptyList(),
    val selected: Boolean = false,
    val selecting: Boolean = false,
)

data class GeneratorHistorySnapshot(
    val loaded: Boolean,
    val items: List<GeneratorHistoryItemSnapshot>,
    val options: List<VaultActionSnapshot> = emptyList(),
    val selectionCount: Int = 0,
    val selectionActions: List<VaultActionSnapshot> = emptyList(),
) {
    companion object {
        val empty = GeneratorHistorySnapshot(
            loaded = false,
            items = emptyList(),
            options = emptyList(),
            selectionCount = 0,
            selectionActions = emptyList(),
        )
    }
}

data class EmailRelayFieldSnapshot(
    val key: String,
    val title: String,
    val hint: String?,
    val fieldDescription: String?,
    val secret: Boolean,
    val canBeEmpty: Boolean,
    val value: String,
)

/**
 * [id] is null for a new entry. [KeyguardCore.loadEmailRelayServices] returns one blank form per service,
 * [KeyguardCore.loadEmailRelay] one prefilled from an existing entry.
 */
data class EmailRelayFormSnapshot(
    val id: String?,
    val type: String,
    val serviceName: String,
    val docUrl: String?,
    val name: String,
    val fields: List<EmailRelayFieldSnapshot>,
)

enum class WordlistLoadStatus { LOADING, READY, FAILED }

/** A row and its selection state arrive in the same immutable screen frame. */
data class WordlistListItemSnapshot(
    val id: String,
    val wordlistId: Long,
    val title: String,
    val counter: String,
    val selected: Boolean = false,
    val selecting: Boolean = false,
)

data class WordlistListSnapshot(
    val status: WordlistLoadStatus,
    val items: List<WordlistListItemSnapshot>,
    val selectionCount: Int = 0,
    val selectionActions: List<VaultActionSnapshot> = emptyList(),
    val canSelectAll: Boolean = false,
) {
    companion object {
        val empty = WordlistListSnapshot(WordlistLoadStatus.LOADING, emptyList())
    }
}

/** Validated targets captured when requesting a native confirmation. */
data class WordlistActionRequestSnapshot(
    val actionId: String,
    val items: List<WordlistActionTargetSnapshot>,
)

data class WordlistActionTargetSnapshot(val id: Long, val name: String)

/** UTF-16 offsets match Kotlin strings and Swift's attributed-text adapter. */
data class WordlistTextRangeSnapshot(val start: Int, val endExclusive: Int)

data class WordlistWordSnapshot(
    val id: String,
    val text: String,
    val highlights: List<WordlistTextRangeSnapshot> = emptyList(),
)

data class WordlistDetailSnapshot(
    val status: WordlistLoadStatus,
    val title: String,
    val wordCount: Int,
    val query: String,
    val queryRevision: Int,
    val resultQuery: String,
    val searching: Boolean,
    val notFound: Boolean,
    val words: List<WordlistWordSnapshot>,
) {
    companion object {
        val empty = WordlistDetailSnapshot(
            WordlistLoadStatus.LOADING, "", 0, "", 0, "", false, false, emptyList(),
        )
    }
}
