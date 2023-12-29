package com.artemchep.keyguard.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.compose.stringResource
import java.util.UUID

@Composable
fun annotatedResource(
    resource: StringResource,
    vararg args: Pair<Any, SpanStyle>,
): AnnotatedString {
    // Generate a bunch of placeholders to use as
    // arguments. Later, we will replace those with
    // actual values.
    val placeholders = remember {
        Array(args.size) { UUID.randomUUID().toString() }
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

fun TranslatorScope.annotate(
    resource: StringResource,
    vararg args: Pair<Any, SpanStyle>,
): AnnotatedString {
    // Generate a bunch of placeholders to use as
    // arguments. Later, we will replace those with
    // actual values.
    val placeholders =
        Array(args.size) { UUID.randomUUID().toString() }
    val value = translate(resource, *placeholders)
    return rebuild(
        value,
        placeholders = placeholders,
        args = args,
    )
}

private fun rebuild(
    source: String,
    placeholders: Array<String>,
    args: Array<out Pair<Any, SpanStyle>>,
) = buildAnnotatedString {
    var from = 0
    placeholders.forEachIndexed { pIndex, p ->
        val i = source.indexOf(p)
        if (i == -1) {
            // Skip, should not happen.
            return@forEachIndexed
        }
        append(source.substring(from, i))
        val entry = args[pIndex]
        withStyle(style = entry.second) {
            append(entry.first.toString())
        }
        from = i + p.length
    }
    append(source.substring(from))
}
