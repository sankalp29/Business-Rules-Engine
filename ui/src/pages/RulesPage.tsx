import { useEffect, useState } from "react"
import { Link } from "react-router-dom"
import { ApiError, api } from "../api"
import { Banner } from "../components/Banner"
import { PageHead } from "../components/PageHead"
import { latestVersion, ruleKey, versionSummary } from "../format"
import type { RuleResponse } from "../model"

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
      ...(rule.versions ?? []).flatMap((version) => [version.version, version.description]),
    ]
      .join(" ")
      .toLowerCase()
    return haystack.includes(query.trim().toLowerCase())
  })

  return (
    <div className="stack page">
      <PageHead
        eyebrow="Desk"
        title="Rules"
        lede={
          rules
            ? `${rules.length} saved ${rules.length === 1 ? "rule" : "rules"}. Open one to see which version runs on which day.`
            : "Open a rule to see which version runs on which day."
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
          <div className="catalog">
            {shown.map((rule) => {
              const blurb = cardDescription(rule)
              return (
                <Link key={rule.id} className="catalog-row" to={`/rules/${encodeURIComponent(ruleKey(rule))}`}>
                  <div className="catalog-copy">
                    {rule.code ? <p className="catalog-code">{rule.code}</p> : null}
                    <h2>{rule.name || "Untitled rule"}</h2>
                    {blurb ? <p className="catalog-blurb">{blurb}</p> : null}
                  </div>
                  <p className="catalog-meta">{versionSummary(rule)}</p>
                </Link>
              )
            })}
          </div>
        </>
      ) : null}
    </div>
  )
}

/** The active version's description, or the latest version's when none is active. */
function cardDescription(rule: RuleResponse): string | null {
  if (rule.activeVersion) {
    const active = (rule.versions ?? []).find((version) => version.version === rule.activeVersion)
    return active?.description?.trim() || null
  }
  return latestVersion(rule)?.description?.trim() || null
}


