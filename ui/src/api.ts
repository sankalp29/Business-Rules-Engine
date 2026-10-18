import type { ExecutionResponse, RuleResponse, RuleVersion, TypeResponse } from "./model"

const base = (import.meta.env.VITE_API_URL ?? "").replace(/\/$/, "")

export class ApiError extends Error {
  readonly details: string[]

  constructor(message: string, details: string[] = []) {
    super(message)
    this.name = "ApiError"
    this.details = details
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(`${base}${path}`, {
      ...init,
      headers: {
        Accept: "application/json",
        ...(init?.body ? { "Content-Type": "application/json" } : {}),
        ...init?.headers,
      },
    })
  } catch {
    throw new ApiError("The rule desk cannot reach the server.", [
      "Start the rule engine on port 8080, then try again.",
    ])
  }

  const text = await response.text()
  const body = text ? parseBody(text) : null
  if (!response.ok) {
    const record = asError(body)
    throw new ApiError(record?.error || `Request failed (${response.status}).`, record?.details ?? [])
  }
  return body as T
}

function parseBody(text: string): unknown {
  try {
    return JSON.parse(text) as unknown
  } catch {
    return null
  }
}

function asError(body: unknown): { error?: string; details?: string[] } | null {
  if (!body || typeof body !== "object") return null
  const record = body as { error?: unknown; details?: unknown }
  return {
    error: typeof record.error === "string" ? record.error : undefined,
    details: Array.isArray(record.details) ? record.details.filter((item): item is string => typeof item === "string") : [],
  }
}

export const api = {
  listTypes: () => request<TypeResponse[]>("/api/types"),
  createType: (body: unknown) => request<TypeResponse>("/api/types", { method: "POST", body: JSON.stringify(body) }),
  addField: (name: string, body: unknown) =>
    request<TypeResponse>(`/api/types/${encodeURIComponent(name)}/fields`, {
      method: "POST",
      body: JSON.stringify(body),
    }),
  listRules: () => request<RuleResponse[]>("/api/rules"),
  getRule: (key: string) => request<RuleResponse>(`/api/rules/${encodeURIComponent(key)}`),
  createRule: (body: unknown) => request<RuleResponse>("/api/rules", { method: "POST", body: JSON.stringify(body) }),
  listVersions: (key: string) => request<RuleVersion[]>(`/api/rules/${encodeURIComponent(key)}/versions`),
  getVersion: (key: string, version: string) =>
    request<RuleVersion>(`/api/rules/${encodeURIComponent(key)}/versions/${encodeURIComponent(version)}`),
  createVersion: (key: string, body: unknown) =>
    request<RuleVersion>(`/api/rules/${encodeURIComponent(key)}/versions`, { method: "POST", body: JSON.stringify(body) }),
  changeDates: (
    key: string,
    version: string,
    body: { effectiveFrom: string; effectiveUntil: string; segments?: { effectiveFrom: string; effectiveUntil: string }[] },
  ) =>
    request<RuleVersion>(`/api/rules/${encodeURIComponent(key)}/versions/${encodeURIComponent(version)}/dates`, {
      method: "PUT",
      body: JSON.stringify(body),
    }),
  executeRule: (key: string, body: unknown) =>
    request<ExecutionResponse>(`/api/rules/${encodeURIComponent(key)}/execute`, {
      method: "POST",
      body: JSON.stringify(body),
    }),
}
