package com.artemchep.keyguard.feature.generator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Domain
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import arrow.core.Either
import arrow.core.compose
import arrow.core.getOrElse
import arrow.core.partially1
import arrow.core.right
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.Emails
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.handleErrorTap
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.shared
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.DGeneratorEmailRelay
import com.artemchep.keyguard.common.model.DGeneratorHistory
import com.artemchep.keyguard.common.model.DGeneratorWordlist
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.GetPasswordResult
import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.model.KeyPairConfig
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.PasswordGeneratorConfigBuilder2
import com.artemchep.keyguard.common.model.build
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.model.isExpensive
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.usecase.KeyPairExport
import com.artemchep.keyguard.common.service.crypto.KeyPairGenerator
import com.artemchep.keyguard.common.usecase.KeyPrivateExport
import com.artemchep.keyguard.common.usecase.KeyPublicExport
import com.artemchep.keyguard.common.service.relays.api.EmailRelay
import com.artemchep.keyguard.common.usecase.AddGeneratorHistory
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetWordlists
import com.artemchep.keyguard.common.usecase.GetEmailRelays
import com.artemchep.keyguard.common.usecase.GetPassword
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetWordlistPrimitive
import com.artemchep.keyguard.common.usecase.NumberFormatter
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.auth.common.IntFieldModel
import com.artemchep.keyguard.feature.auth.common.SwitchFieldModel
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.auth.common.util.REGEX_DOMAIN
import com.artemchep.keyguard.feature.auth.common.util.REGEX_EMAIL
import com.artemchep.keyguard.feature.crashlytics.crashlyticsTap
import com.artemchep.keyguard.feature.generator.emailrelay.EmailRelayListRoute
import com.artemchep.keyguard.feature.generator.history.GeneratorHistoryRoute
import com.artemchep.keyguard.feature.generator.sshkey.SshKeyActions
import com.artemchep.keyguard.feature.generator.util.findBestUserEmailOrNull
import com.artemchep.keyguard.feature.generator.wordlist.WordlistsRoute
import com.artemchep.keyguard.feature.home.vault.add.AddRoute
import com.artemchep.keyguard.feature.home.vault.add.LeAddRoute
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.copy
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.navigation.state.translate
import com.artemchep.keyguard.generatorTarget
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.KeyguardIcons
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.icons.iconSmall
import com.artemchep.keyguard.ui.icons.custom.FormatLetterCaseLower
import com.artemchep.keyguard.ui.icons.custom.FormatLetterCaseUpper
import com.artemchep.keyguard.ui.icons.custom.Numeric
import com.artemchep.keyguard.ui.icons.custom.Symbol
import com.artemchep.keyguard.ui.theme.isDark
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.datetime.Clock
import org.kodein.di.allInstances
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance
import org.kodein.di.instanceOrNull

private const val TIP_VISIBLE = true

private const val PASSWORD_LENGTH_DEFAULT = 16L
private const val PASSWORD_LENGTH_MIN = 4
private const val PASSWORD_LENGTH_MAX = 128
private const val PASSWORD_INCLUDE_UPPERCASE_CHARS_DEFAULT = true
private const val PASSWORD_INCLUDE_UPPERCASE_CHARS_MIN_DEFAULT = 1L
private const val PASSWORD_INCLUDE_LOWERCASE_CHARS_DEFAULT = true
private const val PASSWORD_INCLUDE_LOWERCASE_CHARS_MIN_DEFAULT = 1L
private const val PASSWORD_INCLUDE_NUMBERS_DEFAULT = true
private const val PASSWORD_INCLUDE_NUMBERS_MIN_DEFAULT = 1L
private const val PASSWORD_INCLUDE_SYMBOLS_DEFAULT = true
private const val PASSWORD_INCLUDE_SYMBOLS_MIN_DEFAULT = 1L
private const val PASSWORD_EXCLUDE_SIMILAR_CHARS = true
private const val PASSWORD_EXCLUDE_AMBIGUOUS_CHARS = true

private const val PASSPHRASE_LENGTH_DEFAULT = 5L
private const val PASSPHRASE_LENGTH_MIN = 1
private const val PASSPHRASE_LENGTH_MAX = 10
private const val PASSPHRASE_DELIMITER_DEFAULT = "-"
private const val PASSPHRASE_CAPITALIZE_DEFAULT = false
private const val PASSPHRASE_NUMBER_DEFAULT = false
private const val PASSPHRASE_WORDLIST_ID_DEFAULT = -1L

private const val USERNAME_LENGTH_DEFAULT = 2L
private const val USERNAME_LENGTH_MIN = 1
private const val USERNAME_LENGTH_MAX = 10
private const val USERNAME_DELIMITER_DEFAULT = ""
private const val USERNAME_CAPITALIZE_DEFAULT = true
private const val USERNAME_NUMBER_DEFAULT = true
private const val USERNAME_CUSTOM_WORD_DEFAULT = ""
private const val USERNAME_WORDLIST_ID_DEFAULT = -1L

private const val EMAIL_CATCH_ALL_LENGTH_DEFAULT = 5L
private const val EMAIL_CATCH_ALL_LENGTH_MIN = 3
private const val EMAIL_CATCH_ALL_LENGTH_MAX = 10

private const val EMAIL_PLUS_ADDRESSING_LENGTH_DEFAULT = 5L
private const val EMAIL_PLUS_ADDRESSING_LENGTH_MIN = 3
private const val EMAIL_PLUS_ADDRESSING_LENGTH_MAX = 10

private const val EMAIL_SUBDOMAIN_ADDRESSING_LENGTH_DEFAULT = 5L
private const val EMAIL_SUBDOMAIN_ADDRESSING_LENGTH_MIN = 3
private const val EMAIL_SUBDOMAIN_ADDRESSING_LENGTH_MAX = 10

private const val KEY_PAIR_RSA_LENGTH_DEFAULT = "4096"

@Composable
fun produceGeneratorState(
    mode: AppMode,
    args: GeneratorRoute.Args,
    key: String? = null,
) = with(localDI().direct) {
    produceGeneratorState(
        mode = mode,
        args = args,
        key = key,
        addGeneratorHistory = instanceOrNull(),
        getPassword = instance(),
        getPasswordStrength = instance(),
        getProfiles = instanceOrNull(),
        getEmailRelays = instanceOrNull(),
        getWordlists = instanceOrNull(),
        getWordlistPrimitive = instanceOrNull(),
        cryptoGenerator = instance(),
        keyPairExport = instance(),
        publicKeyExport = instance(),
        privateKeyExport = instance(),
        numberFormatter = instance(),
        getCanWrite = instance(),
        clipboardService = instance(),
        emailRelays = allInstances(),
    )
}

private const val PREFIX_PASSWORD = "password"
private const val PREFIX_PASSPHRASE = "passphrase"
private const val PREFIX_USERNAME = "username"
private const val PREFIX_EMAIL_CATCH_ALL = "email_catch_all"
private const val PREFIX_EMAIL_PLUS_ADDRESSING = "email_plus_addressing"
private const val PREFIX_EMAIL_SUBDOMAIN_ADDRESSING = "email_subdomain_addressing"
private const val PREFIX_KEY_PAIR = "key_pair"

private data class WordlistResult(
    val id: Long,
    val words: List<String>,
)

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalMaterial3Api::class)
@Composable
fun produceGeneratorState(
    mode: AppMode,
    args: GeneratorRoute.Args,
    key: String? = null,
    addGeneratorHistory: AddGeneratorHistory?,
    getPassword: GetPassword,
    getPasswordStrength: GetPasswordStrength,
    getProfiles: GetProfiles?,
    getEmailRelays: GetEmailRelays?,
    getWordlists: GetWordlists?,
    getWordlistPrimitive: GetWordlistPrimitive?,
    cryptoGenerator: CryptoGenerator,
    keyPairExport: KeyPairExport,
    publicKeyExport: KeyPublicExport,
    privateKeyExport: KeyPrivateExport,
    numberFormatter: NumberFormatter,
    getCanWrite: GetCanWrite,
    clipboardService: ClipboardService,
    emailRelays: List<EmailRelay>,
): Loadable<GeneratorState> = produceScreenState(
    initial = Loadable.Loading,
    key = key?.let { "$it:" }.orEmpty() + "autofill",
    args = arrayOf(
        args,
        getPassword,
        getPasswordStrength,
        getCanWrite,
        clipboardService,
    ),
) {
    val generatorContext = mode.generatorTarget
    val copyItemFactory = copy(
        clipboardService = clipboardService,
    )

    fun checkTypeMatchesArgs(type: GeneratorType2) =
        (type.password && args.password) ||
                (type.sshKey && args.sshKey) ||
                (type.username && args.username)

    val typesAllStatic = listOf(
        GeneratorType2.Password,
        GeneratorType2.Passphrase,
        GeneratorType2.Username,
        GeneratorType2.EmailCatchAll,
        GeneratorType2.EmailPlusAddressing,
        GeneratorType2.EmailSubdomainAddressing,
        GeneratorType2.SshKey,
    ).filter(::checkTypeMatchesArgs)
    val typesRelayFlow = if (getEmailRelays != null && args.username) {
        getEmailRelays()
            .map { relays ->
                relays
                    .mapNotNull { config ->
                        val emailRelay = emailRelays
                            .firstOrNull { it.type == config.type }
                            ?: return@mapNotNull null
                        GeneratorType2.EmailRelay(
                            emailRelay = emailRelay,
                            config = config,
                        )
                    }
            }
    } else {
        flowOf(emptyList())
    }
    val typesAllFlow = typesRelayFlow
        .map { typesRelay ->
            // Combine and filter all the possible
            // generator types.
            sequence<GeneratorType2> {
                typesAllStatic.forEach { type ->
                    yield(type)
                }

                typesRelay.forEach { type ->
                    yield(type)
                }
            }
                .run {
                    // Double check that we do not emit
                    // unsupported types.
                    if (!isRelease) {
                        onEach { type ->
                            require(checkTypeMatchesArgs(type)) {
                                "Generator type '${type.key}' doesn't match any of the " +
                                        "types user wants: $args"
                            }
                        }
                    } else {
                        this
                    }
                }
                .toPersistentList()
        }

    val getUserEmailDefaultIo = if (getProfiles != null) {
        getProfiles()
            .toIO()
    } else {
        val e = RuntimeException()
        ioRaise(e)
    }
        .map { profiles ->
            profiles
                .findBestUserEmailOrNull()
        }
        .shared("getUserEmailDefaultIo")

    val getUserDomainDefaultIo = getUserEmailDefaultIo
        .map { email ->
            email
                ?.substringAfterLast('@', missingDelimiterValue = "")
                ?.takeUnless { domain ->
                    // Catch-all email can not be setup for a
                    // public email server.
                    domain in Emails
                }
        }
        .shared("getUserDomainDefaultIo")

    val storage = kotlin.run {
        val disk = loadDiskHandle(
            key = key?.let { "$it:" }.orEmpty() + "generator",
            global = true,
        )
        PersistedStorage.InDisk(disk)
    }
    // common
    val typeDefaultName = typesAllStatic
        .firstOrNull()
        ?.key
        ?: GeneratorType2.Password.key
    val typeSink = mutablePersistedFlow(
        key = "type",
        storage = storage,
    ) { typeDefaultName }
    // Auto-fix the current type if it
    // gets out of the variants.
    typesAllFlow
        .onEach { items ->
            val shouldAutoSelect = items.none { it.key == typeSink.value }
            if (shouldAutoSelect) {
                typeSink.value = typeDefaultName
            }
        }
        .launchIn(screenScope)
    val typeFlow = typeSink
        .combine(typesAllFlow) { typeKey, items ->
            items.firstOrNull { it.key == typeKey }
                ?: GeneratorType2.Password
        }
    val tipVisibleSink = mutablePersistedFlow(
        key = "tip.visible",
        storage = storage,
    ) { TIP_VISIBLE }

    fun hideTip() {
        tipVisibleSink.value = false
    }

    // Wordlists
    val customWordlistsFlow = kotlin
        .run {
            val customWordlistsFlow = getWordlists
                ?.let { useCase ->
                    useCase()
                        .attempt()
                        .map {
                            // If some thing goes wrong, then
                            // just ignore invalid values.
                            it.getOrElse { emptyList() }
                        }
                }
                ?: flowOf(emptyList())
            customWordlistsFlow
        }
        .shareInScreenScope()

    suspend fun wordlistFilterItem(
        wordlistId: Long?,
        wordlists: List<DGeneratorWordlist>,
        onSelect: (Long) -> Unit,
    ): GeneratorState.Filter.Item.Enum.Model {
        val defaultWordlistName = "EFF long wordlist"

        val wordlist = wordlists
            .firstOrNull { it.idRaw == wordlistId }
        val dropdown = buildContextItems {
            section {
                val quantity = 7776
                val text = translate(
                    Res.plurals.word_count_plural,
                    quantity,
                    numberFormatter.formatNumber(quantity),
                )
                this += FlatItemAction(
                    title = TextHolder.Value(defaultWordlistName),
                    text = TextHolder.Value(text),
                    onClick = onSelect
                        .partially1(0),
                )
            }
            section {
                wordlists.forEach { wordlist ->
                    val selected = wordlistId == wordlist.idRaw
                    val quantity = wordlist.wordCount.toInt()
                    val text = translate(
                        Res.plurals.word_count_plural,
                        quantity,
                        numberFormatter.formatNumber(quantity),
                    )
                    this += FlatItemAction(
                        title = TextHolder.Value(wordlist.name),
                        leading = {
                            val backgroundColor = if (MaterialTheme.colorScheme.isDark) {
                                wordlist.accentColor.dark
                            } else {
                                wordlist.accentColor.light
                            }
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(backgroundColor, shape = CircleShape),
                            )
                        },
                        text = TextHolder.Value(text),
                        selected = selected,
                        onClick = onSelect
                            .partially1(wordlist.idRaw)
                            .takeIf { quantity > 0 },
                    )
                }
            }
        }
        return GeneratorState.Filter.Item.Enum.Model(
            value = wordlist?.name
                ?: defaultWordlistName,
            dropdown = dropdown,
        )
    }

    fun wordlist(
        wordlistIdFlow: Flow<Long>,
    ): Flow<WordlistResult?> = wordlistIdFlow
        .flatMapLatest { id ->
            getWordlistPrimitive
                ?.let { useCase ->
                    useCase(id)
                        .attempt()
                        .map { wordsResult ->
                            val words = wordsResult.getOrNull()
                                ?: return@map null
                            WordlistResult(
                                id = id,
                                words = words,
                            )
                        }
                }
            // use default wordlist
                ?: flowOf(null)
        }

    // password
    val passwordLengthSink = mutablePersistedFlow(
        key = "$PREFIX_PASSWORD.length",
        storage = storage,
    ) { PASSWORD_LENGTH_DEFAULT }
    val passwordIncludeUppercaseCharactersSink = mutablePersistedFlow(
        key = "$PREFIX_PASSWORD.include_uppercase_chars",
        storage = storage,
    ) { PASSWORD_INCLUDE_UPPERCASE_CHARS_DEFAULT }
    val passwordIncludeUppercaseCharactersMinSink = mutablePersistedFlow(
        key = "$PREFIX_PASSWORD.include_uppercase_chars.min",
        storage = storage,
    ) { PASSWORD_INCLUDE_UPPERCASE_CHARS_MIN_DEFAULT }
    val passwordIncludeLowercaseCharactersSink = mutablePersistedFlow(
        key = "$PREFIX_PASSWORD.include_lowercase_chars",
        storage = storage,
    ) { PASSWORD_INCLUDE_LOWERCASE_CHARS_DEFAULT }
    val passwordIncludeLowercaseCharactersMinSink = mutablePersistedFlow(
        key = "$PREFIX_PASSWORD.include_lowercase_chars.min",
        storage = storage,
    ) { PASSWORD_INCLUDE_LOWERCASE_CHARS_MIN_DEFAULT }
    val passwordIncludeNumbersSink = mutablePersistedFlow(
        key = "$PREFIX_PASSWORD.include_numbers",
        storage = storage,
    ) { PASSWORD_INCLUDE_NUMBERS_DEFAULT }
    val passwordIncludeNumbersMinSink = mutablePersistedFlow(
        key = "$PREFIX_PASSWORD.include_numbers.min",
        storage = storage,
    ) { PASSWORD_INCLUDE_NUMBERS_MIN_DEFAULT }
    val passwordIncludeSymbolsSink = mutablePersistedFlow(
        "$PREFIX_PASSWORD.include_symbols",
        storage = storage,
    ) { PASSWORD_INCLUDE_SYMBOLS_DEFAULT }
    val passwordIncludeSymbolsMinSink = mutablePersistedFlow(
        key = "$PREFIX_PASSWORD.include_symbols.min",
        storage = storage,
    ) { PASSWORD_INCLUDE_SYMBOLS_MIN_DEFAULT }
    val passwordExcludeSimilarCharactersSink = mutablePersistedFlow(
        "$PREFIX_PASSWORD.exclude_similar_chars",
        storage = storage,
    ) { PASSWORD_EXCLUDE_SIMILAR_CHARS }
    val passwordExcludeAmbiguousCharactersSink = mutablePersistedFlow(
        "$PREFIX_PASSWORD.exclude_ambiguous_chars",
        storage = storage,
    ) { PASSWORD_EXCLUDE_AMBIGUOUS_CHARS }
    // passphrase
    val passphraseLengthSink = mutablePersistedFlow(
        key = "$PREFIX_PASSPHRASE.length",
        storage = storage,
    ) { PASSPHRASE_LENGTH_DEFAULT }
    val passphraseDelimiterSink = mutablePersistedFlow(
        key = "$PREFIX_PASSPHRASE.delimiter",
        storage = storage,
    ) { PASSPHRASE_DELIMITER_DEFAULT }
    val passphraseDelimiterState = mutableComposeState(passphraseDelimiterSink)
    val passphraseCapitalizeSink = mutablePersistedFlow(
        key = "$PREFIX_PASSPHRASE.capitalize",
        storage = storage,
    ) { PASSPHRASE_CAPITALIZE_DEFAULT }
    val passphraseIncludeNumberSink = mutablePersistedFlow(
        key = "$PREFIX_PASSPHRASE.include_number",
        storage = storage,
    ) { PASSPHRASE_NUMBER_DEFAULT }
    val passphraseWordlistIdSink = mutablePersistedFlow(
        key = "$PREFIX_PASSPHRASE.wordlist_id",
        storage = storage,
    ) { PASSPHRASE_WORDLIST_ID_DEFAULT }
    val passphraseWordlistFlow = wordlist(passphraseWordlistIdSink)
    // username
    val usernameLengthSink = mutablePersistedFlow(
        key = "$PREFIX_USERNAME.length",
        storage = storage,
    ) { USERNAME_LENGTH_DEFAULT }
    val usernameDelimiterSink = mutablePersistedFlow(
        key = "$PREFIX_USERNAME.delimiter",
        storage = storage,
    ) { USERNAME_DELIMITER_DEFAULT }
    val usernameDelimiterState = mutableComposeState(usernameDelimiterSink)
    val usernameCapitalizeSink = mutablePersistedFlow(
        key = "$PREFIX_USERNAME.capitalize",
        storage = storage,
    ) { USERNAME_CAPITALIZE_DEFAULT }
    val usernameIncludeNumberSink = mutablePersistedFlow(
        key = "$PREFIX_USERNAME.include_number",
        storage = storage,
    ) { USERNAME_NUMBER_DEFAULT }
    val usernameCustomWordSink = mutablePersistedFlow(
        key = "$PREFIX_USERNAME.custom_word",
        storage = storage,
    ) { USERNAME_CUSTOM_WORD_DEFAULT }
    val usernameCustomWordState = mutableComposeState(usernameCustomWordSink)
    val usernameWordlistIdSink = mutablePersistedFlow(
        key = "$PREFIX_USERNAME.wordlist_id",
        storage = storage,
    ) { USERNAME_WORDLIST_ID_DEFAULT }
    val usernameWordlistFlow = wordlist(usernameWordlistIdSink)
    // email catch all
    val emailCatchAllLengthSink = mutablePersistedFlow(
        key = "$PREFIX_EMAIL_CATCH_ALL.length",
        storage = storage,
    ) { EMAIL_CATCH_ALL_LENGTH_DEFAULT }
    val emailCatchAllDomainSink = mutablePersistedFlow(
        key = "$PREFIX_EMAIL_CATCH_ALL.domain",
        storage = storage,
    ) { "" }
    val emailCatchAllDomainState = mutableComposeState(emailCatchAllDomainSink)
    if (emailCatchAllDomainState.value.isEmpty()) {
        emailCatchAllDomainState.value =
            getUserDomainDefaultIo.attempt().bind().getOrNull().orEmpty()
    }
    // email plus addressing
    val emailPlusAddressingLengthSink = mutablePersistedFlow(
        key = "$PREFIX_EMAIL_PLUS_ADDRESSING.length",
        storage = storage,
    ) { EMAIL_PLUS_ADDRESSING_LENGTH_DEFAULT }
    val emailPlusAddressingEmailSink = mutablePersistedFlow(
        key = "$PREFIX_EMAIL_PLUS_ADDRESSING.email",
        storage = storage,
    ) { "" }
    val emailPlusAddressingEmailState = mutableComposeState(emailPlusAddressingEmailSink)
    if (emailPlusAddressingEmailState.value.isEmpty()) {
        emailPlusAddressingEmailState.value =
            getUserEmailDefaultIo.attempt().bind().getOrNull().orEmpty()
    }
    // email subdomain addressing
    val emailSubdomainAddressingLengthSink = mutablePersistedFlow(
        key = "$PREFIX_EMAIL_SUBDOMAIN_ADDRESSING.length",
        storage = storage,
    ) { EMAIL_SUBDOMAIN_ADDRESSING_LENGTH_DEFAULT }
    val emailSubdomainAddressingEmailSink = mutablePersistedFlow(
        key = "$PREFIX_EMAIL_SUBDOMAIN_ADDRESSING.email",
        storage = storage,
    ) { "" }
    val emailSubdomainAddressingEmailState = mutableComposeState(emailSubdomainAddressingEmailSink)
    if (emailSubdomainAddressingEmailState.value.isEmpty()) {
        emailSubdomainAddressingEmailState.value =
            getUserEmailDefaultIo.attempt().bind().getOrNull().orEmpty()
                .takeIf { email ->
                    email.substringAfterLast('@', "") !in Emails
                }
                .orEmpty()
    }
    // key pair
    val keyPairTypeSink = mutablePersistedFlow(
        key = "$PREFIX_KEY_PAIR.type",
        storage = storage,
    ) { KeyPair.Type.default.key }
    val keyPairRsaLengthSink = mutablePersistedFlow(
        key = "$PREFIX_KEY_PAIR.rsa.length",
        storage = storage,
    ) { KEY_PAIR_RSA_LENGTH_DEFAULT }

    suspend fun keyPairTypeFilterItem(
        keyType: String?,
        onSelect: (String) -> Unit,
    ): GeneratorState.Filter.Item.Enum.Model {
        val dropdown = buildContextItems {
            section {
                KeyPair.Type.entries.forEach { type ->
                    val selected = keyType == type.key
                    val title = TextHolder.Value(type.title)
                    this += FlatItemAction(
                        title = title,
                        text = type.shortDescription,
                        selected = selected,
                        onClick = onSelect
                            .partially1(type.key),
                    )
                }
            }
        }
        return GeneratorState.Filter.Item.Enum.Model(
            value = KeyPair.Type.getOrDefault(keyType).title,
            dropdown = dropdown,
        )
    }

    suspend fun keyPairRsaLengthFilterItem(
        keyType: String,
        onSelect: (String) -> Unit,
    ): GeneratorState.Filter.Item.Enum.Model {
        val dropdown = buildContextItems {
            section {
                KeyPairGenerator.RsaLength.entries.forEach { entityLength ->
                    val length = entityLength.size.toString()
                    val selected = length == keyType
                    val title = translate(Res.string.generator_key_length_item, length)
                    this += FlatItemAction(
                        title = TextHolder.Value(title),
                        selected = selected,
                        onClick = onSelect
                            .partially1(length),
                    )
                }
            }
        }
        val title = translate(Res.string.generator_key_length_item, keyType)
        return GeneratorState.Filter.Item.Enum.Model(
            value = title,
            dropdown = dropdown,
        )
    }

    val passphraseFilterTip = GeneratorState.Filter.Tip(
        text = translate(Res.string.generator_passphrase_note),
        onLearnMore = {
            val url = "https://xkcd.com/936/"
            val intent = NavigationIntent.NavigateToBrowser(url)
            navigate(intent)
        },
        onHide = ::hideTip,
    )

    suspend fun createFilter(
        config: PasswordGeneratorConfigBuilder2.Passphrase,
    ): GeneratorState.Filter {
        val items = persistentListOf<GeneratorState.Filter.Item>(
            GeneratorState.Filter.Item.Text(
                key = "$PREFIX_PASSPHRASE.delimiter",
                title = translate(Res.string.generator_passphrase_delimiter_title),
                model = TextFieldModel2(
                    state = passphraseDelimiterState,
                    text = config.delimiter,
                    onChange = passphraseDelimiterState::value::set,
                ),
            ),
            GeneratorState.Filter.Item.Switch(
                key = "$PREFIX_PASSPHRASE.capitalize",
                icon = KeyguardIcons.FormatLetterCaseUpper,
                title = translate(Res.string.generator_passphrase_capitalize_title),
                model = SwitchFieldModel(
                    checked = config.capitalize,
                    onChange = passphraseCapitalizeSink::value::set,
                ),
            ),
            GeneratorState.Filter.Item.Switch(
                key = "$PREFIX_PASSPHRASE.include_number",
                icon = KeyguardIcons.Numeric,
                title = translate(Res.string.generator_passphrase_number_title),
                model = SwitchFieldModel(
                    checked = config.includeNumber,
                    onChange = passphraseIncludeNumberSink::value::set,
                ),
            ),
            GeneratorState.Filter.Item.Enum(
                key = "$PREFIX_PASSPHRASE.wordlist",
                title = translate(Res.string.generator_passphrase_wordlist_title),
                model = wordlistFilterItem(
                    wordlistId = config.wordlistId,
                    wordlists = config.wordlists,
                    onSelect = passphraseWordlistIdSink::value::set,
                ),
            ),
        )
        return GeneratorState.Filter(
            tip = passphraseFilterTip,
            length = GeneratorState.Filter.Length(
                value = config.length,
                min = PASSPHRASE_LENGTH_MIN,
                max = PASSPHRASE_LENGTH_MAX,
                onChange = passphraseLengthSink::value::set
                    .compose { it.toLong() },
            ),
            items = items,
        )
    }

    suspend fun createFilter(
        config: PasswordGeneratorConfigBuilder2.Username,
    ): GeneratorState.Filter {
        val items = persistentListOf<GeneratorState.Filter.Item>(
            GeneratorState.Filter.Item.Text(
                key = "$PREFIX_PASSPHRASE.custom_word",
                title = translate(Res.string.generator_username_custom_word_title),
                model = TextFieldModel2(
                    state = usernameCustomWordState,
                    text = config.customWord,
                    onChange = usernameCustomWordState::value::set,
                ),
            ),
            GeneratorState.Filter.Item.Text(
                key = "$PREFIX_PASSPHRASE.delimiter",
                title = translate(Res.string.generator_username_delimiter_title),
                model = TextFieldModel2(
                    state = usernameDelimiterState,
                    text = config.delimiter,
                    onChange = usernameDelimiterState::value::set,
                ),
            ),
            GeneratorState.Filter.Item.Switch(
                key = "$PREFIX_USERNAME.capitalize",
                icon = KeyguardIcons.FormatLetterCaseUpper,
                title = translate(Res.string.generator_username_capitalize_title),
                model = SwitchFieldModel(
                    checked = config.capitalize,
                    onChange = usernameCapitalizeSink::value::set,
                ),
            ),
            GeneratorState.Filter.Item.Switch(
                key = "$PREFIX_USERNAME.include_number",
                icon = KeyguardIcons.Numeric,
                title = translate(Res.string.generator_username_number_title),
                model = SwitchFieldModel(
                    checked = config.includeNumber,
                    onChange = usernameIncludeNumberSink::value::set,
                ),
            ),
            GeneratorState.Filter.Item.Enum(
                key = "$PREFIX_USERNAME.wordlist",
                title = translate(Res.string.generator_username_wordlist_title),
                model = wordlistFilterItem(
                    wordlistId = config.wordlistId,
                    wordlists = config.wordlists,
                    onSelect = usernameWordlistIdSink::value::set,
                ),
            ),
        )
        return GeneratorState.Filter(
            tip = null,
            length = GeneratorState.Filter.Length(
                value = config.length,
                min = USERNAME_LENGTH_MIN,
                max = USERNAME_LENGTH_MAX,
                onChange = usernameLengthSink::value::set
                    .compose { it.toLong() },
            ),
            items = items,
        )
    }

    val passwordFilterTip = GeneratorState.Filter.Tip(
        text = translate(Res.string.generator_password_note, 16),
        onHide = ::hideTip,
    )

    suspend fun createFilter(
        config: PasswordGeneratorConfigBuilder2.Password,
    ): GeneratorState.Filter {
        val items = persistentListOf<GeneratorState.Filter.Item>(
            GeneratorState.Filter.Item.Switch(
                key = "$PREFIX_PASSWORD.include_numbers",
                icon = KeyguardIcons.Numeric,
                title = translate(Res.string.generator_password_numbers_title),
                model = SwitchFieldModel(
                    checked = config.includeNumbers,
                    onChange = passwordIncludeNumbersSink::value::set,
                ),
                counter = IntFieldModel(
                    number = config.includeNumbersMin
                        .coerceAtMost(config.length.toLong()),
                    min = 1L,
                    max = config.length.toLong(),
                    onChange = passwordIncludeNumbersMinSink::value::set,
                ),
            ),
            GeneratorState.Filter.Item.Switch(
                key = "$PREFIX_PASSWORD.include_symbols",
                icon = KeyguardIcons.Symbol,
                title = translate(Res.string.generator_password_symbols_title),
                text = """!"#$%&'()*+-./:;<=>?@[\]^_`{|}~""",
                model = SwitchFieldModel(
                    checked = config.includeSymbols,
                    onChange = passwordIncludeSymbolsSink::value::set,
                ),
                counter = IntFieldModel(
                    number = config.includeSymbolsMin
                        .coerceAtMost(config.length.toLong()),
                    min = 1L,
                    max = config.length.toLong(),
                    onChange = passwordIncludeSymbolsMinSink::value::set,
                ),
            ),
            GeneratorState.Filter.Item.Switch(
                key = "$PREFIX_PASSWORD.include_uppercase_chars",
                icon = KeyguardIcons.FormatLetterCaseUpper,
                title = translate(Res.string.generator_password_uppercase_letters_title),
                model = SwitchFieldModel(
                    checked = config.includeUppercaseCharacters,
                    onChange = passwordIncludeUppercaseCharactersSink::value::set,
                ),
                counter = IntFieldModel(
                    number = config.includeUppercaseCharactersMin
                        .coerceAtMost(config.length.toLong()),
                    min = 1L,
                    max = config.length.toLong(),
                    onChange = passwordIncludeUppercaseCharactersMinSink::value::set,
                ),
            ),
            GeneratorState.Filter.Item.Switch(
                key = "$PREFIX_PASSWORD.include_lowercase_chars",
                icon = KeyguardIcons.FormatLetterCaseLower,
                title = translate(Res.string.generator_password_lowercase_letters_title),
                model = SwitchFieldModel(
                    checked = config.includeLowercaseCharacters,
                    onChange = passwordIncludeLowercaseCharactersSink::value::set,
                ),
                counter = IntFieldModel(
                    number = config.includeLowercaseCharactersMin
                        .coerceAtMost(config.length.toLong()),
                    min = 1L,
                    max = config.length.toLong(),
                    onChange = passwordIncludeLowercaseCharactersMinSink::value::set,
                ),
            ),
            GeneratorState.Filter.Item.Section(
                key = "$PREFIX_PASSWORD.exclude",
            ),
            GeneratorState.Filter.Item.Switch(
                key = "$PREFIX_PASSWORD.exclude_similar_chars",
                title = translate(Res.string.generator_password_exclude_similar_symbols_title),
                text = "il1Lo0O",
                model = SwitchFieldModel(
                    checked = config.excludeSimilarCharacters,
                    onChange = passwordExcludeSimilarCharactersSink::value::set,
                ),
            ),
            GeneratorState.Filter.Item.Switch(
                key = "$PREFIX_PASSWORD.exclude_ambiguous_chars",
                title = translate(Res.string.generator_password_exclude_ambiguous_symbols_title),
                text = """{}[]()/\'"`~,;:.<>""",
                model = SwitchFieldModel(
                    checked = config.excludeAmbiguousCharacters,
                    onChange = passwordExcludeAmbiguousCharactersSink::value::set,
                ),
            ),
        )
        return GeneratorState.Filter(
            tip = passwordFilterTip,
            length = GeneratorState.Filter.Length(
                value = config.length,
                min = PASSWORD_LENGTH_MIN,
                max = PASSWORD_LENGTH_MAX,
                onChange = passwordLengthSink::value::set
                    .compose { it.toLong() },
            ),
            items = items,
        )
    }

    val catchAllEmailFilterTip = GeneratorState.Filter.Tip(
        text = translate(Res.string.generator_email_catch_all_note),
        onHide = ::hideTip,
    )

    fun onOpenHistory() {
        val route = GeneratorHistoryRoute
        val intent = NavigationIntent.NavigateToRoute(route)
        navigate(intent)
    }

    suspend fun createFilter(
        config: PasswordGeneratorConfigBuilder2.EmailCatchAll,
    ): GeneratorState.Filter {
        val items = persistentListOf<GeneratorState.Filter.Item>(
            GeneratorState.Filter.Item.Text(
                key = "$PREFIX_EMAIL_CATCH_ALL.domain",
                title = translate(Res.string.generator_email_catch_all_domain_title),
                icon = Icons.Outlined.Domain,
                model = TextFieldModel2(
                    state = emailCatchAllDomainState,
                    text = config.domain,
                    hint = "example.com",
                    error = when {
                        config.domain.isEmpty() ->
                            translate(Res.string.error_must_not_be_empty)

                        config.domain in Emails ->
                            translate(Res.string.error_must_be_custom_domain)

                        !REGEX_DOMAIN.matches(config.domain.trim()) ->
                            translate(Res.string.error_invalid_domain)

                        else -> null
                    },
                    onChange = emailCatchAllDomainState::value::set,
                ),
            ),
        )
        return GeneratorState.Filter(
            tip = catchAllEmailFilterTip,
            length = GeneratorState.Filter.Length(
                value = config.length,
                min = EMAIL_CATCH_ALL_LENGTH_MIN,
                max = EMAIL_CATCH_ALL_LENGTH_MAX,
                onChange = emailCatchAllLengthSink::value::set
                    .compose { it.toLong() },
            ),
            items = items,
        )
    }

    val emailPlusAddressingFilterTip = GeneratorState.Filter.Tip(
        text = translate(
            Res.string.generator_email_plus_addressing_note,
            "username+abc@example.com",
        ),
        onHide = ::hideTip,
    )

    suspend fun createFilter(
        config: PasswordGeneratorConfigBuilder2.EmailPlusAddressing,
    ): GeneratorState.Filter {
        val items = persistentListOf<GeneratorState.Filter.Item>(
            GeneratorState.Filter.Item.Text(
                key = "$PREFIX_EMAIL_PLUS_ADDRESSING.email",
                title = translate(Res.string.generator_email_plus_addressing_email_title),
                icon = Icons.Outlined.Email,
                model = TextFieldModel2(
                    state = emailPlusAddressingEmailState,
                    text = config.email,
                    hint = "username@example.com",
                    error = when {
                        config.email.isEmpty() ->
                            translate(Res.string.error_must_not_be_empty)

                        !REGEX_EMAIL.matches(config.email.trim()) ->
                            translate(Res.string.error_invalid_email)

                        else -> null
                    },
                    onChange = emailPlusAddressingEmailState::value::set,
                ),
            ),
        )
        return GeneratorState.Filter(
            tip = emailPlusAddressingFilterTip,
            length = GeneratorState.Filter.Length(
                value = config.length,
                min = EMAIL_PLUS_ADDRESSING_LENGTH_MIN,
                max = EMAIL_PLUS_ADDRESSING_LENGTH_MAX,
                onChange = emailPlusAddressingLengthSink::value::set
                    .compose { it.toLong() },
            ),
            items = items,
        )
    }

    val emailSubdomainAddressingFilterTip = GeneratorState.Filter.Tip(
        text = translate(
            Res.string.generator_email_subdomain_addressing_note,
            "username@abc.example.com",
        ),
        onHide = ::hideTip,
    )

    suspend fun createFilter(
        config: PasswordGeneratorConfigBuilder2.EmailSubdomainAddressing,
    ): GeneratorState.Filter {
        val items = persistentListOf<GeneratorState.Filter.Item>(
            GeneratorState.Filter.Item.Text(
                key = "$PREFIX_EMAIL_SUBDOMAIN_ADDRESSING.email",
                title = translate(Res.string.generator_email_subdomain_addressing_email_title),
                icon = Icons.Outlined.Email,
                model = TextFieldModel2(
                    state = emailSubdomainAddressingEmailState,
                    text = config.email,
                    hint = "username@example.com",
                    error = when {
                        config.email.isEmpty() ->
                            translate(Res.string.error_must_not_be_empty)

                        config.email.substringAfterLast('@', "") in Emails ->
                            translate(Res.string.error_must_be_custom_domain)

                        !REGEX_EMAIL.matches(config.email.trim()) ->
                            translate(Res.string.error_invalid_email)

                        else -> null
                    },
                    onChange = emailSubdomainAddressingEmailState::value::set,
                ),
            ),
        )
        return GeneratorState.Filter(
            tip = emailSubdomainAddressingFilterTip,
            length = GeneratorState.Filter.Length(
                value = config.length,
                min = EMAIL_SUBDOMAIN_ADDRESSING_LENGTH_MIN,
                max = EMAIL_SUBDOMAIN_ADDRESSING_LENGTH_MAX,
                onChange = emailSubdomainAddressingLengthSink::value::set
                    .compose { it.toLong() },
            ),
            items = items,
        )
    }

    fun createFilter(
        config: PasswordGeneratorConfigBuilder2.EmailRelay,
    ): GeneratorState.Filter {
        val items = persistentListOf<GeneratorState.Filter.Item>(
        )
        return GeneratorState.Filter(
            tip = null,
            length = null,
            items = items,
        )
    }

    val keyPairRsaFilterTip = GeneratorState.Filter.Tip(
        text = translate(Res.string.generator_key_rsa_note),
        onHide = ::hideTip,
        onLearnMore = {
            val url = "https://en.wikipedia.org/wiki/RSA_(cryptosystem)"
            val intent = NavigationIntent.NavigateToBrowser(url)
            navigate(intent)
        },
    )
    val keyPairEd25519FilterTip = GeneratorState.Filter.Tip(
        text = translate(Res.string.generator_key_ed25519_note),
        onHide = ::hideTip,
        onLearnMore = {
            val url = "https://en.wikipedia.org/wiki/EdDSA#Ed25519"
            val intent = NavigationIntent.NavigateToBrowser(url)
            navigate(intent)
        },
    )

    suspend fun createFilter(
        config: PasswordGeneratorConfigBuilder2.KeyPair,
    ): GeneratorState.Filter {
        val tip = when (config.config) {
            is KeyPairConfig.Rsa -> keyPairRsaFilterTip
            is KeyPairConfig.Ed25519 -> keyPairEd25519FilterTip
        }

        val items = mutableListOf<GeneratorState.Filter.Item>(
            GeneratorState.Filter.Item.Enum(
                key = "$PREFIX_KEY_PAIR.type",
                icon = Icons.Outlined.Key,
                title = translate(Res.string.key_type),
                model = keyPairTypeFilterItem(
                    keyType = config.config.type.key,
                    onSelect = keyPairTypeSink::value::set,
                ),
            ),
        )
        when (val cfg = config.config) {
            is KeyPairConfig.Rsa -> {
                items += GeneratorState.Filter.Item.Enum(
                    key = "$PREFIX_KEY_PAIR.rsa.length",
                    title = translate(Res.string.generator_key_length_title),
                    model = keyPairRsaLengthFilterItem(
                        keyType = cfg.length.size.toString(),
                        onSelect = keyPairRsaLengthSink::value::set,
                    ),
                )
            }

            is KeyPairConfig.Ed25519 -> {
                // Do nothing
            }
        }
        return GeneratorState.Filter(
            tip = tip,
            length = null,
            items = items.toPersistentList(),
        )
    }

    suspend fun createFilter(
        config: PasswordGeneratorConfigBuilder2,
    ) = when (config) {
        is PasswordGeneratorConfigBuilder2.Password -> createFilter(config)
        is PasswordGeneratorConfigBuilder2.Passphrase -> createFilter(config)
        is PasswordGeneratorConfigBuilder2.Username -> createFilter(config)
        is PasswordGeneratorConfigBuilder2.EmailCatchAll -> createFilter(config)
        is PasswordGeneratorConfigBuilder2.EmailPlusAddressing -> createFilter(config)
        is PasswordGeneratorConfigBuilder2.EmailSubdomainAddressing -> createFilter(config)
        is PasswordGeneratorConfigBuilder2.EmailRelay -> createFilter(config)
        is PasswordGeneratorConfigBuilder2.KeyPair -> createFilter(config)
    }

    fun createLoginWithUsername(
        username: String,
    ) {
        val type = DSecret.Type.Login
        val route = LeAddRoute(
            args = AddRoute.Args(
                type = type,
                username = username,
            ),
        )
        val intent = NavigationIntent.NavigateToRoute(route)
        navigate(intent)
    }

    fun createLoginWithPassword(
        password: String,
    ) {
        val type = DSecret.Type.Login
        val route = LeAddRoute(
            args = AddRoute.Args(
                type = type,
                password = password,
            ),
        )
        val intent = NavigationIntent.NavigateToRoute(route)
        navigate(intent)
    }

    fun createSshKeyWithKeyPair(
        keyPair: KeyPair,
    ) {
        val type = DSecret.Type.SshKey
        val route = LeAddRoute(
            args = AddRoute.Args(
                type = type,
                keyPair = keyPair,
            ),
        )
        val intent = NavigationIntent.NavigateToRoute(route)
        navigate(intent)
    }

    val refreshValueSink = EventFlow<String>()
    val refreshValue: () -> Unit = {
        val eventId = cryptoGenerator.uuid()
        refreshValueSink.emit(eventId)
    }

    val configBuilderFlow = typeFlow
        .flatMapLatest { type ->
            when (type) {
                is GeneratorType2.Password -> combine(
                    passwordLengthSink,
                    passwordIncludeUppercaseCharactersSink,
                    passwordIncludeLowercaseCharactersSink,
                    passwordIncludeNumbersSink,
                    passwordIncludeSymbolsSink,
                    passwordExcludeSimilarCharactersSink,
                    passwordExcludeAmbiguousCharactersSink,
                    passwordIncludeNumbersMinSink,
                    passwordIncludeSymbolsMinSink,
                    passwordIncludeUppercaseCharactersMinSink,
                    passwordIncludeLowercaseCharactersMinSink,
                ) { array ->
                    val length = array[0] as Long
                    val includeUppercaseCharacters = array[1] as Boolean
                    val includeLowercaseCharacters = array[2] as Boolean
                    val includeNumbers = array[3] as Boolean
                    val includeSymbols = array[4] as Boolean
                    val excludeSimilarCharacters = array[5] as Boolean
                    val excludeAmbiguousCharacters = array[6] as Boolean
                    val includeNumbersMin = array[7] as Long
                    val includeSymbolsMin = array[8] as Long
                    val includeUppercaseCharactersMin = array[9] as Long
                    val includeLowercaseCharactersMin = array[10] as Long
                    PasswordGeneratorConfigBuilder2.Password(
                        length = length.toInt(),
                        includeUppercaseCharacters = includeUppercaseCharacters,
                        includeUppercaseCharactersMin = includeUppercaseCharactersMin,
                        includeLowercaseCharacters = includeLowercaseCharacters,
                        includeLowercaseCharactersMin = includeLowercaseCharactersMin,
                        includeNumbers = includeNumbers,
                        includeNumbersMin = includeNumbersMin,
                        includeSymbols = includeSymbols,
                        includeSymbolsMin = includeSymbolsMin,
                        excludeSimilarCharacters = excludeSimilarCharacters,
                        excludeAmbiguousCharacters = excludeAmbiguousCharacters,
                    )
                }

                is GeneratorType2.Passphrase -> combine(
                    passphraseLengthSink,
                    passphraseDelimiterSink,
                    passphraseCapitalizeSink,
                    passphraseIncludeNumberSink,
                    passphraseWordlistFlow,
                    customWordlistsFlow,
                ) { array ->
                    val length = array[0] as Long
                    val delimiter = array[1] as String
                    val capitalize = array[2] as Boolean
                    val includeNumber = array[3] as Boolean
                    val wordlist = array[4] as WordlistResult?
                    val wordlists = array[5] as List<DGeneratorWordlist>
                    PasswordGeneratorConfigBuilder2.Passphrase(
                        length = length.toInt(),
                        delimiter = delimiter,
                        capitalize = capitalize,
                        includeNumber = includeNumber,
                        customWord = "",
                        wordlistId = wordlist?.id,
                        wordlists = wordlists,
                        wordlist = wordlist?.words,
                    )
                }

                is GeneratorType2.Username -> combine(
                    usernameLengthSink,
                    usernameDelimiterSink,
                    usernameCapitalizeSink,
                    usernameIncludeNumberSink,
                    usernameCustomWordSink,
                    usernameWordlistFlow,
                    customWordlistsFlow,
                ) { array ->
                    val length = array[0] as Long
                    val delimiter = array[1] as String
                    val capitalize = array[2] as Boolean
                    val includeNumber = array[3] as Boolean
                    val customWord = array[4] as String
                    val wordlist = array[5] as WordlistResult?
                    val wordlists = array[6] as List<DGeneratorWordlist>
                    PasswordGeneratorConfigBuilder2.Username(
                        length = length.toInt(),
                        delimiter = delimiter,
                        capitalize = capitalize,
                        includeNumber = includeNumber,
                        customWord = customWord,
                        wordlistId = wordlist?.id,
                        wordlists = wordlists,
                        wordlist = wordlist?.words,
                    )
                }

                is GeneratorType2.EmailCatchAll -> combine(
                    emailCatchAllLengthSink,
                    emailCatchAllDomainSink,
                ) { length, domain ->
                    PasswordGeneratorConfigBuilder2.EmailCatchAll(
                        length = length.toInt(),
                        domain = domain,
                    )
                }

                is GeneratorType2.EmailPlusAddressing -> combine(
                    emailPlusAddressingLengthSink,
                    emailPlusAddressingEmailSink,
                ) { length, email ->
                    PasswordGeneratorConfigBuilder2.EmailPlusAddressing(
                        length = length.toInt(),
                        email = email,
                    )
                }

                is GeneratorType2.EmailSubdomainAddressing -> combine(
                    emailSubdomainAddressingLengthSink,
                    emailSubdomainAddressingEmailSink,
                ) { length, email ->
                    PasswordGeneratorConfigBuilder2.EmailSubdomainAddressing(
                        length = length.toInt(),
                        email = email,
                    )
                }

                is GeneratorType2.EmailRelay -> flow<PasswordGeneratorConfigBuilder2> {
                    val m = PasswordGeneratorConfigBuilder2.EmailRelay(
                        emailRelay = type.emailRelay,
                        config = type.config,
                    )
                    emit(m)
                }

                is GeneratorType2.SshKey -> keyPairTypeSink
                    .flatMapLatest { keyPairType ->
                        val config = when (KeyPair.Type.getOrDefault(keyPairType)) {
                            KeyPair.Type.RSA -> return@flatMapLatest keyPairRsaLengthSink
                                .map { rawLength ->
                                    val length = KeyPairGenerator.RsaLength.getOrDefault(rawLength)
                                    val config = KeyPairConfig.Rsa(length)
                                    PasswordGeneratorConfigBuilder2.KeyPair(
                                        config = config,
                                    )
                                }

                            KeyPair.Type.ED25519 -> KeyPairConfig.Ed25519
                        }
                        val model = PasswordGeneratorConfigBuilder2.KeyPair(
                            config = config,
                        )
                        flowOf(model)
                    }
            }.map { it to type }
        }
        .distinctUntilChanged()
        .shareIn(screenScope, SharingStarted.WhileSubscribed(), replay = 1)

    val typesFlow = flowOfGeneratorType(
        typesAllFlow = typesAllFlow,
        typeFlow = typeFlow,
        typeSink = typeSink,
    ).stateIn(appScope)
    val filterFlow = configBuilderFlow
        .combine(tipVisibleSink) { (configBuilder, _), tipVisible ->
            createFilter(configBuilder)
                .let { filter ->
                    if (tipVisible) {
                        filter
                    } else {
                        filter.copy(tip = null)
                    }
                }
        }
        .stateIn(screenScope)
    val passwordTextFlow = configBuilderFlow
        .map { (configBuilder, type) ->
            val config = configBuilder.build()
            val factory = getPassword(generatorContext, config)
            Triple(
                config,
                factory,
                type,
            )
        }
        .flatMapLatest { (config, factory, type) ->
            val generateOnInit = !config.isExpensive()
            flow {
                kotlin.run {
                    val result = GetPasswordResult.Value("")
                        .right()
                    val data = ValueGenerationEvent.Data(
                        result = result,
                        type = type,
                    )
                    val event = ValueGenerationEvent(
                        id = "",
                        data = Loadable.Ok(data),
                    )
                    emit(event)
                }

                refreshValueSink
                    .onStart {
                        if (generateOnInit) {
                            val eventId = cryptoGenerator.uuid()
                            emit(eventId)
                        }
                    }
                    .transformLatest { eventId ->
                        // 1. Emit the loading event, so the app
                        // knows that we are processing a new
                        // event at this moment.
                        kotlin.run {
                            val event = ValueGenerationEvent(
                                id = eventId,
                                data = Loadable.Loading,
                            )
                            emit(event)
                        }

                        // 2. Generate a new data and emit the
                        // result with the same event id.
                        val result = factory
                            .crashlyticsTap()
                            .handleErrorTap { e ->
                                message(e)
                            }
                            .attempt()
                            .bind()
                        val data = ValueGenerationEvent.Data(
                            result = result,
                            type = type,
                        )
                        val event = ValueGenerationEvent(
                            id = eventId,
                            data = Loadable.Ok(data),
                        )
                        emit(event)
                    }
                    .collect(this)
            }
        }
        .flowOn(Dispatchers.Default)
        .shareIn(screenScope, SharingStarted.Lazily, replay = 1)
    val loadingFlow = passwordTextFlow
        .map { event ->
            val loaded = event.data is Loadable.Ok
            GeneratorState.Loading(
                loaded = loaded,
            )
        }
        .stateIn(appScope)
    passwordTextFlow
        .mapNotNull { event ->
            event.data
                .getOrNull()
        }
        .debounce(640L)
        .onEach { data ->
            val type = data.type
            val value = data.result
                .getOrNull()
            if (value != null && value.isValid() && addGeneratorHistory != null) {
                val model = DGeneratorHistory(
                    value = value,
                    createdDate = Clock.System.now(),
                    isPassword = type.password && args.password,
                    isUsername = type.username && args.username,
                    isEmailRelay = type is GeneratorType2.EmailRelay,
                    isSshKey = type is GeneratorType2.SshKey,
                )
                addGeneratorHistory(model)
                    .crashlyticsTap()
                    .attempt()
                    .launchIn(appScope)
            }
        }
        .launchIn(screenScope)

    val passwordFlow = combine(
        getCanWrite(),
        (getProfiles?.invoke()?.map { it.isNotEmpty() } ?: flowOf(false)),
        passwordTextFlow
            .mapNotNull { event ->
                event.data
                    .getOrNull()
            },
    ) { canWrite, hasAccounts, (passwordResult, type) ->
        passwordResult.fold(
            ifLeft = { e ->
                GeneratorState.Value(
                    title = null,
                    password = "",
                    source = GetPasswordResult.Value(""),
                    strength = type.password && args.password,
                    dropdown = persistentListOf(),
                    actions = persistentListOf(),
                    onCopy = null,
                    onRefresh = refreshValue,
                )
            },
            ifRight = { passwordw ->
                if (passwordw == null) {
                    return@fold null
                }

                when (passwordw) {
                    is GetPasswordResult.Value -> {
                        val password = passwordw.value
                        val dropdown = buildContextItems {
                            this += copyItemFactory.FlatItemAction(
                                title = Res.string.copy.wrap(),
                                value = password,
                                type = CopyText.Type.PASSWORD,
                            )
                        }
                        val actions = kotlin.run {
                            val list = mutableListOf<FlatItemAction>()
                            if (canWrite && hasAccounts && type.username) {
                                list += FlatItemAction(
                                    leading = icon(Icons.Outlined.Add),
                                    title = Res.string.generator_create_item_with_username_title.wrap(),
                                    onClick = ::createLoginWithUsername.partially1(password),
                                )
                            }
                            if (canWrite && hasAccounts && type.password) {
                                list += FlatItemAction(
                                    leading = icon(Icons.Outlined.Add),
                                    title = Res.string.generator_create_item_with_password_title.wrap(),
                                    onClick = ::createLoginWithPassword.partially1(password),
                                )
                            }
                            list.toPersistentList()
                        }
                        GeneratorState.Value(
                            title = null,
                            password = password,
                            source = passwordw,
                            strength = type.password && args.password,
                            dropdown = if (password.isNotEmpty()) {
                                dropdown
                            } else {
                                persistentListOf()
                            },
                            actions = if (password.isNotEmpty()) {
                                actions
                            } else {
                                persistentListOf()
                            },
                            onCopy = if (password.isNotEmpty()) {
                                copyItemFactory::copy
                                    .partially1(password)
                                    .partially1(false)
                                    .partially1(CopyText.Type.PASSWORD)
                            } else {
                                null
                            },
                            onRefresh = refreshValue,
                        )
                    }

                    is GetPasswordResult.AsyncKey -> {
                        val keyPair = passwordw.keyPair
                        val dropdown = buildContextItems {
                            SshKeyActions.addAll(
                                keyPair = keyPair,
                                keyPairExport = keyPairExport,
                                publicKeyExport = publicKeyExport,
                                privateKeyExport = privateKeyExport,
                                copyItemFactory = copyItemFactory,
                            )
                        }
                        val actions = kotlin.run {
                            val list = mutableListOf<FlatItemAction>()
                            if (canWrite && hasAccounts && type.sshKey) {
                                list += FlatItemAction(
                                    leading = icon(Icons.Outlined.Add),
                                    title = Res.string.generator_create_item_with_ssh_key_title.wrap(),
                                    onClick = ::createSshKeyWithKeyPair.partially1(keyPair),
                                )
                            }
                            list.toPersistentList()
                        }
                        GeneratorState.Value(
                            title = keyPair.type.title,
                            password = keyPair.publicKey.fingerprint,
                            source = passwordw,
                            strength = false,
                            dropdown = dropdown,
                            actions = actions,
                            onCopy = null,
                            onRefresh = refreshValue,
                        )
                    }
                }
            },
        )
    }.stateIn(screenScope)
    val optionsStatic = buildContextItems {
        this += EmailRelayListRoute.actionOrNull(
            translator = this@produceScreenState,
            navigate = ::navigate,
        )
        this += WordlistsRoute.actionOrNull(
            translator = this@produceScreenState,
            navigate = ::navigate,
        )
    }
    val optionsFlow = tipVisibleSink
        .map { tipVisible ->
            FlatItemAction(
                title = Res.string.generator_show_tips_title.wrap(),
                icon = Icons.Outlined.Info,
                trailing = {
                    Checkbox(
                        checked = tipVisible,
                        onCheckedChange = null,
                    )
                },
                onClick = tipVisibleSink::value::set.partially1(!tipVisible),
            )
        }
        .map { tipVisibilityItem ->
            optionsStatic.add(0, tipVisibilityItem)
        }

    optionsFlow
        .map { options ->
            val state = GeneratorState(
                onOpenHistory = ::onOpenHistory,
                options = options,
                loadedState = loadingFlow,
                typeState = typesFlow,
                valueState = passwordFlow,
                filterState = filterFlow,
            )
            Loadable.Ok(state)
        }
}

private fun RememberStateFlowScope.flowOfGeneratorType(
    typesAllFlow: Flow<List<GeneratorType2>>,
    typeFlow: Flow<GeneratorType2>,
    typeSink: MutableStateFlow<String>,
): Flow<GeneratorState.Type> {
    fun createEmailRelayAction(
        emailRelay: DGeneratorEmailRelay,
        selected: Boolean,
        onClick: () -> Unit,
    ): FlatItemAction {
        return FlatItemAction(
            leading = {
                val backgroundColor = if (MaterialTheme.colorScheme.isDark) {
                    emailRelay.accentColor.dark
                } else {
                    emailRelay.accentColor.light
                }
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(backgroundColor, shape = CircleShape),
                )
            },
            title = TextHolder.Value(emailRelay.name),
            selected = selected,
            onClick = onClick,
        )
    }

    fun createTypeAction(
        type: GeneratorType2,
        selected: Boolean,
        onClick: () -> Unit,
    ): FlatItemAction {
        val primaryIcon = when {
            type.password -> Icons.Outlined.Password
            type.sshKey -> Icons.Outlined.Terminal
            type is GeneratorType2.Username -> Icons.Outlined.AlternateEmail
            else -> Icons.Outlined.Mail
        }
        val secondaryIcon = when {
            type.sshKey -> Icons.Outlined.Key
            else -> null
        }
        return FlatItemAction(
            title = type.title,
            leading = iconSmall(primaryIcon, secondaryIcon),
            selected = selected,
            onClick = onClick,
        )
    }

    return combine(
        typesAllFlow,
        typeFlow,
    ) { allTypes, type ->
        val typeTitle = translate(type.title)
        val typeItems = buildContextItems {
            allTypes.forEachIndexed { index, item ->
                if (index > 0) {
                    val groupChanged = allTypes[index - 1].group != item.group
                    if (groupChanged) {
                        this += when (item.group) {
                            GENERATOR_TYPE_GROUP_INTEGRATION ->
                                ContextItem.Section(
                                    title = translate(Res.string.emailrelay_list_section_title),
                                )

                            else -> ContextItem.Section()
                        }
                    }
                }

                val selected = item.key == type.key
                val onClick: () -> Unit = {
                    typeSink.value = item.key
                }
                when (item) {
                    is GeneratorType2.EmailRelay -> {
                        this += createEmailRelayAction(
                            emailRelay = item.config,
                            selected = selected,
                            onClick = onClick,
                        )
                    }

                    else -> {
                        this += createTypeAction(
                            type = item,
                            selected = selected,
                            onClick = onClick,
                        )
                    }
                }
            }
        }
        GeneratorState.Type(
            title = typeTitle,
            items = typeItems,
        )
    }
}

private data class ValueGenerationEvent(
    val id: String,
    val data: Loadable<Data>,
) {
    data class Data(
        val result: Either<Throwable, GetPasswordResult?>,
        val type: GeneratorType2,
    )
}
