package com.artemchep.dbus.portal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import org.freedesktop.dbus.DBusMatchRule
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusMemberName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

private const val PORTAL_DEST = "org.freedesktop.portal.Desktop"
private const val PORTAL_OBJECT_PATH = "/org/freedesktop/portal/desktop"
private const val SETTINGS_INTERFACE = "org.freedesktop.portal.Settings"
private const val APPEARANCE_NAMESPACE = "org.freedesktop.appearance"
private const val COLOR_SCHEME_KEY = "color-scheme"

public enum class PortalColorScheme {
    NO_PREFERENCE,
    PREFER_DARK,
    PREFER_LIGHT,
}

public fun observePortalColorSchemeDbus(): Flow<PortalColorScheme> = callbackFlow {
    val connection = runCatching {
        newSessionConnection()
    }.getOrElse {
        close()
        return@callbackFlow
    }

    val rule = DBusMatchRule(
        "signal",
        SETTINGS_INTERFACE,
        "SettingChanged",
        PORTAL_OBJECT_PATH,
    )
    val handler = DBusSigHandler<DBusSignal> { signal ->
        signal.toPortalColorSchemeChange()?.let {
            trySend(it)
        }
    }
    val signalSubscription = runCatching {
        connection.addGenericSigHandler(rule, handler)
    }.getOrElse {
        runCatching {
            connection.close()
        }
        close()
        return@callbackFlow
    }

    runCatching {
        readPortalColorScheme(connection)
    }.getOrNull()?.let {
        trySend(it)
    }

    awaitClose {
        runCatching {
            signalSubscription.close()
        }
        runCatching {
            connection.close()
        }
    }
}
    .distinctUntilChanged()
    .flowOn(Dispatchers.IO)

private fun newSessionConnection(): DBusConnection =
    DBusConnectionBuilder.forSessionBus()
        .withShared(false)
        .build()

private fun readPortalColorScheme(connection: DBusConnection): PortalColorScheme? {
    val settings = connection.getRemoteObject(
        PORTAL_DEST,
        PORTAL_OBJECT_PATH,
        SettingsPortal::class.java,
    )
    return runCatching {
        settings.ReadOne(APPEARANCE_NAMESPACE, COLOR_SCHEME_KEY)
            .toPortalColorScheme()
    }.getOrNull()
        ?: runCatching {
            settings.Read(APPEARANCE_NAMESPACE, COLOR_SCHEME_KEY)
                .toPortalColorScheme()
        }.getOrNull()
}

internal fun DBusSignal.toPortalColorSchemeChange(): PortalColorScheme? = runCatching {
    val parameters = getParameters()
    val namespace = parameters.getOrNull(0) as? String
    val key = parameters.getOrNull(1) as? String
    val value = parameters.getOrNull(2)
    portalColorSchemeFromSettingChanged(
        namespace = namespace,
        key = key,
        value = value,
    )
}.getOrNull()

internal fun portalColorSchemeFromSettingChanged(
    namespace: String?,
    key: String?,
    value: Any?,
): PortalColorScheme? {
    if (namespace != APPEARANCE_NAMESPACE || key != COLOR_SCHEME_KEY) {
        return null
    }
    return value.toPortalColorScheme()
}

internal fun Any?.toPortalColorScheme(): PortalColorScheme? =
    when (val value = unwrapVariant()) {
        is UInt32 -> portalColorSchemeOfValue(value.toLong())
        is Number -> portalColorSchemeOfValue(value.toLong())
        else -> null
    }

private tailrec fun Any?.unwrapVariant(): Any? =
    if (this is Variant<*>) {
        value.unwrapVariant()
    } else {
        this
    }

private fun portalColorSchemeOfValue(value: Long): PortalColorScheme =
    when (value) {
        1L -> PortalColorScheme.PREFER_DARK
        2L -> PortalColorScheme.PREFER_LIGHT
        else -> PortalColorScheme.NO_PREFERENCE
    }

@DBusInterfaceName(SETTINGS_INTERFACE)
internal interface SettingsPortal : DBusInterface {
    @Suppress("FunctionName")
    @DBusMemberName("ReadOne")
    fun ReadOne(namespace: String, key: String): Variant<*>

    @Suppress("FunctionName")
    @DBusMemberName("Read")
    fun Read(namespace: String, key: String): Variant<*>
}
