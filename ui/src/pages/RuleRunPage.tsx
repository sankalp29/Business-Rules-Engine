import { Navigate, useParams } from "react-router-dom"
import { ExecuteBench } from "../components/ExecuteBench"
import { activeVersionOf, findVersion, latestVersion, orderedVersions, ruleKey, runNote, runsNow } from "../format"
import type { RuleResponse, RuleVersion } from "../model"
import { useLoadedRule } from "./RuleFrame"

export function VersionExecutePage() {
  const { rule } = useLoadedRule()
  const params = useParams()
  const viewing = findVersion(orderedVersions(rule), params.version)
  if (!viewing) return null
  const running = activeVersionOf(rule)
  const live = running && runsNow(running) ? running : undefined

  return (
    <ExecuteBench
      key={`${viewing.version}:${live?.version ?? "none"}`}
      ruleKey={rule.code || ruleKey(rule)}
      schema={(live ?? viewing).definition?.inputSchema ?? []}
      note={versionRunNote(rule, viewing)}
    />
  )
}

export function RunRedirect() {
  const { rule } = useLoadedRule()
  const target = activeVersionOf(rule) ?? latestVersion(rule)
  const encoded = encodeURIComponent(ruleKey(rule))
  if (!target) return <Navigate to={`/rules/${encoded}`} replace />
  return <Navigate to={`/rules/${encoded}/versions/${encodeURIComponent(target.version)}/execute`} replace />
}

function versionRunNote(rule: RuleResponse, viewing: RuleVersion): string {
  const current = activeVersionOf(rule)
  if (current && runsNow(current) && current.version !== viewing.version) {
    return `You are looking at ${viewing.version}. Running the rule uses ${current.version}, the approved version whose dates include this moment. The fields below are the ones ${current.version} reads.`
  }
  return runNote(rule)
}
