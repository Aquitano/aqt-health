# aqt-health

A single-user health data API. It pulls data from Withings and Google Health, accepts batches from Health Connect exports and other trusted scripts, and serves one merged view of steps, sleep, activity, blood pressure and point-in-time metrics such as heart rate and weight. It is a Kotlin and Ktor service on PostgreSQL, with Exposed and Flyway. `cli/` holds `aqt-health`, a read-only client for agents.

## Run locally

You need JDK 25 (`mise.toml` pins Temurin 25) and Docker.

The compose file does not publish PostgreSQL to the host. Copy the override that does, then start the database:

```bash
cp docker-compose.override.example.yml docker-compose.override.yml
docker compose up -d postgres
```

Then run the app against it:

```bash
export AQT_HEALTH_BOOTSTRAP_API_KEY="local-dev-key"
./gradlew run
```

In PowerShell, set `$env:AQT_HEALTH_BOOTSTRAP_API_KEY = "local-dev-key"` and run `.\gradlew.bat run`. The database defaults match the compose file, and Flyway migrates the schema at startup.

`curl http://localhost:8080/api/v2/admin/health` needs no key. The other endpoints, apart from the OAuth callbacks, take `Authorization: Bearer <api-key>`. Outside production, `/openapi` serves the spec and `/swagger` serves Swagger UI.

## Configuration

`src/main/resources/application.yaml` maps environment variables to config, and `AppConfig.kt` validates them at startup.

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `AQT_HEALTH_ENV` | `local` | `local` or `production`; see [production validation](#production-validation) |
| `AQT_HEALTH_JDBC_URL` | `jdbc:postgresql://localhost:5432/aqt_health` | Database URL |
| `AQT_HEALTH_DB_USER`, `AQT_HEALTH_DB_PASSWORD` | `aqt_health` | Database credentials |
| `AQT_HEALTH_DB_MAX_POOL_SIZE` | `10` | Hikari pool size |
| `AQT_HEALTH_DB_IDLE_IN_TRANSACTION_TIMEOUT` | `1m` | PostgreSQL ends an app session left idle inside a transaction after this long |
| `AQT_HEALTH_INGESTION_MAX_BODY_BYTES` | `33554432` | Largest ingestion request body; larger ones get `413` |
| `AQT_HEALTH_BOOTSTRAP_CLIENT_NAME` | `local-admin` | Name of the API client created from the bootstrap key |
| `AQT_HEALTH_BOOTSTRAP_API_KEY` | empty | API key to create or rotate at startup |
| `AQT_HEALTH_LOG_FORMAT` | `text` | `text` or `json` log lines on stdout |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | empty | Turns on OTLP export; see [telemetry](#telemetry) |

Each provider takes seven variables, with the prefix `AQT_HEALTH_GOOGLE_` or `AQT_HEALTH_WITHINGS_`:

- `CLIENT_ID`, `CLIENT_SECRET` and `REDIRECT_URI` come from the provider's OAuth app registration.
- `TOKEN_ENCRYPTION_KEY` encrypts the stored OAuth tokens and must be at least 32 bytes. Startup fails without it once the provider's client ID or secret is set.
- `API_BASE_URL`, `OAUTH_TOKEN_URL` and `OAUTH_AUTH_URL` default to the provider's public endpoints and only change for testing.

The app stores the bootstrap key only as a SHA-256 hash in `api_clients`. With no bootstrap key, the app still starts, but only clients already in the table can call it.

Migrations run `CREATE EXTENSION IF NOT EXISTS btree_gist`. On a managed database where the app user cannot create extensions, enable `btree_gist` before the first start.

### Production validation

With `AQT_HEALTH_ENV=production`, the app refuses to start, before it connects to the database, unless:

- the bootstrap API key is set, at least 32 bytes and not a placeholder, and the client name is set and not `local-admin`;
- the JDBC URL does not point at localhost and the database credentials are not the compose default `aqt_health`;
- both providers have a client ID, client secret and a token encryption key of at least 32 bytes;
- both redirect URIs are public HTTPS URLs, and the provider API, token and authorization URLs are HTTPS.

The check rejects values that contain placeholder markers such as `replace-with`, `changeme`, `example.` or `local-dev`. In production `/openapi` requires an API key and Swagger UI is off.

### Secrets

Copy `.env.example` to `.env` for real values; git ignores `.env`. The example's top block is a production template, and the commented block at the end holds local values.

Docker Compose reads `.env` on its own, but `./gradlew run` does not. Load it into the shell first:

```bash
set -a; . ./.env; set +a
```

```powershell
Get-Content .env | Where-Object { $_ -and $_ -notmatch '^\s*#' } | ForEach-Object {
  $name, $value = $_ -split '=', 2
  [Environment]::SetEnvironmentVariable($name.Trim(), $value.Trim(), 'Process')
}
```

`.infisical.json` points at the Infisical project that holds the backend variables. After logging in with the Infisical CLI, run from a POSIX shell such as Git Bash:

```sh
sh scripts/secrets.sh pull backend dev
sh scripts/secrets.sh diff backend dev
sh scripts/secrets.sh push backend dev
```

`pull` replaces `.env` and refuses an empty export. `push` asks for confirmation. `diff` prints secret values, so keep its output out of shared logs.

## Telemetry

Logs always go to stdout. Setting `OTEL_EXPORTER_OTLP_ENDPOINT` also exports HTTP server metrics and every log event over OTLP/HTTP. The OpenTelemetry SDK reads the standard `OTEL_*` variables. The app only sets the defaults for the service name (`aqt-health`), the protocol (`http/protobuf`) and traces (off). For OpenObserve:

```sh
OTEL_EXPORTER_OTLP_ENDPOINT=https://openobserve.example.com/api/<organization>
OTEL_EXPORTER_OTLP_HEADERS=Authorization=Basic%20<base64 user:password>
OTEL_RESOURCE_ATTRIBUTES=deployment.environment.name=production
```

The SDK URL-decodes header values, so write a `+` in the base64 credential as `%2B`.

## Deployment

When a push to `main` changes the backend, CI publishes `ghcr.io/aquitano/aqt-health-api` with the tags `latest` and `sha-<short-commit>`. The package is public, so the server needs no registry login.

On the server, put `docker-compose.deploy.yml` and a filled-in `.env` in a directory outside any checkout, then run:

```bash
docker compose -f docker-compose.deploy.yml up -d
```

Set `AQT_HEALTH_API_TAG` to a `sha-<short-commit>` tag. An update is then a tag edit plus `up -d`, and a rollback is the same edit back. The deploy file runs in production mode, logs JSON, refuses to start without `AQT_HEALTH_DB_PASSWORD` and passes the `OTEL_*` variables through. It binds the API to `127.0.0.1:${AQT_HEALTH_API_PORT:-8080}`, so put a reverse proxy or Tailscale in front. `POSTGRES_DB` names the database for both containers.

`docker-compose.yml` builds the image from source instead. Its app service also defaults to production mode, so it needs a production `.env` as well.

## Storage model

The ingestion log (`ingestion_batches` and `ingestion_records`) is the source of truth. Provider syncs and the ingestion endpoint append to it, and its rows are never deleted. The metric data is a projection of the log: the per-provider metric tables, the canonical tables that pick one source where providers overlap, and the daily step buckets. `POST /api/v2/admin/replay` rebuilds projections from the log. [ADR 0001](docs/adr/0001-single-user-event-log-with-projections.md) records this design and the single-user constraint.

Reads return the canonical result. Where providers overlap, the better-ranked one wins, and the ranking is per metric in `MetricCatalogBootstrap`. A `provider` filter narrows the canonical result, so `provider=withings` returns the rows Withings won, not everything Withings recorded.

## Ingesting data

`POST /api/v2/ingestion/batches` takes a batch of normalized records from a trusted script:

```bash
curl -X POST http://localhost:8080/api/v2/ingestion/batches \
  -H "Authorization: Bearer local-dev-key" \
  -H "Content-Type: application/json" \
  -d '{
    "provider": "health_connect",
    "providerInstanceId": "pixel-8-health-connect",
    "batchExternalId": "export-2026-04-19",
    "ingestedAt": "2026-04-19T10:00:00Z",
    "sourcePayload": {"exportId": "export-2026-04-19"},
    "records": [
      {"type": "scalar", "providerRecordId": "hr-1", "measuredAt": "2026-04-19T08:30:00Z", "metricType": "heart_rate", "value": 62}
    ]
  }'
```

Resending a `batchExternalId` for the same `providerInstanceId` returns the stored batch instead of a new one, and ingestion skips records whose provider record ID is already stored. The spec lists the record types and their fields.

## Providers

Withings and Google Health connect through OAuth and sync in background jobs. `GET /api/v2/providers/status` shows each account's state. Disconnecting only clears the local tokens; it does not revoke them at the provider. When a refresh token stops working, the account moves to `needs_reauth` and syncs skip it until it reconnects.

## Google Health provider

The app reads Google Health with Google's generated Health client library and keeps each response page in the batch's `sourcePayload`. Every data type is read with `reconcile` over all of the account's sources (`all-sources`), so Google merges overlapping devices into one stream. Narrower source families drop real data, such as weights a Withings scale writes into Google Health through Health Connect.

Google Cloud setup:

1. Create or choose a Google Cloud project and configure its OAuth consent screen.
2. Create an OAuth web client.
3. Add the redirect URI, `http://localhost:8080/api/v2/providers/google-health/oauth/callback` locally or the public HTTPS URL in production.
4. Put the client ID, client secret, redirect URI and a token encryption key in `.env`.

The consent screen needs these scopes:

- `https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly`
- `https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly`
- `https://www.googleapis.com/auth/googlehealth.sleep.readonly`

To connect, call `GET /api/v2/providers/google-health/oauth/start` and open the `authorizationUrl` it returns. Google redirects to the callback, which stores the encrypted tokens.

Start a sync with:

```bash
curl -X POST http://localhost:8080/api/v2/providers/google-health/sync-jobs \
  -H "Authorization: Bearer local-dev-key" \
  -H "Content-Type: application/json" \
  -d '{"from": "2026-04-01T00:00:00Z", "to": "2026-04-20T00:00:00Z"}'
```

Without `dataTypes`, the sync reads the provider's default data types, which `GET /api/v2/providers/google-health` lists. A `sleep` point also yields a sleep summary when Google marks it as the main sleep, or, in a window without a main sleep, when it is not a nap. Without `from` and `to`, the sync covers the last seven days. The job splits the range into one-day UTC windows and skips days it has already completed, so only the current day is fetched again.

Poll `GET /api/v2/providers/google-health/sync-jobs/{jobId}` for progress. The job keeps running if the client disconnects.

After a restart, the app reruns unfinished jobs from the start, which is safe because ingestion skips records it already has. `restartCount` on the job counts these reruns. A job is rerun at most three times, and the next restart marks it failed.

Ingestion skips overlapping Google Health step intervals from the same account as duplicates. Steps stored by versions that read with `list` stay inflated where devices overlapped. Raw rows are never deleted, so only a backfill into a fresh database repairs them.

## Withings provider

Withings developer setup:

1. Create or choose a Withings developer application.
2. Add the callback URL, `http://localhost:8080/api/v2/providers/withings/oauth/callback` locally or the public HTTPS URL in production.
3. Put the client ID, client secret, redirect URI and a token encryption key in `.env`.

The app requests the scopes `user.info`, `user.metrics` and `user.activity`.

Connecting and syncing work as for Google Health, under `/api/v2/providers/withings/`. A Withings authorization code expires after 30 seconds, so the callback must reach the app right away.

The raw Withings responses stay in the ingestion log in full. Metrics without a table yet, such as SpO2, temperature or vascular age, exist only there until a projection reads them.

## Reading data

The server's OpenAPI document is the endpoint reference. The CLI lists it:

```bash
cd cli
bun install
export AQT_HEALTH_URL=http://localhost:8080
export AQT_HEALTH_API_KEY=local-dev-key

bun src/main.ts endpoints                          # every read endpoint
bun src/main.ts endpoints metrics/heart_rate/daily # one endpoint's parameters
bun src/main.ts get steps/daily fromDate=2026-11-01 toDate=2026-11-07 timezone=Europe/Berlin
```

`bun src/main.ts --help` describes paging with `--all` and the error format. The CLI only sends GET requests, and it refuses the OAuth endpoints and any path outside `/api/`. `bun run build` compiles a standalone binary to `cli/dist/aqt-health`.

Read responses name each quantity the same way everywhere:

- Aggregates of one metric use `count`, `minValue`, `maxValue` and `avgValue`, with the unit in `unit`.
- Fields of fixed-shape records name the statistic, the quantity and the unit, such as `avgHeartRateBpm` or `systolicMmhg`. Statistics are always `min`, `max` and `avg`, and a step total is always `steps`.
- Trend blocks sit under the quantity's key, so their fields name the period, the statistic and the unit, such as `currentAvgBpm`.

Ingestion records keep their own field names, because the log stores records in that format. A sleep summary is ingested with `hrAverageBpm` and read back as `avgHeartRateBpm`.

## Tests

```bash
./gradlew test             # unit tests, no database
./gradlew integrationTest  # PostgreSQL tests
./gradlew check            # both
```

Integration tests use Testcontainers, so Docker must be running. Without Docker, point them at an existing database and each test gets its own schema:

```bash
export AQT_HEALTH_TEST_JDBC_URL="jdbc:postgresql://localhost:5432/aqt_health_test"
export AQT_HEALTH_TEST_DB_USER="aqt_health"
export AQT_HEALTH_TEST_DB_PASSWORD="aqt_health"
./gradlew check
```

The CLI has its own checks: `bun run typecheck` and `bun test` in `cli/`.

See [CONTRIBUTING.md](CONTRIBUTING.md) for commit conventions.
