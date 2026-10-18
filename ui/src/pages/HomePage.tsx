import { Link } from "react-router-dom"
import { usePageTitle } from "../hooks"

export function HomePage() {
  usePageTitle("Home")
  return (
    <div className="home">
      <header className="home-bar">
        <span className="brand-mark">Clause</span>
        <Link className="primary link-button" to="/rules">
          Open the desk
        </Link>
      </header>
      <main className="home-main" id="content">
        <p className="eyebrow">Rule desk</p>
        <h1>The version in effect, and the ones waiting.</h1>
        <p className="lede">
          Write the logic, give each version the dates it runs, and approve it before anything can fire.
        </p>
        <Link className="primary link-button home-cta" to="/rules">
          Open the desk
        </Link>
        <article className="home-rule" aria-label="Example of a rule on the desk">
          <p className="eyebrow">Example</p>
          <p className="home-line">
            <span>When</span>
            <span>a customer is 65 or older</span>
          </p>
          <p className="home-line">
            <span>Then</span>
            <span>the discount is 0.2</span>
          </p>
          <p className="home-status">
            <span className="status-live">Running now</span>
            <span>v2 · Approved</span>
          </p>
        </article>
        <nav className="home-index" aria-label="Desk sections">
          <Link to="/types">
            <strong>Types</strong>
            <span>Facts a rule can name.</span>
          </Link>
          <Link to="/rules">
            <strong>Rules</strong>
            <span>Versions, and the one running now.</span>
          </Link>
          <Link to="/approval">
            <strong>Approval</strong>
            <span>The path from draft to approved.</span>
          </Link>
        </nav>
      </main>
    </div>
  )
}
