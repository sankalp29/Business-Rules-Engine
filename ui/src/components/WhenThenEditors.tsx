import { useId } from "react"
import { emptyAssignment, emptyComparison, type AssignmentDraft, type ThenDraft, type ValueFieldOption, type WhenDraft } from "../drafts"
import { bindingFor, isPrimitive, uid, type Leaf, type TypeResponse } from "../model"
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
  leaves,
  canRemove,
  onChange,
  onRemove,
}: {
  index: number
  block: ThenDraft
  bindings: string[]
  leaves: Leaf[]
  canRemove: boolean
  onChange: (block: ThenDraft) => void
  onRemove: () => void
}) {
  const bindingId = useId()
  return (
    <section className="sheet block">
      <header className="sheet-head">
        <h2>Then {index + 1}</h2>
        {canRemove ? (
          <button type="button" className="ghost" onClick={onRemove}>
            Remove
          </button>
        ) : null}
      </header>
      <label className="field" htmlFor={bindingId}>
        <span className="label-text">Binding</span>
        <select
          id={bindingId}
          className="control"
          value={block.binding}
          onChange={(event) => onChange({ ...block, binding: event.target.value })}
        >
          <option value="">Choose a binding</option>
          {bindings.map((binding) => (
            <option key={binding} value={binding}>
              {binding}
            </option>
          ))}
        </select>
      </label>
      <div className="stack">
        {block.assignments.map((assignment) => (
          <AssignmentRow
            key={assignment.id}
            assignment={assignment}
            leaves={leaves}
            canRemove={block.assignments.length > 1}
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
    </section>
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
              {item.path} · {item.type}
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
            label={leaf.path}
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
