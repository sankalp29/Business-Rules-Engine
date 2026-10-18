import type { CSSProperties } from "react"
import { Link, NavLink, Outlet, useLocation, useParams } from "react-router-dom"
import { VersionCalendar } from "../components/VersionCalendar"
import { EffectiveDatesForm } from "../components/EffectiveDatesForm"
import { StageTrack } from "../components/StageTrack"
import { versionColor } from "../calendar"
import { findVersion, isEditable, orderedVersions, ruleKey } from "../format"
import type { RuleResponse, RuleVersion } from "../model"
import { useLoadedRule } from "./RuleFrame"

export function VersionPage() {
  const { rule, replace } = useLoadedRule()
  const params = useParams()
  const location = useLocation()
  const versions = orderedVersions(rule)
  const version = findVersion(versions, params.version)
  if (!version) return null
  if (location.pathname.endsWith("/edit")) return <Outlet />

  const encoded = encodeURIComponent(ruleKey(rule))
  const base = `/rules/${encoded}/versions/${encodeURIComponent(version.version)}`
  const color = versionColor(version.versionNumber)

  return (
    <div className="stack">
      <Link className="back" to={`/rules/${encoded}`}>
        Versions
      </Link>
      <header className="version-identity">
        <h2 className="version-mark" style={{ "--mark": color.ink, "--swatch": color.ink } as CSSProperties}>
          <span className="swatch" aria-hidden="true" />
          {version.version}
        </h2>
        <p className="version-blurb">{version.description?.trim() || "No description"}</p>
      </header>
      <StageTrack ruleKey={rule.code || ruleKey(rule)} version={version} onChanged={replace} />
      <nav className="subnav" aria-label="Version">
        <NavLink to={`${base}/schema`} end>
          Schema
        </NavLink>
        <NavLink to={base} end>
          Logic
        </NavLink>
        <NavLink to={`${base}/execute`} end>
          Execute
        </NavLink>
      </nav>
      <Outlet />
      <VersionSchedule rule={rule} version={version} versions={versions} onSaved={replace} />
    </div>
  )
}

function VersionSchedule({
  rule,
  version,
  versions,
  onSaved,
}: {
  rule: RuleResponse
  version: RuleVersion
  versions: RuleVersion[]
  onSaved: (rule: RuleResponse) => void
}) {
  const key = rule.code || ruleKey(rule)
  return (
    <section className="stack version-schedule" aria-labelledby="version-effective">
      <h2 id="version-effective" className="section-title">
        When {version.version} is effective
      </h2>
      <VersionCalendar key={scheduleKey(version)} versions={[version]} ruleKey={key} solo />
      {isEditable(version) ? (
        <EffectiveDatesForm
          key={scheduleKey(version)}
          ruleKey={key}
          version={version}
          versions={versions}
          onSaved={onSaved}
        />
      ) : (
        <p className="note">Dates can be changed only while this version is in draft.</p>
      )}
    </section>
  )
}

function scheduleKey(version: RuleVersion): string {
  const segments = (version.segments ?? [])
    .map((segment) => `${segment.effectiveFrom}/${segment.effectiveUntil ?? "open"}`)
    .join("|")
  return `${version.version}:${segments}:${version.effectiveFrom}:${version.effectiveUntil ?? "open"}`
}
