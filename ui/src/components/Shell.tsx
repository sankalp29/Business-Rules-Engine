import type { ReactNode } from "react"
import { NavLink } from "react-router-dom"

const links = [
  { to: "/types", label: "Types" },
  { to: "/rules", label: "Rules" },
  { to: "/approval", label: "Approval" },
]

export function Shell({ children }: { children: ReactNode }) {
  return (
    <div className="app">
      <a className="skip" href="#content">
        Skip to content
      </a>
      <aside className="spine">
        <NavLink className="brand" to="/">
          <span className="brand-mark">Clause</span>
          <span className="brand-sub">Rule desk</span>
        </NavLink>
        <p className="nav-label">Workspace</p>
        <nav className="nav" aria-label="Sections">
          {links.map((link) => (
            <NavLink key={link.to} to={link.to}>
              {link.label}
            </NavLink>
          ))}
        </nav>
      </aside>
      <main id="content" className="main">
        <div className="frame">{children}</div>
      </main>
    </div>
  )
}
