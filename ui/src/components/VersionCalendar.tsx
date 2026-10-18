import { useState, type CSSProperties } from "react"
import { Link } from "react-router-dom"
import {
  daySlices,
  monthCells,
  monthTitle,
  openingMonth,
  shiftMonth,
  sliceLabel,
  spansOf,
  versionColor,
  type DaySlice,
} from "../calendar"
import { istCalendarDate } from "../format"
import type { RuleVersion } from "../model"

const WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]

export function VersionCalendar({
  versions,
  ruleKey,
  focusVersion,
}: {
  versions: RuleVersion[]
  ruleKey: string
  focusVersion?: string | null
}) {
  const spans = spansOf(versions)
  const [cursor, setCursor] = useState(() => openingMonth(spans, focusVersion))
  const title = monthTitle(cursor.year, cursor.month)
  const today = istCalendarDate(new Date())
  const cells = monthCells(cursor.year, cursor.month)

  return (
    <section className="sheet calendar" aria-label="Effective dates">
      <div className="calendar-toolbar">
        <button type="button" className="ghost" onClick={() => setCursor((current) => shiftMonth(current.year, current.month, -1))}>
          Previous
        </button>
        <h2>{title}</h2>
        <div className="calendar-moves">
          <button
            type="button"
            className="ghost"
            onClick={() => {
              const now = istCalendarDate(new Date())
              if (now) setCursor({ year: now.year, month: now.month })
            }}
          >
            Today
          </button>
          <button type="button" className="ghost" onClick={() => setCursor((current) => shiftMonth(current.year, current.month, 1))}>
            Next
          </button>
        </div>
      </div>
      <ul className="legend">
        {versions.map((version) => {
          const color = versionColor(version.versionNumber)
          return (
            <li key={version.version}>
              <Link
                to={versionHref(ruleKey, version.version)}
                style={paint(color.ink, color.wash)}
              >
                <span className="swatch" aria-hidden="true" />
                <span className="mono">{version.version}</span>
                {version.active ? <span className="pill">Active</span> : null}
              </Link>
            </li>
          )
        })}
      </ul>
      <div className="calendar-board">
        <div className="calendar-weekdays" aria-hidden="true">
          {WEEKDAYS.map((day) => (
            <span key={day}>{day}</span>
          ))}
        </div>
        <div key={`${cursor.year}-${cursor.month}`} className="calendar-grid" aria-label={`${title}, India Standard Time`}>
        {cells.map((day, index) => {
          if (day == null) return <div key={`blank-${index}`} className="day blank" />
          const slices = daySlices(spans, cursor.year, cursor.month, day)
          const full = slices.length === 1 && slices[0]?.fullDay ? slices[0] : null
          const isToday = today?.year === cursor.year && today.month === cursor.month && today.day === day
          const label = daySummary(title, day, slices)
          if (full) {
            const color = versionColor(full.versionNumber)
            const dim = Boolean(focusVersion) && full.version !== focusVersion
            const focused = Boolean(focusVersion) && full.version === focusVersion
            return (
              <Link
                key={day}
                className={cellClass({ full: true, today: isToday, dim, focused })}
                to={versionHref(ruleKey, full.version)}
                style={paint(color.ink, color.wash)}
                aria-label={label}
              >
                <span className="day-num">{day}</span>
                <span className="day-version">{full.version}</span>
              </Link>
            )
          }
          return (
            <div key={day} className={cellClass({ full: false, today: isToday, dim: false, focused: false })} aria-label={slices.length === 0 ? label : undefined}>
              <span className="day-num">{day}</span>
              {slices.map((slice) => {
                const color = versionColor(slice.versionNumber)
                const dim = Boolean(focusVersion) && slice.version !== focusVersion
                const focused = Boolean(focusVersion) && slice.version === focusVersion
                return (
                  <Link
                    key={`${slice.version}-${slice.fromMinute}`}
                    className={bandClass(dim, focused)}
                    to={versionHref(ruleKey, slice.version)}
                    style={paint(color.ink, color.wash)}
                    aria-label={sliceLabel(slice)}
                  >
                    <span className="band-version">{slice.version}</span>
                    <span className="band-time">{bandTime(slice)}</span>
                  </Link>
                )
              })}
            </div>
          )
        })}
        </div>
      </div>
      <p className="note">Days and times are India Standard Time. A shared day lists each version with its hours.</p>
    </section>
  )
}

function cellClass({ full, today, dim, focused }: { full: boolean; today: boolean; dim: boolean; focused: boolean }): string {
  return ["day", full ? "full" : "", today ? "today" : "", dim ? "dim" : "", focused ? "focus" : ""].filter(Boolean).join(" ")
}

function bandClass(dim: boolean, focused: boolean): string {
  return ["band", dim ? "dim" : "", focused ? "focus" : ""].filter(Boolean).join(" ")
}

function bandTime(slice: DaySlice): string {
  if (slice.fromMinute === slice.untilMinute) return slice.fromMinute
  return `${slice.fromMinute}–${slice.untilMinute}`
}

function versionHref(ruleKey: string, version: string): string {
  return `/rules/${encodeURIComponent(ruleKey)}/versions/${encodeURIComponent(version)}`
}

function paint(ink: string, wash: string): CSSProperties {
  return { "--mark": ink, "--wash": wash, "--swatch": ink } as CSSProperties
}

function daySummary(title: string, day: number, slices: DaySlice[]): string {
  const when = slices.length === 0 ? "no version" : slices.map((slice) => sliceLabel(slice).replace(" · ", " ")).join(", ")
  return `${day} ${title}, ${when}`
}
