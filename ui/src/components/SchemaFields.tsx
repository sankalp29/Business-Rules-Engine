import { useId } from "react"
import { draftForField, type DraftValue } from "../drafts"
import type { FieldDecl } from "../model"
import { TypedControl } from "./TypedControl"

export function SchemaFields({
  fields,
  values,
  onChange,
}: {
  fields: FieldDecl[]
  values: Record<string, DraftValue>
  onChange: (values: Record<string, DraftValue>) => void
}) {
  return (
    <div className="stack">
      {fields.map((field, index) => (
        <SchemaField
          key={`${field.name}-${index}`}
          field={field}
          value={values[field.name] ?? draftForField(field)}
          onChange={(next) => onChange({ ...values, [field.name]: next })}
        />
      ))}
    </div>
  )
}

function SchemaField({
  field,
  value,
  onChange,
}: {
  field: FieldDecl
  value: DraftValue
  onChange: (value: DraftValue) => void
}) {
  const id = useId()
  if (value.kind === "object") {
    const nested = field.fields ?? []
    return (
      <fieldset className="nest">
        <legend>
          <span className="mono">{field.name}</span>
          {field.type ? <span className="type-tag">{field.type}</span> : null}
        </legend>
        {nested.length === 0 ? (
          <p className="note">No fields selected.</p>
        ) : (
          <SchemaFields
            fields={nested}
            values={value.children}
            onChange={(children) => onChange({ kind: "object", children })}
          />
        )}
      </fieldset>
    )
  }

  return (
    <div className="field">
      <div className="label-row">
        {value.kind === "boolean" ? (
          <span className="label-text">{field.name}</span>
        ) : (
          <label className="label-text" htmlFor={id}>
            {field.name}
          </label>
        )}
        {field.type ? <span className="type-tag">{field.type}</span> : null}
      </div>
      <TypedControl
        id={id}
        label={field.name}
        typeName={value.kind === "boolean" ? field.type || "boolean" : field.type}
        text={value.kind === "string" || value.kind === "number" ? value.text : ""}
        bool={value.kind === "boolean" ? value.value : false}
        onText={(text) => {
          if (value.kind === "number") onChange({ ...value, text })
          else onChange({ kind: "string", text })
        }}
        onBool={(next) => onChange({ kind: "boolean", value: next })}
      />
    </div>
  )
}
