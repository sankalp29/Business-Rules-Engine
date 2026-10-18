import { VersionCalendar } from "../components/VersionCalendar"
import { orderedVersions, ruleKey } from "../format"
import { useLoadedRule } from "./RuleFrame"

export function RuleOverviewPage() {
  const { rule } = useLoadedRule()
  const versions = orderedVersions(rule)

  return (
    <div className="stack">
      {versions.length === 0 ? <p className="note">This rule has no versions yet.</p> : null}
      <VersionCalendar versions={versions} ruleKey={ruleKey(rule)} />
    </div>
  )
}
