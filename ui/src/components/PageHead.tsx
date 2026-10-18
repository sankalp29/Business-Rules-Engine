import type { ReactNode } from "react"
import { usePageTitle } from "../hooks"

export function PageHead({
  eyebrow,
  title,
  lede,
  action,
}: {
  eyebrow: string
  title: string
  lede: string
  action?: ReactNode
}) {
  usePageTitle(title)
  return (
    <header className={action ? "page-head with-action" : "page-head"}>
      <div className="page-head-copy">
        <p className="eyebrow">{eyebrow}</p>
        <h1>{title}</h1>
        <p className="lede">{lede}</p>
      </div>
      {action ? <div className="page-head-action">{action}</div> : null}
    </header>
  )
}
