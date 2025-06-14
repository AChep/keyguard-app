package com.artemchep.keyguard.test

import arrow.optics.Getter
import com.artemchep.keyguard.common.service.patch.ModelDiffUtil
import com.artemchep.keyguard.common.service.patch.ModelDiffUtil.DiffFinderNode
import com.artemchep.keyguard.common.service.patch.counter
import com.artemchep.keyguard.common.service.patch.favoriteDish
import com.artemchep.keyguard.common.service.patch.firstName
import com.artemchep.keyguard.common.service.patch.ingredients
import com.artemchep.keyguard.common.service.patch.lastName
import com.artemchep.keyguard.common.service.patch.name
import kotlin.test.Test

class DiffTest {
    @Test
    fun `should inherit unknown fields from remote`() {
        val mergeRules = DiffFinderNode.Group<ModelDiffUtil.TestEntity, ModelDiffUtil.TestEntity>(
            lens = Getter.id(),
            identity = ModelDiffUtil.TestEntity::empty,
            children = listOf(
                DiffFinderNode.Leaf(ModelDiffUtil.TestEntity.firstName),
            ),
        )

        val oldRemote = ModelDiffUtil.TestEntity(
            firstName = "Artem",
            lastName = "A",
        )
        val curLocal = ModelDiffUtil.TestEntity(
            firstName = "Artem",
            lastName = "B",
        )
        val curRemote = ModelDiffUtil.TestEntity(
            firstName = "Artem",
            lastName = "C",
        )

        val merged = with(ModelDiffUtil()) {
            mergeRules.merge(
                oldRemote,
                curLocal,
                curRemote,
            ) as ModelDiffUtil.TestEntity
        }
        require(merged == curRemote) {
            "The merged entity is expected to inherit the fields that are " +
                    "not in the merge-rules from the latest remote! " +
                    "Actual: $merged."
        }
    }

    @Test
    fun `should merge fields`() {
        val mergeRules = DiffFinderNode.Group<ModelDiffUtil.TestEntity, ModelDiffUtil.TestEntity>(
            lens = Getter.id(),
            identity = ModelDiffUtil.TestEntity::empty,
            children = listOf(
                DiffFinderNode.Leaf(ModelDiffUtil.TestEntity.counter),
                DiffFinderNode.Leaf(ModelDiffUtil.TestEntity.firstName),
                DiffFinderNode.Leaf(ModelDiffUtil.TestEntity.lastName),
                DiffFinderNode.Group(
                    lens = ModelDiffUtil.TestEntity.favoriteDish,
                    identity = ModelDiffUtil.TestEntity.Dish::empty,
                    children = listOf(
                        DiffFinderNode.Leaf(ModelDiffUtil.TestEntity.Dish.name),
                        DiffFinderNode.Leaf(
                            lens = ModelDiffUtil.TestEntity.Dish.ingredients,
                            finder = ModelDiffUtil.DiffApplierByListValue(),
                        ),
                    ),
                ),
            ),
        )

        val cases = listOf(
            // Local:
            // - first name
            // - last name
            // Remote:
            // - last name
            // Expected: merged first name from local, last name from remote
            TestRow(
                oldRemote = ModelDiffUtil.TestEntity(
                    lastName = "A",
                ),
                curLocal = ModelDiffUtil.TestEntity(
                    firstName = "Marta",
                    lastName = "B",
                ),
                curRemote = ModelDiffUtil.TestEntity(
                    lastName = "C",
                ),
                expected = ModelDiffUtil.TestEntity(
                    firstName = "Marta",
                    lastName = "C",
                ),
            ),
            // Local:
            // - last name
            // Remote:
            // - last name
            // Expected: merged last name from remote
            TestRow(
                oldRemote = ModelDiffUtil.TestEntity(
                    lastName = "A",
                ),
                curLocal = ModelDiffUtil.TestEntity(
                    lastName = "B",
                ),
                curRemote = ModelDiffUtil.TestEntity(
                    lastName = "C",
                ),
                expected = ModelDiffUtil.TestEntity(
                    lastName = "C",
                ),
            ),
            // Local:
            // - Favorite dish
            // Remote:
            // Expected: merged favorite dish from local
            TestRow(
                oldRemote = ModelDiffUtil.TestEntity(
                    favoriteDish = null,
                ),
                curLocal = ModelDiffUtil.TestEntity(
                    favoriteDish = ModelDiffUtil.TestEntity.Dish(
                        name = "Pasta",
                        ingredients = listOf("Sugar"),
                    ),
                ),
                curRemote = ModelDiffUtil.TestEntity(
                    favoriteDish = null,
                ),
                expected = ModelDiffUtil.TestEntity(
                    favoriteDish = ModelDiffUtil.TestEntity.Dish(
                        name = "Pasta",
                        ingredients = listOf("Sugar"),
                    ),
                ),
            ),
            // Local:
            // - last name
            // - counter
            // Remote:
            // - last name
            // Expected: merged last name from remote, counter from local
            TestRow(
                oldRemote = ModelDiffUtil.TestEntity(
                    lastName = "A",
                ),
                curLocal = ModelDiffUtil.TestEntity(
                    lastName = "B",
                    counter = 2,
                ),
                curRemote = ModelDiffUtil.TestEntity(
                    lastName = "C",
                ),
                expected = ModelDiffUtil.TestEntity(
                    lastName = "C",
                    counter = 2,
                ),
            ),
            // Local:
            // - added favorite dish name
            // - added one item to a list
            // Remote:
            // - added one item to a list
            // Expected: merged both items to a list + favorite dish name
            TestRow(
                oldRemote = ModelDiffUtil.TestEntity(
                ),
                curLocal = ModelDiffUtil.TestEntity(
                    favoriteDish = ModelDiffUtil.TestEntity.Dish(
                        name = "Pancake",
                        ingredients = listOf("Sugar"),
                    ),
                ),
                curRemote = ModelDiffUtil.TestEntity(
                    favoriteDish = ModelDiffUtil.TestEntity.Dish(
                        ingredients = listOf("Flour"),
                    ),
                ),
                expected = ModelDiffUtil.TestEntity(
                    favoriteDish = ModelDiffUtil.TestEntity.Dish(
                        name = "Pancake",
                        ingredients = listOf(
                            "Sugar",
                            "Flour",
                        ),
                    ),
                ),
            ),
        )
        cases.forEach { row ->
            val merged = with(ModelDiffUtil()) {
                mergeRules.merge(
                    row.oldRemote,
                    row.curLocal,
                    row.curRemote,
                ) as ModelDiffUtil.TestEntity
            }
            require(merged == row.expected) {
                "The merged entity is unexpected! " +
                        "Actual: $merged. " +
                        "Expected: ${row.expected}."
            }
        }
    }

    private data class TestRow(
        val oldRemote: ModelDiffUtil.TestEntity,
        val curLocal: ModelDiffUtil.TestEntity,
        val curRemote: ModelDiffUtil.TestEntity,
        val expected: ModelDiffUtil.TestEntity,
    )
}
