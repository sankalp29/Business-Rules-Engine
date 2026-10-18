import type { FieldDecl } from "../model"

export function FieldLines({ fields }: { fields: FieldDecl[] | null | undefined }) {
  if (!fields || fields.length === 0) return <p className="note">No fields yet.</p>
  return (
    <ul className="field-lines">
      {fields.map((field, index) => (
        <li key={`${field.name}-${index}`}>
          <div className="field-summary">
            <span className="line-name">
              <span className="mono">{field.name}</span>
              {field.type ? <span className="type-tag">{field.type}</span> : <span className="type-tag">object</span>}
            </span>
            {field.description ? <p className="field-desc">{field.description}</p> : null}
          </div>
          {field.fields && field.fields.length > 0 ? <FieldLines fields={field.fields} /> : null}
        </li>
      ))}
    </ul>
  )
}
