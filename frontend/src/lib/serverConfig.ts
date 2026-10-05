import { type } from "arktype";

const timeZone = type("string").narrow((value, ctx) => {
  try {
    new Intl.DateTimeFormat("en", { timeZone: value });
    return true;
  } catch {
    return ctx.mustBe("an IANA time zone");
  }
});

const serverEnv = type({
  AQT_HEALTH_TIMEZONE: timeZone.default("UTC"),
  AQT_HEALTH_BACKEND_TIMEOUT_MS: type("string.integer.parse").to("number.integer > 0").default("8000"),
  "AQT_HEALTH_API_BASE_URL?": "string",
  "AQT_HEALTH_API_KEY?": "string",
  "NODE_ENV?": "string",
  "NEXT_PHASE?": "string",
}).narrow(
  // Mirror the backend's fail-fast production validation: a production server must not
  // silently fall back to localhost. `next build` prerenders no backend-dependent pages,
  // so the default is only allowed there and in dev.
  (env, ctx) =>
    Boolean(env.AQT_HEALTH_API_BASE_URL) ||
    env.NODE_ENV !== "production" ||
    env.NEXT_PHASE === "phase-production-build" ||
    ctx.reject({ path: ["AQT_HEALTH_API_BASE_URL"], expected: "set when running the production server", actual: "" }),
);

const env = serverEnv.assert(process.env);

export const serverConfig = {
  timeZone: env.AQT_HEALTH_TIMEZONE,
  backendRequestTimeoutMs: env.AQT_HEALTH_BACKEND_TIMEOUT_MS,
  apiBaseUrl: env.AQT_HEALTH_API_BASE_URL || "http://localhost:8080",
  apiKey: env.AQT_HEALTH_API_KEY,
};
