import { Link } from "react-router-dom"
import { Banner } from "../components/Banner"
import { FieldLines } from "../components/FieldLines"
import { PageHead } from "../components/PageHead"
import { useTypes } from "../hooks"

export function FieldsPage() {
  const { types, error, loading } = useTypes()
  const ready = types ?? []
  const fieldCount = ready.reduce((sum, type) => sum + (type.fields?.length ?? 0), 0)

  return (
    <div className="stack page">
      <PageHead
        eyebrow="Catalog"
        title="Fields"
        lede={
          types
            ? `${fieldCount} saved ${fieldCount === 1 ? "field" : "fields"} across ${ready.length} ${ready.length === 1 ? "type" : "types"}.`
            : "Fields already saved on each type."
        }
        action={
          ready.length > 0 ? (
            <Link className="primary link-button" to="/fields/new">
              New field
            </Link>
          ) : null
        }
      />
      {error ? <Banner tone="error" title={error.message} details={error.details} /> : null}
      {loading ? <p className="note">Loading fields…</p> : null}
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
        <div className="rule-list">
          {ready.map((type) => (
            <article key={type.name} className="sheet">
              <header className="sheet-head">
                <h2>{type.name}</h2>
                <Link to={`/fields/new?type=${encodeURIComponent(type.name)}`}>Add field</Link>
              </header>
              <FieldLines fields={type.fields} />
            </article>
          ))}
        </div>
      ) : null}
    </div>
  )
}
