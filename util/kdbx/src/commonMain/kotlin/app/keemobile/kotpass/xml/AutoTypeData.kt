package app.keemobile.kotpass.xml

import app.keemobile.kotpass.constants.AutoTypeObfuscation
import app.keemobile.kotpass.extensions.addBoolean
import app.keemobile.kotpass.extensions.childNodes
import app.keemobile.kotpass.extensions.getText
import app.keemobile.kotpass.models.AutoTypeData
import app.keemobile.kotpass.models.AutoTypeItem
import app.keemobile.kotpass.xml.FormatXml.Tags
import org.redundent.kotlin.xml.Node
import org.redundent.kotlin.xml.node

internal fun unmarshalAutoTypeData(node: Node): AutoTypeData {
    return AutoTypeData(
        enabled = node
            .firstOrNull(Tags.Entry.AutoType.Enabled)
            ?.getText()
            .toBoolean(),
        obfuscation = node
            .firstOrNull(Tags.Entry.AutoType.Obfuscation)
            ?.getText()
            ?.toInt()
            ?.let(AutoTypeObfuscation.entries::getOrNull)
            ?: AutoTypeObfuscation.None,
        defaultSequence = node
            .firstOrNull(Tags.Entry.AutoType.DefaultSequence)
            ?.getText(),
        items = unmarshalAutoTypeItems(node)
    )
}

private fun unmarshalAutoTypeItems(node: Node): List<AutoTypeItem> {
    return node
        .childNodes()
        .filter { it.nodeName == Tags.Entry.AutoType.Association }
        .mapNotNull {
            val window = it.firstOrNull(Tags.Entry.AutoType.Window)?.getText()
            val sequence = it.firstOrNull(Tags.Entry.AutoType.KeystrokeSequence)?.getText()

            if (window != null && sequence != null) {
                AutoTypeItem(window, sequence)
            } else {
                null
            }
        }
}

internal fun AutoTypeData.marshal(): Node {
    return node(Tags.Entry.AutoType.TagName) {
        element(Tags.Entry.AutoType.Enabled) { addBoolean(enabled) }
        element(Tags.Entry.AutoType.Obfuscation) { text(obfuscation.ordinal.toString()) }
        element(Tags.Entry.AutoType.DefaultSequence) { text(defaultSequence ?: "") }

        for (item in items) {
            element(Tags.Entry.AutoType.Association) {
                element(Tags.Entry.AutoType.Window) { text(item.window) }
                element(Tags.Entry.AutoType.KeystrokeSequence) { text(item.keystrokeSequence) }
            }
        }
    }
}
