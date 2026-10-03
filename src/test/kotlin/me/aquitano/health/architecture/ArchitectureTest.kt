package me.aquitano.health.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import kotlin.test.Test

class ArchitectureTest {
    // Konsist's production scope also walks nested worktrees and stale IDE output; scope by directory.
    private val production = Konsist.scopeFromDirectory("src/main/kotlin")

    /**
     * Read-model repositories under application/ build Exposed queries but never open
     * transactions; the calling service owns the boundary. Infrastructure repositories are
     * exempt by design. File text also catches aliased and fully qualified references.
     */
    @Test
    fun `application-layer repositories do not reference transaction entry points`() {
        val repositories = production.classes()
            .filter { it.resideInPackage("me.aquitano.health.application..") }
            .filter { it.name.endsWith("Repository") }
        check(repositories.isNotEmpty()) { "No application repositories found" }
        repositories.assertFalse { repository ->
            val text = repository.containingFile.text
            text.contains("org.jetbrains.exposed.v1.jdbc.transactions.") || text.contains("suspendDbTransaction")
        }
    }

    @Test
    fun `api layer does not reference Exposed`() {
        val apiFiles = production.files
            .filter { it.packagee?.name?.startsWith("me.aquitano.health.api") == true }
        check(apiFiles.isNotEmpty()) { "No api files found" }
        apiFiles.assertFalse { file -> file.text.contains("org.jetbrains.exposed") }
    }
}
