package com.artemchep.keyguard.common.service.browser

/**
 * Installs, repairs, reports and removes the native messaging manifests that
 * let browsers start Keyguard's connector, `keyguard-browser-host`. Only the
 * desktop app has one.
 */
interface BrowserConnectorClient {
    suspend fun status(): BrowserConnectorReport

    /**
     * Installs or repairs the manifests of [browsers], or of every browser
     * found if it is empty.
     */
    suspend fun install(
        browsers: Set<BrowserConnectorBrowser> = emptySet(),
    ): BrowserConnectorReport

    /**
     * Installs or repairs the manifests of [browsers] like [install], if
     * [proceed] holds right before the install starts: the user may change
     * their choice while another operation runs.
     *
     * @return `null` if it skipped the install.
     */
    suspend fun installIf(
        browsers: Set<BrowserConnectorBrowser>,
        proceed: () -> Boolean,
    ): BrowserConnectorReport?

    /** Removes the manifests of [browsers], or of every browser if it is empty. */
    suspend fun uninstall(
        browsers: Set<BrowserConnectorBrowser> = emptySet(),
    ): BrowserConnectorReport
}

/**
 * A browser whose manifests the connector installer manages, by its CLI ID.
 * The Flathub builds, on Linux only, read manifests of their own.
 */
enum class BrowserConnectorBrowser(
    val id: String,
    val flatpak: Boolean = false,
) {
    CHROME("chrome"),
    CHROMIUM("chromium"),
    EDGE("edge"),
    BRAVE("brave"),
    VIVALDI("vivaldi"),
    FIREFOX("firefox"),
    CHROME_FLATPAK("chrome-flatpak", flatpak = true),
    CHROMIUM_FLATPAK("chromium-flatpak", flatpak = true),
    EDGE_FLATPAK("edge-flatpak", flatpak = true),
    BRAVE_FLATPAK("brave-flatpak", flatpak = true),
    FIREFOX_FLATPAK("firefox-flatpak", flatpak = true),
}

/** How this build runs, which decides what the manifests start. */
enum class BrowserConnectorPackaging(val id: String) {
    /** The bundled connector. */
    NATIVE("native"),

    /** A wrapper next to each manifest, which runs the connector with `flatpak run`. */
    FLATPAK("flatpak"),

    /** A copy of the connector, since each run mounts the AppImage elsewhere. */
    APPIMAGE("appimage"),

    /** The connector's execution alias, since browsers may not start a program inside a package. */
    MSIX("msix"),
}

data class BrowserConnectorStatus(
    val browser: BrowserConnectorBrowser,
    val state: State,
    /**
     * Whether the browser was used by this user. Inside Flatpak, whether
     * the sandbox sees the browser's manifest directory.
     */
    val detected: Boolean,
    /** Several browsers may share one manifest, e.g. Brave reads Chrome's on macOS. */
    val manifest: String,
    /** Windows: the registry key that points the browser at the manifest. */
    val registryKey: String?,
    /** What the current manifest starts, if Keyguard wrote it. */
    val host: String?,
) {
    enum class State(val code: String) {
        INSTALLED("installed"),

        /** The browser was used, but has no manifest. */
        MISSING("missing"),

        /** It starts another connector or differs from what install writes; install repairs it. */
        STALE("stale"),

        /** Neither the browser nor a manifest was found. */
        BROWSER_ABSENT("browser_absent"),
    }
}

data class BrowserConnectorReport(
    val packaging: BrowserConnectorPackaging,
    val browsers: List<BrowserConnectorStatus>,
)

/**
 * Groups the browsers that share one manifest, in the order of the report.
 * E.g. Brave reads Chrome's manifest on macOS; on Windows, Brave and Vivaldi
 * share Chrome's manifest and registry key.
 */
fun List<BrowserConnectorStatus>.groups(): List<List<BrowserConnectorStatus>> =
    groupBy { it.manifest to it.registryKey }.values.toList()

data class BrowserConnectorError(
    val kind: Kind,
    val path: String? = null,
    val browser: BrowserConnectorBrowser? = null,
    /**
     * The file name of the MSIX package's execution alias, when a
     * [Kind.HOST_MISSING] error's [path] is the alias. The user can turn an
     * alias off, and the package of another build of Keyguard can own it.
     */
    val alias: String? = null,
    val detail: String? = null,
) {
    enum class Kind(val code: String) {
        /** The bundled connector, or the MSIX package's alias of it, is missing or can't run. */
        HOST_MISSING("host_missing"),

        /** The selected browser was not found. */
        BROWSER_ABSENT("browser_absent"),

        /** Keyguard did not write the file at [path], so it leaves it alone. */
        FOREIGN_FILE("foreign_file"),

        /** This build can't reach the browsers' files, e.g. in the macOS app sandbox. */
        UNSUPPORTED("unsupported"),

        /** A link, another user's file, or a file others can write is at or above [path]. */
        UNSAFE_FILE("unsafe_file"),

        /** The CLI speaks another version of the report. */
        UPDATE_REQUIRED("update_required"),
        FAILED("failed"),
    }
}

class BrowserConnectorException(val error: BrowserConnectorError) : Exception(error.detail)

fun Throwable.toBrowserConnectorError(): BrowserConnectorError =
    (this as? BrowserConnectorException)?.error
        ?: BrowserConnectorError(BrowserConnectorError.Kind.FAILED, detail = message ?: this::class.simpleName)
