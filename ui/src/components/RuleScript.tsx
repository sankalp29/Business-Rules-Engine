import { formatLiteral } from "../format"
import type { Assignment, Condition, RuleDefinition } from "../model"

export function RuleScript({ definition }: { definition: RuleDefinition | null }) {
  const whens = definition?.when ?? []
  const thens = definition?.then ?? []

  return (
    <section className="rule-script" aria-label="Rule">
      <div className="script-part">
        <h2>When</h2>
        {whens.length === 0 ? <p className="note">No when block.</p> : null}
        {whens.map((block, index) => (
          <article key={`${block.binding}-${index}`} className="script-fact">
            <p className="bind-line">
              <span className="bind-name">{block.type}</span>
            </p>
            {block.conditions ? <ConditionView condition={block.conditions} /> : <p className="note">No condition.</p>}
          </article>
        ))}
      </div>
      <div className="script-part">
        <h2>Then</h2>
        {thens.length === 0 ? <p className="note">No then block.</p> : null}
        {thens.map((block, index) => (
          <article key={`${block.binding}-${index}`} className="script-fact">
            <div className="script-rows">
              {block.assignments.map((assignment, assignmentIndex) => (
                <AssignmentView key={`${assignment.field}-${assignmentIndex}`} assignment={assignment} />
              ))}
            </div>
          </article>
        ))}
      </div>
    </section>
  )
}

function ConditionView({ condition }: { condition: Condition }) {
  const grouped = (condition.operator === "and" || condition.operator === "or") && Array.isArray(condition.conditions)
  if (!grouped) {
    return (
      <div className="script-rows">
        <Comparison condition={condition} />
      </div>
    )
  }

  return (
    <div className={condition.operator === "or" ? "logic or" : "logic and"} role="group" aria-label={condition.operator}>
      <span className="logic-op">{condition.operator}</span>
      <div className="logic-kids">
        {(condition.conditions ?? []).map((child, index) => (
          <ConditionView key={index} condition={child} />
        ))}
      </div>
    </div>
  )
}

function Comparison({ condition }: { condition: Condition }) {
  return (
    <p className="cmp">
      <span className="cmp-field">{condition.field || "field"}</span>
      <span className="cmp-op">{condition.operator}</span>
      <ValueText condition={condition} />
    </p>
  )
}

function ValueText({ condition }: { condition: Condition }) {
  if (condition.valueField) return <span className="cmp-val field">{condition.valueField}</span>
  return <span className={`cmp-val ${valueKind(condition.value)}`}>{formatLiteral(condition.value)}</span>
}

function AssignmentView({ assignment }: { assignment: Assignment }) {
  return (
    <p className="cmp">
      <span className="cmp-field">{assignment.field}</span>
      <span className="cmp-op">=</span>
      {assignment.expression ? (
        <span className="cmp-val expr">{assignment.expression}</span>
      ) : (
        <span className={`cmp-val ${valueKind(assignment.value)}`}>{formatLiteral(assignment.value)}</span>
      )}
    </p>
  )
}

function valueKind(value: unknown): string {
  if (typeof value === "string") return "string"
  if (typeof value === "number") return "num"
  if (typeof value === "boolean") return "bool"
  if (value === null || value === undefined) return "nil"
  return "other"
}
