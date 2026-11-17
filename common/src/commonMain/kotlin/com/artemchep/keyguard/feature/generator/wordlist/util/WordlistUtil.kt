package com.artemchep.keyguard.feature.generator.wordlist.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AddWordlistRequest
import com.artemchep.keyguard.common.model.DGeneratorWordlist
import com.artemchep.keyguard.common.model.EditWordlistRequest
import com.artemchep.keyguard.common.model.EditWordlistWordsRequest
import com.artemchep.keyguard.common.model.WordlistWordError
import com.artemchep.keyguard.common.usecase.AddWordlist
import com.artemchep.keyguard.common.usecase.EditWordlist
import com.artemchep.keyguard.common.usecase.EditWordlistWords
import com.artemchep.keyguard.common.usecase.GetWordlistPrimitive
import com.artemchep.keyguard.common.usecase.RemoveWordlistById
import com.artemchep.keyguard.common.usecase.impl.ReadWordlistUtil
import com.artemchep.keyguard.common.util.fileNameStem
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.createConfirmationDialogIntent
import com.artemchep.keyguard.feature.confirmation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.filepicker.FilePickerMime
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.KeyguardCipherFilter
import com.artemchep.keyguard.ui.icons.KeyguardWordlist
import com.artemchep.keyguard.ui.icons.icon
import kotlinx.coroutines.flow.first

object WordlistUtil {
    context(stateScope: RememberStateFlowScope)
    suspend fun onRename(
        confirmationRouteFactory: ConfirmationRouteFactory,
        editWordlist: EditWordlist,
        entity: DGeneratorWordlist,
    ) = with(stateScope) {
        val nameKey = "name"
        val nameItem = ConfirmationRoute.Args.Item.StringItem(
            key = nameKey,
            value = entity.name,
            title = translate(Res.string.generic_name),
            type = ConfirmationRoute.Args.Item.StringItem.Type.Text,
            canBeEmpty = false,
        )

        val items = listOfNotNull(
            nameItem,
        )
        val route = confirmationRouteFactory.registerRouteResultReceiver(
            args = ConfirmationRoute.Args(
                icon = icon(
                    main = Icons.Outlined.KeyguardCipherFilter,
                    secondary = Icons.Outlined.Edit,
                ),
                title = translate(Res.string.wordlist_edit_wordlist_title),
                items = items,
                docUrl = null,
            ),
        ) { result ->
            if (result is ConfirmationResult.Confirm) {
                val name = result.data[nameKey] as? String
                    ?: return@registerRouteResultReceiver

                val request = EditWordlistRequest(
                    id = entity.idRaw,
                    name = name,
                )
                editWordlist(request)
                    .launchIn(appScope)
            }
        }
        val intent = NavigationIntent.NavigateToRoute(route)
        navigate(intent)
    }

    /**
     * Asks for a name and a file, then adds the wordlist. The dialog
     * starts with [initialFile] selected, if there's one.
     */
    context(stateScope: RememberStateFlowScope)
    suspend fun onNewFromFile(
        confirmationRouteFactory: ConfirmationRouteFactory,
        addWordlist: AddWordlist,
        initialFile: ConfirmationRoute.Args.Item.FileItem.File? = null,
    ) = with(stateScope) {
        val nameKey = "name"
        val nameItem = ConfirmationRoute.Args.Item.StringItem(
            key = nameKey,
            value = defaultWordlistName(initialFile?.name),
            title = translate(Res.string.generic_name),
            type = ConfirmationRoute.Args.Item.StringItem.Type.Text,
            canBeEmpty = false,
        )

        val fileKey = "file"
        val fileItem = ConfirmationRoute.Args.Item.FileItem(
            key = fileKey,
            value = initialFile,
            title = translate(Res.string.wordlist),
            mimeTypes = listOf(
                "text/plain",
                "text/wordlist",
                // Diceware lists ship as PGP-signed .asc files.
                FilePickerMime.PGP_SIGNATURE,
            ),
        )

        val items = listOfNotNull(
            nameItem,
            fileItem,
        )
        val route = confirmationRouteFactory.registerRouteResultReceiver(
            args = ConfirmationRoute.Args(
                icon = icon(
                    main = Icons.Outlined.KeyguardWordlist,
                    secondary = Icons.Outlined.Add,
                ),
                title = translate(Res.string.wordlist_add_wordlist_title),
                items = items,
                docUrl = null,
            ),
        ) { result ->
            if (result is ConfirmationResult.Confirm) {
                val name = result.data[nameKey] as? String
                    ?: return@registerRouteResultReceiver
                val file = result.data[fileKey] as? ConfirmationRoute.Args.Item.FileItem.File
                    ?: return@registerRouteResultReceiver

                val wordlist = AddWordlistRequest.Wordlist.FromFile(
                    uri = file.uri,
                )
                val request = AddWordlistRequest(
                    name = name,
                    wordlist = wordlist,
                )
                addWordlist(request)
                    .launchIn(appScope)
            }
        }
        val intent = NavigationIntent.NavigateToRoute(route)
        navigate(intent)
    }

    context(stateScope: RememberStateFlowScope)
    suspend fun onNewFromUrl(
        confirmationRouteFactory: ConfirmationRouteFactory,
        addWordlist: AddWordlist,
    ) = with(stateScope) {
        val nameKey = "name"
        val nameItem = ConfirmationRoute.Args.Item.StringItem(
            key = nameKey,
            value = "",
            title = translate(Res.string.generic_name),
            type = ConfirmationRoute.Args.Item.StringItem.Type.Text,
            canBeEmpty = false,
        )

        val urlKey = "url"
        val urlItem = ConfirmationRoute.Args.Item.StringItem(
            key = urlKey,
            value = "",
            title = translate(Res.string.url),
            type = ConfirmationRoute.Args.Item.StringItem.Type.Command,
            canBeEmpty = false,
        )

        val items = listOfNotNull(
            nameItem,
            urlItem,
        )
        val route = confirmationRouteFactory.registerRouteResultReceiver(
            args = ConfirmationRoute.Args(
                icon = icon(
                    main = Icons.Outlined.KeyguardWordlist,
                    secondary = Icons.Outlined.Add,
                ),
                title = translate(Res.string.wordlist_add_wordlist_title),
                items = items,
                docUrl = null,
            ),
        ) { result ->
            if (result is ConfirmationResult.Confirm) {
                val name = result.data[nameKey] as? String
                    ?: return@registerRouteResultReceiver
                val url = result.data[urlKey] as? String
                    ?: return@registerRouteResultReceiver

                val wordlist = AddWordlistRequest.Wordlist.FromUrl(
                    url = url,
                )
                val request = AddWordlistRequest(
                    name = name,
                    wordlist = wordlist,
                )
                addWordlist(request)
                    .launchIn(appScope)
            }
        }
        val intent = NavigationIntent.NavigateToRoute(route)
        navigate(intent)
    }

    /**
     * Asks for words, one per line, then adds the ones the wordlist
     * [wordlistId] does not hold yet.
     */
    context(stateScope: RememberStateFlowScope)
    suspend fun onAddWords(
        confirmationRouteFactory: ConfirmationRouteFactory,
        editWordlistWords: EditWordlistWords,
        getWordlistPrimitive: GetWordlistPrimitive,
        wordlistId: Long,
    ) = with(stateScope) {
        val item = ConfirmationRoute.Args.Item.StringItem(
            key = "words",
            title = translate(Res.string.wordlist_words),
            description = translate(Res.string.wordlist_add_words_description),
            canBeEmpty = false,
            singleLine = false,
        )
        onEditWords(
            confirmationRouteFactory = confirmationRouteFactory,
            editWordlistWords = editWordlistWords,
            getWordlistPrimitive = getWordlistPrimitive,
            wordlistId = wordlistId,
            item = item,
            title = translate(Res.string.wordlist_add_words_title),
            secondaryIcon = Icons.Outlined.Add,
            remove = emptySet(),
            parse = ::parseWords,
        )
    }

    /**
     * Asks for a new spelling of the [word] of the wordlist [wordlistId],
     * then replaces it.
     */
    context(stateScope: RememberStateFlowScope)
    suspend fun onEditWord(
        confirmationRouteFactory: ConfirmationRouteFactory,
        editWordlistWords: EditWordlistWords,
        getWordlistPrimitive: GetWordlistPrimitive,
        wordlistId: Long,
        word: String,
    ) = with(stateScope) {
        val item = ConfirmationRoute.Args.Item.StringItem(
            key = "word",
            value = word,
            title = translate(Res.string.wordlist_word),
            canBeEmpty = false,
        )
        onEditWords(
            confirmationRouteFactory = confirmationRouteFactory,
            editWordlistWords = editWordlistWords,
            getWordlistPrimitive = getWordlistPrimitive,
            wordlistId = wordlistId,
            item = item,
            title = translate(Res.string.wordlist_edit_word_title),
            secondaryIcon = Icons.Outlined.Edit,
            remove = setOf(word),
            parse = { listOf(it) },
        )
    }

    /**
     * Asks for the text of the [item], then removes the words [remove] and adds
     * the words [parse] reads from the text. The dialog checks the change as the
     * text changes, against the words the wordlist holds when it opens.
     */
    // The parts of the dialog.
    @Suppress("LongParameterList")
    context(stateScope: RememberStateFlowScope)
    private suspend fun onEditWords(
        confirmationRouteFactory: ConfirmationRouteFactory,
        editWordlistWords: EditWordlistWords,
        getWordlistPrimitive: GetWordlistPrimitive,
        wordlistId: Long,
        item: ConfirmationRoute.Args.Item.StringItem,
        title: String,
        secondaryIcon: ImageVector,
        remove: Set<String>,
        parse: (String) -> List<String>,
    ) = with(stateScope) {
        val existing = getWordlistPrimitive(wordlistId)
            .first()
            .toSet()
        val errors = WordlistWordError.entries
            .associateWith { error ->
                TextHolder.Value(translate(error))
            }
        val intent = createConfirmationDialogIntent(
            confirmationRouteFactory = confirmationRouteFactory,
            item = item,
            icon = icon(
                main = Icons.Outlined.KeyguardWordlist,
                secondary = secondaryIcon,
            ),
            title = title,
            validate = { data ->
                val text = data[item.key] as? String
                val error = text?.let {
                    ReadWordlistUtil.patchErrorOrNull(
                        existing = existing,
                        remove = remove,
                        add = parse(it),
                    )
                }
                error
                    ?.let { mapOf(item.key to errors.getValue(it)) }
                    .orEmpty()
            },
        ) { text ->
            val request = EditWordlistWordsRequest(
                wordlistId = wordlistId,
                remove = remove,
                add = parse(text),
            )
            editWordlistWords(request)
                .launchIn(appScope)
        }
        navigate(intent)
    }

    context(stateScope: RememberStateFlowScope)
    suspend fun onDeleteWord(
        confirmationRouteFactory: ConfirmationRouteFactory,
        editWordlistWords: EditWordlistWords,
        wordlistId: Long,
        word: String,
    ) = with(stateScope) {
        val intent = createConfirmationDialogIntent(
            confirmationRouteFactory = confirmationRouteFactory,
            icon = icon(Icons.Outlined.Delete),
            title = translate(Res.string.wordlist_delete_word_confirmation_title),
            message = word,
        ) {
            val request = EditWordlistWordsRequest(
                wordlistId = wordlistId,
                remove = setOf(word),
            )
            editWordlistWords(request)
                .launchIn(appScope)
        }
        navigate(intent)
    }

    /** The words of the [text], one per line, as the import reads a file. */
    fun parseWords(text: String): List<String> = with(text) {
        ReadWordlistUtil.parseAsWordlist()
    }

    context(stateScope: RememberStateFlowScope)
    suspend fun onDeleteByItems(
        confirmationRouteFactory: ConfirmationRouteFactory,
        removeWordlistById: RemoveWordlistById,
        items: List<DGeneratorWordlist>,
    ) = with(stateScope) {
        val title = if (items.size > 1) {
            translate(Res.string.wordlist_delete_many_confirmation_title)
        } else {
            translate(Res.string.wordlist_delete_one_confirmation_title)
        }
        val message = items
            .joinToString(separator = "\n") { it.name }
        val intent = createConfirmationDialogIntent(
            confirmationRouteFactory = confirmationRouteFactory,
            icon = icon(Icons.Outlined.Delete),
            title = title,
            message = message,
        ) {
            val ids = items
                .map { it.idRaw }
                .toSet()
            removeWordlistById(ids)
                .launchIn(appScope)
        }
        navigate(intent)
    }
}

/** Returns the text that explains the [error] to a user. */
suspend fun TranslatorScope.translate(
    error: WordlistWordError,
): String = when (error) {
    WordlistWordError.BLANK -> translate(Res.string.error_must_not_be_blank)
    WordlistWordError.TOO_LONG -> translate(
        Res.string.error_wordlist_word_too_long,
        ReadWordlistUtil.MAX_WORD_LENGTH,
    )

    WordlistWordError.LINE_BREAK -> translate(Res.string.error_must_not_contain_line_breaks)
    WordlistWordError.EXISTS -> translate(Res.string.error_wordlist_word_exists)
    WordlistWordError.NO_NEW_WORDS -> translate(Res.string.error_wordlist_no_new_words)
}

/**
 * The file name without its extension, so `eff_large_wordlist.txt`
 * becomes `eff_large_wordlist`.
 */
internal fun defaultWordlistName(
    fileName: String?,
): String = fileName?.trim()
    .orEmpty()
    .fileNameStem()
