import type { ApiResult } from "@/lib/types";

export async function proxyFetch<T>(
  path: string,
  init?: RequestInit
): Promise<ApiResult<T>> {
  const response = await fetch(`/api/backend${path}`, init);
  try {
    return (await response.json()) as ApiResult<T>;
  } catch {
    return {
      ok: false,
      status: response.status,
      message: "Backend returned an invalid response.",
    };
  }
}
