import { useMemo, useState, type FormEvent } from "react"
import { Link, useNavigate } from "react-router-dom"
import { ApiError, api } from "../api"
import { Banner } from "../components/Banner"
import { PageHead } from "../components/PageHead"
import { PeriodFields } from "../components/PeriodFields"
import { ThenBlockEditor, WhenBlockEditor } from "../components/WhenThenEditors"
import {
  buildRule,
  emptyRule,
  emptyThen,
  emptyWhen,
  sanitizeNumber,
  valueFieldOptions,
  type RuleDraft,
  type WhenDraft,
} from "../drafts"
import { ruleKey } from "../format"
import { useTypes } from "../hooks"
import { catalogOf, expandLeaves } from "../model"

export function CreateRulePage() {
  const navigate = useNavigate()
  const { types, error, loading } = useTypes()
  const [draft, setDraft] = useState<RuleDraft>(emptyRule())
  const [problems, setProblems] = useState<string[]>([])
  const [pending, setPending] = useState(false)
  const catalog = useMemo(() => catalogOf(types ?? []), [types])
  const leavesByWhen = useMemo(
    () => draft.whens.map((when) => expandLeaves(catalog.get(when.type)?.fields, catalog)),
    [catalog, draft.whens],
  )
  const built = buildRule(draft, leavesByWhen)
  const bindings = [...new Set(draft.whens.map((when) => when.binding.trim()).filter(Boolean))]

  function updateWhen(id: string, recipe: (when: WhenDraft) => WhenDraft) {
    setDraft((current) => {
      const previous = current.whens.find((when) => when.id === id)
      if (!previous) return current
      const nextWhen = recipe(previous)
      const whens = current.whens.map((when) => (when.id === id ? nextWhen : when))
      if (previous.binding === nextWhen.binding) return { ...current, whens }
      const thens = current.thens.map((then) =>
        then.binding === previous.binding ? { ...then, binding: nextWhen.binding } : then,
      )
      return { ...current, whens, thens }
    })
  }

  function removeWhen(id: string) {
    setDraft((current) => {
      if (current.whens.length < 2) return current
      const whens = current.whens.filter((when) => when.id !== id)
      const alive = new Set(whens.map((when) => when.binding))
      const fallback = whens[0]?.binding ?? ""
      const thens = current.thens.map((then) => (alive.has(then.binding) ? then : { ...then, binding: fallback }))
      return { ...current, whens, thens }
    })
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!built.body) {
      setProblems(built.errors)
      return
    }
    setPending(true)
    setProblems([])
    try {
      const created = await api.createRule(built.body)
      navigate(`/rules/${encodeURIComponent(ruleKey(created))}`)
    } catch (caught) {
      const failure = caught instanceof ApiError ? caught : new ApiError("Could not save the rule.")
      setProblems([failure.message, ...failure.details])
      setPending(false)
    }
  }

  if (!loading && !error && (types ?? []).length === 0) {
    return (
      <div className="stack page">
        <Link className="back" to="/rules">
          All rules
        </Link>
        <PageHead eyebrow="Rule" title="New rule" lede="Say when the rule matches, what it changes, and when version v1 is active in India Standard Time." />
        <section className="sheet stack">
          <h2>No types yet</h2>
          <p>Save a type before authoring a rule. A when block names a type that already exists.</p>
          <Link className="primary link-button" to="/types/new">
            New type
          </Link>
        </section>
      </div>
    )
  }

  return (
    <div className="stack page">
      <Link className="back" to="/rules">
        All rules
      </Link>
      <PageHead eyebrow="Rule" title="New rule" lede="Say when the rule matches, what it changes, and when version v1 is active in India Standard Time." />
      {error ? <Banner tone="error" title={error.message} details={error.details} /> : null}
      {loading ? <p className="note">Loading types…</p> : null}
      {problems.length > 0 ? <Banner tone="error" title="The rule was not saved." details={problems} /> : null}
      <div className="author">
        <form className="stack" noValidate onSubmit={(event) => void submit(event)}>
          <section className="sheet stack">
            <div className="pair">
              <label className="field" htmlFor="rule-code">
                <span className="label-text">Code</span>
                <input
                  id="rule-code"
                  className="control mono-input"
                  value={draft.code}
                  autoComplete="off"
                  spellCheck={false}
                  placeholder="senior-discount"
                  onChange={(event) => setDraft((current) => ({ ...current, code: event.target.value }))}
                />
              </label>
              <label className="field" htmlFor="rule-name">
                <span className="label-text">Name</span>
                <input
                  id="rule-name"
                  className="control"
                  value={draft.name}
                  autoComplete="off"
                  placeholder="Apply senior discount"
                  onChange={(event) => setDraft((current) => ({ ...current, name: event.target.value }))}
                />
              </label>
            </div>
            <label className="field" htmlFor="rule-description">
              <span className="label-text">Description</span>
              <textarea
                id="rule-description"
                className="control"
                rows={3}
                value={draft.description}
                onChange={(event) => setDraft((current) => ({ ...current, description: event.target.value }))}
              />
            </label>
            <PeriodFields idPrefix="rule" draft={draft} describedBy="rule-dates-note" onChange={setDraft} />
            <label className="field salience" htmlFor="rule-salience">
              <span className="label-text">Salience</span>
              <input
                id="rule-salience"
                className="control"
                inputMode="numeric"
                value={draft.salience}
                placeholder="Optional"
                onChange={(event) =>
                  setDraft((current) => ({ ...current, salience: sanitizeNumber(event.target.value, true) }))
                }
              />
            </label>
            <p className="note" id="rule-dates-note">
              Code starts with a letter. This save creates v1. Later versions keep this code and name, and can use different when and then logic. Times are India Standard Time, to the minute. The start and the end cannot be before the current minute, and the end must be later than the start. Add another period when this version should pause and resume. A one-day period runs from 00:00 to 23:59.
            </p>
          </section>
          {draft.whens.map((when, index) => (
            <WhenBlockEditor
              key={when.id}
              index={index}
              when={when}
              types={types ?? []}
              leaves={leavesByWhen[index] ?? []}
              valueFields={valueFieldOptions(
                leavesByWhen[index] ?? [],
                draft.whens.slice(0, index).map((block, blockIndex) => ({
                  binding: block.binding,
                  leaves: leavesByWhen[blockIndex] ?? [],
                })),
              )}
              canRemove={draft.whens.length > 1}
              onChange={(next) => updateWhen(when.id, () => next)}
              onRemove={() => removeWhen(when.id)}
            />
          ))}
          <button type="button" className="ghost" onClick={() => setDraft((current) => ({ ...current, whens: [...current.whens, emptyWhen()] }))}>
            Add when block
          </button>
          {draft.thens.map((block, index) => {
            const whenIndex = draft.whens.findIndex((when) => when.binding.trim() === block.binding.trim())
            return (
              <ThenBlockEditor
                key={block.id}
                index={index}
                block={block}
                bindings={bindings}
                leaves={whenIndex >= 0 ? leavesByWhen[whenIndex] ?? [] : []}
                canRemove={draft.thens.length > 1}
                onChange={(next) =>
                  setDraft((current) => ({
                    ...current,
                    thens: current.thens.map((item) => (item.id === block.id ? next : item)),
                  }))
                }
                onRemove={() =>
                  setDraft((current) => ({ ...current, thens: current.thens.filter((item) => item.id !== block.id) }))
                }
              />
            )
          })}
          <button
            type="button"
            className="ghost"
            onClick={() => setDraft((current) => ({ ...current, thens: [...current.thens, emptyThen(bindings[0] ?? "")] }))}
          >
            Add then block
          </button>
          <div className="actions">
            <button type="submit" className="primary" disabled={pending || loading}>
              {pending ? "Saving…" : "Save rule"}
            </button>
          </div>
        </form>
        <aside className="preview">
          <section className="sheet code-sheet">
            <header className="sheet-head">
              <h2>Construction JSON</h2>
            </header>
            <p className="note">This is the document Save rule will send.</p>
            {built.body ? (
              <pre className="source">{JSON.stringify(built.body, null, 2)}</pre>
            ) : (
              <ul className="problems">
                {built.errors.map((item, index) => (
                  <li key={`${item}-${index}`}>{item}</li>
                ))}
              </ul>
            )}
          </section>
        </aside>
      </div>
    </div>
  )
}
