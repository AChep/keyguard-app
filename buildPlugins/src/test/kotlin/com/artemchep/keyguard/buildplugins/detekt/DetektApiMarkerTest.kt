package com.artemchep.keyguard.buildplugins.detekt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetektApiMarkerTest {
    @Test
    fun `selects calls and callable references with optional whitespace`() {
        listOf("removeFirst", "removeLast").forEach { operation ->
            listOf(
                "values.$operation()",
                "$operation ()",
                "values::$operation",
                "MutableList<String>::$operation",
                "values::\n    $operation",
            ).forEach { source ->
                assertTrue(source, isSelected(source))
            }
        }
        assertTrue(isSelected("mutablePersistedFlow<String>(\"key\")"))
    }

    @Test
    fun `does not select similarly named helpers`() {
        listOf(
            "values.removeFirstOrNull()",
            "values::removeLastOrNull",
            "removeFirstUnresolvedRequestOrNull()",
            "fun removeLastEntry() = Unit",
        ).forEach { source ->
            assertFalse(source, isSelected(source))
        }
    }

    private fun isSelected(source: String): Boolean =
        DetektCustomRulesPlugin.GUARDED_API_MARKERS.any { containsDetektApiMarker(source, it) }
}
