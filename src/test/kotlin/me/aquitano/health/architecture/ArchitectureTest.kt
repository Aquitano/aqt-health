package me.aquitano.health.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.jetbrains.kotlin.lexer.KotlinLexer
import org.jetbrains.kotlin.lexer.KtTokens
import kotlin.test.Test

class ArchitectureTest {
    // Konsist's production scope also walks nested worktrees and stale IDE output; scope by directory.
    private val production = Konsist.scopeFromDirectory("src/main/kotlin")

    /**
     * Read-model repositories under application/ build Exposed queries but never open
     * transactions; the calling service owns the boundary. Infrastructure repositories are
     * exempt by design.
     */
    @Test
    fun `application-layer repositories do not reference transaction entry points`() {
        val repositories = production.classes()
            .filter { it.resideInPackage("me.aquitano.health.application..") }
            .filter { it.name.endsWith("Repository") }
        check(repositories.isNotEmpty()) { "No application repositories found" }
        repositories.assertFalse { repository ->
            val file = repository.containingFile
            file.imports.any { imported ->
                imported.name.startsWith("org.jetbrains.exposed.v1.jdbc.transactions.") ||
                    imported.name == "me.aquitano.health.infrastructure.database.suspendDbTransaction" ||
                    imported.name == "me.aquitano.health.infrastructure.database.*"
            } || qualifiedCode(file.text).let { code ->
                code.contains("org.jetbrains.exposed.v1.jdbc.transactions.") ||
                    code.contains("me.aquitano.health.infrastructure.database.suspendDbTransaction")
            }
        }
    }

    @Test
    fun `api layer does not reference Exposed`() {
        val apiFiles = production.files
            .filter { it.packagee?.name?.startsWith("me.aquitano.health.api") == true }
        check(apiFiles.isNotEmpty()) { "No api files found" }
        apiFiles.assertFalse { file ->
            file.imports.any { it.name.startsWith("org.jetbrains.exposed.") } ||
                qualifiedCode(file.text).contains("org.jetbrains.exposed.")
        }
    }

    private fun qualifiedCode(source: String): String = buildString {
        val lexer = KotlinLexer()
        lexer.start(source)
        while (lexer.tokenType != null) {
            when (val token = lexer.tokenType) {
                KtTokens.IDENTIFIER -> append(lexer.tokenText.removeSurrounding("`"))
                KtTokens.DOT -> append('.')
                KtTokens.WHITE_SPACE -> Unit
                else -> if (!KtTokens.COMMENTS.contains(token)) append(' ')
            }
            lexer.advance()
        }
    }
}
