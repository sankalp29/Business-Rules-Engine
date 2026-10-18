import { useParams } from "react-router-dom"
import { RuleScript } from "../components/RuleScript"
import { findVersion, orderedVersions } from "../format"
import { useLoadedRule } from "./RuleFrame"

export function RuleLogicPage() {
  const { rule } = useLoadedRule()
  const params = useParams()
  const version = findVersion(orderedVersions(rule), params.version)
  if (!version) return null
  const salience = version.definition?.salience

  return (
    <div className="stack">
      {version.description ? <p className="lede">{version.description}</p> : null}
      {salience !== null && salience !== undefined ? <p className="quiet">Salience {salience}</p> : null}
      <RuleScript definition={version.definition} />
    </div>
  )
}
