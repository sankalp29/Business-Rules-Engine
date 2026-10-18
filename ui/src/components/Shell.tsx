import type { ReactNode } from "react"
import { NavLink } from "react-router-dom"

const links = [
  { to: "/types", label: "Types" },
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
        <p className="spine-note">Types, rules, and the version in effect today.</p>
        <nav className="nav" aria-label="Sections">
          {links.map((link) => (
            <NavLink key={link.to} to={link.to}>
              {link.label}
            </NavLink>
          ))}
        </nav>
        <p className="spine-foot">Rule engine</p>
      </aside>
      <main id="content" className="main">
        <div className="frame">{children}</div>
      </main>
    </div>
  )
}
