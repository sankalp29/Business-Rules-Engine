import { formatWindow } from "../format"
import type { DateOverlap } from "../drafts"

export function OverlapConfirm({
  hits,
  pending,
  confirmLabel,
  onConfirm,
  onCancel,
}: {
  hits: DateOverlap[]
  pending: boolean
  confirmLabel: string
  onConfirm: () => void
  onCancel: () => void
}) {
  return (
    <div className="banner warn" role="alert">
      <p className="banner-title">These dates overlap another version.</p>
      <ul>
        {hits.map((hit) => (
          <li key={`${hit.version}:${hit.from}:${hit.until ?? "open"}`}>
            {hit.version} already runs {formatWindow(hit.from, hit.until)}. Saving adjusts that version so the windows no longer share a minute.
          </li>
        ))}
      </ul>
      <div className="actions">
        <button type="button" className="primary" onClick={onConfirm} disabled={pending}>
          {pending ? "Saving…" : confirmLabel}
        </button>
        <button type="button" className="ghost" onClick={onCancel} disabled={pending}>
          Cancel
        </button>
      </div>
    </div>
  )
}
