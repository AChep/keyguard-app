package com.artemchep.keyguard.feature.home.vault.screen

import com.artemchep.keyguard.common.model.formatH
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.feature.decorator.ItemDecorator
import com.artemchep.keyguard.feature.decorator.ItemDecoratorDate
import com.artemchep.keyguard.feature.decorator.ItemDecoratorNone
import com.artemchep.keyguard.feature.decorator.ItemDecoratorTitle
import com.artemchep.keyguard.feature.home.vault.component.obscurePassword
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort
import com.artemchep.keyguard.feature.home.vault.search.sort.LastCreatedSort
import com.artemchep.keyguard.feature.home.vault.search.sort.LastModifiedSort
import com.artemchep.keyguard.feature.home.vault.search.sort.PasswordLastModifiedSort
import com.artemchep.keyguard.feature.home.vault.search.sort.PasswordSort
import com.artemchep.keyguard.feature.home.vault.search.sort.PasswordStrengthSort
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.CompiledQueryPlan
import com.artemchep.keyguard.feature.home.vault.util.AlphabeticalSortMinItemsSize
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*

/**
 * Picks the section decorator that matches the active sort order.
 * Shared between the phone/desktop and Wear OS vault lists.
 */
fun createVaultListSortDecorator(
    orderConfig: ComparatorHolder?,
    itemCount: Int,
    dateFormatter: DateFormatter,
): ItemDecorator<VaultItem2, VaultItem2.Item> = when {
    orderConfig?.comparator is AlphabeticalSort &&
            // it looks ugly on small lists
            itemCount >= AlphabeticalSortMinItemsSize ->
        ItemDecoratorTitle<VaultItem2, VaultItem2.Item>(
            selector = { it.title.text },
            factory = { id, text ->
                VaultItem2.Section(
                    id = id,
                    text = TextHolder.Value(text),
                )
            },
        )

    orderConfig?.comparator is LastCreatedSort ->
        ItemDecoratorDate<VaultItem2, VaultItem2.Item>(
            dateFormatter = dateFormatter,
            selector = { it.createdDate },
            factory = { id, text ->
                VaultItem2.Section(
                    id = id,
                    text = TextHolder.Value(text),
                )
            },
        )

    orderConfig?.comparator is LastModifiedSort ->
        ItemDecoratorDate<VaultItem2, VaultItem2.Item>(
            dateFormatter = dateFormatter,
            selector = { it.revisionDate },
            factory = { id, text ->
                VaultItem2.Section(
                    id = id,
                    text = TextHolder.Value(text),
                )
            },
        )

    orderConfig?.comparator is PasswordSort -> PasswordDecorator()

    orderConfig?.comparator is PasswordLastModifiedSort ->
        ItemDecoratorDate<VaultItem2, VaultItem2.Item>(
            dateFormatter = dateFormatter,
            selector = { it.passwordRevisionDate },
            factory = { id, text ->
                VaultItem2.Section(
                    id = id,
                    text = TextHolder.Value(text),
                )
            },
        )

    orderConfig?.comparator is PasswordStrengthSort -> PasswordStrengthDecorator()
    else -> ItemDecoratorNone
}

/**
 * Assembles the final vault list: the autofill suggestions block
 * (if any), then the items with sort-order section headers.
 * Shared between the phone/desktop, Apple and Wear OS vault lists.
 */
suspend fun decorateVaultItems(
    list: List<VaultItem2.Item>,
    preferredList: List<VaultItem2.Item>?,
    orderConfig: ComparatorHolder?,
    queryConfig: CompiledQueryPlan?,
    dateFormatter: DateFormatter,
): List<VaultItem2> {
    val decorator = when {
        // Search does not guarantee meaningful order that we can
        // show in the section.
        queryConfig?.hasScoringClauses == true -> ItemDecoratorNone
        else -> createVaultListSortDecorator(
            orderConfig = orderConfig,
            itemCount = list.size,
            dateFormatter = dateFormatter,
        )
    }

    val sectionIds = mutableSetOf<String>()
    return run {
        val out = mutableListOf<VaultItem2>()
        if (preferredList != null) {
            // We want to show the 'No suggestions' text if the suggestions
            // target does exist, but searching for suggestions returns no
            // items.
            if (preferredList.isEmpty()) {
                out += VaultItem2.NoSuggestions
            }
            preferredList.forEach { item ->
                out += item
            }

            // A section item for all items.
            if (list.isNotEmpty()) {
                val section = VaultItem2.Section(
                    id = VAULT_PREFERRED_ID_PREFIX + "end",
                    text = TextHolder.Res(Res.string.items_all),
                )
                out += section
            }
        }
        list.forEach { item ->
            if (!item.favourite || orderConfig?.favourites != true) {
                val section = decorator.getOrNull(item)
                if (section != null) {
                    // Some weird combinations of items might lead to
                    // duplicate # being used.
                    if (section.id !in sectionIds) {
                        sectionIds += section.id
                        out += section
                    } else {
                        val sections = sectionIds
                            .joinToString()

                        val msg =
                            "Duplicate sections prevented @ VaultList: $sections, [${section.id}]"
                        val exception = RuntimeException(msg)
                        recordException(exception)
                    }
                }
            }
            out += item
        }
        out
    }.ifEmpty {
        listOf(VaultItem2.NoItems)
    }
}

private typealias Decorator = ItemDecorator<VaultItem2, VaultItem2.Item>

private class PasswordDecorator : Decorator {
    /**
     * Last shown password, used to not repeat the sections
     * if it stays the same.
     */
    private var lastPassword: Any? = Any()

    override suspend fun getOrNull(item: VaultItem2.Item): VaultItem2? {
        val pw = item.password
        if (pw == lastPassword) {
            return null
        }

        lastPassword = pw
        return if (pw == null) {
            VaultItem2.Section(
                id = "decorator.pw.empty",
                text = Res.string.no_password.wrap(),
            )
        } else {
            val text = obscurePassword(pw)
            VaultItem2.Section(
                id = "decorator.pw.$pw",
                text = TextHolder.Value(text),
                caps = false,
            )
        }
    }
}

private class PasswordStrengthDecorator : Decorator {
    /**
     * Last shown password score, used to not repeat the sections
     * if it stays the same.
     */
    private var lastScore: Any? = Any()

    override suspend fun getOrNull(item: VaultItem2.Item): VaultItem2? {
        val score = item.score?.score
        if (score == lastScore) {
            return null
        }

        lastScore = score
        return if (score == null) {
            VaultItem2.Section(
                id = "decorator.pw_strength.empty",
                text = Res.string.no_password.wrap(),
            )
        } else {
            VaultItem2.Section(
                id = "decorator.pw_strength.${score.name}",
                text = TextHolder.Res(score.formatH()),
            )
        }
    }
}
