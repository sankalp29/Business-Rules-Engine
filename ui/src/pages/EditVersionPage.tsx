import { useMemo, useState, type FormEvent } from "react"
import { Link, useNavigate, useParams } from "react-router-dom"
import { ApiError, api } from "../api"
import { Banner } from "../components/Banner"
import { LogicFields } from "../components/LogicFields"
import { buildLogic, draftFromVersion, sanitizeNumber, type RuleDraft } from "../drafts"
import { findVersion, isEditable, istInputValue, orderedVersions, ruleKey } from "../format"
import { useTypes } from "../hooks"
import { catalogOf, expandLeaves, type RuleResponse, type RuleVersion } from "../model"
import { useLoadedRule } from "./RuleFrame"

export function EditVersionPage() {
  const navigate = useNavigate()
  const params = useParams()
  const { rule, replace, routeKey } = useLoadedRule()
  const { types, error, loading } = useTypes()
  const version = findVersion(orderedVersions(rule), params.version)
  const token = version?.version ?? ""
  const loadedKey = `${routeKey}:${token}`
  const [draftKey, setDraftKey] = useState(loadedKey)
  const [draft, setDraft] = useState<RuleDraft | null>(() => (version ? logicDraft(rule, version) : null))
  const [problems, setProblems] = useState<string[]>([])
  const [pending, setPending] = useState(false)
  if (version && draftKey !== loadedKey) {
    setDraftKey(loadedKey)
    setDraft(logicDraft(rule, version))
    setProblems([])
  }
  const catalog = useMemo(() => catalogOf(types ?? []), [types])
  const leavesByWhen = useMemo(
    () => (draft?.whens ?? []).map((when) => expandLeaves(catalog.get(when.type)?.fields, catalog)),
    [catalog, draft?.whens],
  )
  const built = draft ? buildLogic(draft, leavesByWhen) : { body: null, errors: [] }

  if (!version) return null
  if (!isEditable(version)) {
    return (
      <div className="stack">
        <Banner
          tone="error"
          title="Version is not editable"
          details={["Logic and dates can be changed only while the version is in draft."]}
        />
      </div>
    )
  }
  if (!draft) return null

  function updateDraft(recipe: (current: RuleDraft) => RuleDraft) {
    setDraft((current) => (current ? recipe(current) : current))
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!built.body || !version) {
      setProblems(built.errors)
      return
    }
    setPending(true)
    setProblems([])
    try {
      await api.updateVersion(rule.code || ruleKey(rule), version.version, built.body)
      const next = await api.getRule(routeKey)
      replace(next)
      navigate(`/rules/${encodeURIComponent(ruleKey(next))}/versions/${encodeURIComponent(version.version)}`)
    } catch (caught) {
      const failure = caught instanceof ApiError ? caught : new ApiError("Could not save the version.")
      setProblems([failure.message, ...failure.details])
    } finally {
      setPending(false)
    }
  }

  return (
    <div className="stack">
      <p className="note">This save changes the description, salience, and logic. The effective dates stay as they are.</p>
      {error ? <Banner tone="error" title={error.message} details={error.details} /> : null}
      {loading ? <p className="note">Loading types…</p> : null}
      {problems.length > 0 ? <Banner tone="error" title="The version was not saved." details={problems} /> : null}
      <div className="author">
        <form className="stack" noValidate onSubmit={(event) => void submit(event)}>
          <section className="sheet stack">
            <div className="pair">
              <label className="field" htmlFor="edit-code">
                <span className="label-text">Code</span>
                <input id="edit-code" className="control mono-input" value={draft.code} readOnly />
              </label>
              <label className="field" htmlFor="edit-name">
                <span className="label-text">Name</span>
                <input id="edit-name" className="control" value={draft.name} readOnly />
              </label>
            </div>
            <label className="field" htmlFor="edit-description">
              <span className="label-text">Description</span>
              <textarea
                id="edit-description"
                className="control"
                rows={3}
                value={draft.description}
                onChange={(event) => setDraft((current) => (current ? { ...current, description: event.target.value } : current))}
              />
            </label>
            <label className="field salience" htmlFor="edit-salience">
              <span className="label-text">Salience</span>
              <input
                id="edit-salience"
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
              {pending ? "Saving…" : `Save ${version.version}`}
            </button>
            <Link className="ghost link-button" to={logicHref(rule, version)}>
              Cancel
            </Link>
          </div>
        </form>
        <aside className="preview">
          <section className="sheet code-sheet">
            <header className="sheet-head">
              <h2>Construction JSON</h2>
            </header>
            <p className="note">This is the document Save {version.version} will send. Dates are left out.</p>
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

function logicDraft(rule: RuleResponse, version: RuleVersion): RuleDraft {
  return draftFromVersion(rule, version, {
    effectiveFrom: istInputValue(version.effectiveFrom) || "2090-01-01T00:00:00",
    effectiveUntil: version.effectiveUntil ? istInputValue(version.effectiveUntil) || "2091-01-01T00:00:00" : "2091-01-01T00:00:00",
  })
}

function logicHref(rule: RuleResponse, version: RuleVersion): string {
  return `/rules/${encodeURIComponent(ruleKey(rule))}/versions/${encodeURIComponent(version.version)}`
}
