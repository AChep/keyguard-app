package com.artemchep.keyguard.feature.home.vault.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import arrow.optics.optics
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DTag
import com.artemchep.keyguard.common.model.GroupableShapeItem
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.common.service.passkey.PassKeyServiceInfo
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.feature.attachments.model.AttachmentItem
import com.artemchep.keyguard.feature.navigation.keyboard.KeyShortcut
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemAction
import com.halilibo.richtext.commonmark.CommonmarkAstNodeParser
import com.halilibo.richtext.markdown.node.AstNode
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.StateFlow

@optics
sealed interface VaultViewItem {
    companion object

    val id: String

    interface Groupable<T> : GroupableShapeItem<T>

    data class Card(
        override val id: String,
        val elevation: Dp,
        val shapeState: Int = ShapeState.ALL,
        val data: DSecret.Card,
        val visibility: Visibility = Visibility(),
        val dropdown: ImmutableList<ContextItem> = persistentListOf(),
    ) : VaultViewItem, Groupable<Card> {
        companion object;

        override fun withShape(shape: Int) = copy(shapeState = shape)
    }

    data class Identity(
        override val id: String,
        val data: DSecret.Identity,
        /**
         * List of the callable actions appended
         * to the item.
         */
        val actions: List<FlatItemAction> = emptyList(),
    ) : VaultViewItem {
        companion object
    }

    data class QuickActions(
        override val id: String,
        val actions: ImmutableList<ContextItem> = persistentListOf(),
    ) : VaultViewItem {
        companion object
    }

    data class Action(
        override val id: String,
        val elevation: Dp = 0.dp,
        val shapeState: Int = ShapeState.ALL,
        val title: String,
        val text: String? = null,
        val leading: (@Composable RowScope.() -> Unit)? = null,
        val trailing: (@Composable RowScope.() -> Unit)? = null,
        val badge: Badge? = null,
        /**
         * List of the callable actions appended
         * to the item.
         */
        val onClick: (() -> Unit)? = null,
    ) : VaultViewItem, Groupable<Action> {
        companion object;

        data class Badge(
            val text: String,
            val score: Float,
        )

        override fun withShape(shape: Int) = copy(shapeState = shape)
    }

    data class Value(
        override val id: String,
        val elevation: Dp = 0.dp,
        val title: String?,
        val value: String,
        val maxLines: Int = Int.MAX_VALUE,
        val visibility: Visibility = Visibility(),
        val shapeState: Int = ShapeState.ALL,
        val monospace: Boolean = false,
        val colorize: Boolean = false,
        val leading: (@Composable RowScope.() -> Unit)? = null,
        val trailing: (@Composable RowScope.() -> Unit)? = null,
        val badge: Badge? = null,
        val badge2: List<StateFlow<Badge?>> = emptyList(),
        /**
         * List of the callable actions appended
         * to the item.
         */
        val dropdown: List<ContextItem> = emptyList(),
    ) : VaultViewItem, Groupable<Value> {
        companion object;

        sealed interface Style {
            data object Info : Style
            data object Action : Style
        }

        data class Badge(
            val text: String,
            val score: Float,
        )

        override fun withShape(shape: Int) = copy(shapeState = shape)
    }

    data class Error(
        override val id: String,
        val shapeState: Int = ShapeState.ALL,
        val name: String,
        val message: String?,
        val blob: String? = null,
        val timestamp: String,
        val onRetry: (() -> Unit)? = null,
        val onCopyBlob: (() -> Unit)? = null,
    ) : VaultViewItem, Groupable<Error> {
        companion object;

        override fun withShape(shape: Int) = copy(shapeState = shape)
    }

    data class Info(
        override val id: String,
        val shapeState: Int = ShapeState.ALL,
        val name: String,
        val message: String? = null,
    ) : VaultViewItem {
        companion object;
    }

    data class Switch(
        override val id: String,
        val shapeState: Int = ShapeState.ALL,
        val title: String,
        val value: Boolean,
        val dropdown: List<ContextItem> = emptyList(),
    ) : VaultViewItem, Groupable<Switch> {
        companion object;

        override fun withShape(shape: Int) = copy(shapeState = shape)
    }

    data class Passkey(
        override val id: String,
        val shapeState: Int = ShapeState.ALL,
        val value: String?,
        val source: DSecret.Login.Fido2Credentials,
        val onUse: (() -> Unit)? = null,
        val onClick: (() -> Unit)? = null,
    ) : VaultViewItem, Groupable<Passkey> {
        companion object;

        override fun withShape(shape: Int) = copy(shapeState = shape)
    }

    data class Note(
        override val id: String,
        val content: Content,
        val elevation: Dp = 0.dp,
        val visibility: Visibility = Visibility(),
    ) : VaultViewItem {
        companion object;

        sealed interface Content {
            companion object {
                fun of(
                    parser: CommonmarkAstNodeParser,
                    markdown: Boolean,
                    text: String,
                ): Content =
                    if (markdown) {
                        kotlin.runCatching {
                            val data = text.trimIndent()
                            val node = parser.parse(data)
                            Markdown(node)
                        }.getOrNull()
                    } else {
                        null
                    } ?: Text(text)
            }

            data class Markdown(
                val node: AstNode,
            ) : Content

            data class Text(
                val text: String,
            ) : Content
        }
    }

    data class Spacer(
        override val id: String,
        val height: Dp,
    ) : VaultViewItem {
        companion object
    }

    data class Label(
        override val id: String,
        val horizontalArrangement: Arrangement.Horizontal = Arrangement.Center,
        val text: AnnotatedString,
        val error: Boolean = false,
    ) : VaultViewItem {
        companion object
    }

    data class Tags(
        override val id: String,
        val tags: List<String>,
        val onClick: (String) -> Unit,
    ) : VaultViewItem {
        companion object;
    }

    data class Folder(
        override val id: String,
        val shapeState: Int = ShapeState.ALL,
        val nodes: List<FolderNode>,
        val onClick: () -> Unit,
    ) : VaultViewItem, Groupable<Folder> {
        companion object;

        data class FolderNode(
            val name: String,
            val onClick: () -> Unit,
        )

        override fun withShape(shape: Int) = copy(shapeState = shape)
    }

    data class Organization(
        override val id: String,
        val shapeState: Int = ShapeState.ALL,
        val title: String,
        val onClick: () -> Unit,
    ) : VaultViewItem, Groupable<Organization> {
        companion object;

        override fun withShape(shape: Int) = copy(shapeState = shape)
    }

    data class Collection(
        override val id: String,
        val shapeState: Int = ShapeState.ALL,
        val title: String,
        val onClick: () -> Unit,
    ) : VaultViewItem, Groupable<Collection> {
        companion object;

        override fun withShape(shape: Int) = copy(shapeState = shape)
    }

    data class Uri(
        override val id: String,
        val shapeState: Int = ShapeState.ALL,
        val title: AnnotatedString,
        val text: String? = null,
        val matchTypeTitle: String? = null,
        val warningTitle: String? = null,
        val icon: @Composable () -> Unit,
        /**
         * List of the callable actions appended
         * to the item.
         */
        val dropdown: List<ContextItem> = emptyList(),
        val overrides: List<Override> = emptyList(),
    ) : VaultViewItem, Groupable<Uri> {
        companion object;

        data class Override(
            val title: String,
            val text: String,
            val error: Boolean,
            /**
             * List of the callable actions appended
             * to the item.
             */
            val dropdown: List<ContextItem> = emptyList(),
        )

        override fun withShape(shape: Int) = copy(shapeState = shape)
    }

    data class Totp(
        override val id: String,
        val shapeState: Int = ShapeState.ALL,
        val copy: CopyText,
        val title: String,
        val shortcut: KeyShortcut? = null,
        val elevation: Dp,
        val totp: TotpToken,
        val localStateFlow: StateFlow<LocalState>,
    ) : VaultViewItem, Groupable<Totp> {
        companion object;

        @Immutable
        data class LocalState(
            val codes: PersistentList<List<String>>,
            val dropdown: PersistentList<ContextItem>,
        )

        override fun withShape(shape: Int) = copy(shapeState = shape)
    }

    data class InactiveTotp(
        override val id: String,
        val shapeState: Int = ShapeState.ALL,
        val chevron: Boolean,
        val onClick: (() -> Unit)?,
    ) : VaultViewItem, Groupable<InactiveTotp> {
        companion object;

        override fun withShape(shape: Int) = copy(shapeState = shape)
    }

    data class InactivePasskey(
        override val id: String,
        val shapeState: Int = ShapeState.ALL,
        val info: PassKeyServiceInfo,
        val onClick: () -> Unit,
    ) : VaultViewItem, Groupable<InactivePasskey> {
        companion object;

        override fun withShape(shape: Int) = copy(shapeState = shape)
    }

    data class ReusedPassword(
        override val id: String,
        val shapeState: Int = ShapeState.ALL,
        val count: Int,
        val onClick: () -> Unit,
    ) : VaultViewItem, Groupable<ReusedPassword> {
        companion object;

        override fun withShape(shape: Int) = copy(shapeState = shape)
    }

    data class Qr(
        override val id: String,
        val data: String,
    ) : VaultViewItem {
        companion object;
    }

    data class Section(
        override val id: String,
        val text: String? = null,
    ) : VaultViewItem {
        companion object
    }

    data class Button(
        override val id: String,
        val leading: (@Composable RowScope.() -> Unit)? = null,
        val text: String,
        val onClick: () -> Unit,
    ) : VaultViewItem {
        companion object
    }

    data class Attachment(
        override val id: String,
        val shapeState: Int = ShapeState.ALL,
        val item: AttachmentItem,
    ) : VaultViewItem, Groupable<Attachment> {
        companion object;

        override fun withShape(shape: Int) = copy(shapeState = shape)
    }

}

fun List<VaultViewItem>.transformShapes(): List<VaultViewItem> = this
    .mapIndexed { index, item ->
        when (item) {
            is VaultViewItem.Groupable<*> -> {
                val shapeState = getShapeState(
                    list = this,
                    index = index,
                    predicate = { el, _ -> el is VaultViewItem.Groupable<*> },
                )
                item.withShape(shapeState) as VaultViewItem
            }

            else -> item
        }
    }
