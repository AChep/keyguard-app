package com.artemchep.dbus.tray

import com.artemchep.dbus.newSessionConnection
import com.artemchep.dbus.unwrapVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.matchrules.DBusMatchRule
import org.freedesktop.dbus.matchrules.DBusMatchRuleBuilder
import org.freedesktop.dbus.messages.DBusSignal
import java.util.concurrent.atomic.AtomicLong

private const val DBUS_NAME = "org.freedesktop.DBus"
private const val DBUS_OBJECT_PATH = "/org/freedesktop/DBus"
private const val DBUS_INTERFACE = "org.freedesktop.DBus"
private const val NAME_OWNER_CHANGED = "NameOwnerChanged"

private const val WATCHER_NAME = "org.kde.StatusNotifierWatcher"
private const val WATCHER_OBJECT_PATH = "/StatusNotifierWatcher"
private const val WATCHER_INTERFACE = "org.kde.StatusNotifierWatcher"
private const val HOST_REGISTERED_PROPERTY = "IsStatusNotifierHostRegistered"
private const val HOST_REGISTERED = "StatusNotifierHostRegistered"
private const val HOST_UNREGISTERED = "StatusNotifierHostUnregistered"

/**
 * Whether a StatusNotifier host is available on the session bus. [watcherGeneration]
 * changes with the watcher's owner: a new watcher has forgotten registered icons.
 */
public data class StatusNotifierHost(
    val available: Boolean,
    val watcherGeneration: Long,
)

/**
 * Emits the [StatusNotifierHost] state. Only a confirmed missing watcher or host is
 * unavailable. A failed read, such as an unreachable bus, counts as available, as before
 * the probe existed. Never throws.
 */
public fun observeStatusNotifierHost(): Flow<StatusNotifierHost> = callbackFlow {
    val connection = runCatching {
        newSessionConnection()
    }.getOrElse {
        trySend(StatusNotifierHost(available = true, watcherGeneration = 0))
        close()
        return@callbackFlow
    }

    val dbus = runCatching {
        StatusNotifierHostProbe(connection)
    }.getOrElse {
        runCatching {
            connection.close()
        }
        trySend(StatusNotifierHost(available = true, watcherGeneration = 0))
        close()
        return@callbackFlow
    }

    // Read on the collector's dispatcher, not on the D-Bus signal thread.
    val refresh = Channel<Unit>(Channel.CONFLATED)
    val generation = AtomicLong()
    val handler = statusNotifierHostHandler(refresh, generation)
    val signalSubscriptions = mutableListOf<AutoCloseable>()
    val subscribed = runCatching {
        statusNotifierHostMatchRules().forEach { rule ->
            signalSubscriptions += connection.addGenericSigHandler(rule, handler)
        }
    }.isSuccess
    if (!subscribed) {
        // Without signals a late tray would go unnoticed, so report once and stop.
        trySend(StatusNotifierHost(available = dbus.read().isAvailable(), watcherGeneration = 0))
        runCatching {
            connection.close()
        }
        close()
        return@callbackFlow
    }

    refresh.trySend(Unit)
    launch {
        for (event in refresh) {
            // Read the generation first: a change during the read queues another one.
            val watcherGeneration = generation.get()
            send(StatusNotifierHost(available = dbus.read().isAvailable(), watcherGeneration = watcherGeneration))
        }
    }

    awaitClose {
        signalSubscriptions.forEach { subscription ->
            runCatching {
                subscription.close()
            }
        }
        runCatching {
            connection.close()
        }
    }
}
    .distinctUntilChanged()
    .flowOn(Dispatchers.IO)

private fun statusNotifierHostHandler(
    refresh: Channel<Unit>,
    generation: AtomicLong,
) = DBusSigHandler<DBusSignal> { signal ->
    if (signal.isStatusNotifierHostChange()) {
        if (signal.isStatusNotifierWatcherOwnerChange()) {
            generation.incrementAndGet()
        }
        refresh.trySend(Unit)
    }
}

private class StatusNotifierHostProbe(connection: DBusConnection) {
    private val dbus = connection.getRemoteObject(
        DBUS_NAME,
        DBUS_OBJECT_PATH,
        DBus::class.java,
    )

    // Never D-Bus activate a watcher just to ask about it.
    private val watcher = connection.getRemoteObject(
        WATCHER_NAME,
        WATCHER_OBJECT_PATH,
        Properties::class.java,
        false,
    )

    /** `null` if the read failed: whether there is a host is unknown. */
    fun read(): Boolean? = runCatching {
        dbus.NameHasOwner(WATCHER_NAME) &&
            watcher.Get<Any?>(WATCHER_INTERFACE, HOST_REGISTERED_PROPERTY)
                .toStatusNotifierHostRegistered()
    }.getOrNull()
}

/** An unknown host counts as available, so a failed probe never hides a working tray. */
internal fun Boolean?.isAvailable(): Boolean = this != false

private fun statusNotifierHostMatchRules(): List<DBusMatchRule> = listOf(
    DBusMatchRuleBuilder.create()
        .withType("signal")
        .withInterface(DBUS_INTERFACE)
        .withMember(NAME_OWNER_CHANGED)
        .withPath(DBUS_OBJECT_PATH)
        .withArg0123(0, WATCHER_NAME)
        .build(),
    // Any watcher path: other tray implementations may export it elsewhere.
    DBusMatchRuleBuilder.create()
        .withType("signal")
        .withInterface(WATCHER_INTERFACE)
        .withMember(HOST_REGISTERED)
        .build(),
    DBusMatchRuleBuilder.create()
        .withType("signal")
        .withInterface(WATCHER_INTERFACE)
        .withMember(HOST_UNREGISTERED)
        .build(),
)

internal fun DBusSignal.isStatusNotifierHostChange(): Boolean = runCatching {
    isStatusNotifierHostChange(
        interfaceName = getInterface(),
        member = name,
        arg0 = getParameters().getOrNull(0),
    )
}.getOrDefault(false)

internal fun isStatusNotifierHostChange(
    interfaceName: String?,
    member: String?,
    arg0: Any?,
): Boolean = when (interfaceName) {
    DBUS_INTERFACE -> isStatusNotifierWatcherOwnerChange(interfaceName, member, arg0)
    WATCHER_INTERFACE -> member == HOST_REGISTERED || member == HOST_UNREGISTERED
    else -> false
}

private fun DBusSignal.isStatusNotifierWatcherOwnerChange(): Boolean = runCatching {
    isStatusNotifierWatcherOwnerChange(
        interfaceName = getInterface(),
        member = name,
        arg0 = getParameters().getOrNull(0),
    )
}.getOrDefault(false)

internal fun isStatusNotifierWatcherOwnerChange(
    interfaceName: String?,
    member: String?,
    arg0: Any?,
): Boolean = interfaceName == DBUS_INTERFACE && member == NAME_OWNER_CHANGED && arg0 == WATCHER_NAME

internal fun Any?.toStatusNotifierHostRegistered(): Boolean =
    unwrapVariant() == true
