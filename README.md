# aqt-health

Personal, single-user health data hub built with Ktor, Kotlin, PostgreSQL, Exposed, and Flyway.

This is the authoritative Health repository. The former `Aquitano/aqt/apps/health` copy is retired. Open Health issues and pull requests here.

The service accepts normalized health batches from trusted scripts or provider adapters, stores the original source JSON for audit/reprocessing, writes structured metric tables, and exposes read endpoints for local tools.

## Stack

- Kotlin 2.4
- Ktor 3.6
- PostgreSQL via JDBC and HikariCP
- Exposed DAO for support entities only
- Exposed DSL for ingestion and metric reads/writes
- Flyway versioned migrations
- API-key authentication with `Authorization: Bearer <api-key>`

- JDK 25

## Run Locally

Start PostgreSQL:

```bash
docker compose up -d postgres
```

The compose file uses `AQT_HEALTH_DB_USER`, `AQT_HEALTH_DB_PASSWORD`, and `POSTGRES_DB` from the environment when set. Postgres is not published to the host; the app reaches it over the compose network at `postgres:5432`. To run the app outside compose against that database, publish the port with a local override: `cp docker-compose.override.example.yml docker-compose.override.yml`, then `docker compose up -d postgres`. The override file is ignored by git.

Bash:

```bash
export AQT_HEALTH_BOOTSTRAP_API_KEY="local-dev-key"
export AQT_HEALTH_JDBC_URL="jdbc:postgresql://localhost:5432/aqt_health"
export AQT_HEALTH_DB_USER="aqt_health"
export AQT_HEALTH_DB_PASSWORD="aqt_health"
./gradlew run
```

PowerShell:

```powershell
$env:AQT_HEALTH_BOOTSTRAP_API_KEY = "local-dev-key"
$env:AQT_HEALTH_JDBC_URL = "jdbc:postgresql://localhost:5432/aqt_health"
$env:AQT_HEALTH_DB_USER = "aqt_health"
$env:AQT_HEALTH_DB_PASSWORD = "aqt_health"
.\gradlew.bat run
```

The default database URL is `jdbc:postgresql://localhost:5432/aqt_health`. Flyway migrations run at startup.

Useful tasks:

```powershell
.\gradlew.bat test
.\gradlew.bat build
.\gradlew.bat run
```

## OpenAPI

The OpenAPI spec is generated from routing at runtime and served by Ktor.

After starting the app, open:

- `http://localhost:8080/openapi` (raw OpenAPI document)
- `http://localhost:8080/swagger` (Swagger UI)

## Configuration

Runtime config is read from `src/main/resources/application.yaml`.

Environment variables:

- `PORT`: HTTP port, default `8080`
- `AQT_HEALTH_ENV`: runtime mode, default `local`; set to `production` to enable fail-fast config validation
- `AQT_HEALTH_JDBC_URL`: PostgreSQL JDBC URL, default `jdbc:postgresql://localhost:5432/aqt_health`
- `AQT_HEALTH_DB_USER`: PostgreSQL username, default `aqt_health`
- `AQT_HEALTH_DB_PASSWORD`: PostgreSQL password, default `aqt_health`
- `AQT_HEALTH_DB_MAX_POOL_SIZE`: maximum Hikari connection pool size, default `10`
- `AQT_HEALTH_DB_IDLE_IN_TRANSACTION_TIMEOUT`: how long PostgreSQL keeps an app session that sits idle inside a transaction before ending it and releasing its locks, as a duration such as `30s` or `2m`, default `1m`
- `AQT_HEALTH_INGESTION_MAX_BODY_BYTES`: maximum request body size for `POST /api/v2/ingestion/batches`, default `33554432` (32 MB); larger requests get `413`
- `AQT_HEALTH_BOOTSTRAP_CLIENT_NAME`: initial API client name, default `local-admin`
- `AQT_HEALTH_BOOTSTRAP_API_KEY`: optional plaintext bootstrap key
- `AQT_HEALTH_GOOGLE_CLIENT_ID`: Google OAuth web client ID for Google Health
- `AQT_HEALTH_GOOGLE_CLIENT_SECRET`: Google OAuth web client secret
- `AQT_HEALTH_GOOGLE_REDIRECT_URI`: OAuth callback URL, default `http://localhost:8080/api/v2/providers/google-health/oauth/callback`
- `AQT_HEALTH_GOOGLE_TOKEN_ENCRYPTION_KEY`: required before connecting Google Health; used to encrypt stored OAuth tokens
- `AQT_HEALTH_GOOGLE_API_BASE_URL`: Google Health API base URL, default `https://health.googleapis.com`
- `AQT_HEALTH_GOOGLE_OAUTH_TOKEN_URL`: Google OAuth token URL, default `https://oauth2.googleapis.com/token`
- `AQT_HEALTH_GOOGLE_OAUTH_AUTH_URL`: Google OAuth authorization URL, default `https://accounts.google.com/o/oauth2/v2/auth`
- `AQT_HEALTH_WITHINGS_CLIENT_ID`: Withings OAuth client ID
- `AQT_HEALTH_WITHINGS_CLIENT_SECRET`: Withings OAuth client secret
- `AQT_HEALTH_WITHINGS_REDIRECT_URI`: OAuth callback URL, default `http://localhost:8080/api/v2/providers/withings/oauth/callback`
- `AQT_HEALTH_WITHINGS_TOKEN_ENCRYPTION_KEY`: required before connecting Withings; used to encrypt stored OAuth tokens
- `AQT_HEALTH_WITHINGS_API_BASE_URL`: Withings API base URL, default `https://wbsapi.withings.net`
- `AQT_HEALTH_WITHINGS_OAUTH_TOKEN_URL`: Withings OAuth token URL, default `https://wbsapi.withings.net/v2/oauth2`
- `AQT_HEALTH_WITHINGS_OAUTH_AUTH_URL`: Withings OAuth authorization URL, default `https://account.withings.com/oauth2_user/authorize2`
- `AQT_HEALTH_LOG_FORMAT`: stdout log format, `text` by default or `json` for production JSON lines
- `OTEL_EXPORTER_OTLP_ENDPOINT`: optional OTLP/HTTP endpoint; when set, metrics and logs are exported there (see [Telemetry](#telemetry))
- `OTEL_EXPORTER_OTLP_HEADERS`, `OTEL_RESOURCE_ATTRIBUTES` and the other standard `OTEL_*` variables: exporter headers, resource attributes and overrides

If `AQT_HEALTH_BOOTSTRAP_API_KEY` is set, the app hashes it with SHA-256 and stores only `sha256:<hex>` in `api_clients`. If it is blank, startup still succeeds, but protected endpoints require a client row to exist in PostgreSQL.

PostgreSQL migrations require permission to run `CREATE EXTENSION IF NOT EXISTS btree_gist`. Managed deployments should enable `btree_gist` ahead of application startup if the application database user cannot create extensions.

### Production validation

Set `AQT_HEALTH_ENV=production` for production and production-like deployments. In production mode the app fails startup before connecting to PostgreSQL when critical config is blank, placeholder, localhost-only, or still using local defaults.

Production requires:

- A non-placeholder `AQT_HEALTH_BOOTSTRAP_API_KEY` and non-local `AQT_HEALTH_BOOTSTRAP_CLIENT_NAME`.
- Non-default database credentials; the compose `aqt_health` / `aqt_health` pair is rejected.
- Google Health and Withings OAuth client IDs, client secrets, public HTTPS redirect URIs, and token encryption keys.
- Provider token encryption keys of at least 32 bytes.
- Public HTTPS provider API, OAuth token, and OAuth authorization URLs.

For local secrets, copy `.env.example` to `.env` and put real values only in `.env`. The `.env` file is ignored by git.

PowerShell:

```powershell
Copy-Item .env.example .env
notepad .env

Get-Content .env | Where-Object { $_ -and $_ -notmatch '^\s*#' } | ForEach-Object {
  $name, $value = $_ -split '=', 2
  [Environment]::SetEnvironmentVariable($name.Trim(), $value.Trim(), 'Process')
}

.\gradlew.bat run
```

Never commit `.env`, real API keys, database passwords, production URLs, or OpenObserve credentials. Commit only `.env.example` with placeholders.

### Infisical environments

`.infisical.json` retains the Health project used by the former monorepo. Backend variables live at `/`.

After authenticating with the Infisical CLI, run these commands from the repository root with a POSIX shell such as Git Bash:

```sh
sh scripts/secrets.sh pull backend dev
sh scripts/secrets.sh diff backend dev
sh scripts/secrets.sh push backend dev
```

Replace `dev` with the required environment. Pulls replace the local `.env` atomically and refuse empty exports. Pushes require confirmation. The diff command prints secret values, so keep its output out of shared logs. Deployment uses this repository's Compose files; the script does not contact the VPS.

## Telemetry

Logs always go to stdout, as readable text by default or JSON lines with `AQT_HEALTH_LOG_FORMAT=json`.

Setting `OTEL_EXPORTER_OTLP_ENDPOINT` also exports HTTP server metrics and every log event over OTLP/HTTP through the OpenTelemetry SDK. The SDK reads the standard `OTEL_*` environment variables; the app only defaults the service name to `aqt-health`, the protocol to `http/protobuf` and traces to off. For OpenObserve:

```sh
OTEL_EXPORTER_OTLP_ENDPOINT=https://openobserve.example.com/api/<organization>
OTEL_EXPORTER_OTLP_HEADERS=Authorization=Basic%20<base64 user:password>
OTEL_RESOURCE_ATTRIBUTES=deployment.environment.name=production
```

The SDK URL-decodes header values, so a `+` in the base64 credential must be written as `%2B`. Keep the `Authorization` value in deployment secrets, and rotate any OpenObserve credential that has been pasted into chat, issue trackers, shell history, or logs.

## Deployment

`docker-compose.yml` builds the API from source and is the local setup. Servers use `docker-compose.deploy.yml`, which runs the image CI publishes to GHCR on every push to `main`: `ghcr.io/aquitano/aqt-health-api`. The package is public, so the host needs no registry login.

Copy `docker-compose.deploy.yml` and a filled-in `.env` into a directory outside any checkout, then:

```bash
docker compose -f docker-compose.deploy.yml up -d
docker compose -f docker-compose.deploy.yml ps
```

How it differs from the local compose file:

- `image:` instead of `build:`, tagged by `AQT_HEALTH_API_TAG` (default `latest`). Pin it to `sha-<short-commit>`, the other tag CI pushes, so an update is a tag edit plus `up -d` and a rollback is editing it back.
- `AQT_HEALTH_ENV` defaults to `production`, so [production validation](#production-validation) applies at startup, and `AQT_HEALTH_LOG_FORMAT` defaults to `json`.
- The API port binds to `127.0.0.1`, so nothing is reachable from off the host. Put a reverse proxy or Tailscale in front. PostgreSQL publishes no host port at all.
- `AQT_HEALTH_DB_PASSWORD` has no default; compose refuses to start without it.

The image carries its own `HEALTHCHECK`, so the deploy file does not repeat it.

## Health Check

Unauthenticated:

```bash
curl http://localhost:8080/api/v2/admin/health
```

## Ingest A Batch

```bash
curl -X POST http://localhost:8080/api/v2/ingestion/batches \
  -H "Authorization: Bearer local-dev-key" \
  -H "Content-Type: application/json" \
  -d '{
    "provider": "health_connect",
    "providerInstanceId": "pixel-8-health-connect",
    "batchExternalId": "demo-2026-04-19",
    "ingestedAt": "2026-04-19T10:00:00Z",
    "sourcePayload": {
      "exportId": "demo-2026-04-19"
    },
    "records": [
      {
        "type": "step_interval",
        "providerRecordId": "steps-1",
        "startAt": "2026-04-19T08:00:00Z",
        "endAt": "2026-04-19T09:00:00Z",
        "steps": 1200
      },
      {
        "type": "scalar",
        "providerRecordId": "hr-1",
        "measuredAt": "2026-04-19T08:30:00Z",
        "metricType": "heart_rate",
        "value": 62,
        "context": "resting"
      }
    ]
  }'
```

Supported record types:

- `step_interval`
- `sleep_session`
- `sleep_summary`
- `activity_summary`
- `blood_pressure`
- `scalar`
- `daily_metric`

`scalar` carries every point-in-time metric (heart rate, HRV, respiratory rate, body composition, cardiovascular) as `metricType` plus `value`. The metric types and their units come from `ScalarMetricRegistry`; `unit` is optional on the wire and must match the registry when given.

`batchExternalId` is idempotent per source instance. Provider record IDs are also used to skip duplicate metric rows where available.

## Canonical Metrics

The database preserves raw provider rows for auditability, but read endpoints return canonical cross-provider results. No provider rows are deleted or rewritten.

Provider filters apply to the canonical result: `provider=withings` returns the canonical rows that came from Withings, not everything Withings recorded. Where another provider won an overlap, the Withings rows stay hidden, so a filtered daily step total is only Withings' share of the canonical total.

Default source priorities are code-based in `MetricCatalogBootstrap.providerRanks`:

- Steps and activity: Google Health or Health Connect first, then Withings.
- Body measurements: Withings first, then Google Health or Health Connect.
- Sleep sessions and sleep summaries: Withings first, then Google Health or Health Connect.
- Heart rate: Google Health, then Health Connect, then Withings; sleep-context samples use the sleep ranking, so Withings wins there.
- Respiratory rate and HRV: Withings first, then Google Health or Health Connect.
- Daily metrics: Google Health first, then Withings, then Health Connect.

Overlap handling:

- Steps: overlapping intervals from different sources keep the higher-priority interval; non-overlapping intervals are preserved.
- Sleep sessions: a session is hidden when a session from a better-ranked provider overlaps it in time; between equal ranks the lower source instance id wins. Overlapping sessions from one device all survive, and so do naps from another device that the winner did not record.
- Sleep summaries: among summaries that overlap in time, the best-ranked provider's newest one is kept.
- Scalar samples (heart rate, HRV, respiratory rate, body measurements, cardiovascular): within each 30-second bin per metric type, context and segment, only the best-ranked provider's samples are kept.
- Body measurements: a lower-ranked provider's sample is also hidden when a better-ranked provider recorded the same metric and value (to 0.01) a whole number of quarter hours away, up to 14 hours. Apps that write a scale's local time as UTC into Google Health produce these shifted copies.

Inspect raw provider-specific rows directly in the database when auditing ingestion output.

## Provider Discovery

All provider discovery endpoints require `Authorization: Bearer <api-key>`.

List registered providers and their sync capabilities:

```bash
curl "http://localhost:8080/api/v2/providers" \
  -H "Authorization: Bearer local-dev-key"
```

Get metadata for one provider:

```bash
curl "http://localhost:8080/api/v2/providers/google-health" \
  -H "Authorization: Bearer local-dev-key"
```

Discovery responses return canonical route provider codes. Google Health is returned as `google-health`; `google_health` is accepted as an alias for compatibility with internal source naming and older clients. Each provider descriptor includes its display name, supported and default `dataTypes`, max sync range, and page-size support. Every provider connects through the OAuth routes below.

## Provider Account Lifecycle

Provider OAuth accounts have first-class lifecycle state. Status responses expose account state, token status, last successful sync, last token refresh result, and the latest authentication error without returning encrypted or decrypted token values.

List provider readiness:

```bash
curl "http://localhost:8080/api/v2/providers/status" \
  -H "Authorization: Bearer local-dev-key"
```

List accounts for one provider:

```bash
curl "http://localhost:8080/api/v2/providers/google-health/accounts" \
  -H "Authorization: Bearer local-dev-key"
```

Connect starts the normal provider OAuth flow:

```bash
curl "http://localhost:8080/api/v2/providers/google-health/oauth/start" \
  -H "Authorization: Bearer local-dev-key"
```

Reconnect/re-consent validates an existing local account and starts the same OAuth flow again:

```bash
curl -X POST "http://localhost:8080/api/v2/providers/google-health/accounts/google-health-me/reconnect" \
  -H "Authorization: Bearer local-dev-key"
```

Disconnect is local-only. It clears locally stored OAuth token ciphertext, marks the account `disconnected`, preserves account metadata and sync history, and does not revoke tokens at Google or Withings:

```bash
curl -X POST "http://localhost:8080/api/v2/providers/google-health/accounts/google-health-me/disconnect" \
  -H "Authorization: Bearer local-dev-key"
```

If a refresh token is expired, invalid, or revoked, sync marks the account `needs_reauth`. Manual or future scheduled syncs skip accounts in `needs_reauth` or `disconnected` until reconnect completes.

## Google Health Provider

The Google Health provider is a server-owned OAuth integration. It reads Google Health data, normalizes it into the same ingestion batch contract shown above, stores the original Google response pages in `sourcePayload`, and writes the existing metric tables through the normal ingestion service.

Sync uses Google's generated Health client library (`com.google.cloud:google-cloud-health`, API v4); the remaining Ktor Google client code is OAuth-only. Every data type except `activity` is read with `reconcile` over all of the account's sources (`dataSourceFamily=all-sources`), not `list`; `activity` is read from Google's daily roll-ups over the same sources:

- Google merges overlapping sources into one stream per data type. A watch and a phone counting the same walk count once, and daily step totals match Google's own daily roll-up. `list` returned every source's copy, which over-counted steps on days with phone or Health Connect data.
- Wearable readings from periods the device was not worn are left out.
- Narrower source families drop real data: `google-sources` and `google-wearables` return no weights for a Withings scale that writes into Google Health through Health Connect.

Points Google names (sleep, weight, body fat, respiratory rate) keep that name as their record id; others are keyed by data type and time, so a re-read of the same window updates instead of duplicating. When a window holds two different readings for one time key (two devices at one instant), both are stored: one keeps the key and the other gains a hash of its content. Identical copies are stored once. Scalar records stored through `list` carried a hash of the whole point in their id; V42 removed it, except for instants where some batch held two points at the same second. Those keep their old ids in every batch, so re-reading such an instant stores it again under the new ids.

Google Cloud setup:

1. Create or choose a Google Cloud project.
2. Configure an OAuth consent screen.
3. Create an OAuth web client.
4. Add this authorized redirect URI: `http://localhost:8080/api/v2/providers/google-health/oauth/callback`
5. Put the client ID, client secret, redirect URI, and token encryption key in `.env`.

Required Google Health OAuth scopes:

- `https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly`
- `https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly`
- `https://www.googleapis.com/auth/googlehealth.sleep.readonly`

Start the OAuth flow:

```bash
curl "http://localhost:8080/api/v2/providers/google-health/oauth/start" \
  -H "Authorization: Bearer local-dev-key"
```

Open the returned `authorizationUrl` in a browser. Google redirects back to `/api/v2/providers/google-health/oauth/callback`, which stores encrypted tokens for future syncs.

Sync Google Health data with a background job:

```bash
curl -X POST http://localhost:8080/api/v2/providers/google-health/sync-jobs \
  -H "Authorization: Bearer local-dev-key" \
  -H "Content-Type: application/json" \
  -d '{
    "from": "2026-04-01T00:00:00Z",
    "to": "2026-04-20T00:00:00Z",
    "dataTypes": ["steps", "sleep", "heart-rate", "daily-resting-heart-rate", "activity"],
    "pageSize": 10000
  }'
```

If `dataTypes` is omitted, the sync reads every type:

| Data type | Stored as |
|---|---|
| `steps` | step intervals |
| `sleep` | sleep sessions with stages, plus a sleep summary for the main sleep (Google's `mainSleep`; when a window has none, every session that is not a nap) |
| `heart-rate`, `heart-rate-variability`, `respiratory-rate-sleep-summary`, `weight`, `body-fat` | scalar samples |
| `daily-resting-heart-rate` | daily metric `resting_heart_rate` |
| `daily-heart-rate-variability` | daily metrics `nightly_hrv_rmssd`, `deep_sleep_hrv_rmssd`, `non_rem_heart_rate` |
| `daily-oxygen-saturation` | daily metrics `oxygen_saturation`, `oxygen_saturation_lower_bound` (lower bound of the confidence interval) |
| `daily-respiratory-rate` | daily metric `nightly_respiratory_rate` |
| `daily-sleep-temperature-derivations` | daily metrics `nightly_skin_temperature`, `skin_temperature_baseline` |
| `activity` | one activity summary per date from Google's daily roll-ups: distance, total calories, light/moderate/vigorous minutes (`activeMinutes` is moderate plus vigorous), and heart-rate average, minimum and maximum |

Daily values are dated by Google's calendar date; overnight metrics carry the date of the morning the night ended. A day nothing was worn yields no activity summary rather than zeros. Activity summaries are ranked as whole rows with Google first, so on a date Google summarizes, the Withings summary and its Withings-only fields (`activeEnergyKcal`, `elevationMeters`) are not returned. As with every read, `provider=withings` filters the canonical result, so it returns no summary for that date. If both `from` and `to` are omitted, the sync defaults to the last seven days. Long explicit ranges are accepted for historical backfill and are split into UTC-day-aligned one-day windows before fetching. Completed days are skipped on repeated syncs over the same range; only the current, still-open day is re-fetched.

Poll `/api/v2/providers/google-health/sync-jobs/{jobId}` for progress and the final summary. The backend owns the provider-safe sequential work after the job is accepted, so a client that disconnects does not stop the sync job.

Sync jobs also survive backend restarts: unfinished jobs are requeued and rerun on startup, which is safe because ingestion dedupes by provider record ID. The job status reports how often this happened in `restartCount`; after three restarts a job is failed instead of resumed to stop crash loops.

Overlapping Google Health step intervals for the same account are still skipped as duplicates during ingestion. Steps stored before the switch to `reconcile` keep their old, inflated totals: raw rows are never deleted, so only a backfill into a fresh database repairs them. Google serves the full history to `reconcile`.

## Withings Provider

The Withings provider is a server-owned OAuth integration. It starts the Withings authorization flow, exchanges the callback authorization code immediately, stores encrypted access and refresh tokens in the shared provider OAuth table, reads Withings data, preserves the raw Withings response pages in ingestion `sourcePayload`, and writes supported normalized metrics through the normal ingestion service.

Withings developer setup:

1. Create or choose a Withings developer application.
2. Add this callback URL: `http://localhost:8080/api/v2/providers/withings/oauth/callback`
3. Put the client ID, client secret, redirect URI, and token encryption key in `.env`.

Required Withings OAuth scopes:

- `user.info`
- `user.metrics`
- `user.activity`

Start the OAuth flow:

```bash
curl "http://localhost:8080/api/v2/providers/withings/oauth/start" \
  -H "Authorization: Bearer local-dev-key"
```

Open the returned `authorizationUrl` in a browser. Withings redirects back to `/api/v2/providers/withings/oauth/callback`, which stores encrypted tokens for future syncs. Withings authorization codes are valid for only 30 seconds, so the callback must reach this backend immediately.

Sync Withings data with a background job:

```bash
curl -X POST http://localhost:8080/api/v2/providers/withings/sync-jobs \
  -H "Authorization: Bearer local-dev-key" \
  -H "Content-Type: application/json" \
  -d '{
    "from": "2026-04-01T00:00:00Z",
    "to": "2026-04-20T00:00:00Z",
    "dataTypes": ["activity", "measures", "sleep-summary", "sleep"]
  }'
```

If `dataTypes` is omitted, the sync reads `activity`, `measures`, `sleep-summary`, and `sleep`. Long explicit ranges are accepted for historical backfill and are fetched in sequential UTC-day-aligned one-day windows per data type, so completed days are skipped on repeated syncs. Withings fields from the listed Measure, Activity, Sleep, and Sleep Summary APIs are preserved in the ingestion source payload. The current normalized metric tables store steps, supported body measurements, heart-rate samples, and sleep sessions; unsupported Withings metrics such as blood pressure, SpO2, temperature, ECG intervals, BMR, metabolic age, bone mass, vascular age, segmental body composition, and conductance values remain available in source payloads until matching metric tables exist.

Poll `/api/v2/providers/withings/sync-jobs/{jobId}` for progress; the backend continues processing provider-safe windows even if the client disconnects.

## Read Data

All read endpoints require `Authorization: Bearer <api-key>`.

Clients that need runtime discovery can call the scalar metric catalog:

```bash
curl "http://localhost:8080/api/v2/metrics" \
  -H "Authorization: Bearer local-dev-key"
```

The catalog lists scalar `metricType` values, families, units, segment support, and allowed contexts. Structural metrics such as steps, sleep, activity summaries, and blood pressure have dedicated endpoints. OpenAPI remains the formal schema contract for request and response shapes.

```bash
curl "http://localhost:8080/api/v2/steps?from=2026-04-19T00:00:00Z&to=2026-04-20T00:00:00Z" \
  -H "Authorization: Bearer local-dev-key"

curl "http://localhost:8080/api/v2/steps?latest=true&includeSource=true" \
  -H "Authorization: Bearer local-dev-key"

curl "http://localhost:8080/api/v2/steps/daily?fromDate=2026-04-19&toDate=2026-04-19" \
  -H "Authorization: Bearer local-dev-key"

curl "http://localhost:8080/api/v2/sleep/sessions?includeSource=true" \
  -H "Authorization: Bearer local-dev-key"

curl "http://localhost:8080/api/v2/sleep/nights?date=2026-04-20&timezone=Europe/Berlin&includeSource=true" \
  -H "Authorization: Bearer local-dev-key"

curl "http://localhost:8080/api/v2/metrics/weight" \
  -H "Authorization: Bearer local-dev-key"

curl "http://localhost:8080/api/v2/metrics/weight?latest=true&includeSource=true" \
  -H "Authorization: Bearer local-dev-key"

curl "http://localhost:8080/api/v2/metrics/samples?metricTypes=weight,body_fat" \
  -H "Authorization: Bearer local-dev-key"

curl "http://localhost:8080/api/v2/metrics/heart_rate" \
  -H "Authorization: Bearer local-dev-key"

curl "http://localhost:8080/api/v2/metrics/heart_rate?order=desc&limit=100" \
  -H "Authorization: Bearer local-dev-key"

curl "http://localhost:8080/api/v2/metrics/heart_rate/summary?from=2026-04-19T00:00:00Z&to=2026-04-20T00:00:00Z" \
  -H "Authorization: Bearer local-dev-key"

curl "http://localhost:8080/api/v2/metrics/heart_rate/daily?fromDate=2026-04-13&toDate=2026-04-19&timezone=Europe/Berlin" \
  -H "Authorization: Bearer local-dev-key"

curl "http://localhost:8080/api/v2/daily-metrics?metricTypes=resting_heart_rate,nightly_hrv_rmssd&fromDate=2026-04-13&toDate=2026-04-19" \
  -H "Authorization: Bearer local-dev-key"
```

`/api/v2/daily-metrics` returns values a provider computes once per calendar day (`resting_heart_rate`, `non_rem_heart_rate`, `nightly_hrv_rmssd`, `deep_sleep_hrv_rmssd`, `oxygen_saturation`, `oxygen_saturation_lower_bound`, `nightly_respiratory_rate`, `nightly_skin_temperature`, `skin_temperature_baseline`), each with its unit. `metricTypes` defaults to all of them. Ingest them as `daily_metric` records with `date`, `metricType` and `value`; per date and metric the best-ranked provider wins (Google first).

Admin ingestion views:

```bash
curl "http://localhost:8080/api/v2/admin/ingestion/batches" \
  -H "Authorization: Bearer local-dev-key"

curl "http://localhost:8080/api/v2/admin/ingestion/failures" \
  -H "Authorization: Bearer local-dev-key"
```

Common read filters:

- `from`: ISO-8601 instant, inclusive, on reads of timestamped records
- `to`: ISO-8601 instant, exclusive, on reads of timestamped records
- `date`, `fromDate`, `toDate`: inclusive local dates on day-keyed reads (`/steps/daily`, `/metrics/{metricType}/daily`, `/sleep/nights`, `/activity/summaries`, `/daily-metrics`); where an endpoint accepts `timezone`, it sets the day boundaries
- `provider`: source code, for example `health_connect`
- `providerInstanceId`: concrete provider/device/account instance
- `includeSource`: `true` or `false`
- `limit`: default `500`, max `5000`
- `order`: `asc` or `desc`, default `asc`

Metric list responses include an `items` array plus a `meta` object:

```json
{
  "items": [],
  "meta": {
    "count": 0,
    "limit": 500,
    "sort": "measuredAt",
    "order": "asc"
  }
}
```

Response field names follow these rules, so a quantity has one name everywhere:

- Aggregates of one metric (`/metrics/{metricType}/summary`, `/metrics/{metricType}/daily` and the `heartRate` block of `/health/day`) use `count`, `minValue`, `maxValue` and `avgValue`, with the unit in `unit`.
- Fields of fixed-shape records (activity and sleep summaries, blood pressure) name the statistic, the quantity and the unit: `avgHeartRateBpm`, `minRespiratoryRateBreathsPerMinute`, `totalSleepSeconds`, `systolicMmhg`. Statistics are always `min`, `max` and `avg`, and a step total is always `steps`.
- Trend blocks of `/dashboard/trends` sit under the quantity's key (`heartRate`, `sleep`), so their fields name the period, the statistic and the unit: `currentAvgBpm`, `previousAvgSeconds`.

Ingestion records keep their own field names (a sleep summary is ingested with `hrAverageBpm` and read back as `avgHeartRateBpm`), because the ingestion log stores records in that format.

A query parameter the endpoint does not support returns `400 validation_failed` with code `unknown_parameter`, so a misspelled filter cannot silently widen the result.

Cursor pagination is exposed through the `cursor` query parameter. When `meta.nextCursor` is present, pass it back with the same `order` value to fetch the next page. `meta.sort` names the field each endpoint orders by.

`latest=true` is supported on timestamp-based list endpoints, scalar metric reads, activity summaries, sleep summaries, and blood pressure. It cannot be combined with `limit`, `order`, or `cursor`; unsupported combinations return `400 validation_failed` with field-level details. Daily step summaries, sleep nights, daily metrics and the scalar `/summary` and `/daily` aggregates reject `latest=true`; use `date` or descending `order` where appropriate.

Sleep reads have two modes:

- `/api/v2/sleep/sessions` is the instant-based read. Its `from` and `to` filters apply to session `startAt`.
- `/api/v2/sleep/nights` is the calendar sleep-night read. It returns complete sessions, including all stages, and classifies each session by the localized date of `endAt`. `timezone` is an IANA timezone and defaults to `UTC`; pass it when clients need local calendar behavior. For example, `date=2026-04-20&timezone=Europe/Berlin` returns a session that starts at `2026-04-19T22:00:00Z` and ends at `2026-04-20T06:00:00Z` as the `2026-04-20` sleep night in `Europe/Berlin`. A session from the evening of April 20 to the morning of April 21 is returned for `date=2026-04-21`, not April 20, under those semantics.
- `/api/v2/health/day` reports the same sessions as `/sleep/nights` for its date, whole: a night that started the previous evening counts in full toward the day it ended.

## Storage Model

- Ingestion log: `ingestion_batches`, `ingestion_records`
- Raw metrics: `step_samples`, `sleep_sessions`, `sleep_stages`, `sleep_summaries`, `activity_summaries`, `blood_pressure_measurements`, `scalar_samples`, `daily_metric_values`
- Derived steps: `canonical_step_samples`, `canonical_step_day_bucket_contributions`, and the `pending_derived_rebuilds` retry queue
- Canonical views: `canonical_scalar_samples`, `canonical_sleep_sessions`, `canonical_sleep_summaries`, `canonical_activity_summaries`, `canonical_daily_metric_values`
- Reference data: `metric_catalog`, `provider_ranks` (both re-seeded from code at startup)
- Sources and access: `sources`, `source_instances`, `api_clients`
- Providers and jobs: `provider_oauth_accounts`, `provider_oauth_states`, `provider_sync_jobs`, `provider_sync_runs`, `provider_scheduled_sync_configs`, `provider_scheduled_sync_checkpoints`, `replay_jobs`

Timestamps are stored as PostgreSQL `timestamptz` values and returned as UTC ISO-8601 strings. Step totals are summed from the canonical bucket contributions over the requested timezone's local days.

## Tests

Run unit tests without a database:

```powershell
.\gradlew.bat test
```

Run `./gradlew check` for both unit and integration tests, or `./gradlew integrationTest` for integration tests alone.
Integration tests run against temporary PostgreSQL databases via Testcontainers, each cloned from one database that the application's Flyway migrations set up once per run.
If Docker is unavailable, point tests at an existing PostgreSQL database; each test gets an isolated schema:

```bash
export AQT_HEALTH_TEST_JDBC_URL="jdbc:postgresql://localhost:5432/aqt_health_test"
export AQT_HEALTH_TEST_DB_USER="aqt_health"
export AQT_HEALTH_TEST_DB_PASSWORD="aqt_health"
./gradlew check
```

## Contributing

For coding standards, development workflows, and conventional commit guidelines used in this project, please see the [CONTRIBUTING.md](CONTRIBUTING.md) guide.
