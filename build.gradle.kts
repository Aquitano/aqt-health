plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)

    alias(libs.plugins.ben.manes.versions)
    alias(libs.plugins.ktlint)
}

group = "me.aquitano"
version = "0.0.1"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(25)
}

ktor {
    openApi {
        enabled = true
        codeInferenceEnabled = true
        onlyCommented = false
    }
}

dependencies {
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-client-content-negotiation")
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-logging")
    implementation("io.ktor:ktor-server-auth")
    implementation("io.ktor:ktor-server-body-limit")
    implementation("io.ktor:ktor-server-call-id")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-server-metrics-micrometer")
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.snappy.java)
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-openapi")
    implementation("io.ktor:ktor-server-routing-openapi")
    implementation("io.ktor:ktor-openapi-schema")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-swagger")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-config-yaml")
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)

    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.kotlinx.coroutines.slf4j)
    implementation(libs.kotlin.logging.jvm)
    implementation(libs.okhttp)

    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.jdbc)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)

    implementation(libs.google.cloud.health)

    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("io.ktor:ktor-client-mock")
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.konsist)
    testCompileOnly(libs.konsist.kotlin.compiler)
}

ktlint {
    version.set(libs.versions.ktlint)
}

// The Ktor plugin defaults shadowJar to DuplicatesStrategy.EXCLUDE, which drops
// duplicate service files before the merge transformer sees them; Flyway then
// registers only half its plugins and NPEs on the first connection.
tasks.shadowJar {
    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    mergeServiceFiles()
}

tasks.test {
    description = "Runs fast unit tests that do not require PostgreSQL."
    useJUnit {
        excludeCategories("me.aquitano.health.test.PostgresIntegration")
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs PostgreSQL-backed integration tests."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    shouldRunAfter(tasks.test)
    useJUnit {
        includeCategories("me.aquitano.health.test.PostgresIntegration")
    }
    exclude("**/OpenApiExportTest.class")
}

tasks.check {
    dependsOn(tasks.named("integrationTest"))
}

tasks.register<Test>("generateOpenApi") {
    description = "Generates the runtime OpenAPI contract at build/openapi/openapi.json."
    group = "documentation"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/OpenApiExportTest.class")
    systemProperty(
        "aqtHealth.openapi.output",
        layout.buildDirectory
            .file("openapi/openapi.json")
            .get()
            .asFile.absolutePath,
    )
    outputs.file(layout.buildDirectory.file("openapi/openapi.json"))
}
