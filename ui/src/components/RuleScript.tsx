import { factLabels } from "../drafts"
import { formatLiteral } from "../format"
import type { Assignment, Branch, BranchArm, Condition, RuleDefinition, ThenBlock } from "../model"

const BOUND_FIELD = /^(\$[A-Za-z_][A-Za-z0-9_]*)\.(.+)$/

export function RuleScript({ definition }: { definition: RuleDefinition | null }) {
  const whens = definition?.when ?? []
  const thens = definition?.then ?? []
  const labels = factLabels(whens)
  const multiple = whens.length > 1

  return (
    <section className="rule-script" aria-label="Logic">
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
          <ThenView key={`${block.binding}-${index}`} block={block} multiple={multiple} labels={labels} />
        ))}
      </div>
    </section>
  )
}

function ThenView({
  block,
  multiple,
  labels,
}: {
  block: ThenBlock
  multiple: boolean
  labels: Map<string, string>
}) {
  const branches = block.branches ?? []
  const assignments = block.assignments ?? []
  const nameOf = (path: string | undefined) => displayPath(path, labels, block.binding)
  return (
    <article className="script-fact">
      {multiple ? (
        <p className="bind-line">
          <span className="bind-name">{labels.get(block.binding) ?? block.binding}</span>
        </p>
      ) : null}
      {assignments.length > 0 ? (
        <div className="script-rows">
          {assignments.map((assignment, index) => (
            <AssignmentView
              key={`${assignment.field}-${index}`}
              assignment={assignment}
              field={assignmentName(block.binding, assignment.field, multiple, labels)}
            />
          ))}
        </div>
      ) : null}
      {branches.length > 0 && assignments.length > 0 ? (
        <p className="note">These actions run when the when condition matches, before any branch.</p>
      ) : null}
      {branches.length > 0 ? (
        <>
          <p className="note">
            Each branch is an if, else if, else chain. The first matching arm runs. A later branch can replace a field an earlier one set.
          </p>
          <div className="branch-stack">
            {branches.map((branch, index) => (
              <BranchView
                key={index}
                branch={branch}
                index={index}
                binding={block.binding}
                multiple={multiple}
                labels={labels}
                nameOf={nameOf}
              />
            ))}
          </div>
        </>
      ) : null}
    </article>
  )
}

function BranchView({
  branch,
  index,
  binding,
  multiple,
  labels,
  nameOf,
}: {
  branch: Branch
  index: number
  binding: string
  multiple: boolean
  labels: Map<string, string>
  nameOf: (path: string | undefined) => string
}) {
  return (
    <article className="branch-card" aria-label={`Branch ${index + 1}`}>
      <ArmView kicker="If" arm={branch.if} binding={binding} multiple={multiple} labels={labels} nameOf={nameOf} />
      {(branch.elseIf ?? []).map((arm, armIndex) => (
        <ArmView key={armIndex} kicker="Else if" arm={arm} binding={binding} multiple={multiple} labels={labels} nameOf={nameOf} />
      ))}
      {branch.else ? (
        <ArmView kicker="Else" arm={branch.else} binding={binding} multiple={multiple} labels={labels} nameOf={nameOf} />
      ) : null}
    </article>
  )
}

function ArmView({
  kicker,
  arm,
  binding,
  multiple,
  labels,
  nameOf,
}: {
  kicker: string
  arm: BranchArm | null | undefined
  binding: string
  multiple: boolean
  labels: Map<string, string>
  nameOf: (path: string | undefined) => string
}) {
  const assignments = arm?.assignments ?? []
  return (
    <div className="branch-step">
      <h3 className="branch-kicker">{kicker}</h3>
      {arm?.when ? <ConditionView condition={arm.when} nameOf={nameOf} /> : kicker === "Else" ? null : <p className="note">No condition.</p>}
      <div className="script-rows">
        {assignments.map((assignment, index) => (
          <AssignmentView
            key={`${assignment.field}-${index}`}
            assignment={assignment}
            field={assignmentName(binding, assignment.field, multiple, labels)}
          />
        ))}
      </div>
    </div>
  )
}

function displayPath(path: string | undefined, labels: Map<string, string>, selfBinding: string): string {
  if (!path) return "field"
  const match = BOUND_FIELD.exec(path)
  if (!match) return path
  if (match[1] === selfBinding) return match[2]
  const label = labels.get(match[1]) ?? match[1].slice(1)
  return `${label}.${match[2]}`
}

function assignmentName(binding: string, field: string, multiple: boolean, labels: Map<string, string>): string {
  if (!multiple) return field
  const label = labels.get(binding)
  return label ? `${label}.${field}` : field
}

function ConditionView({
  condition,
  nameOf,
}: {
  condition: Condition
  nameOf?: (path: string | undefined) => string
}) {
  const grouped = (condition.operator === "and" || condition.operator === "or") && Array.isArray(condition.conditions)
  if (!grouped) {
    return (
      <div className="script-rows">
        <Comparison condition={condition} nameOf={nameOf} />
      </div>
    )
  }

  return (
    <div className={condition.operator === "or" ? "logic or" : "logic and"} role="group" aria-label={condition.operator}>
      <span className="logic-op">{condition.operator}</span>
      <div className="logic-kids">
        {(condition.conditions ?? []).map((child, index) => (
          <ConditionView key={index} condition={child} nameOf={nameOf} />
        ))}
      </div>
    </div>
  )
}

function Comparison({ condition, nameOf }: { condition: Condition; nameOf?: (path: string | undefined) => string }) {
  const field = nameOf ? nameOf(condition.field) : condition.field || "field"
  return (
    <p className="cmp">
      <span className="cmp-field">{field}</span>
      <span className="cmp-op">{condition.operator}</span>
      <ValueText condition={condition} nameOf={nameOf} />
    </p>
  )
}

function ValueText({ condition, nameOf }: { condition: Condition; nameOf?: (path: string | undefined) => string }) {
  if (condition.valueField) {
    return <span className="cmp-val field">{nameOf ? nameOf(condition.valueField) : condition.valueField}</span>
  }
  return <span className={`cmp-val ${valueKind(condition.value)}`}>{formatLiteral(condition.value)}</span>
}

function AssignmentView({ assignment, field }: { assignment: Assignment; field?: string }) {
  return (
    <p className="cmp">
      <span className="cmp-field">{field ?? assignment.field}</span>
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
