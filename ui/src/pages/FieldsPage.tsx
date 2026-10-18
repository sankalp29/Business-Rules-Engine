import { Link } from "react-router-dom"
import { Banner } from "../components/Banner"
import { PageHead } from "../components/PageHead"
import { useTypes } from "../hooks"

export function FieldsPage() {
  const { types, error, loading } = useTypes()
  const ready = types ?? []

  return (
    <div className="stack page">
      <PageHead
        eyebrow="Catalog"
        title="Fields"
        lede={types ? "Add a field to a saved type. Open the type to read its fields." : "Fields belong to a type."}
        action={
          ready.length > 0 ? (
            <Link className="primary link-button" to="/fields/new">
              New field
            </Link>
          ) : null
        }
      />
      {error ? <Banner tone="error" title={error.message} details={error.details} /> : null}
      {loading ? <p className="note">Loading types…</p> : null}
      {!loading && !error && ready.length === 0 ? (
        <section className="sheet stack">
          <h2>No types yet</h2>
          <p>Save a type before adding a field to it.</p>
          <Link className="primary link-button" to="/types/new">
            New type
          </Link>
        </section>
      ) : null}
      {ready.length > 0 ? (
        <div className="type-grid">
          {ready.map((type) => (
            <article key={type.name} className="sheet type-card">
              <Link className="type-card-link" to={`/types/${encodeURIComponent(type.name)}`}>
                <h2>{type.name}</h2>
                {type.description ? <p>{type.description}</p> : null}
              </Link>
              <Link className="text-action" to={`/fields/new?type=${encodeURIComponent(type.name)}`}>
                Add field
              </Link>
            </article>
          ))}
        </div>
      ) : null}
    </div>
  )
}
