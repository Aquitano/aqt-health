// Liveness probe for the frontend container. Does not touch the backend so a
// backend outage never flaps the frontend healthcheck.
export function GET() {
  return Response.json({ status: "ok" });
}
