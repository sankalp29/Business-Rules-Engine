import { useEffect, useState } from "react"
import { Link } from "react-router-dom"
import { ApiError, api } from "../api"
import { Banner } from "../components/Banner"
import { PageHead } from "../components/PageHead"
import { activeVersionOf, formatSchedule, formatWhen, latestVersion, ruleKey } from "../format"
import type { RuleDefinition, RuleResponse } from "../model"

export function RulesPage() {
  const [rules, setRules] = useState<RuleResponse[] | null>(null)
  const [error, setError] = useState<ApiError | null>(null)
  const [query, setQuery] = useState("")

  useEffect(() => {
    let cancelled = false
    api
      .listRules()
      .then((next) => {
        if (!cancelled) setRules(next)
      })
      .catch((caught: unknown) => {
        if (!cancelled) setError(caught instanceof ApiError ? caught : new ApiError("Could not load rules."))
      })
    return () => {
      cancelled = true
    }
  }, [])

  const shown = (rules ?? []).filter((rule) => {
    const haystack = [
      rule.name,
      rule.code,
      rule.description,
      ...(rule.versions ?? []).flatMap((version) => [version.version, version.description]),
    ]
      .join(" ")
      .toLowerCase()
    return haystack.includes(query.trim().toLowerCase())
  })

  return (
    <div className="stack page">
      <PageHead
        eyebrow="Docket"
        title="Rules"
        lede={
          rules
            ? `${rules.length} saved ${rules.length === 1 ? "rule" : "rules"}. Open one to read its versions, then run the version that is active now.`
            : "Open a rule to read its versions, then run the version that is active now."
        }
        action={
          <Link className="primary link-button" to="/rules/new">
            New rule
          </Link>
        }
      />
      {error ? <Banner tone="error" title={error.message} details={error.details} /> : null}
      {rules === null && !error ? <p className="note">Loading rules…</p> : null}
      {rules && rules.length === 0 ? (
        <section className="sheet stack">
          <h2>No rules yet</h2>
          <p>Save a rule and it will show up here.</p>
        </section>
      ) : null}
      {rules && rules.length > 0 ? (
        <>
          <label className="field filter" htmlFor="rule-filter">
            <span className="label-text">Find</span>
            <input
              id="rule-filter"
              className="control"
              value={query}
              placeholder="Name, code, version, or description"
              onChange={(event) => setQuery(event.target.value)}
            />
          </label>
          {shown.length === 0 ? <p className="note">No rule matches that search.</p> : null}
          <div className="rule-list">
            {shown.map((rule) => {
              const blurb = cardDescription(rule)
              return (
                <Link key={rule.id} className="sheet rule-card" to={`/rules/${encodeURIComponent(ruleKey(rule))}`}>
                  {rule.code ? <p className="eyebrow">{rule.code}</p> : null}
                  <h2>{rule.name || "Untitled rule"}</h2>
                  {blurb ? <p>{blurb}</p> : null}
                  <p className="quiet">{versionLine(rule)}</p>
                  <p className="mono quiet">{whenSummary(summaryDefinition(rule))}</p>
                  <p className="quiet">{formatWhen(rule.createdAt)}</p>
                </Link>
              )
            })}
          </div>
        </>
      ) : null}
    </div>
  )
}

function cardDescription(rule: RuleResponse): string | null {
  if (rule.description) return rule.description
  if (rule.activeVersion) return null
  return latestVersion(rule)?.description ?? null
}

function versionLine(rule: RuleResponse): string {
  const count = rule.versions?.length ?? 0
  const noun = `${count} ${count === 1 ? "version" : "versions"}`
  const active = activeVersionOf(rule)
  if (!active) return `${noun} · none active`
  return `${noun} · ${active.version} active · ${formatSchedule(active)}`
}

function summaryDefinition(rule: RuleResponse): RuleDefinition | null {
  return rule.definition ?? latestVersion(rule)?.definition ?? null
}

function whenSummary(definition: RuleDefinition | null): string {
  const blocks = definition?.when ?? []
  if (blocks.length === 0) return "No when block"
  return blocks.map((block) => block.type).join("   ")
}
