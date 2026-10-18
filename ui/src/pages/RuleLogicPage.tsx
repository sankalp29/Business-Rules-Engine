import { Link, useParams } from "react-router-dom"
import { RuleScript } from "../components/RuleScript"
import { findVersion, isEditable, orderedVersions, ruleKey } from "../format"
import { useLoadedRule } from "./RuleFrame"

export function RuleLogicPage() {
  const { rule } = useLoadedRule()
  const params = useParams()
  const version = findVersion(orderedVersions(rule), params.version)
  if (!version) return null
  const editHref = `/rules/${encodeURIComponent(ruleKey(rule))}/versions/${encodeURIComponent(version.version)}/edit`

  return (
    <div className="stack">
      {isEditable(version) ? (
        <Link className="ghost link-button" to={editHref}>
          Edit logic
        </Link>
      ) : (
        <p className="note">Logic can be changed only while this version is in draft.</p>
      )}
      <RuleScript definition={version.definition} />
    </div>
  )
}
