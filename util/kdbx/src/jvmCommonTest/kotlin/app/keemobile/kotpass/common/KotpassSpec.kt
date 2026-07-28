package app.keemobile.kotpass.common

class KotpassSpec {
    private val path = ArrayDeque<String>()

    fun describe(
        name: String,
        block: KotpassSpec.() -> Unit,
    ) {
        path.addLast(name)
        try {
            block()
        } finally {
            path.removeLast()
        }
    }

    fun it(
        name: String,
        block: () -> Unit,
    ) {
        path.addLast(name)
        try {
            block()
        } catch (e: Throwable) {
            val testName = path.joinToString(" > ")
            throw AssertionError("Kotpass test failed: $testName", e)
        } finally {
            path.removeLast()
        }
    }
}

fun runKotpassSpec(block: KotpassSpec.() -> Unit) {
    KotpassSpec().block()
}
