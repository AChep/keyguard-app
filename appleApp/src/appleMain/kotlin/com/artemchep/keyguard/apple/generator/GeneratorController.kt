package com.artemchep.keyguard.apple.generator

import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.main
import com.artemchep.keyguard.common.model.GetPasswordResult
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.service.relays.EmailRelayRegistry
import com.artemchep.keyguard.feature.generator.GeneratorRoute
import com.artemchep.keyguard.feature.generator.GeneratorState
import com.artemchep.keyguard.feature.generator.generatorStateProducer
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.throttleLatest
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.apple.model.ActionKeyAllocator
import com.artemchep.keyguard.apple.model.invokeAction
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.koin.core.scope.Scope

/**
 * The password / passphrase / username / email generator screen of the macOS
 * bridge. Runs the shared [generatorStateProducer] headlessly and projects its
 * [GeneratorState] into a flat [GeneratorSnapshot]; the SwiftUI screen mutates
 * it through the opaque-id `setGenerator*` / `invokeGenerator*` methods.
 */
internal class GeneratorController(
    private val ctx: CoreContext,
) {
    /**
     * Resolves the navigation interceptor handed to the generator producer, so its
     * "create login / SSH key" actions (which emit an `AddRoute`) reach the stack
     * interceptor instead of the NoOp controller. Late-bound by [KeyguardCore].
     */
    var navigationInterceptorProvider: ((Scope) -> ((NavigationIntent) -> Boolean))? = null
    /**
     * Routing tables rebuilt on every [GeneratorSnapshot] emission: they map the
     * snapshot's string ids / keys back to the live producer closures. The
     * SwiftUI generator screen only ever passes opaque ids / keys, mirroring the
     * login and vault detail screens.
     *  - [generatorActionHandlers]: any `() -> Unit` (type select, suggestion /
     *    value copy, refresh, menu options, enum option select, tip hide / learn
     *    more, open history).
     *  - [generatorSwitchHandlers] / [generatorTextHandlers] / [generatorIntHandlers]:
     *    the parameterised filter field setters, keyed by the filter item key
     *    (counter setters keyed by "<key>:counter").
     *  - [generatorLengthHandlers]: the value-length slider, keyed by "length".
     */
    private var generatorActionHandlers: Map<String, () -> Unit> = emptyMap()
    private var generatorSwitchHandlers: Map<String, (Boolean) -> Unit> = emptyMap()
    private var generatorTextHandlers: Map<String, (String) -> Unit> = emptyMap()
    private var generatorIntHandlers: Map<String, (Long) -> Unit> = emptyMap()
    private var generatorLengthHandlers: Map<String, (Int) -> Unit> = emptyMap()

    /**
     * Observes the password / passphrase / username / email generator by running
     * the shared [generatorStateProducer] in a headless
     * [com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope] —
     * exactly the way the login and vault detail screens reuse their producers.
     * All the generation logic (config building, suggestions, live value
     * generation, wordlists, email relays) stays in the shared producer; this
     * only projects its [GeneratorState] into a flat [GeneratorSnapshot].
     *
     * Optional dependencies (history, profiles, email relays, wordlists) live in
     * the per-session sub-DI carried by [com.artemchep.keyguard.common.model.VaultState.Main],
     * so the generator is only available once the vault is unlocked. While the
     * vault is not in the Main phase the callback receives [GeneratorSnapshot.empty].
     * Mutate the producer through the `setGenerator*` / `invokeGenerator*` input
     * methods. Callbacks run on the main thread.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeGenerator(
        args: GeneratorRoute.Args = GeneratorRoute.Args(
            password = true,
            username = true,
            sshKey = true,
            gpgKey = true,
        ),
        scopeName: String = "generator",
        producerKey: String? = null,
        recordHistory: Boolean = true,
        onResult: (GetPasswordResult?) -> Unit = {},
        onChange: (GeneratorSnapshot) -> Unit,
    ): KeyguardCancellable {
        val leContext = ctx.koin.get<LeContext>()
        return ctx.launchSessionObserver(
            onLocked = {
                generatorActionHandlers = emptyMap()
                generatorSwitchHandlers = emptyMap()
                generatorTextHandlers = emptyMap()
                generatorIntHandlers = emptyMap()
                generatorLengthHandlers = emptyMap()
                onResult(null)
                onChange(GeneratorSnapshot.empty)
            },
        ) { state ->
            val producerScope = this
            val interceptor = navigationInterceptorProvider?.invoke(state.sessionKoin)
            val producerFlow = with(state.sessionKoin) {
                ctx.koin.newHeadlessStateFlowScope(scopeName, producerScope, interceptor)
                    .generatorStateProducer(
                        mode = AppMode.Main,
                        args = args,
                        key = producerKey,
                        addGeneratorHistory = if (recordHistory) getOrNull() else null,
                        getPassword = get(),
                        getPasswordStrength = get(),
                        getProfiles = getOrNull(),
                        getEmailRelays = getOrNull(),
                        getWordlists = getOrNull(),
                        getWordlistPrimitive = getOrNull(),
                        cryptoGenerator = get(),
                        keyPairExport = get(),
                        publicKeyExport = get(),
                        privateKeyExport = get(),
                        gpgKeyExport = get(),
                        gpgPublicKeyExport = get(),
                        gpgPrivateKeyExport = get(),
                        numberFormatter = get(),
                        dateFormatter = get(),
                        getCanWrite = get(),
                        tldService = get(),
                        clipboardService = get(),
                        emailRelays = get<EmailRelayRegistry>().values,
                    )
            }
            // Single shared copy of the latest top-level state; the
            // producer is cold, so fan out from one StateFlow.
            val latest = producerFlow
                .map { loadable -> loadable.getOrNull() }
                .stateIn(this, SharingStarted.Eagerly, null)

            // Suggestions are regenerated wholesale (refresh, type switch), so
            // their ids carry a generation: a tap on the previous list misses
            // instead of copying a different value from the new one.
            var suggestions: List<GeneratorState.Suggestion>? = null
            var suggestionsGeneration = 0

            // Snapshot stream. The top-level GeneratorState rarely
            // re-emits (only its options menu changes); the live data
            // — loaded flag, type picker, generated value, quick
            // suggestions and the filter form — flows through five
            // inner StateFlows it does NOT re-emit for, so combine
            // them in: any tick re-runs the builder and pushes a fresh
            // snapshot. valueState is nullable, so fold it in via a
            // separate combine to keep null handling explicit.
            latest.filterNotNull()
                .flatMapLatest { gen ->
                    combine(
                        gen.suggestionsState,
                        gen.loadedState,
                        gen.typeState,
                        gen.filterState,
                    ) { suggestions, loading, type, filter ->
                        GeneratorInner(
                            suggestions = suggestions,
                            loading = loading,
                            type = type,
                            filter = filter,
                            value = null,
                        )
                    }.combine(gen.valueState) { inner, value ->
                        gen to inner.copy(value = value)
                    }
                }
                .throttleLatest()
                .collect { (gen, inner) ->
                    if (inner.suggestions !== suggestions) {
                        suggestions = inner.suggestions
                        suggestionsGeneration++
                    }
                    val actionHandlers = LinkedHashMap<String, () -> Unit>()
                    val switchHandlers = LinkedHashMap<String, (Boolean) -> Unit>()
                    val textHandlers = LinkedHashMap<String, (String) -> Unit>()
                    val intHandlers = LinkedHashMap<String, (Long) -> Unit>()
                    val lengthHandlers = LinkedHashMap<String, (Int) -> Unit>()
                    val snapshot = buildGeneratorSnapshot(
                        gen = gen,
                        inner = inner,
                        suggestionsGeneration = suggestionsGeneration,
                        leContext = leContext,
                        actionHandlers = actionHandlers,
                        switchHandlers = switchHandlers,
                        textHandlers = textHandlers,
                        intHandlers = intHandlers,
                        lengthHandlers = lengthHandlers,
                    )
                    ctx.publishOnMain {
                        generatorActionHandlers = actionHandlers
                        generatorSwitchHandlers = switchHandlers
                        generatorTextHandlers = textHandlers
                        generatorIntHandlers = intHandlers
                        generatorLengthHandlers = lengthHandlers
                        onResult(inner.value?.source)
                        onChange(snapshot)
                    }
                }
        }
    }

    /**
     * Builds the Swift-facing [GeneratorSnapshot], filling the handler maps so
     * the snapshot's string ids / keys map back to the live producer closures.
     * Pure apart from the out-params.
     */
    private suspend fun buildGeneratorSnapshot(
        gen: GeneratorState,
        inner: GeneratorInner,
        suggestionsGeneration: Int,
        leContext: LeContext,
        actionHandlers: LinkedHashMap<String, () -> Unit>,
        switchHandlers: LinkedHashMap<String, (Boolean) -> Unit>,
        textHandlers: LinkedHashMap<String, (String) -> Unit>,
        intHandlers: LinkedHashMap<String, (Long) -> Unit>,
        lengthHandlers: LinkedHashMap<String, (Int) -> Unit>,
    ): GeneratorSnapshot {
        // Top options menu (tips toggle, email relays, wordlists).
        val options = ArrayList<GeneratorActionSnapshot>()
        val optionKeys = ActionKeyAllocator("option")
        gen.options.forEach { ci ->
            if (ci !is FlatItemAction) return@forEach
            val title = textResource(ci.title, leContext)
            val id = optionKeys.keyFor(ci, title)
            ci.onClick?.let { actionHandlers[id] = it }
            options += GeneratorActionSnapshot(
                id = id,
                title = title,
                selected = ci.selected,
            )
        }

        // Type picker (password / passphrase / username / email / ...).
        val types = ArrayList<GeneratorTypeItemSnapshot>()
        val typeKeys = ActionKeyAllocator("type")
        var typeSectionIndex = 0
        inner.type.items.forEach { ci ->
            when (ci) {
                is FlatItemAction -> {
                    val title = textResource(ci.title, leContext)
                    val id = typeKeys.keyFor(ci, title)
                    ci.onClick?.let { actionHandlers[id] = it }
                    types += GeneratorTypeItemSnapshot(
                        id = id,
                        kind = GeneratorTypeItemKind.TYPE,
                        title = title,
                        selected = ci.selected,
                    )
                }

                // Sections are non-invokable markers; a positional id is fine (it is
                // only a snapshot/list key, never a handler-map key).
                is ContextItem.Section -> {
                    types += GeneratorTypeItemSnapshot(
                        id = "type:section:$typeSectionIndex",
                        kind = GeneratorTypeItemKind.SECTION,
                        title = ci.title,
                        selected = false,
                    )
                    typeSectionIndex++
                }

                is ContextItem.Custom -> Unit
            }
        }

        // Generated value (+ its copy / refresh / extra menu actions).
        val value = inner.value?.let { v ->
            v.onCopy?.let { actionHandlers["value:copy"] = it }
            v.onRefresh?.let { actionHandlers["value:refresh"] = it }
            val valueActions = ArrayList<GeneratorActionSnapshot>()
            val valueKeys = ActionKeyAllocator("value:action")
            (v.actions + v.dropdown.filterIsInstance<FlatItemAction>())
                .forEach { action ->
                    val title = textResource(action.title, leContext)
                    val id = valueKeys.keyFor(action, title)
                    action.onClick?.let { actionHandlers[id] = it }
                    valueActions += GeneratorActionSnapshot(
                        id = id,
                        title = title,
                        selected = action.selected,
                    )
                }
            GeneratorValueSnapshot(
                title = v.title,
                value = v.password,
                showStrength = v.strength,
                canCopy = v.onCopy != null,
                canRefresh = v.onRefresh != null,
                actions = valueActions,
            )
        }

        // Quick suggestions.
        val suggestions = inner.suggestions.mapIndexed { index, s ->
            val id = "suggestion:$suggestionsGeneration:$index"
            actionHandlers[id] = s.onCopy
            GeneratorSuggestionSnapshot(
                id = id,
                value = s.value,
                length = s.length ?: -1,
            )
        }

        // Filter form: tip, length slider, switch / text / enum rows.
        val tip = inner.filter.tip?.let { t ->
            t.onHide?.let { actionHandlers["tip:hide"] = it }
            t.onLearnMore?.let { actionHandlers["tip:learnMore"] = it }
            GeneratorTipSnapshot(
                text = t.text,
                canHide = t.onHide != null,
                canLearnMore = t.onLearnMore != null,
            )
        }
        val length = inner.filter.length?.let { l ->
            l.onChange?.let { lengthHandlers["length"] = it }
            GeneratorLengthSnapshot(
                value = l.value,
                min = l.min,
                max = l.max,
            )
        }
        val filters = inner.filter.items.map { item ->
            when (item) {
                is GeneratorState.Filter.Item.Switch -> {
                    item.model.onChange?.let { switchHandlers[item.key] = it }
                    val counter = item.counter?.let { c ->
                        c.onChange?.let { intHandlers["${item.key}:counter"] = it }
                        GeneratorCounterSnapshot(
                            value = c.number.toInt(),
                            min = c.min.toInt(),
                            max = c.max.toInt(),
                        )
                    }
                    GeneratorFilterSnapshot(
                        key = item.key,
                        kind = GeneratorFilterKind.SWITCH_FIELD,
                        title = item.title,
                        text = item.text,
                        switchValue = item.model.checked,
                        switchEnabled = item.model.onChange != null,
                        textValue = "",
                        textPlaceholder = null,
                        textError = null,
                        enumValue = "",
                        enumOptions = emptyList(),
                        counter = counter,
                    )
                }

                is GeneratorState.Filter.Item.Text -> {
                    item.model.onChange?.let { textHandlers[item.key] = it }
                    GeneratorFilterSnapshot(
                        key = item.key,
                        kind = GeneratorFilterKind.TEXT_FIELD,
                        title = item.title,
                        text = null,
                        switchValue = false,
                        switchEnabled = false,
                        textValue = item.model.text,
                        textRevision = item.model.textRevision,
                        textPlaceholder = item.model.hint,
                        textError = item.model.error,
                        enumValue = "",
                        enumOptions = emptyList(),
                        counter = null,
                    )
                }

                is GeneratorState.Filter.Item.Enum -> {
                    val enumKeys = ActionKeyAllocator("enum:${item.key}")
                    val enumOptions = ArrayList<GeneratorActionSnapshot>()
                    item.model.dropdown.forEach { ci ->
                        if (ci !is FlatItemAction) return@forEach
                        val title = textResource(ci.title, leContext)
                        val id = enumKeys.keyFor(ci, title)
                        ci.onClick?.let { actionHandlers[id] = it }
                        enumOptions += GeneratorActionSnapshot(
                            id = id,
                            title = title,
                            selected = ci.selected,
                        )
                    }
                    GeneratorFilterSnapshot(
                        key = item.key,
                        kind = GeneratorFilterKind.ENUM_FIELD,
                        title = item.title,
                        text = null,
                        switchValue = false,
                        switchEnabled = false,
                        textValue = "",
                        textPlaceholder = null,
                        textError = null,
                        enumValue = item.model.value,
                        enumOptions = enumOptions,
                        counter = null,
                    )
                }

                is GeneratorState.Filter.Item.Section -> GeneratorFilterSnapshot(
                    key = item.key,
                    kind = GeneratorFilterKind.SECTION,
                    title = null,
                    text = item.text,
                    switchValue = false,
                    switchEnabled = false,
                    textValue = "",
                    textPlaceholder = null,
                    textError = null,
                    enumValue = "",
                    enumOptions = emptyList(),
                    counter = null,
                )
            }
        }

        gen.onOpenHistory?.let { actionHandlers["history"] = it }

        return GeneratorSnapshot(
            loaded = inner.loading.loaded,
            typeTitle = inner.type.title,
            types = types,
            value = value,
            suggestions = suggestions,
            tip = tip,
            length = length,
            filters = filters,
            options = options,
            canOpenHistory = gen.onOpenHistory != null,
        )
    }

    /** Invokes a generator `() -> Unit` closure by its snapshot id / key. */
    fun invokeGeneratorAction(id: String) {
        generatorActionHandlers.invokeAction(id)
    }

    /** Sets a boolean generator filter switch identified by its filter key. */
    fun setGeneratorSwitch(key: String, value: Boolean) {
        generatorSwitchHandlers[key]?.invoke(value)
    }

    /** Writes text into a generator text filter identified by its filter key. */
    fun setGeneratorText(key: String, text: String) {
        generatorTextHandlers[key]?.invoke(text)
    }

    /** Sets an integer generator counter identified by its routing key. */
    fun setGeneratorCounter(key: String, value: Int) {
        generatorIntHandlers[key]?.invoke(value.toLong())
    }

    /** Sets the generated value length. */
    fun setGeneratorLength(value: Int) {
        generatorLengthHandlers["length"]?.invoke(value)
    }
}
