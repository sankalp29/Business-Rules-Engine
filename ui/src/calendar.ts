import { istCalendarDate, istClock, periodsOf } from "./format"
import type { RuleVersion } from "./model"

export type VersionSpan = {
  version: string
  versionNumber: number
  active: boolean
  segments: { effectiveFrom: string; effectiveUntil: string | null }[]
}

export type DaySlice = {
  version: string
  versionNumber: number
  active: boolean
  fromMinute: string
  untilMinute: string
  fullDay: boolean
}

export const VERSION_PALETTE = [
  { id: "navy", ink: "#243656", wash: "#e6ebf2" },
  { id: "forest", ink: "#1e4636", wash: "#e5eeea" },
  { id: "bronze", ink: "#5c4630", wash: "#f1ebe3" },
  { id: "plum", ink: "#4e3a4c", wash: "#eee8ed" },
  { id: "wine", ink: "#5c3333", wash: "#f3e8e8" },
  { id: "steel", ink: "#2c4550", wash: "#e6eef1" },
] as const

export function versionColor(versionNumber: number) {
  const index = ((versionNumber - 1) % VERSION_PALETTE.length + VERSION_PALETTE.length) % VERSION_PALETTE.length
  return VERSION_PALETTE[index] ?? VERSION_PALETTE[0]
}

export function spansOf(versions: readonly RuleVersion[]): VersionSpan[] {
  return versions.map((version) => ({
    version: version.version,
    versionNumber: version.versionNumber,
    active: version.active,
    segments: periodsOf(version),
  }))
}

export function monthCells(year: number, month: number): Array<number | null> {
  const first = new Date(Date.UTC(year, month - 1, 1))
  const lead = (first.getUTCDay() + 6) % 7
  const days = new Date(Date.UTC(year, month, 0)).getUTCDate()
  const cells: Array<number | null> = Array.from({ length: lead }, () => null)
  for (let day = 1; day <= days; day += 1) cells.push(day)
  while (cells.length % 7 !== 0) cells.push(null)
  return cells
}

export function shiftMonth(year: number, month: number, delta: number): { year: number; month: number } {
  const next = new Date(Date.UTC(year, month - 1 + delta, 1))
  return { year: next.getUTCFullYear(), month: next.getUTCMonth() + 1 }
}

export function monthTitle(year: number, month: number): string {
  return new Intl.DateTimeFormat("en-GB", { month: "long", year: "numeric", timeZone: "UTC" }).format(
    new Date(Date.UTC(year, month - 1, 1)),
  )
}

/** The month that contains the focused version, otherwise the earliest dated version, otherwise today. */
export function openingMonth(versions: readonly VersionSpan[], focusVersion?: string | null, now = new Date()): { year: number; month: number } {
  const preferred = versions.find((version) => version.version === focusVersion)
  const ordered = preferred ? [preferred, ...versions.filter((version) => version !== preferred)] : versions
  for (const version of ordered) {
    for (const segment of version.segments) {
      const date = istCalendarDate(new Date(segment.effectiveFrom))
      if (date && date.year >= 1980) return { year: date.year, month: date.month }
    }
  }
  const today = istCalendarDate(now)
  if (today) return { year: today.year, month: today.month }
  return { year: now.getUTCFullYear(), month: now.getUTCMonth() + 1 }
}

function dayBounds(year: number, month: number, day: number): { start: number; end: number } {
  const monthText = String(month).padStart(2, "0")
  const dayText = String(day).padStart(2, "0")
  return {
    start: new Date(`${year}-${monthText}-${dayText}T00:00:00+05:30`).getTime(),
    end: new Date(`${year}-${monthText}-${dayText}T23:59:00+05:30`).getTime(),
  }
}

/** Inclusive India Standard Time coverage of one calendar day, earliest start first. */
export function daySlices(versions: readonly VersionSpan[], year: number, month: number, day: number): DaySlice[] {
  const { start, end } = dayBounds(year, month, day)
  const slices: DaySlice[] = []
  for (const version of versions) {
    for (const segment of version.segments) {
      const from = new Date(segment.effectiveFrom).getTime()
      if (Number.isNaN(from) || from > end) continue
      const until = segment.effectiveUntil == null ? null : new Date(segment.effectiveUntil).getTime()
      if (until != null && (Number.isNaN(until) || until < start)) continue
      const sliceStart = Math.max(from, start)
      const sliceEnd = until == null ? end : Math.min(until, end)
      if (sliceStart > sliceEnd) continue
      slices.push({
        version: version.version,
        versionNumber: version.versionNumber,
        active: version.active,
        fromMinute: istClock(new Date(sliceStart)),
        untilMinute: istClock(new Date(sliceEnd)),
        fullDay: sliceStart === start && sliceEnd === end,
      })
    }
  }
  slices.sort((left, right) => left.fromMinute.localeCompare(right.fromMinute) || left.versionNumber - right.versionNumber)
  return slices
}

export function sliceLabel(slice: DaySlice): string {
  if (slice.fullDay) return slice.version
  if (slice.fromMinute === slice.untilMinute) return `${slice.version} · ${slice.fromMinute}`
  return `${slice.version} · ${slice.fromMinute}–${slice.untilMinute}`
}
