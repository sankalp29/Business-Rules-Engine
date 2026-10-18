import { createContext, useContext, useEffect, useState, type CSSProperties } from "react"
import { Link, NavLink, Outlet, useLocation, useMatch, useParams } from "react-router-dom"
import { ApiError, api } from "../api"
import { Banner } from "../components/Banner"
import { PageHead } from "../components/PageHead"
import { versionColor } from "../calendar"
import { activeVersionOf, canonicalVersion, findVersion, latestVersion, orderedVersions, ruleKey } from "../format"
import type { RuleResponse, RuleVersion } from "../model"

type RuleState = {
  rule: RuleResponse
  replace: (rule: RuleResponse) => void
  routeKey: string
}

const RuleContext = createContext<RuleState | null>(null)

export function useLoadedRule(): RuleState {
  const value = useContext(RuleContext)
  if (!value) throw new Error("Rule pages render inside the rule frame.")
  return value
}

export function RuleFrame() {
  const params = useParams()
  const routeKey = params.key ?? ""
  const location = useLocation()
  const versionMatch = useMatch({ path: "/rules/:key/versions/:version", end: false })
  const requested = versionMatch?.params.version
  const [rule, setRule] = useState<RuleResponse | null>(null)
  const [error, setError] = useState<ApiError | null>(null)

  useEffect(() => {
    let cancelled = false
    setRule(null)
    setError(null)
    api
      .getRule(routeKey)
      .then((next) => {
        if (!cancelled) setRule(next)
      })
      .catch((caught: unknown) => {
        if (!cancelled) setError(caught instanceof ApiError ? caught : new ApiError("Could not load the rule."))
      })
    return () => {
      cancelled = true
    }
  }, [routeKey])

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
        <PageHead eyebrow="Rule" title="Rule" lede="Loading the rule." />
        <p className="note">Loading rule…</p>
      </div>
    )
  }

  const versions = orderedVersions(rule)
  const requestedVersion = requested ? findVersion(versions, requested) : undefined
  const unknownVersion = Boolean(requested) && !requestedVersion
  const focus = requestedVersion ?? activeVersionOf(rule) ?? latestVersion(rule)
  const encoded = encodeURIComponent(ruleKey(rule))
  const section = location.pathname.endsWith("/dates") ? "dates" : location.pathname.endsWith("/schema") ? "schema" : "logic"

  return (
    <RuleContext.Provider value={{ rule, replace: setRule, routeKey }}>
      <div className="stack page">
        <Link className="back" to="/rules">
          All rules
        </Link>
        <PageHead
          eyebrow={rule.code || "Rule"}
          title={rule.name || "Untitled rule"}
          lede={rule.description || "Schedule, logic, and execution."}
          action={
            <Link className="ghost link-button" to={newVersionHref(encoded, focus)}>
              New version
            </Link>
          }
        />
        <nav className="subnav" aria-label="Rule sections">
          <NavLink to={`/rules/${encoded}`} end>
            Overview
          </NavLink>
          {focus ? (
            <NavLink to={sectionHref(encoded, focus.version, "logic")} end>
              Logic
            </NavLink>
          ) : null}
          {focus ? (
            <NavLink to={sectionHref(encoded, focus.version, "dates")} end>
              Dates
            </NavLink>
          ) : null}
          {focus ? (
            <NavLink to={sectionHref(encoded, focus.version, "schema")} end>
              Schema
            </NavLink>
          ) : null}
          <NavLink to={`/rules/${encoded}/run`} end>
            Run
          </NavLink>
        </nav>
        {requested ? (
          <nav className="version-switch" aria-label="Versions">
            {versions.map((version) => {
              const color = versionColor(version.versionNumber)
              const current = version.version === requestedVersion?.version
              return (
                <Link
                  key={version.version}
                  to={sectionHref(encoded, version.version, section)}
                  className={current ? "on" : undefined}
                  aria-current={current ? "page" : undefined}
                  style={{ "--swatch": color.ink } as CSSProperties}
                >
                  <span className="swatch" aria-hidden="true" />
                  <span className="mono">{version.version}</span>
                  {version.active ? <span className="pill">Active</span> : null}
                </Link>
              )
            })}
          </nav>
        ) : null}
        {unknownVersion ? (
          <Banner
            tone="error"
            title={
              canonicalVersion(requested)
                ? `No version ${canonicalVersion(requested)} exists for rule ${rule.code || routeKey}.`
                : "version must look like v1"
            }
            details={["Open a version from the calendar."]}
          />
        ) : (
          <div key={location.pathname} className="rule-panel">
            <Outlet />
          </div>
        )}
      </div>
    </RuleContext.Provider>
  )
}

function sectionHref(encoded: string, version: string, section: "logic" | "dates" | "schema"): string {
  const base = `/rules/${encoded}/versions/${encodeURIComponent(version)}`
  return section === "logic" ? base : `${base}/${section}`
}

function newVersionHref(encoded: string, focus: RuleVersion | undefined): string {
  const base = `/rules/${encoded}/versions/new`
  if (!focus) return base
  return `${base}?from=${encodeURIComponent(focus.version)}`
}
