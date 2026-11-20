@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package com.artemchep.keyguard.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import kotlin.test.assertNotNull

// Shared semantics queries for tests that drive an ImageComposeScene.
// Each focusable popup is a layer of its own, after the main one.

/**
 * Merged semantics nodes of the layers from [firstLayer] on, popups
 * last. The first layer is the main one.
 */
internal fun ImageComposeScene.mergedNodes(firstLayer: Int = 0): List<SemanticsNode> =
    semanticsOwners.drop(firstLayer).flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }

/** The merged node whose texts contain [text], in the layers from [firstLayer] on, or `null`. */
internal fun ImageComposeScene.nodeWithTextOrNull(text: String, firstLayer: Int = 0): SemanticsNode? =
    mergedNodes(firstLayer).firstOrNull { node -> text in node.texts() }

internal fun ImageComposeScene.nodeWithText(text: String): SemanticsNode =
    assertNotNull(nodeWithTextOrNull(text), "No node with the text $text")

/** The focused node of the topmost layer that has one, or `null`. */
internal fun ImageComposeScene.focusedNodeOrNull(): SemanticsNode? =
    semanticsOwners.toList().asReversed().firstNotNullOfOrNull { owner ->
        owner.getAllSemanticsNodes(mergingEnabled = true)
            .firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }
    }

internal fun ImageComposeScene.focusedNode(): SemanticsNode = assertNotNull(focusedNodeOrNull())

internal fun ImageComposeScene.focusedOrNull(): SemanticsConfiguration? = focusedNodeOrNull()?.config

internal fun ImageComposeScene.focused(): SemanticsConfiguration = focusedNode().config

/** The texts of the focused node, or an empty list. */
internal fun ImageComposeScene.focusedTexts(): List<String> = focusedNodeOrNull()?.texts().orEmpty()

internal fun SemanticsNode.texts(): List<String> =
    config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
