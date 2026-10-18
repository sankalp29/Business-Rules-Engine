import { istInstant } from "../format"
import { oneDayPeriod, suggestNextWindow, type PeriodDraft, type RuleDraft } from "../drafts"

/**
 * The periods of a new rule or version. A one-day period is 00:00–23:59 India Standard Time.
 * Removing is available once another period remains.
 */
export function PeriodFields({
  idPrefix,
  draft,
  describedBy,
  onChange,
}: {
  idPrefix: string
  draft: RuleDraft
  describedBy?: string
  onChange: (draft: RuleDraft) => void
}) {
  const extras = draft.extraSegments ?? []
  const periods: PeriodDraft[] = [
    { effectiveFrom: draft.effectiveFrom, effectiveUntil: draft.effectiveUntil },
    ...extras,
  ]

  function commit(next: PeriodDraft[]) {
    const first = next[0] ?? { effectiveFrom: "", effectiveUntil: "" }
    onChange({
      ...draft,
      effectiveFrom: first.effectiveFrom,
      effectiveUntil: first.effectiveUntil,
      extraSegments: next.slice(1),
    })
  }

  function update(index: number, patch: Partial<PeriodDraft>) {
    commit(periods.map((period, periodIndex) => (periodIndex === index ? { ...period, ...patch } : period)))
  }

  function addPeriod() {
    const last = periods.at(-1)
    const instant = last ? istInstant(last.effectiveUntil) : null
    const suggested = suggestNextWindow(instant ? instant.toISOString() : null)
    const period = suggested.effectiveFrom ? suggested : oneDayPeriod()
    commit([...periods, period])
  }

  return (
    <div className="stack">
      <div className="period-list">
        {periods.map((period, index) => (
          <div className="period" key={`${idPrefix}-period-${index}`}>
            <div className="period-head">
              <span>{periods.length > 1 ? `Period ${index + 1}` : "Effective period"}</span>
              {periods.length > 1 ? (
                <button type="button" className="ghost" onClick={() => commit(periods.filter((_, periodIndex) => periodIndex !== index))}>
                  Remove
                </button>
              ) : null}
            </div>
            <div className="pair">
              <label className="field" htmlFor={`${idPrefix}-from-${index}`}>
                <span className="label-text">Effective from</span>
                <input
                  id={`${idPrefix}-from-${index}`}
                  className="control"
                  type="datetime-local"
                  step={60}
                  value={period.effectiveFrom}
                  aria-describedby={describedBy}
                  onChange={(event) => update(index, { effectiveFrom: event.target.value })}
                />
              </label>
              <label className="field" htmlFor={`${idPrefix}-until-${index}`}>
                <span className="label-text">Effective until</span>
                <input
                  id={`${idPrefix}-until-${index}`}
                  className="control"
                  type="datetime-local"
                  step={60}
                  value={period.effectiveUntil}
                  aria-describedby={describedBy}
                  onChange={(event) => update(index, { effectiveUntil: event.target.value })}
                />
              </label>
            </div>
          </div>
        ))}
      </div>
      <div className="actions">
        <button type="button" className="ghost" onClick={addPeriod}>
          Add period
        </button>
        <button type="button" className="ghost" onClick={() => commit([...periods, oneDayPeriod()])}>
          Add one-day period
        </button>
      </div>
    </div>
  )
}
