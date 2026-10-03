package me.aquitano.health.infrastructure.repositories

import me.aquitano.health.domain.ScalarMetricRegistry

internal fun sameScalarIdentity(left: String, right: String): String =
    """($right.record_type <> 'scalar' OR (
        $left.normalized_record_json->>'metricType' = $right.normalized_record_json->>'metricType'
        AND ${scalarContext(left)} = ${scalarContext(right)}
        AND COALESCE($left.normalized_record_json->>'segment', '') = COALESCE($right.normalized_record_json->>'segment', '')
    ))"""

// Defaults mirror mapScalarSample; absent context means "unknown" for context-bearing metrics.
private val contextualMetricTypes = ScalarMetricRegistry.descriptors
    .filter { it.allowedContexts != null }
    .joinToString(",") { "'${it.metricType.replace("'", "''")}'" }

internal fun scalarContext(alias: String): String =
    "COALESCE($alias.normalized_record_json->>'context', " +
        "CASE WHEN $alias.normalized_record_json->>'metricType' IN ($contextualMetricTypes) " +
        "THEN 'unknown' ELSE '' END)"
