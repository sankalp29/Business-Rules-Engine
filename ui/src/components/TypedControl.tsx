import { sanitizeNumber } from "../drafts"
import { controlKind } from "../model"

type Props = {
  id: string
  label: string
  typeName?: string | null
  text: string
  bool: boolean
  onText: (value: string) => void
  onBool: (value: boolean) => void
}

export function TypedControl({ id, label, typeName, text, bool, onText, onBool }: Props) {
  const kind = controlKind(typeName)
  if (kind === "boolean") {
    return (
      <div className="toggle" role="group" aria-label={`${label}: true or false`}>
        <button type="button" aria-pressed={!bool} onClick={() => onBool(false)}>
          False
        </button>
        <button type="button" aria-pressed={bool} onClick={() => onBool(true)}>
          True
        </button>
      </div>
    )
  }
  if (kind === "integer" || kind === "decimal") {
    const integer = kind === "integer"
    return (
      <span className="number-field">
        <input
          id={id}
          className="control"
          inputMode={integer ? "numeric" : "decimal"}
          autoComplete="off"
          spellCheck={false}
          value={text}
          onChange={(event) => onText(sanitizeNumber(event.target.value, integer))}
        />
        <span className="adorn">{typeName}</span>
      </span>
    )
  }
  return (
    <input
      id={id}
      className="control"
      type="text"
      autoComplete="off"
      value={text}
      onChange={(event) => onText(event.target.value)}
    />
  )
}
