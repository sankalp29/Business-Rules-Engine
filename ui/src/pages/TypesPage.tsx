import { useState } from "react"
import { Link } from "react-router-dom"
import { Banner } from "../components/Banner"
import { PageHead } from "../components/PageHead"
import { useTypes } from "../hooks"

export function TypesPage() {
  const { types, error, loading } = useTypes()
  const [query, setQuery] = useState("")
  const count = types?.length ?? 0
  const shown = (types ?? []).filter((type) => {
    const haystack = [type.name, type.description ?? ""].join(" ").toLowerCase()
    return haystack.includes(query.trim().toLowerCase())
  })

  return (
    <div className="stack page">
      <PageHead
        eyebrow="Catalog"
        title="Types"
        lede={types ? `${count} saved ${count === 1 ? "type" : "types"}. Open one to see its fields.` : "Facts a rule can name."}
        action={
          <Link className="primary link-button" to="/types/new">
            New type
          </Link>
        }
      />
      {error ? <Banner tone="error" title={error.message} details={error.details} /> : null}
      {loading ? <p className="note">Loading types…</p> : null}
      {types && types.length === 0 ? (
        <section className="sheet stack">
          <h2>No types yet</h2>
          <p>Save a type before a field or a rule can use it.</p>
        </section>
      ) : null}
      {types && types.length > 0 ? (
        <>
          <label className="field filter" htmlFor="type-filter">
            <span className="label-text">Find</span>
            <input
              id="type-filter"
              className="control"
              value={query}
              placeholder="Name or description"
              onChange={(event) => setQuery(event.target.value)}
            />
          </label>
          {shown.length === 0 ? <p className="note">No type matches that search.</p> : null}
          <div className="type-grid">
            {shown.map((type) => (
              <Link key={type.name} className="sheet type-card" to={`/types/${encodeURIComponent(type.name)}`}>
                <h2>{type.name}</h2>
                {type.description ? <p>{type.description}</p> : null}
              </Link>
            ))}
          </div>
        </>
      ) : null}
    </div>
  )
}
