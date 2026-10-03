import { afterEach, expect, it, vi } from "vitest";
import { aqtHealthClient } from "./aqtHealthClient";

afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); });

it("rejects an empty successful backend response", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(null, { status: 204 })));
  expect(await aqtHealthClient.getHealth()).toEqual({
    ok: false,
    status: 204,
    message: "Backend returned an empty response.",
  });
});
