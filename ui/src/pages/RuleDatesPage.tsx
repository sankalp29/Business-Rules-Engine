import { useParams } from "react-router-dom"
import { VersionCalendar } from "../components/VersionCalendar"
import { EffectiveDatesForm } from "../components/EffectiveDatesForm"
import { findVersion, orderedVersions, ruleKey } from "../format"
import type { RuleVersion } from "../model"
import { useLoadedRule } from "./RuleFrame"

export function RuleDatesPage() {
  const { rule, replace } = useLoadedRule()
  const params = useParams()
  const versions = orderedVersions(rule)
  const version = findVersion(versions, params.version)
  if (!version) return null

  return (
    <div className="stack">
      <VersionCalendar versions={versions} ruleKey={ruleKey(rule)} focusVersion={version.version} />
      <EffectiveDatesForm key={datesKey(version)} ruleKey={rule.code || ruleKey(rule)} version={version} onSaved={replace} />
    </div>
  )
}

function datesKey(version: RuleVersion): string {
  const segments = (version.segments ?? [])
    .map((segment) => `${segment.effectiveFrom}/${segment.effectiveUntil ?? "open"}`)
    .join("|")
  return `${version.version}:${segments}:${version.effectiveFrom}:${version.effectiveUntil ?? "open"}`
}
