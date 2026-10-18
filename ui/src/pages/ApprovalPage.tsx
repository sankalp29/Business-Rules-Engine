import { useEffect, useState, type FormEvent } from "react"
import { ApiError, api } from "../api"
import { Banner } from "../components/Banner"
import { PageHead } from "../components/PageHead"
import { approvalStageErrors, formatWhen } from "../format"
import { uid, type ApprovalWorkflow } from "../model"

type StageRow = { id: string; name: string }

export function ApprovalPage() {
  const [workflows, setWorkflows] = useState<ApprovalWorkflow[] | null>(null)
  const [error, setError] = useState<ApiError | null>(null)
  const [rows, setRows] = useState<StageRow[]>([])
  const [problems, setProblems] = useState<string[]>([])
  const [pending, setPending] = useState(false)

  function load() {
    return api.listWorkflows()
  }

  useEffect(() => {
    let cancelled = false
    load()
      .then((next) => {
        if (!cancelled) setWorkflows(next)
      })
      .catch((caught: unknown) => {
        if (!cancelled) setError(caught instanceof ApiError ? caught : new ApiError("Could not load approval paths."))
      })
    return () => {
      cancelled = true
    }
  }, [])

  const active = workflows?.find((workflow) => workflow.active) ?? workflows?.[0]

  function move(index: number, delta: number) {
    setRows((current) => {
      const target = index + delta
      if (target < 0 || target >= current.length) return current
      const next = current.slice()
      const [row] = next.splice(index, 1)
      if (!row) return current
      next.splice(target, 0, row)
      return next
    })
  }

  async function publish(event: FormEvent) {
    event.preventDefault()
    const names = rows.map((row) => row.name.trim().replace(/\s+/g, " "))
    const found = approvalStageErrors(names)
    if (found.length > 0) {
      setProblems(found)
      return
    }
    setPending(true)
    setProblems([])
    setError(null)
    try {
      await api.publishWorkflow(names)
      setRows([])
      setWorkflows(await load())
    } catch (caught) {
      const failure = caught instanceof ApiError ? caught : new ApiError("The approval path was not saved.")
      setProblems([failure.message, ...failure.details])
    } finally {
      setPending(false)
    }
  }

  return (
    <div className="stack page">
      <PageHead
        eyebrow="Desk"
        title="Approval"
        lede="New rules and new versions follow the active path. A version already in progress keeps the path it started with."
      />
      {error ? <Banner tone="error" title={error.message} details={error.details} /> : null}
      {workflows === null && !error ? <p className="note">Loading approval paths…</p> : null}
      {active ? (
        <section className="sheet stack">
          <header className="sheet-head">
            <h2>Active path</h2>
            <span className="mono quiet">{active.version}</span>
          </header>
          <PathRail labels={active.stages} />
        </section>
      ) : null}
      <form className="sheet stack" onSubmit={(event) => void publish(event)}>
        <header className="sheet-head">
          <h2>Publish the next path</h2>
        </header>
        <p className="note">
          Draft stays first and Approved stays last. Name the stages between them, in the order a version must pass. Leave the middle empty when one approval is enough.
        </p>
        {problems.length > 0 ? <Banner tone="error" title="Invalid approval path" details={problems} /> : null}
        <ol className="path-rail" aria-label="Next approval path">
          <li className="locked">
            <span className="path-mark" aria-hidden="true" />
            <div className="path-step">
              <span className="path-end">Draft</span>
              <span className="quiet">Logic and dates can still change</span>
            </div>
          </li>
          {rows.map((row, index) => (
            <li key={row.id}>
              <span className="path-mark" aria-hidden="true" />
              <div className="path-step">
                <label className="field" htmlFor={`stage-${row.id}`}>
                  <span className="label-text">Stage {index + 1}</span>
                  <input
                    id={`stage-${row.id}`}
                    className="control"
                    value={row.name}
                    maxLength={40}
                    onChange={(event) =>
                      setRows((current) => current.map((item) => (item.id === row.id ? { ...item, name: event.target.value } : item)))
                    }
                  />
                </label>
                <div className="row">
                  <button type="button" className="ghost" onClick={() => move(index, -1)} disabled={index === 0}>
                    Move up
                  </button>
                  <button type="button" className="ghost" onClick={() => move(index, 1)} disabled={index === rows.length - 1}>
                    Move down
                  </button>
                  <button type="button" className="ghost" onClick={() => setRows((current) => current.filter((item) => item.id !== row.id))}>
                    Remove
                  </button>
                </div>
              </div>
            </li>
          ))}
          <li className="locked">
            <span className="path-mark" aria-hidden="true" />
            <div className="path-step">
              <span className="path-end">Approved</span>
              <span className="quiet">The version can run</span>
            </div>
          </li>
        </ol>
        <div className="actions">
          <button
            type="button"
            className="ghost"
            onClick={() => setRows((current) => (current.length >= 8 ? current : [...current, { id: uid(), name: "" }]))}
            disabled={rows.length >= 8}
          >
            Add a stage
          </button>
          <button type="submit" className="primary" disabled={pending}>
            {pending ? "Publishing…" : "Publish path"}
          </button>
        </div>
      </form>
      {workflows && workflows.length > 0 ? (
        <section className="stack">
          <h2 className="path-list-title">Paths</h2>
          {workflows.map((workflow) => (
            <article key={workflow.id} className="sheet stack">
              <header className="sheet-head">
                <h3 className="mono">{workflow.version}</h3>
                {workflow.active ? <span className="pill">Active</span> : <span className="quiet">Earlier path</span>}
              </header>
              <PathRail labels={workflow.stages} />
              <p className="quiet">{formatWhen(workflow.createdAt)}</p>
            </article>
          ))}
        </section>
      ) : null}
    </div>
  )
}

function PathRail({ labels }: { labels: string[] }) {
  return (
    <ol className="path-rail">
      {labels.map((label) => {
        const locked = label === "Draft" || label === "Approved"
        return (
          <li key={label} className={locked ? "locked" : undefined}>
            <span className="path-mark" aria-hidden="true" />
            <span className={locked ? "path-end" : undefined}>{label}</span>
          </li>
        )
      })}
    </ol>
  )
}
