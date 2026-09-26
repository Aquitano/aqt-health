import type { ApiResult } from "@/lib/types";

export async function proxyFetch<T>(
  path: string,
  init?: RequestInit
): Promise<ApiResult<T>> {
  const response = await fetch(`/api/backend${path}`, init);
  return (await response.json()) as ApiResult<T>;
}
