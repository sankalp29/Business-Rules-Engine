import { useState, type FormEvent } from "react"
import { ApiError, api } from "../api"
import {
  dateLocks,
  editEffectiveSegments,
  oneDayPeriod,
  overlappingWindows,
  periodsOfSubmission,
  suggestNextWindow,
  type DateOverlap,
  type SegmentEdit,
} from "../drafts"
import { istInputValue, istInstant } from "../format"
import { uid, type RuleResponse, type RuleVersion } from "../model"
import { Banner } from "./Banner"
import { OverlapConfirm } from "./OverlapConfirm"

type PeriodRow = SegmentEdit & { key: string }

function sourceSegments(version: RuleVersion): { effectiveFrom: string; effectiveUntil: string | null }[] {
  if (version.segments && version.segments.length > 0) return version.segments
  return [{ effectiveFrom: version.effectiveFrom, effectiveUntil: version.effectiveUntil }]
}

function rowFrom(segment: { effectiveFrom: string; effectiveUntil: string | null }, stored: boolean): PeriodRow {
  return {
    key: uid(),
    fromInput: istInputValue(segment.effectiveFrom),
    untilInput: segment.effectiveUntil ? istInputValue(segment.effectiveUntil) : "",
    storedFrom: stored ? segment.effectiveFrom : null,
    storedUntil: stored ? segment.effectiveUntil : null,
  }
}

export function EffectiveDatesForm({
  ruleKey,
  version,
  versions,
  onSaved,
}: {
  ruleKey: string
  version: RuleVersion
  versions: readonly RuleVersion[]
  onSaved: (rule: RuleResponse) => void
}) {
  const [rows, setRows] = useState<PeriodRow[]>(() => sourceSegments(version).map((segment) => rowFrom(segment, true)))
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const [overlaps, setOverlaps] = useState<DateOverlap[] | null>(null)
  const storedCount = sourceSegments(version).length
  const anyEditable = rows.some((row) => {
    const locks = dateLocks(row.storedFrom ?? "", row.storedUntil)
    return !locks.fromLocked || !locks.untilLocked
  })
  const showSave = anyEditable || rows.length !== storedCount

  function addPeriod() {
    setOverlaps(null)
    setRows((current) => {
      const last = current.at(-1)
      const instant = last?.untilInput ? istInstant(last.untilInput) : null
      const suggested = suggestNextWindow(instant ? instant.toISOString() : null)
      const period = suggested.effectiveFrom ? suggested : oneDayPeriod()
      return [...current, rowFrom(period, false)]
    })
  }

  function updateRow(key: string, patch: Partial<PeriodRow>) {
    setOverlaps(null)
    setRows((current) => current.map((item) => (item.key === key ? { ...item, ...patch } : item)))
  }

  async function submit(event?: FormEvent, confirmed = false) {
    event?.preventDefault()
    if (!showSave || saving) return
    const edited = editEffectiveSegments({ segments: rows })
    if (!edited.body) {
      setOverlaps(null)
      setError(new ApiError("The dates were not saved.", edited.errors))
      return
    }
    const hits = overlappingWindows(periodsOfSubmission(edited.body), versions, version.version)
    if (hits.length > 0 && !confirmed) {
      setError(null)
      setOverlaps(hits)
      return
    }
    setSaving(true)
    setError(null)
    setOverlaps(null)
    try {
      await api.changeDates(ruleKey, version.version, edited.body)
      onSaved(await api.getRule(ruleKey))
    } catch (caught: unknown) {
      setError(caught instanceof ApiError ? caught : new ApiError("The dates were not saved."))
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="sheet stack">
      <h2>Edit periods</h2>
      {error ? <Banner tone="error" title={error.message} details={error.details} /> : null}
      {overlaps && overlaps.length > 0 ? (
        <OverlapConfirm
          hits={overlaps}
          pending={saving}
          confirmLabel="Change dates"
          onConfirm={() => void submit(undefined, true)}
          onCancel={() => setOverlaps(null)}
        />
      ) : null}
      <form className="stack" noValidate onSubmit={(event) => void submit(event)}>
        <div className="period-list">
          {rows.map((row, index) => {
            const locks = dateLocks(row.storedFrom ?? "", row.storedUntil)
            const removable = rows.length > 1 && !locks.fromLocked && !locks.untilLocked
            return (
              <div className="period" key={row.key}>
                <div className="period-head">
                  <span>{rows.length > 1 ? `Period ${index + 1}` : "Effective period"}</span>
                  {removable ? (
                    <button
                      type="button"
                      className="ghost"
                      onClick={() => {
                        setOverlaps(null)
                        setRows((current) => current.filter((item) => item.key !== row.key))
                      }}
                    >
                      Remove
                    </button>
                  ) : null}
                </div>
                <div className="pair">
                  <label className="field" htmlFor={`version-edit-from-${index}`}>
                    <span className="label-text">Effective from</span>
                    <input
                      id={`version-edit-from-${index}`}
                      className="control"
                      type="datetime-local"
                      step={60}
                      value={row.fromInput}
                      disabled={locks.fromLocked}
                      aria-describedby="effective-dates-note"
                      onChange={(event) => updateRow(row.key, { fromInput: event.target.value })}
                    />
                  </label>
                  <label className="field" htmlFor={`version-edit-until-${index}`}>
                    <span className="label-text">Effective until</span>
                    <input
                      id={`version-edit-until-${index}`}
                      className="control"
                      type="datetime-local"
                      step={60}
                      value={row.untilInput}
                      disabled={locks.untilLocked}
                      aria-describedby="effective-dates-note"
                      onChange={(event) => updateRow(row.key, { untilInput: event.target.value })}
                    />
                  </label>
                </div>
              </div>
            )
          })}
        </div>
        <p className="note" id="effective-dates-note">
          Times are India Standard Time, to the minute. A start or end already before the current minute cannot change.
          The end must be later than the start. If these dates overlap another version, that version's dates are adjusted
          after you confirm the overlapped window. A period strictly inside another version splits it, so the earlier
          version resumes when this period ends.
        </p>
        <div className="actions">
          <button type="button" className="ghost" onClick={addPeriod}>
            Add period
          </button>
          <button
            type="button"
            className="ghost"
            onClick={() => {
              setOverlaps(null)
              setRows((current) => [...current, rowFrom(oneDayPeriod(), false)])
            }}
          >
            Add one-day period
          </button>
          {showSave ? (
            <button type="submit" className="primary" disabled={saving}>
              {saving ? "Saving dates…" : "Save dates"}
            </button>
          ) : null}
        </div>
      </form>
    </section>
  )
}
