package com.artemchep.keyguard.feature.browserextension.ui

import com.artemchep.keyguard.common.service.browser.BrowserConnectorBrowser
import com.artemchep.keyguard.common.service.browser.BrowserConnectorError
import com.artemchep.keyguard.common.service.browser.BrowserConnectorPackaging
import kotlin.time.Instant

/** The connectors and the paired browsers of the browser extension settings. */
data class BrowserExtensionSettingsState(
    /** What the switch of the integration says; `null` shows its description. */
    val status: Status?,
    /** The setup guide, `null` once a browser is paired or while the integration is off. */
    val setup: Setup?,
    /** `null` if this build installs no connectors. */
    val connectors: Connectors?,
    val pairings: List<Pairing>,
    /** The last change of a pairing failed, until the next one. */
    val pairingFailed: Boolean,
) {
    sealed interface Status {
        data class Paired(
            val paired: Int,
            /** The paired browsers connected now. */
            val connected: Int,
        ) : Status

        /** The last operation on the connectors failed, until the next one. */
        data object ConnectorFailed : Status
    }

    data class Setup(
        /** The first step; `null` if this build installs no connectors. */
        val connector: SetupConnector?,
        /** Where to get the extension for the browsers found. */
        val stores: List<BrowserExtensionStore>,
        /** An extension is connected now, paired or not. */
        val extensionConnected: Boolean,
    )

    sealed interface SetupConnector {
        /** Reading the report, or installing the first connector. */
        data object Busy : SetupConnector

        /** The connectors of these browsers are installed. */
        data class Installed(
            val browsers: List<BrowserConnectorBrowser>,
        ) : SetupConnector

        /**
         * Nothing is installed and Keyguard doesn't install it on its own:
         * the user turned the browsers off. A manifest in the way that
         * Keyguard didn't write shows as [Failed] instead.
         */
        data object TurnOnBrowser : SetupConnector

        /** Keyguard found no supported browser. */
        data class NoBrowser(
            /** Looks for the browsers again. */
            val onRetry: () -> Unit,
        ) : SetupConnector

        /** The last operation failed, or a manifest that Keyguard didn't write is in the way. */
        data class Failed(
            val error: BrowserConnectorError,
            /** Repeats the operation that failed, or looks for the browsers again. */
            val onRetry: () -> Unit,
        ) : SetupConnector
    }

    data class Connectors(
        /** `null` until the first report. */
        val packaging: BrowserConnectorPackaging?,
        /** One row per manifest: browsers that share one switch together. */
        val items: List<Connector>,
        /** An operation is running; the actions are `null` meanwhile. */
        val busy: Boolean,
        /** Why the last operation failed, until the next one. */
        val error: BrowserConnectorError?,
        /**
         * Repeats the operation that failed: the change of a row, or
         * reading the report and installing the missing connectors; `null`
         * while busy or if nothing failed.
         */
        val onRetry: (() -> Unit)?,
    )

    data class Connector(
        /** The same across reports: the IDs of every browser that shares the manifest. */
        val id: String,
        /** The browsers found that share the manifest, or all of them if none was found. */
        val browsers: List<BrowserConnectorBrowser>,
        val status: ConnectorStatus,
        /** A manifest is there, whether it works or not. */
        val checked: Boolean,
        /**
         * Installs the connector, or removes it and keeps Keyguard from
         * installing it again; `null` while busy, or if no browser of the
         * row was found.
         */
        val onCheckedChange: ((Boolean) -> Unit)?,
    )

    enum class ConnectorStatus {
        INSTALLED,
        NOT_INSTALLED,

        /** It starts another connector, or Keyguard didn't write it. */
        NEEDS_REPAIR,
    }

    data class Pairing(
        val id: String,
        val name: String,
        /** The origin of the extension. */
        val origin: String,
        val createdAt: Instant,
        val lastUsedAt: Instant,
        /** The browser is connected now. */
        val connected: Boolean,
        /** `null` while a pairing changes. */
        val onRename: ((String) -> Unit)?,
        /** Removes the pairing and ends its connections; `null` while a pairing changes. */
        val onRemove: (() -> Unit)?,
    )
}

/** Where to get the Keyguard extension. */
enum class BrowserExtensionStore {
    /** Google Chrome, Chromium, Brave and Vivaldi. */
    CHROME_WEB_STORE,
    EDGE_ADD_ONS,
    FIREFOX_ADD_ONS,
}

val BrowserConnectorBrowser.store: BrowserExtensionStore
    get() = when (this) {
        BrowserConnectorBrowser.CHROME,
        BrowserConnectorBrowser.CHROMIUM,
        BrowserConnectorBrowser.BRAVE,
        BrowserConnectorBrowser.VIVALDI,
        BrowserConnectorBrowser.CHROME_FLATPAK,
        BrowserConnectorBrowser.CHROMIUM_FLATPAK,
        BrowserConnectorBrowser.BRAVE_FLATPAK,
        -> BrowserExtensionStore.CHROME_WEB_STORE

        BrowserConnectorBrowser.EDGE,
        BrowserConnectorBrowser.EDGE_FLATPAK,
        -> BrowserExtensionStore.EDGE_ADD_ONS

        BrowserConnectorBrowser.FIREFOX,
        BrowserConnectorBrowser.FIREFOX_FLATPAK,
        -> BrowserExtensionStore.FIREFOX_ADD_ONS
    }
