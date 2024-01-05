package com.artemchep.keyguard.common.util

private const val DIVIDER_START = '{'
private const val DIVIDER_END = '}'

private sealed interface Node {
    suspend fun eval(): String

    data class Placeholder(
        val parent: Placeholder?,
        var start: Int,
        val dividerStart: Char,
        val dividerEnd: Char,
        val getter: suspend (key: String) -> String?,
        val nodes: MutableList<Node> = mutableListOf(),
    ) : Node {
        override suspend fun eval(): String {
            val key = run {
                // If the number of nodes is 1, then
                // we can skip joining them.
                if (nodes.size == 1) {
                    return@run nodes
                        .first()
                        .eval()
                }

                buildString {
                    nodes.forEach { node ->
                        val s = node.eval()
                        append(s)
                    }
                }
            }
            val value = getter(key)
            return value
                ?: "$dividerStart$key$dividerEnd"
        }
    }

    data class Text(
        val value: String,
    ) : Node {
        override suspend fun eval(): String = value
    }
}

suspend fun String.simpleFormat2(
    dividerStart: Char = DIVIDER_START,
    dividerEnd: Char = DIVIDER_END,
    getter: suspend (key: String) -> String?,
): String {
    val root = Node.Placeholder(
        parent = null,
        start = 0,
        dividerStart = dividerStart,
        dividerEnd = dividerEnd,
        getter = { it },
    )
    var node: Node.Placeholder = root

    fun appendPrefixPlainTextToSelectedNode(i: Int) {
        // Copy the text before the command start
        // symbol. This text is the plain text node.
        if (i > node.start) {
            val plainText = substring(node.start, i)
            node.nodes += Node.Text(
                value = plainText,
            )
        }
    }

    var i = 0
    while (true) {
        val c = getOrNull(i)
        when (c) {
            null -> {
                appendPrefixPlainTextToSelectedNode(i)
                break
            }

            dividerStart -> {
                appendPrefixPlainTextToSelectedNode(i)
                val placeholderNode = Node.Placeholder(
                    parent = node,
                    start = i + 1, // Skip the command start symbol
                    dividerStart = dividerStart,
                    dividerEnd = dividerEnd,
                    getter = getter,
                )
                node.nodes += placeholderNode
                node = placeholderNode
            }

            dividerEnd -> {
                val parent = node.parent
                if (parent != null) {
                    appendPrefixPlainTextToSelectedNode(i)
                    node = parent
                    node.start = i + 1
                }
            }
        }
        i += 1
    }
    if (root !== node) {
        return this
    }
    return root.eval()
}
