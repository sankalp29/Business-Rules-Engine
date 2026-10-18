import { ExecuteBench } from "../components/ExecuteBench"
import { activeVersionOf, latestVersion, ruleKey } from "../format"
import { useLoadedRule } from "./RuleFrame"

export function RuleRunPage() {
  const { rule } = useLoadedRule()
  const active = activeVersionOf(rule)
  const definition = active?.definition ?? latestVersion(rule)?.definition ?? rule.definition

  return (
    <ExecuteBench
      ruleKey={rule.code || ruleKey(rule)}
      schema={definition?.inputSchema ?? []}
      activeVersion={active?.version ?? rule.activeVersion ?? null}
    />
  )
}
