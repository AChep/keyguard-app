package com.artemchep.keyguard.apple.generator

import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.service.relays.EmailRelayRegistry
import com.artemchep.keyguard.feature.generator.GeneratorRoute
import com.artemchep.keyguard.feature.generator.GeneratorState
import com.artemchep.keyguard.feature.generator.generatorStateProducer
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.throttleLatest
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.apple.model.ActionKeyAllocator
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

internal class GeneratorController(
    private val ctx: CoreContext,
) {
    /**
     * Lets the producer's "create login / SSH key" actions (an `AddRoute`) reach the stack interceptor instead of
     * the NoOp controller. Late-bound by [KeyguardCore].
     */
    var navigationInterceptorProvider: ((Scope) -> ((NavigationIntent) -> Boolean))? = null
    /**
     * Optional dependencies (history, profiles, email relays, wordlists) live in the per-session sub-DI, so the
     * generator needs an unlocked vault.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun makeSession(
        args: GeneratorRoute.Args = GeneratorRoute.Args(
            password = true,
            username = true,
            sshKey = true,
            gpgKey = true,
        ),
        scopeName: String = "generator",
        producerKey: String? = null,
        recordHistory: Boolean = true,
    ): GeneratorSession = GeneratorSession { publish, onResult ->
        val leContext = ctx.koin.get<LeContext>()
        ctx.launchSessionObserver(
            onLocked = {
                onResult(null)
                publish(GeneratorSnapshot.empty, GeneratorSessionActions())
            },
            onTeardown = {
                onResult(null)
                publish(GeneratorSnapshot.empty, GeneratorSessionActions())
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

            // The top-level GeneratorState rarely re-emits (only its options menu changes); the live data
            // (loaded flag, type picker, generated value, quick suggestions, filter form) flows through five
            // inner StateFlows it does NOT re-emit for, so combine them in. valueState is nullable, so fold it
            // in via a separate combine to keep null handling explicit.
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
                        onResult(inner.value?.source)
                        publish(snapshot, GeneratorSessionActions(
                            actions = actionHandlers,
                            switches = switchHandlers,
                            text = textHandlers,
                            counters = intHandlers,
                            length = lengthHandlers["length"],
                        ))
                    }
                }
        }
    }

    /** Pure apart from filling the handler-map out-params. */
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
        // Top options menu.
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

        // Type picker.
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

        // Generated value.
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

        // Filter form.
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

}
