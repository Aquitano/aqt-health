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
});

const env = serverEnv.assert(process.env);

export const serverConfig = {
  timeZone: env.AQT_HEALTH_TIMEZONE,
  backendRequestTimeoutMs: env.AQT_HEALTH_BACKEND_TIMEOUT_MS,
};
