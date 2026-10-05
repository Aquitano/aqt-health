import type { ApiResult } from "@/lib/types";

export async function proxyFetch<T>(
  path: string,
  readResponse: (response: Response) => Promise<ApiResult<T>>,
  init?: RequestInit,
): Promise<ApiResult<T>> {
  try {
    const response = await fetch(`/api/backend${path}`, init);
    return await readResponse(response).catch(() => ({
      ok: false,
      status: response.status,
      message: "Backend returned an invalid response.",
    }));
  } catch (error) {
    return { ok: false, message: error instanceof Error ? error.message : "Backend request failed." };
  }
}
