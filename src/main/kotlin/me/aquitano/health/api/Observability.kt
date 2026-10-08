package me.aquitano.health.api

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.*
import io.ktor.server.metrics.micrometer.*
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import io.opentelemetry.instrumentation.micrometer.v1_5.OpenTelemetryMeterRegistry
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk

private val logger = KotlinLogging.logger("me.aquitano.health.api.Observability")

/**
 * Exports metrics and logs over OTLP once OTEL_EXPORTER_OTLP_ENDPOINT is set. Everything else
 * (headers, resource attributes, protocol) comes from the standard OTEL_* environment variables.
 */
fun Application.configureObservability() {
    if (System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT").isNullOrBlank()) {
        // The logback appender buffers events until an SDK is installed; a no-op one releases them.
        OpenTelemetryAppender.install(OpenTelemetry.noop())
        logger.info { "OTLP export disabled: OTEL_EXPORTER_OTLP_ENDPOINT is not set" }
        return
    }
    // Closed on ApplicationStopped rather than by the SDK's own JVM hook, which races Ktor's graceful stop.
    val openTelemetry =
        AutoConfiguredOpenTelemetrySdk
            .builder()
            .addPropertiesSupplier {
                mapOf(
                    "otel.service.name" to "aqt-health",
                    "otel.exporter.otlp.protocol" to "http/protobuf",
                    "otel.traces.exporter" to "none",
                )
            }.disableShutdownHook()
            .build()
            .openTelemetrySdk
    OpenTelemetryAppender.install(openTelemetry)
    install(MicrometerMetrics) {
        registry = OpenTelemetryMeterRegistry.builder(openTelemetry).build()
    }
    monitor.subscribe(ApplicationStopped) { openTelemetry.close() }
}
