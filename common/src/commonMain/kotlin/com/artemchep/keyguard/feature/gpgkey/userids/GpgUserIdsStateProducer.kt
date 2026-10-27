package com.artemchep.keyguard.feature.gpgkey.userids

import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgUserIdChange
import com.artemchep.keyguard.common.service.crypto.GpgUserIdInfo
import com.artemchep.keyguard.common.service.crypto.GpgUserIdMutationError
import com.artemchep.keyguard.common.service.crypto.GpgUserIdReplacementResult
import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationResult
import com.artemchep.keyguard.common.service.crypto.GpgUserIdUpdateResult
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.common.util.REGEX_EMAIL
import com.artemchep.keyguard.feature.gpgkey.replacement.gpgUserIdReplacementFailureMessage
import com.artemchep.keyguard.feature.gpgkey.replacement.translateGpgUserIdReplacementMessage
import com.artemchep.keyguard.feature.gpgkey.revocation.gpgUserIdRevocationFailureMessage
import com.artemchep.keyguard.feature.gpgkey.userids.GpgUserIdsState.Page
import com.artemchep.keyguard.feature.home.vault.add.DraftGpgKeyMutationCoordinator
import com.artemchep.keyguard.feature.home.vault.add.DraftGpgKeyMutationOutcome
import com.artemchep.keyguard.feature.home.vault.add.GpgKeyMutationGuard
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.nativecrypto.isValidOpenPgpUserId
import com.artemchep.keyguard.res.*
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import kotlin.time.Instant

/** One subflow owned by the item editor. No operation persists or publishes the draft. */
@Suppress("TooManyFunctions") // Keep draft transitions and their small presentation helpers together.
internal class GpgUserIdsStateProducer(
    private val scope: CoroutineScope,
    private val mutations: GpgKeyMutationGuard,
    private val coordinator: DraftGpgKeyMutationCoordinator,
    translator: TranslatorScope,
    private val formatDateTime: (Instant) -> String,
) : TranslatorScope by translator {
    data class Editor(
        val open: Boolean = false,
        val page: Page = Page.List,
        val revision: Int = 0,
        val snapshot: GpgKeyMutationGuard.Snapshot? = null,
        val identity: GpgUserIdInfo? = null,
        val form: GpgUserIdForm = GpgUserIdForm(),
        val error: String? = null,
        val retry: (() -> Unit)? = null,
    ) {
        /** A new revision invalidates callbacks bound to the previous page. */
        fun reset(open: Boolean = true) = Editor(open = open, revision = revision + 1)
    }

    private val mutableEditor = MutableStateFlow(Editor())
    val editor = mutableEditor.asStateFlow()

    fun open() {
        if (!coordinator.inProgress.value) {
            mutableEditor.update { it.reset() }
        }
    }

    private data class Context(
        val key: GeneratedGpgKey,
        val info: GpgPublicKeyInfo?,
        val loading: Boolean,
        val busy: Boolean,
        val snapshot: GpgKeyMutationGuard.Snapshot?,
    ) {
        /** Each identity's own flags authorize its actions; this gates editing at all. */
        val canEdit = key.privateKeyArmored.isNotBlank() && info != null && !loading && snapshot != null
        val canModify = canEdit && info?.canModifyUserIds == true
        val available = canEdit && !busy
    }

    suspend fun state(
        key: GeneratedGpgKey,
        info: GpgPublicKeyInfo?,
        loading: Boolean,
        busy: Boolean,
        editor: Editor,
    ): GpgUserIdsState? {
        if (!editor.open) return null
        val context = Context(key, info, loading, busy, mutations.snapshot().takeIf { it.key == key })
        val valid = editor.page == Page.Edit && validForm(editor.form)
        val formError = formError(editor, info, valid)
        return GpgUserIdsState(
            title = translate(title(editor)),
            message = message(editor, context),
            error = editor.error ?: formError,
            busy = busy,
            loading = loading,
            page = editor.page,
            identities = if (editor.page == Page.List) identities(context) else selectedIdentity(editor),
            history = if (editor.page == Page.List) history(context) else emptyList(),
            fields = if (editor.page == Page.Edit) fields(editor, !busy) else emptyList(),
            add = addAction(editor, context),
            formats = formats(editor, busy),
            confirm = confirmAction(editor, context, valid && formError == null),
            back =
                action(
                    "back",
                    if (editor.page == Page.List) Res.string.close else Res.string.cancel,
                    enabled = !busy,
                ) {
                    mutableEditor.update { it.reset(open = editor.page != Page.List) }
                },
        )
    }

    private fun title(editor: Editor): StringResource =
        when (editor.page) {
            Page.List -> Res.string.gpg_key_user_ids
            Page.Edit -> if (editor.identity == null) {
                Res.string.gpg_user_ids_add
            } else Res.string.gpg_user_id_replacement_title
            Page.Revoke -> Res.string.gpg_user_id_revocation_title
        }

    private suspend fun message(
        editor: Editor,
        context: Context,
    ): String =
        translate(
            when (editor.page) {
                Page.Edit -> {
                    if (editor.identity == null) {
                        Res.string.gpg_user_ids_add_message
                    } else Res.string.gpg_user_ids_replace_message
                }

                Page.Revoke -> {
                    Res.string.gpg_user_ids_revoke_message
                }

                Page.List -> {
                    when {
                        context.loading -> Res.string.loading
                        context.key.privateKeyArmored.isBlank() -> Res.string.gpg_user_ids_private_required
                        context.info == null -> Res.string.gpg_key_expiry_key_unreadable
                        !context.info.canModifyUserIds -> Res.string.gpg_user_ids_read_only
                        !coordinator.userIdUpdateSupported -> Res.string.gpg_user_ids_unavailable
                        else -> Res.string.gpg_user_ids_draft_message
                    }
                }
            },
        )

    private suspend fun formError(
        editor: Editor,
        info: GpgPublicKeyInfo?,
        valid: Boolean,
    ): String? {
        val userId = editor.form.userId
        if (editor.page != Page.Edit || userId.isEmpty()) return null
        return when {
            !valid && editor.form.exact -> {
                translateGpgUserIdReplacementMessage(Res.string.gpg_user_id_replacement_invalid_message_with_limit)
            }

            !valid -> {
                translate(Res.string.gpg_user_ids_invalid_form)
            }

            info?.userIdDetails?.any { it.userId == userId } == true -> {
                translate(Res.string.gpg_user_id_replacement_duplicate_message)
            }

            info?.userIdHistory?.any { it.userId == userId && it.revoked } == true -> {
                translate(Res.string.gpg_user_id_replacement_retired_message)
            }

            else -> null
        }
    }

    private suspend fun confirmAction(
        editor: Editor,
        context: Context,
        submittable: Boolean,
    ): GpgUserIdsState.Action? {
        editor.retry?.let { retry ->
            return action(
                "retry:${editor.revision}",
                Res.string.retry,
                destructive = editor.page == Page.Revoke,
                enabled = context.available,
            ) {
                if (mutableEditor.value == editor && !coordinator.inProgress.value) retry()
            }
        }
        return when (editor.page) {
            Page.List -> {
                null
            }

            Page.Edit -> {
                action(
                    "confirm",
                    if (editor.identity == null) Res.string.add else Res.string.gpg_user_id_replacement_value_confirm,
                    enabled = context.available && submittable,
                ) { submit(editor) }
            }

            Page.Revoke -> {
                action(
                    "confirm",
                    Res.string.gpg_user_id_revocation_confirm,
                    destructive = true,
                    enabled = context.available,
                ) { submit(editor) }
            }
        }
    }

    private suspend fun identities(context: Context): List<GpgUserIdsState.Identity> =
        context.info?.userIdDetails.orEmpty().sortedByDescending { it.primary }.map { identity ->
            GpgUserIdsState.Identity(
                id = identity.identityId,
                value = identity.userId,
                status = identityStatus(identity),
                actions = identityActions(identity, context),
            )
        }

    private suspend fun identityActions(
        identity: GpgUserIdInfo,
        context: Context,
    ): List<GpgUserIdsState.Action> {
        val snapshot = context.snapshot
        if (!context.canEdit || snapshot == null) return emptyList()
        return buildList {
            if (coordinator.userIdUpdateSupported && identity.canMakePrimary && !identity.primary) {
                add(
                    action(
                        "${snapshot.generation}:${identity.identityId}:primary",
                        Res.string.gpg_user_ids_make_primary,
                        enabled = !context.busy,
                    ) {
                        makePrimary(snapshot, identity)
                    },
                )
            }
            if (coordinator.userIdReplacementSupported && identity.canReplace) {
                add(
                    action(
                        "${snapshot.generation}:${identity.identityId}:replace",
                        Res.string.gpg_user_ids_replace,
                        enabled = !context.busy,
                    ) {
                        showPage(Page.Edit, snapshot, identity)
                    },
                )
            }
            if (coordinator.userIdRevocationSupported && identity.canRevoke) {
                add(
                    action(
                        "${snapshot.generation}:${identity.identityId}:revoke",
                        Res.string.gpg_user_ids_revoke,
                        destructive = true,
                        enabled = !context.busy,
                    ) { showPage(Page.Revoke, snapshot, identity) },
                )
            }
        }
    }

    private suspend fun history(context: Context): List<GpgUserIdsState.Identity> =
        context.info?.userIdHistory.orEmpty().map { identity ->
            GpgUserIdsState.Identity(
                identity.identityId,
                identity.userId,
                identityStatus(identity) ?: translate(Res.string.gpg_user_ids_inactive),
                actions = identityActions(identity, context),
            )
        }

    private suspend fun identityStatus(identity: GpgUserIdInfo): String? = when {
        identity.revoked -> translate(Res.string.revoked)
        identity.primary -> translate(Res.string.gpg_user_ids_primary)
        else -> null
    }

    private fun selectedIdentity(editor: Editor): List<GpgUserIdsState.Identity> =
        listOfNotNull(editor.identity?.let { GpgUserIdsState.Identity(it.identityId, it.userId, null) })

    private suspend fun addAction(
        editor: Editor,
        context: Context,
    ): GpgUserIdsState.Action? {
        val snapshot = context.snapshot ?: return null
        return if (editor.page == Page.List && context.canModify && coordinator.userIdUpdateSupported) {
            action("add", Res.string.gpg_user_ids_add, enabled = !context.busy) {
                showPage(Page.Edit, snapshot, null)
            }
        } else null
    }

    private suspend fun formats(
        editor: Editor,
        busy: Boolean,
    ): ImmutableList<GpgUserIdsState.Format> {
        if (editor.page != Page.Edit) return persistentListOf()
        return listOf(false, true).map { exact ->
            val key = if (exact) "exact" else "structured"
            val selected = editor.form.exact == exact
            GpgUserIdsState.Format(
                key = key,
                selected = selected,
                action = action(
                    "format:${editor.revision}:$key",
                    if (exact) Res.string.gpg_user_ids_exact else Res.string.gpg_user_ids_structured,
                    enabled = !busy && (selected || editor.form.toggleFormat() != null),
                ) { selectFormat(editor.revision, exact) },
            )
        }.toImmutableList()
    }

    private fun selectFormat(revision: Int, exact: Boolean) {
        if (coordinator.inProgress.value) return
        mutableEditor.update { current ->
            if (current.revision != revision || current.form.exact == exact) {
                current
            } else {
                current.form.toggleFormat()?.let { form ->
                    current.copy(form = form, revision = current.revision + 1, error = null, retry = null)
                } ?: current
            }
        }
    }

    private suspend fun fields(
        editor: Editor,
        enabled: Boolean,
    ): List<GpgUserIdsState.Field> {
        suspend fun field(
            kind: GpgUserIdsState.Field.Kind,
            label: StringResource,
            value: String,
        ) = GpgUserIdsState.Field(
            kind = kind,
            label = translate(label),
            value =
                TextFieldModel(
                    id = "gpg-user-ids:${kind.name.lowercase()}",
                    text = value,
                    textRevision = editor.revision,
                    onChange =
                        if (enabled) {
                            { text ->
                                mutableEditor.update { current ->
                                    if (current.revision != editor.revision) {
                                        current
                                    } else {
                                        current.copy(
                                            form =
                                                when (kind) {
                                                    GpgUserIdsState.Field.Kind.Name -> current.form.copy(name = text)
                                                    GpgUserIdsState.Field.Kind.Email -> current.form.copy(email = text)
                                                    GpgUserIdsState.Field.Kind.Raw -> current.form.copy(raw = text)
                                                },
                                            error = null,
                                            retry = null,
                                        )
                                    }
                                }
                            }
                        } else {
                            null
                        },
                ),
        )
        return if (editor.form.exact) {
            listOf(
                field(GpgUserIdsState.Field.Kind.Raw, Res.string.gpg_user_id_replacement_value_label, editor.form.raw),
            )
        } else {
            listOf(
                field(GpgUserIdsState.Field.Kind.Name, Res.string.generic_name, editor.form.name),
                field(GpgUserIdsState.Field.Kind.Email, Res.string.gpg_user_ids_email_optional, editor.form.email),
            )
        }
    }

    private fun showPage(
        page: Page,
        snapshot: GpgKeyMutationGuard.Snapshot,
        identity: GpgUserIdInfo?,
    ) {
        if (coordinator.inProgress.value) return
        mutableEditor.update {
            it.reset().copy(
                page = page,
                snapshot = snapshot,
                identity = identity,
                form = identity?.userId?.let(GpgUserIdForm::parse) ?: GpgUserIdForm(),
            )
        }
    }

    private fun submit(editor: Editor) {
        val snapshot = editor.snapshot ?: return
        if (editor != mutableEditor.value || coordinator.inProgress.value) return
        val identity = editor.identity
        when {
            editor.page == Page.Revoke && identity != null -> {
                execute(
                    operation = { coordinator.revokeUserId(snapshot, identity.identityId) },
                    retryAt = { (it as? GpgUserIdRevocationResult.Error)?.retryAt },
                    error = { result ->
                        (result as? GpgUserIdRevocationResult.Error)?.let {
                            translateMutationError(it.reason, it.retryAt, revocation = true)
                        }
                    },
                )
            }

            editor.page == Page.Edit && validForm(editor.form) -> {
                if (identity == null) {
                    update(snapshot, GpgUserIdChange.Add(editor.form.userId))
                } else {
                    execute(
                        operation = { coordinator.replaceUserId(snapshot, identity.identityId, editor.form.userId) },
                        retryAt = { (it as? GpgUserIdReplacementResult.Error)?.retryAt },
                        error = { result ->
                            (result as? GpgUserIdReplacementResult.Error)?.let {
                                translateMutationError(it.reason, it.retryAt)
                            }
                        },
                    )
                }
            }
        }
    }

    private fun makePrimary(
        snapshot: GpgKeyMutationGuard.Snapshot,
        identity: GpgUserIdInfo,
    ) = update(snapshot, GpgUserIdChange.MakePrimary(identity.identityId))

    private fun update(
        snapshot: GpgKeyMutationGuard.Snapshot,
        change: GpgUserIdChange,
    ) = execute(
        operation = { coordinator.updateUserId(snapshot, change) },
        retryAt = { (it as? GpgUserIdUpdateResult.Error)?.retryAt },
        error = { result ->
            (result as? GpgUserIdUpdateResult.Error)?.let {
                translateMutationError(it.reason, it.retryAt)
            }
        },
    )

    private suspend fun translateMutationError(
        reason: GpgUserIdMutationError,
        retryAt: Instant?,
        revocation: Boolean = false,
    ): String = when {
        reason == GpgUserIdMutationError.TimeConflict && retryAt != null ->
            translate(Res.string.gpg_user_ids_retry_at, formatDateTime(retryAt))
        reason == GpgUserIdMutationError.TimeConflict -> translate(Res.string.gpg_user_ids_time_conflict)
        revocation -> translate(gpgUserIdRevocationFailureMessage(reason))
        else -> translateGpgUserIdReplacementMessage(gpgUserIdReplacementFailureMessage(reason))
    }

    private fun <T> execute(
        operation: suspend () -> DraftGpgKeyMutationOutcome<T>,
        error: suspend (T) -> String?,
        retryAt: (T) -> Instant? = { null },
    ) {
        scope.launch {
            val result = operation()
            if (result == DraftGpgKeyMutationOutcome.Busy) return@launch
            val message =
                when (result) {
                    is DraftGpgKeyMutationOutcome.Complete -> error(result.result)
                    DraftGpgKeyMutationOutcome.Conflict -> translate(Res.string.gpg_user_ids_conflict)
                    DraftGpgKeyMutationOutcome.Unsupported -> translate(Res.string.gpg_user_ids_unavailable)
                    else -> translate(Res.string.gpg_user_ids_failed)
                }
            mutableEditor.update {
                if (message == null) {
                    it.reset()
                } else {
                    val retry = (result as? DraftGpgKeyMutationOutcome.Complete)?.result?.let(retryAt)
                    it.copy(error = message, retry = if (retry != null) {
                        { execute(operation, error, retryAt) }
                    } else null)
                }
            }
        }
    }

    private suspend fun action(
        id: String,
        title: StringResource,
        destructive: Boolean = false,
        enabled: Boolean = true,
        block: () -> Unit,
    ) = GpgUserIdsState.Action(id, translate(title), destructive, block.takeIf { enabled })

    private fun validForm(form: GpgUserIdForm): Boolean =
        form.userId.isValidOpenPgpUserId() && (
            form.exact ||
                (
                    form.name.isNotBlank() && form.name.none { it in "<>()" } &&
                        (form.email.isBlank() || REGEX_EMAIL.matches(form.email.trim()))
                )
        )
}
