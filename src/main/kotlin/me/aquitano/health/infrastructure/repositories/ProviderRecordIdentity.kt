package me.aquitano.health.infrastructure.repositories

import me.aquitano.health.domain.ScalarMetricRegistry

internal fun sameRecordIdentity(
    left: String,
    right: String,
): String =
    """(CASE $right.record_type
        WHEN 'scalar' THEN $left.normalized_record_json->>'metricType' = $right.normalized_record_json->>'metricType'
            AND ${scalarContext(left)} = ${scalarContext(right)}
            AND COALESCE($left.normalized_record_json->>'segment', '') = COALESCE($right.normalized_record_json->>'segment', '')
        WHEN 'daily_metric' THEN $left.normalized_record_json->>'metricType' = $right.normalized_record_json->>'metricType'
        ELSE TRUE
    END)"""

// Defaults mirror mapScalarSample; absent context means "unknown" for context-bearing metrics.
private val contextualMetricTypes =
    ScalarMetricRegistry.descriptors
        .filter { it.allowedContexts != null }
        .joinToString(",") { "'${it.metricType.replace("'", "''")}'" }

internal fun scalarContext(alias: String): String =
    "COALESCE($alias.normalized_record_json->>'context', " +
        "CASE WHEN $alias.normalized_record_json->>'metricType' IN ($contextualMetricTypes) " +
        "THEN 'unknown' ELSE '' END)"
