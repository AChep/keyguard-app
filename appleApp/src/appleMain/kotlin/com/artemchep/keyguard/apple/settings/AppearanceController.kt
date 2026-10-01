package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.main
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AppColors
import com.artemchep.keyguard.common.model.AppFont
import com.artemchep.keyguard.common.model.AppTheme
import com.artemchep.keyguard.common.model.NavAnimation
import com.artemchep.keyguard.common.usecase.GetAllowTwoPanelLayoutInLandscape
import com.artemchep.keyguard.common.usecase.GetAllowTwoPanelLayoutInPortrait
import com.artemchep.keyguard.common.usecase.GetCloseToTray
import com.artemchep.keyguard.common.usecase.GetColors
import com.artemchep.keyguard.common.usecase.GetColorsVariants
import com.artemchep.keyguard.common.usecase.GetFont
import com.artemchep.keyguard.common.usecase.GetFontVariants
import com.artemchep.keyguard.common.usecase.GetGravatar
import com.artemchep.keyguard.common.usecase.GetKeepScreenOn
import com.artemchep.keyguard.common.usecase.GetLocale
import com.artemchep.keyguard.common.usecase.GetLocaleVariants
import com.artemchep.keyguard.common.usecase.GetMarkdown
import com.artemchep.keyguard.common.usecase.GetMinimizeOnCopy
import com.artemchep.keyguard.common.usecase.GetNavAnimation
import com.artemchep.keyguard.common.usecase.GetNavAnimationVariants
import com.artemchep.keyguard.common.usecase.GetNavLabel
import com.artemchep.keyguard.common.usecase.GetTheme
import com.artemchep.keyguard.common.usecase.GetThemeExpressive
import com.artemchep.keyguard.common.usecase.GetThemeUseAmoledDark
import com.artemchep.keyguard.common.usecase.GetThemeVariants
import com.artemchep.keyguard.common.usecase.GetUseExternalBrowser
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import com.artemchep.keyguard.common.usecase.PutAllowTwoPanelLayoutInLandscape
import com.artemchep.keyguard.common.usecase.PutAllowTwoPanelLayoutInPortrait
import com.artemchep.keyguard.common.usecase.PutCloseToTray
import com.artemchep.keyguard.common.usecase.PutColors
import com.artemchep.keyguard.common.usecase.PutFont
import com.artemchep.keyguard.common.usecase.PutKeepScreenOn
import com.artemchep.keyguard.common.usecase.PutLocale
import com.artemchep.keyguard.common.usecase.PutMarkdown
import com.artemchep.keyguard.common.usecase.PutMinimizeOnCopy
import com.artemchep.keyguard.common.usecase.PutNavAnimation
import com.artemchep.keyguard.common.usecase.PutNavLabel
import com.artemchep.keyguard.common.usecase.PutTheme
import com.artemchep.keyguard.common.usecase.PutThemeExpressive
import com.artemchep.keyguard.common.usecase.PutThemeUseAmoledDark
import com.artemchep.keyguard.common.usecase.PutUseExternalBrowser
import com.artemchep.keyguard.copy.CopyEventsSource
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.toArgbLong
import com.artemchep.keyguard.apple.model.SettingOptionSnapshot
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.LeLocale
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The Appearance settings screen plus the two app-wide preference effects that
 * feed the SwiftUI shell ([observeAppPreferences]) and the minimize-on-copy
 * trigger ([observeMinimizeOnCopy]). Thin bridge over the shared Get/Put use
 * cases; enum pickers (theme, accent, font, nav animation, locale) are surfaced
 * as [SettingOptionSnapshot] lists keyed by the variant's name (see [setTheme]).
 */
internal class AppearanceController(
    private val ctx: CoreContext,
) {
    private val getTheme: GetTheme by lazy { ctx.koin.get() }
    private val getThemeVariants: GetThemeVariants by lazy { ctx.koin.get() }
    private val putTheme: PutTheme by lazy { ctx.koin.get() }
    private val getThemeUseAmoledDark: GetThemeUseAmoledDark by lazy { ctx.koin.get() }
    private val putThemeUseAmoledDark: PutThemeUseAmoledDark by lazy { ctx.koin.get() }
    private val getThemeExpressive: GetThemeExpressive by lazy { ctx.koin.get() }
    private val putThemeExpressive: PutThemeExpressive by lazy { ctx.koin.get() }
    private val getColors: GetColors by lazy { ctx.koin.get() }
    private val getColorsVariants: GetColorsVariants by lazy { ctx.koin.get() }
    private val putColors: PutColors by lazy { ctx.koin.get() }
    private val getFont: GetFont by lazy { ctx.koin.get() }
    private val getFontVariants: GetFontVariants by lazy { ctx.koin.get() }
    private val putFont: PutFont by lazy { ctx.koin.get() }
    private val getMarkdown: GetMarkdown by lazy { ctx.koin.get() }
    private val putMarkdown: PutMarkdown by lazy { ctx.koin.get() }
    private val getNavAnimation: GetNavAnimation by lazy { ctx.koin.get() }
    private val getNavAnimationVariants: GetNavAnimationVariants by lazy { ctx.koin.get() }
    private val putNavAnimation: PutNavAnimation by lazy { ctx.koin.get() }
    private val getNavLabel: GetNavLabel by lazy { ctx.koin.get() }
    private val putNavLabel: PutNavLabel by lazy { ctx.koin.get() }
    private val getUseExternalBrowser: GetUseExternalBrowser by lazy { ctx.koin.get() }
    private val putUseExternalBrowser: PutUseExternalBrowser by lazy { ctx.koin.get() }
    private val getKeepScreenOn: GetKeepScreenOn by lazy { ctx.koin.get() }
    private val putKeepScreenOn: PutKeepScreenOn by lazy { ctx.koin.get() }
    private val getMinimizeOnCopy: GetMinimizeOnCopy by lazy { ctx.koin.get() }
    private val putMinimizeOnCopy: PutMinimizeOnCopy by lazy { ctx.koin.get() }
    private val getCloseToTray: GetCloseToTray by lazy { ctx.koin.get() }
    private val putCloseToTray: PutCloseToTray by lazy { ctx.koin.get() }
    private val getLocale: GetLocale by lazy { ctx.koin.get() }
    private val getLocaleVariants: GetLocaleVariants by lazy { ctx.koin.get() }
    private val putLocale: PutLocale by lazy { ctx.koin.get() }
    private val getTwoPanelPortrait: GetAllowTwoPanelLayoutInPortrait by lazy { ctx.koin.get() }
    private val putTwoPanelPortrait: PutAllowTwoPanelLayoutInPortrait by lazy { ctx.koin.get() }
    private val getTwoPanelLandscape: GetAllowTwoPanelLayoutInLandscape by lazy { ctx.koin.get() }
    private val putTwoPanelLandscape: PutAllowTwoPanelLayoutInLandscape by lazy { ctx.koin.get() }
    private val getWebsiteIcons: GetWebsiteIcons by lazy { ctx.koin.get() }
    private val getGravatar: GetGravatar by lazy { ctx.koin.get() }

    private var latestThemeVariants: List<AppTheme?> = emptyList()
    private var latestFontVariants: List<AppFont?> = emptyList()
    private var latestNavAnimationVariants: List<NavAnimation> = emptyList()
    private var latestColorsVariants: List<AppColors?> = emptyList()
    private var latestLocaleVariants: List<String?> = emptyList()

    fun observeAppearanceSettings(
        onChange: (AppearanceSettingsSnapshot) -> Unit,
    ): KeyguardCancellable {
        val leContext = ctx.koin.get<LeContext>()
        val togglesA = combine(
            getThemeUseAmoledDark(),
            getThemeExpressive(),
            getMarkdown(),
            getNavLabel(),
        ) { values -> values.toList() }
        val togglesB = combine(
            getUseExternalBrowser(),
            getMinimizeOnCopy(),
            getCloseToTray(),
            getWebsiteIcons(),
            getGravatar(),
        ) { values -> values.toList() }
        val togglesC = combine(
            getTwoPanelPortrait(),
            getTwoPanelLandscape(),
            getKeepScreenOn(),
        ) { values -> values.toList() }
        val themeFlow = combine(getTheme(), getThemeVariants()) { current, variants -> current to variants }
        val fontFlow = combine(getFont(), getFontVariants()) { current, variants -> current to variants }
        val navAnimFlow = combine(
            getNavAnimation(),
            getNavAnimationVariants(),
        ) { current, variants -> current to variants }
        val localeFlow = combine(getLocale(), getLocaleVariants()) { current, variants -> current to variants }
        val accentFlow = combine(getColors(), getColorsVariants()) { current, variants -> current to variants }
        val pickers1 = combine(themeFlow, fontFlow, navAnimFlow) { theme, font, nav -> Triple(theme, font, nav) }
        val pickers2 = combine(localeFlow, accentFlow) { locale, accent -> locale to accent }
        val job = ctx.scope.launch {
            combine(togglesA, togglesB, togglesC, pickers1, pickers2) { tA, tB, tC, p1, p2 ->
                val theme = p1.first
                val font = p1.second
                val navAnim = p1.third
                val locale = p2.first
                val accent = p2.second
                latestThemeVariants = theme.second
                latestFontVariants = font.second
                latestNavAnimationVariants = navAnim.second
                latestColorsVariants = accent.second
                // Locale options follow the common provider's ordering: the
                // follow-system entry first, then by display name.
                val sortedLocales = locale.second
                    .map { it to localeTitle(it, leContext) }
                    .sortedWith(compareBy({ it.first != null }, { it.second }))
                latestLocaleVariants = sortedLocales.map { it.first }
                AppearanceSettingsSnapshot(
                    loaded = true,
                    amoledDark = tA[0],
                    expressive = tA[1],
                    markdown = tA[2],
                    navLabel = tA[3],
                    useExternalBrowser = tB[0],
                    minimizeOnCopy = tB[1],
                    closeToTray = tB[2],
                    websiteIcons = tB[3],
                    gravatar = tB[4],
                    twoPanelPortrait = tC[0],
                    twoPanelLandscape = tC[1],
                    keepScreenOn = tC[2],
                    themeTitle = themeTitle(theme.first, leContext),
                    themeOptions = theme.second.map { value ->
                        SettingOptionSnapshot(value.optionId(), themeTitle(value, leContext), value == theme.first)
                    },
                    fontTitle = fontTitle(font.first, leContext),
                    fontOptions = font.second.map { value ->
                        SettingOptionSnapshot(value.optionId(), fontTitle(value, leContext), value == font.first)
                    },
                    navAnimationTitle = textResource(navAnim.first.title, leContext),
                    navAnimationOptions = navAnim.second.map { value ->
                        SettingOptionSnapshot(
                            value.optionId(),
                            textResource(value.title, leContext),
                            value == navAnim.first,
                        )
                    },
                    localeTitle = localeTitle(locale.first, leContext),
                    localeOptions = sortedLocales.map { pair ->
                        SettingOptionSnapshot(localeOptionId(pair.first), pair.second, pair.first == locale.first)
                    },
                    accentTitle = accentTitle(accent.first, leContext),
                    accentOptions = accent.second.map { value ->
                        SettingOptionSnapshot(value.optionId(), accentTitle(value, leContext), value == accent.first)
                    },
                )
            }.collect { onChange(it) }
        }
        return KeyguardCancellable(job)
    }

    private suspend fun themeTitle(theme: AppTheme?, leContext: LeContext): String = when (theme) {
        null -> textResource(Res.string.follow_system_settings, leContext)
        AppTheme.DARK -> textResource(Res.string.theme_dark, leContext)
        AppTheme.LIGHT -> textResource(Res.string.theme_light, leContext)
    }

    private suspend fun fontTitle(font: AppFont?, leContext: LeContext): String = when (font) {
        null -> textResource(Res.string.follow_system_settings, leContext)
        AppFont.ROBOTO -> "Roboto"
        AppFont.NOTO -> "Noto"
        AppFont.ATKINSON_HYPERLEGIBLE -> "Atkinson Hyperlegible"
    }

    private suspend fun accentTitle(colors: AppColors?, leContext: LeContext): String = when (colors) {
        null -> textResource(Res.string.follow_system_settings, leContext)
        else -> colors.title
    }

    private suspend fun localeTitle(locale: String?, leContext: LeContext): String =
        locale?.let { LeLocale.displayName(it) }
            ?: textResource(Res.string.follow_system_settings, leContext)

    fun setAmoledDark(value: Boolean) { putThemeUseAmoledDark(value).launchIn(ctx.scope) }
    fun setExpressive(value: Boolean) { putThemeExpressive(value).launchIn(ctx.scope) }
    fun setMarkdown(value: Boolean) { putMarkdown(value).launchIn(ctx.scope) }
    fun setNavLabel(value: Boolean) { putNavLabel(value).launchIn(ctx.scope) }
    fun setUseExternalBrowser(value: Boolean) { putUseExternalBrowser(value).launchIn(ctx.scope) }
    fun setKeepScreenOn(value: Boolean) { putKeepScreenOn(value).launchIn(ctx.scope) }
    fun setMinimizeOnCopy(value: Boolean) { putMinimizeOnCopy(value).launchIn(ctx.scope) }
    fun setCloseToTray(value: Boolean) { putCloseToTray(value).launchIn(ctx.scope) }
    fun setTwoPanelPortrait(value: Boolean) { putTwoPanelPortrait(value).launchIn(ctx.scope) }
    fun setTwoPanelLandscape(value: Boolean) { putTwoPanelLandscape(value).launchIn(ctx.scope) }

    // Option ids are the variant's own name (the locale tag for locales), not its
    // position, so a pick made against a list that has since been re-sorted or
    // re-emitted still selects the variant the user saw.
    fun setTheme(optionId: String) {
        val index = latestThemeVariants.indexOfFirst { it.optionId() == optionId }
        if (index < 0) return
        putTheme(latestThemeVariants[index]).launchIn(ctx.scope)
    }

    fun setFont(optionId: String) {
        val index = latestFontVariants.indexOfFirst { it.optionId() == optionId }
        if (index < 0) return
        putFont(latestFontVariants[index]).launchIn(ctx.scope)
    }

    fun setColors(optionId: String) {
        val index = latestColorsVariants.indexOfFirst { it.optionId() == optionId }
        if (index < 0) return
        putColors(latestColorsVariants[index]).launchIn(ctx.scope)
    }

    fun setLocale(optionId: String) {
        val index = latestLocaleVariants.indexOfFirst { localeOptionId(it) == optionId }
        if (index < 0) return
        putLocale(latestLocaleVariants[index]).launchIn(ctx.scope)
    }

    fun setNavAnimation(optionId: String) {
        val value = latestNavAnimationVariants.firstOrNull { it.optionId() == optionId } ?: return
        putNavAnimation(value).launchIn(ctx.scope)
    }

    /**
     * Observes the preferences the SwiftUI shell applies app-wide (theme, accent,
     * close-to-tray, keep-screen-on). Invoked on the main thread on any change.
     */
    fun observeAppPreferences(
        onChange: (AppPreferencesSnapshot) -> Unit,
    ): KeyguardCancellable {
        val job = ctx.scope.launch {
            combine(
                getTheme(),
                getColors(),
                getCloseToTray(),
                getNavLabel(),
                getLocale(),
            ) { theme, colors, closeToTray, navLabel, locale ->
                AppPreferencesSnapshot(
                    locale = locale,
                    loaded = true,
                    theme = when (theme) {
                        AppTheme.DARK -> "dark"
                        AppTheme.LIGHT -> "light"
                        null -> null
                    },
                    accentArgb = colors?.color?.toArgbLong(),
                    navLabel = navLabel,
                    closeToTray = closeToTray,
                )
            }.combine(getKeepScreenOn()) { preferences, keepScreenOn ->
                preferences.copy(keepScreenOn = keepScreenOn)
            }.combine(getUseExternalBrowser()) { preferences, useExternalBrowser ->
                preferences.copy(useExternalBrowser = useExternalBrowser)
            }.collect { onChange(it) }
        }
        return KeyguardCancellable(job)
    }

    /**
     * Invokes [onMinimize] after every clipboard copy made while the shared
     * "Minimize after copying" preference is enabled.
     */
    fun observeMinimizeOnCopy(
        onMinimize: () -> Unit,
    ): KeyguardCancellable {
        val clipboard = ctx.koin.get<CopyEventsSource>()
        val job = ctx.scope.launch {
            clipboard.copyEvents.collect {
                val minimize = getMinimizeOnCopy().first()
                if (minimize) {
                    onMinimize()
                }
            }
        }
        return KeyguardCancellable(job)
    }

}

/** The picker option id of a "follow system settings" (`null`) variant. */
private const val SYSTEM_OPTION_ID = "system"

private fun Enum<*>?.optionId(): String = this?.name ?: SYSTEM_OPTION_ID

private fun localeOptionId(locale: String?): String = locale ?: SYSTEM_OPTION_ID
