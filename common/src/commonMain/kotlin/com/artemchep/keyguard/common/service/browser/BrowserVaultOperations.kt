package com.artemchep.keyguard.common.service.browser

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import arrow.optics.Getter
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AddCipherOpenedHistoryRequest
import com.artemchep.keyguard.common.model.AddUriCipherRequest
import com.artemchep.keyguard.common.model.AutofillHint
import com.artemchep.keyguard.common.model.AutofillTarget
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryRequestType
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MatchDetection
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.model.UrlBlockScope
import com.artemchep.keyguard.common.model.displayName
import com.artemchep.keyguard.common.model.isPasswordHidden
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.passkey.canStorePasskey
import com.artemchep.keyguard.common.service.passkey.toWebAuthnCredential
import com.artemchep.keyguard.common.service.session.BrowserExtensionSessionDependencies
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.common.usecase.blocksFill
import com.artemchep.keyguard.common.usecase.blocksSave
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import com.artemchep.keyguard.common.util.ensureUrlScheme
import com.artemchep.keyguard.common.util.parseHttpUrlHostOrNull
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserError
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserReply
import com.artemchep.keyguard.feature.browserextension.protocol.FillResponse
import com.artemchep.keyguard.feature.browserextension.protocol.GenerateRequest
import com.artemchep.keyguard.feature.browserextension.protocol.GenerateResponse
import com.artemchep.keyguard.feature.browserextension.protocol.HandleRequest
import com.artemchep.keyguard.feature.browserextension.protocol.NeverSaveRequest
import com.artemchep.keyguard.feature.browserextension.protocol.SaveRequest
import com.artemchep.keyguard.feature.browserextension.protocol.SearchItem
import com.artemchep.keyguard.feature.browserextension.protocol.SearchRequest
import com.artemchep.keyguard.feature.browserextension.protocol.SearchResponse
import com.artemchep.keyguard.feature.browserextension.protocol.SuggestItem
import com.artemchep.keyguard.feature.browserextension.protocol.SuggestPasskey
import com.artemchep.keyguard.feature.browserextension.protocol.SuggestRequest
import com.artemchep.keyguard.feature.browserextension.protocol.SuggestResponse
import com.artemchep.keyguard.feature.browserextension.protocol.TotpResponse
import com.artemchep.keyguard.feature.browserextension.protocol.browserOk
import com.artemchep.keyguard.feature.generator.PasswordGeneratorConfigReader
import com.artemchep.keyguard.util.webauthn.WebAuthnAllowedCredentialDescriptors
import com.artemchep.keyguard.util.webauthn.canAnswer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The requests of one session that reach the vault, README section 5:
 * `suggest`, `search`, `fill`, `totp`, `generate`, `save` and `never_save`. The passkey requests
 * are served by [BrowserPasskeyCeremony]. The session checks the
 * gate of each request before it gets here.
 */
@Suppress("TooManyFunctions", "LongParameterList") // One function per step of the requests, one dependency per concern.
internal class BrowserVaultOperations(
    private val vaultAccess: BrowserVaultAccess,
    private val approvals: BrowserApprovals,
    private val saveWindows: BrowserSaveWindows,
    private val requestLog: BrowserRequestLog,
    private val cryptoGenerator: CryptoGenerator,
    private val tldService: TldService,
    private val passwordGenerator: PasswordGeneratorConfigReader,
    /** Checks the relying party of a `suggest`. */
    private val passkeys: BrowserPasskeys,
    private val logRepository: LogRepository,
    private val session: () -> BrowserExtensionSession,
    private val pairing: () -> BrowserPairing?,
    private val handles: BrowserHandles = BrowserHandles(cryptoGenerator),
) {
    /**
     * The logins that match the page of the request, by a URI or by a name
     * alike its site, and with `"passkey"`, the logins with a passkey of the
     * relying party; with their handles. Only the logins that match the page
     * by a URI are matched, see [uriMatcher]. The one used last on this device
     * comes first, marked.
     */
    @Suppress("ReturnCount") // Each refusal has its own error code.
    suspend fun suggest(request: SuggestRequest): BrowserReply {
        val page = BrowserPage.parse(request.url)
            ?: return BrowserReply.Error(BrowserError.INVALID_REQUEST)
        val kinds = request.kinds ?: listOf(KIND_LOGIN)
        val requestedRpId = request.rpId
            ?.takeIf { KIND_PASSKEY in kinds }
        if (KIND_PASSKEY in kinds && requestedRpId.isNullOrBlank()) {
            return BrowserReply.Error(BrowserError.INVALID_REQUEST)
        }
        val dependencies = vaultAccess.dependencies()
            ?: return BrowserReply.Error(BrowserError.VAULT_LOCKED)
        val blocks = dependencies.urlBlocksOf(page)
        // A rule that blocks autofill blocks saving too.
        val saveBlocked = blocks.any { it.blocksSave }
        if (blocks.any { it.blocksFill }) {
            val response = SuggestResponse(blocked = true, saveBlocked = true, truncated = false, items = emptyList())
            return browserOk(response)
        }
        // The page learns only the passkeys of a relying party it may use.
        val rpId = requestedRpId?.let { passkeys.validateRpId(it, page.origin) }
        if (requestedRpId != null && rpId == null) return BrowserReply.Error(BrowserError.SECURITY)
        val logins = if (KIND_LOGIN in kinds) dependencies.matches(page) else emptyList()
        val pageLogins = logins.mapTo(mutableSetOf()) { it.accountId to it.id }
        val passkeyLogins = rpId
            ?.let { dependencies.passkeyLogins(it) }
            .orEmpty()
            // A login of the page is listed once, with its passkeys.
            .filter { login -> (login.accountId to login.id) !in pageLogins }
        val found = logins + passkeyLogins
        val lastUsed = dependencies.lastUsed(found)
        // Most recently used first, before the list is cut. The sort is
        // stable: the logins never used follow in the order of the match.
        val matches = found.sortedByDescending { lastUsed[it.id] }
        val listed = matches.take(MAX_SUGGESTIONS)
        val site = page.site(tldService)
        val accountNames = dependencies.accountNames()
        val collections = dependencies.getCollections().first()
        val folderNames = dependencies.folderNames(listed)
        val isMatched = dependencies.uriMatcher(page, strictHttp = true)
        val items = listed
            .mapIndexed { index, cipher ->
                val used = index == 0 && cipher.id in lastUsed
                // By a URI only: a name alike the page, or a passkey of the relying party, doesn't match it.
                val matched = (cipher.accountId to cipher.id) in pageLogins && isMatched(cipher)
                suggestion(
                    cipher = cipher,
                    site = site,
                    rpId = rpId,
                    accountNames = accountNames,
                    collections = collections,
                    folderNames = folderNames,
                    matched = matched,
                    lastUsed = used,
                )
            }
        val response = SuggestResponse(
            blocked = false,
            saveBlocked = saveBlocked,
            truncated = matches.size > MAX_SUGGESTIONS,
            items = items,
        )
        return browserOk(response)
    }

    private fun suggestion(
        cipher: DSecret,
        site: String,
        rpId: String?,
        accountNames: Map<String, String>,
        collections: List<DCollection>,
        folderNames: Map<Pair<String, String>, String>,
        matched: Boolean,
        lastUsed: Boolean,
    ): SuggestItem {
        // Unmatched for a login listed by its name or for its passkeys: a release of it always prompts.
        val handle = BrowserItemHandle(
            accountId = cipher.accountId,
            cipherId = cipher.id,
            site = site,
            matched = matched,
        )
        val passkeys = rpId?.let { id ->
            cipher.discoverablePasskeys(id).map { passkey ->
                val passkeyHandle = BrowserPasskeyHandle(
                    accountId = cipher.accountId,
                    cipherId = cipher.id,
                    credentialId = passkey.credentialId,
                    rpId = id,
                )
                SuggestPasskey(
                    handle = handles.issue(passkeyHandle),
                    userName = passkey.userName.orEmpty(),
                    userDisplayName = passkey.userDisplayName?.takeIf { it.isNotEmpty() },
                )
            }
        }
        // Not a URI past the length cap, as a `data:` URL: one could take the
        // reply past its size limit. Left out, never cut: a copy would be wrong.
        val uris = cipher.uris
            .asSequence()
            .map { it.uri }
            .filter { it.length <= MAX_SUGGESTION_URI_LENGTH }
            .take(MAX_SUGGESTION_URIS)
            .toList()
        return SuggestItem(
            handle = handles.issue(handle),
            name = cipher.name,
            username = cipher.login?.username?.takeIf { it.isNotEmpty() },
            account = accountNames[cipher.accountId].orEmpty(),
            hasPassword = !cipher.login?.password.isNullOrEmpty(),
            hasTotp = cipher.totpToken() != null,
            reprompt = cipher.reprompt,
            passkeys = passkeys,
            lastUsed = true.takeIf { lastUsed },
            passwordHidden = true.takeIf { cipher.isPasswordHidden(collections) },
            matched = matched,
            uris = uris.takeIf { it.isNotEmpty() },
            uriCount = cipher.uris.size.takeIf { it > uris.size },
            folder = cipher.folderId?.let { folderNames[cipher.accountId to it] },
            revisionDate = cipher.revisionDate.toString(),
        )
    }

    /**
     * The logins whose name, username or URI host holds the query, with
     * their handles: those that match the page by a URI first, see
     * [uriMatcher], then those whose name starts with it. Metadata only; a
     * release of an unmatched one prompts.
     */
    @Suppress("ReturnCount") // Each refusal has its own error code.
    suspend fun search(request: SearchRequest): BrowserReply {
        val query = request.query.trim()
        if (query.countCodePoints() !in MIN_QUERY_LENGTH..MAX_QUERY_LENGTH) {
            return BrowserReply.Error(BrowserError.INVALID_REQUEST)
        }
        val page = BrowserPage.parse(request.url)
            ?: return BrowserReply.Error(BrowserError.INVALID_REQUEST)
        val dependencies = vaultAccess.dependencies()
            ?: return BrowserReply.Error(BrowserError.VAULT_LOCKED)
        if (dependencies.isBlocked(page)) {
            return browserOk(SearchResponse(blocked = true, truncated = false, items = emptyList()))
        }
        val needle = query.lowercase()
        val isMatched = dependencies.uriMatcher(page, strictHttp = true)
        val found = dependencies.logins()
            .filter { it.holds(needle) }
            // By a URI only: search doesn't list by a name alike the page.
            .map { cipher -> cipher to isMatched(cipher) }
            .sortedWith(
                compareBy<Pair<DSecret, Boolean>> { (cipher, matched) ->
                    when {
                        matched -> 0
                        cipher.name.startsWith(query, ignoreCase = true) -> 1
                        else -> 2
                    }
                }.thenBy(String.CASE_INSENSITIVE_ORDER) { (cipher, _) -> cipher.name },
            )
        val site = page.site(tldService)
        val accountNames = dependencies.accountNames()
        val collections = dependencies.getCollections().first()
        val items = found
            .take(MAX_SEARCH_RESULTS)
            .map { (cipher, matched) -> searchItem(cipher, site, matched, accountNames, collections) }
        val response = SearchResponse(
            blocked = false,
            truncated = found.size > MAX_SEARCH_RESULTS,
            items = items,
        )
        return browserOk(response)
    }

    private fun searchItem(
        cipher: DSecret,
        site: String,
        matched: Boolean,
        accountNames: Map<String, String>,
        collections: List<DCollection>,
    ): SearchItem {
        val handle = BrowserItemHandle(
            accountId = cipher.accountId,
            cipherId = cipher.id,
            site = site,
            matched = matched,
        )
        return SearchItem(
            handle = handles.issue(handle),
            name = cipher.name,
            username = cipher.login?.username?.takeIf { it.isNotEmpty() },
            account = accountNames[cipher.accountId].orEmpty(),
            hasPassword = !cipher.login?.password.isNullOrEmpty(),
            hasTotp = cipher.totpToken() != null,
            reprompt = cipher.reprompt,
            matched = matched,
            passwordHidden = true.takeIf { cipher.isPasswordHidden(collections) },
        )
    }

    /** The username and the password of the item of the handle, once the user approves. */
    suspend fun fill(request: HandleRequest): BrowserReply =
        release(request, CliAgentUsageHistoryRequestType.BROWSER_FILL, BrowserOperation::Fill) { _, cipher ->
            val login = cipher.login
            browserOk(
                FillResponse(
                    username = login?.username?.takeIf { it.isNotEmpty() },
                    password = login?.password?.takeIf { it.isNotEmpty() },
                ),
            )
        }

    /** The current one-time password of the item of the handle, once the user approves. */
    suspend fun totp(request: HandleRequest): BrowserReply =
        release(request, CliAgentUsageHistoryRequestType.BROWSER_TOTP, BrowserOperation::Totp) { dependencies, cipher ->
            val token = cipher.totpToken()
                ?: return@release BrowserReply.Error(BrowserError.INVALID_REQUEST)
            val code = dependencies.getTotpCode(token)
                .first()
                .getOrNull()
                ?: return@release BrowserReply.Error(BrowserError.INVALID_REQUEST)
            browserOk(TotpResponse(code = code.code))
        }

    /** A new password with the user's generator settings. Works while the vault is locked. */
    suspend fun generate(request: GenerateRequest): BrowserReply {
        val page = request.url?.let(BrowserPage::parse)
        if (request.url != null && page == null) return BrowserReply.Error(BrowserError.INVALID_REQUEST)
        val password = passwordGenerator.generate(page?.host)
        return password
            ?.let { browserOk(GenerateResponse(password = it)) }
            ?: BrowserReply.Error(BrowserError.NOT_SUPPORTED)
    }

    /**
     * Opens the app's window that saves the login, and answers with no fields
     * once it is on screen. The window shows the login of the same site and
     * username with the new password, or else a new login. One window per
     * session at a time.
     */
    suspend fun save(request: SaveRequest): BrowserReply =
        recorded(CliAgentUsageHistoryRequestType.BROWSER_SAVE, request.privateWindow) { usage ->
            val page = BrowserPage.parse(request.url)
                ?: return@recorded BrowserReply.Error(BrowserError.INVALID_REQUEST)
            usage.origin = page.origin
            val dependencies = vaultAccess.dependencies()
                ?: return@recorded BrowserReply.Error(BrowserError.VAULT_LOCKED)
            if (dependencies.isSaveBlocked(page)) return@recorded BrowserReply.Error(BrowserError.NOT_ALLOWED)
            val existing = dependencies.existingLogin(page, request)
            usage.cipherId = existing?.id
            val save = BrowserSaveRequest(
                id = cryptoGenerator.uuid(),
                session = session(),
                pairingName = pairing()?.name,
                webScheme = page.scheme,
                webDomain = page.host,
                username = request.username?.takeIf { it.isNotEmpty() },
                password = request.password?.takeIf { it.isNotEmpty() },
                cipherId = existing?.id,
            )
            if (!saveWindows.open(save)) return@recorded BrowserReply.Error(BrowserError.BUSY)
            val error = try {
                saveWindows.awaitShown(save.id)
            } catch (e: CancellationException) {
                // The request was cancelled before the window showed: it closes.
                saveWindows.close(save.id)
                throw e
            }
            error?.let { return@recorded BrowserReply.Error(it) }
            browserOk()
        }

    /**
     * Adds a blocked URI that stops the offer to save a login on the site of
     * the page, as the user asked in the save bar; answers with no fields.
     * Adds nothing if saving is already blocked there.
     */
    suspend fun neverSave(request: NeverSaveRequest): BrowserReply =
        recorded(CliAgentUsageHistoryRequestType.BROWSER_NEVER_SAVE, request.privateWindow) { usage ->
            val page = BrowserPage.parse(request.url)
                ?: return@recorded BrowserReply.Error(BrowserError.INVALID_REQUEST)
            usage.origin = page.origin
            val dependencies = vaultAccess.dependencies()
                ?: return@recorded BrowserReply.Error(BrowserError.VAULT_LOCKED)
            if (!dependencies.isSaveBlocked(page)) {
                val rule = DGlobalUrlBlock(
                    name = page.host,
                    description = "",
                    // The base domain of the host: the whole site.
                    uri = "${page.scheme}://${page.host}",
                    mode = MatchDetection.Domain,
                    createdDate = Clock.System.now(),
                    enabled = true,
                    // Autofill skips the offer while the vault is locked as well.
                    exposed = true,
                    scope = UrlBlockScope.SAVE,
                )
                dependencies.addUrlBlock(rule)
                    .attempt()
                    .bind()
                    .onLeft { e ->
                        val message = "Failed to add a blocked URI: ${e::class.simpleName}"
                        logRepository.post(TAG, message, LogLevel.WARNING)
                        return@recorded BrowserReply.Error(BrowserError.NOT_ALLOWED)
                    }
            }
            browserOk()
        }

    /**
     * Releases a secret of the item of the handle in [request] to its page:
     * checks that the page is on the site of the handle and that the user
     * approves the [operation], then answers with what [answer] reads from
     * the item as it is, if the approval still covers it, see [approvedItem].
     * Then records the use of the item, unless the page is of a private
     * window, and adds the page to an unmatched item, if the user asked.
     * One vault session serves the whole release.
     */
    private suspend fun release(
        request: HandleRequest,
        type: CliAgentUsageHistoryRequestType,
        operation: (BrowserRelease) -> BrowserOperation,
        answer: suspend (BrowserExtensionSessionDependencies, DSecret) -> BrowserReply,
    ): BrowserReply = recorded(type, request.privateWindow) { usage ->
        val handle = handles.resolve(request.handle) as? BrowserItemHandle
            ?: return@recorded BrowserReply.Error(BrowserError.INVALID_REQUEST)
        val (page, topPage) = request.pages()
            ?: return@recorded BrowserReply.Error(BrowserError.INVALID_REQUEST)
        usage.origin = page.origin
        // A handle never leaves the site of the page it was issued for.
        if (page.site(tldService) != handle.site) return@recorded BrowserReply.Error(BrowserError.NOT_ALLOWED)
        val vault = vaultAccess.currentVault()
            ?: return@recorded BrowserReply.Error(BrowserError.VAULT_LOCKED)
        val dependencies = vault.dependencies
        if (dependencies.isBlocked(page)) return@recorded BrowserReply.Error(BrowserError.NOT_ALLOWED)
        val cipher = dependencies.login(handle)
            ?: return@recorded BrowserReply.Error(BrowserError.INVALID_REQUEST)
        usage.cipherId = cipher.id
        if (type == CliAgentUsageHistoryRequestType.BROWSER_TOTP && cipher.totpToken() == null) {
            return@recorded BrowserReply.Error(BrowserError.INVALID_REQUEST)
        }
        // A matched handle doesn't outlive an edit of the item's URIs.
        val matched = handle.matched && dependencies.uriMatcher(page, strictHttp = true)(cipher)
        val approval = approve(vault, handle, matched, page, topPage, cipher, operation)
            .getOrElse { return@recorded BrowserReply.Error(it) }
        val item = approvedItem(vault, handle, page, approved = cipher, matched = matched)
            .getOrElse { return@recorded BrowserReply.Error(it) }
        answer(dependencies, item).also { reply ->
            if (reply is BrowserReply.Ok) {
                // A private window leaves no use, which would rank the item first on the site.
                if (request.privateWindow != true) {
                    val history = AddCipherOpenedHistoryRequest(
                        accountId = item.accountId,
                        cipherId = item.id,
                    )
                    dependencies.addCipherUsedAutofillHistory(history).attempt().bind()
                }
                if (approval.addUri) dependencies.addPage(item, page)
            }
        }
    }

    /**
     * The item of the [handle] as it is now, after the user approved its
     * release to the [page] as the [approved] item, or why the approval no
     * longer covers it: the vault session ended, the page was blocked, the
     * item turned on master-password re-prompt, or an item [matched] at
     * approval no longer matches. A change that asks for less, such as
     * turning re-prompt off, is still covered.
     */
    private suspend fun approvedItem(
        vault: BrowserVault,
        handle: BrowserItemHandle,
        page: BrowserPage,
        approved: DSecret,
        matched: Boolean,
    ): Either<BrowserError, DSecret> {
        val dependencies = vault.dependencies
        val item = dependencies.login(handle)
        val blocked = dependencies.isBlocked(page)
        val unmatched = matched && item != null && !dependencies.uriMatcher(page, strictHttp = true)(item)
        // After the reads: a lock during them ends the release too.
        currentCoroutineContext().ensureActive()
        return when {
            !vaultAccess.isCurrent(vault.key) -> BrowserError.VAULT_LOCKED.left()
            blocked -> BrowserError.NOT_ALLOWED.left()
            item == null -> BrowserError.INVALID_REQUEST.left()
            // Only an item with re-prompt asks for the user's identity to approve it.
            item.reprompt && !approved.reprompt -> BrowserError.NOT_ALLOWED.left()
            unmatched -> BrowserError.NOT_ALLOWED.left()
            else -> item.right()
        }
    }

    /**
     * Asks the user to approve the [operation] that releases the [cipher] of
     * the [vault] to the [page], a frame of the [topPage] if not `null`. An
     * item that isn't [matched] always asks, and offers to add the page to
     * it. A frame of another site's page always asks too.
     */
    private suspend fun approve(
        vault: BrowserVault,
        handle: BrowserItemHandle,
        matched: Boolean,
        page: BrowserPage,
        topPage: BrowserPage?,
        cipher: DSecret,
        operation: (BrowserRelease) -> BrowserOperation,
    ): Either<BrowserError, BrowserApproval.Approved> {
        val pairing = pairing()
            ?: return BrowserError.UNPAIRED.left()
        val accountName = vault.dependencies.accountNames()[cipher.accountId]
        // What `AddUriCipher` writes; offered only for an item the user can edit.
        val addUri = "${page.scheme}://${page.host}"
            .takeIf { !matched && vault.dependencies.canEdit(cipher) }
        // A frame of another site's page: the page around it may be anyone's.
        val topSite = topPage
            ?.site(tldService)
            ?.takeIf { it != handle.site }
        val grant = BrowserGrant(
            pairingId = pairing.id,
            site = handle.site,
            accountId = handle.accountId,
            cipherId = handle.cipherId,
        )
        return approvals.approveRelease(
            key = vault.key,
            session = session(),
            pairing = pairing,
            grant = grant,
            reprompt = cipher.reprompt,
            matched = matched,
            crossSite = topSite != null,
        ) { approvalWindow ->
            val release = cipher.toRelease(
                page = page,
                site = handle.site,
                username = cipher.login?.username,
                accountName = accountName,
                approvalWindow = approvalWindow,
                topSite = topSite,
                matched = matched,
                addUri = addUri,
            )
            operation(release)
        }
    }

    /** See [BrowserRequestLog.recorded]; [privateWindow] is the field of the request. */
    private suspend fun recorded(
        type: CliAgentUsageHistoryRequestType,
        privateWindow: Boolean?,
        block: suspend (BrowserUsage) -> BrowserReply,
    ): BrowserReply = requestLog.recorded(session, pairing, type, privateWindow == true, block)

    /** The logins of the visible accounts, best match first. */
    private suspend fun BrowserExtensionSessionDependencies.matches(page: BrowserPage): List<DSecret> {
        val logins = logins()
        if (logins.isEmpty()) return emptyList()
        val target = AutofillTarget(
            links = listOf(page.link),
            hints = listOf(AutofillHint.USERNAME, AutofillHint.PASSWORD),
            maxCount = MAX_SUGGESTIONS,
        )
        return getSuggestions(logins, Getter { it as DSecret }, target, equivalentDomainsBuilderFactory)
            .bind()
            .filterIsInstance<DSecret>()
    }

    /**
     * When each of the [logins] was last used on this device, by a fill or a
     * passkey sign-in; none if the history can't be read.
     */
    private suspend fun BrowserExtensionSessionDependencies.lastUsed(logins: List<DSecret>): Map<String, Instant> =
        getCipherLastUsed(logins.mapTo(mutableSetOf()) { it.id })
            .attempt()
            .bind()
            .getOrElse { e ->
                val message = "Failed to read the last use of the logins: ${e::class.simpleName}"
                logRepository.post(TAG, message, LogLevel.WARNING)
                emptyMap()
            }

    /** The logins of the visible accounts with a discoverable passkey of the relying party [rpId]. */
    private suspend fun BrowserExtensionSessionDependencies.passkeyLogins(rpId: String): List<DSecret> =
        logins().filter { it.discoverablePasskeys(rpId).isNotEmpty() }

    /**
     * The name of each folder of the [items], by its account and ID; none of
     * a hidden account. Reads no folders if none of the items is in one.
     */
    private suspend fun BrowserExtensionSessionDependencies.folderNames(
        items: List<DSecret>,
    ): Map<Pair<String, String>, String> {
        val keys = items.mapNotNullTo(mutableSetOf()) { item -> item.folderId?.let { item.accountId to it } }
        if (keys.isEmpty()) return emptyMap()
        return filterHiddenProfiles(getFolders = getFolders, getProfiles = getProfiles)
            .first()
            .filter { (it.accountId to it.id) in keys }
            .associate { (it.accountId to it.id) to it.name }
    }

    /**
     * Tells whether a URI of a login matches the [page], as the suggestions
     * score it; a name alike doesn't count. With [strictHttp], on an `http`
     * page only a URI that names `http://` counts, so an `https` login never
     * fills silently on the `http` page of its host.
     */
    private suspend fun BrowserExtensionSessionDependencies.uriMatcher(
        page: BrowserPage,
        strictHttp: Boolean,
    ): suspend (DSecret) -> Boolean {
        val httpOnly = strictHttp && page.scheme == SCHEME_HTTP
        val defaultMatchDetection = getAutofillDefaultMatchDetection().first()
        val equivalentDomainsBuilder = equivalentDomainsBuilderFactory.build()
        val url = page.link.url.toString()
        return { cipher ->
            val equivalentDomains = equivalentDomainsBuilder.getAndCache(cipher.accountId)
            cipher.uris.any { uri ->
                (!httpOnly || uri.uri.trim().startsWith("$SCHEME_HTTP://", ignoreCase = true)) &&
                    cipherUrlCheck(uri, url, defaultMatchDetection, equivalentDomains)
                        .attempt()
                        .bind()
                        .getOrElse { false }
            }
        }
    }

    /**
     * Adds the scheme and host of the [page] to the [item], as autofill does;
     * an item the user can't edit is skipped. A failure doesn't fail the release.
     */
    private suspend fun BrowserExtensionSessionDependencies.addPage(item: DSecret, page: BrowserPage) {
        if (!canEdit(item)) return
        val request = AddUriCipherRequest(
            cipherId = item.id,
            webDomain = page.host,
            webScheme = page.scheme,
        )
        addUriCipher(request)
            .attempt()
            .bind()
            .onLeft { e ->
                logRepository.post(TAG, "Failed to add the page to an item: ${e::class.simpleName}", LogLevel.WARNING)
            }
    }

    /** Whether the user may edit the login, as for storing a passkey in it. */
    private suspend fun BrowserExtensionSessionDependencies.canEdit(cipher: DSecret): Boolean =
        cipher.canStorePasskey(getCanWrite().first(), getCollections().first())

    /** The login of the [handle], as it is now. */
    private suspend fun BrowserExtensionSessionDependencies.login(handle: BrowserItemHandle): DSecret? =
        logins().firstOrNull { it.id == handle.cipherId && it.accountId == handle.accountId }

    /**
     * The login that saving [request] updates: with a URI that matches the
     * page and the same username, preferring the one with the previous
     * password. An item that asks for the master password, or that the user
     * can't edit, is never opened this way.
     */
    private suspend fun BrowserExtensionSessionDependencies.existingLogin(
        page: BrowserPage,
        request: SaveRequest,
    ): DSecret? {
        val username = request.username
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        // By a URI only; on an `http` page a URI need not name `http://`.
        val matchesPage = uriMatcher(page, strictHttp = false)
        val candidates = matches(page)
            .filter { !it.reprompt && it.login?.username == username }
            .filter { matchesPage(it) && canEdit(it) }
        val previousPassword = request.previousPassword
            ?.takeIf { it.isNotEmpty() }
        return candidates.firstOrNull { previousPassword != null && it.login?.password == previousPassword }
            ?: candidates.firstOrNull()
    }

    companion object {
        private const val TAG = "BrowserVaultOperations"

        /** The most items of a `suggest` response. */
        const val MAX_SUGGESTIONS = 50

        /** The most URIs of an item of a `suggest` response. */
        const val MAX_SUGGESTION_URIS = 5

        /** The longest URI, in characters, of an item of a `suggest` response: longer ones are left out. */
        const val MAX_SUGGESTION_URI_LENGTH = 1024

        /** The most items of a `search` response. */
        const val MAX_SEARCH_RESULTS = 20

        /** The bounds of a `search` query, in code points once trimmed. */
        const val MIN_QUERY_LENGTH = 2
        const val MAX_QUERY_LENGTH = 100

        private const val KIND_LOGIN = "login"
        private const val KIND_PASSKEY = "passkey"

        private const val SCHEME_HTTP = "http"
    }
}

/**
 * The page of a `fill` or `totp`, and the page that embeds its frame if the
 * request names one; `null` if either is invalid.
 */
private fun HandleRequest.pages(): Pair<BrowserPage, BrowserPage?>? {
    val page = BrowserPage.parse(url)
        ?: return null
    val topPage = topOrigin?.let(BrowserPage::parseOrigin)
    return (page to topPage).takeIf { topOrigin == null || topPage != null }
}

/** Whether a blocked URI of the user stops autofill on the page; see [blocksFill]. */
internal suspend fun BrowserExtensionSessionDependencies.isBlocked(page: BrowserPage): Boolean =
    urlBlocksOf(page).any { it.blocksFill }

/** Whether a blocked URI of the user stops the offer to save a login on the page; see [blocksSave]. */
internal suspend fun BrowserExtensionSessionDependencies.isSaveBlocked(page: BrowserPage): Boolean =
    urlBlocksOf(page).any { it.blocksSave }

/**
 * The enabled blocked URIs of the user that match the page, also by the
 * equivalent domains of any account: a rule belongs to no account.
 */
private suspend fun BrowserExtensionSessionDependencies.urlBlocksOf(page: BrowserPage): List<DGlobalUrlBlock> {
    val defaultMatchDetection = getAutofillDefaultMatchDetection().first()
    // The same target that the matching of the suggestions checks, see [BrowserPage.link].
    val target = "${page.scheme}://${page.host}"
    val equivalentDomains = equivalentDomainsBuilderFactory.build().getAndCacheAll()
    return getUrlBlocks()
        .first()
        .filter { block ->
            // A rule that can't be checked, such as an invalid regular expression, blocks nothing.
            block.enabled && blockedUrlCheck(block, target, defaultMatchDetection, equivalentDomains)
                .attempt()
                .bind()
                .getOrElse { false }
        }
}

/** The logins that a browser may see: not of a hidden account, not in the trash or the archive. */
internal suspend fun BrowserExtensionSessionDependencies.logins(): List<DSecret> =
    filterHiddenProfiles(getCiphers = getCiphers, getProfiles = getProfiles)
        .first()
        .filter { it.type == DSecret.Type.Login && !it.deleted && !it.archived }

/** The display name of each account. */
internal suspend fun BrowserExtensionSessionDependencies.accountNames(): Map<String, String> =
    getProfiles()
        .first()
        .associate { it.accountId to it.displayName }

/**
 * The passkeys of a login for the relying party [rpId] that answer a request
 * without `allowCredentials`, which `suggest` lists.
 */
internal fun DSecret.discoverablePasskeys(rpId: String): List<DSecret.Login.Fido2Credentials> =
    login?.fido2Credentials
        .orEmpty()
        .filter { it.toWebAuthnCredential().canAnswer(rpId, WebAuthnAllowedCredentialDescriptors.NONE) }

/** The time-based one-time password of a login; a counter-based one is not a TOTP. */
private fun DSecret.totpToken(): TotpToken? = login?.totp?.token
    ?.takeUnless { it is TotpToken.HotpAuth }

/** Whether the name, the username or a URI host of the login holds [needle], in lowercase. */
private fun DSecret.holds(needle: String): Boolean =
    needle in name.lowercase() ||
        login?.username?.lowercase()?.contains(needle) == true ||
        uris.any { uri -> uri.host()?.contains(needle) == true }

/** The host of a web URI, also of one saved without a scheme; `null` for an app or a regular expression. */
private fun DSecret.Uri.host(): String? = uri
    .takeIf { match != DSecret.Uri.MatchType.RegularExpression }
    ?.let { parseHttpUrlHostOrNull(ensureUrlScheme(it.trim())) }
    ?.lowercase()

/** The number of Unicode code points: a surrogate pair counts once. */
internal fun String.countCodePoints(): Int = length - (1 until length).count { i ->
    this[i].isLowSurrogate() && this[i - 1].isHighSurrogate()
}
