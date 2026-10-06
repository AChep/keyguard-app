package com.artemchep.keyguard.core.session.usecase

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.native.wrapConnection
import co.touchlab.sqliter.DatabaseConnection
import com.artemchep.keyguard.common.model.MasterKey
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

/** Keeps existing queries usable while the vault session replaces its database after rekeying. */
internal class RekeyableAppleSqlDriver(
    initialDriver: SqlDriver,
    initialKey: MasterKey,
    private val openDriver: (MasterKey) -> SqlDriver,
    private val rekeyDatabase: (oldKey: MasterKey, newKey: MasterKey) -> Unit,
    private val openTransactionConnection: (MasterKey) -> DatabaseConnection,
) : SqlDriver {
    private val lock = reentrantLock()
    private var delegate = initialDriver
    private var key = initialKey
    private var transaction: AppleTransaction? = null

    // The delegate never notifies listeners itself, so the wrapper owns them
    // and they survive the delegate being replaced.
    private val listeners = mutableMapOf<String, MutableSet<Query.Listener>>()

    fun rekey(newKey: MasterKey) = lock.withLock {
        check(transaction == null)
        // All pooled connections retain their original cipher key. Close them
        // before rotating the file, and keep queries blocked until replacements
        // use the new key. The old session can still have active collectors here.
        delegate.close()
        try {
            rekeyDatabase(key, newKey)
            key = newKey
        } finally {
            delegate = openDriver(key)
        }
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> = lock.withLock { accessDriver { it.execute(identifier, sql, parameters, binders) } }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = lock.withLock { accessDriver { it.executeQuery(identifier, sql, mapper, parameters, binders) } }

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        lock.lock()
        try {
            val enclosing = transaction
            val connection = enclosing?.connection ?: openTransactionConnection(key)
            if (enclosing == null) {
                try {
                    connection.beginTransaction()
                } catch (e: Throwable) {
                    connection.close()
                    throw e
                }
            }
            val next = AppleTransaction(enclosing, connection)
            transaction = next
            return QueryResult.Value(next)
        } catch (e: Throwable) {
            lock.unlock()
            throw e
        }
    }

    override fun currentTransaction(): Transacter.Transaction? = lock.withLock { transaction }

    private inner class AppleTransaction(
        override val enclosingTransaction: AppleTransaction?,
        val connection: DatabaseConnection,
    ) : Transacter.Transaction() {
        override fun endTransaction(successful: Boolean): QueryResult<Unit> {
            try {
                if (enclosingTransaction == null) {
                    try {
                        if (successful) connection.setTransactionSuccessful()
                        connection.endTransaction()
                    } finally {
                        connection.close()
                    }
                }
                return QueryResult.Value(Unit)
            } finally {
                // SQLDelight does not run completion hooks when COMMIT/ROLLBACK
                // or a query listener throws. Release at the driver boundary.
                transaction = enclosingTransaction
                lock.unlock()
            }
        }
    }

    private fun <T> accessDriver(block: (SqlDriver) -> T): T {
        val connection = transaction?.connection ?: return block(delegate)
        // This adapter uses the transaction's writable connection directly,
        // instead of routing SELECT/PRAGMA through the native reader pool.
        var result: T? = null
        wrapConnection(connection) { result = block(it) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) = lock.withLock {
        queryKeys.forEach { listeners.getOrPut(it) { mutableSetOf() }.add(listener) }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = lock.withLock {
        queryKeys.forEach { listeners[it]?.remove(listener) }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        val listenersToNotify = lock.withLock {
            queryKeys.flatMapTo(mutableSetOf()) { listeners[it].orEmpty() }
        }
        listenersToNotify.forEach(Query.Listener::queryResultsChanged)
    }

    override fun close() = lock.withLock { delegate.close() }
}
