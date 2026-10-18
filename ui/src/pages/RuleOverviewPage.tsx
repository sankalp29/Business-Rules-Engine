import type { CSSProperties } from "react"
import { Link } from "react-router-dom"
import { VersionCalendar } from "../components/VersionCalendar"
import { versionColor } from "../calendar"
import { orderedVersions, ruleKey } from "../format"
import { useLoadedRule } from "./RuleFrame"

export function RuleOverviewPage() {
  const { rule } = useLoadedRule()
  const versions = orderedVersions(rule)
  const encoded = encodeURIComponent(ruleKey(rule))

  return (
    <div className="stack">
      <section className="stack" aria-labelledby="rule-versions">
        <h2 id="rule-versions" className="section-title">
          Versions
        </h2>
        {versions.length === 0 ? (
          <p className="note">This rule has no versions yet.</p>
        ) : (
          <ul className="version-cards">
            {versions.map((version) => {
              const color = versionColor(version.versionNumber)
              return (
                <li key={version.version}>
                  <Link
                    className="version-card"
                    to={`/rules/${encoded}/versions/${encodeURIComponent(version.version)}`}
                    style={{ "--mark": color.ink } as CSSProperties}
                  >
                    <span className="version-card-mark">{version.version}</span>
                    <span className="version-card-desc">{version.description?.trim() || "No description"}</span>
                  </Link>
                </li>
              )
            })}
          </ul>
        )}
      </section>
      {versions.length > 0 ? (
        <section className="stack effective-section" aria-labelledby="rule-effective">
          <h2 id="rule-effective" className="section-title">
            When each version is effective
          </h2>
          <VersionCalendar versions={versions} ruleKey={ruleKey(rule)} />
        </section>
      ) : null}
    </div>
  )
}
