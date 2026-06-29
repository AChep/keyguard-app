package app.keemobile.kotpass.common.matchers

import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

infix fun Any?.shouldBe(expected: Any?) {
    when {
        this is ByteArray && expected is ByteArray -> assertContentEquals(expected, this)
        this is ByteArray && expected is List<*> -> assertContentEquals(expected, this.toList())
        this is List<*> && expected is ByteArray -> assertContentEquals(expected.toList(), this)
        this is IntArray && expected is IntArray -> assertContentEquals(expected, this)
        this is LongArray && expected is LongArray -> assertContentEquals(expected, this)
        this is Array<*> && expected is Array<*> -> assertContentEquals(expected.toList(), this.toList())
        else -> assertEquals(expected, this)
    }
}

infix fun Any?.shouldNotBe(expected: Any?) {
    assertNotEquals(expected, this)
}

@OptIn(ExperimentalContracts::class)
inline fun <reified T> Any?.shouldBeInstanceOf(): T {
    contract {
        returns() implies (this@shouldBeInstanceOf is T)
    }
    return assertIs<T>(this)
}

infix fun <T> T.shouldBeIn(collection: Collection<T>) {
    assertContains(collection, this)
}

infix fun <T> T.shouldNotBeIn(collection: Collection<T>) {
    assertTrue(this !in collection)
}

infix fun <T> Collection<T>.shouldContain(item: T) {
    assertContains(this, item)
}

infix fun <T> Collection<T>.shouldContainAll(items: Collection<T>) {
    assertTrue(containsAll(items), "Expected $this to contain all $items.")
}

fun Collection<*>.shouldBeEmpty() {
    assertTrue(isEmpty(), "Expected collection to be empty, but was $this.")
}

fun Collection<*>.shouldNotBeEmpty() {
    assertTrue(isNotEmpty(), "Expected collection to be non-empty.")
}

fun Map<*, *>.shouldBeEmpty() {
    assertTrue(isEmpty(), "Expected map to be empty, but was $this.")
}

fun Int.shouldNotBeZero() {
    assertTrue(this != 0, "Expected value to be non-zero.")
}

fun shouldNotThrowAny(block: () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        fail("Expected no exception to be thrown.", e)
    }
}
