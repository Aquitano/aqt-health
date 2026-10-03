import type { ApiResult } from "@/lib/types";

export async function proxyFetch<T>(
  path: string,
  readResponse: (response: Response) => Promise<ApiResult<T>>,
  init?: RequestInit,
): Promise<ApiResult<T>> {
  const response = await fetch(`/api/backend${path}`, init);
  try {
    return await readResponse(response);
  } catch {
    return {
      ok: false,
      status: response.status,
      message: "Backend returned an invalid response.",
    };
  }
}
