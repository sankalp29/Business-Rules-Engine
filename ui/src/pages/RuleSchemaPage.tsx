import { useParams } from "react-router-dom"
import { FieldLines } from "../components/FieldLines"
import { findVersion, orderedVersions } from "../format"
import type { FactSchema } from "../model"
import { useLoadedRule } from "./RuleFrame"

export function RuleSchemaPage() {
  const { rule } = useLoadedRule()
  const params = useParams()
  const version = findVersion(orderedVersions(rule), params.version)
  if (!version) return null
  const definition = version.definition

  return (
    <div className="stack">
      <p className="note">Fields this version reads and writes.</p>
      <div className="schema-grid">
        <SchemaColumn title="Reads" facts={definition?.inputSchema ?? []} empty="This version reads no fields." />
        <SchemaColumn title="Writes" facts={definition?.outputSchema ?? []} empty="This version writes no fields." />
      </div>
    </div>
  )
}

function SchemaColumn({ title, facts, empty }: { title: string; facts: FactSchema[]; empty: string }) {
  return (
    <section className="sheet stack">
      <h2>{title}</h2>
      {facts.length === 0 ? <p className="note">{empty}</p> : null}
      {facts.map((fact) => (
        <div key={fact.type}>
          <h3>{fact.type}</h3>
          <FieldLines fields={fact.fields} />
        </div>
      ))}
    </section>
  )
}
