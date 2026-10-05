import { expect, it } from "vitest";
import {
  readAcknowledgement,
  readOAuthStart,
  readScheduledSyncRun,
  readSyncJobStart,
  readSyncJobStatus,
} from "./apiResponses";

it.each([
  readOAuthStart,
  readScheduledSyncRun,
  readSyncJobStart,
  readSyncJobStatus,
])("rejects incomplete successful provider responses with %s", async (readResponse) => {
  const result = await readResponse(Response.json({ ok: true, data: {} }));
  expect(result).toMatchObject({ ok: false, message: expect.stringContaining("Unexpected response") });
});

it("preserves backend failures and accepts acknowledgements without assuming an OAuth payload", async () => {
  const failure = { ok: false, status: 409, message: "Account disconnected." };
  expect(await readSyncJobStart(Response.json(failure))).toEqual(failure);
  expect(await readAcknowledgement(Response.json({ ok: true, data: { disconnected: true } })))
    .toEqual({ ok: true, data: { disconnected: true } });
});
