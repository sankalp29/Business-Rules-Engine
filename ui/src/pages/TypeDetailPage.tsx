import { useDeferredValue, useState } from "react"
import { Link, useParams } from "react-router-dom"
import { Banner } from "../components/Banner"
import { FieldLines } from "../components/FieldLines"
import { PageHead } from "../components/PageHead"
import { matchingFields } from "../fields"
import { useTypes } from "../hooks"

export function TypeDetailPage() {
  const params = useParams()
  const name = decodeURIComponent(params.name ?? "")
  const { types, error, loading } = useTypes()
  const type = types?.find((item) => item.name === name)
  const [query, setQuery] = useState("")
  const [scopedName, setScopedName] = useState(name)
  if (name !== scopedName) {
    setScopedName(name)
    setQuery("")
  }
  const deferredQuery = useDeferredValue(query)
  const count = type?.fields?.length ?? 0
  const shown = matchingFields(type?.fields, deferredQuery)

  if (error) {
    return (
      <div className="stack page">
        <PageHead eyebrow="Type" title="Type" lede="This type could not be opened." />
        <Banner tone="error" title={error.message} details={error.details} />
        <Link className="back" to="/types">
          All types
        </Link>
      </div>
    )
  }

  if (loading || !types) {
    return (
      <div className="stack page">
        <PageHead eyebrow="Type" title={name || "Type"} lede="Loading this type." />
        <p className="note">Loading type…</p>
      </div>
    )
  }

  if (!type) {
    return (
      <div className="stack page">
        <PageHead eyebrow="Type" title={name || "Type"} lede="This type is not in the catalog." />
        <Banner tone="error" title={`No type named ${name}.`} details={["Open a type from the list."]} />
        <Link className="back" to="/types">
          All types
        </Link>
      </div>
    )
  }

  return (
    <div className="stack page">
      <Link className="back" to="/types">
        All types
      </Link>
      <PageHead
        eyebrow="Type"
        title={type.name}
        lede={type.description || "Fields a rule can read or write on this type."}
        action={
          <Link className="primary link-button" to={`/fields/new?type=${encodeURIComponent(type.name)}`}>
            Add field
          </Link>
        }
      />
      <p className="quiet">
        {count} {count === 1 ? "field" : "fields"}
        {query.trim() && shown.length !== count ? ` · ${shown.length} ${shown.length === 1 ? "match" : "matches"}` : ""}
      </p>
      {count > 0 ? (
        <label className="field filter" htmlFor="field-filter">
          <span className="label-text">Find a field</span>
          <input
            id="field-filter"
            className="control"
            value={query}
            placeholder="Name, type, or description"
            onChange={(event) => setQuery(event.target.value)}
          />
        </label>
      ) : null}
      <section className="sheet">
        {count > 0 && shown.length === 0 ? <p className="note">No field matches that search.</p> : <FieldLines fields={shown} />}
      </section>
    </div>
  )
}
