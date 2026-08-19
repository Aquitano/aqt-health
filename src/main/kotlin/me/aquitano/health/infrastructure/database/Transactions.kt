package me.aquitano.health.infrastructure.database

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-database dispatchers for blocking JDBC work, sized to the connection pool so
 * transactions neither block the caller's dispatcher nor occupy more threads than
 * the pool has connections.
 */
object DatabaseDispatchers {
    private val dispatchers = ConcurrentHashMap<Database, CoroutineDispatcher>()

    fun register(database: Database, poolSize: Int) {
        dispatchers[database] = Dispatchers.IO.limitedParallelism(poolSize)
    }

    fun forDatabase(database: Database): CoroutineDispatcher =
        dispatchers[database] ?: Dispatchers.IO
}

/**
 * [suspendTransaction] variant that runs the blocking JDBC work on the database's
 * pool-sized IO dispatcher instead of the caller's dispatcher. All application code
 * should use this instead of calling [suspendTransaction] directly.
 */
suspend fun <T> suspendDbTransaction(
    db: Database,
    statement: suspend JdbcTransaction.() -> T,
): T =
    withContext(DatabaseDispatchers.forDatabase(db)) {
        suspendTransaction(db = db, statement = statement)
    }

/**
 * Runs [block] inside a savepoint so a failure rolls back only the writes it made. Postgres
 * aborts the whole transaction on a SQL error (25P02), so without this the caller cannot record
 * the failure in the same transaction. The exception is rethrown either way.
 */
inline fun <T> JdbcTransaction.withSavepoint(name: String, block: () -> T): T {
    val savepoint = connection.setSavepoint(name)
    return try {
        block().also { connection.releaseSavepoint(savepoint) }
    } catch (exception: Throwable) {
        connection.rollback(savepoint)
        throw exception
    }
}
