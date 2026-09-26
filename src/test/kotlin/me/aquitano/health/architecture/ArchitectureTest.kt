package me.aquitano.health.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import kotlin.test.Test

class ArchitectureTest {
    private val production = Konsist.scopeFromDirectory("src/main/kotlin")

    @Test
    fun `application-layer repositories do not import transaction entry points`() {
        val repositories = production.classes()
            .filter { it.resideInPackage("me.aquitano.health.application..") }
            .filter { it.name.endsWith("Repository") }
        check(repositories.isNotEmpty()) { "No application repositories found" }
        repositories.assertFalse { repository ->
            repository.containingFile.imports.any { imported ->
                imported.name.startsWith("org.jetbrains.exposed.v1.jdbc.transactions.") ||
                    imported.name == "me.aquitano.health.infrastructure.database.suspendDbTransaction" ||
                    imported.name == "me.aquitano.health.infrastructure.database.*"
            }
        }
    }

    @Test
    fun `api layer does not import Exposed`() {
        val apiFiles = production.files
            .filter { it.packagee?.name?.startsWith("me.aquitano.health.api") == true }
        check(apiFiles.isNotEmpty()) { "No api files found" }
        apiFiles.assertFalse { file ->
            file.imports.any { it.name.startsWith("org.jetbrains.exposed.") }
        }
    }
}
