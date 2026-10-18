import { useEffect, useMemo, useState, type FormEvent } from "react"
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom"
import { ApiError, api } from "../api"
import { Banner } from "../components/Banner"
import { OverlapConfirm } from "../components/OverlapConfirm"
import { PageHead } from "../components/PageHead"
import { PeriodFields } from "../components/PeriodFields"
import { LogicFields } from "../components/LogicFields"
import { VersionCalendar } from "../components/VersionCalendar"
import {
  buildRule,
  draftFromVersion,
  overlappingWindows,
  periodsOfSubmission,
  sanitizeNumber,
  suggestNextWindow,
  type DateOverlap,
  type RuleDraft,
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
  const [overlaps, setOverlaps] = useState<DateOverlap[] | null>(null)

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

  function updateDraft(recipe: (current: RuleDraft) => RuleDraft) {
    setDraft((current) => (current ? recipe(current) : current))
  }

  async function submit(event?: FormEvent, confirmed = false) {
    event?.preventDefault()
    if (!rule || !built.body) {
      setOverlaps(null)
      setProblems(built.errors)
      return
    }
    const hits = overlappingWindows(periodsOfSubmission(built.body), rule.versions ?? [], "")
    if (hits.length > 0 && !confirmed) {
      setProblems([])
      setOverlaps(hits)
      return
    }
    setOverlaps(null)
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
        A period that overlaps another version adjusts that version, after you confirm the overlapped window. A period strictly inside another version splits it, so the earlier version resumes afterwards.
      </p>
      {overlaps && overlaps.length > 0 ? (
        <OverlapConfirm
          hits={overlaps}
          pending={pending}
          confirmLabel={`Save v${nextNumber}`}
          onConfirm={() => void submit(undefined, true)}
          onCancel={() => setOverlaps(null)}
        />
      ) : null}
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
              onChange={(next) => {
                setOverlaps(null)
                setDraft(next)
              }}
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
          <LogicFields draft={draft} types={types ?? []} leavesByWhen={leavesByWhen} onChange={updateDraft} />
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
