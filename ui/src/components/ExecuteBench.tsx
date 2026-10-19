import { useState } from "react"
import { ApiError, api } from "../api"
import { draftFromFields, executePayload, factsFromSchema, incompleteNumbers, type FactDraft } from "../drafts"
import { collectChanges, formatLiteral } from "../format"
import { uid, type ExecutionResponse, type FactResult, type FactSchema } from "../model"
import { Banner } from "./Banner"
import { SchemaFields } from "./SchemaFields"

export function ExecuteBench({
  ruleKey,
  schema,
  note,
}: {
  ruleKey: string
  schema: FactSchema[]
  note: string
}) {
  const [facts, setFacts] = useState<FactDraft[]>(() => factsFromSchema(schema))
  const [result, setResult] = useState<ExecutionResponse | null>(null)
  const [error, setError] = useState<ApiError | null>(null)
  const [pending, setPending] = useState(false)

  async function run() {
    const problems = facts.flatMap((fact, index) => incompleteNumbers(fact.values, `${fact.type} ${index + 1}`))
    if (problems.length > 0) {
      setError(new ApiError("Check the values.", problems))
      setResult(null)
      return
    }
    setPending(true)
    setError(null)
    try {
      setResult(await api.executeRule(ruleKey, executePayload(facts)))
    } catch (caught) {
      setResult(null)
      setError(caught instanceof ApiError ? caught : new ApiError("The rule did not run."))
    } finally {
      setPending(false)
    }
  }

  return (
    <section className="sheet bench">
      <header className="sheet-head">
        <h2>Execution</h2>
      </header>
      <p className="note">{note}</p>
      <p className="note">Enter the fields this run reads. Blank text and numbers are left unset. A true or false choice is always sent.</p>
      {schema.length === 0 ? (
        <p className="note">This rule's input schema has no fields.</p>
      ) : (
        <div className="stack">
          {facts.map((fact, index) => {
            const siblings = facts.filter((item) => item.type === fact.type).length
            return (
              <article key={fact.id} className="fact-card">
                <header className="sheet-head">
                  <h3>
                    {fact.type}
                    {siblings > 1 ? <span className="quiet"> {indexLabel(facts, fact, index)}</span> : null}
                  </h3>
                  <div className="row">
                    <button
                      type="button"
                      className="ghost"
                      onClick={() =>
                        setFacts((current) => [
                          ...current.slice(0, index + 1),
                          {
                            id: uid(),
                            type: fact.type,
                            fields: fact.fields,
                            values: draftFromFields(fact.fields),
                          },
                          ...current.slice(index + 1),
                        ])
                      }
                    >
                      Add another {fact.type}
                    </button>
                    {siblings > 1 ? (
                      <button type="button" className="ghost" onClick={() => setFacts((current) => current.filter((item) => item.id !== fact.id))}>
                        Remove
                      </button>
                    ) : null}
                  </div>
                </header>
                <SchemaFields
                  fields={fact.fields}
                  values={fact.values}
                  onChange={(values) =>
                    setFacts((current) => current.map((item) => (item.id === fact.id ? { ...item, values } : item)))
                  }
                />
              </article>
            )
          })}
        </div>
      )}
      {error ? <Banner tone="error" title={error.message} details={error.details} /> : null}
      <div className="actions">
        <button type="button" className="primary" onClick={() => void run()} disabled={pending}>
          {pending ? "Running…" : "Run rule"}
        </button>
      </div>
      {result ? <ExecutionResult result={result} /> : null}
    </section>
  )
}

function indexLabel(facts: FactDraft[], fact: FactDraft, index: number): string {
  const prior = facts.slice(0, index).filter((item) => item.type === fact.type).length
  return String(prior + 1)
}

function ExecutionResult({ result }: { result: ExecutionResponse }) {
  const changes = (result.modifiedData ?? []).flatMap((fact) =>
    collectChanges(fact.values).map((change) => ({ ...change, type: fact.type })),
  )
  return (
    <div className="result">
      <div className="result-head">
        <div>
          <p className={result.fired ? "stamp fired" : "stamp"}>{result.fired ? "Fired" : "Held"}</p>
          {result.version ? <p className="mono quiet">{result.version}</p> : null}
        </div>
        <div className="reasoning">
          {result.reasoning.map((line, index) => (
            <p key={index}>{line}</p>
          ))}
        </div>
      </div>
      <div>
        <h3>Changes</h3>
        {changes.length === 0 ? (
          <p className="note">No fields changed.</p>
        ) : (
          <ul className="changes">
            {changes.map((change) => (
              <li key={`${change.type}.${change.path}`}>
                <span className="mono">
                  {change.type}.{change.path}
                </span>
                <span>
                  {formatLiteral(change.previous)} to {formatLiteral(change.next)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
      <div className="schema-grid">
        <FactColumn title="Input" facts={result.inputData} />
        <FactColumn title="Output" facts={result.outputData} />
      </div>
    </div>
  )
}

function FactColumn({ title, facts }: { title: string; facts: FactResult[] | null }) {
  return (
    <div>
      <h3>{title}</h3>
      {(facts ?? []).length === 0 ? <p className="note">No facts.</p> : null}
      {(facts ?? []).map((fact, index) => (
        <article key={`${fact.type}-${index}`} className="fact-card">
          <h3>{fact.type}</h3>
          <ValueTree value={fact.values ?? {}} />
        </article>
      ))}
    </div>
  )
}

function ValueTree({ value }: { value: unknown }) {
  if (value === null || value === undefined) return <span>null</span>
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
    return <span>{formatLiteral(value)}</span>
  }
  if (typeof value !== "object" || Array.isArray(value)) return <span>{formatLiteral(value)}</span>
  const entries = Object.entries(value as Record<string, unknown>)
  if (entries.length === 0) return <span className="note">Empty</span>
  return (
    <div className="pairs">
      {entries.map(([key, entry]) => (
        <div key={key}>
          <span className="mono">{key}</span>
          <ValueTree value={entry} />
        </div>
      ))}
    </div>
  )
}
