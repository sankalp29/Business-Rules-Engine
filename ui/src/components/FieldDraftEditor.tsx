import { useId } from "react"
import { emptyField, type FieldDraft } from "../drafts"
import { SCALAR_TYPES, isScalar } from "../model"

export function FieldDraftEditor({
  draft,
  types,
  depth = 0,
  canRemove,
  onChange,
  onRemove,
}: {
  draft: FieldDraft
  types: string[]
  depth?: number
  canRemove: boolean
  onChange: (draft: FieldDraft) => void
  onRemove: () => void
}) {
  const nameId = useId()
  const kindId = useId()
  const typeId = useId()
  const kindValue = draft.mode === "scalar" ? draft.scalar : draft.mode
  const allowObject = depth < 8

  function chooseKind(value: string) {
    if (value === "object") {
      onChange({
        ...draft,
        mode: "object",
        children: draft.children.length > 0 ? draft.children : [emptyField()],
      })
      return
    }
    if (value === "type") {
      onChange({ ...draft, mode: "type" })
      return
    }
    if (isScalar(value)) onChange({ ...draft, mode: "scalar", scalar: value })
  }

  return (
    <div className={depth > 0 ? "field-editor nested" : "field-editor"}>
      <div className="pair">
        <label className="field" htmlFor={nameId}>
          <span className="label-text">Field name</span>
          <input
            id={nameId}
            className="control"
            value={draft.name}
            autoComplete="off"
            spellCheck={false}
            onChange={(event) => onChange({ ...draft, name: event.target.value })}
          />
        </label>
        <label className="field" htmlFor={kindId}>
          <span className="label-text">Data type</span>
          <select id={kindId} className="control" value={kindValue} onChange={(event) => chooseKind(event.target.value)}>
            <optgroup label="Scalar">
              {SCALAR_TYPES.map((scalar) => (
                <option key={scalar} value={scalar}>
                  {scalar}
                </option>
              ))}
            </optgroup>
            <option value="type" disabled={types.length === 0}>
              Saved type
            </option>
            {allowObject ? <option value="object">Nested fields</option> : null}
          </select>
        </label>
      </div>
      {draft.mode === "type" ? (
        <label className="field" htmlFor={typeId}>
          <span className="label-text">Saved type</span>
          <select
            id={typeId}
            className="control"
            value={draft.typeName}
            onChange={(event) => onChange({ ...draft, typeName: event.target.value })}
          >
            <option value="">Choose a type</option>
            {types.map((type) => (
              <option key={type} value={type}>
                {type}
              </option>
            ))}
          </select>
        </label>
      ) : null}
      {draft.mode === "object" ? (
        <div className="stack">
          {draft.children.map((child) => (
            <FieldDraftEditor
              key={child.id}
              draft={child}
              types={types}
              depth={depth + 1}
              canRemove={draft.children.length > 1}
              onChange={(next) =>
                onChange({ ...draft, children: draft.children.map((item) => (item.id === child.id ? next : item)) })
              }
              onRemove={() => onChange({ ...draft, children: draft.children.filter((item) => item.id !== child.id) })}
            />
          ))}
          <button
            type="button"
            className="ghost"
            onClick={() => onChange({ ...draft, children: [...draft.children, emptyField()] })}
          >
            Add nested field
          </button>
        </div>
      ) : null}
      {canRemove ? (
        <button type="button" className="ghost" onClick={onRemove}>
          Remove field
        </button>
      ) : null}
    </div>
  )
}
