import { useId } from "react"
import {
  compatibleFields,
  emptyComparison,
  emptyGroup,
  type ComparisonDraft,
  type ConditionDraft,
  type GroupDraft,
  type ValueFieldOption,
} from "../drafts"
import { isPrimitive, operatorsFor, type Leaf } from "../model"
import { TypedControl } from "./TypedControl"

export function ConditionEditor({
  node,
  leaves,
  valueFields,
  onChange,
  onRemove,
  onExtend,
  depth = 0,
}: {
  node: ConditionDraft
  leaves: Leaf[]
  valueFields: ValueFieldOption[]
  onChange: (node: ConditionDraft) => void
  onRemove?: () => void
  onExtend?: () => void
  depth?: number
}) {
  if (node.kind === "group") {
    return (
      <GroupEditor
        node={node}
        leaves={leaves}
        valueFields={valueFields}
        depth={depth}
        onChange={onChange}
        onRemove={onRemove}
      />
    )
  }
  return (
    <ComparisonEditor
      node={node}
      leaves={leaves}
      valueFields={valueFields}
      onChange={onChange}
      onRemove={onRemove}
      onExtend={onExtend}
    />
  )
}

function GroupEditor({
  node,
  leaves,
  valueFields,
  depth,
  onChange,
  onRemove,
}: {
  node: GroupDraft
  leaves: Leaf[]
  valueFields: ValueFieldOption[]
  depth: number
  onChange: (node: ConditionDraft) => void
  onRemove?: () => void
}) {
  function removeChild(id: string) {
    const remaining = node.children.filter((child) => child.id !== id)
    if (remaining.length === 1) onChange(remaining[0])
    else if (remaining.length === 0) onChange(emptyComparison())
    else onChange({ ...node, children: remaining })
  }

  return (
    <div className={depth > 0 ? "group nested" : "group"}>
      <div className="row">
        <div className="toggle" role="group" aria-label="Combine conditions">
          <button type="button" aria-pressed={node.operator === "and"} onClick={() => onChange({ ...node, operator: "and" })}>
            And
          </button>
          <button type="button" aria-pressed={node.operator === "or"} onClick={() => onChange({ ...node, operator: "or" })}>
            Or
          </button>
        </div>
        {onRemove ? (
          <button type="button" className="ghost" onClick={onRemove}>
            Remove group
          </button>
        ) : null}
      </div>
      <div className="stack">
        {node.children.map((child) => (
          <ConditionEditor
            key={child.id}
            node={child}
            leaves={leaves}
            valueFields={valueFields}
            depth={depth + 1}
            onChange={(next) => onChange({ ...node, children: node.children.map((item) => (item.id === child.id ? next : item)) })}
            onRemove={() => removeChild(child.id)}
          />
        ))}
      </div>
      <div className="row">
        <button type="button" className="ghost" onClick={() => onChange({ ...node, children: [...node.children, emptyComparison()] })}>
          Add condition
        </button>
        {depth < 8 ? (
          <button type="button" className="ghost" onClick={() => onChange({ ...node, children: [...node.children, emptyGroup()] })}>
            Add group
          </button>
        ) : null}
      </div>
    </div>
  )
}

function ComparisonEditor({
  node,
  leaves,
  valueFields,
  onChange,
  onRemove,
  onExtend,
}: {
  node: ComparisonDraft
  leaves: Leaf[]
  valueFields: ValueFieldOption[]
  onChange: (node: ConditionDraft) => void
  onRemove?: () => void
  onExtend?: () => void
}) {
  const fieldId = useId()
  const operatorId = useId()
  const valueId = useId()
  const leaf = leaves.find((item) => item.path === node.field)
  const operators = leaf ? operatorsFor(leaf.type) : ["==", "!="]
  const comparable = compatibleFields(valueFields, leaf?.type)
  const nullable = !!leaf && !isPrimitive(leaf.type)

  function chooseField(path: string) {
    const next = leaves.find((item) => item.path === path)
    const nextOperators = next ? operatorsFor(next.type) : ["=="]
    onChange({
      ...node,
      field: path,
      operator: nextOperators.includes(node.operator) ? node.operator : nextOperators[0],
      rhs: "value",
      valueText: "",
      valueBool: false,
      valueNull: false,
      valueField: "",
    })
  }

  return (
    <div className="comparison-block">
      <div className="comparison">
        <label className="field" htmlFor={fieldId}>
          <span className="label-text">Field</span>
          <select id={fieldId} className="control" value={node.field} onChange={(event) => chooseField(event.target.value)}>
            <option value="">Choose a field</option>
            {leaves.map((item) => (
              <option key={item.path} value={item.path}>
                {item.path} · {item.type}
              </option>
            ))}
          </select>
        </label>
        <label className="field" htmlFor={operatorId}>
          <span className="label-text">Operator</span>
          <select
            id={operatorId}
            className="control"
            disabled={!leaf}
            value={operators.includes(node.operator) ? node.operator : operators[0]}
            onChange={(event) => onChange({ ...node, operator: event.target.value })}
          >
            {operators.map((operator) => (
              <option key={operator} value={operator}>
                {operator}
              </option>
            ))}
          </select>
        </label>
        <div className="field">
          <span className="label-text">Compare with</span>
          <div className="toggle" role="group" aria-label="Compare with a value or a field">
            <button type="button" aria-pressed={node.rhs === "value"} onClick={() => onChange({ ...node, rhs: "value" })}>
              Value
            </button>
            <button type="button" aria-pressed={node.rhs === "field"} onClick={() => onChange({ ...node, rhs: "field" })}>
              Field
            </button>
          </div>
          {node.rhs === "field" ? (
            comparable.length > 0 ? (
              <select
                className="control"
                value={node.valueField}
                onChange={(event) => onChange({ ...node, valueField: event.target.value })}
              >
                <option value="">Choose a field</option>
                {comparable.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label} · {option.type}
                  </option>
                ))}
              </select>
            ) : (
              <p className="note">No comparable field is in scope yet.</p>
            )
          ) : !leaf ? (
            <p className="note">Choose a field first.</p>
          ) : node.valueNull ? null : (
            <TypedControl
              id={valueId}
              label={leaf.path}
              typeName={leaf.type}
              text={node.valueText}
              bool={node.valueBool}
              onText={(valueText) => onChange({ ...node, valueText })}
              onBool={(valueBool) => onChange({ ...node, valueBool })}
            />
          )}
          {node.rhs === "value" && nullable ? (
            <label className="check">
              <input
                type="checkbox"
                checked={node.valueNull}
                onChange={(event) => onChange({ ...node, valueNull: event.target.checked })}
              />
              Use null
            </label>
          ) : null}
        </div>
      </div>
      <div className="row">
        {onExtend ? (
          <button type="button" className="ghost" onClick={onExtend}>
            Add condition
          </button>
        ) : null}
        {onRemove ? (
          <button type="button" className="ghost" onClick={onRemove}>
            Remove
          </button>
        ) : null}
      </div>
    </div>
  )
}
