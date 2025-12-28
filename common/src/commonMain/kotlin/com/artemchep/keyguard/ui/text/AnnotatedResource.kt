package com.artemchep.keyguard.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.util.Locale
import kotlin.uuid.Uuid

@Composable
fun annotatedResource(
    resource: StringResource,
    vararg args: Pair<Any, SpanStyle>,
): AnnotatedString = withHumanReadableException(resource) {
    // Generate a bunch of placeholders to use as
    // arguments. Later, we will replace those with
    // actual values.
    val placeholders = remember {
        Array(args.size) { createPlaceholder() }
    }
    val value = stringResource(resource, *placeholders)
    return remember(value) {
        rebuild(
            value,
            placeholders = placeholders,
            args = args,
        )
    }
}

suspend fun TranslatorScope.annotate(
    resource: StringResource,
    vararg args: Pair<Any, SpanStyle>,
): AnnotatedString = withHumanReadableException(resource) {
    // Generate a bunch of placeholders to use as
    // arguments. Later, we will replace those with
    // actual values.
    val placeholders = Array(args.size) { createPlaceholder() }
    val value = translate(resource, *placeholders)
    return rebuild(
        value,
        placeholders = placeholders,
        args = args,
    )
}

private fun createPlaceholder(): String {
    return "[" + Uuid.random() + "]"
}

private fun createPlaceholderRegex(): Regex {
    return "\\[([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12})]"
        .toRegex()
}

private inline fun <T> withHumanReadableException(
    resource: StringResource,
    block: () -> T,
) = runCatching {
    block()
}.getOrElse { e ->
    val language = Locale.getDefault().isO3Language
    val msg = "Failed to annotate ${resource.key}@$language resource!"
    throw RuntimeException(msg, e)
}

private fun rebuild(
    source: String,
    placeholders: Array<String>,
    args: Array<out Pair<Any, SpanStyle>>,
) = buildAnnotatedString {
    val regex = createPlaceholderRegex()
    val nodes = mutableListOf<Node>()

    var lastEndIndex = 0
    regex.findAll(source).forEach { matchResult ->
        val text = source
            .substring(
                startIndex = lastEndIndex,
                endIndex = matchResult.range.first,
            )
        if (text.isNotEmpty()) {
            nodes += Node.Value(text)
        }

        val placeholder = matchResult.value
        nodes += Node.Placeholder(placeholder)

        lastEndIndex = matchResult.range.last + 1
    }
    if (lastEndIndex < source.length) {
        val text = source.substring(startIndex = lastEndIndex)
        if (text.isNotEmpty()) {
            nodes += Node.Value(text)
        }
    }

    nodes.forEach { node ->
        when (node) {
            is Node.Value -> append(node.value)
            is Node.Placeholder -> {
                // Find the actual value and append it with the
                // specified style.
                val index = placeholders
                    .indexOf(node.value)
                if (index != -1) {
                    val entry = args[index]
                    withStyle(style = entry.second) {
                        append(entry.first.toString())
                    }
                }
            }
        }
    }
}

private sealed interface Node {
    data class Value(
        val value: String,
    ) : Node

    data class Placeholder(
        val value: String,
    ) : Node
}
