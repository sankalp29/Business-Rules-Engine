import { useId } from "react"
import {
  chainLeaves,
  chainValueFields,
  emptyArm,
  emptyAssignment,
  emptyBranch,
  emptyComparison,
  type ArmDraft,
  type AssignmentDraft,
  type BranchDraft,
  type ThenDraft,
  type ValueFieldOption,
  type WhenDraft,
} from "../drafts"
import { bindingFor, isPrimitive, leafText, uid, type Leaf, type TypeResponse } from "../model"
import { ConditionEditor } from "./ConditionEditor"
import { TypedControl } from "./TypedControl"

export function WhenBlockEditor({
  index,
  when,
  types,
  leaves,
  valueFields,
  canRemove,
  onChange,
  onRemove,
}: {
  index: number
  when: WhenDraft
  types: TypeResponse[]
  leaves: Leaf[]
  valueFields: ValueFieldOption[]
  canRemove: boolean
  onChange: (when: WhenDraft) => void
  onRemove: () => void
}) {
  const bindingId = useId()
  const typeId = useId()

  return (
    <section className="sheet block">
      <header className="sheet-head">
        <h2>When {index + 1}</h2>
        {canRemove ? (
          <button type="button" className="ghost" onClick={onRemove}>
            Remove
          </button>
        ) : null}
      </header>
      <div className="pair">
        <label className="field" htmlFor={bindingId}>
          <span className="label-text">Binding</span>
          <input
            id={bindingId}
            className="control mono-input"
            value={when.binding}
            spellCheck={false}
            autoComplete="off"
            onChange={(event) => onChange({ ...when, binding: event.target.value, bindingTouched: true })}
          />
        </label>
        <label className="field" htmlFor={typeId}>
          <span className="label-text">Type</span>
          <select
            id={typeId}
            className="control"
            value={when.type}
            onChange={(event) => {
              const type = event.target.value
              onChange({
                ...when,
                type,
                binding: when.bindingTouched ? when.binding : bindingFor(type),
                root: type === when.type ? when.root : emptyComparison(),
              })
            }}
          >
            <option value="">Choose a type</option>
            {types.map((type) => (
              <option key={type.name} value={type.name}>
                {type.name}
              </option>
            ))}
          </select>
        </label>
      </div>
      <ConditionEditor
        node={when.root}
        leaves={leaves}
        valueFields={valueFields}
        onChange={(root) => onChange({ ...when, root })}
        onExtend={() =>
          onChange({
            ...when,
            root: { id: uid(), kind: "group", operator: "and", children: [when.root, emptyComparison()] },
          })
        }
      />
    </section>
  )
}

export function ThenBlockEditor({
  index,
  block,
  bindings,
  bindingLabels,
  bindingName = "Binding",
  leaves,
  whens,
  leavesByWhen,
  canRemove,
  framed = true,
  onChange,
  onRemove,
}: {
  index: number
  block: ThenDraft
  bindings: string[]
  bindingLabels?: ReadonlyMap<string, string>
  bindingName?: string
  leaves: Leaf[]
  whens: WhenDraft[]
  leavesByWhen: Leaf[][]
  canRemove: boolean
  framed?: boolean
  onChange: (block: ThenDraft) => void
  onRemove: () => void
}) {
  const bindingId = useId()
  const branches = block.branches ?? []
  const conditionLeaves = chainLeaves(block.binding, whens, leavesByWhen)
  const valueFields = chainValueFields(block.binding, whens, leavesByWhen)
  const canDropAssignment = block.assignments.length > 1 || branches.length > 0
  const body = (
    <>
      {framed ? (
        <header className="sheet-head">
          <h2>Then {index + 1}</h2>
          {canRemove ? (
            <button type="button" className="ghost" onClick={onRemove}>
              Remove
            </button>
          ) : null}
        </header>
      ) : null}
      <div className={framed ? undefined : "fact-row"}>
        <label className="field" htmlFor={bindingId}>
          <span className="label-text">{bindingName}</span>
          <select
            id={bindingId}
            className="control"
            value={block.binding}
            onChange={(event) => onChange({ ...block, binding: event.target.value })}
          >
            <option value="">{bindingName === "Fact" ? "Choose a fact" : "Choose a binding"}</option>
            {bindings.map((binding) => (
              <option key={binding} value={binding}>
                {bindingLabels?.get(binding) ?? binding}
              </option>
            ))}
          </select>
        </label>
        {!framed && canRemove ? (
          <button type="button" className="ghost" onClick={onRemove}>
            Remove
          </button>
        ) : null}
      </div>
      <div className="stack">
        {block.assignments.map((assignment) => (
          <AssignmentRow
            key={assignment.id}
            assignment={assignment}
            leaves={leaves}
            canRemove={canDropAssignment}
            onChange={(next) =>
              onChange({
                ...block,
                assignments: block.assignments.map((item) => (item.id === assignment.id ? next : item)),
              })
            }
            onRemove={() => onChange({ ...block, assignments: block.assignments.filter((item) => item.id !== assignment.id) })}
          />
        ))}
      </div>
      <button
        type="button"
        className="ghost"
        onClick={() => onChange({ ...block, assignments: [...block.assignments, emptyAssignment()] })}
      >
        Add assignment
      </button>
      {branches.length > 0 && block.assignments.length > 0 ? (
        <p className="note">These actions run when the when condition matches, before any branch.</p>
      ) : null}
      {branches.length > 0 ? (
        <p className="note">
          Each branch is an if, else if, else chain. The first matching arm runs. A later branch can replace a field an earlier one set.
        </p>
      ) : (
        <p className="note">A branch is an if, else if, else chain. It runs only after the when condition matches.</p>
      )}
      {branches.length > 0 ? (
        <div className="branch-stack">
          {branches.map((branch, branchIndex) => (
            <BranchChainEditor
              key={branch.id}
              branch={branch}
              index={branchIndex}
              conditionLeaves={conditionLeaves}
              valueFields={valueFields}
              assignmentLeaves={leaves}
              onChange={(next) =>
                onChange({
                  ...block,
                  branches: branches.map((item) => (item.id === branch.id ? next : item)),
                })
              }
              onRemove={() =>
                onChange({
                  ...block,
                  branches: branches.filter((item) => item.id !== branch.id),
                  assignments: branches.length === 1 && block.assignments.length === 0 ? [emptyAssignment()] : block.assignments,
                })
              }
            />
          ))}
        </div>
      ) : null}
      <button
        type="button"
        className="ghost"
        onClick={() =>
          onChange({
            ...block,
            assignments: branches.length === 0 && block.assignments.every(untouchedAssignment) ? [] : block.assignments,
            branches: [...branches, emptyBranch()],
          })
        }
      >
        Add a branch
      </button>
    </>
  )
  if (!framed) return <div className="stack then-plain">{body}</div>
  return <section className="sheet block">{body}</section>
}

function BranchChainEditor({
  branch,
  index,
  conditionLeaves,
  valueFields,
  assignmentLeaves,
  onChange,
  onRemove,
}: {
  branch: BranchDraft
  index: number
  conditionLeaves: Leaf[]
  valueFields: ValueFieldOption[]
  assignmentLeaves: Leaf[]
  onChange: (branch: BranchDraft) => void
  onRemove: () => void
}) {
  return (
    <section className="branch-card" aria-label={`Branch ${index + 1}`}>
      <header className="sheet-head">
        <h3 className="branch-kicker">Branch {index + 1}</h3>
        <button type="button" className="ghost" onClick={onRemove}>
          Remove branch
        </button>
      </header>
      <ArmEditor
        kicker="If"
        arm={branch.ifArm}
        conditionLeaves={conditionLeaves}
        valueFields={valueFields}
        assignmentLeaves={assignmentLeaves}
        onChange={(ifArm) => onChange({ ...branch, ifArm })}
      />
      {branch.elseIf.map((arm, armIndex) => (
        <ArmEditor
          key={arm.id}
          kicker="Else if"
          arm={arm}
          conditionLeaves={conditionLeaves}
          valueFields={valueFields}
          assignmentLeaves={assignmentLeaves}
          onChange={(next) => onChange({ ...branch, elseIf: branch.elseIf.map((item) => (item.id === arm.id ? next : item)) })}
          onRemove={() => onChange({ ...branch, elseIf: branch.elseIf.filter((item) => item.id !== arm.id) })}
          removeLabel={`Remove else if ${armIndex + 1}`}
        />
      ))}
      {branch.otherwise ? (
        <ArmEditor
          kicker="Else"
          arm={branch.otherwise}
          conditionLeaves={conditionLeaves}
          valueFields={valueFields}
          assignmentLeaves={assignmentLeaves}
          onChange={(otherwise) => onChange({ ...branch, otherwise })}
          onRemove={() => onChange({ ...branch, otherwise: null })}
          removeLabel="Remove else"
        />
      ) : null}
      <div className="row">
        <button type="button" className="ghost" onClick={() => onChange({ ...branch, elseIf: [...branch.elseIf, emptyArm(true)] })}>
          Add else if
        </button>
        {branch.otherwise ? null : (
          <button type="button" className="ghost" onClick={() => onChange({ ...branch, otherwise: emptyArm(false) })}>
            Add else
          </button>
        )}
      </div>
    </section>
  )
}

function ArmEditor({
  kicker,
  arm,
  conditionLeaves,
  valueFields,
  assignmentLeaves,
  onChange,
  onRemove,
  removeLabel,
}: {
  kicker: string
  arm: ArmDraft
  conditionLeaves: Leaf[]
  valueFields: ValueFieldOption[]
  assignmentLeaves: Leaf[]
  onChange: (arm: ArmDraft) => void
  onRemove?: () => void
  removeLabel?: string
}) {
  return (
    <div className="branch-step">
      <div className="sheet-head">
        <h3 className="branch-kicker">{kicker}</h3>
        {onRemove ? (
          <button type="button" className="ghost" onClick={onRemove}>
            {removeLabel ?? "Remove"}
          </button>
        ) : null}
      </div>
      {arm.when ? (
        <ConditionEditor
          node={arm.when}
          leaves={conditionLeaves}
          valueFields={valueFields}
          onChange={(when) => onChange({ ...arm, when })}
          onExtend={() =>
            onChange({
              ...arm,
              when: { id: uid(), kind: "group", operator: "and", children: [arm.when ?? emptyComparison(), emptyComparison()] },
            })
          }
        />
      ) : null}
      <div className="stack">
        {arm.assignments.map((assignment) => (
          <AssignmentRow
            key={assignment.id}
            assignment={assignment}
            leaves={assignmentLeaves}
            canRemove={arm.assignments.length > 1}
            onChange={(next) => onChange({ ...arm, assignments: arm.assignments.map((item) => (item.id === assignment.id ? next : item)) })}
            onRemove={() => onChange({ ...arm, assignments: arm.assignments.filter((item) => item.id !== assignment.id) })}
          />
        ))}
      </div>
      <button type="button" className="ghost" onClick={() => onChange({ ...arm, assignments: [...arm.assignments, emptyAssignment()] })}>
        Add assignment
      </button>
    </div>
  )
}

function untouchedAssignment(assignment: AssignmentDraft): boolean {
  return (
    assignment.field.trim() === "" &&
    assignment.expression.trim() === "" &&
    assignment.valueText.trim() === "" &&
    !assignment.valueBool &&
    !assignment.valueNull
  )
}

function AssignmentRow({
  assignment,
  leaves,
  canRemove,
  onChange,
  onRemove,
}: {
  assignment: AssignmentDraft
  leaves: Leaf[]
  canRemove: boolean
  onChange: (assignment: AssignmentDraft) => void
  onRemove: () => void
}) {
  const fieldId = useId()
  const valueId = useId()
  const expressionId = useId()
  const leaf = leaves.find((item) => item.path === assignment.field)
  const nullable = !!leaf && !isPrimitive(leaf.type)

  return (
    <div className="assignment">
      <label className="field" htmlFor={fieldId}>
        <span className="label-text">Field</span>
        <select
          id={fieldId}
          className="control"
          value={assignment.field}
          onChange={(event) =>
            onChange({
              ...assignment,
              field: event.target.value,
              valueText: "",
              valueBool: false,
              valueNull: false,
            })
          }
        >
          <option value="">Choose a field</option>
          {leaves.map((item) => (
            <option key={item.path} value={item.path}>
              {leafText(item)}
            </option>
          ))}
        </select>
      </label>
      <div className="field">
        <span className="label-text">Set with</span>
        <div className="toggle" role="group" aria-label="Set with a value or an expression">
          <button type="button" aria-pressed={assignment.mode === "value"} onClick={() => onChange({ ...assignment, mode: "value" })}>
            Value
          </button>
          <button
            type="button"
            aria-pressed={assignment.mode === "expression"}
            onClick={() => onChange({ ...assignment, mode: "expression" })}
          >
            Expression
          </button>
        </div>
        {assignment.mode === "expression" ? (
          <input
            id={expressionId}
            className="control mono-input"
            value={assignment.expression}
            spellCheck={false}
            autoComplete="off"
            placeholder="$customer.getAge() * 0.01"
            onChange={(event) => onChange({ ...assignment, expression: event.target.value })}
          />
        ) : !leaf ? (
          <p className="note">Choose a field first.</p>
        ) : assignment.valueNull ? null : (
          <TypedControl
            id={valueId}
            label={leaf.label ?? leaf.path}
            typeName={leaf.type}
            text={assignment.valueText}
            bool={assignment.valueBool}
            onText={(valueText) => onChange({ ...assignment, valueText })}
            onBool={(valueBool) => onChange({ ...assignment, valueBool })}
          />
        )}
        {assignment.mode === "value" && nullable ? (
          <label className="check">
            <input
              type="checkbox"
              checked={assignment.valueNull}
              onChange={(event) => onChange({ ...assignment, valueNull: event.target.checked })}
            />
            Set null
          </label>
        ) : null}
      </div>
      {canRemove ? (
        <button type="button" className="ghost" onClick={onRemove}>
          Remove
        </button>
      ) : null}
    </div>
  )
}
