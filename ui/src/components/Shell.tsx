import type { ReactNode } from "react"
import { NavLink } from "react-router-dom"

const links = [
  { to: "/types", label: "Type" },
  { to: "/fields", label: "Fields" },
  { to: "/rules", label: "Rules" },
]

export function Shell({ children }: { children: ReactNode }) {
  return (
    <div className="app">
      <a className="skip" href="#content">
        Skip to content
      </a>
      <aside className="spine">
        <div className="brand">
          <span className="brand-mark">Clause</span>
          <span className="brand-sub">Rule desk</span>
        </div>
        <p className="spine-note">Read a saved rule and its versions, or create a type, field, or rule.</p>
        <nav className="nav" aria-label="Sections">
          {links.map((link) => (
            <NavLink key={link.to} to={link.to}>
              {link.label}
            </NavLink>
          ))}
        </nav>
        <p className="spine-foot">Talks to the rule engine</p>
      </aside>
      <main id="content" className="main">
        <div className="frame">{children}</div>
      </main>
    </div>
  )
}
