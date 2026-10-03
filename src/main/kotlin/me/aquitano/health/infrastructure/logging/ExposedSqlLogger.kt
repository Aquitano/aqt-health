package me.aquitano.health.infrastructure.logging

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext

object Slf4jSqlLogger : SqlLogger {
    private val logger = KotlinLogging.logger("me.aquitano.health.database.Sql")

    override fun log(
        context: StatementContext,
        transaction: Transaction,
    ) {
        if (logger.isDebugEnabled()) {
            logger.debug { "SQL: ${context.sql(transaction)}" }
        }
    }
}
