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

it("parses complete OAuth and job-start payloads", async () => {
  const oauth = { provider: "withings", authorizationUrl: "https://provider.test/oauth", expiresAt: "2026-10-03T12:00:00Z" };
  const job = { jobId: "job-1", status: "queued", createdAt: "2026-10-03T12:00:00Z" };
  expect(await readOAuthStart(Response.json({ ok: true, data: oauth }))).toEqual({ ok: true, data: oauth });
  expect(await readSyncJobStart(Response.json({ ok: true, data: job }))).toEqual({ ok: true, data: job });
});
