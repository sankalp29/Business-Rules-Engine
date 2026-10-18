import { Link } from "react-router-dom"
import { Banner } from "../components/Banner"
import { FieldLines } from "../components/FieldLines"
import { PageHead } from "../components/PageHead"
import { useTypes } from "../hooks"

export function TypesPage() {
  const { types, error, loading } = useTypes()
  const count = types?.length ?? 0

  return (
    <div className="stack page">
      <PageHead
        eyebrow="Catalog"
        title="Type"
        lede={types ? `${count} saved ${count === 1 ? "type" : "types"}. A rule names one of these.` : "Facts a rule can name."}
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
        <div className="rule-list">
          {types.map((type) => (
            <article key={type.name} className="sheet">
              <h2>{type.name}</h2>
              <FieldLines fields={type.fields} />
            </article>
          ))}
        </div>
      ) : null}
    </div>
  )
}
