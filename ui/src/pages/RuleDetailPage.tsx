import { useEffect, useRef, useState } from "react"
import { Link, useNavigate, useParams } from "react-router-dom"
import { ApiError, api } from "../api"
import { Banner } from "../components/Banner"
import { EffectiveDatesForm } from "../components/EffectiveDatesForm"
import { ExecuteBench } from "../components/ExecuteBench"
import { FieldLines } from "../components/FieldLines"
import { PageHead } from "../components/PageHead"
import { RuleScript } from "../components/RuleScript"
import { activeVersionOf, canonicalVersion, findVersion, formatSchedule, formatWhen, latestVersion, orderedVersions } from "../format"
import type { RuleResponse, RuleVersion } from "../model"

export function RuleDetailPage() {
  const navigate = useNavigate()
  const params = useParams()
  const key = params.key ?? ""
  const requested = params.version
  const [rule, setRule] = useState<RuleResponse | null>(null)
  const [error, setError] = useState<ApiError | null>(null)
  const [benchOpen, setBenchOpen] = useState(false)
  const benchRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    let cancelled = false
    setRule(null)
    setError(null)
    setBenchOpen(false)
    api
      .getRule(key)
      .then((next) => {
        if (!cancelled) setRule(next)
      })
      .catch((caught: unknown) => {
        if (!cancelled) setError(caught instanceof ApiError ? caught : new ApiError("Could not load the rule."))
      })
    return () => {
      cancelled = true
    }
  }, [key])

  function openBench() {
    setBenchOpen((open) => {
      const next = !open
      if (next) {
        window.setTimeout(() => {
          const motion = window.matchMedia("(prefers-reduced-motion: reduce)").matches
          benchRef.current?.scrollIntoView({ behavior: motion ? "auto" : "smooth", block: "start" })
        }, 0)
      }
      return next
    })
  }

  if (error) {
    return (
      <div className="stack page">
        <PageHead eyebrow="Rule" title="Rule" lede="This rule could not be opened." />
        <Banner tone="error" title={error.message} details={error.details} />
        <Link className="back" to="/rules">
          All rules
        </Link>
      </div>
    )
  }

  if (!rule) {
    return (
      <div className="stack page">
        <PageHead eyebrow="Rule" title="Rule" lede="Loading the rule record." />
        <p className="note">Loading rule…</p>
      </div>
    )
  }

  const versions = orderedVersions(rule)
  const requestedVersion = requested ? findVersion(versions, requested) : undefined
  const unknownVersion = Boolean(requested) && !requestedVersion
  const active = activeVersionOf(rule)
  const displayed = requestedVersion ?? active ?? latestVersion(rule)
  const currentToken = (requestedVersion ?? (!requested ? displayed : undefined))?.version
  const definition = displayed?.definition ?? rule.definition
  const encodedKey = encodeURIComponent(key)

  return (
    <div className="stack page">
      <Link className="back" to="/rules">
        All rules
      </Link>
      <PageHead
        eyebrow={displayed ? displayed.version : "Rule"}
        title={rule.name || "Untitled rule"}
        lede={displayed?.description || rule.description || "No description was saved with this version."}
        action={
          <Link className="ghost link-button" to={newVersionHref(encodedKey, displayed)}>
            New version
          </Link>
        }
      />
      {unknownVersion ? (
        <Banner
          tone="error"
          title={
            canonicalVersion(requested)
              ? `No version ${canonicalVersion(requested)} exists for rule ${rule.code || key}.`
              : "version must look like v1"
          }
          details={["Open a version from the list."]}
        />
      ) : null}
      <section className="sheet stack">
        <h2>Versions</h2>
        {versions.length === 0 ? <p className="note">This rule has no versions.</p> : null}
        <nav className="version-rail" aria-label="Versions">
          {versions.map((version) => (
            <Link
              key={version.version}
              className={version.version === currentToken ? "version-chip current" : "version-chip"}
              to={`/rules/${encodedKey}/versions/${encodeURIComponent(version.version)}`}
              aria-current={version.version === currentToken ? "page" : undefined}
            >
              <span className="mono">{version.version}</span>
              <span>{formatSchedule(version)}</span>
              {version.active ? <span className="pill">Active</span> : null}
            </Link>
          ))}
        </nav>
      </section>
      {!unknownVersion && displayed ? (
        <EffectiveDatesForm
          key={datesKey(displayed)}
          ruleKey={rule.code || key}
          version={displayed}
          onSaved={(next) => {
            setRule(next)
            if (!requested) {
              navigate(`/rules/${encodedKey}/versions/${encodeURIComponent(displayed.version)}`, { replace: true })
            }
          }}
        />
      ) : null}
      <div className="actions">
        <button type="button" className="primary" onClick={openBench}>
          {benchOpen ? "Hide execution" : "Execute"}
        </button>
        {rule.code ? <span className="mono quiet">{rule.code}</span> : null}
        {displayed && !displayed.active ? <span className="quiet">Outside its dates</span> : null}
      </div>
      {benchOpen ? (
        <div ref={benchRef}>
          <ExecuteBench
            key={active?.version ?? displayed?.version ?? "rule"}
            ruleKey={rule.code || String(rule.id)}
            schema={(active?.definition ?? definition)?.inputSchema ?? []}
            activeVersion={active?.version ?? rule.activeVersion ?? null}
          />
        </div>
      ) : null}
      <RuleScript definition={definition} />
      <section className="sheet">
        <h2>Record</h2>
        <div className="meta-grid">
          <p>
            <span>Code</span>
            {rule.code || "None"}
          </p>
          <p>
            <span>Version</span>
            {displayed?.version ?? "None"}
          </p>
          <p>
            <span>Effective</span>
            {displayed ? formatSchedule(displayed) : "No effective dates"}
          </p>
          <p>
            <span>Saved</span>
            {formatWhen(displayed?.createdAt ?? rule.createdAt)}
          </p>
        </div>
      </section>
      <section className="schema-grid">
        <div className="sheet stack">
          <h2>Input schema</h2>
          {(definition?.inputSchema ?? []).length === 0 ? <p className="note">This rule reads no fields.</p> : null}
          {(definition?.inputSchema ?? []).map((fact) => (
            <div key={fact.type}>
              <h3>{fact.type}</h3>
              <FieldLines fields={fact.fields} />
            </div>
          ))}
        </div>
        <div className="sheet stack">
          <h2>Output schema</h2>
          {(definition?.outputSchema ?? []).length === 0 ? <p className="note">This rule writes no fields.</p> : null}
          {(definition?.outputSchema ?? []).map((fact) => (
            <div key={fact.type}>
              <h3>{fact.type}</h3>
              <FieldLines fields={fact.fields} />
            </div>
          ))}
        </div>
      </section>
    </div>
  )
}

function datesKey(version: RuleVersion): string {
  const segments = (version.segments ?? [])
    .map((segment) => `${segment.effectiveFrom}/${segment.effectiveUntil ?? "open"}`)
    .join("|")
  return `${version.version}:${segments}:${version.effectiveFrom}:${version.effectiveUntil ?? "open"}`
}

function newVersionHref(encodedKey: string, displayed: RuleVersion | undefined): string {
  const base = `/rules/${encodedKey}/versions/new`
  if (!displayed) return base
  return `${base}?from=${encodeURIComponent(displayed.version)}`
}
