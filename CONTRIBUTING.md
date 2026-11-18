# Contributing

## Commits

Commit messages and pull request titles follow [Conventional Commits](https://www.conventionalcommits.org/): `<type>(<scope>): <summary>`, with the summary in the imperative. A breaking change adds `!` after the scope, as in `refactor(api)!: give each quantity one name in read responses`.

Types: `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `build`, `ci` and `chore`.

Scopes in use:

- `api` for routes, OpenAPI, authentication and request validation
- `cli` for the `aqt-health` client in `cli/`
- `db` for migrations, schema and connection setup
- `deploy` for the Dockerfile and compose files
- `deps` for dependency updates
- `ingestion` for batch ingestion and record mapping
- `metrics` for metric reads, writes, canonical derivations and trends
- `provider` for OAuth, accounts and discovery shared by all providers
- `google` and `withings` for one provider's client, normalizer or sync
- `sleep` for sleep sessions, stages and nights
- `sync` for sync jobs and scheduled syncs
- `test` for shared test helpers

Leave the scope out when a change spans several of these. Documentation commits use plain `docs:`.

## Verifying a change

CI runs these on every pull request:

```bash
./gradlew check                                # unit and PostgreSQL integration tests
cd cli && bun run typecheck && bun test        # CLI
```

`./gradlew test` runs only the unit tests. A test that needs PostgreSQL extends `PostgresIntegrationTest`. That base class puts the test in the JUnit category the `integrationTest` task runs, and it closes the pools the test opens with `openDatabase`. See the README for running the integration tests without Docker.

List the checks you ran, and any manual verification, in the pull request description.
