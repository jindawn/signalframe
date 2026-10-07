import createClient from "openapi-fetch";
import type { paths, components } from "./api.generated";
export const api = createClient<paths>({ baseUrl: "" });
export type Schema<T extends keyof components["schemas"]> =
  components["schemas"][T];
export function unwrap<T>(result: { data?: T; error?: Schema<"ApiError"> }): T {
  if (result.data !== undefined) return result.data;
  throw new Error(result.error?.message ?? "无法连接 API，请确认后端已启动。");
}
