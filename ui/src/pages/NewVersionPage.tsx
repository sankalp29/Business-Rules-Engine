import { useEffect, useMemo, useState, type FormEvent } from "react"
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom"
import { ApiError, api } from "../api"
import { Banner } from "../components/Banner"
import { PageHead } from "../components/PageHead"
import { PeriodFields } from "../components/PeriodFields"
import { VersionCalendar } from "../components/VersionCalendar"
import { ThenBlockEditor, WhenBlockEditor } from "../components/WhenThenEditors"
import {
  buildRule,
  draftFromVersion,
  emptyThen,
  emptyWhen,
  sanitizeNumber,
  suggestNextWindow,
  valueFieldOptions,
  type RuleDraft,
  type WhenDraft,
} from "../drafts"
import { ruleKey } from "../format"
import { useTypes } from "../hooks"
import { catalogOf, expandLeaves, type RuleResponse } from "../model"

export function NewVersionPage() {
  const navigate = useNavigate()
  const params = useParams()
  const [searchParams] = useSearchParams()
  const ruleKeyParam = params.key ?? ""
  const fromVersion = searchParams.get("from")
  const { types, error, loading } = useTypes()
  const [rule, setRule] = useState<RuleResponse | null>(null)
  const [draft, setDraft] = useState<RuleDraft | null>(null)
  const [problems, setProblems] = useState<string[]>([])
  const [pending, setPending] = useState(false)
  const [loadError, setLoadError] = useState<ApiError | null>(null)

  useEffect(() => {
    let cancelled = false
    setRule(null)
    setDraft(null)
    setProblems([])
    setLoadError(null)
    api
      .getRule(ruleKeyParam)
      .then((loaded) => {
        if (cancelled) return
        const versions = loaded.versions ?? []
        const source = versions.find((version) => version.version === fromVersion) ?? versions[versions.length - 1]
        if (!source) {
          setProblems(["This rule has no version to copy."])
          setRule(loaded)
          return
        }
        const latest = [...versions].sort((left, right) => left.versionNumber - right.versionNumber).at(-1)
        setDraft(draftFromVersion(loaded, source, suggestNextWindow(latest?.effectiveUntil)))
        setRule(loaded)
      })
      .catch((caught: unknown) => {
        if (!cancelled) setLoadError(caught instanceof ApiError ? caught : new ApiError("Could not load the rule."))
      })
    return () => {
      cancelled = true
    }
  }, [fromVersion, ruleKeyParam])

  const catalog = useMemo(() => catalogOf(types ?? []), [types])
  const leavesByWhen = useMemo(
    () => (draft?.whens ?? []).map((when) => expandLeaves(catalog.get(when.type)?.fields, catalog)),
    [catalog, draft?.whens],
  )
  const built = draft ? buildRule(draft, leavesByWhen) : { body: null, errors: [] }
  const bindings = [...new Set((draft?.whens ?? []).map((when) => when.binding.trim()).filter(Boolean))]

  function updateWhen(id: string, recipe: (when: WhenDraft) => WhenDraft) {
    setDraft((current) => {
      if (!current) return current
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
      if (!current || current.whens.length < 2) return current
      const whens = current.whens.filter((when) => when.id !== id)
      const alive = new Set(whens.map((when) => when.binding))
      const fallback = whens[0]?.binding ?? ""
      const thens = current.thens.map((then) => (alive.has(then.binding) ? then : { ...then, binding: fallback }))
      return { ...current, whens, thens }
    })
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!rule || !built.body) {
      setProblems(built.errors)
      return
    }
    setPending(true)
    setProblems([])
    try {
      const created = await api.createVersion(ruleKey(rule), built.body)
      navigate(`/rules/${encodeURIComponent(ruleKey(rule))}/versions/${encodeURIComponent(created.version)}`)
    } catch (caught) {
      const failure = caught instanceof ApiError ? caught : new ApiError("Could not save the version.")
      setProblems([failure.message, ...failure.details])
      setPending(false)
    }
  }

  if (loadError) {
    return (
      <div className="stack page">
        <PageHead eyebrow="Version" title="New version" lede="This rule could not be opened." />
        <Banner tone="error" title={loadError.message} details={loadError.details} />
        <Link className="back" to="/rules">
          All rules
        </Link>
      </div>
    )
  }

  if (!rule) {
    return (
      <div className="stack page">
        <PageHead eyebrow="Version" title="New version" lede="Loading the rule this version belongs to." />
        <p className="note">Loading rule…</p>
      </div>
    )
  }

  if (!draft) {
    return (
      <div className="stack page">
        <PageHead eyebrow="Version" title={rule.name || "New version"} lede="This rule has no version to copy." />
        <Banner tone="error" title="The version was not started." details={problems.length > 0 ? problems : ["This rule has no version to copy."]} />
        <Link className="back" to={`/rules/${encodeURIComponent(ruleKey(rule))}`}>
          {rule.name || "Rule"}
        </Link>
      </div>
    )
  }

  const versions = rule.versions ?? []
  const nextNumber = versions.reduce((highest, version) => Math.max(highest, version.versionNumber), 0) + 1

  return (
    <div className="stack page">
      <Link className="back" to={`/rules/${encodeURIComponent(ruleKey(rule))}`}>
        {rule.name || "Rule"}
      </Link>
      <PageHead
        eyebrow={`Version v${nextNumber}`}
        title={rule.name || "Untitled rule"}
        lede="Code and name stay the same. This version can use different when and then logic, and it runs during the India Standard Time periods below."
      />
      {error ? <Banner tone="error" title={error.message} details={error.details} /> : null}
      {loading ? <p className="note">Loading types…</p> : null}
      {problems.length > 0 ? <Banner tone="error" title="The version was not saved." details={problems} /> : null}
      {versions.length === 0 ? <p className="note">No versions yet.</p> : <VersionCalendar versions={versions} ruleKey={ruleKey(rule)} />}
      <p className="note">
        A period that sits strictly inside another version splits that version, so the earlier version resumes after it. A partial overlap is rejected.
      </p>
      <div className="author">
        <form className="stack" noValidate onSubmit={(event) => void submit(event)}>
          <section className="sheet stack">
            <div className="pair">
              <label className="field" htmlFor="version-code">
                <span className="label-text">Code</span>
                <input id="version-code" className="control mono-input" value={draft.code} readOnly />
              </label>
              <label className="field" htmlFor="version-name">
                <span className="label-text">Name</span>
                <input id="version-name" className="control" value={draft.name} readOnly />
              </label>
            </div>
            <label className="field" htmlFor="version-description">
              <span className="label-text">Description</span>
              <textarea
                id="version-description"
                className="control"
                rows={3}
                value={draft.description}
                onChange={(event) => setDraft((current) => (current ? { ...current, description: event.target.value } : current))}
              />
            </label>
            <PeriodFields
              idPrefix="version"
              draft={draft}
              describedBy="version-dates-note"
              onChange={(next) => setDraft(next)}
            />
            <p className="note" id="version-dates-note">
              Add a period when this version should pause and resume. Add a one-day period for a flash, from 00:00 to 23:59. If that day sits inside the long period on this form, remove the long period or move the day outside it.
            </p>
            <label className="field salience" htmlFor="version-salience">
              <span className="label-text">Salience</span>
              <input
                id="version-salience"
                className="control"
                inputMode="numeric"
                value={draft.salience}
                placeholder="Optional"
                onChange={(event) =>
                  setDraft((current) => (current ? { ...current, salience: sanitizeNumber(event.target.value, true) } : current))
                }
              />
            </label>
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
          <button type="button" className="ghost" onClick={() => setDraft((current) => (current ? { ...current, whens: [...current.whens, emptyWhen()] } : current))}>
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
                  setDraft((current) =>
                    current ? { ...current, thens: current.thens.map((item) => (item.id === block.id ? next : item)) } : current,
                  )
                }
                onRemove={() =>
                  setDraft((current) => (current ? { ...current, thens: current.thens.filter((item) => item.id !== block.id) } : current))
                }
              />
            )
          })}
          <button
            type="button"
            className="ghost"
            onClick={() => setDraft((current) => (current ? { ...current, thens: [...current.thens, emptyThen(bindings[0] ?? "")] } : current))}
          >
            Add then block
          </button>
          <div className="actions">
            <button type="submit" className="primary" disabled={pending || loading}>
              {pending ? "Saving…" : `Save v${nextNumber}`}
            </button>
          </div>
        </form>
        <aside className="preview">
          <section className="sheet code-sheet">
            <header className="sheet-head">
              <h2>Construction JSON</h2>
            </header>
            <p className="note">This is the document Save v{nextNumber} will send.</p>
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
