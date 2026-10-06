package com.artemchep.keyguard.core.session.usecase

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import co.touchlab.sqliter.DatabaseConnection
import co.touchlab.sqliter.Statement
import co.touchlab.sqliter.interop.SqliteDatabasePointer
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import kotlinx.cinterop.ExperimentalForeignApi
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_SEC
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class RekeyableAppleSqlDriverTest {
    private val oldKey = MasterKey(MasterKdfVersion.V1, byteArrayOf(1))
    private val newKey = MasterKey(MasterKdfVersion.V1, byteArrayOf(2))

    @Test
    fun retainedDriverUsesNewConnectionAndKeepsListeners() {
        val old = FakeDriver(1)
        val replacement = FakeDriver(2)
        val driver = RekeyableAppleSqlDriver(
            old, oldKey,
            openDriver = {
                assertEquals(newKey, it)
                replacement
            },
            rekeyDatabase = { currentKey, nextKey ->
                assertTrue(old.closed)
                assertEquals(oldKey, currentKey)
                assertEquals(newKey, nextKey)
            },
            openTransactionConnection = { FakeConnection() },
        )
        var notifications = 0
        val listener = object : Query.Listener {
            override fun queryResultsChanged() { notifications++ }
        }
        driver.addListener("items", listener = listener)
        driver.rekey(newKey)
        assertEquals(2L, driver.execute(null, "test", 0, null).value)
        driver.notifyListeners("items")
        assertEquals(1, notifications)
        driver.removeListener("items", listener = listener)
        driver.notifyListeners("items")
        assertEquals(1, notifications)
    }

    @Test
    fun failedRekeyReopensOldKeyAndLeavesDriverUsable() {
        val driver = RekeyableAppleSqlDriver(
            FakeDriver(1), oldKey,
            openDriver = {
                assertEquals(oldKey, it)
                FakeDriver(3)
            },
            rekeyDatabase = { _, _ -> error("failed rekey") },
            openTransactionConnection = { FakeConnection() },
        )
        assertFailsWith<IllegalStateException> { driver.rekey(newKey) }
        assertEquals(3L, driver.execute(null, "test", 0, null).value)
    }

    @Test
    fun nestedTransactionsShareConnectionAndCommitOnce() {
        val connection = FakeConnection()
        val driver = transactionDriver(connection)
        val transacter = object : TransacterImpl(driver) {}
        transacter.transaction {
            transaction { }
        }
        assertEquals(1, connection.begins)
        assertEquals(1, connection.commits)
        assertEquals(1, connection.ends)
        assertTrue(connection.closed)
        assertNull(driver.currentTransaction())
        assertAccessibleFromAnotherThread(driver)
    }

    @Test
    fun nestedRollbackRollsBackOuterTransaction() {
        val connection = FakeConnection()
        val driver = transactionDriver(connection)
        val transacter = object : TransacterImpl(driver) {}
        transacter.transaction {
            transaction { rollback() }
        }
        assertEquals(0, connection.commits)
        assertEquals(1, connection.ends)
        assertTrue(connection.closed)
        assertAccessibleFromAnotherThread(driver)
    }

    @Test
    fun transactionEndFailureClosesConnectionAndReleasesLock() {
        val connection = FakeConnection(failEnd = true)
        val driver = transactionDriver(connection)
        val transacter = object : TransacterImpl(driver) {}
        assertFailsWith<IllegalStateException> { transacter.transaction { } }
        assertTrue(connection.closed)
        assertNull(driver.currentTransaction())
        assertAccessibleFromAnotherThread(driver)
    }

    @Test
    fun throwingListenerDoesNotRetainTransactionLock() {
        val driver = transactionDriver(FakeConnection())
        val listener = object : Query.Listener {
            override fun queryResultsChanged() { error("listener failed") }
        }
        driver.addListener("items", listener = listener)
        val transacter = object : TransacterImpl(driver) {
            fun changed() = notifyQueries(1) { it("items") }
        }
        assertFailsWith<IllegalStateException> {
            transacter.transaction { transacter.changed() }
        }
        assertNull(driver.currentTransaction())
        assertAccessibleFromAnotherThread(driver)
    }

    private fun transactionDriver(connection: FakeConnection) = RekeyableAppleSqlDriver(
        FakeDriver(1), oldKey,
        openDriver = { FakeDriver(2) },
        rekeyDatabase = { _, _ -> },
        openTransactionConnection = { connection },
    )

    private fun assertAccessibleFromAnotherThread(driver: SqlDriver) {
        val completed = dispatch_semaphore_create(0)
        dispatch_async(dispatch_get_global_queue(0, 0u)) {
            driver.execute(null, "test", 0, null)
            dispatch_semaphore_signal(completed)
        }
        val timeout = dispatch_time(DISPATCH_TIME_NOW, 2 * NSEC_PER_SEC.toLong())
        assertEquals(
            0L,
            dispatch_semaphore_wait(completed, timeout),
            "Transaction retained its lock after completion",
        )
    }

    private class FakeConnection(private val failEnd: Boolean = false) : DatabaseConnection {
        var begins = 0
        var commits = 0
        var ends = 0
        override var closed = false
        override fun beginTransaction() { begins++ }
        override fun setTransactionSuccessful() { commits++ }
        override fun endTransaction() {
            ends++
            if (failEnd) error("end failed")
        }
        override fun close() { closed = true }
        override fun rawExecSql(sql: String) = error("Unused")
        override fun createStatement(sql: String): Statement = error("Unused")
        override fun getDbPointer(): SqliteDatabasePointer = error("Unused")
    }

    private class FakeDriver(private val value: Long) : SqlDriver {
        var closed = false
        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            check(!closed)
            return QueryResult.Value(value)
        }
        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> = error(
            "Unused",
        )
        override fun newTransaction(): QueryResult<Transacter.Transaction> = error("Unused")
        override fun currentTransaction(): Transacter.Transaction? = null
        override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit
        override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit
        override fun notifyListeners(vararg queryKeys: String) = Unit
        override fun close() { closed = true }
    }
}
