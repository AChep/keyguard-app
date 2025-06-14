package com.artemchep.keyguard.common.service.patch

import arrow.optics.Lens
import arrow.optics.optics

class ModelDiffUtil {
    fun DiffFinderNode<out Any>.merge(
        base: Any?, // old remote
        a: Any?, // local
        b: Any?, // remote
        c: Any? = b, // set into
    ): Any? {
        val lensFixed = lens as Lens<Any, Any?>

        // Returns the value we focus on, or the
        // empty placeholder value.
        fun focusOrInitial(
            value: Any,
        ): Any? = lensFixed
            .getOrNull(value)

        var baseFocus = focusOrInitial(base!!)
        var aFocus = focusOrInitial(a!!)
        var bFocus = focusOrInitial(b!!)
        var cFocus = focusOrInitial(c!!)
        return when (this) {
            is DiffFinderNode.Group<*, *> -> {
                // Both of our targets are null, no need to
                // search for the similarities.
                if (aFocus == null && bFocus == null) {
                    return lensFixed.set(
                        source = c,
                        focus = null,
                    )
                }
                // The local version was not modified,
                // so we just use the remote.
                if (baseFocus == aFocus) {
                    return lensFixed.set(
                        source = c,
                        focus = bFocus,
                    )
                }

                val emptyObject by lazy {
                    identity()
                }
                baseFocus = baseFocus ?: emptyObject
                aFocus = aFocus ?: emptyObject
                bFocus = bFocus ?: emptyObject
                cFocus = cFocus ?: emptyObject

                val merged = children
                    .fold(cFocus) { y, child ->
                        child.merge(baseFocus, aFocus, bFocus, y)!!
                    }
                // We want to modify the properties of the remote object,
                // so all of the group's unlisted attributes are kept as
                // they are on remote.
                lensFixed.set(
                    source = c,
                    focus = merged,
                )
            }

            is DiffFinderNode.Leaf<*, *> -> {
                val finder = finder as DiffFinder<Any?>
                val focus = finder.compare(baseFocus, aFocus, bFocus)
                lensFixed.set(c, focus)
            }
        }
    }

    //
    // Find difference
    //

    sealed interface DiffFinderNode<Input : Any> {
        val lens: Lens<Input, *>

        class Group<Input : Any, Focus : Any>(
            override val lens: Lens<Input, out Focus?>,
            val identity: () -> Focus?,
            val children: List<DiffFinderNode<Focus>> = emptyList(),
        ) : DiffFinderNode<Input>

        class Leaf<Input : Any, Focus : Any>(
            override val lens: Lens<Input, out Focus?>,
            val finder: DiffFinder<Focus> = DiffApplierBySingleValue(),
        ) : DiffFinderNode<Input>
    }

    @FunctionalInterface
    interface DiffFinder<T> {
        fun compare(base: T, a: T, b: T): T
    }

    class DiffApplierBySingleValue<T> : DiffFinder<T> {
        override fun compare(
            base: T,
            a: T,
            b: T,
        ): T {
            return b.takeIf { it != base }
                ?: a.takeIf { it != base }
                // Otherwise return the value that is
                // the same between all the values.
                ?: base
        }
    }

    class DiffApplierByListValue<T> : DiffFinder<List<T>> {
        override fun compare(
            base: List<T>,
            a: List<T>,
            b: List<T>,
        ): List<T> {
            val baseToA = calculateDiff(base, a)
                .toMutableList()
            val baseToB = calculateDiff(base, b)
                .toMutableList()

            val actions = mutableListOf<DiffItem<T>>()
            while (true) {
                if (baseToA.isEmpty()) {
                    actions += baseToB
                    break
                }

                val item = baseToA.removeAt(0)
                baseToB.remove(item)
                actions += item
            }

            val out = base.toMutableList()
            actions.forEach { action ->
                when (action) {
                    is DiffItem.Add -> out += action.value
                    is DiffItem.Remove -> out -= action.value
                }
            }
            return out
        }

        private fun calculateDiff(
            old: List<T>,
            new: List<T>,
        ) = buildList<DiffItem<T>> {
            old.forEach {
                val exists = new.contains(it)
                if (exists) {
                    return@forEach
                }

                add(DiffItem.Remove(it))
            }

            // Find all the items the did not exist
            // in the old list.
            new.forEach {
                val exists = old.contains(it)
                if (exists) {
                    return@forEach
                }

                add(DiffItem.Add(it))
            }
        }

        private sealed interface DiffItem<T> {
            data class Add<T>(
                val value: T,
            ) : DiffItem<T>

            data class Remove<T>(
                val value: T,
            ) : DiffItem<T>
        }
    }

    //
    // Test
    //

    @optics
    data class TestEntity(
        val firstName: String = "Artem",
        val lastName: String = "Chepurnyi",
        val favoriteDish: Dish? = null,
        val counter: Int? = null,
    ) {
        companion object {
            fun empty() = TestEntity(
                firstName = "",
                lastName = "",
            )
        }

        @optics
        data class Dish(
            val name: String? = null,
            val ingredients: List<String>,
        ) {
            companion object {
                fun empty() = Dish(
                    name = "",
                    ingredients = emptyList(),
                )
            }
        }
    }
}
