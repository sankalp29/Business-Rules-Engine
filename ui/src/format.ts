import type { Assignment, Condition, EffectiveSegment, RuleResponse, RuleVersion, StageMark } from "./model"

export function ruleKey(rule: Pick<RuleResponse, "code" | "id">): string {
  return rule.code || String(rule.id)
}

/** The request shape for the version that is active now. Code and name come from the rule. */
export function constructionDocument(rule: RuleResponse): Record<string, unknown> {
  const version = activeVersionOf(rule)
  const definition = version?.definition ?? {}
  const document: Record<string, unknown> = {}
  if (rule.code) document.code = rule.code
  if (rule.name) document.name = rule.name
  if (version?.description) document.description = version.description
  if (definition.salience !== null && definition.salience !== undefined) document.salience = definition.salience
  document.when = definition.when ?? []
  document.then = definition.then ?? []
  return document
}

export function describeCondition(condition: Condition | null | undefined, depth = 0): string[] {
  if (!condition) return []
  const pad = "  ".repeat(depth)
  if ((condition.operator === "and" || condition.operator === "or") && condition.conditions) {
    const lines: string[] = []
    condition.conditions.forEach((child, index) => {
      if (index > 0) lines.push(`${pad}${condition.operator}`)
      lines.push(...describeCondition(child, depth + 1))
    })
    return lines
  }
  const right = condition.valueField
    ? condition.valueField
    : formatLiteral(condition.value)
  return [`${pad}${condition.field ?? "field"} ${condition.operator} ${right}`]
}

export function describeAssignment(assignment: Assignment): string {
  if (assignment.expression) return `${assignment.field} = ${assignment.expression}`
  return `${assignment.field} = ${formatLiteral(assignment.value)}`
}

export function formatLiteral(value: unknown): string {
  if (typeof value === "string") return JSON.stringify(value)
  if (typeof value === "boolean") return value ? "true" : "false"
  if (typeof value === "number") return String(value)
  if (value === null || value === undefined) return "null"
  return JSON.stringify(value)
}

export type Change = {
  path: string
  previous: unknown
  next: unknown
}

export function collectChanges(values: unknown, prefix = ""): Change[] {
  if (!values || typeof values !== "object" || Array.isArray(values)) return []
  const changes: Change[] = []
  for (const [key, value] of Object.entries(values as Record<string, unknown>)) {
    const path = prefix ? `${prefix}.${key}` : key
    if (isChange(value)) {
      changes.push({ path, previous: value.previousValue, next: value.newValue })
    } else {
      changes.push(...collectChanges(value, path))
    }
  }
  return changes
}

function isChange(value: unknown): value is { previousValue: unknown; newValue: unknown } {
  return !!value && typeof value === "object" && "previousValue" in value && "newValue" in value
}

export const IST = "Asia/Kolkata"

const IST_PARTS = new Intl.DateTimeFormat("en-GB", {
  timeZone: IST,
  hourCycle: "h23",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
})

function part(parts: Intl.DateTimeFormatPart[], type: Intl.DateTimeFormatPartTypes): string {
  return parts.find((item) => item.type === type)?.value ?? ""
}

/** `YYYY-MM-DDTHH:mm:ss` wall time in India Standard Time, for a datetime-local input. */
export function istInputValue(value: Date | string | null | undefined): string {
  if (value == null || value === "") return ""
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return ""
  const parts = IST_PARTS.formatToParts(date)
  const hour = part(parts, "hour") === "24" ? "00" : part(parts, "hour")
  return `${part(parts, "year")}-${part(parts, "month")}-${part(parts, "day")}T${hour}:${part(parts, "minute")}:${part(parts, "second")}`
}

/** `HH:mm` in India Standard Time. */
export function istClock(value: Date): string {
  if (Number.isNaN(value.getTime())) return ""
  const parts = IST_PARTS.formatToParts(value)
  const hour = part(parts, "hour") === "24" ? "00" : part(parts, "hour")
  return `${hour}:${part(parts, "minute")}`
}

export function istCalendarDate(value: Date): { year: number; month: number; day: number } | null {
  if (Number.isNaN(value.getTime())) return null
  const parts = IST_PARTS.formatToParts(value)
  const year = Number(part(parts, "year"))
  const month = Number(part(parts, "month"))
  const day = Number(part(parts, "day"))
  if (!year || !month || !day) return null
  return { year, month, day }
}

/** Calendar arithmetic that does not depend on the machine's timezone. */
export function addCalendarDays(year: number, month: number, day: number, days: number): string {
  const next = new Date(Date.UTC(year, month - 1, day + days))
  const y = next.getUTCFullYear()
  const m = String(next.getUTCMonth() + 1).padStart(2, "0")
  const d = String(next.getUTCDate()).padStart(2, "0")
  return `${y}-${m}-${d}`
}

/** An exact minute stays. Any later instant moves to the next minute. */
export function roundUpMinute(date: Date): Date {
  return new Date(Math.ceil(date.getTime() / 60_000) * 60_000)
}

const WALL_TIME = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})(?::(\d{2})(\.\d{1,9})?)?$/

/** A datetime-local value is India Standard Time. Other text is left unchanged. */
export function istOffset(value: string): string {
  const trimmed = value.trim()
  const wall = WALL_TIME.exec(trimmed)
  if (!wall) return trimmed
  return `${wall[1]}T${wall[2]}:${wall[3] ?? "00"}${wall[4] ?? ""}+05:30`
}

export function istInstant(value: string): Date | null {
  const trimmed = value.trim()
  if (!trimmed) return null
  const date = new Date(WALL_TIME.test(trimmed) ? istOffset(trimmed) : trimmed)
  return Number.isNaN(date.getTime()) ? null : date
}

export function formatWhen(value: string | null | undefined): string {
  if (!value) return "Unknown time"
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(date)
}

export function formatDay(value: string | null | undefined): string {
  if (!value) return "open"
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const formatted = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short", timeZone: IST }).format(date)
  return `${formatted} IST`
}

export function formatWindow(from?: string | null, until?: string | null): string {
  if (!from && !until) return "No effective dates"
  if (!until) return `${formatDay(from)} onward`
  return `${formatDay(from)} – ${formatDay(until)}`
}

/** The current minute is still the present. An earlier minute is before current. */
export function isBeforeCurrentMinute(date: Date, now: Date): boolean {
  return Math.floor(date.getTime() / 60_000) < Math.floor(now.getTime() / 60_000)
}

/** A timestamp whose seconds and fraction are absent or zero. */
export function isWholeMinute(value: string): boolean {
  const match = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?$/.exec(value.trim())
  if (!match) return false
  if (match[4] && match[4] !== "00") return false
  if (match[5] && !/^\.0+$/.test(match[5])) return false
  return true
}

export function periodsOf(
  version: Pick<RuleVersion, "effectiveFrom" | "effectiveUntil" | "segments">,
): EffectiveSegment[] {
  if (version.segments && version.segments.length > 0) return version.segments
  if (!version.effectiveFrom && !version.effectiveUntil) return []
  return [{ effectiveFrom: version.effectiveFrom, effectiveUntil: version.effectiveUntil }]
}

/** One period reads as a single window. Several periods are joined, holes included. */
export function formatSchedule(version: Pick<RuleVersion, "effectiveFrom" | "effectiveUntil" | "segments">): string {
  const periods = periodsOf(version)
  if (periods.length === 0) return "No effective dates"
  if (periods.length === 1) return formatWindow(periods[0]?.effectiveFrom, periods[0]?.effectiveUntil)
  return periods.map((period) => formatWindow(period.effectiveFrom, period.effectiveUntil)).join(" · ")
}

export function orderedVersions(rule: Pick<RuleResponse, "versions">): RuleVersion[] {
  return [...(rule.versions ?? [])].sort((left, right) => left.versionNumber - right.versionNumber)
}

export function activeVersionOf(rule: Pick<RuleResponse, "activeVersion" | "versions">): RuleVersion | undefined {
  const versions = orderedVersions(rule)
  if (rule.activeVersion) {
    const named = versions.find((version) => version.version === rule.activeVersion)
    if (named) return named
  }
  return versions.find((version) => version.active)
}

export function latestVersion(rule: Pick<RuleResponse, "versions">): RuleVersion | undefined {
  return orderedVersions(rule).at(-1)
}

/** Accepts `v2`, `V2`, and `2`. Leading zeros and other text do not match a version. */
export function canonicalVersion(token: string | undefined | null): string | null {
  if (!token) return null
  const trimmed = token.trim()
  const prefixed = /^v(\d+)$/i.exec(trimmed)
  const bare = /^(\d+)$/.exec(trimmed)
  const digits = prefixed?.[1] ?? bare?.[1]
  if (!digits || (digits.length > 1 && digits.startsWith("0"))) return null
  const number = Number(digits)
  if (!Number.isInteger(number) || number < 1) return null
  return `v${number}`
}

export function findVersion(versions: readonly RuleVersion[], token: string | undefined): RuleVersion | undefined {
  const canonical = canonicalVersion(token)
  if (!canonical) return undefined
  return versions.find((version) => version.version.toLowerCase() === canonical)
}

/** A missing stage is a version stored before approval. It already counts as approved. */
export function isApprovedStage(version: { stage?: string | null }): boolean {
  const stage = version.stage?.trim()
  return !stage || stage === "APPROVED"
}

/** The version both covers this minute and has finished approval. */
export function runsNow(version: { active: boolean; stage?: string | null }): boolean {
  return version.active && isApprovedStage(version)
}

/**
 * Draft is the only editable stage. When the server omits the flag, a missing stage stays
 * editable so an older server does not lock the desk.
 */
export function isEditable(version: { editable?: boolean | null; stage?: string | null }): boolean {
  if (typeof version.editable === "boolean") return version.editable
  const stage = version.stage?.trim()
  return !stage || stage === "DRAFT"
}

export function stageText(version: { stage?: string | null; stageLabel?: string | null }): string {
  const label = version.stageLabel?.trim()
  if (label) return label
  if (isApprovedStage(version)) return "Approved"
  if (!version.stage || version.stage.trim() === "DRAFT") return "Draft"
  return version.stage.trim()
}

/** Uses the server marks. Without them, draws Draft and Approved around the current stage. */
export function approvalMarks(version: Pick<RuleVersion, "stage" | "stageLabel" | "stages">): StageMark[] {
  if (version.stages && version.stages.length > 0) return version.stages
  if (isApprovedStage(version)) {
    return [
      { name: "DRAFT", label: "Draft", status: "complete" },
      { name: "APPROVED", label: "Approved", status: "current" },
    ]
  }
  const stage = version.stage?.trim()
  if (!stage || stage === "DRAFT") {
    return [
      { name: "DRAFT", label: "Draft", status: "current" },
      { name: "APPROVED", label: "Approved", status: "upcoming" },
    ]
  }
  return [
    { name: "DRAFT", label: "Draft", status: "complete" },
    { name: stage, label: stageText(version), status: "current" },
    { name: "APPROVED", label: "Approved", status: "upcoming" },
  ]
}

const STAGE_NAME = /^[A-Za-z][A-Za-z0-9 ._-]*$/

/** Middle-stage names for a new approval path. Messages match the server. */
export function approvalStageErrors(raw: readonly string[]): string[] {
  const errors: string[] = []
  if (raw.length > 8) {
    errors.push("An approval path can have at most 8 stages between draft and approved.")
  }
  const seen = new Set<string>()
  raw.forEach((item, index) => {
    const label = `Stage ${index + 1}`
    const name = item.trim().replace(/\s+/g, " ")
    if (!name) {
      errors.push(`${label} needs a name.`)
      return
    }
    if (name.length > 40) {
      errors.push(`${label} must be 40 characters or fewer.`)
      return
    }
    if (!STAGE_NAME.test(name)) {
      errors.push(`${label} must start with a letter and use only letters, digits, spaces, periods, underscores, or hyphens.`)
      return
    }
    if (name.toLowerCase() === "draft" || name.toLowerCase() === "approved") {
      errors.push(`${label} cannot be named Draft or Approved.`)
      return
    }
    if (seen.has(name.toLowerCase())) {
      errors.push(`${label} repeats ${name}.`)
      return
    }
    seen.add(name.toLowerCase())
  })
  return errors
}

export function versionSummary(rule: Pick<RuleResponse, "versions">): string {
  const versions = rule.versions ?? []
  const count = versions.length
  const noun = `${count} ${count === 1 ? "version" : "versions"}`
  const running = versions.find((version) => runsNow(version))
  if (running) return `${noun} · ${running.version} running now`
  const pending = versions.find((version) => version.active && !isApprovedStage(version))
  if (pending) {
    return `${noun} · ${pending.version} is in ${stageText(pending)} and will not run until it is approved`
  }
  return `${noun} · none running now`
}

export function runNote(rule: Pick<RuleResponse, "activeVersion" | "versions">): string {
  const current = activeVersionOf(rule)
  if (current && runsNow(current)) {
    return `Running the rule uses ${current.version}, the approved version whose dates include this moment.`
  }
  if (current && current.active && !isApprovedStage(current)) {
    return `${current.version} is in ${stageText(current)} and will not run until it is approved.`
  }
  return "No version is active right now. Running the rule fails until a version's dates include this moment."
}
