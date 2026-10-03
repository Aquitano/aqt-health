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
});

const env = serverEnv.assert(process.env);

export const serverConfig = {
  timeZone: env.AQT_HEALTH_TIMEZONE,
};
