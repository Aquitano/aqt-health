# Refactor plan — status and remaining work

Status as of 2026-07-07. Tracks the five-phase drift/consolidation plan; one PR per branch.

## Landed (open PRs, all CI-green unless noted)

| Item | PR | Branch |
|---|---|---|
| 1.1 Legacy scalar mappers delegate to registry | [#57](https://github.com/Aquitano/aqt-health/pull/57) | `refactor/legacy-scalar-mappers-registry` |
| 1.2 Ingestion body schema inferred from DTO | [#59](https://github.com/Aquitano/aqt-health/pull/59) | `refactor/openapi-ingestion-batch-schema` (CI fix in progress) |
| 1.3 Query-parameter descriptors | [#60](https://github.com/Aquitano/aqt-health/pull/60) | `refactor/query-param-descriptors` |
| 1.4 Dead code deletion | [#55](https://github.com/Aquitano/aqt-health/pull/55) | `chore/delete-dead-code` |
| 1.5 Status-enum migration | [#56](https://github.com/Aquitano/aqt-health/pull/56) | `refactor/status-enum-domain` |
| 2.1 Affected-dates mapping on DerivedRebuildModule | [#65](https://github.com/Aquitano/aqt-health/pull/65) | `refactor/derived-rebuild-affected-dates` |
| 2.2 Idempotency keys for job-creating POSTs | [#66](https://github.com/Aquitano/aqt-health/pull/66) | `feat/idempotency-keys` |
| 3.1–3.4 Frontend hygiene | [#58](https://github.com/Aquitano/aqt-health/pull/58) | `chore/frontend-hygiene` |
| 3.5 Generated types replace hand-written | [#62](https://github.com/Aquitano/aqt-health/pull/62) | `refactor/frontend-generated-types`, stacked on #59 |
| 4.1 Generic DataTable | [#61](https://github.com/Aquitano/aqt-health/pull/61) | `refactor/generic-data-table` |
| 4.2 Chart shared helpers | [#63](https://github.com/Aquitano/aqt-health/pull/63) | `refactor/chart-shared-helpers` |
| 4.6 Read-repository layering + Konsist rules | [#64](https://github.com/Aquitano/aqt-health/pull/64) | `refactor/metric-repository-layering` |

## Plan corrections discovered during execution

- **5.1 and 5.2 are obsolete**: `V15__canonical_views_for_structural_metrics.sql` already replaced the three materialized canonical daily-summary tables with `DISTINCT ON` + `provider_ranks` views (the exact spike outcome). `DerivedKind` is already down to the two genuinely heavy materialized computations.
- `src/main/resources/openapi/aqt-health.yaml` (named in 1.4) never existed; nothing to delete.
- 1.1 caught real drift: Withings emitted `basal_metabolic_rate` in `kcal/day` vs the registry's `kcal` (fixed in #57).
- Verification note: `./gradlew test` runs only unit tests; the Testcontainers suites run under `./gradlew integrationTest`. CI's `./gradlew check` runs both.

## Suggested merge order

1. #55, #56, #57, #58, #61, #63, #64, #66 — independent, merge in any order; #55/#56/#60/#64/#65 have trivial import-level conflicts pairwise on `ReadRoutes.kt`, `AdminService.kt`, `AppModules.kt`, `Application.kt`, so rebase-after-each-merge.
2. #59, then #62 (stacked on #59; retargets to main automatically).
3. #60, #65 after the above (same central files).

After merging: run `generateOpenApi` + frontend codegen on main once to confirm zero contract drift.

## Remaining work

### 4.3 + 4.4 — DTO naming standardization + uniform list envelope (one coordinated PR, M)
Blocked on: all open backend PRs merged (renames touch nearly every DTO file).
- Pick `XxxRequest`/`XxxResponse`; drop `Dto` and the `ResponseDto` double-suffix (`ProviderDescriptorResponseDto` → `ProviderDescriptorResponse`, `StepIntervalDto` → `StepIntervalRequest`, …). Kotlin renames change OpenAPI component names only, not JSON payloads.
- Note from 1.2: ingestion record components are now named from `@SerialName` values (`step_interval`, …), so those are unaffected by class renames.
- 4.4 in the same PR (the one deliberate wire break, per the ADR's batch-wire-breaks rule): give `GET /metrics`, `GET /providers`, `GET /providers/status` the `{items, meta: {nextCursor: null}}` envelope (`providers` key becomes `items`).
- Regenerate frontend types and update imports in the same PR. Verify: OpenAPI diff shows only component renames + the three envelope changes; frontend typechecks.

### 4.5 — DI cleanup (M)
Blocked on: #55, #64, #65, #66 merged (all touch `AppModules.kt`/`Application.kt`).
- Regroup `AppModules.kt` by feature (ingestion, metrics-read, providers, replay/admin); `singleOf(::Type)` instead of by-name argument lists; delete the `ApplicationServices` bag + `buildApplicationServices()` in favor of route-module-level `inject()` (#64 already deleted its unused `database` field).
- Register `ProviderSyncPipeline` and its ports as beans (currently default-constructed, unswappable in tests); fix the default-arg construction at `HealthDayQueryService.kt:147`.
- Follow-up from 1.5 to fold in: `ProviderSyncPipeline.kt` still builds run status from raw strings via `ProviderSyncRunPort.finish(status: String)` — convert to `SyncStatus`.

### 4.7 — API error vocabulary + validator consolidation (S)
Blocked on: #60 merged (shares `OpenApiParameters.kt`/`ReadRoutes.kt`).
- Keep the bespoke error envelope; enumerate all `code` values as an OpenAPI enum and document per-endpoint conflict/upstream codes.
- Collapse the three copy-pasted metric-type validators (`MetricQueryValidation.kt:10-50`) into `validateMetricType(type, family)`; add shared `requiredPathParam()`.

### 5.3 — Generic daily-summary registry (L)
Blocked on: #64 merged (repository moves), ideally 4.3 too (avoids double renames).
- Collapse per-metric daily-summary plumbing (activity, step-daily, sleep-summary) into one generic dated-summary table keyed by `kind` + descriptor registry, the way scalars collapsed. Sleep sessions/stages stay bespoke.
- Target: a new structural daily metric = descriptor + migration, not 15–20 files.
- Note: 5.2's original premise is gone (tables are already views), so scope this as consolidating the read-side repos/services/routes over the existing views.

### 5.4 — Per-day heart-rate series endpoint (M)
Independent; can start once #60/#65 merge (touches `ReadRoutes.kt`).
- Backend: `GET /api/v2/metrics/heart_rate/daily?from=&to=` replacing the frontend's one-call-per-day loop (`aqtHealthApi.ts:424-452`, up to 92 requests).
- Frontend: consume the new endpoint; Suspense-split the health-data debug tables so they don't block first paint. Conflicts with #58/#62 on `health-data/page.tsx` — land after those merge.

### Small follow-ups (batch into any nearby PR)
- `HealthDataVisualizations` `bodyMetricConfig` hardcodes hex hues duplicating the `--hue-*` tokens (from 4.2).
- Per-page visual verification of the DataTable consolidation (#61) and chart token changes (#63) — DOM was preserved by construction, but eyeball each page once.
