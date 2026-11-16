import { type ParseArgsOptionsConfig, parseArgs } from "node:util";
import { type } from "arktype";

export type Io = { out: (text: string) => void; err: (text: string) => void };
export type Env = Readonly<Record<string, string | undefined>>;

const DEFAULT_URL = "http://localhost:8080";
const DEFAULT_MAX_ITEMS = 50_000;
const MAX_PAGE_SIZE = "5000";

const HELP = `aqt-health reads health data from an aqt-health API and prints it as JSON.

Usage:
  aqt-health endpoints [path]            List the read endpoints, or one endpoint's parameters
  aqt-health get <path> [name=value]...  Read one endpoint
    --all             Follow meta.nextCursor and return every page as one items array
    --max-items <n>   With --all, stop once at least n items are read (default ${DEFAULT_MAX_ITEMS});
                      meta.nextCursor then continues the read
  --pretty            Indent the JSON

<path> is an API path with or without /api/v2, for example steps/daily or metrics/heart_rate/daily.
Query parameters are name=value pairs:
  aqt-health get steps/daily fromDate=2026-11-01 toDate=2026-11-07 timezone=Europe/Berlin

Environment:
  AQT_HEALTH_URL       API base URL (default ${DEFAULT_URL})
  AQT_HEALTH_API_KEY   API key, sent as a bearer token (required)

Output is JSON on stdout. Errors are JSON on stderr ({"error":{"code":...,"message":...}}),
with exit code 1 for a failed request and 2 for a usage error.
`;

type Failure = { exitCode: 1 | 2; body: unknown };
type Result<T> = { ok: true; value: T } | { ok: false; failure: Failure };

const ok = <T>(value: T): Result<T> => ({ ok: true, value });
const usageError = (message: string): Result<never> => ({
  ok: false,
  failure: { exitCode: 2, body: { error: { code: "usage", message } } },
});
const requestError = (code: string, message: string): Result<never> => ({
  ok: false,
  failure: { exitCode: 1, body: { error: { code, message } } },
});

type Config = { baseUrl: URL; apiKey: string };

type GetCommand = {
  kind: "get";
  path: string;
  params: ReadonlyArray<readonly [name: string, value: string]>;
  all: boolean;
  maxItems: number;
  pretty: boolean;
};
type Command = { kind: "help" } | { kind: "endpoints"; path: string | undefined; pretty: boolean } | GetCommand;

const options = {
  all: { type: "boolean", default: false },
  "max-items": { type: "string" },
  pretty: { type: "boolean", default: false },
  help: { type: "boolean", short: "h", default: false },
} as const satisfies ParseArgsOptionsConfig;

function parseFlags(argv: string[]) {
  try {
    return ok(parseArgs({ args: argv, allowPositionals: true, options }));
  } catch (error) {
    const reason = error instanceof Error ? error.message : String(error);
    return usageError(`${reason}. Pass query parameters as name=value, for example fromDate=2026-11-01.`);
  }
}

function parseCommand(argv: string[]): Result<Command> {
  const flags = parseFlags(argv);
  if (!flags.ok) return flags;
  const { values, positionals } = flags.value;
  const [name, ...rest] = positionals;
  if (values.help || name === undefined || name === "help") return ok({ kind: "help" });

  if (name === "endpoints") {
    if (rest.length > 1 || values.all || values["max-items"] !== undefined) {
      return usageError("endpoints takes at most one path, and no --all or --max-items");
    }
    return ok({ kind: "endpoints", path: rest[0], pretty: values.pretty });
  }
  if (name !== "get") return usageError(`Unknown command "${name}". The commands are endpoints and get.`);

  const [path, ...pairs] = rest;
  if (path === undefined) return usageError("get needs a path, for example: aqt-health get steps/daily date=2026-11-01");
  const params: Array<readonly [string, string]> = [];
  for (const pair of pairs) {
    const separator = pair.indexOf("=");
    if (separator <= 0) return usageError(`Expected a query parameter as name=value, got "${pair}"`);
    params.push([pair.slice(0, separator), pair.slice(separator + 1)]);
  }

  const maxItemsText = values["max-items"];
  if (maxItemsText !== undefined && !values.all) return usageError("--max-items only applies with --all");
  const maxItems = maxItemsText === undefined ? DEFAULT_MAX_ITEMS : Number(maxItemsText);
  if (!Number.isSafeInteger(maxItems) || maxItems < 1) return usageError("--max-items must be a positive whole number");

  return ok({ kind: "get", path, params, all: values.all, maxItems, pretty: values.pretty });
}

function readConfig(env: Env): Result<Config> {
  const apiKey = env.AQT_HEALTH_API_KEY;
  if (!apiKey) return usageError("Set AQT_HEALTH_API_KEY to an aqt-health API key");
  const url = env.AQT_HEALTH_URL ?? DEFAULT_URL;
  if (!URL.canParse(url)) return usageError(`AQT_HEALTH_URL is not a URL: ${url}`);
  return ok({ baseUrl: new URL(url), apiKey });
}

const isOAuthPath = (path: string) => path.includes("/oauth/");

function apiPath(path: string): Result<string> {
  const relative = path.replace(/^\/+/, "");
  const absolute = relative.startsWith("api/") ? `/${relative}` : `/api/v2/${relative}`;
  if (isOAuthPath(absolute)) return usageError("OAuth endpoints change provider connections, and this CLI only reads");
  return ok(absolute);
}

const endpointUrl = (config: Config, path: string) =>
  new URL(config.baseUrl.pathname.replace(/\/$/, "") + path, config.baseUrl);

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return undefined;
  }
}

async function requestJson(config: Config, url: URL): Promise<Result<unknown>> {
  let response: Response;
  try {
    response = await fetch(url, {
      headers: { authorization: `Bearer ${config.apiKey}`, accept: "application/json" },
    });
  } catch (error) {
    return requestError("network_error", `Could not reach ${url.origin}: ${String(error)}`);
  }
  const text = await response.text();
  const body = parseJson(text);
  if (!response.ok) {
    return {
      ok: false,
      failure: { exitCode: 1, body: body ?? { error: { code: `http_${response.status}`, message: text || response.statusText } } },
    };
  }
  if (body === undefined) return requestError("invalid_response", `${url.pathname} did not return JSON`);
  return ok(body);
}

const Page = type({ items: "unknown[]", meta: { "nextCursor?": "string" } });

async function readAllPages(config: Config, url: URL, maxItems: number): Promise<Result<unknown>> {
  if (!url.searchParams.has("limit")) url.searchParams.set("limit", MAX_PAGE_SIZE);
  const items: unknown[] = [];
  const cursors = new Set<string>();
  for (;;) {
    const response = await requestJson(config, url);
    if (!response.ok) return response;
    const page = Page(response.value);
    if (page instanceof type.errors) {
      return requestError("not_paged", `${url.pathname} does not return pages of items, so --all does not apply`);
    }
    items.push(...page.items);
    const next = page.meta.nextCursor;
    if (next === undefined || items.length >= maxItems) {
      return ok({ ...page, items, meta: { ...page.meta, count: items.length } });
    }
    if (cursors.has(next)) return requestError("repeated_cursor", `${url.pathname} returned the cursor ${next} twice`);
    cursors.add(next);
    url.searchParams.set("cursor", next);
  }
}

async function read(config: Config, command: GetCommand): Promise<Result<unknown>> {
  const path = apiPath(command.path);
  if (!path.ok) return path;
  const url = endpointUrl(config, path.value);
  for (const [name, value] of command.params) url.searchParams.append(name, value);
  return command.all ? readAllPages(config, url, command.maxItems) : requestJson(config, url);
}

const Parameter = type({
  name: "string",
  in: "string",
  "required?": "boolean",
  "description?": "string",
  "schema?": "object",
});
const Operation = type({ "summary?": "string", "description?": "string", "parameters?": Parameter.array() });
const OpenApi = type({ paths: { "[string]": { "get?": Operation } } });

const templatePattern = (template: string) => new RegExp(`^${template.replace(/\{[^}]+\}/g, "[^/]+")}$`);

async function describeEndpoints(config: Config, path: string | undefined): Promise<Result<unknown>> {
  const spec = await requestJson(config, endpointUrl(config, "/openapi"));
  if (!spec.ok) return spec;
  const doc = OpenApi(spec.value);
  if (doc instanceof type.errors) return requestError("invalid_openapi", `The API's OpenAPI document is not readable: ${doc.summary}`);
  const reads = Object.entries(doc.paths).flatMap(([template, item]) =>
    item.get !== undefined && !isOAuthPath(template) ? [{ template, operation: item.get }] : [],
  );

  if (path === undefined) {
    return ok({
      endpoints: reads.map(({ template, operation }) => ({
        path: template,
        summary: operation.summary,
        parameters: (operation.parameters ?? []).map((parameter) => parameter.name),
      })),
    });
  }

  const wanted = apiPath(path);
  if (!wanted.ok) return wanted;
  const [wantedPath = ""] = wanted.value.split("?");
  const match =
    reads.find(({ template }) => template === wantedPath) ??
    reads.find(({ template }) => templatePattern(template).test(wantedPath));
  if (match === undefined) return usageError(`No read endpoint matches ${wantedPath}. Run aqt-health endpoints to list them.`);
  return ok({
    path: match.template,
    summary: match.operation.summary,
    description: match.operation.description,
    parameters: (match.operation.parameters ?? []).map(({ name, in: location, required, description, schema }) => ({
      name,
      in: location,
      required: required ?? false,
      description,
      schema,
    })),
  });
}

function report(io: Io, failure: Failure): number {
  io.err(`${JSON.stringify(failure.body)}\n`);
  return failure.exitCode;
}

export async function run(argv: string[], env: Env, io: Io): Promise<number> {
  const command = parseCommand(argv);
  if (!command.ok) return report(io, command.failure);
  if (command.value.kind === "help") {
    io.out(HELP);
    return 0;
  }
  const config = readConfig(env);
  if (!config.ok) return report(io, config.failure);

  const result =
    command.value.kind === "endpoints"
      ? await describeEndpoints(config.value, command.value.path)
      : await read(config.value, command.value);
  if (!result.ok) return report(io, result.failure);
  io.out(`${JSON.stringify(result.value, null, command.value.pretty ? 2 : undefined)}\n`);
  return 0;
}
