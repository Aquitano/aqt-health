package me.aquitano.health.api

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import me.aquitano.health.shared.AppJson

fun Application.configureHttp() {
    install(ContentNegotiation) {
        json(AppJson)
    }
    configureRequestLogging()
    configureErrorHandling()
}
